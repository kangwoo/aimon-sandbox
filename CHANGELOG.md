# Changelog

All notable aimon-sandbox changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

Requires aimon-core **0.3.4** (pinned to `0.3.4-SNAPSHOT` until core releases it; see below) — 0.3.3 for the seams of
[`docs/design/workspace-sandbox-core-033.md`](docs/design/workspace-sandbox-core-033.md), 0.3.4 for streaming and the
sandbox SPI of core's Python `ExecuteCode` shell engine. `aimon-sandbox` exposes core as an `api` dependency, so
upgrading from 0.1.0 also raises core from 0.3.1 to 0.3.3, which core itself declares incompatible with 0.3.2 — upgrade
it as you would across a minor: the auto-compact buffer and ratio members are gone (kangwoo/aimon-core#285), and the
session log is written as version 2 by default, so a mixed-version cluster must pin `v1` (kangwoo/aimon-core#286). See
core's [0.3.3 release notes](https://github.com/kangwoo/aimon-core/releases/tag/v0.3.3).

### Changed: OpenSandbox 1.2.0

`aimon-sandbox-opensandbox` now requires **OpenSandbox 1.2.0** — server, execd and egress sidecar
([release notes](https://github.com/opensandbox-group/OpenSandbox/releases/tag/release-1.2.0)). The docker tier pins the three
`release-1.2.0` images by digest, and the k8s tier renders the chart and images from the `release-1.2.0` tag (it used
`release-1.1.0` / execd `v1.1.0` / egress `v1.1.7` and main `3738975` / `release-1.1.1-rc.1`).

- **Command output is exact (behaviour change).** execd 1.1.1 and later send each output line with its own ending
  (upstream #2033), and the provider now passes it through instead of appending `\n` to every event: a `\r` progress
  line keeps its `\r`, `\r\n` stays `\r\n`, and a last line the command did not end has no `\n` added (`printf abc` is
  `abc`, not `abc\n`). Output of older execd, which strips the endings, is still recognised from its events and gets
  its `\n` back, but its first line is then handed to the `OutputSink` only once the next event arrives. (The execd
  image is configured apart from the server — `execd_image`, a pool's pre-warmed installer — so a 1.2.0 server can
  still drive an old execd; passing its stripped lines through would glue them together.)
- **Exit codes come from `error.exit_code`** when execd sends it (1.2.0, upstream #2303), `evalue` otherwise.
- **The credential vault is reached through the lifecycle server** (`/v1/sandboxes/{id}/credential-vault`, new in
  1.2.0, upstream #2263), not the egress sidecar's port 18080 with its `OPENSANDBOX-EGRESS-AUTH` header. With
  `useServerProxy(false)` the AIMON pods now need to reach only execd (44772) — the NetworkPolicy example in
  `docs/guide/opensandbox-kubernetes.md` §6.1 drops 18080. **This is why 1.2.0 is required:** a 1.1.x server has no
  such route, and a create with credentials fails on it — permanently, saying the server is older than 1.2.0. Vault
  reads and deletes are retried when the server proxy answers 502/503/504 (the sidecar not up yet), as the direct calls
  to the sidecar were retried after a failed connect.
- **Transient command spools are removed from `/tmp/opensandbox-execd/` only.** The second place, `/tmp` itself, was for
  execd before `release-1.1.0`.

Verified on both tiers against 1.2.0: the docker tier (three `OpenSandboxWorkspaceIT` expectations moved from
line-normalized to exact output, and the spool check's negative control is now a marker file in execd's spool
directory, since execd removes a finished command's spool itself) and the k8s tier in template and pool mode on kind
v1.37, the credential checks included.

Behaviour changes upstream that the provider meets as they are, noted for operators: a deleted Kubernetes sandbox now
stays listed as `Stopping` (read as TERMINATED) until its pod is gone (upstream #2154), and BatchSandbox pods default
to `restartPolicy: Never` (the hardened template already set it).

### Added: the sandbox half of core's Python `ExecuteCode` shell engine (D2)

Core 0.3.4's shell engine runs model-written Python inside the sandbox. A workspace sandbox now offers what it needs, so
core can offer `ExecuteCode` there when the app enables programmatic tools and the profile declares `python3`. Design
and departures: [`docs/design/ptc-shell-provider.md`](docs/design/ptc-shell-provider.md); summary in
[`docs/design/ptc-shell-prerequisites.md`](docs/design/ptc-shell-prerequisites.md) §6–§12.

- **`SandboxProfile.runtimes(Set<String>)` (S3)**, empty by default; only `python3` is accepted, and startup refuses any
  other name. A declared `python3` is checked by the seed (new permanent step `runtime-python3`: on `PATH`, 3.8 or
  later, `python3 -I` imports the standard-library modules core's runtime imports) and carried by the descriptor. A
  profile that declares none keeps its `contentHash`. The image contract gains the optional `python3` item
  (`docs/design/workspace-sandbox.md` §13.3); the docker tier's image installs `python3`.
- **Secret run files are never inlined (S4).** A `RunDirectory` with any file marked secret is uploaded whole — the
  manifest and the blob, two uploads — however small, so the secret is on no process's command line. **Behaviour
  change:** every `ExecuteCode` run (core marks its key secret) takes the upload path, and a small secret set can now
  fail with "could not pass … into the sandbox" where it used to be inlined.
- **`SandboxExecutionEnvironment.runtimeFileSystem()` (S5)** is present: core's framework-only file access, confined to
  `/workspace/**`, outside the file tools' path rules. `writeNew` is the files API's create-new (atomic, refuses
  anything existing, a dangling link included), `read` and `delete` never follow a link at the path (one in-sandbox
  helper exec each), every failure — connecting included — is an `IOException`, a failed existence check behind
  `writeNew` too (it is never taken for "nothing there"). Asking for it provisions nothing.
- **The descriptor declares `isolation() = SANDBOXED` and the profile's `runtimes()` (S6)**, for forks too.
- **The provider says why it ended a command (S8).** New provider SPI `ExecKillCause` and `ExecOutcome.killCause()`
  (never together with `timedOut()`); the OpenSandbox provider sets `LINE_LIMIT_EXCEEDED` when it ends a command for an
  event line over 256 KiB, unless a timeout's or caller's kill was already on its way. **Behaviour change:** an isolated
  command that is not a hook's and is ended that way now returns a result — exit 137, truncated,
  `killCause() = LINE_LIMIT_EXCEEDED`, which core's engine reports as a protocol violation — instead of throwing. Hook
  and monitor runs still throw; the message now names the cause.
- **`{root}/.aimon-ptc/` is hidden from the file tools too (S9 follow-up)**: core places the engine's directory under
  the descriptor's working directory, which is the binding root (`/workspace/repo` by default), not `/workspace`.
- **`RuntimeFileSystemContract`** in `aimon-sandbox-testkit`: the runtime file system's contract suite, which core does
  not publish. The local provider and the docker tier run it, and core's engine contract suite
  (`aimon-code-engine-testkit`, test-only) runs on both tiers.
- **A timed-out, cancelled or limit-stopped `ExecuteCode` run leaves nothing running in the sandbox** — with the
  `0.3.4-SNAPSHOT` that carries aimon-core#336 (Q-C4: core's runtime used to leave the exec's process group, so the
  sandbox's kill missed it). No behaviour change here: main source changed only in javadoc (`SandboxShell`,
  `SandboxExecutionEnvironment`, `OpenSandboxRunningCommand`), the rest is tests. Both tiers now run the whole engine
  contract suite, and the docker tier checks that no runtime, child or zombie is left (`docs/design/ptc-shell-provider.md`
  §9 Q-C4).
- **The shell engine's checks, verified on Kubernetes in template and pool mode** (design and departures:
  [`docs/design/ptc-shell-verification.md`](docs/design/ptc-shell-verification.md); results:
  [`docs/design/ptc-shell-provider.md`](docs/design/ptc-shell-provider.md) §9 "K8s tier, measured"). Tests and scripts
  only; no behaviour change.
  - Every engine test on a sandbox is followed by a leftover check against a snapshot taken before it, so a runtime,
    child or zombie fails the test that left it, by name, and a zombie is shown with the parent that did not reap it.
    The timed-out test also checks, from inside the run, that the runtime led its own group and had a watchdog.
  - `LeftoverCheckNegativeControlIT` (docker tier) shows that check turns red for an escaped child and an unreaped
    zombie. It does not show a pre-#336 core failing; that cannot be pinned.
  - The k8s tier gains `ShellEngineContractK8sTemplateIT` and `ShellEngineContractK8sPoolIT`: core's engine suite, the
    leftover check, PID 1 and orphaned-zombie reaping. Both passed on kind (15/15 each, `OpenSandboxK8sIT` 8/8). Pool
    mode reaps only because its pod's PID 1 is a shell; task-executor as PID 1 reaps nothing, unless the server sets
    `execd_run_as_init = true` (measured; WS §20).
  - `scripts/k8s-tier.sh` uses its own kubeconfig (`AIMON_K8S_KUBECONFIG`) and never touches `~/.kube/config` or its
    current context; refuses a cluster name that already exists; provisions the pool of pool mode
    (`src/test/k8s/pool.yaml`); gains `status` and `reconfigure` (`AIMON_K8S_EXECD_INIT`); and no longer leaves its
    port-forward running or exits 1 after a green run (its EXIT trap named an out-of-scope local under `set -u`).
  - `OpenSandboxProviderConfig` has a package-private `extensions` seam that the pool class uses for
    `extensions.poolRef`. It is not API. Pools stay out of the SPI (WS §6.4).
  - Follow-up, re-verified on kind on the final code (template, pool, and `OpenSandboxK8sIT` in pool mode as the new
    `OpenSandboxK8sPoolIT`: 4 pass, 4 skip for needing a network policy, a credential or the runtime class):
    - **`scripts/k8s-tier.sh` now runs the server with `execd_run_as_init = true`** (`AIMON_K8S_EXECD_INIT=0` leaves it
      out), and the Kubernetes guide (§4.2) makes it an operator requirement: task-executor as a pool pod's PID 1 reaps
      nothing. The pool class asserts that PID 1 is not task-executor or that `execd --init` runs, so a pool without it
      fails naming the cause (measured both ways).
    - The leftover baseline is keyed on pid and start time, so a pid an exited leftover freed cannot hide a new one;
      the watchdog probe counts only an `aimon_ptc` process whose parent is neither the runtime nor its anchor.
    - Docker tier flakes: the egress IT attaches the server's log of a create that answered HTTP 500, and retries it
      once only when that log shows a host-port conflict — the server's own allocation probes ports inside its
      container and, on the egress path, does not retry a conflict (reproduced: `Bind for 0.0.0.0:… failed: port is
      already allocated` → "Egress sidecar container failed to start."). The out-of-memory test records the sandbox's
      `memory.*` accounting (swap is allowed up to the limit), asserts the cgroup's `oom_kill` count rose, and gives
      the command 180 s.
    - Second follow-up: the leftover baseline's start time is the kernel's (`/proc/<pid>/stat` field 22, clock ticks)
      where `/proc` has it, `lstart` (to the second) otherwise; the pool's PID 1 check recognizes task-executor by its
      program's name wherever it is installed; the `oom_kill` count is read on cgroup v1 too (`memory.oom_control`),
      and a cgroup with no count skips that one check, saying so; the egress retry is unit-tested on a simulated
      server log, and the egress IT runs `@Isolated`, so the log it reads since a mark is its own calls' alone.
    - Third follow-up: the leftover snapshot reads each process from `/proc` alone, its `cmdline` bracketed by two
      `stat` reads whose start times must agree, so args and start time cannot come from two processes sharing a
      reused pid (`ps` only where there is no `/proc`). The PID 1 check takes PID 1's arguments from its
      NUL-separated `/proc/1/cmdline`, so a path with a space still matches. The `oom_kill` skip is narrowed to
      cgroup v1; on v2 a missing count fails. The egress retry's stderr line has the `[OpenSandboxEgressIT]` prefix
      again. `scripts/k8s-tier.sh` gains `AIMON_K8S_NODES` (multi-node kind, workloads on workers) and records each
      pod's node in `$AIMON_K8S_WORK/placement.txt`. Re-verified on kind, single node and three nodes (control plane
      plus two workers, sandboxes on both workers): 42 passed, 4 skipped, 0 failed each time.
    - Fourth follow-up: the out-of-memory test takes swap away from its sandbox before the command (the container's
      Docker memory-swap set to its 256 Mi limit, then `memory.swap.max` 0 is asserted). With Docker's default swap,
      a runner's slow swap disk kept the cgroup in reclaim past 180 s and left execd unreachable (CI, PR #55); without
      swap the kill took about 0.3–0.4 s on a local Docker Desktop (the CI runner's time is not recorded: a green run
      uploads no test output). A server started elsewhere keeps its swap and the 180 s margin. The
      contract suite's creates retry a host-port conflict once, as the egress IT's do, and the conflict is also
      recognized in the error body and in Docker 28's `failed to bind host port …` wording.
    - Fifth follow-up: the out-of-memory test finds its sandbox's container by the server's `opensandbox.io/id` label
      (egress sidecars left out), then by the `sandbox-{id}` name, and fails naming the candidates when neither picks
      out one; a host whose kernel accounts no cgroup's swap is said on stdout instead of failing the swap assertion;
      a sandbox with neither cgroup v2's `cgroup.controllers` nor v1's memory controller fails as unknown instead of
      being read as v1. The k8s template-mode PID 1 check matches PID 1's NUL-separated arguments, as the pool check
      does. `OpenSandboxProviderContractTest` runs `@Isolated`, as its log-reading retry assumes. The leftover
      snapshot's retry on a pid reused between its two `stat` reads is unit-tested. `scripts/k8s-tier.sh test`
      restarts the pod watch when the API server ends it.
    - Sixth follow-up: only a swap file that does not exist counts as a host without swap accounting; one that exists
      but cannot be read fails the swap assertion with the reading. Two non-sidecar containers labelled with a
      sandbox's id fail with both named instead of the name picking one. `scripts/k8s-tier.sh test` says each pod
      watch restart on stderr and in `placement.txt` (which can miss a pod created and deleted in the 1 s before a
      restart), and backs off up to 60 s when kubectl fails at once instead of retrying every second in silence. A
      401/403 on the egress sidecar's network-policy read during `create` now says the server had just accepted the
      api-key and that something else may answer on the sidecar's host port; the egress IT attaches the server's
      log and Docker's published host ports to it (a one-off 401 there under concurrent runs had no evidence).

### Added: groundwork for core's programmatic tool calling (shell engine)

See [`docs/design/ptc-shell-prerequisites.md`](docs/design/ptc-shell-prerequisites.md).

- **Stream runs leave no execd spool behind (S1).** `ExecSpec.transientOutput()` asks a provider to remove its
  in-sandbox copy of a command's output once the command has ended, however it ended. `SandboxShell` sets it for hook,
  isolated and streamed execs. The OpenSandbox provider then removes execd's spool files of the command, in both execd
  layouts, before `await` reports it — one call, then one per file if execd refuses a path, so one bad path does not
  keep the others (not when execd did not take the call: a timeout, an I/O failure, the server proxy's 502 for "cannot
  connect to execd", or a 503/504 is not retried, and that run's spool stays). A command that ended in time is never
  reported as timed out because of the removal: `await` waits one request timeout for it, then reports the command's
  real exit while the removal goes on. A command killed past `await`'s timeout that ends inside the kill grace is
  reported with its real exit code too, not 137, when its removal outlasts the grace. execd `release-1.1.0` and later
  already remove their own; `docker/execd/v1.1.0` keeps them in `/tmp`.
- **One output line is bounded (S2).** The OpenSandbox provider stops reading a `/command` stream at an event line over
  256 KiB, kills the command and marks both streams truncated, instead of holding the whole line in memory. A hook,
  isolated or streamed command printing such a line now fails (the wrapper's exit 137). The model's own commands are not
  affected: their output goes through the wrapper's files. `Grep` is: a match on such a line (a minified bundle, a
  one-line JSON file) now fails the in-sandbox `rg` search, and core's `GrepTool` falls back to its slower filesystem
  walk, which still finds it.
- **`/workspace/.aimon-ptc/` is hidden from the file tools (S9)**, where core's shell engine will put its tool replies
  (`PathRule.deny`). The shell still reads and writes it, and `Grep`'s in-sandbox search does not go through path rules.
- **Contract tests (S7)** pin that `isolated(true)` streaming is not foreground: it streams, takes no shell lock, and
  neither reads nor saves the session's state — also with a run directory, as core's shell engine runs it.

### Added: streaming shell output

- **Standard error streams too** (`ShellLineListener#onStderrLine`, aimon-core 0.3.4), without the wrapper's trailer
  line — so a failed monitor reports the *last* lines of stderr. `StdoutLines` is now `StreamLines`, one per stream.

- **`SandboxShell` declares `ShellFeature.STREAMING`** and implements `VirtualShell#execute(ShellCommand,
  ExecutionOptions, ShellLineListener)` (aimon-core 0.3.4): a command's stdout reaches the listener line by line as the
  provider delivers it. A streamed command takes a hook command's stream path — no shell lock, no state save, the
  session's cwd and exports, the watchdog for its timeout. This is what core's background `Monitor` command form needs
  in a sandbox (`docs/design/workspace-sandbox.md` §9.x).
- **Pinned to `aimon-core 0.3.4-SNAPSHOT`** from Central's snapshot repository until core 0.3.4 is released — a release
  blocker by design: `scripts/release.sh` refuses while the catalog pins a SNAPSHOT. The snapshot of 2026-11-06 (core
  PTC pieces A–D1) made the hook-context builders require `origin(...)`; the one test that built one without it is
  fixed.

### Added: workspace files for a file explorer

- **`SandboxWorkspaceFiles`**, from `SandboxWorkspaces.files()` (use right) and `SandboxAdministration.files()` (no
  check, actor recorded): `stat`, `list` (one level, directories first, `truncated` past the limit), ranged `read`,
  name `search` (glob or case-insensitive substring), `write` (create-new or overwrite), `createDirectory`, `move` and
  `delete` under `/workspace` of a workspace's `primary` sandbox. Reads stream in 4 MiB ranged fetches
  ([`docs/design/workspace-sandbox-files.md`](docs/design/workspace-sandbox-files.md)).
- **It reaches only a RUNNING, seeded sandbox** and never creates, provisions, reopens or waits:
  `WorkspaceFilesUnavailableException.reason()` says `NOT_FOUND`, `NOT_RUNNING`, `CLOSING`, `CLOSED` or
  `UNREACHABLE` (only this one is worth retrying: the provider or the workspace store failing or busy). A sandbox found
  gone is marked lost, as from a file tool.
- **Paths are relative to `/workspace`**; `..` out of it, absolute paths and NUL bytes are `InvalidPathException`.
  `/workspace` itself and the top-level `.aimon-*` entries are read-only (`FileAccessDeniedException`).
- **Changes** record activity like an agent's file call (reads do not) and publish the new
  `SandboxEvent.Type.FILES_CHANGED` with the actor and the operation in `cause()`, as soon as the change returned. It
  is for a person's changes only (a departure from WS's lifecycle-only events: such a change has no tool hook). A listener that switches over
  `SandboxEvent.Type` exhaustively sees a new constant.
- New views `WorkspaceFile` and `WorkspaceFileListing`.

### Changed: follow core's run-directory cap and refusals (#36, kangwoo/aimon-core#283)

- `SandboxShell` follows core's published `RunDirectory.MAX_TOTAL_BYTES` (4 MiB) as a run directory's ceiling.
- Core's `RunDirectory.Builder` now refuses control characters and file/directory overlaps itself; the sandbox's own
  newline and size checks stay as a backstop, and `SandboxShellRunDirectoryIT` pins the core refusal instead of the
  sandbox's.

### Added: run directories for hooks, and hook runs off the shell directory (#33, kangwoo/aimon-core#275)

- **`SandboxShell` declares `ShellFeature.RUN_DIRECTORY`.** Core hands a hook its files as bytes
  (`ExecutionOptions.getRunDirectory()`) and runs a `hooks.json` handler that names `AIMON_HOOK_DIR` only on a shell
  that declares it; until now such a handler was not run on the sandbox (`RUN_DIRECTORY_UNSUPPORTED`, so a guard
  blocked). Skill hooks get `AIMON_SKILL_DIR` the same way instead of the staged copy under `.aimon-staged`. The files
  are placed for the one run in a fresh private directory, `/workspace/.aimon-shell/.run.{hex}/d` (exclusive `mkdir`,
  mode 700), inline in the exec when they fit and otherwise as two uploads whatever their number (a manifest and one
  blob, whose digests the exec carries, so a skill with many small files is neither refused nor one upload per file),
  checked against SHA-256 digests that travel only in the exec, counted, exported in the run directory's variable after
  every other variable, and removed after the run whatever its outcome. A digest or count mismatch throws
  `RunDirectoryIntegrityException` (core: `HOOK_FILES_TAMPERED`, which blocks whatever `failOpen` says) and the command
  does not run; any other placement failure is a plain `ShellExecutionException`. A script over the exec's
  single-argument budget is refused before anything is provisioned; a control character in a file name, a
  file/directory overlap and more than 4 MiB are refused by core's `RunDirectory.Builder` (kangwoo/aimon-core#283).
- **Changed: hook and isolated runs keep nothing in the shell directory.** Their command, a small stdin and their output
  used to be run files under `/workspace/.aimon-shell/{h}/`, which the agent's shell (the same uid) could rewrite to
  forge a policy hook's verdict. Now the command is an argument of the exec, stdin is base64 in the exec (or uploaded,
  copied into the private directory and digest-checked), stdout and stderr are the exec's own streams capped in the JVM,
  and a timeout is read from the JVM's clock rather than a marker file. A hook command over 64 KiB is refused. A stdin
  with a NUL byte, or in a charset other than UTF-8, now reaches a hook as its exact bytes. Foreground and background
  commands are unchanged. This narrows tampering; it is not a privilege boundary — a process of the same uid can still
  reach a hook while it runs (#34).
- **Behaviour change: a hook's background jobs end with the hook.** A hook's stdout is now the exec's, so a `cmd &` it
  leaves behind would hold the exec open until its backstop. `SandboxShell` kills the exec's process group once the
  hook's trailer has arrived (after a 500 ms drain grace): a `postTool` hook's `notify-send &` is killed when the hook
  ends. A job that left the group (`setsid`, `set -m`) survives, as before.
- **The image contract adds `base64` and asks for GNU coreutils** (`sha256sum` and `base64` in `/usr/bin`). The seed
  checks both paths and that `/usr/bin/sha256sum -c --strict -` accepts a manifest: a BusyBox `sha256sum` rejects
  `--strict`, which would read as tampering on every hook with files.
- **Testkit: `LocalProcessSandboxProvider`'s shims start at once on macOS.** The `flock`, `rg` and `shasum` shim scripts
  are written once per host and user (`$TMPDIR/aimon-sandbox-shims-{user}/`, mode 700, keyed by content; a directory
  that is not the user's own or that others can reach is not used) and linked into each provider, and the
  `sha256sum` shim links the host's binary: a freshly written executable is assessed on its first run, which under load
  outlasted a seed's timeout.
- Design record:
  [`docs/design/workspace-sandbox-run-directory.md`](docs/design/workspace-sandbox-run-directory.md).

### Added: isolated execution for `hooks.json` commands (#31, kangwoo/aimon-core#273)

- **`SandboxShell` declares `ShellFeature.ISOLATED_EXECUTION`.** Core runs an operator's `hooks.json` command on an
  in-execution event (`preTool`, `postTool`, …) in the execution's environment only on a shell that declares it; until
  now the sandbox did not, so with `HookHotReloadBootstrap` and this provider every tool call a `hooks.json` guard
  matched was blocked unless the handler said `"shell": "host"`.
- An isolated command (`ExecutionOptions.isIsolated()`) runs like a hook's — no session lock, no state save, the
  watchdog at its own timeout — but starts as a new shell would: in the root (or its own working directory), with the
  profile's exec environment and its own variables. It never reads the session's state file, so the model's `cd` and
  exports (`PATH`, `BASH_ENV`, `LD_PRELOAD`) cannot steer an operator's policy hook. Skill-declared hooks are unchanged:
  they still start from the session's cwd and exports.

### Changed: the session owner is read alone (kangwoo/aimon-core#262)

- `SessionOwnerLookup.recorded(...)` (and so `WorkspaceSandbox.Builder.sessionRecords(...)`) reads the owner with core's
  `SessionRecordStore.loadOwner` instead of loading the whole session record. A main-turn bind's owner check no longer
  costs a transcript read (core backlog SO-5 · SO-8).

### Added: per-execution workspace and rule (kangwoo/aimon-core#252)

- **Request attributes place one execution.** A host sets `environment.sandbox.workspace` on a turn
  (`SubmitOptions.executionAttributes`) or on a routine run (a `RoutineExecutionAttributes` resolver); core forwards
  it, and the turn or run binds to that managed workspace ahead of the session's options and the agent default. It
  redirects that execution only and writes no session option, so the executing principal's use right is the whole
  right it needs (a routine runs as its task's owner). `environment.sandbox.unattached` joins the unattached rule,
  which is now `strictest(unattached, sandbox.unattached, session option, request)`. Any other
  `environment.sandbox.*` key is refused. Forks inherit their parent's workspace and never see request attributes.
  `SandboxRequestAttributes` holds the keys and builds the maps. Never fill them from model output.
- `BindingContext.requestAttributes()` for custom binding policies.
- A session redirected by a request attribute hears so once, and once more when a later turn runs elsewhere again.
  A session that had no options record gets one holding only where it last ran; it grants nothing and is not listed as
  an attachment.

### Added: session owner from core and a deletion listener (kangwoo/aimon-core#253)

- **`WorkspaceSandbox.Builder.sessionRecords(SessionRecordStore)`** (or a `Supplier`): the owner core recorded for a
  session — set by `SubmitRequest.owner(...)` on the submit that creates it — is the session's owner for its
  `ws:{sessionId}` workspace and its options, with a `SessionOwnerLookup`, when also wired, asked only for sessions
  recorded without one. `SessionOwnerLookup.recorded(...)` and `orElse(...)` compose the same by hand. It satisfies
  `require-principal` on its own. Once any owner source is wired, a session no source knows the owner of gets no
  sandbox, with or without `require-principal`: wiring `sessionRecords(...)` without setting `SubmitRequest.owner(...)`
  refuses every main turn, older sessions included, unless a `SessionOwnerLookup` answers for them.
- **`WorkspaceSandbox.sessionLifecycleListener()`** (`SandboxSessionCleanup`): register it with core's session
  router (`SessionRouterBuilder.lifecycleListener`, `SessionSpec.Builder.lifecycleListener`, or a Spring bean). On a
  delete it removes the session's options and closes and deletes its `ws:{sessionId}` workspace, without
  `closed-retention`: the record is marked at once, under the session's lease, and the sandboxes are destroyed in the
  background. A session re-created with the same id meanwhile gets "being removed; retry shortly", never the deleted
  session's sandbox. A managed workspace the session was attached to is left alone.
- **`CloseCause.SESSION_DELETED`**, the cause of such a close. The janitor finishes a deletion that was interrupted
  and deletes a `CLOSED(SESSION_DELETED)` record on its first pass.
- **Janitor sweep of deleted sessions** (with `sessionRecords` wired): options of a session core no longer has are
  deleted once older than `closed-retention` — a lost event, or a session deleted outside the router.
- **`WorkspaceSandbox.Builder.backgroundExecutor(Executor)`**: where that background work and the `lastWorkspaceId`
  write run. Default: two daemon threads with a queue of 1 000, owned and shut down by the assembly (it waits five
  seconds, then interrupts; the janitor finishes what was cut short), apart from the heartbeat and janitor threads.
- **`SessionOptionsStore.sessions(after, limit)`**, every record by session id, with a contract test.

### Changed: environment notices reach every tool result (kangwoo/aimon-core#254)

- **`SandboxExecutionEnvironment.drainNotices()`**. What `connect` reports — an idle reset, a re-created or lost
  sandbox, a slow provisioning, a workspace switch — is delivered by core at the head of the next tool result of any
  kind, a `Read` or a hook's tool call included, once, as an `[environment]` line. Before, it waited for the next
  `Bash` result, and a turn of file tools only never heard it. The switch is recorded as the session's
  `lastWorkspaceId` when it is delivered.
- **`ShellCommandResult.notices()` and the shell exceptions carry only per-command facts**: the command was killed,
  or its working directory was gone.
- **A re-creation made by a fork is told to its parent**: each environment remembers the sandbox generation it last
  used and says so when a later call of its own finds a newer one it did not re-create itself.
- **A reset that a hook, a workflow step or a staging call caused is told on the session's next tool result, even a
  turn later** (kangwoo/aimon-sandbox#28). Core drains notices only after model-facing tool calls, so such a reset
  used to die with its environment. A session's root environment now keeps it on the session's options record
  (`SessionSandboxOptions.unannouncedReset()`) before queueing the notice, and the drain that delivers it clears it;
  otherwise the next turn tells it once, with the time of the reset. A record holding only that fact is removed with
  it, and lists as no attachment. Forks keep nothing
  ([`docs/design/workspace-sandbox-unannounced-reset.md`](docs/design/workspace-sandbox-unannounced-reset.md)).
- **A failed re-provisioning after a reset** reports the bare reason as the tool error; the reset arrives as an
  `[environment]` line on the same result instead of the error's "(also: …)" suffix. Callers of the manager outside
  the environment still read the suffix.

### Behaviour changes for 0.1.0 hosts and for this cycle's API

- **`SandboxWorkspaces.deleteSessionOptions` is removed** (it was added in this cycle, never released). Register the
  lifecycle listener instead; for sessions deleted outside the router, use `SandboxAdministration.deleteSessionOptions`
  — which needs no owner source at all any more, so it also removes the `lastWorkspaceId` record a request attribute
  leaves on a single-tenant host — or rely on the janitor's sweep.
- **`CloseCause` has a third constant**: an exhaustive `switch` over it needs a branch for `SESSION_DELETED`. A store
  that persists the enum by name needs nothing.
- **A custom `SessionOptionsStore` must implement `sessions(...)` and persist `unannouncedReset`**; run the
  updated contract; it keeps the reset's time to at least millisecond precision. A store that drops the field behaves
  as before #28 for that session.
- **Code that reads `ShellCommandResult.notices()`** no longer finds environment notices there; they reach the model
  through core.
- **Non-String `environment.*` execution attributes** set for other purposes now make that turn's environment
  unavailable: core 0.3.3 reserves the prefix.
- `WorkspaceSwitch.optionsVersion()` is an `OptionalLong` (no options record yet).

### Added: managed workspaces, session options and an agent default

Management design [`docs/design/workspace-sandbox-management.md`](docs/design/workspace-sandbox-management.md), PR 1.

- **`WorkspaceSandbox.workspaces()`** (`SandboxWorkspaces`): `create` a workspace under a generated `wsm:` id and
  provision its `primary` slot before returning; `provision` (retry after a `WorkspaceProvisioningException`),
  `describe`, `list` the caller's own, `close`, `reopen` (which provisions) and `purge` (CLOSED managed workspaces
  only; the id is never reused). Views carry no provider sandbox id.
- **Session options**: `setSessionOptions` / `clearSessionOptions` / `sessionOptions` attach a session to a managed
  workspace and set its own unattached rule; the lifecycle listener (above) removes them with the session. Only the
  session's owner may, which needs an owner source (`sessionRecords(...)` or a `SessionOwnerLookup`). They
  live in the new `SessionOptionsStore` SPI (`InMemorySessionOptionsStore` by default, single node only;
  `WorkspaceSandbox.Builder.sessionOptionsStore`) with a `SessionOptionsStoreContract` in the testkit.
- **Binding**: the configured policy is wrapped in `SessionOptionsBindingPolicy` — session options, then the
  definition's `sandbox.workspace`, then the effective unattached rule
  `strictest(unattached, sandbox.unattached, session option)`. A binding to a workspace it did not derive is
  `mustExist` and never re-creates a missing record. A session that moved hears so once ("now runs in sandbox
  workspace …") with its next tool result.
- **Rights**: a record's own `access` decides who may use it; close, reopen and purge of a managed workspace are its
  owner's alone.
- **Settings**: `unattached` (`per-session`), `allow-shared-workspaces` (false), `max-managed-per-owner` (20, or
  `unlimited`).
- **Janitor**: managed CLOSED records are kept past `closed-retention`; a sweep clears session options still attached
  to a purged workspace.
- **Events**: types `WORKSPACE_CREATED`, `WORKSPACE_PURGED`, `SESSION_OPTIONS_SET`, `SESSION_OPTIONS_CLEARED`, and
  `session()`, `actor()` and `workspace()` (the `Optional` workspace; `workspaceId()` throws on a session event that
  names none). A listener that throws a `LinkageError` no longer fails the transition it hears of.
- **`SandboxAdministration.of(sandbox)`** (design PR 2): `workspaces(AdminWorkspaceQuery)` (state, origin, name,
  tenant and owner filters), `workspace(id)` with its attached sessions, `sessions(id, after, limit)`, `sandboxes()`
  — every provider sandbox of the deployment with the reconciler's classification as a `SandboxVerdict`, by
  `refHash`, with `pastGrace` (when the provider reports `createdAt`) where the janitor waits for one — and
  owner-check-free writes: `create` on behalf of an owner, `setSessionOptions` / `clearSessionOptions` /
  `deleteSessionOptions`, `terminateSlot` (refused for a PROVISIONING or absent slot), `close`, `reopen` (the owner
  passes the principal gate first), `purge`. A separate API surface, not a JVM security
  boundary: expose it only behind your operator authorization.
- **`actor()` on events** of every operator write, of an owner's own close, reopen, purge and session-options calls,
  and of the terminations a close causes; the janitor's carry none.
- **Recorded failures** never quote the provider's raw sandbox id: a server message that does gets the id's hash instead.

### Breaking for custom `SandboxWorkspaceStore`s

- **`SandboxWorkspace`** gains `origin`, `name`, `profile` and `access`, and **`WorkspaceScan`** gains `owner` and
  `origin` filters. A custom store **must persist the four fields** through `createIfAbsent` and `update` (an
  `access` of `principal` included — dropping it widens a narrowed workspace, dropping `origin` lets the janitor delete
  a managed record) and **should honour the filters**; run the updated `SandboxWorkspaceStoreContract`. The manager
  re-checks every scanned record, so a store that ignores the filters is slow, not unsafe.
- New `SandboxEvent.Type` constants: a listener with an exhaustive `switch` needs a default branch.

### Fixed: a skill's shell hook no longer waits for the model's shell (#9)

- **A hook's command takes no shell lock.** aimon-core 0.3.2 marks it with `ExecutionOptions.isHook()`, and
  `SandboxShell` runs it like a background command: it starts from the session's cwd and exports, saves no state, and
  does not wait for the session's lock. Before, a `preTool` shell guard firing while the session's shell was busy could
  wait `shell-lock-wait`, meet "shell is busy", and read that as a block. Unlike a background command, it keeps the
  in-sandbox watchdog, so it ends at the hook's own timeout rather than at the exec's backstop five seconds later.
  For a hook at core's default 30-second timeout or longer, that later moment is exactly when core's outer deadline
  for the hook fires, so the two raced.
- **Correction to the 0.1.0 notes.** They said a hook's `cd` and `export` persisted into the model's shell state. They
  never did: core passes every hook `AIMON_*` variables, so the wrapper ran it in a subshell. The shell lock was the
  only real effect.

### Changed

- **aimon-core 0.3.3** (through 0.3.2, which removed `UserLocale` (EE-60); nothing in this library's main code used
  it).
- **`scripts/release.sh` refuses while `gradle/libs.versions.toml` pins a SNAPSHOT** (any uncommented line, inline
  `version = "…"` entries included; a dry run refuses too), so a pre-release dependency cannot be released by accident.

## [0.1.0] - 2026-10-05

The first release under the `at.aimon.sandbox` group: the **workspace sandbox**
([`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md)). An agent keeps using aimon-core's `Bash`,
`Read`, `Write`, `Edit` and `Grep`; with sandboxing on, they run inside an
[OpenSandbox](https://github.com/alibaba/OpenSandbox) environment that the platform — not the model — picks,
provisions and retires. Requires **aimon-core 0.3.1** and Java 17.

It replaces the identifier-based sandbox that aimon-core published through 0.2.4, with no compatibility layer
(design §17 maps old to new, §19 says why). Those artifacts stay on Maven Central and keep working, but receive no
further releases.

| Through 0.2.4 | From 0.1.0 |
|---|---|
| `at.aimon.core:aimon-sandbox` (four `*Sandbox` tools, `SandboxBackend` SPI) | `at.aimon.sandbox:aimon-sandbox` — the workspace sandbox |
| `at.aimon.core:aimon-sandbox-docker`, `at.aimon.core:aimon-sandbox-kubernetes` | `at.aimon.sandbox:aimon-sandbox-opensandbox` — one provider for both runtimes |
| — | `at.aimon.sandbox:aimon-sandbox-testkit` — contract suites and test doubles |

### `aimon-sandbox`

- **An `ExecutionEnvironmentProvider` for aimon-core** (`SandboxExecutionEnvironmentProvider`, assembled by
  `WorkspaceSandbox`). Every execution resolves its environment once: a declared descriptor for the prompt (nothing is
  provisioned for a turn that runs no command), a file system with path rules, read-only staging and shell areas, and
  `rg --json` search. Forks share their parent's workspace; a fork's caller is its own principal, checked like a root
  request's.
- **Shell state that survives nodes.** cwd and exported variables persist per shell key in files inside the sandbox,
  behind an in-sandbox lock. A timed-out, interrupted or cancelled command is killed with its process group and leaves
  the previous state in place, with a notice. `KillShell` stops background commands (`ShellFeature.CANCELLATION`).
  Background commands end at the profile's `background-command-timeout`, 24 hours by default, which core tells the
  model when the command starts. When the exec environment names a CA bundle in `SSL_CERT_FILE`, git is pointed at it
  through `GIT_SSL_CAINFO`.
- **Workspaces and their lifecycle.** Workspace records with a version-CAS store (`SandboxWorkspaceStore`, with
  `InMemorySandboxWorkspaceStore`), lazy provisioning, owner checks at every entry point, close and reopen with
  tombstones, a default binding policy with tenant and session-owner resolution, and a default per-tenant admission.
  An activity heartbeat keeps working sandboxes awake; the janitor enforces the idle policy and reconciles the
  provider's sandboxes against the records (ORPHAN, STALE, FAILED-LEFTOVER, DUPLICATE, LOST).
- **Skill-declared shell hooks run in the sandbox**, never on the host (aimon-core 0.3.1's executor). A `preTool`
  shell guard blocks its tool call, and an `onStart` shell guard keeps its skill fork from starting, when the sandbox is
  unavailable; give hooks that only observe `failOpen: true`.
- **Settings are checked at startup, not ignored**: profiles, credential bindings (existence, overlap, egress
  coverage), runtime class, and everything this version cannot honour yet (below).
- **The provider SPI** (`SandboxProvider`, `SandboxConnection`, `SandboxFiles`, `RunningCommand`, `SharedVolumes` and
  their value types), with labels that tie each sandbox to its deployment and workspace.
- Types and members that are public only for use across this library's packages carry an **Internal** javadoc note
  and are not supported API.

### `aimon-sandbox-opensandbox`

- **`OpenSandboxProvider`** calls the OpenSandbox REST API directly (JDK `HttpClient` + Jackson, no SDK), for both the
  Docker and the Kubernetes runtime. Capabilities come from operator declarations in `OpenSandboxProviderConfig`, never
  from probing, and declarations the runtime cannot honour are refused.
- It enforces what the server does not: a timeout on every sandbox, expiry that only moves forward (rounded up to the
  microsecond the server keeps, capped at `max-expiry`, never on a paused sandbox), a `DELETE /command` on every kill,
  file writes through a temporary name, and a wait for execd's `/ping` before a new sandbox is returned.
- **Credential bindings** are written to the egress sidecar's vault, only the ones a profile names. The seed verifies
  the egress enforcement mode and the vault, and probes the control plane when `NETWORK_ISOLATION` is required.
- See the [module README](modules/aimon-sandbox-opensandbox/README.md) for configuration and the operator's side.

### `aimon-sandbox-testkit`

- The provider and store contract suites that every implementation must pass, `LocalProcessSandboxProvider`
  (**tests only**), `FaultInjectingSandboxProvider`, `ManualClock`, `ManualScheduler` and `SandboxTestProfiles`. It
  carries JUnit 6.1.3 and AssertJ on its `api`.

### Not supported yet — refused at startup

- Slots other than `primary`, `shared-access` other than `none`, and a profile `seed` (design §18 step 5).
- `pause-after` (step 7).
- A persistent store: `InMemorySandboxWorkspaceStore` is **single-node only** (step 6). Several nodes sharing a
  `deployment` with in-memory stores would reconcile each other's sandboxes away.

### Known limitations

- **A skill hook runs as a foreground command of the session's shell**: it takes the shell lock, and its `cd` and
  `export` persist into the model's shell state. Fixed once aimon-core ships `ExecutionOptions.isHook()` (#9).
- **Kills reach the exec's process group.** A job a command moved out of it (`setsid`, `set -m`) lives until the
  sandbox goes.
- **A background command does not follow a node move**: if the node that started it dies, nothing collects its
  result, and the idle policy reclaims the sandbox.
- **The Docker runtime is for local development**: it needs `insecure-allow: [NETWORK_ISOLATION,
  HARDENED_SECURITY_CONTEXT]`.
- Tools that ignore `SSL_CERT_FILE` (and are not git) fail TLS verification where a credential is injected.

### Verification

- CI runs the unit tier, the docker tier (`integrationTest`, a real OpenSandbox server via Testcontainers) and the
  coverage floors on every pull request.
- The k8s tier (`k8sTest`) passed on kind with the hardened container-level template, the isolation NetworkPolicy and
  a RuntimeClass. `scripts/k8s-tier.sh up|test|down` reproduces it.
- Releases are cut by `scripts/release.sh` (README › Releasing).
