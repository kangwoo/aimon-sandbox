# Changelog

All notable aimon-sandbox changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Removed — the identifier-based sandbox

Everything below "Split out of aimon-core" is superseded by
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md), which replaces it without a
compatibility layer (§17 maps old to new). Nothing from it has been released under `at.aimon.sandbox`.

- **`aimon-sandbox-docker` and `aimon-sandbox-kubernetes` are gone.** The only backend in the new design is
  OpenSandbox, which covers both runtimes (§6.4); `aimon-sandbox-opensandbox` arrives in step 4 (§18).
- **`aimon-sandbox` is empty.** The four tools (`RunSandbox`, `CopyToSandbox`, `RestartSandbox`,
  `DeleteSandbox`), `SandboxBackend`, `SandboxConfig`, `RunStore`/`RunManager`, `SandboxExpiryStore`,
  `SandboxLock`, `ReaperService`, the tar transfer classes and `IdentifierValidator` were all deleted; each
  has a replacement or an explicit "none" in §17, and none is reused as-is. The module and its coordinate
  stay because the new design builds there (§4.1). It now declares aimon-core on `api`, as §4.1 requires.
- **Build cleanup.** The Docker, Kubernetes and slf4j catalog entries, the three coverage floors and the
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
