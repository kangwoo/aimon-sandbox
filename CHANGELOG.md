# Changelog

All notable aimon-sandbox changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added — workspace sandbox, implementation step 3

The domain and the local path of [`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md) §18-3;
how it was built and where it departed from the plan is
[`docs/design/workspace-sandbox-step3.md`](docs/design/workspace-sandbox-step3.md). **Not usable in production
yet**: the only provider is the test-only `LocalProcessSandboxProvider`; the OpenSandbox provider is step 4, and
steps 3 and 4 are released together.

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
  (step 5), a `seed` (step 5) or `credentials` (step 4). Only the `primary` slot is served (step 5).
- **aimon-core is pinned to `0.3.1-SNAPSHOT`**, resolved through a `mavenLocal()` filtered to the `at.aimon.core`
  group and to snapshots. **This is a release blocker**: nothing is released from this repository until
  aimon-core 0.3.1 is on Maven Central, the pin is raised to it, and `mavenLocal()` is removed.
- `slf4j-api` and `jackson-databind` join the catalog at aimon-core's own runtime versions: aimon-core's published
  API exports no dependencies, so what this code compiles against is declared here.

### Removed — the identifier-based sandbox

Everything below "Split out of aimon-core" is superseded by
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md), which replaces it without a
compatibility layer (§17 maps old to new). Nothing from it has been released under `at.aimon.sandbox`.

- **`aimon-sandbox-docker` and `aimon-sandbox-kubernetes` are gone.** The only backend in the new design is
  OpenSandbox, which covers both runtimes (§6.4); `aimon-sandbox-opensandbox` arrives in step 4 (§18).
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
