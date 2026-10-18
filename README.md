# aimon-sandbox

Isolated execution environments for [AIMON](https://github.com/kangwoo/aimon-core) agents.

An agent that has to run code — try what it just wrote, build, test, process data — needs somewhere to
run it that is not the host. The workspace sandbox gives it one without new tools: the agent keeps using
`Bash`, `Read`, `Write`, `Edit` and `Grep`, and when sandboxing is on those tools run inside an
[OpenSandbox](https://github.com/alibaba/OpenSandbox) environment that the platform — not the model —
picks, provisions and retires. The design is
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md).

## Status

**0.1.0 is the first release** (requires aimon-core 0.3.1; see [`CHANGELOG.md`](CHANGELOG.md)). It contains
implementation steps 3 and 4 (§18): the workspace domain, the provider SPI and the local path (step 3), and the
production OpenSandbox provider with the janitor's sandbox reconciliation (step 4). Multiple slots and shared volumes
(step 5), a persistent store for several nodes (step 6) and pause/resume (step 7) are not in it, and profiles that
ask for them are refused at startup.

The identifier-based sandbox it was split out of aimon-core with — four `*Sandbox` tools, the
`SandboxBackend` SPI and its Docker and Kubernetes backends — has been deleted, not kept alongside the new
design (why: [`workspace-sandbox.md`](docs/design/workspace-sandbox.md) §19; old-to-new map: §17). How steps 3
and 4 were built, and where they departed from the plan, is
[`workspace-sandbox-step3.md`](docs/design/workspace-sandbox-step3.md) and
[`workspace-sandbox-step4.md`](docs/design/workspace-sandbox-step4.md); what the OpenSandbox server actually does is
[`opensandbox-spike.md`](docs/design/opensandbox-spike.md); how the sandbox follows aimon-core 0.3.1 (shell
cancellation, the background ceiling, runtime bindings) is
[`workspace-sandbox-core-031.md`](docs/design/workspace-sandbox-core-031.md).

| Module | Coordinate | What it is |
|---|---|---|
| `aimon-sandbox` | `at.aimon.sandbox:aimon-sandbox` | Workspace records and CAS store, the manager and janitor, provider SPI, binding policy, and the `ExecutionEnvironmentProvider` that aimon-core's file and shell tools resolve per execution (step 3) |
| `aimon-sandbox-testkit` | `at.aimon.sandbox:aimon-sandbox-testkit` | Provider and store contract suites every implementation must pass, a local-process provider **for tests only**, fault injection, a manual clock (step 3) |
| `aimon-sandbox-opensandbox` | `at.aimon.sandbox:aimon-sandbox-opensandbox` | The production provider: OpenSandbox over its REST API, capabilities from operator declarations, credential vault bindings (step 4; [README](modules/aimon-sandbox-opensandbox/README.md)) |

`aimon-sandbox-store-jdbc` (step 6) is added when its step lands.

If you need the old implementation, `at.aimon.core:aimon-sandbox{,-docker,-kubernetes}:0.2.4` is still on
Maven Central and still works; its design document was deleted with it and survives at
[`sandbox.md` @ `704013c`](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md).

## Relationship to aimon-core

The dependency runs one way: this repository compiles against a released `at.aimon.core:aimon-core` from
Maven Central (one line in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)), and aimon-core has no
reference to anything here. The workspace sandbox implements aimon-core's execution-environment SPI
(design §7), which aimon-core has since 0.3.1. `main` needs 0.3.3 — pinned as `0.3.3-SNAPSHOT` until it is released —
for per-request attributes (kangwoo/aimon-core#252), the recorded session owner and the session deletion event
(kangwoo/aimon-core#253) and environment notices on every tool result (kangwoo/aimon-core#254)
([`docs/design/workspace-sandbox-core-033.md`](docs/design/workspace-sandbox-core-033.md)).

## Wiring

```java
WorkspaceSandbox sandbox = WorkspaceSandbox.builder().settings(settings).provider(provider)
        .sessionRecords(() -> stack.sessionRecordStore())    // the session owner core recorded
        .build();
sandbox.janitor().start();

// a hand-built runtime
runtimeBuilder.executionEnvironmentProvider(sandbox.environmentProvider());

// through aimon-bootstrap
ExecutionEnvironmentSpec.shared(sandbox.environmentProvider());
SessionSpec.builder().lifecycleListener(sandbox.sessionLifecycleListener());   // or SessionRouterBuilder, or a bean
```

- **Session owners come from core.** With `sessionRecords(...)`, the owner core recorded for a session — set by
  `SubmitRequest.owner(...)` on the submit that creates it — owns its `ws:{sessionId}` workspace and its options. A
  host whose older sessions were created without an owner keeps its `SessionOwnerLookup` too; it is asked only for
  those. Once any owner source is wired, a session no source knows the owner of gets no sandbox — with or without
  `require-principal`: there is no fallback to "whoever calls first". So a host that wires `sessionRecords(...)`
  before it sets `SubmitRequest.owner(...)` gets an unavailable environment on every main turn, its older sessions'
  included, until it sets the owner or keeps a `SessionOwnerLookup` that answers for them. Each main-turn bind reads
  the session's owner alone (core's `SessionRecordStore.loadOwner`), not its whole record.
- **Register the session lifecycle listener.** When core's session router deletes a session, it removes the
  session's sandbox options and closes and deletes its `ws:{sessionId}` workspace (a managed workspace it was attached
  to is shared and stays). Only the deleting node hears of it, which is enough: everything it removes is in the
  shared stores. A session deleted outside the router is cleaned up by `SandboxAdministration.deleteSessionOptions`,
  or by the janitor once `closed-retention` has passed (with `sessionRecords` wired). Re-creating a session under the
  same id right after deleting it gets "being removed; retry shortly" from its tools until the old sandbox is gone:
  normally up to `close-wait`, but when that background close is dropped (its executor refused it, `close()` cut it
  short, the node died) the janitor finishes it after `close-resume-after` plus one janitor period. The shell state
  such a session left in a managed workspace (`/workspace/.aimon-shell/session-{id}`) is not removed: a new session
  with the same id in the same workspace starts from its cwd and exports.
- **Notices reach every tool result.** A reset, a re-created sandbox or a workspace move is told to the model at the
  head of the next tool result of any kind — a `Read` too — once, as an `[environment]` line. `ShellCommandResult
  .notices()` now carries only what is true of that command (it was killed; its working directory was gone). A reset
  caused where core drains nothing — a Stop hook's command, a workflow step, a staging call — is kept on the session's
  options and told on the session's next tool result, a turn later if need be, so a custom `SessionOptionsStore` must
  persist `unannouncedReset`
  ([`docs/design/workspace-sandbox-unannounced-reset.md`](docs/design/workspace-sandbox-unannounced-reset.md)).

- **Use `shared(...)`, not `provider(Supplier)`.** The host owns the `WorkspaceSandbox` and closes it; the stack
  must not. `ExecutionEnvironmentSpec.factory` no longer exists in aimon-core 0.3.1.
- **Close the core stack first, then `WorkspaceSandbox`.** The stack's shutdown stops the background commands that
  are still running by signalling them through their shells, and a sandbox shell reaches its command over a
  connection the `WorkspaceSandbox` holds. Closed the other way round, those commands run on until their sandboxes
  go.
- **Background commands can be stopped, and they end.** `KillShell` stops a sandbox background command and what it
  started in its process group (a job the command moved out of the group with `setsid` or `set -m` survives until
  the sandbox goes). A command nobody stops ends at the profile's `background-command-timeout`, 24 hours by
  default (design §5.3).
- **Runtimes can come and go.** The provider keeps nothing per `AgentRuntime`, so evicting one stops no command and
  touches no sandbox; sandboxes live as long as their workspaces (design §3.2, §7).
- **Skill shell hooks run in the sandbox, and guards fail closed.** With aimon-core 0.3.1 a shell hook that a skill
  declares runs in the execution's sandbox shell, never on the host (with core's default
  `DefaultShellActionExecutor`; a host that installs another executor gets what that one does). When the sandbox is unavailable — the binding
  was refused, provisioning failed, the provider cannot be reached — a tool call guarded by a skill's `preTool`
  shell hook is **blocked** with the reason, and a skill fork with an `onStart` shell guard **does not start**.
  Give hooks that only observe `failOpen: true`. Hooks from `hooks.json` run on the host shell and do not depend on
  the sandbox. `OrcaRuntimeSandboxE2ETest` checks this against a real `OrcaAgentExecutor`. To refuse
  skill-declared shell hooks altogether, build core's `SkillHookSetParser` with `NoOpShellActionExecutor` (design
  §12.1). A hook's command runs outside the model's shell session: it takes no shell lock, starts from the
  session's cwd and exports, and changes neither (aimon-core 0.3.2's `ExecutionOptions.isHook()`).

## Managing workspaces

Without anything else, a session's first command creates its own workspace (`ws:{sessionId}`) and provisions it.
`WorkspaceSandbox.workspaces()` lets an application create a sandbox **before** any agent runs and put sessions into
it ([`docs/design/workspace-sandbox-management.md`](docs/design/workspace-sandbox-management.md)):

```java
SandboxWorkspaces workspaces = sandbox.workspaces();
WorkspaceView ws = workspaces.create(CreateWorkspace.named("ticket-4711"), alice);   // primary RUNNING on return
workspaces.setSessionOptions(sessionId, SessionSandboxOptions.Update.attach(ws.id()), alice);
// every turn of that session, and its forks, now runs in ws; the model hears once that it moved
workspaces.close(ws.id(), alice);
workspaces.purge(ws.id(), alice);                                                   // gone for good; id never reused
```

- **Ids are generated, never chosen** (`wsm:{uuid}`); the `name` is for people and never reaches the model. A
  failed first provisioning keeps the workspace and throws `WorkspaceProvisioningException` with its id: retry with
  `provision(id)` or `purge` it. One owner holds at most `max-managed-per-owner` (20) managed workspaces.
- **Session options need an owner source** — `sessionRecords(...)`, a `SessionOwnerLookup`, or both: only a
  session's owner sets them, to a workspace that owner may use. With `sessionRecords(...)` alone, a session's record
  exists only from its first submit, so options cannot be set before it: place that first turn with a request
  attribute instead, provision the record with `SessionRecordStore.provision(id, agentRef, owner)`, or keep a lookup.
  `clearSessionOptions` detaches a session but keeps a small record of where it last ran; the lifecycle listener
  removes it with the session.
- **One turn or one routine run can be placed by its host**, with request attributes:

  ```java
  live.submit(prompt, SubmitOptions.builder()
          .executionAttributes(SandboxRequestAttributes.workspace(ws.id())).build());
  schedulingSpec = schedulingSpec.withRoutineExecutionAttributes((task, executionId) ->
          placementOf(task.getId()).map(SandboxRequestAttributes::workspace).orElse(Map.of()));
  ```

  `environment.sandbox.workspace` binds that execution only — it writes no session option — and the principal it
  runs as must be allowed to use the workspace (a routine runs as its task's owner). `environment.sandbox.unattached:
  reject` refuses that execution when nothing else attaches it; it can only tighten. Forks inherit their parent's
  workspace and never see request attributes. Any other `environment.sandbox.*` key is refused. **Never fill them
  from model output.** Which workspace a root execution gets — the first that applies: the request attribute, the
  session option, the agent default, then the unattached rule (`ws:{sessionId}` or refused), whose layers —
  deployment setting, agent, session, request — can each only tighten the one before.
- **An agent default**: a definition's `attributes: { sandbox: { workspace: wsm:…, unattached: reject } }` binds every
  session of that agent that is not attached elsewhere, and `reject` refuses unattached sessions a sandbox. The
  `unattached` setting is the floor; a definition and a session's own option can only tighten it. Writing
  `sandbox.workspace` is the right to redirect every session of that agent.
- **Use and manage are separate rights.** Anyone the workspace's access admits may use it; only its owner closes,
  reopens or purges it.
- **A shared workspace is one trust domain.** Every session attached to it — and, with `access: tenant`, every
  principal of the tenant — can read and change every file, including the other sessions' shell state under
  `/workspace/.aimon-shell/`, which each command sources, and uses the same profile credentials. Sharing a managed
  workspace across principals in a `principal` deployment therefore needs `allow-shared-workspaces: true`.
- **Files do not persist yet.** A slot ends after its profile's `terminate-after`, and the next use starts from an
  empty `/workspace` (with the usual "recreated" notice). Persistent files come with shared volumes (step 5).

### Administration

`SandboxAdministration.of(sandbox)` is the operator's surface. It lists every workspace (by state, origin, name,
tenant and owner) with the sessions attached to it, and every sandbox the provider runs for the deployment with the
reconciler's classification of it before grace (`OK`, `IN_FLIGHT`, `ORPHAN`, `STALE`, …, identified by a hash, never
the raw sandbox id, with `pastGrace` where the janitor waits for one). It
can create on behalf of an owner, attach and detach any session or delete its options, terminate a slot, close,
reopen and purge:

```java
SandboxAdministration admin = SandboxAdministration.of(sandbox);
admin.sandboxes().forEach(view -> log.info("{} {}", view.refHash(), view.verdict()));
admin.terminateSlot(workspaceId, "primary", operator);   // the next use provisions a new generation
```

- **It checks no owner or tenant.** It is not a security boundary inside the JVM but a separate API surface: build
  it only behind your own operator authorization, and never hand it to ordinary callers.
- **Every write records the actor** on its `SandboxEvent` (`actor()`), so the audit log tells operator actions apart.
- An operator can attach a session only to a workspace the session's owner may use, and needs an owner source
  (`sessionRecords(...)` or a `SessionOwnerLookup`) for it too. Deleting a session's options needs no owner source:
  the session may be unknown by now, and a single-tenant host placing turns by request attribute has none.

## Build

```bash
./gradlew build            # compile + unit tests (no Docker needed)
./gradlew checkAll         # the gate: Spotless + Checkstyle + unit tests
./gradlew format           # apply formatting
./gradlew integrationTest  # @Tag("docker"): the provider against a real OpenSandbox server (needs Docker)
./gradlew k8sTest          # @Tag("k8s"): manual, against a provisioned cluster (see the provider's README)
```

CI (`.github/workflows/build.yml`) runs `checkAll` and `integrationTest` in parallel jobs, then a `coverage` job
that joins both tiers' JaCoCo data and checks the floors in
[`gradle/coverage-baselines.properties`](gradle/coverage-baselines.properties).

## Releasing

Releases are cut by [`scripts/release.sh`](scripts/release.sh), the same procedure as aimon-core's. It publishes
to Maven Central from the maintainer's machine and only then commits, tags and pushes, because a publish cannot be
taken back.

1. **Finalize the changelog in a PR.** Rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` and merge it to `main`.
   The script does not edit `CHANGELOG.md`; it warns when the section is missing, and the GitHub Release then
   carries only a link.
2. **Run the k8s tier.** `scripts/k8s-tier.sh up && scripts/k8s-tier.sh test` builds a kind cluster and runs
   `./gradlew :aimon-sandbox-opensandbox:k8sTest` against it with every check enabled; every test must pass, none
   skipped. `scripts/k8s-tier.sh down` removes the cluster. See the
   [provider's README](modules/aimon-sandbox-opensandbox/README.md). This tier is not in CI or in the gate.
3. **Dry run.** `scripts/release.sh patch --dry-run` runs the pre-flight checks and the gate (`checkAll
   integrationTest jacocoTestCoverageVerification`, the tasks CI runs) and changes nothing. You need a clean
   `main` in sync with `origin`, a running Docker daemon, and the publish credentials below.
4. **Release.** `scripts/release.sh patch --k8s-verified`. Without `--k8s-verified` the script refuses. It asks
   you to type the version, publishes, commits `VERSION_NAME=X.Y.Z`, tags `vX.Y.Z`, moves `main` to the next
   `-SNAPSHOT`, and pushes. From `0.1.0-SNAPSHOT`, `patch` releases `0.1.0`.
5. The pushed tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which checks the tag
   against `VERSION_NAME` and creates the GitHub Release from the matching `CHANGELOG.md` section. It publishes
   nothing.

The publish credentials go in `~/.gradle/gradle.properties` (or `ORG_GRADLE_PROJECT_*` env vars), never in GitHub
secrets: `mavenCentralUsername`, `mavenCentralPassword`, and either `signing.keyId` / `signing.password` /
`signing.secretKeyRingFile` or `signingInMemoryKey` (plus `signingInMemoryKeyId` and `signingInMemoryKeyPassword`
as your key needs). The script refuses to start while `OPENSANDBOX_TEST_ENDPOINT` or
`OPENSANDBOX_TEST_SANDBOX_IMAGE` is set, because either one points the docker tier away from what CI tests. It
also refuses, dry run included, while [`gradle/libs.versions.toml`](gradle/libs.versions.toml) pins a `-SNAPSHOT`:
release the dependency first and raise the pin.
`ReleaseGateMatchesCiGateTest` fails the build if the gate and CI stop matching.

## License

Apache 2.0 — see [LICENSE](LICENSE).
