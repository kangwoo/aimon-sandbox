# Record — aimon-sandbox#34: policy-hook execs under a separate uid (core EE-113)

Status: **record, not implemented.** This page fixes what is known and what is still blocked, so that the work can start
from here once the blockers clear. Nothing in this repository changes behaviour because of it.

Base: sandbox `main` `4255a44`. Core `main` `c8cbfc22`: EE-113 is open, and the SPI is proposed in
[kangwoo/aimon-core#287](https://github.com/kangwoo/aimon-core/issues/287). OpenSandbox
([opensandbox-group/OpenSandbox](https://github.com/opensandbox-group/OpenSandbox), formerly `alibaba/OpenSandbox`)
was read at HEAD `0548e18f` (2026-10-19) and at the tags `docker/execd/v1.1.0`, `release-1.1.1-rc.1`, `release-1.1.1` and
`release-1.2.0-rc.1`. The sandbox's docker test tier pins `execd:v1.1.0` (`OpenSandboxTestServer`). The K8s chart
`1.1.1-rc.1` defaults to `execd:release-1.1.1-rc.1`. OpenSandbox paths below are under `components/execd/` unless stated
otherwise.

Notation follows core's design §7: **A** is the agent's uid (the image's `sandbox`, 1000), and **H** is the hook
identity.

---

## 1. What changed since #34 was filed

EE-113 named two triggers. The second, *OpenSandbox's files API gains a uid argument*, has happened:

- **Files API as a uid** — `/v1/filesystem/{uid}/{gid}/files|directories/...`.
  - Source: `pkg/web/router.go:56`, `pkg/web/filesystem_identity*.go`, `pkg/runtime/filesystem_worker_linux.go:33-108`.
  - Merged in opensandbox-group/OpenSandbox#2070 for #1843. SDK support came in #2093 and #2094.
  - Ships in `release-1.1.1` and `release-1.2.0-rc.1`. It is **not** in `release-1.1.1-rc.1` or `v1.1.0`.
  - How it works: each request re-execs execd as a worker with that `Credential`. The worker gets the account's
    supplementary groups and `HOME`, a scrubbed environment and cwd `/`. It refuses to start if the non-root worker
    still holds capabilities.
- **`/command` took a uid already.** `RunCommandRequest.Uid`/`Gid` are `*uint32` (`pkg/web/model/codeinterpreting.go:58-59`),
  in `v1.1.0` too. There is no user-name field, and a gid without a uid is refused (`:112-114`).
  - `buildCredential` (`pkg/runtime/command.go:104-145`) sets `SysProcAttr{Setpgid, Credential}` (`:245-248`; background
    commands at `:404`).
  - With a Credential set, Go always calls `setgroups`, so execd needs `CAP_SETUID` **and** `CAP_SETGID`.
  - A non-root execd gets `fork/exec …: operation not permitted`. It does not ignore the argument.
  - `release-1.1.0` and later skip the switch when the requested identity is execd's own (#1809, for #1802). The
    `docker/execd/v1.1.0` tag predates that fix.

What this unblocks is §7 condition 4 of core's design, the file-tool owner mismatch of `workspace-sandbox.md` §13.3.
The model's file tools can act as A and run-directory uploads as H, with no rewrite of the file tools into execs.

The first trigger (an operator who runs `hooks.json` guards against an untrusted agent in a sandbox) has not happened.
The work stays parked until it does, or until the maintainer decides to do it ahead of demand.

## 2. Target topology

| | Today | With a hook identity |
| --- | --- | --- |
| execd | uid 1000, the container's `runAsUser` | **root**, `no_new_privs`, every capability dropped except `SETUID` · `SETGID` |
| Model's commands (`SandboxShell` foreground/background, seed, staging) | execd's uid, no `uid` argument | `uid=A, gid=A` on every `/command` call |
| Model's file tools (`OpenSandboxFiles`) | `/files/*`, `/directories/*` as execd | `/v1/filesystem/A/A/...` |
| `hooks.json` execs (`isIsolated` + the core flag) | as the model | `uid=H, gid=H` |
| Run directory (`RUN_DIRECTORY`) | `mktemp -d` under `/workspace/.aimon-shell/.run.*`, as A | `mktemp -d` under the H-owned directory (§4), as H; uploads through `/v1/filesystem/H/H/...` into it |
| Skill hooks (`isHook`, not isolated) | as the model | unchanged: they run as A, from the session's state (R4) |

Why execd must be root: a non-root execd gets capabilities only through file or ambient capabilities, and the
container's `no_new_privs` disables file capabilities. Container-level `no_new_privs` does not stop a holder of
`CAP_SETUID` from dropping to a lower uid. After the drop the child has no capabilities. Both points are inferred from
Linux semantics and **not tested** against OpenSandbox.

The uid and gid must always be sent together. If the uid is missing from `/etc/passwd` and no gid is given, execd
gives the child **gid 0** (`buildCredential`). `/command` also sets no `HOME` or `USER`: the child inherits execd's
environment overlaid with the request's `envs`. The wrapper must therefore set `HOME`, `USER` and `LOGNAME` itself, or
the model's shell starts with root's `HOME`.

## 3. Core's conditions against this topology

| # | Condition (core design §7) | Status |
| --- | --- | --- |
| 1 | A is not root and cannot become H or root | **Open — the main blocker.** A runs as 1000 with no capabilities after the drop, but A's processes share the container's network namespace with a root execd, and execd's API runs commands as any uid, root included. The boundary therefore rests on execd's access token, which A must not be able to read (§5 Q1). setuid binaries that reach H or root (`sudo`, `su`) must be absent from the image — a seed check (§4). |
| 2 | Hook execs run as H ≠ A | Available: `/command` `uid`/`gid`, with execd as root (§2). |
| 3 | Hook and run files, and their parents, H-owned and not A-writable | Image contract (§4). The run directory moves out of `/workspace`. |
| 4 | File tools as A, run-directory uploads as H | Available from `release-1.1.1` (§1). Also raises the execd floor for the K8s chart, which defaults to `release-1.1.1-rc.1` (§5 Q4). |
| 5 | H's processes unreachable from A; hook output goes to execd | Holds by the uid difference: no `ptrace`, signals, `/proc/<pid>/fd` or `environ` without `CAP_SYS_PTRACE`/`CAP_KILL`. Output already goes to the exec's own streams since #33. |
| 6 | The image provides H, the H-owned directory, `sha256sum` and `base64`, and the seed checks them | Image contract and seed draft (§4). |
| 7 | Local provider | Core-side only; not ours. |
| 8 | A policy never trusts workspace files | Documentation, unchanged. |

## 4. Image contract and seed checks (draft)

This adds to `workspace-sandbox.md` §13.3. It applies only to a profile that declares a hook identity, so existing
images stay valid.

- A user **H** with its own uid and primary group. The name is declared by the profile; the suggestion is
  `aimon-hook`. Its uid must not be 0, and must not be A's.
- `/opt/aimon` owned by root, mode `0755`. `/opt/aimon/hooks` owned by H, mode `0700`. No parent of either may be
  writable by A.
- No `sudo`, `su` or other setuid/setgid binary reachable by A that can lead to H or root. Exactly which binaries to
  allow (`passwd`, `mount` and so on) is §5 Q3.
- `sha256sum` and `base64` in `/usr/bin`, as today.

The seed would run its checks **as A** (with `uid=A` on its exec), so that "A cannot write there" is tested by A itself.
The following script is a sketch: names are illustrative, and `fail` and the variables follow `SandboxSeeder`'s style.

```sh
hook_user='aimon-hook'                       # quoted from the profile, like declared_platform
[ "$(id -u)" = 1000 ] || fail hook-identity "the seed must run as uid 1000 (it runs as $(id -u))"
h=$(id -u "$hook_user" 2>/dev/null) || fail hook-identity "the image lacks the user $hook_user"
[ "$h" != 0 ] && [ "$h" != 1000 ] || fail hook-identity "$hook_user must not be uid 0 or 1000"
for d in /opt/aimon/hooks /opt/aimon /opt /; do
  [ ! -w "$d" ] || fail hook-identity "$d is writable by the agent"
done
[ "$(stat -c %u /opt/aimon/hooks)" = "$h" ] || fail hook-identity "/opt/aimon/hooks is not owned by $hook_user"
s=$(find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null | head -n 20)
[ -z "$s" ] || fail hook-identity "setuid/setgid binaries present: $s"    # allow-list: §5 Q3
```

A second check runs **as H**, through one exec with `uid=H`. It confirms that the switch works at all: `id -u` prints
H, `/opt/aimon/hooks` is writable, and a `mktemp -d` there succeeds. A failure there means execd cannot switch
identity. It is a permanent seed failure in a new step, `hook-identity`.

The seed's present root check (`id -u` ≠ 0, skipped when `HARDENED_SECURITY_CONTEXT` is waived) keeps its meaning,
because it now runs as A. The K8s hardening it stands for changes, though. The container no longer runs as
`runAsUser: 1000` with every capability dropped: it runs as root, keeps `SETUID`/`SETGID`, and keeps `no_new_privs`.
How `HARDENED_SECURITY_CONTEXT` is declared and checked for such a profile is §5 Q2.

## 5. Open questions

- **Q1 — execd's API is reachable from A.** This decides whether a boundary exists at all, and nothing on the sandbox
  side can answer it.
  - execd listens on every interface. A shares its network namespace, so `curl localhost:44772/command` with `uid=0`
    is a root shell unless the token check stops it.
  - The spike found that the Docker runtime did not check the token in `v1.1.0` (`opensandbox-spike.md` §4, item 5).
  - At HEAD, `accessTokenMiddleware` (`pkg/web/router.go:190-231`) checks a token hash delivered through
    `/internal/init`, and falls back to `EXECD_ACCESS_TOKEN`. When neither is set, every request passes. The same
    variable is stripped from the environment of user processes (`pkg/runtime/env.go:288-321`,
    `pkg/isolation/config.go:56-64`).
  - To verify: that both runtimes always deliver a token; that the token never appears on execd's command line
    (`/proc/<pid>/cmdline` is world-readable — the `--access-token` flag exists, `pkg/flag/parser.go:71`); and that
    `/internal/init` cannot be re-run by A to replace the binding.
  - If any of these fails, condition 1 cannot hold on OpenSandbox. The question then goes upstream, for example a Unix
    socket only root can open, or a privileged helper in OSEP-0018's launcher.
- **Q2 — `HARDENED_SECURITY_CONTEXT` for a root execd.** Today it means `runAsUser: 1000`, `runAsNonRoot` and every
  capability dropped, checked by the seed's `id -u`. With a hook identity it would mean: every exec carries `uid=A` or
  `uid=H`; the container keeps only `SETUID`/`SETGID`; `no_new_privs` is set; there is no service-account token. One
  option is a separate capability, so that the two shapes are not confused. Another is OSEP-0018's hardening floor
  (`pkg/runtime/hardening_linux.go`): its launcher trims capabilities, sets `no_new_privs`, and applies Landlock and
  seccomp per launch. Its default target is execd's own uid (`:411`), so it would still need the per-request uid
  (`:529-541`).
- **Q3 — setuid allow-list.** Whether the seed refuses every setuid/setgid binary, or allows a fixed list that cannot
  reach H or root. The full `find` costs seconds on a large image; it could run only at the first provisioning of an
  image digest.
- **Q4 — version floor.** Identity file routes need execd `release-1.1.1`. The docker tier pins `v1.1.0` and the chart
  defaults to `release-1.1.1-rc.1`. The provider would advertise the feature only after probing the route (a `404`
  means unsupported), not from a version string.
- **Q5 — existing workspaces.** `/workspace` stays A-owned, so nothing there moves. The old run-directory location
  (`/workspace/.aimon-shell/.run.*`) stays in use for skill hooks and for profiles without a hook identity.
- **Q6 — run-directory digests.** When uploads go as H straight into an H-only directory, the digest check of
  `RUN_DIRECTORY` item 3 is no longer needed against A. The suggestion is to keep it: it costs one `sha256sum` and
  guards against a mistaken route.

## 6. What the implementation would touch

These are notes for whoever picks the work up, not a plan.

- `SandboxProfile`: an optional hook identity (user name), which enters `contentHash`. `SandboxSeeder`: the checks of §4
  when it is declared.
- Provider SPI: `ExecSpec` gains a uid/gid. `ProviderCapabilities` gains "commands and files as a given uid", probed as
  in Q4. `OpenSandboxRunningCommand` sends `uid`/`gid`. `OpenSandboxFiles` takes a base path per identity.
- `SandboxShell`: declares core's `ShellFeature.HOOK_IDENTITY` (core#287) only when the profile declares an identity,
  the seed passed both checks, and the provider supports identity routes. With the flag set, it runs as H, places the run
  directory under `/opt/aimon/hooks`, and uploads as H. Every other command runs as A, with `HOME`/`USER`/`LOGNAME` set
  by the wrapper.
- Docs: `workspace-sandbox.md` §6.1 (the uid sentence), §12.1, §13.3 (the contract) and §16 (tests). Tests against a
  real execd in the docker tier must cover: A cannot write the hook directory; A cannot signal or read
  `/proc/<pid>/fd` of a running hook; a run-directory upload as H; and Q1's token checks.

## Related

- Core design: `docs/design/tool/execution-environment-ee109-hook-tamper-narrowing.md` §7, and EE-113 in
  `docs/backlog/execution-environment-open-items.md` (aimon-core).
- [`workspace-sandbox-run-directory.md`](workspace-sandbox-run-directory.md) — the narrowing (#33) this would turn into
  a boundary.
- [`opensandbox-spike.md`](opensandbox-spike.md) — execd's uid and token behaviour at `v1.1.0`.
