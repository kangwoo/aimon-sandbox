# Changelog

All notable aimon-sandbox changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed — follows aimon-core 0.3.1 (shell cancellation, background ceiling, runtime bindings)

The sandbox now meets the three rows aimon-core 0.3.1 added to its provider contract (core `61604b4`, PRs #204–#208);
the design and where the implementation departed from it is
[`docs/design/workspace-sandbox-core-031.md`](docs/design/workspace-sandbox-core-031.md).

- **`KillShell` stops sandbox background commands.** `SandboxShell` declares `ShellFeature.CANCELLATION` and honours
  `ExecutionOptions.getCancellation()` for foreground and background commands: a tripped signal kills the command
  through the provider's existing `RunningCommand.kill()`, and `execute` throws `ShellCancelledException` with the
  output written so far. A signal that is already tripped starts nothing and provisions nothing. The kill reaches the
  exec's process group; a job the command moved out of it (`setsid`, `set -m`) survives until the sandbox goes. No
  provider SPI change.
- **An interrupt after a cancel is reported as cancelled.** When the waiting thread is interrupted after the signal
  tripped — core's stack shutdown cancels, then interrupts five seconds later — `execute` kills the command again and
  throws `ShellCancelledException` with the output so far (the interrupt flag stays set), so core settles the
  background task as `KILLED`, not `FAILED`. An interrupt without a cancel is still "interrupted and killed". A
  provider whose `kill()` throws no longer makes `execute` throw that exception before the command ended; the partial
  output of a timeout or a cancellation is flagged truncated when it was cut at the capture cap.
- **Background commands have a stated ceiling, 24 hours by default** — the length they could already run. Profiles
  gain `background-command-timeout` (`SandboxProfile.backgroundCommandTimeout`, default
  `SandboxProfile.DEFAULT_BACKGROUND_COMMAND_TIMEOUT`), which the environment returns as core's
  `backgroundCommandTimeout()`, so core tells the model when the command will be stopped. It is independent of
  `background-heartbeat-limit` (1h): set the two equal to end a command when it stops being allowed to keep its
  sandbox awake. An explicit value must be positive.
- The new field is part of `SandboxProfile.contentHash()`, so every profile's hash changes with this version and a
  slot that failed permanently is retried once after the upgrade.
- **`bindRuntime` is not overridden.** The provider keeps nothing per `AgentRuntime`; the inherited
  `RuntimeBinding.NONE` meets the contract, and tests pin it (closing a binding stops no command; `resolve` answers
  for an id nobody bound). Close the core stack before `WorkspaceSandbox`.
- **Skill-declared shell hooks are no longer refused.** aimon-core 0.3.1 runs them in the execution's sandbox shell,
  not on the host, so `WorkspaceSandbox.markdownSkillParser()` and `skillHookSetParser()` are removed (they were never
  released); a host that wants no skill-declared shell code builds core's `SkillHookSetParser` with
  `NoOpShellActionExecutor`. A `preTool` shell guard blocks its tool call and an `onStart` shell guard keeps its skill
  fork from starting when the sandbox is unavailable; hooks that only observe should set `failOpen: true`. New
  end-to-end tests run a skill's `preTool` shell guard through a real `OrcaAgentExecutor`: it runs in the sandbox, its
  exit 2 blocks the tool, and it blocks when the sandbox is unavailable unless it declares `failOpen`.
- **`OpenSandboxProvider.extendExpiry` rounds the target up to a whole microsecond.** The server keeps microseconds,
  so on a nanosecond clock (Linux) the stored expiry fell just short of the one asked for; the first CI run of the
  docker tier caught it in the provider contract suite.
- **The k8s tier ran for the first time**, on kind with the hardened container-level template, the isolation
  NetworkPolicy and a runc-backed RuntimeClass: all eight checks pass. `scripts/k8s-tier.sh up|test|down` provisions
  that cluster and runs the tier. It found two defects, both fixed:
  - **git over HTTPS failed where a credential is injected.** execd hands the egress proxy's CA bundle to commands
    as `SSL_CERT_FILE`, which Debian's git (GnuTLS) ignores. The shell wrapper now sets `GIT_SSL_CAINFO` to that bundle
    when `SSL_CERT_FILE` is set and `GIT_SSL_CAINFO` is not.
  - **The first command after a create could get HTTP 502** (about one create in five, with egress): execd starts only
    after the egress CA is ready. `OpenSandboxProvider.create` now waits until execd answers `/ping`, within
    `create-timeout`.
- CI: `.github/workflows/build.yml` runs `checkAll` and the docker tier (`integrationTest`) on Ubuntu.
- README gains a "Wiring" section (`ExecutionEnvironmentSpec.shared`, close order, the notes above).
- Tests compile against core's `UserLocale` (was `Environment`).

### Added — workspace sandbox, implementation step 4

The production provider and sandbox reconciliation of
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md) §18-4; how it was built and where it departed
from the plan is [`docs/design/workspace-sandbox-step4.md`](docs/design/workspace-sandbox-step4.md). Released together
with step 3.

- **`aimon-sandbox-opensandbox`** is a new, published module: `OpenSandboxProvider` calls the OpenSandbox REST API
  directly (JDK `HttpClient` + Jackson, no SDK). Its capabilities are derived from operator declarations, never
  probed (`egress-enforcement`, `network-isolation`, `hardened-security-context`, `runtime-class`, `credentials`,
  `volume-reclaimer`), and `OpenSandboxProviderConfig` refuses declarations the runtime cannot honour. It enforces
  what the server does not: `timeout` is always sent; expiry moves forward only, capped at `max-expiry`, and never on
  a paused sandbox; kill and await-timeout always send `DELETE /command`; file writes go through a temporary name
  plus `mv`/`ln`; a 404 on destroy is success. Errors are classified by HTTP status, and the execd endpoint is
  re-resolved once after a connect failure. Credential bindings are written to the egress sidecar's vault, only the
  ones a profile names.
- **Sandbox reconciliation** (`SandboxReconciler`, run by `SandboxJanitor.runOnce()`): ORPHAN, STALE,
  FAILED-LEFTOVER and DUPLICATE sandboxes of the deployment are destroyed, and missing ones are confirmed LOST after
  `lost-confirm-after`. The grace is measured from the sandbox's creation time, and every destroy re-reads the record
  first. New settings: `orphan-grace` (10m, must exceed `provision-timeout`) and `lost-confirm-after` (90s).
- **SPI additions**, all additive:
  - `ProviderCapabilities` gains `runtimeClass()`, `credentialScopes()` and `controlPlaneEndpoints()`, plus a
    builder;
  - `SandboxProvider.verify(ref, required)`;
  - `ProviderSandbox.createdAt()`;
  - `CredentialScope`, `HostPort` and `VerificationFailure`.
- **Output may be line-normalized**, and the SPI now says so. The contract suite's truncation test uses
  newline-terminated output. `SandboxShell` sizes its provider-side capture backstop for U+FFFD expansion
  (`3 × max + 1 KiB`).
- **The seed checks more.** `provider.verify` runs before the seed script: the egress `enforcementMode` and the
  vault's bindings. When `NETWORK_ISOLATION` is required and not waived, the seed probes the provider's control-plane
  endpoints.
- **Credential bindings are allowed** in profiles. Startup validation checks:
  - that every named binding exists;
  - that no two bindings of a profile overlap;
  - that `egress` explicitly allows every binding host (`credentials` without `egress` is refused);
  - that a profile's `runtime-class` equals the provider's.
- **Test tiers.** `integrationTest` (`@Tag("docker")`) now has a subject: the contract suite, a `WorkspaceSandbox`
  end-to-end IT and an egress IT, run against an OpenSandbox server that Testcontainers starts per run. A new
  `k8sTest` tier (`@Tag("k8s")`) is manual, runs against a provisioned cluster, and is excluded from `test` and
  `integrationTest`. The testkit's contract suite gains an overridable `deployment()` and a `createdAt` check, and
  its fault injector gains `Operation.VERIFY`.
- `testcontainers` 2.0.5 joins the catalog (test only). The coverage floors are now `aimon-sandbox` 90 and
  `aimon-sandbox-opensandbox` 86.

### Added — workspace sandbox, implementation step 3

The domain and the local path of [`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md) §18-3;
how it was built and where it departed from the plan is
[`docs/design/workspace-sandbox-step3.md`](docs/design/workspace-sandbox-step3.md). **Not usable in production
yet** at step 3 alone: its only provider was the test-only `LocalProcessSandboxProvider`. The OpenSandbox provider is
step 4 (above), and steps 3 and 4 are released together.

- **`aimon-sandbox`** now has code: workspace records with a version-CAS store (`InMemorySandboxWorkspaceStore`,
  single node only) and the `SandboxWorkspaceManager` (lazy provisioning, owner checks at every entry point,
  close/reopen with tombstones, CAS retry), the `SandboxProvider` SPI and its labels, the default binding policy
  with tenant and session-owner resolution, and `SandboxExecutionEnvironmentProvider` — aimon-core's
  `ExecutionEnvironmentProvider` answered with a declared descriptor, read-only staging and shell areas, shell state
  (cwd, exported variables) kept in files inside the sandbox, staging verified inside the sandbox, and `rg --json`
  search. Around it: the activity heartbeat, a janitor that enforces idle policy, a default per-tenant admission,
  refusal of skill shell-action hooks, and startup validation, all assembled by `WorkspaceSandbox`.
- **`aimon-sandbox-testkit`** is a new, published module: the provider and store contract suites, the
  local-process provider, fault injection, a manual clock and scheduler.
- **Refused at startup, not ignored**: profiles with `pause-after` (step 7), `shared-access` other than `none`
  (step 5) or a `seed` (step 5). Step 3 also refused `credentials`; step 4 (above) lifted that refusal. Only the
  `primary` slot is served (step 5).
- **aimon-core is pinned to the released `0.3.1`** from Maven Central. While it was unreleased the pin was
  `0.3.1-SNAPSHOT`, resolved through a `mavenLocal()` filtered to the `at.aimon.core` group and to snapshots;
  that block is gone with it, so nothing in this build resolves from `~/.m2` any more.
- `slf4j-api` and `jackson-databind` join the catalog at aimon-core's own runtime versions: aimon-core's published
  API exports no dependencies, so what this code compiles against is declared here.

### Removed — the identifier-based sandbox

Everything below "Split out of aimon-core" is superseded by
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md), which replaces it without a
compatibility layer (§17 maps old to new). Nothing from it has been released under `at.aimon.sandbox`.

- **`aimon-sandbox-docker` and `aimon-sandbox-kubernetes` are gone.** The only backend in the new design is
  OpenSandbox, which covers both runtimes (§6.4); `aimon-sandbox-opensandbox` arrived with step 4 (above).
- **`aimon-sandbox` was emptied** (step 3, above, fills it again). The four tools (`RunSandbox`, `CopyToSandbox`, `RestartSandbox`,
  `DeleteSandbox`), `SandboxBackend`, `SandboxConfig`, `RunStore`/`RunManager`, `SandboxExpiryStore`,
  `SandboxLock`, `ReaperService`, the tar transfer classes and `IdentifierValidator` were all deleted; each
  has a replacement or an explicit "none" in §17, and none is reused as-is. The module and its coordinate
  stay because the new design builds there (§4.1). It now declares aimon-core on `api`, as §4.1 requires.
- **Build cleanup.** The Docker, Kubernetes and slf4j catalog entries (slf4j returned with step 3, above), the three coverage floors and the
  comments about the two gated integration-test classes went with the code, and so did
  its design document, `docs/design/sandbox.md` (last version at commit `704013c`).
  `workspace-sandbox.md` is now ACCEPTED.
- **The known gap below closes by removal**, not by being fixed: the classes without a CI signal no longer
  exist. The new design's first `@Tag("docker")` tier is the OpenSandbox provider contract suite (§16).

### Split out of aimon-core

The three sandbox modules moved here from
[aimon-core](https://github.com/kangwoo/aimon-core), where they were published through 0.2.4.

- **No source change.** Every `.java` file under `modules/` arrived byte for byte. The package stays
  `at.aimon.sandbox.*`, the SPI keeps every signature, and the four tools keep their names, their input
  schemas and their `ToolContext` keys. A build that swaps the coordinate and nothing else compiles.
- **The group id changed**, and with it the version counter. `at.aimon.core:aimon-sandbox{,-docker,
  -kubernetes}:0.2.4` stay on Maven Central and keep working — nothing was withdrawn or relocated — but
  they receive no further releases. New coordinates start at `0.1.0`, because claiming `0.2.5` would
  assert a release history this group id does not have.

  | Through 0.2.4 | From now |
  |---|---|
  | `at.aimon.core:aimon-sandbox` | `at.aimon.sandbox:aimon-sandbox` |
  | `at.aimon.core:aimon-sandbox-docker` | `at.aimon.sandbox:aimon-sandbox-docker` |
  | `at.aimon.core:aimon-sandbox-kubernetes` | `at.aimon.sandbox:aimon-sandbox-kubernetes` |

- **aimon-core is now a Maven dependency**, pinned in `gradle/libs.versions.toml` at the released
  `at.aimon.core:aimon-core:0.2.4` instead of `project(":aimon-core")`. Only `aimon-sandbox` declares it:
  the two backends import no aimon-core type at all, which is why the split was mechanical.
- **No BOM.** aimon-core and aimon-memory each publish one; three coordinates, two of which already carry
  the third on `api`, do not earn a fourth. `settings.gradle.kts` says when to add one.
- **`archunit` left behind.** All three modules declared `testImplementation(libs.archunit.junit5)` and no
  test used it. The declaration is gone rather than ported.
- **Test dependencies lost Spring.** aimon-core puts `spring-boot-starter-test` on every module's test
  classpath, which is where its version-less `junit-jupiter` catalog entry got a version. Nothing here
  needs Spring, so the conventions plugin imports `junit-bom` as a platform instead.

### Known gap, inherited rather than introduced

- **The backends have no CI signal.** `DockerSandboxBackendIntegrationTest` and
  `KubernetesSandboxBackendIntegrationTest` are gated on `AIMON_DOCKER_IT` / `AIMON_KUBERNETES_IT`; no
  workflow sets either, and a gated class that skips leaves the build green. That was true in aimon-core,
  where it was recorded (backlog `live-api-test-tier.md`, `LA-2`) and not closed, and it is true here. The
  coverage floors carried over unchanged (88 / 89 / 85) measure unit tests against stubbed clients.
  `README.md` says how to run the two classes by hand and why `--rerun` is required.
