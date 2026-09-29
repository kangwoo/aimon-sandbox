# Step 3 implementation design — `aimon-sandbox`: domain and local path

> Status: **IMPLEMENTED** (implementation step 3 of [`workspace-sandbox.md`](workspace-sandbox.md) §18). The body
> below is the design as approved; where the code departed from it, §12 says so and why. The architecture document
> [`workspace-sandbox.md`](workspace-sandbox.md) has absorbed this design's departures (§7) and decisions (§10), and
> its §20 carries the questions that outlive this step.

> Scope: `docs/design/workspace-sandbox.md` (below: **WS**) §18 item 3, and nothing from items 4+.
> Authority: WS is the architecture; this document is how step 3 builds it. Where step 3 cannot follow WS as
> written, the departure is listed in §7 with its reason and must be folded into WS in the same branch
> (TASK acceptance 3). aimon-core's SPI is consumed as `at.aimon.core:aimon-core:0.3.1-SNAPSHOT`; **no aimon-core
> change is required by this design** (§7.2 records why each candidate was not needed).
>
> Grounding: every name below was checked against WS, this repository at `fd4cc5b`, and aimon-core `main` at
> `d530c1d` (the published 0.3.1-SNAPSHOT).

---

## 1. Problem, restated

`modules/aimon-sandbox` is empty. Step 3 must fill it with everything the workspace sandbox needs *except a
production provider*: the workspace aggregate and its CAS store (with a reusable contract suite), the
`SandboxWorkspaceManager` that lazily provisions, owner-checks, closes/reopens (tombstones) and retries on CAS
conflicts, the `SandboxProvider` SPI, the default binding policy with tenant/owner resolution, and a
`SandboxExecutionEnvironmentProvider` implementing aimon-core's `ExecutionEnvironmentProvider` — declared descriptor,
read-only staging and shell areas, and shell state carried in files inside the sandbox (WS §9). Around that sit the
activity heartbeat, a janitor that enforces idle policy (terminate, idle close, resume stuck CLOSING, delete expired
tombstones), a default per-tenant admission, rejection of skill shell-action hooks, and startup validation. A new
`aimon-sandbox-testkit` provides the provider and store contract suites, a local-process provider and a
fault-injecting wrapper so all of this is tested without Docker. Only the `primary` slot and a single node are
supported; `isolate()` returns empty; profiles with `pauseAfter` are rejected at startup. Acceptance is `./gradlew
build` green on this macOS host and every WS §16 row with stage 3 covered by a passing test.

---

## 2. Boundaries

**In step 3** (WS §18-3, TASK "Scope"): §3.2 application singletons that step 3 needs, §5 (records, store, CAS rules,
activity throttling, heartbeat incl. `backgroundHeartbeatLimit`), §6.1 SPI types (all of them — the SPI is the
contract step 4 implements), §6.3 key/label computation on the manager side, §7 table except `isolate()`, §8.1–8.3
(bind, forkSlot default, tenant/owner checks, principal gate, profile stickiness), §9 in full except the cross-node
takeover *verification* (code path present, exercised in step 6), §10.1 for `primary` without PAUSED/resume, §10.2
`terminateAfter`/`closeAfter`, §10.3 expiry set on create and pushed by activity, §10.4 item 1 only (idle
enforcement), §10.5 without volumes, §11.1, §12.1 skill-hook rejection, §12.2 default admission, §13.1/§13.2
validation for the keys step 3 reads, §15 rows reachable in step 3.

**Not in step 3**: OpenSandbox provider, label *reconciliation* (ORPHAN/STALE/DUPLICATE/LOST-by-list, §10.4 items 2–3),
shared volumes and `sharedAccess ≠ none`, bare-repo seed, orchestrator tools (`OrcaSandboxToolProvider`), workspace
quota enforcement, non-`primary` slots, JDBC store, pause/resume, warm pool, `isolate()`/`SandboxWorktrees`,
snapshots, §14 metrics/spans (not listed in §18-3 — decision Q6; the `SandboxEventListener` lifecycle events *are* in
step 3), profile `credentials` (rejected at startup — decision Q3).

---

## 3. Approach, and what was rejected

### 3.1 Chosen approach

1. **Two new modules, packages exactly as WS §4.1.** `aimon-sandbox` gets `workspace/`, `binding/`, `provider/`,
   `profile/`, `environment/`, plus a root-package assembly `WorkspaceSandbox` (§7 D1). `aimon-sandbox-testkit`
   gets `at.aimon.sandbox.testkit`. No `tool/` package yet (step 5).
2. **Manager as a CAS state machine over one aggregate** (WS §5.3, §10.1): every write is
   read → decide → `update(id, expectedVersion, next)`; on `StaleVersionException` re-read and re-decide, up to
   `casRetries`. Provider calls happen only *after* the CAS that authorises them; losing a CAS after a provider call
   never destroys (WS §21).
3. **Filesystem = three layers**, so the core's public path-rule factory can be reused unchanged (§6.6):
   `SandboxFileSystem` (resolves relative paths against the binding `root`, reports `root` as working directory)
   → `VirtualFileSystems.withPathRules(…)` anchored at `/` (read-only `workspace/.aimon-staged`,
   `workspace/.aimon-shell`) → `ProviderFileSystem` (raw `SandboxFiles` adapter, working directory `/`).
4. **Shell = one `run` per command with a generated bash wrapper** (WS §9), bash-3.2 compatible so the local provider
   runs on this macOS host. Model output goes to per-run files inside the shell-state directory, not to the exec
   pipe (§6.5) — this is what makes "a leftover `sleep 600 &` does not hold the next command" and "truncate inside
   the sandbox" both hold.
5. **Time and scheduling are injected** (`java.time.Clock` + a small `SandboxScheduler`), so heartbeat, idle and
   backoff rows are tested with a manual clock instead of real hours.
6. **Local provider maps sandbox paths textually** (`/workspace` ↔ a per-sandbox temp dir) in commands, file paths
   and output — test-only, documented as such (§6.8).

### 3.2 Rejected alternatives

| Alternative | Why rejected |
|---|---|
| Let the environment's `VirtualFileSystem` report `/workspace/repo` as working directory and wrap it directly with `withPathRules` | `PathRuleVirtualFileSystem` resolves every path under the delegate's working directory and denies anything outside it (`VfsPaths.resolveUnder` returns `null` → "outside this filesystem"). `/workspace/.aimon-staged`, `/shared`, `/tmp` would all become unreadable — the staged skill copy included. |
| Add an "anchor" parameter to `VirtualFileSystems.withPathRules` in aimon-core | Works, but the three-layer composition gets the same result with zero core change; TASK asks to keep core changes minimal. |
| Capture model output from the exec pipe and truncate in the JVM | A backgrounded descendant (`npm run dev &`) inherits the pipe, so the exec never sees EOF and the next command waits (§16 row "`sleep 600 &`"); WS §9 also requires truncation inside the sandbox. |
| A `/proc`/`setsid`/util-linux-only wrapper | This host is macOS with bash 3.2, no `flock`, no `setsid`, no `/proc`. `./gradlew build` here is acceptance criterion 1; tests gated to Linux would leave most §16 rows unexercised here. The production wrapper stays POSIX + bash 3.2; the local provider replaces `setsid` with a perl `setpgrp` launcher and shims `flock` (and stubs `rg` when the host lacks ripgrep, as this one does) on its own PATH only (§6.8). |
| `SandboxConnection.layout()` (per-sandbox path root in the SPI) so the local provider needs no path translation | Puts a test-only concern into the production SPI, and the descriptor's `workingDirectory` must be known in `resolve()` *before* any connection exists (WS §7). |
| `@EnabledOnOs(LINUX)` on local-provider tests / running them in Docker | Same as above: the default `test` tier on the acceptance host would skip them, and the docker tier is reserved for the OpenSandbox provider (step 4). |
| JVM-side per-file hashes for staging verification (WS §11.1 wording) | Needs the per-file hashes of the source, which `StagedResource` does not keep — computing them re-reads the skill on every `stage()`, which `StagedResource`'s javadoc explicitly avoids. Instead the sandbox recomputes the **content key** with core's exact algorithm over the expected sorted file list, and the JVM compares it plus the exact file set (§6.7). No source re-read; as strong as core's own content key, but **slightly weaker than per-file hashes** (D6). |
| Metrics via Micrometer now | Not in §18-3; would add a dependency and a label design without a consumer (Q6). |
| Serialise staging only through the in-sandbox `.staged` marker (no JVM lock) | Two first activations of one skill in one slot (main turn + fork, parallel workflow steps — WS §8.4) would both see no marker; the second deletes the first's half-written copy and the first returns a path whose files are vanishing. Core's `LocalStaging` serialises this case (`synchronized` per target, marker re-checked inside), so callers of `stage()` rely on it (§6.7). |
| Publishing no testkit (as aimon-core's `aimon-session-testkit`) | WS §16 says *every* provider must pass the provider contract — third-party providers live outside this repo. aimon-core publishes `aimon-memory-testkit` for the same reason (Q5). |
| A Spring starter / `@ConfigurationProperties` for the §13.2 keys | This repository has no Spring; WS §13.2 calls the keys proposals. A plain builder (`SandboxSettings`) keeps the keys' names in javadoc for a later starter. |

---

## 4. Concrete changes by file

### 4.1 Build

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | `aimon-core = "0.3.1-SNAPSHOT"` and rewrite the header comment (the "bump lands with step 3" sentence becomes past tense; say a SNAPSHOT is pinned until aimon-core 0.3.1 is released, and that raising it back to a release is a release blocker). Add `slf4j-api` (`2.0.20`) and `jackson-databind` (`2.22.3`) — **aimon-core's `apiElements` exports no dependencies** (checked in its `.module`), so anything this module compiles against must be declared here; versions match core's runtime pins so the classpath resolves one version. |
| `build.gradle.kts` | In `allprojects.repositories`, add `mavenLocal { content { includeGroup("at.aimon.core") }; mavenContent { snapshotsOnly() } }` after `mavenCentral()` (group- *and* snapshot-filtered: `~/.m2` already holds stale `at.aimon.core` release artifacts), with a comment: only for the core SNAPSHOT published by `./gradlew publishToMavenLocal` in aimon-core; content-filtered so no other coordinate can be served stale from `~/.m2`; delete together with the SNAPSHOT pin. `buildSrc` does not resolve aimon-core and is left alone. |
| `settings.gradle.kts` | `include("aimon-sandbox", "aimon-sandbox-testkit")`, `projectDir` for the testkit, update the comment (testkit has joined; opensandbox and store-jdbc still to come). |
| `modules/aimon-sandbox/build.gradle.kts` | Keep `api(libs.aimon.core)`; comment no longer says "predates". Add `implementation(libs.slf4j.api)`, `implementation(libs.jackson.databind)` (rg `--json` parsing; not on any public signature). `testImplementation(project(":aimon-sandbox-testkit"))` — test-only edge, no cycle in the task graph (sandbox:main ← testkit:main ← sandbox:test). |
| `modules/aimon-sandbox-testkit/{build.gradle.kts,gradle.properties,README.md}` | New. `aimon.java-conventions` + `aimon.publishable` (Q5: published, first with the step 3+4 release). `api(project(":aimon-sandbox"))`, `api(platform(libs.junit.bom))`, `api(libs.junit.jupiter)`, `api(libs.assertj.core)` — the contract suites compile against JUnit/AssertJ in *main*; comment copies aimon-core testkits' reasoning. `POM_ARTIFACT_ID=aimon-sandbox-testkit`. |
| `gradle/coverage-baselines.properties` | Add `aimon-sandbox` and `aimon-sandbox-testkit` floors = measured line coverage rounded **down** to a whole percent, committed in the same commit as the tests they measure (Q7); rewrite the "Empty for now" comment to say how the floors were set. |
| `modules/aimon-sandbox/README.md`, `README.md`, `CHANGELOG.md` | Status: step 3 code exists, still **not usable in production** (no production provider until step 4, and they release together — WS §18-4). Module table gains the testkit row. CHANGELOG `[Unreleased]` gains "Added — workspace sandbox, step 3" with the core bump and the SNAPSHOT caveat stated as a **release blocker**: no release until aimon-core 0.3.1 is on Central, the pin is raised to it and `mavenLocal()` is removed (Q8). |
| `docs/design/workspace-sandbox.md` | Fold in every departure of §7 (D1–D14), and add the §10 decisions Q1–Q8 to WS §20 (each with the step that revisits it). |

### 4.2 `aimon-sandbox` — main sources (`at.aimon.sandbox.*`)

Conventions (checked against core and `config/checkstyle`): immutable `final` classes with hand-written builders and
`with*()` (not Lombok, not records — core has two records in total); ids as `final` classes with `of()`/`value()`
like core's `SessionId`; ≤ 7 parameters, methods ≤ 150 lines, lines ≤ 120; `log` constant name; javadoc on public
types.

**Exception supertypes.** `SandboxUnavailableException` and `BindingRejectedException` extend core's
`at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException` (constructor `(message, cause)`, cause
may be null). Core's tools catch exactly that type and return its message as the tool error — `WriteTool.java:191`,
`ReadTool.java:225`, `BashTool.java:319`, `SkillTool.java:356` — so the model sees the WS §15 wording; a plain
`RuntimeException` would fall into their generic `catch (Exception)` and lose it. `resolve()` can therefore throw
`BindingRejectedException` as is. Staging failures (source unreadable, content changed, size, name/key/path shape)
throw core's `StagingException`, which `SkillTool` catches alongside.

| Package | Types |
|---|---|
| root | `WorkspaceSandbox` (assembly, `AutoCloseable`), `SandboxSettings`, `SandboxConfigurationException`, `SandboxStartupValidator` (package-private) |
| `workspace` | `SandboxWorkspace`, `SandboxWorkspaceId`, `WorkspaceOwner`, `TenantId`, `WorkspaceState`, `CloseCause`, `SandboxSlot`, `SlotState`, `SlotFailure`, `ProvisioningClaim`, `WorkspaceQuota`, `SandboxWorkspaceStore`, `InMemorySandboxWorkspaceStore`, `WorkspaceScan`, `StaleVersionException`, `SandboxWorkspaceManager`, `ConnectedSlot`, `SandboxUnavailableException`, `SandboxJanitor`, `SandboxAdmission`, `DefaultSandboxAdmission`, `AdmissionDecision`, `SandboxEventListener`, `SandboxEvent`, `SlotActivity`, `Heartbeat`, `ActivityHeartbeat` (package-private), `SandboxScheduler` |
| `binding` | `SandboxBindingPolicy`, `DefaultSandboxBindingPolicy`, `BindingContext`, `SandboxBinding`, `SlotChoice`, `ShellKey`, `SandboxTenantResolver`, `SessionOwnerLookup`, `BindingRejectedException` |
| `provider` | `SandboxProvider`, `ProviderCapabilities`, `Capability`, `CreateSpec`, `ResourceSpec`, `ProviderSandbox`, `ProviderSandboxState`, `ProviderSandboxRef`, `SharedVolumes`, `ProviderVolume`, `VolumeRef`, `VolumeMount`, `SandboxConnection`, `RunningCommand`, `ExecSpec`, `ExecOutcome`, `OutputSink`, `SandboxFiles`, `FileStat`, `WriteMode`, `SandboxProviderException`, `SandboxNotFoundException`, `VolumeInUseException`, `SandboxConnectionCache`, `SandboxLabels` |
| `profile` | `SandboxProfile`, `SharedAccess`, `SeedSpec`, `SandboxProfileRegistry` |
| `environment` | `SandboxExecutionEnvironmentProvider`, `SandboxExecutionEnvironment`, `SandboxFileSystem`, `ProviderFileSystem` (package-private), `SandboxShell`, `ShellWrapper` (package-private script builder), `SandboxStaging` (package-private), `SandboxSeeder` (package-private), `SandboxContentSearch`, `PendingNotices` (package-private) |

### 4.3 `aimon-sandbox-testkit` — main sources (`at.aimon.sandbox.testkit`)

`SandboxProviderContract` (abstract JUnit 5), `SandboxWorkspaceStoreContract` (abstract JUnit 5),
`LocalProcessSandboxProvider` (+ `Builder`), `FaultInjectingSandboxProvider` (+ `Fault`, `Operation`),
`ManualClock`, `ManualScheduler`, `SandboxTestProfiles` (a profile that fits the local host: host platform,
`insecureAllow = {HARDENED_SECURITY_CONTEXT, NETWORK_ISOLATION}`, no runtime class/egress/credentials/seed).
Resources: `flock` shim (perl) and `rg` stub, each put on the local provider's PATH only when the host lacks the
real tool (§6.8).

### 4.4 Tests

`modules/aimon-sandbox/src/test/java/...` mirrors the packages (unit tests per class + the integration tests in
§9), `modules/aimon-sandbox-testkit/src/test/java/...` runs `LocalProcessSandboxProviderContractTest`,
`InMemorySandboxWorkspaceStoreContractTest` lives in `aimon-sandbox` tests (subject is there), and
`FaultInjectingSandboxProviderTest`.

---

## 5. Data and interface shapes

Signatures are illustrative; javadoc, builders and `equals` omitted.

### 5.1 Records (WS §5)

```java
public final class SandboxWorkspace {            // immutable, builder, with*()
    SandboxWorkspaceId id(); WorkspaceOwner owner(); WorkspaceState state();       // OPEN | CLOSING | CLOSED
    String incarnation();                          // 8 chars [a-z0-9], new on create and reopen
    Optional<VolumeRef> sharedVolume();            // always empty in step 3
    Optional<Instant> stateSince(); Optional<CloseCause> closeCause();             // EXPLICIT | IDLE
    Optional<Instant> retainVolumeUntil(); List<RetainedVolume> retainedVolumes(); // empty in step 3, kept for schema
    Map<String, SandboxSlot> slots(); WorkspaceQuota quota();                     // quota copied, enforced in step 5
    long version(); Instant createdAt(); Instant lastActivityAt();
}
public final class SandboxSlot {
    String name(); String profile(); String profileHash(); SlotState state();    // PROVISIONING|RUNNING|PAUSED|TERMINATED|FAILED
    long generation(); Optional<ProvisioningClaim> provisioning();                // (since, nodeId)
    Optional<ProviderSandboxRef> providerRef(); boolean seeded();
    Optional<Instant> missingSince();                                             // unused until step 4
    Optional<SlotFailure> failure();                                              // (at, kind, step, reason, attempts, profileHash)
    Instant lastActivityAt(); Optional<Instant> lastActiveAt(); Optional<Instant> lostAt();
}
```

`profileHash` on the slot (not only in `SlotFailure`) is needed so the permanent-failure rule "until the profile
changes" has something to compare against — it is the SHA-256 of the profile's canonical form, computed by
`SandboxProfile.contentHash()`.

### 5.2 Store (WS §5.3 + D2)

```java
public interface SandboxWorkspaceStore {
    Optional<SandboxWorkspace> find(SandboxWorkspaceId id);
    SandboxWorkspace createIfAbsent(SandboxWorkspace initial);   // stored with version 1; returns the winner
    SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next);
                                                                 // stores next with version expected+1; StaleVersionException
                                                                 // also when the record is absent
    void delete(SandboxWorkspaceId id, long expectedVersion);    // D2; StaleVersionException on mismatch; absent -> no-op
    List<SandboxWorkspace> scan(WorkspaceScan scan);             // filters: states, tenant, lastActivityBefore,
                                                                 // stateSinceBefore; keyset paging: afterId + limit
}
```

`InMemorySandboxWorkspaceStore`: `ConcurrentHashMap` + `compute` for atomic CAS; `scan` sorts by id for stable
paging. Javadoc says **single node only** (WS §5.3).

### 5.3 Binding (WS §8 + D3)

```java
public final class SandboxBinding {
    SandboxWorkspaceId workspaceId(); WorkspaceOwner owner();
    WorkspaceOwner caller();                 // D3: tenant + TYPE:id of the executing principal
    String slot(); Optional<String> requiredProfile(); ShellKey shellKey(); String root();
}
public interface SandboxBindingPolicy {
    SandboxBinding bind(BindingContext ctx);                                   // throws BindingRejectedException
    default SlotChoice forkSlot(SandboxBinding parent, ForkDefinition fork) {
        return SlotChoice.fromAttributes(fork.attributes(), parent);
    }
}
public interface SandboxTenantResolver { TenantId resolve(Principal principal); }   // default: TenantId.of("default")
public interface SessionOwnerLookup { Optional<Principal> ownerOf(SessionId sessionId); }
```

`ShellKey`: `session:{sessionId}` or `exec:{executionId}`; `ShellKey.directoryName()` = `h(value)` (§5.4).

### 5.4 Provider SPI (WS §6.1, verbatim shape)

```java
public interface SandboxProvider extends AutoCloseable {
    ProviderCapabilities capabilities();
    ProviderSandboxRef create(CreateSpec spec);
    Optional<ProviderSandbox> status(ProviderSandboxRef ref);
    void pause(ProviderSandboxRef ref);  void resume(ProviderSandboxRef ref);
    void extendExpiry(ProviderSandboxRef ref, Instant until);
    void destroy(ProviderSandboxRef ref);
    List<ProviderSandbox> list(Map<String, String> labels);
    Optional<SharedVolumes> sharedVolumes();
    SandboxConnection connect(ProviderSandboxRef ref);
}
public final class ProviderCapabilities { Set<Capability> advertised(); Optional<Duration> maxExpiry(); }   // D4
public interface SandboxConnection extends AutoCloseable { RunningCommand run(ExecSpec spec, OutputSink sink); SandboxFiles files(); }
public interface RunningCommand { ExecOutcome await(Duration timeout) throws InterruptedException; void kill(); }
public interface SandboxFiles {
    InputStream read(String path, long offset, long length);        // length < 0: to end
    void write(String path, InputStream content, long length, WriteMode mode);  // CREATE_OR_REPLACE, mkdirs parents
    Optional<FileStat> stat(String path);                           // size, mtime (ms+), directory, etag?
    List<String> list(String dir, boolean recursive, int limit);
    void delete(String path, boolean recursive);  void move(String from, String to, boolean overwrite);
}
```

`ExecSpec{command, workingDirectory, environment(profile env only), timeout, maxCaptureBytes}`;
`ExecOutcome{exitCode, stdout, stderr, stdoutTruncated, stderrTruncated, timedOut}`. `maxCaptureBytes` is per stream.
For wrapped shell commands it is only a backstop and must be **strictly larger than the wrapper's in-sandbox cap plus
the trailer**: `SandboxShell` sets it to `max + TRAILER_ALLOWANCE` (1 KiB; the trailer is < 128 bytes) where `max` is
the `head -c {max}` of §6.5, and checks the relation once at construction. Were the two equal, a command whose stderr
reaches `max` would have its trailer cut by provider-side truncation and be misclassified as a wrapper failure.
`SandboxFiles` throws core VFS
exceptions (`FileNotFoundException`, `FileAlreadyExistsException`, `VirtualFileSystemException`) — the SPI already
lives next to core, so no second exception family. `SandboxProviderException` carries `kind`
(`TRANSIENT` default / `PERMANENT`), which is what §10.1's failure classification reads.

`SandboxLabels`: `h(s)` = first 32 chars of base32(SHA-256(s)), lower-cased; builds the §6.3 label map and the
`CreateSpec.key` `"{deployment}/{workspaceId}/{incarnation}/{slot}/{generation}"`. Used by the manager to build
`CreateSpec` and to verify returned labels (§6.3 "돌려받은 샌드박스는 대조한 뒤에만 쓴다").

### 5.5 Profiles and settings (WS §13)

`SandboxProfile`: `name, image, platform, osVersion?, shellName (default bash), resources(cpu, memory, disk, pids),
runtimeClass?, egress: Optional<List<String>>` (absent ≠ empty — WS §6.4), `credentials, env, pauseAfter?,
terminateAfter, backgroundHeartbeatLimit (1h), sharedAccess (NONE), seed?, insecureAllow`.
`requiredCapabilities()` implements the WS §6.4 derivation plus `EXEC`, `FILES`, minus `insecureAllow`.

`SandboxSettings` carries only keys step 3 reads: `deployment` (required), `requirePrincipal`, `workspaceAccess`
(`PRINCIPAL`|`TENANT`), `allowedSystemPrincipals`, `provisionTimeout` 5m, `failureBackoff` 1m, `maxFailureBackoff`
15m, `activityWriteInterval` 30s, `casRetries` 5, `closeWait` 2m, `closeResumeAfter` 5m, `closedRetention` 7d,
`execShellIdle` 10m, `shellLockWait` 10s, `janitorInterval` 30s, `maxRunningPerTenant` 10 or `unlimited`,
`defaultProfile`, `profiles`, `closeAfter` 24h, `quota` (copied into records), `nodeId` (default
`{hostname}-{uuid}`, fresh per JVM — WS §9). `orphanGrace`, `lostConfirmAfter`, volume and orchestrator keys arrive
with the step that reads them (a key nothing reads rots — the catalog's own rule).

### 5.6 Manager and assembly

```java
public final class SandboxWorkspaceManager {
    ConnectedSlot connect(SandboxBinding binding);            // SandboxUnavailableException (an ExecutionEnvironment-
                                                              // UnavailableException, §4.2) with a model-facing reason
    void close(SandboxWorkspaceId id, Principal caller);      // owner-checked
    void reopen(SandboxWorkspaceId id, Principal caller);     // owner-checked; CLOSED -> OPEN, new incarnation
    // package-private, used only by SandboxJanitor (same package):
    boolean closeProcedure(SandboxWorkspaceId id, CloseCause cause, Predicate<SandboxWorkspace> guard);  // §12-28, §12-34
}
public final class ConnectedSlot {
    SandboxWorkspace workspace(); SandboxSlot slot(); SandboxConnection connection(); List<String> notices();
    SlotActivity activity();                                  // bound to (workspace, slot, generation, providerRef)
}
public interface SlotActivity {                               // public: SandboxShell (environment/) is the caller
    void record(boolean force);                               // throttled lastActivityAt CAS + extendExpiry (§6.3)
    Heartbeat startHeartbeat(boolean background, Runnable onLost);   // Heartbeat extends AutoCloseable
    void markLost();                                          // CAS guarded by generation + ref
}
```

`SlotActivity` is the public seam between `environment/` and `workspace/`, so the package-private manager internals
stay package-private in a published module. It is reachable only through a `ConnectedSlot`, i.e. after `connect`'s
owner check, and is bound to one generation: after a regeneration the old handle's writes are no-ops (CAS guard).
`ActivityHeartbeat` (package-private) implements `Heartbeat`.

```java

WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
        .settings(settings).provider(provider)          // provider borrowed unless .ownProvider(true)
        .store(store)                                   // default InMemorySandboxWorkspaceStore
        .tenantResolver(..).sessionOwnerLookup(..).admission(..).eventListener(..).bindingPolicy(..)
        .clock(Clock.systemUTC()).scheduler(SandboxScheduler.daemon())
        .build();                                       // runs SandboxStartupValidator -> SandboxConfigurationException
sandbox.environmentProvider(); sandbox.manager(); sandbox.janitor().start();
WorkspaceSandbox.skillHookSetParser();                  // new SkillHookSetParser(NoOpShellActionExecutor.INSTANCE)
WorkspaceSandbox.markdownSkillParser();                 // MarkdownSkillParser wired with the parser above
```

---

## 6. Mechanisms

### 6.1 Resolve (WS §7, §8.1–8.2)

`SandboxExecutionEnvironmentProvider.resolve(request)` — no provider call, one store read at most:

1. `request.fork().isPresent()` → fork path: parent absent → unavailable "fork without a parent environment (EE-30)";
   parent `UnavailableExecutionEnvironment` → unavailable carrying `parent.message()`; parent not a
   `SandboxExecutionEnvironment` → unavailable; no `executionId` → unavailable. Otherwise
   `policy.forkSlot(parentBinding, fork)` picks slot/profile, the provider forces workspace/owner/root from the parent,
   `shellKey = exec:{executionId}`, `caller` from the request principal (principal gate applied).
2. Otherwise `policy.bind(BindingContext.from(request))`.
3. Step-3 guard: `slot != primary` → unavailable "only the primary slot is supported (slots arrive with step 5)".
4. Return `new SandboxExecutionEnvironment(binding, …)`. Failures are thrown as
   `ExecutionEnvironmentUnavailableException`; the core publishes `UnavailableExecutionEnvironment` (closed failure).

Default `bind` (WS §8.2 table): principal gate (§8.3: `requirePrincipal` ⇒ USER/GROUP, or SYSTEM/SERVICE listed in
`allowedSystemPrincipals`; off ⇒ absent principal becomes the typeless `anonymous`), `ws:{sessionId}` else `ws:{executionId}` else
reject, owner from `SessionOwnerLookup` for sessions (absent bean in single-tenant ⇒ caller; lookup empty ⇒ reject),
routine owner = request principal, tenant via resolver, slot from `sandbox.slot` else `primary`, required profile from
`sandbox.profile` (must exist), shellKey `session:`/`exec:`, root `/workspace/repo`.

### 6.2 Connect (WS §10.1, step-3 subset)

Loop up to `casRetries`, restarting on `StaleVersionException`:

1. `find` or `createIfAbsent(OPEN, owner=binding.owner, new incarnation, quota from settings)`.
2. Owner check (tenant always; principal when `workspaceAccess=PRINCIPAL`) **before any state-dependent branch** —
   the message reveals nothing beyond "not permitted" (WS §15). A foreign caller therefore neither triggers the
   idle-reopen CAS (and consumes its notice) nor learns that the workspace is closed. This reorders WS §10.1 steps
   2–3; recorded with D5 as D10.
3. `CLOSED ∧ closeCause=IDLE` → CAS reopen (new incarnation, slots TERMINATED) + notice "/workspace was reset";
   `CLOSED ∧ EXPLICIT` or `CLOSING` → unavailable "workspace closed". Slot-profile check: `requiredProfile` present
   and slot exists with another profile ⇒ unavailable naming both. A slot whose recorded profile is no longer in
   `SandboxProfileRegistry` (removed by a redeploy) ⇒ unavailable "profile X is no longer configured".
4. Slot: absent/TERMINATED → `admission.admit(owner, profile)` → CAS PROVISIONING(gen+1, claim(now,nodeId), profile =
   existing slot's profile, else required, else **current** default; `profileHash`) → `create(spec, expiresAt =
   now + terminateAfter)` → `status` + label verification → CAS RUNNING(ref). `create` failure → CAS
   FAILED(kind from exception, step `create`, attempts+1). Label mismatch → FAILED(permanent, `labels`).
   FAILED permanent → error with recorded failure while `profileHash` is unchanged; FAILED transient → error until
   `failure.at + min(failureBackoff·2^(attempts-1), maxFailureBackoff)`, then as TERMINATED. PROVISIONING by someone
   else within `provisionTimeout` → poll (100 ms doubling to 2 s) until `provisionTimeout` from *our* start, then error;
   past `since + provisionTimeout` → takeover CAS (same generation, new claim) → create. PAUSED → cannot occur in step 3
   (no `PAUSE_RESUME`); defensive unavailable.
5. `!seeded` → `SandboxSeeder` (§6.9) → CAS `seeded=true`, or FAILED(permanent, step).
6. `connectionCache.get(ref)`; notice "sandbox provisioned in {n}s" when provisioning+seed took ≥ 5 s.

Every command re-runs connect (reads are per command, writes throttled — WS §5.3) and only sends the command when the
slot is RUNNING and its generation equals the cached connection's (WS §5.3 last paragraph).

### 6.3 Activity and heartbeat (WS §5.3, §10.3)

- At command start: if `now − slot.lastActivityAt ≥ activityWriteInterval` → CAS `lastActivityAt = max(old, now)` on
  slot and workspace (retry on conflict, never dropped) and `extendExpiry(ref, now + terminateAfter)`.
- While a command runs, `ActivityHeartbeat` (scheduled every `activityWriteInterval` on `SandboxScheduler`) forces
  that write + `extendExpiry`; foreground commands additionally rewrite `.aimon-shell/{h}/heartbeat`. Background
  commands stop heartbeating after the profile's `backgroundHeartbeatLimit` and never touch the lock heartbeat.
- `extendExpiry` → `SandboxNotFoundException` ⇒ `markLost` (CAS slot TERMINATED, `lostAt`, guarded by
  generation+ref), kill the running command, and fail it with "sandbox lost mid-command; the environment will be
  recreated on the next call and /workspace will be reset" (WS §15).

### 6.4 Janitor (WS §10.4 item 1, §10.5)

`SandboxJanitor.runOnce()` (scheduled every `janitorInterval`, also called directly by tests):
(a) scan OPEN workspaces; for each RUNNING slot with `now − slot.lastActivityAt ≥ profile.terminateAfter` →
re-read, re-check, CAS TERMINATED(`lastActiveAt=now`) → `destroy` → event. A slot whose profile is no longer
configured uses the **longest** `terminateAfter` among configured profiles (conservative: it is never terminated
earlier than any live profile would be; its provider expiry, set with the old value, remains the backstop);
(a′) `lastActiveAt = now` is stamped by **every** transition that leaves PROVISIONING/RUNNING/PAUSED — janitor (a),
FAILED from `create`/seed/labels, `markLost`, close — per WS §5.2, so a workspace whose only slot failed is not
idle-closed early;
(b) OPEN workspaces with no RUNNING/PAUSED/PROVISIONING slot and `now − max(createdAt, openedAt, slots'
lastActiveAt) ≥ closeAfter` → `closeProcedure(IDLE, idle guard)`, where `openedAt` is the `stateSince` stamped by every transition
into OPEN (create, explicit `reopen`, idle auto-reopen) — so a reopened workspace gets a full `closeAfter` before it
can be idle-closed again, even if its first provisioning is rejected by admission;
(c) CLOSING with `now − stateSince ≥ closeResumeAfter` → `closeProcedure(stored cause, "still CLOSING with the scanned incarnation")`;
(d) CLOSED (either cause) with `now − stateSince ≥ closedRetention` → `delete(id, version)`. The next `connect` for
that id creates a brand-new workspace **without** a reset notice — nothing is left to tell it from a new session
(Q1, D12).
Each item is isolated (one failure is logged and the loop continues).

**Close procedure** (`close` and the janitor's guarded `closeProcedure` — §12-28, §12-34; WS §10.5 without the volume steps), every step idempotent so (c) can
rerun it from the top:
1. CAS OPEN → CLOSING(`stateSince=now`, `closeCause`); already CLOSING ⇒ keep the stored cause; CLOSED ⇒ no-op.
2. For every slot not TERMINATED: CAS TERMINATED(`lastActiveAt=now`), then `destroy(providerRef)` if it has one
   (a PROVISIONING slot without a ref has nothing to destroy); `destroy` is idempotent.
3. Poll `provider.list({deployment, workspace=h(id)})` until empty or `closeWait` passes; on timeout log WARN and go on
   (step-3 has no reconciliation, so a straggler is left to provider expiry — §8).
4. Volume steps: none in step 3 (`sharedAccess` is always `none`).
5. CAS CLOSING → CLOSED(`stateSince=now`, cause kept) — for both causes under D5; event `workspace-closed`.

Node-local, the connection cache also sweeps
`exec:` shell directories idle for `execShellIdle` (only when `flock -n` shows the lock free), and drops the
shell semaphores and staging locks (§6.7) of a `providerRef` when its connection is evicted — an entry is removed
only while no thread holds or waits on it, so a running `stage()` never has its lock replaced under it.

### 6.5 Shell wrapper (WS §9)

One `run` per command. The wrapper is generated by `ShellWrapper` with **every dynamic value single-quoted** —
including the model's command, which is never pasted into the script's syntax — and is **two bash processes**: an
*outer* wrapper that owns the exec's stdout/stderr, the lock and the trailer, and an *inner* bash that restores the
shell state, runs the command and saves the state. Per-run files live in
`/workspace/.aimon-shell/{h}/run-{runId}.{out,err,in,timedout}`; `runId` and a random `nonce` come from the JVM.

```bash
# outer — its fds 1/2 are the exec's pipes and are never redirected
__aimon_cmd='{command, with every ' written as '\''}'      # data, not syntax
d=/workspace/.aimon-shell/{h}; mkdir -p "$d"
exec 9>"$d/lock"; flock -w {lockWait} 9 || exit 75        # foreground only; no trailer on this path
printf '%s %s %s\n' {nodeId} $$ "$(start_time $$)" > "$d/owner"   # /proc/$$/stat field 22, "-" when no /proc
( trap 'kill "$s" 2>/dev/null; exit 0' TERM                # watchdog: its sleep dies with it
  sleep {timeoutSecs} & s=$!; wait "$s"
  : >"$d/run-{id}.timedout"; kill -KILL 0 ) 9>&- </dev/null >/dev/null 2>&1 & wd=$!
/bin/bash -c "$__aimon_inner" aimon "$d" "$__aimon_cmd" {fg|bg} \
    9>&- >"$d/run-{id}.out" 2>"$d/run-{id}.err" <"${stdin:-/dev/null}"   # 2>&1 when redirectErrorStream
rc=$?; kill "$wd" 2>/dev/null
head -c {max} "$d/run-{id}.out"; head -c {max} "$d/run-{id}.err" >&2
printf '\n%s exit=%d out=%d err=%d cwd=%d\n' {nonce} "$rc" "$(wc -c <…out)" "$(wc -c <…err)" "$cwdflag" >&2

# inner ($__aimon_inner, a constant) — $1=dir, $2=command, $3=fg|bg
__aimon_base=$(export -p)
[ -r "$1/state" ] && . "$1/state"                          # cwd + exported vars (missing cwd -> root + flag file)
__aimon_dir=$1; __aimon_mode=$3; __aimon_nosave=0
readonly __aimon_dir __aimon_base __aimon_mode             # the command cannot redirect or widen the save
trap '__aimon_nosave=1; exit 143' TERM                     # kill(): SIGTERM -> previous state kept
[ "$__aimon_mode" = fg ] && trap '[ $__aimon_nosave = 1 ] || __aimon_save' EXIT
                         # tmp + mv into $__aimon_dir; export -p minus $__aimon_base minus __aimon_* names
eval "$2"
```

- **The command is data.** `eval "$2"` runs in the inner bash's own process, so `cd`/`export` persist through the
  inner EXIT trap, and the command's text cannot interact with any wrapper syntax: a trailing `# comment`, a heredoc
  (`cat <<EOF … EOF`), or an unbalanced quote is parsed by `eval` alone. A syntax error becomes the command's own
  non-zero exit (1 or 2 depending on the error). Single-quoting (`'` → `'\''`) is the only escaping and is total for
  any byte string without NUL (a NUL in the command is rejected by `SandboxShell` before `run`).
- **Why two processes** (review-2). With a single process, a command that ends the shell — `exit N`, `set -e` then a
  failing command, `test -f x || exit 1` — runs the EXIT trap while the command's redirections to the run files are
  still in effect, so the output and trailer land in the run files and the exec returns nothing. Saving the pipe fds
  (`exec 7>&1 8>&2`) does not fix it either: they must stay open for the trap, so a descendant such as `sleep 600 &`
  inherits them and holds the exec pipe open again. In the two-process form, whatever the command does ends only the
  inner bash; the outer's fds 1/2 were never redirected and are not inherited by the inner (it gets the run files), so
  `exit 75`, `exit 0`, `exit 3` and `set -e; false` all return the command's output plus a trailer carrying its own
  exit code. Verified on this host's `/bin/bash` 3.2.57, including that a backgrounded descendant does not delay the
  exec, that `cd`/`export` persist to the next run, and that a SIGTERM to the group leaves the previous state file
  untouched and produces no trailer.
- **Wrapper state is out of the command's reach** (review-3). The command is `eval`'d in the inner bash, so it
  shares that shell's positional parameters and variables. The traps therefore read nothing the command can
  plausibly change: the state directory, the base snapshot and the mode are copied into `__aimon_`-prefixed variables
  *before* the `eval` and made `readonly`, and `__aimon_save` takes no arguments. `set -- src` or `base=…` in the
  command (both checked under bash 3.2 to misdirect or widen the save when the trap read `$1`/`$base`) now have no
  effect on the save. Assigning to a `readonly` `__aimon_*` name is bash's own error ("readonly variable") and ends
  the command's list — the state is still saved. The save excludes `__aimon_*` names, so the restore can never
  overwrite them either. A command that redefines `__aimon_save` or resets the traps can still defeat the save; shell
  state is best-effort (WS §9) and the `__aimon_` prefix is reserved in `ShellWrapper`'s javadoc.
- **The watchdog leaves nothing behind** (review-3). `kill "$wd"` alone kills the watchdog subshell but not its
  `sleep`, so every foreground command would leave one `sleep` alive for its full timeout and a burst of short
  commands could exhaust the profile's `resources.pids`. The watchdog runs `sleep` in the background, `wait`s on it,
  and its TERM trap kills that pid before exiting; a trapped TERM interrupts `wait` at once. Checked on this host's
  bash 3.2.57: after `kill "$wd"` no `sleep` remains, and when the sleep runs out the `.timedout` file is written and
  `kill -KILL 0` fires.
- `9>&-` keeps the lock fd from the inner bash and every descendant; descendants inherit run *files*, not the exec
  pipe, so the exec ends when the outer ends.
- The watchdog uses `kill -KILL 0`: a background subshell of a non-job-control shell shares the outer's process group,
  so this reaches the whole group without assuming the outer *leads* it (execd may interpose `sh -c`; §18-2 confirms).
- The trailer is `\n{nonce} exit=N out=BYTES err=BYTES cwd=0|1`. `SandboxShell` strips it (last occurrence of
  `"\n"+nonce`), sets truncation flags from the byte counts and deletes the run files.
- `options.isRedirectErrorStream()` (core `BashTool` asks for it) is honoured: the inner bash gets `2>&1` into the
  `.out` file, so interleaving is preserved and `stderr` is empty; truncation then applies to the merged stream.
- Lock wait is outside the command timeout: the watchdog starts after `flock`; the node-local semaphore (per
  `(providerRef, shellKey)` in `SandboxConnectionCache`) is also bounded by `shellLockWait`. `ExecSpec.timeout =
  commandTimeout + shellLockWait + 5 s` is only a backstop that triggers `RunningCommand.kill()`.
- **Outcome classification.** First, if the JVM itself stopped the command (thread interrupt, backstop, sandbox
  lost — it called `kill()`), that is the outcome, whatever came back. Otherwise: trailer present ⇒ the command ran,
  its exit code (including its own `exit 75` or 137) is reported as-is; no trailer + `.timedout` ⇒ timeout; no
  trailer + exit 75 ⇒ lock busy; anything else ⇒ wrapper failure (error with the raw exit and stderr).
- Timeout ⇒ `ShellTimeoutException` with partial output read from the run files via `files()`, plus notice "the
  command was killed; its cd/export were not applied". This notice is true on every kill path: the watchdog's
  SIGKILL and `kill()`'s SIGKILL never run the inner trap, and `kill()`'s SIGTERM hits the inner's TERM trap, which
  sets `__aimon_nosave` before exiting. (A command that itself resets the TERM trap can defeat this — shell state is
  best-effort, WS §9.)
- Lock busy ⇒ owner/heartbeat inspection per WS §9 (dead-node takeover or "shell is in use by a command on another
  node"). The branch exists in step 3; its multi-node rows are step 6.
- Background (`options.isBackground()`, arg `bg`): no lock, no state save, no watchdog (the JVM enforces the 24 h core
  timeout via `await`/`kill`), heartbeat per §6.3.
- `options.getWorkingDirectory()` / `getEnvironment()` ⇒ the inner runs `( cd -- "$4" && export K="$5" … && eval "$2" )`
  with the directory and values passed as further single-quoted arguments, so state is untouched (WS §9). They
  are read before the command runs, so the command changing `$@` does not matter here.
- `head -c {max}` is the real per-stream cap; `ExecSpec.maxCaptureBytes` is `max + 1 KiB` so provider-side
  truncation can never cut the trailer (§5.4).
- Notices on `ShellCommandResult.notices()`: shell state missing for a known shellKey after a generation change,
  generation changed, sandbox recreated after loss, workspace reset (queued in `PendingNotices` per binding so a reset
  first observed by a file tool is still reported on the next shell result), previous command from another node
  terminated, provisioning time.

### 6.6 Filesystem (WS §7, §11.1)

`SandboxFileSystem` → `withPathRules(ProviderFileSystem, [readOnly("workspace/.aimon-staged"),
readOnly("workspace/.aimon-shell")])` → `ProviderFileSystem`. `ProviderFileSystem.getWorkingDirectory()` is `/`, so
the rule wrapper sees every absolute sandbox path; `SandboxFileSystem` turns relative paths into
`{root}/{path}` *before* delegating and reports `root`. No other path restriction (WS §11.1). Each call starts with
`manager.connect(binding)` (lazy provisioning). `getMetadata` maps `FileStat` to `FileMetadata` with the etag when the
provider gives one. `copy` = read + write (no copy in `SandboxFiles`).

### 6.7 Staging (WS §7, §11.1)

`stage(resource)` → target `/workspace/.aimon-staged/{name}/{contentKey}`. Core's `LocalStaging.stage` is the
reference; every check it makes is made here too, so a caller of `ExecutionEnvironment.stage()` sees the same
contract on either environment — including **safety under concurrency**: several executions share one slot (WS §8.4,
§16 row 1), and SkillTool, skill forks and slash commands all call `stage()` (WS §11.1).

0. **Shape checks, before anything touches the sandbox** (as core). A resource whose source filesystem *is* this
   environment's filesystem (any layer of §6.6) and whose source directory exists is returned as is, uncopied — core's
   in-workspace case. Otherwise: `name` is one path segment (not empty, `.`,
   `..`, no `/` or `\`); `contentKey` matches `[0-9a-f]{16}`; every `getFiles()` entry is a confined relative path (no
   leading `/`, no `\`, no empty/`.`/`..` segment); `getTotalBytes() ≤ DEFAULT_MAX_STAGED_BYTES` (core's constant,
   50 MiB) with core's wording pointing at `.stageignore`. Any failure ⇒ `StagingException`.
1. **Take the staging lock** for `(providerRef, target)`: a `ReentrantLock` from `SandboxConnectionCache` (next to the
   shell semaphores, same eviction rule — §6.4), acquired with `lockInterruptibly()` and no timeout, like core's
   `synchronized` block. Items 2–4 run under it, and items 3–4 are the only code that deletes or writes under
   `.aimon-staged/{name}/`. A node-local lock is enough because implementation step 3 is single-node; item 4's
   temp-dir-and-move already makes the in-sandbox result appear atomically, so the multi-node case (implementation
   step 6) needs only its already-described `FileAlreadyExistsException` branch, not a different procedure.
2. **Check inside the lock** (the marker re-check core does): if `{target}/.staged` exists, run one stateless exec
   (no wrapper, `PATH=/usr/bin:/bin`) that prints the file list under the target and the content key recomputed over
   the JVM-supplied sorted `resource.getFiles()` with core's algorithm: `for f; do printf '%s\0' "$f"; cat -- "$f";
   printf '\0'; done | /usr/bin/sha256sum`. When the key's first 16 hex equal `contentKey` **and** the listed set equals
   `getFiles() ∪ {.staged}`, release the lock and return the target — a concurrent caller that waited on the lock
   lands here and returns the copy the first caller finished. There is no unlocked fast path: the verification exec is
   the only check, and it must not race a copy.
3. **Copy into a temp directory**: `tmp = /workspace/.aimon-staged/{name}/.tmp-{contentKey}-{nonce}` (a name the
   16-hex key directory can never have). First delete leftovers `.tmp-{contentKey}-*` of an earlier crashed copy (safe:
   the lock excludes any live copy of this target on this node). Read each file of `getFiles()` in order from the
   source (`resource.getSourceFileSystem()` at `resource.sourcePath(rel)`), feed its bytes to a
   `StagedResource.ContentKeyBuilder`, and write them with `files().write(tmp/rel, …)` (default mode — core's local
   staging sets none either). A read failure ⇒ delete `tmp`, `StagingException` naming the file. After the last file,
   `builder.build()` must equal `contentKey`; otherwise delete `tmp` and throw `StagingException` with core's wording
   ("Skill '{name}' changed on disk after it was loaded … Restart the application, or reload the skill registry"). This
   keeps parity with core's `LocalStaging.copy`: without it a changed skill would be copied under the old key, and every
   later `stage()` would fail the key check, recopy, and run the changed content. The bytes are already in the JVM, so
   the hash costs nothing. Write `tmp/.staged` (content: the key) last.
4. **Move into place**: if `target` exists (no marker, a failed verification, or planted content — §16 row 18),
   `files().delete(target, recursive)`; then `files().move(tmp, target, overwrite=false)`. On
   `FileAlreadyExistsException` (only possible multi-node, step 6) re-run step 2's verification: valid ⇒ delete `tmp`
   and return; invalid ⇒ delete target and move again, once, else `StagingException`. The target thus only ever
   appears complete with its marker. The lock is released in `finally`.
5. Returns the absolute target path.

Deleting a target that failed verification can still remove files a model command is using, but only files that were
tampered with after a successful stage (the shell can write under `.aimon-staged`; the file tools cannot) — a
successful verification is never followed by a delete while the lock is held.

### 6.8 `LocalProcessSandboxProvider` (testkit only; WS §16)

- Per sandbox: `{base}/{sandboxId}/workspace` (base `toRealPath()`'d so macOS `/var`→`/private/var` does not leak).
- **Path translation**: `/workspace` ↔ that directory, and `/usr/bin/sha256sum` → the host's `sha256sum`, applied at
  path boundaries to `ExecSpec.command`, `workingDirectory` and every `SandboxFiles` path; the reverse mapping is
  applied to stdout/stderr. Javadoc lists the limit (paths assembled at runtime are not translated) and "no isolation,
  never use in production".
- Process group: `perl -e 'setpgrp(0,0); exec @ARGV' bash -c …`; `kill()` = `kill -TERM -- -pgid`, then `-KILL` after
  a grace. PATH is prefixed with a shim dir holding a perl `flock` (only `-w N FD` and `-n FD`) when the host lacks one.
- **Ripgrep.** Probed once per provider with `/bin/bash -c 'command -v rg'` (the same lookup the seed does — a
  login-shell function or alias does not count). When absent — as on the acceptance host today — the shim dir also
  gets an `rg` **stub** that prints `ripgrep is not installed on this host (LocalProcessSandboxProvider stub)` to
  stderr and exits 2. The stub satisfies the seed's image-contract `command -v` check, so provisioning works; any real
  content search fails loudly instead of returning "no matches" (rg's exit 1). `LocalProcessSandboxProvider
  .hostHasRipgrep()` is public so tests that need real `rg --json` gate on `assumeTrue(...)`, as aimon-core's
  `RipgrepContentSearchTest` does. The production image contract is unchanged — the stub exists only on the local
  provider's PATH.
- Capabilities: `EXEC`, `FILES`, `EXPIRY` (expiry recorded and enforced lazily against the injected clock: an expired
  sandbox is destroyed on the next `status`/`connect`/`list`), `maxExpiry` configurable. Not advertised:
  `PAUSE_RESUME`, `SHARED_VOLUME`, `EGRESS_POLICY`, `CREDENTIAL_INJECTION`, `NETWORK_ISOLATION`, `RUNTIME_CLASS`,
  `HARDENED_SECURITY_CONTEXT`, `SNAPSHOT`, `FORK`.
- Idempotent `create` by `sandbox-key` label; `stat` returns an etag (SHA-256) so the change-detection contract holds
  on any filesystem.

`FaultInjectingSandboxProvider` wraps any provider; rules per `Operation` (`CREATE`, `STATUS`, `EXTEND_EXPIRY`,
`DESTROY`, `LIST`, `CONNECT`, `RUN`, `FILES_READ`, `FILES_WRITE`, `FILES_MOVE`, `FILES_DELETE`, …) and call index: `delay(d)`, `fail(TRANSIENT|PERMANENT)`, `timeout()`,
`notFound()`, `succeedButLoseResponse()` (performs the call, then throws transient).

### 6.9 Seed, step-3 subset (WS §11.3, §7)

One exec that takes the seed lock **on an fd** (`exec 8>/workspace/.aimon-seed.lock; flock -w {provisionTimeout} 8` — the same form as the shell lock, the only forms the local provider's `flock` shim supports): `command -v git rg flock sha256sum` (image contract; on the local
provider `flock`/`rg` may be the testkit shims of §6.8), `uname -s` lower-cased = `profile.platform`, and — when the
profile declares `osVersion` — `uname -sr` matched against it as a glob (`Linux 6.*` for the doc's `Linux 6.x`
example, `x` read as `*`); an undeclared `osVersion` is not checked, `id -u ≠ 0` and no service-account token (both skipped when
`HARDENED_SECURITY_CONTEXT ∈ insecureAllow`), `rm -rf /workspace/.tmp-*`, `mkdir -p /workspace/.aimon-staged
/workspace/.aimon-shell {root}`. A failed check prints `AIMON_SEED_FAIL {step}` and exits 65 ⇒ FAILED(permanent,
step). The control-plane network probe needs a provider endpoint and joins in step 4 (Q4); `seed.git` is rejected at
startup in step 3 (Q2).

### 6.10 Startup validation (WS §13.2, step-3 subset)

`SandboxStartupValidator` (run by `WorkspaceSandbox.build()`), all failures collected into one
`SandboxConfigurationException`: `deployment` present and label-safe (`[a-z0-9]([-a-z0-9_.]{0,61}[a-z0-9])?`); every
profile has `terminateAfter`; `terminateAfter ≤ capabilities.maxExpiry` when present; `pauseAfter` present ⇒
rejected ("requires PAUSE_RESUME, implementation step 7"); `sharedAccess ≠ none` ⇒ rejected (step 5); `seed` present ⇒
rejected (Q2); non-empty `credentials` ⇒ rejected ("credential bindings arrive with implementation step 4", Q3 — the
§13.2 overlap check cannot be done yet, and an unchecked binding must not start); required capabilities advertised
(§6.4); `requirePrincipal` ⇒ tenant resolver and session owner lookup
supplied; `defaultProfile` defined; admission: the assembly defaults to `DefaultSandboxAdmission` (never allow-all, WS §12.2); allow-all is accepted only as an explicit `maxRunningPerTenant(UNLIMITED)` setting — there is no "missing value" path to it — and with `requirePrincipal` it logs a WARN. A warning is
logged per profile with non-empty `insecureAllow`.

---

## 7. Departures and additions (to fold into WS in this branch)

### 7.1 Against `workspace-sandbox.md`

| # | Departure | Reason |
|---|---|---|
| D1 | Root-package `WorkspaceSandbox` assembly + `SandboxSettings`; not in the §4.1 diagram | §3.2 lists a dozen application singletons with ordering and close obligations; without Spring something must build, validate (§13.2 "기동 시 검사") and close them. |
| D2 | `SandboxWorkspaceStore.delete(id, expectedVersion)` | §10.5 (janitor deletes expired tombstones — with D5 also expired idle records; WS as written also deletes on idle close) and §16 ("툼스톤 보존과 삭제") require a delete the §5.3 interface does not have. CAS-guarded so a concurrent reopen is not lost. |
| D3 | `SandboxBinding.caller` (tenant + principal of the executing principal) | §8.3 checks "the caller's principal" at every entry point, but the §3.1 binding tuple carries only the owner; the check needs both. |
| D4 | `ProviderCapabilities.maxExpiry()` | §13.2 validates `terminate-after ≤ max-expiry`, which is provider configuration (`opensandbox.max-expiry`); the manager can only see it through the SPI. |
| D5 | Idle close keeps a non-blocking `CLOSED(closeCause=idle)` record for `closedRetention` (as §10.5 already does when a volume is retained) instead of deleting it. WS edits: §10.5 step 5 and the "idle close 는 툼스톤을 남기지 않는다" paragraph, and the §21 bullet "idle close 에는 툼스톤을 남기지 않는다" (becomes: the idle record never *blocks*) | Once the record is deleted, the next `connect` cannot tell "reset" from "brand-new session", so the "/workspace was reset" notice required by §10.5/§15/§16 row 22 is unimplementable. The record does not block (`connect` auto-reopens), so "no tombstone" semantics are unchanged. Decision Q1. |
| D6 | Staging verification recomputes the content key in-sandbox over the expected file list, rather than comparing per-file hashes | §6.7 / §3.2: no source re-read. **Not an equivalent guarantee**: core's key framing (`relPath\0bytes\0`) is ambiguous when a file's bytes contain `\0{nextRelPath}\0`, so content moved across a file boundary can reproduce the stream; per-file hashes do not have this weakness. The exact-file-set check narrows it, the risk is theoretical, and it is core's own algorithm (core trusts the same key) — WS §11.1 is reworded to say "as strong as core's content key", not "same as per-file hashes". |
| D7 | Shell output goes through per-run files and a nonce trailer; the wrapper is bash-3.2/POSIX and split into an outer process (lock, output, trailer) and an inner bash (state restore, command, state save) | §6.5 / §3.2: pipe inheritance by descendants; truncation inside the sandbox; macOS host; a command's own `exit`/`set -e` must not swallow the output (review-2). |
| D8 | The local provider translates paths; `LocalProcessSandboxProvider` javadoc and WS §16 say so | §6.8. |
| D9 | Step-3 seed subset; no network probe until step 4; `seed.git` rejected at startup in step 3 | §6.9, Q2, Q4. §18 assigns bare-repo seed to step 5 and says nothing about direct clone. `osVersion` is checked against `uname -sr` only when declared. |
| D10 | Owner check before the CLOSED/CLOSING branches in `connect` (reorders WS §10.1 steps 2–3) | With D5, idle-closed records are routine; checking after would let any caller trigger the reopen CAS and consume the reset notice, and tell a foreign tenant a workspace is closed. |
| D11 | Shell command embedded as single-quoted data and run with `eval`; the wrapper's own state lives in `readonly` `__aimon_*` variables (prefix reserved) | §6.5: pasting it into the script let a trailing comment or heredoc break the wrapper's syntax; `eval` shares the inner shell's variables, so the traps must not read `$1`/`base` (review-3). |
| D12 | After a CLOSED record (explicit or idle) is deleted at `closedRetention`, the next `connect` creates a new workspace **without** the "/workspace was reset" notice. WS edits: §10.5 ("`closedRetention` 이 지나면 … notice 를 붙인다") and the §15 row "idle close 나 툼스톤 만료 뒤의 같은 id" (split: idle close → notice; tombstone expiry → new workspace, no notice) | Nothing remains to distinguish the id from a new session; keeping records forever is what §10.5 already rejects. Decision Q1. |
| D13 | Implementation step 3 rejects at startup any profile with non-empty `credentials` (WS §13.2 check list gains the step note) | The §13.2 overlap check needs vault binding definitions only the step-4 provider knows; starting with an unchecked binding would skip a security check WS makes mandatory. Decision Q3. |
| D14 | `stage()` holds a node-local lock per `(providerRef, target)` and copies into a temp directory then moves it into place; the copied bytes are re-hashed against `contentKey`. WS §11.1 staging paragraph gains these three sentences | §6.7: concurrent first activations of one skill in one slot; parity with core's `LocalStaging` (lock + marker re-check, changed-on-disk rejection) (review-3). |

§18-4 lists "라벨 인코딩과 대조" under step 4; step 3 already computes labels and verifies them on the manager side
because `CreateSpec` needs them — not a departure in behaviour, noted in the doc for accuracy.

### 7.2 Against aimon-core's SPI — none needed

| Candidate | Resolution |
|---|---|
| Path rules anchored at the working directory | Three-layer VFS (§6.6). |
| Skill hook rejection | `SkillHookSetParser(NoOpShellActionExecutor.INSTANCE)` already rejects `action.type: shell` at parse time. |
| Fork definition / attributes / parent cause | `EnvironmentRequest.fork()`, `definitionAttributes()`, `UnavailableExecutionEnvironment.message()` exist. |
| Background detection, notices | `ExecutionOptions.isBackground()`, `ShellCommandResult`/`ShellExecutionException.notices()` exist. |
| Compile deps | Declared in this repo (§4.1), not by changing core's POM. |

If implementation nevertheless hits a core gap, TASK's procedure applies (branch `herdr/workspace-sandbox-step3` in
aimon-core, `publishToMavenLocal -x test -x javadoc`, listed in HANDOFF.md).

---

## 8. Failure modes

| Failure | Handling |
|---|---|
| `resolve()` cannot bind (no session/exec id, principal gate, owner lookup empty, unknown profile, non-primary slot, fork parent missing/unavailable) | Throw `ExecutionEnvironmentUnavailableException`; core publishes `UnavailableExecutionEnvironment`; no workspace created. |
| Owner check fails | `SandboxUnavailableException("not permitted")`, no detail about the workspace (WS §15). |
| Admission rejects | Unavailable naming the limit (`max-running-per-tenant = N`). |
| CAS conflict | Re-read and re-decide; after `casRetries` → unavailable "workspace busy, retry"; conflicts after a provider call never destroy. |
| `create` fails | FAILED(transient/permanent per exception kind) with backoff; permanent waits for a profile change. |
| `create` succeeded but response lost / RUNNING CAS lost | Re-run state machine; the extra sandbox leaks until provider expiry in step 3 (reconciliation is step 4). Logged at WARN with the key. |
| Label mismatch on returned sandbox | FAILED(permanent, `labels`); sandbox not used. |
| Another node/thread provisioning | Poll until `provisionTimeout`, then error; next call takes over. |
| Seed check fails | FAILED(permanent, step) with the failed step in the error. |
| `extendExpiry` not found mid-command | Mark LOST, kill command, error; next call provisions generation+1 with a notice. |
| Provider unreachable | Error from the tool; never falls back to host (WS §12.1). |
| Command timeout / interrupt | Watchdog or `kill()` of the process group; `ShellTimeoutException` with partial output + notice; state from before the command remains (SIGKILL skips the inner trap; SIGTERM sets `__aimon_nosave` via the inner TERM trap). |
| Command ends its shell (`exit N`, `set -e`) | Ends only the inner bash; the outer still emits output + trailer with that exit code. |
| Shell lock busy (same node) | Wait ≤ `shellLockWait`, then error "shell busy". |
| Shell lock busy (other node) | WS §9 owner/heartbeat logic; hosts without `/proc` report busy (no takeover). |
| Close interrupted (node death) | Record stays CLOSING; janitor resumes after `closeResumeAfter`. |
| Janitor action fails | Logged per workspace; the cycle continues; the next cycle retries from re-read state. |
| Startup misconfiguration | `SandboxConfigurationException` listing every violation; nothing starts. |
| Model command has a syntax error / trailing comment / heredoc | Parsed by `eval` alone (D11); reported as the command's own exit and stderr. |
| Host lacks `rg` (local provider) | Stub on the provider PATH; provisioning works, content search fails loudly; real-`rg` tests are assumption-gated. |
| Staging copy fails midway | The copy was in a `.tmp-{key}-{nonce}` directory; the target never appears half-written. The temp dir is deleted on the failure path, or by the next `stage()` of that target if the JVM died. Planted marker/content fails key or set check → deleted and recopied under the lock. |
| Two `stage()` calls for the same resource in one slot | Serialised by the `(providerRef, target)` lock; the second re-checks inside the lock, finds the first's verified copy and returns it (§6.7). |
| Skill changed on disk after it was loaded | Copied bytes do not hash to `contentKey` → temp dir deleted, `StagingException` with core's wording; nothing is staged under the old key. |
| Model command assigns `base`, `set --`, or a `__aimon_*` name | No effect on the state save (readonly `__aimon_*` copies); assigning a `__aimon_*` name is bash's "readonly variable" error in the command's stderr. |
| `InMemory` store restart | All records lost; sandboxes leak until provider expiry (orphan reconciliation is step 4). Documented, as in WS §5.3. |

---

## 9. Test strategy

Layers: unit tests per class (records' `with*`, `SandboxLabels`, `ShellWrapper` script shape, validator, policy,
backoff math); contract suites (`InMemorySandboxWorkspaceStoreContractTest`,
`LocalProcessSandboxProviderContractTest`, and the fault wrapper's own tests); integration tests driving
`WorkspaceSandbox` with `LocalProcessSandboxProvider` (+ `FaultInjectingSandboxProvider`), `ManualClock` and
`ManualScheduler`; and end-to-end tests with a real `OrcaAgentRuntime` (all needed core classes are public; a
scripted `LlmClient` in test sources, as core's `SlashSkillForkE2EIntegrationTest` does) for the prompt,
slash-command and core-tool rows. Real processes keep sleeps ≤ 2 s; timing rows are scaled down with configurable
settings. No `@Tag` — everything runs in `test`.

**Contract suites** cover WS §16's lists: provider — same-key sequential create → one; destroy idempotent;
stdout/stderr separated + exit code; cwd/env; per-stream truncation; `kill()` only its own group; files
read/write/stat/list/move; stat change detection (same size, < 1 s); `list(labels)` returns all (N = 30);
`status` returns labels; `extendExpiry`/`resume` on absent → `SandboxNotFoundException` (resume only if
advertised); volume tests gated on `SHARED_VOLUME`; unadvertised capabilities throw. Store — concurrent
`createIfAbsent` → one; stale version → `StaleVersionException`; scan filters and paging; tombstone kept until
deleted, `delete` CAS.

**§16 rows with stage 3** (29 rows; class names are the planned test classes in `aimon-sandbox` tests):

| # | §16 row | Test |
|---|---|---|
| 1 | A `Write Foo.java` → B `Read` same slot | `CoreToolsSandboxIT#writeByMainTurnIsReadByFork` — core `WriteTool`/`ReadTool` through two resolved environments (main turn + fork) |
| 2 | A `export FOO=A` → B `echo $FOO` empty | `SandboxShellIT#shellStateIsIsolatedPerShellKey` |
| 3 | `cd src && export X=1` → next `Bash` | `SandboxShellIT#cwdAndExportedVariablesPersistButPlainVariablesDoNot` |
| 4 | `Write` → `Bash cat` | `CoreToolsSandboxIT#fileToolAndShellSeeOneFileSystem` |
| 5 | Timeout kill → next command | `SandboxShellIT#killedCommandLeavesPreviousStateAndNotice` |
| 6 | Session-less routine → fork → grandchild fork | `SandboxEnvironmentProviderTest#routineForkChainStaysInOneWorkspaceAndSlot` |
| 7 | Fork → grandchild, parent unavailable | `SandboxEnvironmentProviderTest#forksOfUnavailableParentCarryParentCause` |
| 8 | Parent-less workflow step (`fork()` present, no parent) | `SandboxEnvironmentProviderTest#parentlessForkIsUnavailableAndCreatesNoWorkspace` (asserts store empty) |
| 9 | Command longer than `terminateAfter`, no tool calls, another execution keeps updating the record | `ActivityHeartbeatIT#longCommandIsNotTerminatedAndExpiryIsPushed` (manual clock advanced past `terminateAfter` while `sleep` runs; a second thread writes the same workspace record (a fork's activity writes) between heartbeats so heartbeat CASes hit `StaleVersionException`; asserts every heartbeat write landed (`lastActivityAt` never regresses), `janitor.runOnce()` leaves the slot RUNNING, provider expiry moved) |
| 10 | `sleep 600 &` leftover → next command | `SandboxShellIT#leftoverDescendantDoesNotHoldTheLock` (next command completes < 2 s; leftover killed in teardown) |
| 11 | Timeout vs. lock held by previous command | `SandboxShellIT#lockWaitIsNotCountedInCommandTimeout` (scaled: timeout 2 s, lock held 1.5 s, command 1 s) |
| 12 | Background command running → next `Bash` | `SandboxShellIT#backgroundCommandTakesNoLock` |
| 13 | Background beyond `backgroundHeartbeatLimit` | `ActivityHeartbeatIT#backgroundHeartbeatStopsAtLimitThenIdlePolicyApplies` (+ lock heartbeat file untouched) |
| 14 | Session with no command in first turn | `OrcaRuntimeSandboxE2ETest#textOnlyTurnProvisionsNothingAndPromptShowsDeclaredValues` |
| 15 | Slash-command skill's `Bash` runs in the sandbox | `OrcaRuntimeSandboxE2ETest#slashCommandSkillBashRunsInSandbox` (marker file appears in the local sandbox dir, not the host cwd) |
| 16 | Skill with shell-action hook | `SkillHookRejectionTest#shellHookSkillIsRejectedAndNothingRunsOnHost` |
| 17 | `Write` into `.aimon-staged`/`.aimon-shell` | `SandboxFileSystemTest#stagingAndShellAreasAreReadOnlyToFileToolsButShellCanWrite` |
| 18 | Planted script + marker in staged dir | `SandboxStagingIT#plantedContentFailsKeyCheckAndIsRecopied` |
| 1, 18 (concurrency) | Same slot, two executions activate one skill at once (review-3) | `SandboxStagingIT#concurrentStageOfSameResourceReturnsCompleteCopyToBoth` — two threads released by one latch call `stage()` on two environments bound to the same slot (main turn + fork); `FaultInjectingSandboxProvider` delays every `FILES_WRITE` by 200 ms to widen the window. Both return the same path; after both return, and again after a third `stage()`, the listing equals `getFiles() ∪ {.staged}` with each file's bytes equal to the source; the fault wrapper's counters show exactly one copy (`FILES_WRITE` = files + 1) and no `.tmp-*` directory remains |
| 19 | `connect` on CLOSED; `reopen` | `WorkspaceLifecycleIT#closedWorkspaceIsUnavailableUntilReopenedWithNewGeneration` |
| 20 | `connect` during close | `WorkspaceLifecycleIT#connectDuringCloseLeavesNoSlotAfterClosed` (fault-injected `destroy` delay + concurrent connect) |
| 21 | Node dies during close | `WorkspaceLifecycleIT#janitorResumesStuckClosing` (close aborted after CLOSING via fault; clock + `closeResumeAfter`; `runOnce`) |
| 22 | Idle close → next turn | `WorkspaceLifecycleIT#idleCloseThenNextTurnGetsFreshWorkspaceAndResetNotice` |
| 23 | `default-profile` changed → existing workspace | `WorkspaceLifecycleIT#redeployWithNewDefaultProfileKeepsExistingPrimaryProfile` (second assembly, same store/provider) |
| 24 | Other-tenant principal: `connect` · `close` · `reopen` | `OwnerCheckTest#otherTenantIsRejectedAtEveryEntryPoint` |
| 25 | Tenant exceeds `max-running-per-tenant` | `DefaultSandboxAdmissionIT#tenantOverRunningLimitIsRejected` |
| 26 | Same tenant, other user, `workspace-access: principal` | `OwnerCheckTest#sameTenantOtherUserIsRejectedUnderPrincipalAccess` |
| 27 | `Principal.system()` + `require-principal` | `DefaultSandboxBindingPolicyTest#systemPrincipalIsRejectedWhenPrincipalRequired` |
| 28 | Record lost, non-owner connects first | `OwnerCheckTest#ownerComesFromSessionOwnerNotFirstCaller` |
| 29 | Fault: `extendExpiry` not found mid-command | `ActivityHeartbeatIT#expiryNotFoundMidCommandMarksLostAndNextCallRecreates` |

Also covered (not §16 rows but WS contracts): startup validation (`SandboxStartupValidatorTest`, one case per rule),
descriptor declared values and notes, permanent vs transient failure/backoff, provisioning takeover after
`provisionTimeout` (single JVM, two manager instances — the step-4 multi-node rows are *not* claimed), label
verification, staging parity with core (`SandboxStagingIT`: a skill whose file changes after `scan` →
`StagingException` with core's wording, no target and no `.tmp-*` left; name/key/path shape and size limit →
`StagingException` before any provider call; a crashed copy's `.tmp-*` leftover is removed by the next `stage()`;
an in-workspace resource is returned uncopied), `SandboxUnavailableException`/`BindingRejectedException` reaching the
model through core `ReadTool`/`BashTool` as the WS §15 message (not the generic tool error), startup rejection of a
profile with `credentials` (Q3), after `closedRetention` a deleted record's id gets a new workspace **without** a
reset notice (`WorkspaceLifecycleIT#anExpiredClosedRecordIsDeletedAndTheIdStartsFreshWithoutANotice`, Q1/D12), content search
via `rg --json` (gated on `assumeTrue(LocalProcessSandboxProvider.hostHasRipgrep())`, plus an ungated test that the stub makes search fail loudly), janitor items (a)–(d), exec shell-dir sweep.

**Wrapper robustness** (`ShellWrapperTest` for the script text, `SandboxShellIT` through the local provider): a
trailing `# comment`, a heredoc writing a file, an unbalanced quote (command's own non-zero exit, stderr carries
bash's message, trailer present, state from before preserved), a command containing `'` and `$'…'`, a command that
`exit 75`s and one that `exit 137`s (reported as the command's exit code, not as lock-busy/timeout), `echo x; exit 0`,
`echo x; exit 3` and `set -e; false; echo after` (output present, trailer present, exit code the command's own, and
`cd`/`export` done before the `exit` persisted), `kill()` during `cd /x; export Y=1; sleep 5` (state file unchanged,
no trailer, classified as the JVM's kill), `redirectErrorStream` interleaving, and a NUL in the command (rejected
before `run`). Review-3 additions: `set -- /tmp/zz; export Z=3` and `set -- src; export Z=3` (Z saved to the shell's
own state file; nothing written under `/tmp/zz` or `src`), `base=x; export Z=3` (the next run's state has `Z` and
none of the base environment), `__aimon_dir=x` (bash's readonly error, state still saved in the right place); after
ten quick foreground commands no `sleep` from a watchdog is left in the sandbox's process group (local provider:
`pgrep -g`); a command writing exactly `max` bytes to stderr (and one writing `max + 10`) still yields the trailer, the
command's own exit code and `stderrTruncated` set only in the second case; `SandboxShell` construction rejects
`maxCaptureBytes ≤ max + trailer`.

---

## 10. Decisions on the open questions

Each question was closed with the conservative option consistent with WS (launcher decision, TASK.md). They stay
listed here, each with the step that revisits it, so they can be folded into WS §20 in the same branch (§4.1).

- **Q1 — reset notices after a record is gone.** *Decided: keep a non-blocking `CLOSED(closeCause=idle)` record for
  `closedRetention` (D5); after any CLOSED record is deleted at `closedRetention`, the next `connect` starts a new
  workspace without a notice (D12).* WS §10.5 deletes the record on idle close yet promises a reset notice on the next
  `connect`; both cannot hold. D5 reuses a record shape WS already has (the retained-volume idle record, which never
  blocks), so no user-visible promise is dropped for the common case and §16 row 22 is met in full. For the
  tombstone-expiry case no option keeps the notice short of never deleting records, which WS §10.5 rejects; dropping
  the notice there is the only consistent choice. Revisit: none; the WS text changes with D5/D12.
- **Q2 — which step owns direct `seed.git` clone (`sharedAccess: none`).** *Decided: implementation step 3 rejects
  any `seed` at startup (D9); direct clone arrives with bare-repo seed in step 5.* §18 assigns seeding from git only
  to step 5; rejecting keeps an unimplemented path from silently doing nothing. Adding it is a small `SandboxSeeder`
  change with transient/backoff handling already in place. Revisit: step 5.
- **Q3 — credential-overlap check (§13.2).** *Decided: implementation step 3 rejects any profile with non-empty
  `credentials` at startup (D13, §6.10); the overlap check arrives in step 4 as a provider-side validation hook.* The
  check needs vault binding definitions (host, path prefix, method) only the OpenSandbox provider config knows. Without
  it, a profile could start with a binding WS §12.1/§13.2 would refuse; rejecting is the conservative reading. (No
  step-3 provider advertises `CREDENTIAL_INJECTION` anyway, and `insecureAllow` cannot waive it.) Revisit: step 4.
- **Q4 — network-isolation probe in seed.** *Decided: no probe in step 3; it joins the seed in step 4 with the SPI
  shape for the control-plane endpoint (D9).* `NETWORK_ISOLATION` stays a required capability (WS §6.4), so every
  step-3 profile either runs on a provider that advertises it or waives it through `insecureAllow` with the startup
  WARN — the gap is never silent. Revisit: step 4.
- **Q5 — publish `aimon-sandbox-testkit`.** *Decided: yes — `aimon.publishable`, `POM_ARTIFACT_ID=aimon-sandbox-testkit`
  (§4.1).* WS §4.1 draws the testkit as its own module and WS §16 requires *every* provider, including third-party ones
  outside this repo, to pass `SandboxProviderContract`; aimon-core publishes `aimon-memory-testkit` for the same
  reason. Nothing is published before the step 3+4 release (Q8), so the choice is reversible until then. Revisit:
  the step 3+4 release.
- **Q6 — §14 observability.** *Decided: spans and metrics are not implemented in step 3; the `SandboxEventListener`
  lifecycle events (the audit half of §14, and a §4.1 type) are.* §18-3 does not list §14, and metrics would add a
  Micrometer dependency and a label design with no consumer. WS §20 gains an item "which step owns §14 spans and
  metrics", to be closed before the step 3+4 release. Revisit: before the step 3+4 release.
- **Q7 — coverage floors.** *Decided: per module, the measured line coverage rounded down to a whole percent,
  committed in the same commit as the tests it measures (§4.1).* Matches how `gradle/coverage-baselines.properties`
  is described (whole percentages, one per module) and never sets a floor the build does not already meet. Revisit:
  raised by later steps as coverage grows.
- **Q8 — the SNAPSHOT pin.** *Decided: keep `aimon-core = "0.3.1-SNAPSHOT"` with the content- and snapshot-filtered
  `mavenLocal()` for this branch; record in CHANGELOG `[Unreleased]` and in the `libs.versions.toml` comment that
  steps 3+4 cannot be released until aimon-core 0.3.1 is on Central, the pin is raised to it and `mavenLocal()` is
  removed (§4.1).* TASK prescribes the SNAPSHOT; WS §18-4 already holds step 3 back until step 4, so the pin blocks
  nothing that could ship. Revisit: the step 3+4 release.

---

## 11. Suggested commit sequence

1. build: core 0.3.1-SNAPSHOT via content-filtered `mavenLocal()`, catalog entries, testkit module skeleton.
2. provider SPI + labels; testkit `LocalProcessSandboxProvider`, fault wrapper, provider contract + its test.
3. records + store + `InMemory` + store contract.
4. profiles, settings, startup validator, binding policy, tenant/owner.
5. manager (connect/close/reopen, CAS, admission, events) + janitor.
6. environment: filesystem layers, shell wrapper, staging, seed, content search, heartbeat, provider, assembly,
   skill-hook parser.
7. integration/E2E tests for §16 rows; coverage floors.
8. docs: WS departures D1–D14 and the Q1–Q8 decisions in WS §20, READMEs, CHANGELOG.

---

## 12. Implementation departures

Where the implementation departed from the design above, and why. None changes an aimon-core SPI; none needed an
aimon-core change. Section numbers without a prefix refer to this document; `WS` is
[`workspace-sandbox.md`](workspace-sandbox.md), which states the resulting contracts.

### Structure

1. **`SandboxSeeder` lives in `workspace/`, not `environment/`** (§4.2 table). The manager runs the seed inside
   `connect` (§6.2 step 5); a package-private class in `environment/` is unreachable from `workspace/`, and making it
   public would publish an internal. It stays package-private, next to its only caller.
2. **New public `binding.CallerResolver`.** The principal gate (WS §8.3: `require-principal` ⇒ USER/GROUP or a listed
   system principal; otherwise an absent principal is `anonymous`) plus tenant resolution. The design put the gate in
   `DefaultSandboxBindingPolicy.bind` only, but `close(id, Principal)` and `reopen(id, Principal)` take a principal too
   and WS §8.3 says every entry point checks the caller. One class serves both, so the two cannot drift.
3. **`RetainedVolume` value type added** (§5.1 lists `List<RetainedVolume> retainedVolumes()`; the §4.2 type list
   omitted it). Always empty in step 3.
4. **`ConnectedSlot.profile()` added** (§5.6). The shell and content search need the profile's `env` for
   `ExecSpec.environment`; re-resolving it from the registry in the environment layer would duplicate the manager's
   "slot keeps its profile" rule.

### Provider SPI details (§5.4 says "signatures are illustrative")

5. **`SandboxFiles.list` returns `List<FileStat>`** rather than names — a VFS listing needs the directory flag, and
   names alone would cost one `stat` per entry (an HTTP call on OpenSandbox).
6. **`SandboxFiles.createDirectories(path)` added** — `VirtualFileSystem.createDirectory` needs it and WS §6.1's list
   has no way to express an empty directory. OpenSandbox's execd files API has directory creation.
7. **`WriteMode.CREATE_NEW`** exists beside `CREATE_OR_REPLACE`; `ProviderCapabilities.of(...)` is the factory, and the
   type has value equality.
8. **`RunningCommand.await(timeout)` kills on timeout** and reports `ExecOutcome.timedOut()`, so a caller has one kill
   path; `ExecSpec.timeout()` is the provider-side backstop with the same result.
9. **`ExecSpec.environment` is not only the profile's `env`**: staging verification passes `PATH=/usr/bin:/bin`, as
   §6.7 itself requires ("PATH fixed"). The `ExecSpec` javadoc says so.

### Shell (§6.5)

10. **Cross-node lock takeover is reported, not performed, in step 3.** On the wrapper's exit 75 (in-sandbox lock
    still busy after `shellLockWait`), `SandboxShell` reads `.aimon-shell/{h}/owner` and fails with "the shell is in
    use by a command on another node (…)". The wrapper still records `owner` as `{nodeId} {pid} {/proc start time|-}`
    and the JVM writes the lock heartbeat, so step 6 can add the kill-and-take-over half. The design said "the branch
    exists in step 3; its multi-node rows are step 6"; the takeover half could not be exercised on one node and would
    have been untested code that kills process groups.
11. **Heartbeat file.** `SlotActivity.startHeartbeat(background, onTick, onLost)` takes an `onTick` callback (the
    design showed two arguments and left the lock heartbeat's writer open). A foreground command's `onTick` rewrites
    `.aimon-shell/{h}/heartbeat` through the files API (content: node id and JVM millis); the wrapper never touches it;
    a background command passes no `onTick` (WS §9: it must not make a dead node's lock look alive).
12. **No construction-time `maxCaptureBytes` check.** The design (§5.4, §9 "wrapper robustness") asked
    `SandboxShell` construction to reject `ExecSpec.maxCaptureBytes ≤ max + trailer`. `SandboxShell` derives
    `maxCaptureBytes = max + TRAILER_ALLOWANCE` per call from the caller's `max`, so the relation holds by
    construction and there is nothing to reject. The behaviour it protects is tested
    (`SandboxShellIT#stderrAtTheCapKeepsTheTrailerAndOnlyOverflowTruncates`).
13. **Inner-script arguments**: `$5` is the root (fallback when the saved cwd is gone), `$6` the per-command working
    directory, then `NAME=value` pairs. The design numbered only `$1`–`$3`. The inner script is a Java text block.
14. **Background backstop**: `ExecSpec.timeout = commandTimeout + 5 s` for background commands (the design only gave
    the foreground formula `commandTimeout + shellLockWait + 5 s`; background takes no lock).

### Manager and janitor (§6.2–§6.4)

15. **A close destroys every recorded sandbox on every pass, and acts only while the record is still its own** not only slots this pass moved to TERMINATED. Found by
    the §16 row-21 test: a close that crashed between a slot's TERMINATED CAS and its `destroy` was resumed by the
    janitor, found the slot already TERMINATED, skipped `destroy`, and left the sandbox running (and waited out
    `closeWait`). `destroy` is idempotent, so re-destroying is safe while the workspace is CLOSING. Every step after
    the CLOSING CAS (slot terminations, destroys, the CLOSED CAS) re-checks that the record is still CLOSING with the
    same incarnation, so a slow closer never touches a workspace that was closed by someone else and reopened
    meanwhile (found in code review; `WorkspaceLifecycleIT#aStaleCloserLeavesAWorkspaceReopenedMeanwhileAlone`).
    While it waits for the sandboxes to disappear, the close also destroys whatever the provider still lists for the
    workspace — e.g. a sandbox whose creator lost its RUNNING CAS to the close and which no record holds.
16. **The "another node is provisioning" wait is bounded by real time as well as the injected clock** — with a frozen
    test clock the design's clock-only bound polls forever. Takeover decisions still use the injected clock.
17. **Activity CAS retries**: activity writes retry up to 1000 times, with a 1–5 ms random pause after 10 conflicts.
    The design says "retry on conflict, never dropped" without a bound; the general `casRetries = 5` lost a
    heartbeat write under the row-9 test's concurrent writer.
18. **Recreation notice** wording: when a TERMINATED slot is provisioned again (idle terminate, lost), the result
    carries "the sandbox was recreated (generation N): files under /workspace and shell state (cwd, exported
    variables) from before are gone" — the design's "generation changed / sandbox recreated after loss" notices,
    merged into one. Not added after an idle reopen, whose reset notice already says it.
19. **Seed order**: the image-contract check (`command -v git rg flock sha256sum`) runs before the seed lock is taken,
    so an image without `flock` fails as `image-contract`, not as a lock error. The seed exec's working directory is
    `/workspace` (the root may not exist yet).
20. **Descriptor for a removed profile**: `resolve()` describes the default profile when the slot's recorded profile
    is no longer configured; `connect` then refuses with "profile X … is no longer configured". A *required* profile
    that is not configured is rejected at `resolve()`.

### Found in code review

- **A call recreates a lost sandbox at most three times**; then it fails with "unavailable". The design's connect loop
  had no bound on the lost → recreate path (only waits were time-bounded).
- **Provider failures never reach core tools raw.** File, staging and search calls go through one guard: a sandbox
  found gone marks the slot lost and fails with the §15 "lost" message; any other provider failure is
  `SandboxUnavailableException`.
- **A CLOSING workspace answers "being closed; retry shortly"**, not the explicit-close message (CLOSING is
  transient, and `reopen` refuses it).
- **The seed's `mkdir` uses the binding's root**, not the default root, and the declared platform and OS version
  are single-quoted into variables (they were interpolated into double-quoted text).
- **Content-search results are sorted by path** (rg's parallel output order varies between runs).

### Fixed after build review 1

- **A fork in its parent's slot keeps the parent's root** (WS §8.2); only a fork into another slot gets the default
  root. The code had always used the default root, which contradicted WS and the design. It was not a deliberate
  departure (`SandboxEnvironmentProviderTest#aForkInTheParentsSlotKeepsTheParentsRoot`).
- **An explicit close overtaking an idle close makes a tombstone.** A CLOSING(idle) record becomes CLOSING(explicit).
  A CLOSED(idle) record becomes CLOSED(explicit), with `stateSince` reset so the retention counts from the explicit
  close. The next `connect` then answers "closed" instead of silently auto-reopening (WS §10.5, added)
  (`WorkspaceLifecycleIT#anExplicitClose…`).
- **The state save is independent of the command's `IFS` and `set -x`.** `__aimon_save` sets `local IFS` and
  `set +eux`, and the EXIT trap first turns tracing off quietly
  (`SandboxShellIT#aCommandChangingIfsKeepsEveryPersistedExport`, `#tracingInTheCommandDoesNotLeakTheSaveIntoStderr`).
- Smaller fixes:
  - `create` compares the claim against the *stored* slot, so a store that rounds `Instant`s cannot fake a lost CAS.
  - The per-call recreate budget counts only claims that were won.
  - Provider failures in the shell become `SandboxUnavailableException`.
  - A non-positive `terminate-after` is refused at startup.

### Fixed after build review 2

- **An explicit close of an idle-closed record is decided in one CAS.** The review-1 fix used two read-and-CAS
  steps, so an idle auto-reopen landing between them made `close` return with the workspace OPEN and no tombstone.
  Now the CLOSED(idle) → CLOSED(explicit) conversion is one branch of the close's single state-switch CAS. If a
  concurrent reopen wins it, the retry reads OPEN and closes that workspace. Either order now has a definite outcome
  (`WorkspaceLifecycleIT#anIdleReopenRacingAnExplicitCloseCannotSwallowIt`, which runs a `connect` between the close's
  read and its CAS).
- **The provisioning-takeover CAS also requires the workspace to be OPEN**, so a takeover cannot start a `create` on a
  record a close has just made CLOSING (WS §10.5).
- **Seed-lock contention is transient.** It exits 75 rather than the permanent-failure code 65, so it is retried on the
  next call instead of blocking the slot until the profile changes.
- **The state save also turns off `set -v` and `set -T`, and clears DEBUG and RETURN traps**, so a command's own
  tracing cannot leak the save's internals.

### Departures found at build review 1 (§6.2–§6.4, §6.9, WS §12.2)

These three depart from the design as written; review 1 found them in the code, not in this list.

27. **Admission counts only OPEN workspaces.** `DefaultSandboxAdmission` counts RUNNING and PROVISIONING slots of OPEN
    workspaces only; WS §12.2 counts every RUNNING and PROVISIONING slot of the tenant. A CLOSING workspace's slots are being
    destroyed and a CLOSED workspace has none, so neither holds provider resources that should count.
28. **The janitor closes through a guarded `closeProcedure`, not `closeInternal`.** The guard is re-checked on the
    freshly read record, whatever its state, before the close's state-switch CAS; a refusal leaves the record alone.
    For an idle close it is the idle condition; for a resumed stuck close it is "still CLOSING with the incarnation
    the janitor scanned" (fixed after build review 3, below). `closeInternal` stays as the unguarded internal close
    path of WS §8.3 (superseded by 34: it was removed).
29. **A failed seed check destroys the sandbox** after the FAILED CAS it won; the design only recorded FAILED. That
    sandbox has verified labels and is this generation's own, and no record points anyone at it any more. A label
    mismatch is not destroyed (the sandbox may not be ours).

### Fixed after build review 3

- **The janitor's resume of a stuck close cannot close a workspace reopened after its scan.** `resumeClose` passed an
  always-true guard, and `closeProcedure` applied the guard only to an OPEN record, so a workspace that was closed by
  someone else and reopened between the janitor's CLOSING scan and its resume was CASed back to CLOSING and its live
  sandbox destroyed. Now `closeProcedure` tests the guard on the freshly read record in every state, and
  `resumeClose` passes "CLOSING with the scanned incarnation"
  (`WorkspaceLifecycleIT#theJanitorLeavesAStuckCloseThatWasFinishedAndReopenedAfterItsScanAlone`, which finishes
  the close, reopens and writes a file from inside the store's `scan`, then asserts OPEN, new incarnation, RUNNING
  slot, one sandbox and the file intact).
- **A root binding's `caller` is always the request's principal.** `resolve()` kept whatever `caller` the policy
  returned, so a custom policy that set `caller(owner)` let every principal pass the owner check (WS §8.3's second
  tenant line) and bypassed `require-principal`. The root path now overrides `caller` with
  `callers.callerOf(request.principal())` after `policy.bind`, as `bindFork` already did; the
  `SandboxBindingPolicy.bind` javadoc says the returned caller is replaced
  (`SandboxEnvironmentProviderTest#aPolicyThatNamesTheOwnerAsCallerCannotLetAnotherPrincipalIn`: a ticket policy
  naming alice as caller; bob's shell and file calls are refused "not permitted").

### Staging (§6.7)

21. **"Resource already in this environment"** is recognised only when its source filesystem is this environment's own
    `SandboxFileSystem` instance — the rule and raw layers are per-call objects and cannot be compared by identity.

### Testkit (§6.8, §9)

22. **`FaultInjectingSandboxProvider.Fault.crash()`** (throws `SimulatedCrash extends Error`) added. The design's
    faults (delay, fail, timeout, not found, lost response) are all `RuntimeException`s the close procedure survives by
    design, so none can leave a workspace in CLOSING as the §16 "node dies during close" row needs.
23. **Local provider path mapping** refuses a path whose deepest existing ancestor resolves outside the sandbox
    through a symbolic link (a command can plant one), and covers every absolute path in the files API (`/p` ↔ `{base}/{id}/p`), not only
    `/workspace`; in commands only `/workspace` and `/usr/bin/sha256sum` are rewritten (documented limit).
    `hostHasRipgrep()` probes with the provider's own `PATH`, which is what its commands get.
24. **"No watchdog left behind" test** uses `pgrep -f 'sleep 17.321'` (a unique timeout) instead of `pgrep -g`: every
    command is its own process group, and parallel test JVMs share the host.
25. **Janitor fallback** for a removed profile when no profile has `terminateAfter` is 365 days — unreachable, since
    startup validation requires `terminateAfter` on every profile.

### Build

26. `aimon-sandbox-testkit` also declares `implementation(libs.slf4j.api)` (it logs).

### Follow-up: the open non-blocking findings (after build review 4)

Each item from HANDOFF's follow-up list and review 4's two non-blocking items, where the fix changes what WS or this
document says. WS is updated in the same change; the section named in brackets is the one amended.

30. **Store versions are never reused, and a store returns what it keeps** [WS §5.3, §16]. `createIfAbsent` starts a
    re-created id above every version the id had before (`InMemorySandboxWorkspaceStore` keeps the highest deleted
    version as a floor), so a caller holding the deleted record cannot CAS its successor (ABA); the design's "stored
    with version 1" holds only for an id the store never held. `SandboxWorkspaceStore` also states that a later `find`
    equals what `update`/`createIfAbsent` returned, with `Instant`s kept to at least milliseconds. The contract suite
    pins both, plus `update` ignoring `next.version()`, refusing another record's id, failing after `delete`, and
    paging over filtered-out records.
31. **The janitor reclaims an abandoned PROVISIONING claim** [WS §10.2, §10.4 item 1]. A claim nobody finished or took
    over for `2 × provisionTimeout` is CASed (same generation and claim, workspace OPEN) to TERMINATED, and a sandbox
    the claimer may have created under its key is destroyed. The design left such a slot in PROVISIONING until the
    next connect, where it blocked the idle close and held an admission slot. Twice the takeover threshold leaves a
    slow but live claimer, and the connect-path takeover, to go first.
32. **A sandbox whose `status` fails, or is not visible, after `create` is destroyed** [WS §10.1] — only once the
    FAILED CAS of this claim is won: the sandbox carries this generation's key and no one took the claim over. A label
    mismatch is still not destroyed. (The design's rule "never destroy on the call path after a lost CAS" is about a
    lost CAS and is unchanged.)
33. **`connect` carries its notices on the error, and never throws raw** [WS §10.1, §15]. When the recreate that follows
    an idle reopen or a lost/terminated slot fails, the reset or "recreated" notice is appended to that error — the
    next call sees FAILED, not TERMINATED, and would have nothing to tell. A `RuntimeException` from the store or an
    application `SandboxAdmission` becomes `SandboxUnavailableException`.
34. **`closeInternal` is removed** (review 4). It had no caller since the janitor closes through the guarded
    `closeProcedure` (departure 28, whose "stays as the unguarded internal close path" no longer holds). An unused
    unguarded close would only invite being wired into the janitor by mistake; `closeProcedure` is the internal path.
35. **`reopen` refuses a record that became CLOSING before its CAS** [WS §15]. The CLOSING check moved inside the CAS
    decision; it used to return silently.
36. **Workspace existence through `close`/`reopen` is documented, not changed** [WS §15]. `close` of an absent id is
    success (idempotent for the owner, e.g. after the tombstone expired), so a foreign caller can tell "not
    permitted" from success. Hiding that would make the owner's own close fail after tombstone expiry; ids
    (`ws:{sessionId}`) are not guessable. Recorded as a known, accepted limit in the javadoc and WS §15.
37. **The shell's exec runs in `/workspace`, and the wrapper moves into the root** [WS §9]. A new shell (no state
    file) `cd`s to the root; a saved directory that is gone falls back to the root (`cwd=1`) or, when the root is
    gone too, to `/workspace` (`cwd=2`, new trailer value). The notice says "the shell continues in …" instead of
    "the command ran in …" when the command had its own `workingDirectory`. The local provider now refuses a missing
    working directory instead of silently using `/workspace`, as a real exec server may. Whether execd refuses a
    missing directory is still to confirm in step 4 — with this change it no longer matters to the shell.
38. **The command never travels as an argument** [WS §9, §11.1]. The outer writes it to `run-{id}.cmd` with the
    `printf` builtin and the inner reads it (`read -d ''`); a command whose quoted form exceeds 64 KiB is uploaded
    through the files API and not embedded at all. Staging verification embeds its file list only up to 32 KiB; a
    longer list is uploaded as a NUL-separated file under `.aimon-staged` and removed afterwards. What remains for
    step 4: confirm against execd how it passes the script (one argv string or stdin) and its own request-size limit.
39. **The state save cannot be broken by the command's `PATH` or its functions, and hides itself** [WS §9]. It uses
    builtins and `command -p mv`, unsets functions named `builtin`/`command`, and removes its temp file when the save
    fails. The EXIT trap turns off `-x`/`-v` and clears DEBUG/RETURN inside a silenced group before anything runs, so a
    DEBUG trap no longer traces the save; under `set -v` the one-line trap string is still echoed (bash echoes a trap
    string as it reads it) — documented.
40. **A command that removes `.aimon-shell` is reported as a wrapper failure** (exit 71, "its output is lost") [WS §9,
    §15], not as exit 0 with empty output.
41. **A `null` timeout is no timeout** [WS §9], as core defines it: no watchdog, and an exec backstop of one day. It
    used to become two minutes.
42. **The timeout kill is documented as best-effort** [WS §9]: jobs the command put into their own process group
    (`set -m`, `setsid`) survive the watchdog's `kill -KILL 0` until the sandbox goes. Killing by session or cgroup
    needs execd — deferred to step 4.
43. **Startup refuses `terminate-after < 3 × activity-write-interval`** [WS §13.2], so one late or failed heartbeat
    write cannot let a running command's sandbox expire.
44. **A fork request without a principal keeps its parent's caller** [WS §3.1, §8.2]. Found while testing §16 row 15 in
    FORK mode: aimon-core's `SubagentBackedSkillForkExecutor` builds the fork's environment request without the
    principal, so `callerOf(empty)` produced an anonymous caller and every skill-fork `Bash` failed "not permitted".
    The fork acts for its parent, whose caller already passed the gate, and the parent environment it was handed is
    its entitlement; a fork that carries a principal still gets that principal. The core gap itself (the principal
    is not forwarded on the skill-fork path) is left to aimon-core — no core change was made here.
45. **The janitor has a scheduler thread of its own** (assembly), and a scheduled task that throws an `Error` keeps
    running at its next period. A failed assembly after startup validation closes the schedulers it started.
46. **A listing over 100 000 entries fails** [WS §11.1] instead of returning a silently truncated tree to Glob/Grep.
47. **Content search normalises its path and honours cancellation** [WS §7]: rg gets the absolute, normalised path (so
    `./src//` and `../repo/src` read back as `src/…`), and a cancelled query kills rg. Staging verification, the seed
    and the exec-shell sweep kill their command when interrupted, as `RunningCommand.await` asks.
48. **Testkit behaviour** [WS §16]: the local provider refuses a missing working directory (37), answers
    `SandboxNotFoundException` through a connection opened before `destroy`, kills every process group its commands
    started on `destroy`/`close` (a `sleep 600 &` no longer outlives its sandbox), signals the leader when a kill
    lands before perl's `setpgrp`, and makes no-replace writes and moves atomic (`link(2)` for files, a `mkdir(2)`
    reservation for directories — `rename(2)` replaces). `FaultInjectingSandboxProvider` lets a one-shot rule win over
    a standing one; `injectAt` counts since the last `resetCounts()` (javadoc corrected). Departure 24's fixed
    `sleep 17.321` became a random timeout per run.
49. **The provider contract suite is stricter** [WS §16]: `kill()` must end the victim's background child, a provider
    advertising EXPIRY must report `expiresAt`, output must reach the `OutputSink`, and a connection opened before
    `destroy` must answer not found.
50. **A no-replace move of a symbolic link moves the link** (fixed after follow-up review 1). Departure 48's
    `link(2)`-then-unlink path followed a symlink source on macOS — the link became a hard link to its target's
    content, and a link to a directory was refused. A symlink source is now re-created at the target with
    `symlink(2)` (which refuses an existing target) and then removed, as the old rename did
    (`LocalProcessSandboxProviderTest#aNoReplaceMoveOfASymbolicLinkMovesTheLinkItself`, links to a file and to a
    directory, plus refusal over an existing target).

### Fixed after PR review 1

51. **The owner records the principal's type** [WS §5.1, §8.3]. `WorkspaceOwner` was `(tenant, principal id)`, while
    core's identity of a principal is type + id: under `workspace-access: principal` a USER `eng` passed the check of a
    GROUP `eng` workspace (and the reverse), a USER `system` passed that of an allowed SYSTEM `system`, and with
    `require-principal` off a USER `anonymous` was every principal-less execution. The owner is now
    `(tenant, TYPE:id)` built with `WorkspaceOwner.of(TenantId, Principal)`; a principal-less execution is
    `WorkspaceOwner.anonymous(tenant)`, recorded as the typeless `anonymous`. The string factory is now
    `WorkspaceOwner.parse`, which refuses a bare id, so a custom policy that built `of(tenant, "alice")` fails to
    compile instead of silently never matching. Done before the step-6 JDBC store persists owners, so no data
    migration is needed (`OwnerCheckTest#aUserAndAGroupWithTheSameIdAreDifferentOwners`,
    `#aUserNamedLikeASystemPrincipalIsNotThatPrincipal`, `#aUserNamedAnonymousIsNotAPrincipalLessExecution`,
    `WorkspaceRecordsTest#ownersCarryThePrincipalType`).
