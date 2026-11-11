# Changelog

All notable aimon-sandbox changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). While the version is 0.x, a patch release may break API or
behaviour; each such release lists what to change under "Upgrading".

## [Unreleased]

## [0.1.1] - 2026-11-11

Requires **aimon-core 0.3.4** and **OpenSandbox 1.2.0** (server, execd and egress sidecar), and Java 17.

**This patch release breaks API and behaviour** — 0.1.x is still moving fast, so breaking changes ship in patch
releases for now. Read "Upgrading from 0.1.0" below before you upgrade. `aimon-sandbox` exposes core
as an `api` dependency, so upgrading also raises core from 0.3.1 to 0.3.4; core declares 0.3.3 incompatible with 0.3.2
(the auto-compact buffer and ratio members are gone, kangwoo/aimon-core#285; the session log is written as version 2
by default, so a mixed-version cluster must pin `v1`, kangwoo/aimon-core#286 — see core's
[0.3.3 release notes](https://github.com/kangwoo/aimon-core/releases/tag/v0.3.3)).

### Upgrading from 0.1.0

- **aimon-core 0.3.4 and OpenSandbox 1.2.0 are required.** A 1.1.x server has no credential-vault route: a create
  with credentials fails permanently, saying the server is older than 1.2.0.
- **`CloseCause` has a third constant, `SESSION_DELETED`**, and **`SandboxEvent.Type` has new constants**
  (`WORKSPACE_CREATED`, `WORKSPACE_PURGED`, `SESSION_OPTIONS_SET`, `SESSION_OPTIONS_CLEARED`, `FILES_CHANGED`): an
  exhaustive `switch` needs a branch or a default. **`SandboxEvent.workspaceId()` throws** for a session event that
  names no workspace; use `workspace()` (an `Optional`).
- **A custom `SandboxWorkspaceStore` must persist** the new `origin`, `name`, `profile` and `access` fields of
  `SandboxWorkspace` through `createIfAbsent` and `update` (dropping `access` widens a narrowed workspace, dropping
  `origin` lets the janitor delete a managed record), and should honour `WorkspaceScan`'s new `owner` and `origin`
  filters. Run the updated `SandboxWorkspaceStoreContract`.
- **A custom `SessionOptionsStore` must implement `sessions(after, limit)` and persist `unannouncedReset`** (to at
  least millisecond precision). Run the updated `SessionOptionsStoreContract`.
- **Environment notices no longer appear in `ShellCommandResult.notices()`**; core delivers them on the next tool
  result (see Changed). The result and the shell exceptions carry only per-command facts.
- **Non-String `environment.*` execution attributes** make that turn's environment unavailable: core 0.3.3 reserves the
  prefix, and any `environment.sandbox.*` key other than the two below is refused.
- **Command output is exact.** With execd 1.1.1 or later the provider no longer appends `\n` to every output event:
  `printf abc` is `abc`, a `\r` progress line keeps its `\r`. With an older execd, which strips line endings, the
  first line reaches the `OutputSink` only when the next event arrives.
- **A hook's background jobs end with the hook**, and a hook, isolated or streamed command over 64 KiB is refused.
- **A run directory with a secret file is always uploaded**, never inlined, so a small secret set can now fail with
  "could not pass … into the sandbox" where it used to be inlined. Every `ExecuteCode` run takes this path.
- **Kubernetes: run the OpenSandbox server with `execd_run_as_init = true`** — required in pool mode, where
  task-executor as the pod's PID 1 reaps nothing and every `ExecuteCode` run would leave zombies
  ([guide](docs/guide/opensandbox-kubernetes.md) §4.2). Upstream 1.2.0 also lists a deleted Kubernetes sandbox as
  `Stopping` (read as TERMINATED) until its pod is gone, and defaults BatchSandbox pods to `restartPolicy: Never`.
- **A skill hook's `AIMON_SKILL_DIR`** is now a private per-run directory
  (`/workspace/.aimon-shell/.run.{hex}/d`, removed after the run), not the staged copy under `.aimon-staged`.
- **A failed re-provisioning after a reset** returns the bare reason as the tool error; the reset arrives as an
  `[environment]` line instead of an "(also: …)" suffix.
- **The image contract** adds `base64`, asks for GNU coreutils (`sha256sum` and `base64` in `/usr/bin`), and, for a
  profile that declares it, `python3` 3.8 or later.

### Added

- **Managed workspaces** ([design](docs/design/workspace-sandbox-management.md)). `WorkspaceSandbox.workspaces()`
  creates, provisions, describes, lists, closes, reopens and purges workspaces under `wsm:` ids, with their own
  `access` rule. **Session options** attach a session to one and set its unattached rule, stored in the new
  `SessionOptionsStore` SPI (`InMemorySessionOptionsStore`, single node). Binding takes the session's options, then the
  definition's `sandbox.workspace`, then the unattached rule. New settings: `unattached`, `allow-shared-workspaces`,
  `max-managed-per-owner`.
- **`SandboxAdministration`** — an operator surface over every workspace, session and provider sandbox (with the
  reconciler's verdict), and owner-check-free writes. It is not a JVM security boundary: expose it only behind your
  own operator authorization.
- **Per-execution workspace** (kangwoo/aimon-core#252): a host sets `environment.sandbox.workspace` and
  `environment.sandbox.unattached` on a turn or a routine run (`SandboxRequestAttributes`) to redirect that execution
  only. Never fill them from model output.
- **Session owner from core and session deletion** (kangwoo/aimon-core#253): `Builder.sessionRecords(...)` takes the
  owner core recorded; `WorkspaceSandbox.sessionLifecycleListener()` removes a deleted session's options and destroys
  its `ws:{sessionId}` workspace in the background (`Builder.backgroundExecutor(...)`). Once any owner source is wired,
  a session with no known owner gets no sandbox. A deleted session's workspace cannot be reopened while it is being
  removed.
- **Workspace files for a file explorer** (`SandboxWorkspaceFiles`, [design](docs/design/workspace-sandbox-files.md)):
  stat, list, ranged read, name search, write, mkdir, move and delete under `/workspace` of a running sandbox, without
  provisioning it. Changes publish `FILES_CHANGED`.
- **Isolated execution for `hooks.json` commands** (`ShellFeature.ISOLATED_EXECUTION`, kangwoo/aimon-core#273): an
  operator's hook runs in the sandbox from a fresh shell, never from the model's `cd` and exports.
- **Run directories for hooks** (`ShellFeature.RUN_DIRECTORY`, kangwoo/aimon-core#275,
  [design](docs/design/workspace-sandbox-run-directory.md)): a hook's files are placed in a private per-run
  directory, digest-checked (`RunDirectoryIntegrityException` on a mismatch) and removed after the run.
- **Streaming shell output** (`ShellFeature.STREAMING`): stdout and stderr reach a `ShellLineListener` line by line,
  which core's background `Monitor` needs in a sandbox.
- **The sandbox half of core's Python `ExecuteCode` shell engine**
  ([design](docs/design/ptc-shell-provider.md)): `SandboxProfile.runtimes(Set.of("python3"))`, checked by the seed;
  `SandboxExecutionEnvironment.runtimeFileSystem()`; the descriptor declares `SANDBOXED` isolation; the provider SPI's
  `ExecKillCause` says why a command was ended. A timed-out or cancelled run leaves nothing running in the sandbox
  (with core 0.3.4). Verified on the docker tier and on Kubernetes in template and pool mode
  ([how](docs/design/ptc-shell-verification.md); [results](docs/design/ptc-shell-provider.md) §9).
- **Testkit:** `RuntimeFileSystemContract`, and updated store contracts.

### Changed

- **OpenSandbox 1.2.0.** Exit codes come from `error.exit_code`; the credential vault is reached through the lifecycle
  server, so with `useServerProxy(false)` the AIMON pods need to reach only execd (44772) — the NetworkPolicy example
  drops 18080.
- **Environment notices reach every tool result** (kangwoo/aimon-core#254), not just the next `Bash` result — a
  reset, a re-created or lost sandbox, a workspace switch. A reset caused by a hook or a workflow step is kept on the
  session's options record and told on its next tool result, even a turn later
  ([design](docs/design/workspace-sandbox-unannounced-reset.md)).
- **Hook and isolated runs keep nothing in the shell directory**: the command, stdin and output travel in the exec, so
  the model's shell cannot rewrite a policy hook's verdict. This narrows tampering; it is not a privilege boundary.
- **One output line is bounded** at 256 KiB for hook, isolated and streamed commands (the command is killed), and their
  execd spool files are removed when they end. A `Grep` match on such a line falls back to core's slower walk.
- `{root}/.aimon-ptc/` is hidden from the file tools.
- The session owner is read with `SessionRecordStore.loadOwner`, without the transcript (kangwoo/aimon-core#262).
- `scripts/release.sh` refuses while `gradle/libs.versions.toml` pins a SNAPSHOT.

### Fixed

- **An out-of-memory command no longer takes execd with it.** The shell wrapper raises the command's
  `oom_score_adj`, so the kernel kills the command, not execd; the sandbox and the next command go on
  (`docs/design/workspace-sandbox.md` §12.2).
- **A skill's shell hook no longer waits for the model's shell** (#9): it takes no shell lock and ends at its own
  timeout. The 0.1.0 notes were wrong that a hook's `cd` and `export` persisted into the model's shell; they never did.
- **An owned provider is closed when `WorkspaceSandbox.Builder.build()` fails.**
- **Recorded failures never quote the provider's raw sandbox id**; a server message that does gets the id's hash.
- **An interrupted move or link in the OpenSandbox files API kills its in-sandbox helper.**
- An event listener that throws a `LinkageError` no longer fails the transition it hears of.
- Testkit: `LocalProcessSandboxProvider`'s shims start at once on macOS.

The test tiers gained leftover-process checks, a multi-node kind setup (`scripts/k8s-tier.sh`, `AIMON_K8S_NODES`)
and fixes for several flaky tests; the details are in the design records linked above and in the commit history.

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
