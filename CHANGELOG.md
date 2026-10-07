# Changelog

All notable aimon-sandbox changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

Needs aimon-core **0.3.2**. Until it is released, `main` resolves `0.3.2-SNAPSHOT` from Central's snapshot repository
(filtered to `at.aimon.core` and to snapshots), and `scripts/release.sh` refuses to release.

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

- **aimon-core 0.3.2-SNAPSHOT.** Core removed `UserLocale` (EE-60); nothing in this library's main code used it.
- **`scripts/release.sh` refuses while `gradle/libs.versions.toml` pins a SNAPSHOT** (any uncommented line, inline
  `version = "…"` entries included; a dry run refuses too), so the pin above cannot be released by accident.

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
