# Step 4 implementation design — `aimon-sandbox-opensandbox`: the production provider and reconciliation

> Status: **IMPLEMENTED** (implementation step 4 of [`workspace-sandbox.md`](workspace-sandbox.md) §18). The body
> below is the design as approved; where the code departed from it, §13 says so and why. The architecture document
> [`workspace-sandbox.md`](workspace-sandbox.md) has absorbed this design's departures (§7, D1–D10) and decisions
> (§10), and its §20 carries the questions that outlive this step.

> Scope: `docs/design/workspace-sandbox.md` (below: **WS**) §18 item 4, and nothing from items 5+.
> Authority: WS is the architecture; [`opensandbox-spike.md`](opensandbox-spike.md) (below: **spike**) records the server behaviour the provider must honour; `workspace-sandbox-step3.md` (below:
> **S3**) is the pattern this document follows. Where step 4 cannot follow WS as written, the departure is listed in
> §7 and is folded into WS in the same branch. This design becomes `docs/design/workspace-sandbox-step4.md` in the
> repository (TASK acceptance 6).
>
> Grounding: every existing name below was checked against the repository at `d6cf485` (branch
> `herdr/opensandbox-provider-step4`) — `SandboxProvider` and its value types, `SandboxLabels`,
> `SandboxStartupValidator`, `SandboxSeeder`, `SandboxJanitor`, `SandboxWorkspaceManager` (`create`, `seed`,
> `terminateSlot`, `reclaimProvisioning`, `BoundActivity`), `SandboxEvent.Type` (`ORPHAN_DESTROYED`,
> `DUPLICATE_DESTROYED` already exist), `SandboxProviderContract`, `FaultInjectingSandboxProvider`, and
> `buildSrc/.../aimon.java-conventions.gradle.kts` (Java 17 toolchain; `docker`/`packaging` tiers). Spike facts are
> cited as "spike §n" or by report ("B §d" = `spike/opensandbox/reports/B-docker.md` section d).

---

## 1. Problem, restated

Steps 1–3 built everything except a provider anyone can run in production: the only `SandboxProvider` is the
test-only `LocalProcessSandboxProvider`, so steps 3 and 4 must ship together (WS §18-4). Step 4 adds a new module,
`aimon-sandbox-opensandbox`, whose `OpenSandboxProvider` implements the SPI against the OpenSandbox REST API (lifecycle
server + execd, called directly with the JDK `HttpClient` and Jackson — spike §8) and honours what the spike measured:
`timeout` is always sent, expiry is forward-only and capped by the provider, kill and await-timeout send an explicit
`DELETE /command`, output arrives line-framed, file writes go through a temporary name, `DELETE` of an absent sandbox
is success, errors are classified by HTTP status, the execd endpoint is re-resolved after a connection failure. The
provider advertises capabilities exactly per WS §6.4 / spike §1 from operator declarations (`egress-enforcement`,
`network-isolation`, `hardened-security-context`, `runtime-class`, `volume-reclaimer`, …). Around it, step 4 closes the
three WS §20 items it owns — output-byte normalization, the credential-binding overlap check, the seed's control-plane
network check plus the `enforcementMode` comparison — and adds the janitor's **sandbox reconciliation** (ORPHAN ·
STALE · DUPLICATE · FAILED-LEFTOVER · LOST, WS §10.4 item 2) scoped by the `deployment` label, the only mechanism that
reclaims what an `InMemory` store forgets on restart. Acceptance: `./gradlew build` green with unit tests against a
fake HTTP server; the provider contract suite passing against a real OpenSandbox Docker-runtime server under
`@Tag("docker")` (run locally and reported — Docker 29.2.1 arm64 is available on this host); K8s-runtime checks
written under `@Tag("k8s")` for manual/pre-release runs.

---

## 2. Boundaries

**In step 4**: the new module (provider, config, connection, files, commands, credential vault client, error
mapping); the SPI additions of §5.1; WS §6.3 label acceptance on a real server; `deployment`-scoped reconciliation
(WS §10.4 item 2, all rows except DRIFT); `orphan-grace` and `lost-confirm-after` settings; credential bindings in
profiles (the step-3 refusal is lifted, replaced by the §13.2 checks); the seed's network-isolation probe and the
provider-side post-create verification (egress enforcement mode, vault bindings); the contract-suite adjustment for
line-normalized output; the `docker` tier's first subject and a new `k8s` tier; WS §16 rows marked stage 4.

**Not in step 4**: pause/resume and DRIFT (step 7 — `PAUSE_RESUME` is never advertised; `pause`/`resume` throw
`UnsupportedOperationException`); built-in `VolumeReclaimer` implementations and volume reconciliation (step 5 —
§6.1, Q3); `sharedAccess ≠ none`, git seed, multiple slots, orchestrator tools (step 5); JDBC store and the real
multi-process run of the multi-node scenarios (step 6); warm pool (step 7); `SNAPSHOT`/`FORK` (step 8); §14
metrics/spans (Q2); a CI workflow (none exists in the repository — Q10); upstream issues or anything pushed outside
this repository (TASK acceptance 7). The spike evidence under `spike/opensandbox/` and `docs/design/opensandbox-spike.md`
are not modified.

---

## 3. Approach, and what was rejected

### 3.1 Chosen approach

1. **One new published module, `modules/aimon-sandbox-opensandbox`, package `at.aimon.sandbox.opensandbox`.** Public
   surface: `OpenSandboxProvider`, `OpenSandboxProviderConfig` (+ its builder and small enums/value types),
   `CredentialDefinition`, `VolumeReclaimer`. Everything that speaks HTTP is package-private. No OpenSandbox type
   exists at all (no SDK), so WS §21's "no SDK types outside the module" holds trivially.
2. **REST directly, JDK `HttpClient` + Jackson** (spike §8; both already in the catalog). Two small clients: the
   lifecycle client (`/v1/sandboxes…`, header `OPEN-SANDBOX-API-KEY`) and an execd client per connection (endpoint
   from `GET /v1/sandboxes/{id}/endpoints/44772`, plus the `headers` it returns). The egress-sidecar vault client
   reuses the execd client shape on port 18080.
3. **The provider enforces what the server does not** (spike §4-1): `create` always sends `timeout`; `extendExpiry`
   reads the current `expiresAt`, ignores a backward move, clamps to `now + max-expiry`, and refuses a paused
   sandbox; `destroy` maps 404 to success; `maxCaptureBytes` is enforced client-side per stream.
4. **Capabilities are derived from the config, never probed at startup** (WS §6.4): the server exposes none of the
   deciding facts before a sandbox exists. What *can* be checked on a live sandbox is checked once per generation,
   in the seed step, through a new SPI hook (`verify`, §5.1) — so a wrong declaration fails a slot permanently
   instead of running open.
5. **Declared provider configuration the manager must check is exposed as data on `ProviderCapabilities`**, the
   place step 3 already put `maxExpiry` (S3 D4): the server's runtime class, the credential scopes, the
   control-plane endpoints. The §13.2 checks (runtime-class equality, credential overlap) then live once, in
   `SandboxStartupValidator`, and are tested without the provider.
6. **Reconciliation is manager-side, provider-agnostic**, in a new package-private `workspace/SandboxReconciler`
   run by `SandboxJanitor.runOnce()` after idle enforcement: scan first, list second, classify each listed sandbox
   with a pure decision function (the WS §10.4 table), re-read the record before every destroy, confirm LOST by
   time (`missingSince` + `lostConfirmAfter`). It is fully tested in the default build with the local provider,
   the fault injector and a manual clock; the real server only has to prove the labels and listing work.
7. **Output is line-normalized and the SPI says so** (WS §20 item, spike §4-3 recommendation). The contract test
   that assumed byte-exact output switches to newline-terminated output.
8. **Tests in three tiers**: default (`test`) — the provider against a scripted fake server built on the JDK's
   `com.sun.net.httpserver.HttpServer` (no new dependency), plus all reconciliation/validator/seeder logic;
   `docker` (`integrationTest`) — the contract suite and stage-4 WS §16 rows against a real OpenSandbox Docker-runtime
   server started per run by Testcontainers; `k8s` (new `k8sTest`) — K8s-only checks against a pre-provisioned
   cluster, manual/pre-release.

### 3.2 Rejected alternatives

| Alternative | Why rejected |
|---|---|
| OpenSandbox Kotlin SDK (`com.alibaba.opensandbox:sandbox`) | Spike §8: `run()` buffers output without a cap and blocks, the command id needed for `kill()` only arrives in a callback, internals are Kotlin `internal`; drags OkHttp + Kotlin stdlib onto the classpath. The REST surface is small (eight lifecycle calls, `/command`, `/command/status`, a few file calls). |
| Byte-exact output: have execd write to files and read them back with `/files/download` | Two extra HTTP round-trips per command (spike §4-3). The §9 wrapper does not need bytes (the trailer is one line, truncation is read from `out=`/`err=`), and no current consumer does. Kept as the documented escape hatch. |
| Provider-side profile validation hook (`validateProfile(SandboxProfile)`) for the credential overlap check | Couples the provider SPI to the `profile` package, makes every provider re-implement a security check, and cannot be tested without a provider. Exposing scopes as data (§5.1) keeps one implementation in the validator. |
| The provider runs the network-isolation probe itself inside `verify` | Each provider would re-implement an in-sandbox probe; the generic shell probe in `SandboxSeeder` is testable in the default build (local provider + a local `ServerSocket`). The provider only supplies *where* to probe. |
| Keeping the orphan grace clock in the record | An orphan by definition has no (matching) record. The grace is measured from the sandbox's `createdAt` (new, optional, on `ProviderSandbox`), falling back to the janitor's node-local first-seen time. |
| `create` destroys a sandbox that did not become Running in time | Another node's takeover may already have adopted it through the key lookup (WS §6.3). Throw transient; the record goes FAILED and reconciliation reclaims the leftover (FAILED-LEFTOVER), which re-reads the record first. |
| Kill the command when output exceeds `maxCaptureBytes` (spike §3 says "and ends the command") | Changes the observable exit code of a command that merely printed a lot, diverges from the local provider, and the §9 wrapper's output cannot reach the backstop once the backstop allows for normalization (see §5.3: `SandboxShell` raises it to `3 × max + 1 KiB`). The provider discards the excess and keeps draining; runaway output is bounded by the command timeout. Recorded as departure D8. |
| Leader election for reconciliation | WS §19: CAS plus re-read-before-destroy plus idempotent provider calls suffice. |
| Starting the test server with `uv` from an OpenSandbox checkout, or `docker run` via `ProcessBuilder` | The first needs `uv` and a source checkout on every machine; the second re-implements container lifecycle. Testcontainers is what WS §16 names; an externally started server can still be used through an environment override (§9.2). |
| Running the contract suite against the fake server | The fake cannot execute commands or keep a file system; it scripts responses. The contract suite runs against the real server; the fake covers wire-level behaviour and error paths the real server cannot be made to produce on demand. |
| Putting the `opensandbox.*` keys into `SandboxSettings` | `aimon-sandbox` must not know OpenSandbox (WS §4.1). The provider owns its config; the manager sees only `ProviderCapabilities`. |

---

## 4. Concrete changes by file

### 4.1 Build

| File | Change |
|---|---|
| `settings.gradle.kts` | `include("aimon-sandbox-opensandbox")`, `projectDir = file("modules/aimon-sandbox-opensandbox")`; the comment's "joins as its step lands" sentence updated. |
| `modules/aimon-sandbox-opensandbox/build.gradle.kts` | `aimon.java-conventions` + `aimon.publishable`; `api(project(":aimon-sandbox"))` (SPI types on public signatures); `implementation(libs.jackson.databind)`, `implementation(libs.slf4j.api)`; `testImplementation(project(":aimon-sandbox-testkit"))`, `testImplementation(libs.testcontainers)` (docker tier only uses it). |
| `modules/aimon-sandbox-opensandbox/gradle.properties` | `POM_ARTIFACT_ID=aimon-sandbox-opensandbox`, name, description. |
| `modules/aimon-sandbox-opensandbox/README.md` | Config keys, capability derivation table, operator contract (WS §13.3), how to run the `docker` and `k8s` tiers. |
| `gradle/libs.versions.toml` | `testcontainers` version + library (exact version pinned at implementation; nothing else new). |
| `buildSrc/.../aimon.java-conventions.gradle.kts` | New `k8sTest` task (`includeTags("k8s")`); `test` excludes `k8s` as well as `docker`/`packaging`; `integrationTest` excludes `k8s` (a test tagged both must not run in the docker tier); the "IMPORTANT: neither tier has a subject" comment rewritten — the docker tier now has one; `JacocoReportBase` `mustRunAfter` gains `k8sTest`. |
| `gradle/coverage-baselines.properties` | `aimon-sandbox-opensandbox=<measured floor>` from the **default tier alone**, so the floor never requires Docker; the comment records the measurement. `aimon-sandbox` / testkit floors raised only if the new tests raise them. |
| `CHANGELOG.md` | `[Unreleased]` "Added — implementation step 4"; the step-3 bullet "Refused at startup … `credentials` (step 4)" updated. |

### 4.2 `aimon-sandbox` (SPI and manager side)

| File | Change |
|---|---|
| `provider/ProviderCapabilities.java` | Builder; new fields `runtimeClass()`, `credentialScopes()`, `controlPlaneEndpoints()` (§5.1). `of(advertised, maxExpiry)` kept. |
| `provider/CredentialScope.java` (new) | Value type `{schemes, hosts, methods, paths}` with defaults and `overlaps(other)` (§6.6). |
| `provider/HostPort.java` (new) | `{host, port}` value type for `controlPlaneEndpoints()`. |
| `provider/VerificationFailure.java` (new) | `{step, reason}` returned by `SandboxProvider.verify`. |
| `provider/SandboxProvider.java` | `default List<VerificationFailure> verify(ProviderSandboxRef ref, Set<Capability> required)` returning empty (§5.1). |
| `provider/ProviderSandbox.java` | Optional `createdAt()`; `of(ref, state, labels, expiresAt)` kept, `of(…, createdAt)` added. |
| `environment/SandboxShell.java` | Provider backstop `maxCaptureBytes = 3 × max + TRAILER_ALLOWANCE` (§5.3). |
| `provider/ExecOutcome.java`, `provider/SandboxConnection.java` javadoc | "Output may be line-normalized" (§5.3, WS §6.1). |
| `SandboxSettings.java` | `orphanGrace` (10m), `lostConfirmAfter` (90s). |
| `SandboxStartupValidator.java` | Remove the credentials refusal; add unknown-binding, overlap, egress-coverage, runtime-class equality, `orphan-grace > provision-timeout`, positive `lost-confirm-after` (§6.6). |
| `workspace/SandboxSeeder.java` | Network-isolation probe when `NETWORK_ISOLATION` is required and not waived (§6.4); takes the probe targets. |
| `workspace/SandboxWorkspaceManager.java` | `seed()` calls `provider.verify(ref, profile.requiredCapabilities())` before the seeder; a failure is FAILED(permanent, step) + destroy, like a seed check. Package-private helpers for the reconciler: `markMissing`, `clearMissing`, `confirmLost`, `destroyIfStill` (§6.5). The "left to provider expiry" WARN after a lost RUNNING CAS becomes "left to reconciliation". |
| `workspace/SandboxReconciler.java` (new, package-private) | Scan → list → classify → act (§6.5). |
| `workspace/SandboxJanitor.java` | `runOnce()` runs the reconciler after the idle items; class javadoc updated (items 1–2; item 3 in step 5). |
| `WorkspaceSandbox.java` | Passes `capabilities().controlPlaneEndpoints()` to the manager/seeder (via the manager builder). |

### 4.3 `aimon-sandbox-opensandbox` (new) — `at.aimon.sandbox.opensandbox`

| Class | Visibility | Role |
|---|---|---|
| `OpenSandboxProvider` | public | `SandboxProvider`; owns the `HttpClient`, the call semaphore and the stream-reader executor; `close()` shuts both down (does not destroy sandboxes). |
| `OpenSandboxProviderConfig` (+ `Builder`, `Runtime`, `EgressEnforcement`, `Declaration`) | public | §13.2 keys (§5.2); validated in `build()`. |
| `CredentialDefinition` (+ `Auth`) | public | One vault binding: scope + auth type + secret `Supplier<String>`; `toString` redacts. |
| `VolumeReclaimer` | public interface | `list(prefix)`, `delete(name)` — the WS §6.4 seam; no implementation in step 4 (Q3). |
| `LifecycleClient` | package | `/v1/sandboxes` create/get/list(paged)/delete/renew/endpoints/networkpolicy. |
| `ExecdClient` | package | `/command` (SSE), `DELETE /command`, `/command/status/{id}`, files API; endpoint cache + one re-resolution on connect failure. |
| `SseEvents` | package | Tolerant SSE/JSON-lines parser (blank-line separated JSON, optional `data:` prefix). |
| `OpenSandboxConnection` / `OpenSandboxRunningCommand` / `OpenSandboxFiles` | package | `SandboxConnection` / `RunningCommand` / `SandboxFiles` (§6.2–6.3). |
| `VaultClient` | package | `POST/GET /credential-vault` on the egress sidecar (port 18080). |
| `HttpErrors` | package | Status → `SandboxNotFoundException` / `SandboxProviderException(kind)` / VFS exception (§8.1). |
| `Retry` | package | Retries idempotent calls on transient failures (`retry.max-attempts`, `retry.backoff`). |

### 4.4 `aimon-sandbox-testkit`

| File | Change |
|---|---|
| `SandboxProviderContract.java` | `execTruncatesStdoutAndStderrSeparately` uses newline-terminated output (§5.3); `DEPLOYMENT` becomes an overridable `protected String deployment()` (default `"contract"`) so a shared server can use `ci-{runId}` (WS §16); new `statusReportsCreatedAtWhenKnown` (present ⇒ not in the future). |
| `LocalProcessSandboxProvider.java` | Reports `createdAt`. |
| `FaultInjectingSandboxProvider.java` | Passes `verify` through (new `Operation.VERIFY`, so faults can be injected). |

### 4.5 Docs (same branch)

`docs/design/workspace-sandbox-step4.md` (this design, then §12 "implementation departures" as S3 did); WS edits
listed in §7; module README; CHANGELOG.

---

## 5. Data and interface shapes

### 5.1 SPI additions (all additive; existing implementations compile unchanged)

```java
public final class ProviderCapabilities {
    Set<Capability> advertised();
    Optional<Duration> maxExpiry();                    // step 3 (S3 D4)
    Optional<String> runtimeClass();                   // the server's class; profiles' runtimeClass must equal it (§13.2)
    Map<String, CredentialScope> credentialScopes();   // binding name -> scope; no secrets (§12.1, §13.2)
    List<HostPort> controlPlaneEndpoints();            // what a sandbox must NOT reach (§11.3 probe)
    static ProviderCapabilities of(Set<Capability>, Duration);   // kept
    static Builder builder();
}

public final class CredentialScope {                   // the vault's CredentialMatch (A §Q8) minus `ports`:
                                                       // deprecated there, the port follows the scheme (443/80),
                                                       // so ports are ignored and overlap errs towards "overlaps"
    Set<String> schemes();   // default [https]
    Set<String> hosts();     // required; exact or "*.suffix"
    Set<String> methods();   // default GET, POST, PUT, PATCH, DELETE
    List<String> paths();    // default ["/*"]; trailing '*' = prefix, else exact
    boolean overlaps(CredentialScope other);
}

public interface SandboxProvider extends AutoCloseable {
    // ... WS §6.1 unchanged ...
    /**
     * Checks, on a live sandbox, what only the provider can see (seed step, §11.3): e.g. the egress enforcement
     * mode the server applied. Called once per generation before the seed script; idempotent.
     * @param required the profile's required capabilities (after insecure-allow)
     * @return failed checks, each permanent for this profile; empty when all hold or nothing is checkable
     * @throws SandboxNotFoundException when the sandbox is gone
     * @throws SandboxProviderException (transient) when the check could not run
     */
    default List<VerificationFailure> verify(ProviderSandboxRef ref, Set<Capability> required) { return List.of(); }
}

public final class ProviderSandbox { /* + */ Optional<Instant> createdAt(); }
```

Why on `ProviderCapabilities` rather than new provider methods: they are declared configuration read once at startup,
exactly like `maxExpiry`; the validator already receives `ProviderCapabilities` through `Wiring`.

### 5.2 `OpenSandboxProviderConfig` (WS §13.2 `aimon.sandbox.opensandbox.*`)

| Key | Type / default | Effect |
|---|---|---|
| `endpoint` | URI, required | Lifecycle server base (`…/v1` appended if absent). |
| `api-key` | `Supplier<String>`, required | `OPEN-SANDBOX-API-KEY`; never logged. |
| `runtime` | `docker` \| `kubernetes`, **required, no default** | Decides which declarations are legal (below). New key — WS §13.2 gains it (D2). |
| `max-expiry` | Duration, required, ≥ 60s | `ProviderCapabilities.maxExpiry`; the server's `max_sandbox_timeout_seconds`. |
| `egress-enforcement` | `none` \| `dns` \| `dns+nft`, default `none` | `EGRESS_POLICY` iff `dns+nft`. `dns` is accepted and logged as "not advertised" (it leaks by IP, spike §6). |
| `network-isolation` | `undeclared` \| `declared`, default `undeclared` | `NETWORK_ISOLATION` iff `declared`; `declared` with `runtime: docker` is a config error (spike §7). |
| `hardened-security-context` | same | `HARDENED_SECURITY_CONTEXT` iff `declared`; illegal on docker (B §g). |
| `runtime-class` | `{name, kind: gvisor \| kata \| other}`, optional | `RUNTIME_CLASS` + `runtimeClass()` (the name); illegal on docker. `kind` is required with a name: the provider cannot tell from a class name what runtime it selects, and the server refuses `networkPolicy` under gVisor (below). New sub-key (D2). |
| `credentials` | `Map<String, CredentialDefinition>`, default empty | `credentialScopes()`; `CREDENTIAL_INJECTION` iff non-empty **and** `dns+nft` (non-empty without `dns+nft` is a config error — the server refuses `credentialProxy` otherwise, A §Q8). |
| `control-plane-probes` | `List<HostPort>`, default derived | `controlPlaneEndpoints()`. Default: the endpoint's host:port, plus `kubernetes.default.svc:443` on `kubernetes`. Set it explicitly when AIMON reaches the server through NAT/port-forward (the JVM's address is not the sandbox's). `NETWORK_ISOLATION` declared with an empty list is a config error — the probe would be vacuous. |
| `volume-reclaimer` | `VolumeReclaimer` instance, optional | `SHARED_VOLUME` + `sharedVolumes()` iff present. Step 4 ships no built-in `kubernetes`/`docker` reclaimer (Q3). |
| `use-server-proxy` | boolean, default `false` | Resolve execd/egress endpoints with `?use_server_proxy=true` (K8s from outside the cluster; the docker test tier). |
| `request-timeout` | 30s | Per non-streaming call (headers + body). |
| `create-timeout` | 90s | `POST /sandboxes` (K8s blocks up to 60s, C §1) plus waiting for `Running`. New key (D2). |
| `retry` | `{max-attempts: 3, backoff: 500ms}` | Idempotent calls only: status, list, destroy, renew, endpoints, file stat/list/read. Never create, `/command`, upload. |
| `max-concurrent-calls` | 32 | Semaphore over request/response calls. SSE streams do not hold a permit (a 20-minute build must not starve the janitor). |
| `entrypoint` | default `[tail, -f, /dev/null]` | Keeps the sandbox alive regardless of the image's CMD (Q7). New key (D2). |
| `default-resources` | `{cpu: 1, memory: 1Gi}` | `resourceLimits` when the profile gives none — required on K8s (422 otherwise, C §1). New key (D2). |

`OpenSandboxProviderConfig.build()` collects every violation into one `SandboxConfigurationException` (reusing the
`aimon-sandbox` type) — the same "list them all" rule as the validator.

Capability derivation (`capabilities()`):

| Capability | Advertised when |
|---|---|
| `EXEC`, `FILES`, `EXPIRY` | always |
| `EGRESS_POLICY` | `egress-enforcement: dns+nft` — and `runtime-class.kind` is not `gvisor` (that combination is a config error, below) |
| `CREDENTIAL_INJECTION` | `dns+nft` and `credentials` non-empty |
| `NETWORK_ISOLATION` | `network-isolation: declared` (kubernetes only) |
| `HARDENED_SECURITY_CONTEXT` | `hardened-security-context: declared` (kubernetes only) |
| `RUNTIME_CLASS` | `runtime-class` set (kubernetes only) |
| `SHARED_VOLUME` | a `volume-reclaimer` is supplied |
| `PAUSE_RESUME`, `SNAPSHOT`, `FORK` | never in step 4 (steps 7, 8) |

**`dns+nft` with a gVisor runtime class is refused by `OpenSandboxProviderConfig.build()`.** When the effective
runtime is gVisor the server rejects every `networkPolicy` with 400 — gVisor's netstack has no iptables nat table
(A §Q8 `validators.py:585-619`; spike §1; WS §6.4 `EGRESS_POLICY` row: "gVisor 와는 같이 쓸 수 없다"). Advertising both
would let every profile with `egress` (including `egress: []`) pass startup and then fail each slot FAILED(permanent)
at its first `create`. Refusing, rather than silently dropping `EGRESS_POLICY`, puts the error where the wrong
declaration is: the server's `[egress].mode` is unusable with that runtime, so declaring it is a mistake, and dropping
it would surface later as a confusing "provider does not advertise EGRESS_POLICY" on every egress profile. Because
`CREDENTIAL_INJECTION` requires `dns+nft`, it is refused with it. Kata is allowed (same source). WS §13.2's example
config (`dns+nft` + `runtime-class: gvisor`) is corrected in the same branch to `kind: kata` (D2).

**Docker `network_mode`.** The server also rejects `networkPolicy` with 400 unless `[docker] network_mode = "bridge"`
(`host`, the server default, and user-defined networks are refused — A §Q8). The provider cannot see that setting;
the README's operator contract states it, and `egress-enforcement: dns+nft` on `runtime: docker` is documented as
requiring it. A misconfigured server surfaces as FAILED(permanent, `create`) carrying the server's 400 message.

### 5.3 Output normalization (closes the WS §20 item)

SPI text added to `ExecOutcome`/`SandboxConnection.run` javadoc and WS §6.1: *a provider may deliver stdout and stderr
line-normalized — each line terminated by `\n` (a final newline is always present on non-empty output), `\r` dropped
or turned into a line break (B §d saw `A\r\nB` arrive as `A`, `B`; a lone `\r` was not measured — the docker tier
adds that case and the step-4 doc records what it saw), invalid UTF-8 replaced by U+FFFD. Consumers that need exact byte counts must carry them in-band (the §9
trailer does).* The OpenSandbox provider appends `\n` to each `stdout`/`stderr` event text (an event whose text is
`"\n"` is one empty line — B §d), UTF-8-encodes, and counts bytes after encoding for `maxCaptureBytes`.

**Backstop sizing.** Normalization can grow output: execd turns each invalid input byte into U+FFFD (3 bytes in
UTF-8). The wrapper's `head -c {max}` caps *raw* bytes, so after normalization a stream can reach `3 × max` plus the
trailer. With step 3's `maxCaptureBytes = max + 1 KiB` (`SandboxShell`), a command writing more than ≈`(max+1024)/3`
bytes of non-UTF-8 to stderr would lose its trailer and be misreported as a wrapper failure. `SandboxShell` therefore
sets the backstop to `3 × max + TRAILER_ALLOWANCE` (and its construction-time check becomes `backstop ≥ 3 × max +
trailer`). It remains a backstop — the local provider never gets near it — and costs at most 3 MiB per stream at the
1 MiB default. Recorded in D4; `SandboxShell.java` joins §4.2's change list.

Contract change:

```java
// was: printf 'aaaaaaaaaaaaaaaaaaaa'; printf 'bbbbb' >&2   (expects "bbbbb" exactly)
connection.run(ExecSpec.builder().command("printf 'aaaaaaaaaaaaaaaaaaaa\\n'; printf 'bbbbb\\n' >&2")
        .maxCaptureBytes(10)...);
assertThat(text(outcome.stdout())).isEqualTo("aaaaaaaaaa");     // cut inside the line
assertThat(outcome.stdoutTruncated()).isTrue();
assertThat(text(outcome.stderr())).isEqualTo("bbbbb\n");        // 6 bytes, under the cap
assertThat(outcome.stderrTruncated()).isFalse();
```

### 5.4 Wire shapes the provider sends (from spike, B/C reports)

```jsonc
// POST /v1/sandboxes  -> 202 {id, ...}
{ "image": {"uri": "<spec.image>"}, "entrypoint": ["tail","-f","/dev/null"],
  "timeout": <ceil((expiresAt-now)/1s), min 60>,            // always sent (spike §4-1)
  "resourceLimits": {"cpu": "2", "memory": "4Gi"},           // profile, else default-resources
  "env": {...spec.environment}, "metadata": {...spec.labels},
  "networkPolicy": {"defaultAction": "deny", "egress": [{"action":"allow","target":"github.com"}]},  // iff egress present, [] included
  "credentialProxy": {"enabled": true},                      // iff credentials non-empty
  "volumes": [{"name": "...", "mountPath": "/shared", "readOnly": false,
               "pvc": {"claimName": "...", "createIfNotExists": true}}] }                              // from VolumeMount
// GET  /v1/sandboxes?metadata=<urlencode("k=v&k=v")>&pageSize=200&page=N     (ONE metadata param, B §a)
// POST /v1/sandboxes/{id}/renew-expiration {"expiresAt": "<RFC3339 UTC>"}
// GET  /v1/sandboxes/{id}/endpoints/44772[?use_server_proxy=true] -> {endpoint:"host:port[/path]", headers?}
// GET  /v1/sandboxes/{id}/networkpolicy -> {mode, enforcementMode, policy:{defaultAction, egress}}
// execd: POST /command {command, cwd, envs, timeout(ms)} -> SSE; DELETE /command?id=<init id>; GET /command/status/{id}
// egress sidecar (port 18080): POST /credential-vault {credentials:[{name, source:{type:"inline", value}}],
//                                                     bindings:[{name, match:{schemes,hosts,methods,paths}, auth:{...}}]}
```

`runtimeClass` and `platform` are never sent: the server has no per-request class (C §b); `CreateSpec.runtimeClass`
different from the configured one is a `PERMANENT` error (defence in depth — the validator already refuses it). `disk`
and `pids` have no per-request field (Q8).

### 5.5 State mapping (`status`, `list`)

| OpenSandbox | `ProviderSandboxState` |
|---|---|
| `Pending`, `Resuming` | `PENDING` |
| `Running` | `RUNNING` |
| `Pausing`, `Paused` | `PAUSED` |
| `Stopping`, `Terminated` | `TERMINATED` |
| `Failed` | `FAILED` |

404 on `GET` → empty. Labels = `metadata`; `expiresAt`, `createdAt` parsed as RFC3339.

---

## 6. Mechanisms

### 6.1 `create(spec)` — best-effort idempotent (WS §6.3)

1. `list({managed, deployment, sandbox-key = spec.labels[sandbox-key]})`; a hit that is neither TERMINATED nor FAILED is reused (a FAILED one would only wait out `create-timeout`): ensure its
   vault (step 4 below, `GET` then `POST` if absent), wait for `Running` (step 3), return its ref. Several hits
   (takeover race): return the oldest by `createdAt`; the others become DUPLICATE for reconciliation.
2. `POST /sandboxes` (§5.4) with `create-timeout`. Not retried (not idempotent — a lost response is exactly the
   "created but response lost" fault the record and reconciliation absorb).
3. Poll `GET` until `Running` (Docker returns while `Pending`; K8s returns Running) within `create-timeout`.
   `Failed` or timeout → `SandboxProviderException(TRANSIENT)`; **the sandbox is not destroyed** (§3.2).
4. Credentials: resolve the egress endpoint (port 18080), `POST /credential-vault` with the secrets read from the
   `CredentialDefinition` suppliers **now** (rotation takes effect on the next generation). Failure →
   `SandboxProviderException` (403/400 permanent, else transient); the sandbox is left to FAILED-LEFTOVER.
5. Return `ProviderSandboxRef.of("opensandbox", id)`.

The manager then calls `status(ref)` and verifies labels exactly as in step 3 — this is the "real server accepts the
labels" check of §18-4, exercised by the docker tier with `ws:{uuid}` ids.

### 6.2 Commands (`run`, `await`, `kill`)

- `run` → `POST /command {command, cwd, envs, timeout: spec.timeout in ms}` streamed on a provider-owned daemon
  thread (bounded cached pool, named `opensandbox-exec-N`; Java 17 — no virtual threads). The `init` event's `text` is
  the command id; `stdout`/`stderr` events go to the `OutputSink` and to per-stream buffers capped at
  `maxCaptureBytes` (excess counted and discarded, `*Truncated=true`); `execution_complete` → exit 0; `error` →
  `Integer.parseInt(evalue)` (`-1` for a signal); `ping` ignored.
- A 400 before the stream (missing cwd, B §d) → `SandboxProviderException(PERMANENT)` with the server message; the
  manager's seed already runs in `/`.
- `await(timeout)`: waits for completion; past the timeout → `kill()` then returns with `timedOut=true`. Also
  `timedOut=true` when the spec timeout elapsed client-side and execd ended the command with `-1`.
- `kill()` → `DELETE /command?id=` (idempotent; a 404 means it already ended) — **always sent**, because a disconnect
  does not stop the command (B §d). If the id has not arrived yet, the kill is latched and sent when `init` arrives.
- The stream ends without a terminal event (connection dropped) → poll `GET /command/status/{id}` until
  `running:false` (within the await deadline) for the exit code; the status endpoint keeps it 24h (spike §3).
- Not found: an execd connection failure triggers one endpoint re-resolution (spike §3, K8s resume changes the IP);
  if resolution answers 404, or `status(ref)` is empty → `SandboxNotFoundException`; otherwise transient.
- Background mode (`background: true`) is never used (it merges streams, spike §3).

### 6.3 Files (`SandboxFiles`)

| Call | Implementation |
|---|---|
| `read(path, offset, length)` | `stat` first (download of a directory drops the connection, B §e) → `GET /files/download?path=` with `Range: bytes=offset-(offset+length-1)`; 404 → `FileNotFoundException`. |
| `write(path, in, length, CREATE_OR_REPLACE)` | Upload to `{dir}/.{name}.aimon-tmp-{uuid}` (multipart: `metadata` as a **file part** with filename, `{path, mode: 644}` — B §e; parents are created), then `/command` `mv -f -- tmp path`; on failure delete the temp. |
| `write(…, CREATE_NEW)` | Same upload, then `/command` `ln -- tmp path && rm -f -- tmp` (link(2) is atomic; spike §4-4); `ln` "File exists" → `FileAlreadyExistsException`. |
| `stat(path)` | `GET /files/info?path=`; `modified_at` (nanoseconds) → `modifiedAt`; `type` directory → `directory=true`; no etag (spike §1). 404 → empty. |
| `list(dir, recursive, limit)` | `GET /directories/list?path=&depth=` (depth 1, or a large depth when recursive); limit applied client-side; 404 → `FileNotFoundException`, "not a directory" 400 → `VirtualFileSystemException`. |
| `createDirectories(path)` | `POST /directories {path: {mode: 755}}` (idempotent). |
| `delete(path, recursive)` | `stat` (absent → `FileNotFoundException`, matching the local provider); directory: non-recursive and non-empty → error, else `DELETE /directories`; file → `DELETE /files`. |
| `move(from, to, overwrite)` | overwrite → `/command mv -f -- from to`; else `POST /files/mv` (500 "already exists" → `FileAlreadyExistsException`; not atomic, documented — spike §4-4). |

The exact exception semantics follow `LocalProcessSandboxProvider`, verified by the shared contract tests.

### 6.4 Seed additions (closes the WS §20 "seed network check" item)

`SandboxWorkspaceManager.seed()` now does, before the existing script:

1. `provider.verify(ref, profile.requiredCapabilities())`. OpenSandbox checks:
   - `EGRESS_POLICY` required → `GET /networkpolicy`: `enforcementMode == "dns+nft"` and `policy.defaultAction ==
     "deny"`, else `VerificationFailure("egress", …)` (spike §4-6);
   - `CREDENTIAL_INJECTION` required → vault `GET`: every binding name of the sandbox present, else
     `("credentials", …)`.
   Failures → FAILED(permanent, step) and destroy (own verified sandbox — same branch as a failed seed check).
   `SandboxNotFoundException` → LOST path; other exceptions → unavailable, `seeded` stays false (as step 3).
2. The seed script gains, when `NETWORK_ISOLATION ∈ required` (not waived), after the root/token checks:

```bash
probe() {  # bash-3.2, no coreutils `timeout`: a watchdog kills the connect after 3 s
  ( exec 3<>"/dev/tcp/$1/$2" ) 2>/dev/null & c=$!
  ( sleep 3; kill "$c" ) >/dev/null 2>&1 & w=$!   # redirected: an orphaned sleep must not hold the exec's pipes
  wait "$c"; r=$?; kill "$w" 2>/dev/null; wait "$w" 2>/dev/null; return "$r"
}
probe 'opensandbox-server.opensandbox-system.svc' '80' \
  && fail network-isolation "the sandbox reaches the control plane at opensandbox-server...:80"
```

   Targets come from `capabilities().controlPlaneEndpoints()` (single-quoted data, as every declared value in the
   seeder). Any connect failure — refused, timeout, DNS — passes; only an established connection fails the slot
   (WS §11.3 "connecting … must fail"). `/dev/tcp` is a bash feature; bash is in the image contract.

### 6.5 Reconciliation (`SandboxReconciler`, WS §10.4 item 2)

```
reconcile(now):
  records := every record, all states (paged store.scan)             -- FIRST (WS §10.4)
  byHash  := { h(record.id) -> record }
  listed  := provider.list({managed: "true", deployment: settings.deployment})   -- SECOND
  seen    := refs in listed
  firstSeen.retainAll(seen); firstSeen.putIfAbsent(each listed ref, now)         -- node-local fallback clock
  for sb in listed:  act(classify(byHash.get(sb.labels[workspace]), sb, now))
  for record in records, slot RUNNING|PAUSED with providerRef:
      if ref not in seen, or listed with state TERMINATED|FAILED: lostCheck(record, slot, now)
      else if slot.missingSince present: clearMissing(record, slot)
```

`classify` is a pure static function (first matching row wins; WS §10.4 table):

| Condition | Verdict | Action |
|---|---|---|
| labels unparseable (no generation/slot/incarnation) | SKIP | WARN once; not ours to judge |
| same generation, slot FAILED with failure step `labels` | SKIP | WS §6.3: a sandbox that failed the label comparison may not be ours, so it is never destroyed — only provider expiry removes it |
| no record · record incarnation ≠ label · no slot of that name | ORPHAN | destroy once `age ≥ orphanGrace` |
| label generation > slot generation | IN-FLIGHT | none; becomes ORPHAN once `age ≥ orphanGrace` |
| label generation < slot generation | STALE | destroy |
| same generation, slot PROVISIONING | IN-FLIGHT | none |
| same generation, slot FAILED | FAILED-LEFTOVER | destroy |
| same generation, slot TERMINATED | ORPHAN | destroy once `age ≥ orphanGrace` |
| same generation, RUNNING/PAUSED, ref ≠ slot.providerRef | DUPLICATE | destroy once `age ≥ orphanGrace` |
| same generation, RUNNING/PAUSED, ref = slot.providerRef | OK | none |

`age = min(now − sb.createdAt(), now − firstSeen[ref])` (only the second when the provider gives no `createdAt`).
Taking the minimum means server/node clock skew can only delay a destroy, never hasten one: for a no-record orphan,
which gets no re-read, the margin would otherwise be only `orphanGrace − provisionTimeout` (5 min at the defaults)
against skew. The cost is that after a janitor restart (empty `firstSeen`) an orphan waits one more `orphanGrace`.

- **Re-read before destroy**: for every destroying verdict with a record, `store.find(id)` again and re-classify
  against the fresh record; destroy only if the verdict is unchanged (WS §21). For "no record", nothing can be
  re-read by hash; safety comes from the ordering invariant — a record is always created (`createIfAbsent`) before
  its first sandbox, and `orphanGrace > provisionTimeout` (validated) — so a sandbox older than the grace with no
  record in an earlier scan cannot belong to a record that exists now **with the same incarnation**.
- **Events**: `ORPHAN_DESTROYED` (causes `orphan`, `stale`, `failed-leftover`) and `DUPLICATE_DESTROYED` when a
  record exists; a no-record orphan is logged at INFO with the workspace-label hash, never the sandbox id (WS §13.3).
- **`lostCheck`**: `status(ref)` again; present and RUNNING → `clearMissing`; absent (or TERMINATED/FAILED — Q11) and
  no `missingSince` → `markMissing(now)`; `missingSince + lostConfirmAfter ≤ now` → `confirmLost`: CAS guarded on
  generation + ref + state → TERMINATED(`lostAt`), evict the connection, `LOST` event. All three use the existing
  `mutate` CAS-retry helper and write only if generation, `providerRef` and state still match (WS §10.4).
- A list or scan failure ends the reconciliation pass (logged); nothing is inferred from a partial view.

### 6.6 Startup validation additions (WS §13.2; closes the overlap-check item)

Per profile, with `scopes = capabilities.credentialScopes()`:

- every name in `profile.credentials()` is in `scopes` — else "unknown credential binding X";
- pairwise `overlaps` — else "bindings X and Y overlap (host … path …): the vault cannot tell which to inject";
- a profile with `credentials` and **absent** `egress` is refused: the provider then sends no `networkPolicy`, and the
  vault does not count `defaultAction=allow` as coverage (A §Q8, `vault.go:366-373`);
- every scope host is **equal** to an entry of the profile's `egress` — else "binding X
  targets host H, which egress does not allow" (the vault rejects uncovered hosts, A §Q8). Coverage by an egress
  wildcard (`*.s` covering `h.s`) is accepted only if the build agent confirms, in `vault.go:970-983` or on the docker
  tier, that the vault treats it as "explicitly allowed"; until then the validator requires equality (the safe side)
  and the step-4 doc records the outcome;
- `runtimeClass` present ⇒ equals `capabilities.runtimeClass()`;
- globally: `orphan-grace > provision-timeout`; `lost-confirm-after` positive.

`CredentialScope.overlaps`: schemes intersect ∧ methods intersect ∧ some host pair overlaps (equal; `*.s` vs a host
ending in `.s`; `*.a` vs `*.b` when one suffix ends with the other) ∧ some path pair overlaps (equal exact; prefix
`p*` vs exact starting with `p`; two prefixes where one starts with the other).

---

## 7. Departures and additions (to fold into WS in this branch)

| # | Departure | WS sections | Reason |
|---|---|---|---|
| D1 | `aimon-sandbox-opensandbox` calls REST (JDK `HttpClient` + Jackson), no SDK; diagram box `-> com.alibaba.opensandbox` replaced; module contents listed | §4.1, §21 bullet reworded | Spike §8. |
| D2 | Provider config gains `runtime` (required), `runtime-class.kind` (gVisor + `dns+nft` refused; WS §13.2 example changed to `kind: kata`), `create-timeout`, `use-server-proxy`, `entrypoint`, `default-resources`, `control-plane-probes`, `credentials` (definitions); `volume-reclaimer` becomes an injected instance in step 4 | §13.2 | §5.2: capability legality depends on the runtime; K8s create blocks and needs `resourceLimits`; the probe target is not always the JVM's endpoint. |
| D3 | `ProviderCapabilities` gains `runtimeClass`, `credentialScopes`, `controlPlaneEndpoints`; `SandboxProvider.verify`; `ProviderSandbox.createdAt` | §6.1 | §5.1. |
| D4 | Output line-normalization written into the SPI; the contract test changed; `SandboxShell`'s backstop allows for U+FFFD expansion (`3 × max + 1 KiB`) | §6.1, §16, §20 (item closed) | §5.3. |
| D5 | Credential checks: unknown binding, overlap, egress coverage — all at startup from `credentialScopes()` | §12.1, §13.2, §20 (closed) | §6.6. Egress coverage is new: the vault refuses uncovered hosts. |
| D6 | Seed network probe = bash `/dev/tcp` with a watchdog against `controlPlaneEndpoints()`; egress mode and vault bindings checked by `provider.verify` in the seed step | §11.3, §20 (closed) | §6.4. |
| D7 | Reconciliation grace measured from the sandbox's `createdAt` (node-local first sighting as fallback); DRIFT deferred to step 7; volume reconciliation to step 5; provider TERMINATED/FAILED for a RUNNING slot counts as missing | §10.4 | §6.5; nothing is paused before step 7. |
| D8 | The provider discards output beyond `maxCaptureBytes` but does not end the command | §6.1 (spike §3's sentence is not followed) | §3.2. |
| D9 | New `k8s` test tier (`k8sTest`), excluded from `test` and `integrationTest`; the docker tier's server runs as a Testcontainers container reached through the server proxy | §16 | Without the exclusion a `@Tag("k8s")` test would run in `build`. The JVM reaches execd only through the server proxy; the server container's own reachability of execd is settled by upstream's compose settings (§9.2). |
| D10 | Status header, §18-4 "구현되었다", §20 items closed with a pointer to this doc; the three release-gated §20 items (observability, SNAPSHOT pin, testkit publish) stay open | header, §18, §20 | TASK acceptance 6. |

---

## 8. Failure modes

### 8.1 HTTP classification (by status, never by code string — spike §2)

| Status / condition | Mapping |
|---|---|
| 404 on a sandbox-scoped call | `SandboxNotFoundException` (`destroy`: success; `status`: empty) |
| 404 on a files call | `FileNotFoundException` (the sandbox existing is established separately) |
| 400, 422 | `SandboxProviderException(PERMANENT)` with the server message (bad request: label rules, timeout > max, missing cwd) |
| 401, 403 | `PERMANENT` ("check api-key") |
| 409 | `TRANSIENT` (state conflict) |
| 429, 5xx, 504 (`POD_READY_TIMEOUT`), I/O, timeout | `TRANSIENT`; idempotent calls retried per `retry` |
| 500 "already exists" on `/files/mv` | `FileAlreadyExistsException` |

### 8.2 Scenarios

| Failure | Handling |
|---|---|
| Create response lost / RUNNING CAS lost / takeover race | Record keeps one ref; the other sandbox is DUPLICATE/ORPHAN, destroyed after `orphanGrace` (§6.5). |
| Sandbox stuck `Pending`, or `Failed` after create | `create` throws transient → slot FAILED(transient) with backoff; the leftover is FAILED-LEFTOVER. |
| Vault configuration fails | Same as above (transient or permanent by status). |
| Server declared `dns+nft` but runs `dns` | `verify` → FAILED(permanent, `egress`), sandbox destroyed; retried only after the profile changes. |
| Sandbox reaches the control plane | Seed probe → FAILED(permanent, `network-isolation`). |
| Operator declares isolation on the Docker runtime | `OpenSandboxProviderConfig.build()` refuses (nothing starts). |
| Operator declares `dns+nft` with a gVisor `runtime-class` | `OpenSandboxProviderConfig.build()` refuses (§5.2): the server would 400 every `networkPolicy`. |
| Docker server not in `network_mode = "bridge"` while `dns+nft` is declared | Not detectable by the provider; the first `create` with egress fails FAILED(permanent, `create`) with the server's 400 message; README operator contract states the requirement. |
| `network-isolation` not declared, profile does not waive it | Startup refusal (step-3 validator, unchanged) — WS §16 row. |
| Credential bindings overlap / unknown / uncovered by egress | Startup refusal listing each. |
| `extendExpiry` on a paused sandbox | `SandboxProviderException(PERMANENT, "paused")` — unreachable before step 7. |
| `extendExpiry` backwards or beyond max | Ignored / clamped client-side. Concurrent extends from two nodes may race (read-then-renew is not atomic); both write ≈ `now + terminateAfter`, so the loss is seconds. |
| execd endpoint moved (K8s) | One re-resolution after a connect failure. |
| Client disconnect mid-command | Status polling recovers the exit code; `kill`/await-timeout always `DELETE`. |
| Command prints more than the cap | Discarded, truncation flagged; the command keeps running to its own end or timeout (D8). |
| `list` misses a sandbox (paging shift, B §a) | Not LOST until `status` also misses it for `lostConfirmAfter`; dedupe by id. |
| Two janitors act on the same sandbox | Destroy is idempotent; record writes are CAS-guarded on generation + ref + state. |
| A janitor whose store is `InMemory` in a multi-node deployment | It would reclaim other nodes' sandboxes as ORPHAN. This is WS §5.3's "InMemory is single-node" rule, restated in the README and the `WorkspaceSandbox` javadoc; not detectable. |
| Server unreachable at a janitor cycle | Pass ends, logged; next cycle retries. Commands fail closed (WS §12.1). |
| Secrets | Read from suppliers per create, sent only to the vault, never logged; `CredentialDefinition.toString` redacts; the API key likewise. Sandbox ids are not logged at INFO (WS §13.3). |

---

## 9. Test strategy

### 9.1 Default tier (`./gradlew build`)

- **`OpenSandboxProviderTest`** against `FakeOpenSandboxServer` (JDK `HttpServer`, scripted per test; records requests):
  create body (timeout always, clamping, deny policy with `[]`, credentialProxy, resources default, no runtimeClass);
  create reuse by key and oldest-of-several; Pending→Running wait and timeout (no destroy); list paging with a single
  `metadata` param and dedupe; destroy 404 = success; renew forward-only/clamp/paused refusal/404; state mapping;
  error classification table §8.1; retry of idempotent calls only; semaphore not held by streams; SSE parsing
  (optional `data:`, `ping`, `"\n"` event, `error.evalue`, `-1`), cap per stream after UTF-8 encoding, OutputSink
  delivery, `DELETE` on kill and on await timeout, kill latched before `init`, status polling after a dropped stream;
  endpoint re-resolution and not-found mapping; files (multipart metadata file part, temp+`mv -f`, CREATE_NEW via
  `ln`, already-exists mapping, stat nanos, list depth, directory read guarded); vault request shape and secret
  redaction in logs/`toString`; `verify` (enforcementMode mismatch, defaultAction, missing binding).
- **`OpenSandboxProviderConfigTest`**: capability derivation table; docker-illegal declarations; credentials without
  `dns+nft`; `dns+nft` with `runtime-class.kind: gvisor` refused (and with `kata` accepted, advertising both
  `EGRESS_POLICY` and `RUNTIME_CLASS`); `runtime-class` name without `kind` refused; empty probe list with isolation
  declared; all violations reported together.
- **`CredentialScopeTest`**, **`SandboxStartupValidatorTest`** additions (§6.6).
- **`SandboxSeederTest`** additions: probe emitted only when required and not waived; targets quoted; a probe against
  a listening local `ServerSocket` fails the slot `network-isolation`, a closed port passes (local provider, which
  runs the real script).
- **`SandboxReconcilerTest`**: `classify` table row by row (pure); and scenario tests with `LocalProcessSandboxProvider`
  + `FaultInjectingSandboxProvider` + `ManualClock`, covering the stage-4 WS §16 rows that are manager logic:
  create response lost → one recorded, the rest DUPLICATE/ORPHAN; RUNNING CAS lost after create → reclaimed;
  FAILED(transient) retry → gen+1, leftover STALE; list omits once → not LOST; two janitors each miss it in one
  cycle → not LOST before `lostConfirmAfter`; scan-then-gen+1 by another manager → IN-FLIGHT, not STALE; two managers
  (different `nodeId`, caches) connect the same slot → one sandbox; slow-but-alive takeover → DUPLICATE reclaimed;
  node restart with a fresh `InMemory` store → new sandbox, old one ORPHAN after grace; record deleted → orphan
  destroyed; other `deployment` label → untouched; LOST between commands → next call gen+1 with notice and staging
  recopied; seed crash (`Fault.crash()` on the seed exec) → the next manager cleans `.tmp-*` and completes; two
  managers seeding one slot concurrently → both succeed (seed lock).
- **Contract suite** still passes on `LocalProcessSandboxProvider` after §5.3's change.
- **Network probe path end to end** (in `aimon-sandbox`'s own tests, package `at.aimon.sandbox.workspace`, since
  `SandboxSeeder` is package-private): the existing test `DelegatingProvider` wraps the local provider, advertises
  `NETWORK_ISOLATION` and returns a listening local `ServerSocket` (then a closed port) as
  `controlPlaneEndpoints()`; a `WorkspaceSandbox` with an unwaived profile goes FAILED(permanent,
  `network-isolation`) resp. seeds normally. No docker-tier probe test: the probe runs only when `NETWORK_ISOLATION`
  is required and unwaived, which needs it advertised, which `runtime: docker` forbids (§5.2). The real probe is
  exercised by the k8s tier.

### 9.2 Docker tier (`./gradlew integrationTest`, `@Tag("docker")`)

`OpenSandboxServerExtension` (JUnit extension, per test class): Testcontainers `GenericContainer` of the pinned
OpenSandbox server image (Q1) with `/var/run/docker.sock` bound, a generated `config.toml` (runtime docker,
`execd_image` pinned, `max_sandbox_timeout_seconds = 7200` — `extendExpiryMovesForwardOnly` pushes to `now + 1h10m`,
so the provider's `max-expiry` must be ≥ that, sqlite in the container), health-polled
on `/health`; the provider uses `use-server-proxy: true` so the JVM reaches execd only through the server container.
`OPENSANDBOX_TEST_ENDPOINT`/`OPENSANDBOX_TEST_API_KEY` override it with an external server.

The server-in-a-container networking follows upstream's reference compose (A §Q13,
`server/docker-compose.example.yaml`): `[docker] host_ip = "host.docker.internal"`, the container started with
`extra_hosts host.docker.internal:host-gateway` (Testcontainers `withExtraHost`), `[proxy] resolve_internal = false`,
`network_mode = "bridge"`. Sandboxes publish execd on the host (all interfaces — `publish_host` is not restricted,
because a loopback-only bind would be unreachable from the server's own network namespace through the host gateway on
Linux); the server reaches them at `host.docker.internal:{port}`; the JVM reaches only the server (its mapped port)
and goes to execd through the server proxy. The proxy therefore does not remove the reachability question, it moves it
into the server container, where the compose settings answer it. Fallback if that fails on a host: run the server
container on the default bridge with `resolve_internal = true` (it reaches sandbox containers by bridge IP). The build
agent records which combination worked on macOS/Docker Desktop in the step-4 doc; Linux stays unverified until CI
exists (Q10). Execd ports open on the test host's interfaces for the duration of a run — acceptable for a
developer/CI machine, stated in the README. Every run uses deployment
`ci-{uuid}` and deletes whatever that label still lists afterwards. The sandbox image is built from
`src/test/docker/Dockerfile` (debian-slim + bash, coreutils, util-linux, git, ripgrep).

- `OpenSandboxProviderContractTest extends SandboxProviderContract` (the full suite).
- `OpenSandboxWorkspaceIT`: `WorkspaceSandbox` end to end (`Write` then `Bash cat`, `cd`/`export` persistence, timeout
  kill) with `insecure-allow: [NETWORK_ISOLATION, HARDENED_SECURITY_CONTEXT]`; workspace id `ws:{uuid}` accepted
  (label encoding); root image without the waiver → FAILED(permanent, `root`), not recreated; reconciliation on the
  real server (orphan destroyed after a short `orphanGrace`); resource limits (`pids` bounded by the server's `pids_limit`, memory OOM stays inside the
  sandbox, a second sandbox still answers).
- `OpenSandboxEgressIT` (needs `opensandbox/egress` and `dns+nft`; a second server config): deny-all blocks a
  domain, `1.1.1.1` by IP, `169.254.169.254` (WS §12.1/§16 row; on a host without a metadata service the check is
  "no HTTP answer", same as any blocked IP), `dig @8.8.8.8`, the server by host IP; `verify` passes; with the server switched to
  `dns` while the provider declares `dns+nft` → FAILED(`egress`). Assumption-gated on the egress image being
  pullable.

### 9.3 K8s tier (`./gradlew k8sTest`, `@Tag("k8s")`, manual/pre-release)

Against a pre-provisioned kind + OpenSandbox K8s runtime (`OPENSANDBOX_K8S_ENDPOINT`, `…_API_KEY`; skipped when
unset; README documents the spike's C §1 procedure and `spike/opensandbox/k8s/deploy/*` as the reference setup):
labels on CR (list by label), hardened template observed in-sandbox (`id -u` = 1000, `NoNewPrivs: 1`, `CapEff` 0, no SA
token) and the seed passing without waivers; runtime class via `kubectl get pod -o jsonpath` when `kubectl` is on
`PATH`; east-west blocked (peer execd), control plane blocked (seed probe passes), egress rows; credential scope (a
push-shaped request outside the bound path gets no token) — gated on internet access and Q4/Q5. The test JVM reaches
the server through `port-forward` + `use-server-proxy`.

### 9.4 What is reported

The build agent runs `./gradlew build` and, since Docker is present, `./gradlew integrationTest` and reports the
actual result per class (including any assumption skips — the egress IT may skip). `k8sTest` is not run in this
pipeline; the report says so.

---

## 10. Decisions on the WS §20 items step 4 owns

- **Output bytes** — line-normalization documented in the SPI; the contract test uses newline-terminated output
  (§5.3). The byte-exact file path stays available if a consumer ever needs it.
- **Credential overlap check** — scopes exposed as data (`ProviderCapabilities.credentialScopes()`), checked at
  startup in `SandboxStartupValidator` together with unknown names and egress coverage (§6.6). The step-3 refusal of
  `credentials` is lifted.
- **Seed network check + `enforcementMode`** — the provider publishes probe targets
  (`controlPlaneEndpoints()`); the seed probes from inside the sandbox; the provider verifies the egress mode (and
  vault bindings) through `SandboxProvider.verify` in the same seed step (§6.4).

---

## 11. Suggested commit sequence

1. build: `k8s` tier and exclusions in the conventions plugin; catalog `testcontainers`; module skeleton + README stub.
2. SPI additions (§5.1) + testkit updates (contract output change, `deployment()`, `createdAt`, fault passthrough).
3. settings + validator (credentials, runtime class, grace) + `CredentialScope`.
4. seeder probe + manager `verify` call.
5. reconciler + janitor wiring + manager helpers; default-tier scenario tests.
6. provider: config, lifecycle client, execd client + SSE, files, vault, errors, retry; fake-server tests; coverage floor.
7. docker tier: server extension, contract test, workspace/egress ITs; k8s tier tests.
8. docs: `workspace-sandbox-step4.md`, WS edits D1–D10, CHANGELOG, READMEs.
   The WS folding is large (§4.1, §6.1, §6.4, §10.4, §11.3, §12.1, §13.2, §16, §18, §20, §21): build review checks
   WS against the D1–D10 table row by row, not only the new document.

---

## 12. Open questions (not resolved from the task statement)

Each has the default the build agent should take unless told otherwise.

- **Q1 — which OpenSandbox server image and tags the docker tier pins.** The spike ran the Docker runtime from source
  `3738975` with `execd:v1.1.0`/`egress:v1.1.7`, and the K8s runtime on `release-1.1.1-rc.1`; spike §9 says the step-4
  release pins a formal tag. *Default:* the newest `opensandbox/server` tag whose Docker runtime passes the contract
  suite locally, pinned by digest in one Gradle property, with `execd:v1.1.0`/`egress:v1.1.7` by digest; record the
  choice in the step-4 doc. If no server image works with the Docker runtime from inside a container, fall back to
  the external-endpoint override and say so.
- **Q2 — §14 metrics/spans for the 3+4 release** (WS §20: "3·4단계 릴리스 전에 닫는다"). *Default:* not implemented in
  step 4 (no consumer; Micrometer dependency); the WS item stays open as a release decision for the user, not closed
  silently.
- **Q3 — built-in `VolumeReclaimer` (Docker volume API / K8s PVC) in step 4 or 5.** `volume-reclaimer` is listed among
  the step-4 keys, but nothing in step 4 can use shared volumes (`sharedAccess ≠ none` is still refused). *Default:*
  step 4 defines the interface and accepts a supplied instance (advertising `SHARED_VOLUME` then); built-in
  implementations and volume reconciliation arrive with step 5.
- **Q4 — does the sandbox trust the egress MITM CA?** The spike's vault probe disabled TLS verification (spike §6).
  If the sandbox does not trust it, HTTPS credential injection breaks tools like `git`; the fix is an image-contract
  or env addition (CA bundle), which is a live-test question. *Default:* the k8s tier checks it with verification on;
  until then `CREDENTIAL_INJECTION` is advertised per config but the README flags the unknown.
- **Q5 — how the client authenticates to the egress sidecar's vault API.** The token lives in the sidecar env
  (A §Q8); the spike wrote the vault through the Python SDK and the server proxy. *Default:* resolve port 18080
  through the endpoints API and send the `headers` it returns, verified against the Python SDK source at
  implementation. If the server hands out no credential for it, `CREDENTIAL_INJECTION` cannot be advertised and
  `credentials` without it is a config error — the build agent reports this rather than working around it.
- **Q6 — default probe targets.** *Default:* the endpoint's host:port, plus `kubernetes.default.svc:443` on the
  kubernetes runtime; operators override with `control-plane-probes`.
- **Q7 — is `entrypoint` required by `POST /sandboxes`, and is `tail -f /dev/null` right for the runtime image?**
  *Default:* always send the configured entrypoint (default `[tail, -f, /dev/null]`, the server's own snapshot
  default, A §Q11).
- **Q8 — profile `disk` and `pids` have no per-request OpenSandbox field.** *Default:* not sent; the provider logs one
  WARN per profile at first create that they are enforced only by server config (Docker `pids_limit`), and the README
  says so. Refusing such profiles would break the WS §13.1 example profile.
- **Q9 — the k8s tier provisions its own kind cluster or expects one.** *Default:* expects one (3–4 minutes of setup
  and helm overrides do not belong in a test class); README gives the procedure.
- **Q10 — add a CI workflow now?** WS §16 expects "4단계의 첫 CI" to confirm amd64 execd and `dns+nft` on a runner, but the
  repository has no workflow and this pipeline pushes nothing. *Default:* no workflow in this branch; the step-4 doc
  records that the runner confirmation is still owed.
- **Q11 — provider reports TERMINATED/FAILED for a RUNNING slot.** WS §10.4 does not say. *Default:* treat as missing
  (LOST confirmed by time), since such a sandbox cannot serve; recorded as part of D7.

---

## 13. Implementation departures

Where the code differs from the body above, and why. §4.5 calls this section "§12"; §12 was already taken by the open
questions, so it is §13. Everything here is also in the task's `build/deviations.md`. The WS edits follow D1–D10 row
by row (§4.1, §6.1, §6.4, §10.4, §11.3, §12.1, §13.2, §13.3, §16, §18, §20, §21, the status header and the related
documents list).

### Measured on the real server (settles §6.6, Q1, Q5 and review-2's first note)

All of this was measured against `opensandbox/server:release-1.1.0` running as a container on Docker Desktop 29.2.1
(macOS arm64), with `execd:v1.1.0` and `egress:v1.1.7`.

- **Q1 — the docker tier's server.** It is `opensandbox/server:release-1.1.0`, the newest release tag, pinned by
  digest (`sha256:68ca0212…`). `execd_image` and `[egress].image` are pinned as `name:tag@digest`, and the server
  accepts that form. The pins live as constants in `OpenSandboxTestServer`, overridable through environment variables
  (`OPENSANDBOX_TEST_SERVER_IMAGE`, `…_EXECD_IMAGE`, `…_EGRESS_IMAGE`, `…_SANDBOX_IMAGE`). §9.2 put them in one Gradle
  property. A constant next to the only code that reads it was simpler, and the override covers the same need.
- **The server-in-a-container combination** that works on macOS is the upstream compose one from §9.2:
  `[docker] host_ip = "host.docker.internal"`, the container's extra host `host.docker.internal:host-gateway`,
  `[proxy] resolve_internal = false`, and `network_mode = "bridge"`. The fallback was not needed. Linux is still
  unverified (Q10).
- **The server-proxy endpoint** carries the authority of the request's `Host` header (`localhost:{mapped port}`), not
  one from server config, so it would have worked as returned. The provider still follows review-2's suggestion: under
  `use-server-proxy` it keeps only the returned path and resolves it against the configured endpoint. That costs
  nothing and survives NAT or a port-forward whose `Host` differs.
- **Q5 — vault authentication.** `GET /endpoints/18080` returns
  `{"endpoint": …, "headers": {"OPENSANDBOX-EGRESS-AUTH": "<token>"}}`. The vault client sends those headers, as §5.4
  assumed. `POST /credential-vault` answers 201, and `GET` returns the bindings without secrets.
- **§6.6 — egress coverage of binding hosts.** Measured against the vault:
  - an egress entry `*.example.com` covers a binding host `a.example.com`, and a binding host `*.example.com`;
  - it does not cover `example.com` (400 "not allowed by egress policy");
  - an unrelated host is refused.

  `SandboxStartupValidator.egressCovers` therefore accepts an equal entry, or `*.s` for a non-wildcard host under
  `s`. A wildcard binding host needs an equal egress entry. `*.a.s` under `*.s` was not measured, so it is refused
  (the safe side).
- **§5.3 — normalization of a lone `\r`.** It becomes a line break: `x\ry` arrives as `x`, `y`. `\r\n` is one
  break, and an invalid byte is U+FFFD. `OpenSandboxWorkspaceIT.outputIsLineNormalized` asserts all three. The SPI
  wording "dropped or turned into a line break" stands.
- **New: the egress sidecar starts after the sandbox.** For a moment after `Running`, both `GET /networkpolicy` and
  the vault answer 500 "Server disconnected" through the proxy. `create` therefore waits, within `create-timeout`, for
  `GET /networkpolicy` to succeed whenever the spec has egress. It then writes the vault. The seed's `verify` never
  meets an unready sidecar. The design did not foresee this. The first egress IT run failed on it.

### Provider

- **`verify` for credentials needs the binding names, and `verify` receives only a ref.** The provider adds its own
  label `aimon.at/credentials = h(sorted binding names)` at create. `verify` compares it with the names the vault
  holds. This works on any node without the profile. The label is not one of `SandboxLabels.VERIFIED`, so the
  manager's comparison ignores it. As review-2 asked, the vault receives only the bindings named in
  `spec.credentials()`, never every configured definition.
- **404 through the server proxy.** Through the proxy, a gone sandbox and a missing file are both 404. The design
  (§8.1) said the sandbox's existence is "established separately". `ExecdClient.notFound` does that with a lifecycle
  `GET`. There is one shortcut: a 404 whose body code is execd's own `FILE_NOT_FOUND` (no runtime prefix) is taken as
  a missing file without the second call. This is the one place a code string is read. It only saves a call; any
  other 404 falls back to asking the lifecycle server.
- **Exit codes.** A signal (`evalue` `-1`) becomes `128 + n`, with `n` taken from the traceback's signal name
  (`terminated` 143, `killed` 137, …; unknown names map to 137). An exec failure whose `evalue` is not a number
  (argument list too long) becomes 126.
- **Files.** Several behaviours were settled in the code; the local provider's contract pins them:
  - `write` buffers the content in memory to build the multipart body. §6.3 did not say whether to stream;
    streaming would have to deal with the one re-resolution retry.
  - `CREATE_OR_REPLACE` onto an existing directory is refused. Otherwise `mv` would move the file into it.
  - `move(overwrite)` runs `mkdir -p` on the target's parent, then `mv -f -T`.
  - A non-recursive `delete` of a directory lists it first to refuse a non-empty one.
  - A recursive `list` uses `depth=64`, because execd rejects `-1`.
  - A 500 from a files call becomes a `VirtualFileSystemException`. Other 5xx stay transient provider errors.
- **Defence in depth in `create`.** Besides the runtime-class check of §5.4, `create` refuses (PERMANENT) an egress
  request without `EGRESS_POLICY` and credentials without `CREDENTIAL_INJECTION`. Startup validation already refuses
  both. It logs one WARN per distinct `ResourceSpec` carrying `disk` or `pids` (Q8).
- **`VolumeReclaimer` extends `SharedVolumes`.** §4.3 had `list(prefix)` and `delete(name)`. Making the reclaimer the
  `SharedVolumes` it becomes needs no adapter, and the prefix/label question is step 5's (Q3).
- **Threads.** Stream readers run on an unbounded cached pool of daemon threads, `opensandbox-exec-N`. §6.2 said
  "bounded cached pool". A bound would refuse the N+1st concurrent command, and each command is already bounded by its
  own timeout.
- **`OpenSandboxProviderConfig.build()`** logs a WARN when `network-isolation` is declared on Kubernetes with the
  derived `control-plane-probes` (review-2's last note). The README says the same.

### Manager side

- **The seeder's probe targets** come from `provider.capabilities().controlPlaneEndpoints()`, read by the manager's
  constructor. §4.2 routed them through `WorkspaceSandbox` and the manager builder. Every manager already has its
  provider, so the builder would only have carried a second copy.
- **The seed probe's own output goes to `/dev/null`.** Review-2 noted that bash prints a job notice when the watchdog
  kills a connect. The function body is wrapped as `{ … } >/dev/null 2>&1`. This was measured under macOS bash 3.2:
  no stderr, and 3 s for an unroutable address.
- **`SandboxShell`'s backstop.** No construction-time check existed to change (review-2). The backstop is
  `SandboxShell.captureBackstop(max)`, which is `3 × max + 1 KiB` and saturates at `Long.MAX_VALUE` instead of
  overflowing.
- **The runtime-class rule** (§6.6, "`runtimeClass` present ⇒ equals `capabilities.runtimeClass()`") accepts one more
  case: a provider that declares no class, with `RUNTIME_CLASS` waived by `insecure-allow`. This is local development.
  Without it, a profile shared with production could not run on the local provider. The provider refuses a mismatch
  only when it declares a class itself.
- **Reconciliation.** It follows §6.5, with these settled details:
  - An `IN_FLIGHT` sandbox escalates to `ORPHAN` after the grace only when its generation is ahead of the record's.
    A sandbox of a `PROVISIONING` generation never escalates: the takeover and the abandoned-claim reclaim own that
    slot.
  - `reconcile` is `synchronized` per janitor, so a direct call and the scheduled pass never interleave the
    node-local first-seen map.
  - `SandboxWorkspaceManager` gained `markMissing`, `clearMissing`, `confirmLost` and `provider()`. §6.5 named a
    `destroyIfStill`; the reconciler does that itself with `store.find` plus re-classify plus `destroyQuietly`.
- **Review-2's `labels`-SKIP note.** The residual risk (after gen+1 such a sandbox classifies STALE) is stated in WS
  §10.4 rather than guarded. Only this deployment's `managed` sandboxes are listed.

### Tests

- **The docker tier's server** is started once per JVM and egress mode and shared by the test classes of a run.
  §9.2 said per test class. A shutdown hook deletes what the run's `ci-{uuid}` deployment still lists and stops the
  container. The `dns`-mode server (for `verify`'s negative case) is a second container, started only by the egress
  IT.
- **Rows dropped from `OpenSandboxWorkspaceIT`** because the Docker runtime cannot reach them:
  - "root image without the waiver → FAILED(root)". A root image needs `HARDENED_SECURITY_CONTEXT` waived to pass
    startup, and the waived seed skips the check. The step-3 seeder tests and the k8s tier cover it, as §9.1 already
    reasoned for the network probe.
  - The pids row checks `/sys/fs/cgroup/pids.max` = 4096 (the server's `pids_limit`) instead of exhausting it. The
    memory row runs an out-of-memory command under a 256 Mi limit (exit 137), then checks that the sandbox and a
    neighbour still answer.
- **`OpenSandboxEgressIT`** is gated on an unrestricted sandbox reaching the internet: without that control, "blocked"
  proves nothing. The "server by host IP" row is not there. A deny-all sandbox cannot resolve the host, and the host's
  IP is runtime-specific. The egress rows that did run (domain, `1.1.1.1`, `169.254.169.254`, `dig @8.8.8.8`) are
  enough for WS §16's "보안: egress" row on Docker. The vault test checks the vault and that the secret is absent from
  the sandbox's environment. Injection itself needs an echoing HTTPS endpoint and the CA question (Q4), so it is in
  the k8s tier.
- **The k8s tier (`OpenSandboxK8sIT`)** was written but not run: this pipeline has no cluster. Every test skips
  without `OPENSANDBOX_K8S_ENDPOINT`. It covers:
  - labels on the list;
  - the hardened template (uid, NoNewPrivs, CapEff, no SA token);
  - a waiver-free profile seeding through `WorkspaceSandbox`, which also runs the network probe;
  - east-west to a peer's execd;
  - the runtime class through `kubectl`;
  - `dns+nft` through `verify`;
  - credential scope with TLS verification on (Q4, gated on `OPENSANDBOX_K8S_INTERNET=1`).
- **The multi-node rows** (two managers connecting one slot, a seed crash, two concurrent seeds) are in a class of
  their own, `MultiNodeScenariosTest`, not in `SandboxReconcilerTest` as §9.1 listed them: they exercise `connect` and
  the seed lock, not reconciliation.
- **Coverage floors**, measured from the default tier alone: `aimon-sandbox` 90.85% (floor raised 89 → 90),
  `aimon-sandbox-testkit` 89.17% (floor kept at 88), `aimon-sandbox-opensandbox` 86.92% (floor 86).

### Where the WS §16 rows of stage 4 run

| Row | Test |
|---|---|
| `create` response lost; RUNNING CAS lost; FAILED(transient) retry → STALE | `SandboxReconcilerTest` (lost response → FAILED-LEFTOVER; stale generation; duplicate) |
| janitor: `list` misses once; two janitors each miss it | `SandboxReconcilerTest.aSandboxListedOnceWithoutItIsNotLost`, `…MissingFromOneListingOrListedTerminated…` |
| janitor: scan then gen+1 by another node → IN-FLIGHT | `SandboxReconcilerTest.aNewerGenerationThanTheScannedRecordIsInFlightUntilTheGraceEnds` |
| two nodes `connect` one slot; slow-but-alive takeover | `MultiNodeScenariosTest.twoNodesConnectingOneSlotShareOneSandbox` + `SandboxReconcilerTest.aDuplicateFromATakeoverRaceIsDestroyedAfterTheGrace` |
| node restart with `InMemory`; record deleted | `SandboxReconcilerTest` (both) and, on the real server, `OpenSandboxWorkspaceIT.reconciliationReclaimsTheOrphanOfARestartedNode` |
| other `deployment` label | `SandboxReconcilerTest.sandboxesWithUnparseableLabelsOrOfAnotherDeploymentAreUntouched`, contract `labelsOfOtherDeploymentsAreNotListed` |
| `ws:{sessionId}` labels accepted | contract suite + `OpenSandboxWorkspaceIT` on the real server |
| LOST between commands → gen+1 with notice | `SandboxReconcilerTest.aMissingSandboxIsLostOnlyAfterLostConfirmAfterAndTheNextCallRecreatesIt` |
| seed crash; two nodes seeding one slot | `MultiNodeScenariosTest` (a crash during the seed completed by the other node, `.tmp-*` removed; two concurrent seeds both succeed) |
| resource limits | `OpenSandboxWorkspaceIT.resourceLimitsHoldInsideTheSandbox` |
| security: host / egress / east-west / credential scope | `OpenSandboxEgressIT` (egress on Docker); `OpenSandboxK8sIT` (hardening, east-west, credential scope — not run) |
| root image → FAILED(permanent) | step-3 `SandboxSeederTest`; `OpenSandboxK8sIT` (not run) — unreachable on Docker |
| `network-isolation` undeclared → startup refusal | `SandboxStartupValidatorTest.requiredCapabilitiesMustBeAdvertised` |
| control plane reachable → FAILED(`network-isolation`) | `SeedVerificationTest`, `SandboxSeederTest` (local `ServerSocket`) |
| declared `dns+nft`, server `dns` → FAILED(`egress`) | `SeedVerificationTest` (manager), `OpenSandboxEgressIT.verifyNoticesAServerThatOnlyFiltersDns` (real server) |
