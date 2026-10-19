# Record — aimon-sandbox#34: policy-hook execs under a separate uid (core EE-113)

Status: **record, not implemented.** This page fixes what is known and what is still blocked, so that the work can start
from here once the blockers clear. The main blocker is now upstream, not here: on OpenSandbox as shipped the agent's
uid can reach execd's root API, so core §7 condition 1 does not hold until OpenSandbox changes (§5 Q1). Nothing in this
repository changes behaviour because of this page.

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
| execd | uid 1000, the container's `runAsUser` | **root**, `no_new_privs`, every capability dropped except `SETUID` · `SETGID` · `KILL` |
| Model's commands (`SandboxShell` foreground/background, seed, staging) | execd's uid, no `uid` argument | `uid=A, gid=A` on every `/command` call |
| Model's file tools (`OpenSandboxFiles`) | `/files/*`, `/directories/*` as execd | `/v1/filesystem/A/A/...` |
| `hooks.json` execs (`isIsolated` + the core flag) | as the model | `uid=H, gid=H` |
| Run directory (`RUN_DIRECTORY`) | `mktemp -d` under `/workspace/.aimon-shell/.run.*`, as A | `mktemp -d` under the H-owned directory (§4), as H; uploads through `/v1/filesystem/H/H/...` into it |
| Skill hooks (`isHook`, not isolated) | as the model | unchanged: they run as A, from the session's state (core R4) |

Why execd must be root: a non-root execd gets capabilities only through file or ambient capabilities, and the
container's `no_new_privs` disables file capabilities. Ambient capabilities would work in principle, but neither a K8s
`securityContext` nor Docker's `--cap-add` sets them: for a non-root container the added capabilities land in the
bounding and permitted sets, and execve clears the effective set (kubernetes/kubernetes#56374; read, not checked in a
local run). Root is therefore what the deployment tooling can express, not what execd needs: the need is `SETUID`,
`SETGID` and `KILL`. A non-root execd holding them would not be safer either, since `CAP_SETUID` lets it `setuid(0)`;
what decides the boundary is who can reach its API (§5 Q1), not execd's own uid.

Container-level `no_new_privs` does not stop a holder of `CAP_SETUID` from dropping to a lower uid, and after the drop
the child has no capabilities — confirmed in a local run (a `/command` child with `uid=A` reported `CapEff: 0` and `NoNewPrivs: 1`). `CAP_KILL` is in the set because execd
stops a command's process group with `kill(-pgid)` (`command.go:319,328,480`, `bash_session.go:405,631`) and also
signals the filesystem-identity workers; a root execd signalling an A- or H-owned process group needs it, or timeouts
and `kill()` fail with `EPERM`.

The uid and gid must always be sent together. If the uid is missing from `/etc/passwd` and no gid is given, execd
gives the child **gid 0** (`buildCredential`). `gid` is a group id, not a repeat of the uid number: it must be the
account's own primary group (`id -g`). `/command` also sets no `HOME` or `USER`: the child inherits execd's
environment overlaid with the request's `envs` (`command_argv.go:52-66`; confirmed in a local run, where a `/command`
child's `HOME` was `/root`). The wrapper must therefore set `HOME`, `USER` and `LOGNAME` itself, or the model's shell
starts with root's `HOME`.

## 3. Core's conditions against this topology

| # | Condition (core design §7) | Status |
| --- | --- | --- |
| 1 | A is not root and cannot become H or root | **Does not hold on OpenSandbox today — the blocker (§5 Q1).** A runs as 1000 with no capabilities after the drop, but it shares the container's network namespace with a root execd whose API runs commands as any uid, and that API is reachable from A over localhost. Two unauthenticated paths to root were confirmed by reading HEAD and in a local run: `/internal/init` and (with the hardening launcher off) the env-delivered token (§5 Q1). Both need an upstream fix. Core §7 writes this as `runAsNonRoot`; this topology cannot satisfy that at pod level (execd is root), so the condition is restated per process (every exec carries `uid=A`/`uid=H`, and nothing reachable by A escalates). `no_new_privs` already makes any setuid/setgid binary inert on exec, so the seed's setuid scan (§4) is defence in depth, not the barrier. |
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
  `aimon-hook`. Its uid must not be 0, and must not be A's, and A must not be a member of H's primary group (or A could
  write H's files whatever the owner is).
- `/opt/aimon` owned by root, mode `0755`. `/opt/aimon/hooks` owned by H, mode `0700`. No parent of either may be
  writable by A.
- Defence in depth: no `sudo`, `su` or other setuid/setgid binary reachable by A that can lead to H or root. Because
  the topology keeps `no_new_privs`, execve already ignores setuid/setgid bits, so this is a second line, not the
  barrier. Whether the seed refuses every such binary or allows a fixed harmless list is §5 Q3.
- `sha256sum` and `base64` in `/usr/bin`, as today.

The seed would run its checks **as A** (with `uid=A` on its exec), so that "A cannot write there" is tested by A itself.
The following script is a sketch: `1000` is A's uid from the image contract, the names are illustrative, and `fail`
and the variables follow `SandboxSeeder`'s style.

```sh
hook_user='aimon-hook'                       # quoted from the profile, like declared_platform
grep -q '^NoNewPrivs:[[:space:]]*1' /proc/self/status || fail hook-identity "no_new_privs is not set"
[ "$(id -u)" = 1000 ] || fail hook-identity "the seed must run as uid 1000 (it runs as $(id -u))"
h=$(id -u "$hook_user" 2>/dev/null) || fail hook-identity "the image lacks the user $hook_user"
[ "$h" != 0 ] && [ "$h" != 1000 ] || fail hook-identity "$hook_user must not be uid 0 or 1000"
id -G "$hook_user" | tr ' ' '\n' | grep -qx "$(id -g)" && \
  fail hook-identity "the agent is a member of $hook_user's group"
[ -d /opt/aimon/hooks ] || fail hook-identity "/opt/aimon/hooks is missing"
for d in /opt/aimon/hooks /opt/aimon /opt /; do
  [ ! -w "$d" ] || fail hook-identity "$d is writable by the agent"
done
[ "$(stat -c %u /opt/aimon)" = 0 ] || fail hook-identity "/opt/aimon is not owned by root"
[ "$(stat -c %u /opt/aimon/hooks)" = "$h" ] || fail hook-identity "/opt/aimon/hooks is not owned by $hook_user"
# Defence in depth (NNP already makes these inert, §5 Q3); -xdev/2>/dev/null hide other mounts and unreadable dirs.
s=$(find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null | head -n 20 | tr '\n' ' ')
[ -z "$s" ] || fail hook-identity "setuid/setgid binaries present: $s"
```

A second check runs **as H**, through one exec with `uid=H gid=$(id -g aimon-hook)`. It confirms that the switch works
at all: `id -u` prints H, `/opt/aimon/hooks` is writable, and a `mktemp -d` there succeeds. A failure there means execd
cannot switch identity. It is a permanent seed failure in a new step, `hook-identity`.

The seed's present root check (`id -u` ≠ 0, skipped when `HARDENED_SECURITY_CONTEXT` is waived) no longer detects
anything once the seed's own exec carries `uid=A`: `id -u` is then always 1000. For this topology the meaningful checks
are the `NoNewPrivs` line and the capability bounding set read **as A** (`CapBnd`/`CapEff` in `/proc/self/status` —
A must hold none), which replace it. The K8s hardening the old check stood for also changes: the container no longer
runs as `runAsUser: 1000` with every capability dropped; it runs as root, keeps `SETUID`/`SETGID`/`KILL`, and keeps
`no_new_privs`. How `HARDENED_SECURITY_CONTEXT` is declared and checked for such a profile is §5 Q2.

## 5. Open questions

- **Q1 — execd's API is reachable from A, and that defeats the boundary on OpenSandbox today.** This decides whether a
  boundary exists at all, and the sandbox side cannot fix it. execd listens on `:44772` on every interface and A shares
  its network namespace, so a request to `localhost` naming `uid=0` runs as root unless the token check stops it. Two
  ways past the token were confirmed by reading HEAD (`0548e18f`) and in a local run of `execd:release-1.1.1` as root:
  - **`/internal/init` is unauthenticated and re-runnable.** It is in `preInitPaths` (`pkg/web/router.go:36-40`), which
    `accessTokenMiddleware` skips (`:197`), and the manager is installed unconditionally (`main.go:190`). `Apply` has
    no generation or "already initialised" guard; with `preserveRuntimeState:true` it swaps in the caller's
    `accessTokenHash` and marks ready (`init_runtime.go:218-227`, `binding.go`). So A can replace the binding with a
    token hash it chose, and a full (non-preserving) init also runs the request's `lifecycle` preStart as root execd.
    This path is present from `release-1.1.0` on. A local run reproduced the binding swap; no exploit steps are
    recorded here.
  - **`/command` children inherit the env-delivered token when the hardening launcher is off.** The blacklist that
    strips `EXECD_ACCESS_TOKEN` (`pkg/runtime/env.go:288-321`) is used only for PTY, session and lifecycle processes
    (`bash_session.go`), not for `/command`, which builds its child environment from an unfiltered `os.Environ()`
    (`command_argv.go:52-66`). It is scrubbed for `/command` only under the OSEP-0018 hardening launcher
    (`hardening_linux.go`), and `[hardening] enabled` defaults to off. So where the operator delivers the token through
    `EXECD_ACCESS_TOKEN` (rather than only an init token hash), A's own commands read it. A local run confirmed a
    `/command` child saw `EXECD_ACCESS_TOKEN`.
  - Related surface for the same threat list: `/pty`, `/session` and `/code` take **no** uid argument, so they always
    run as root execd — only `/command` and `/v1/isolated` carry `uid`/`gid`. The egress sidecar (`components/egress/`)
    shares the network namespace and exposes its own control surface, whose authentication must be checked before
    relying on it. A Jupyter kernel, if the image runs one, is reachable over localhost. Upstream's own docs say the
    filesystem-identity routes "do not provide … a tenant isolation boundary" and that API credentials belong in the
    trusted backend (`docs/architecture/data-plane/execd.md:89`, at the OpenSandbox repo root) — the same caution
    applies to running hooks as H.
  - **Conclusion:** condition 1 cannot hold on OpenSandbox as shipped. A real boundary needs an upstream change — for
    example `/internal/init` accepted once or only from the control plane, a token never placed in execd's environment,
    and execd's API bound to a path only root can open (a Unix socket, or the OSEP-0018 launcher as a privileged
    helper). These are OpenSandbox-side and should be raised with upstream through its private security-advisory
    channel (`SECURITY.md`), not a public issue.
- **Q2 — `HARDENED_SECURITY_CONTEXT` for a root execd.** Today it means `runAsUser: 1000`, `runAsNonRoot` and every
  capability dropped, checked by the seed's `id -u`. With a hook identity it would mean: every exec carries `uid=A` or
  `uid=H`; the container keeps only `SETUID`/`SETGID`; `no_new_privs` is set; there is no service-account token. One
  option is a separate capability, so that the two shapes are not confused. Another is OSEP-0018's hardening floor
  (`pkg/runtime/hardening_linux.go`): its launcher trims capabilities, sets `no_new_privs`, and applies Landlock and
  seccomp per launch. Its default target is execd's own uid (`:411`) but it maps a request's `Credential` into the
  launcher policy (`:528-539`), so a `/command` `uid` already works under it — and, per Q1, turning it on also scrubs
  the token from `/command` children. Enabling the floor is therefore part of the answer, not a conflict with it.
- **Q3 — setuid handling.** With `no_new_privs` set, execve ignores setuid/setgid bits, so the scan is defence in
  depth. The seed can keep the cheap `/proc/self/status` checks (`NoNewPrivs`, `CapBnd`/`CapEff` as A, §4) as the real
  test and either skip the `find` or run it only at the first provisioning of an image digest (it costs seconds on a
  large image).
- **Q4 — version floor.** Identity file routes need execd `release-1.1.1`. The docker tier pins `v1.1.0` and the chart
  defaults to `release-1.1.1-rc.1`. The provider would advertise the feature only after probing the route (a `404`
  means unsupported), not from a version string.
- **Q5 — existing workspaces.** `/workspace` stays A-owned, so nothing there moves. The old run-directory location
  (`/workspace/.aimon-shell/.run.*`) stays in use for skill hooks and for profiles without a hook identity.
- **Q6 — run-directory digests.** When uploads go as H straight into an H-only directory, the digest check of core's
  `ShellFeature.RUN_DIRECTORY` step 3 is no longer needed against A. The suggestion is to keep it: it costs one
  `sha256sum` and guards against a mistaken route.

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
