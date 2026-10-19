# Changelog

All notable aimon-sandbox changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

Requires aimon-core **0.3.3**. Until core releases it, `main` pins `0.3.3-SNAPSHOT` from Central's snapshot repository,
which `scripts/release.sh` refuses to release
([`docs/design/workspace-sandbox-core-033.md`](docs/design/workspace-sandbox-core-033.md)).

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
  does not run; any other placement failure is a plain `ShellExecutionException`. A file name with a newline, files over
  8 MiB, and a script over the exec's single-argument budget are refused before anything is provisioned.
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
