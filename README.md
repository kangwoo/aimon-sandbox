# aimon-sandbox

Isolated, reusable execution environments for [AIMON](https://github.com/kangwoo/aimon-core) agents.

An agent that has to run code — try what it just wrote, build, test, process data — needs somewhere to
run it that is not the host. These modules give it one: commands run inside a container or a pod, the
environment is addressed by a stable `identifier` and survives between calls, and files move both ways
between it and the agent's virtual file system.

| Module | Coordinate | What it is |
|---|---|---|
| `aimon-sandbox` | `at.aimon.sandbox:aimon-sandbox` | The `SandboxBackend` SPI and the four agent tools that use it — `RunSandbox`, `CopyToSandbox`, `RestartSandbox`, `DeleteSandbox` — registered through `OrcaSandboxToolProvider` |
| `aimon-sandbox-docker` | `at.aimon.sandbox:aimon-sandbox-docker` | A `SandboxBackend` on Docker containers |
| `aimon-sandbox-kubernetes` | `at.aimon.sandbox:aimon-sandbox-kubernetes` | A `SandboxBackend` on Kubernetes pods |

```kotlin
dependencies {
    // A backend re-exports the SPI on `api`, so one line brings both
    implementation("at.aimon.sandbox:aimon-sandbox-docker:<version>")
    implementation("at.aimon.core:aimon-core:<version>")
}
```

The design — what an identifier owns, how a Run differs from a turn, why `RunState` has no `CANCELED`,
where the tar limits live — is [`docs/design/sandbox.md`](docs/design/sandbox.md).

## What this is not

- **Not a `VirtualShell`.** Nothing here implements that SPI, and `BashTool` does not route through a
  sandbox. What is isolated is four tools the model calls by name, not the agent's shell. aimon-core's
  own docs claimed otherwise for a while; they no longer do.
- **Not container orchestration.** One container or pod per identifier. No replicas, services or volumes.
- **Not the last line of isolation.** Stopping a container escape is the runtime's job (gVisor, Kata);
  these modules narrow resources, privileges and paths on top of one.

## Relationship to aimon-core

These three modules were part of [aimon-core](https://github.com/kangwoo/aimon-core) through 0.2.4 and
were published there as `at.aimon.core:aimon-sandbox{,-docker,-kubernetes}`. Those artifacts are still on
Maven Central and still work; nothing was withdrawn. What changed is the group id and where the build
lives — see [CHANGELOG.md](CHANGELOG.md) and aimon-core's
[`docs/migration/rename-maps.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/migration/rename-maps.md).

The dependency runs one way and only one way: this repository compiles against a released
`at.aimon.core:aimon-core` from Maven Central (the version is one line in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml)), and aimon-core has no reference to anything
here. The compile surface is small and public — `at.aimon.core.agent.orca`, `at.aimon.core.agent.tool`,
`at.aimon.core.filesystem`, `at.aimon.core.agent.artifact` — but aimon-core is `0.x`, so a minor bump
there can still require changes here.

## Build

```bash
./gradlew build       # compile + unit tests
./gradlew checkAll    # the gate: Spotless + Checkstyle + unit tests
./gradlew format      # apply formatting
```

### What the build does not check

IMPORTANT: no test here talks to a real Docker daemon or Kubernetes cluster in CI, and none did in
aimon-core either. `DockerSandboxBackendIntegrationTest` and `KubernetesSandboxBackendIntegrationTest`
are gated on `AIMON_DOCKER_IT` / `AIMON_KUBERNETES_IT`, and a gated class that skips leaves the build
green. The line-coverage floors in `gradle/coverage-baselines.properties` (88 / 89 / 85) measure unit
tests against stubbed clients — read them as "the code paths are exercised", never as "the backends
drive their runtimes correctly".

Run them by hand against a real daemon:

```bash
AIMON_DOCKER_IT=true ./gradlew :aimon-sandbox-docker:test --rerun
```

`--rerun` is not optional. Gradle does not treat an environment variable as an input to the `test` task,
so a second invocation with the variable now set reports `UP-TO-DATE` and runs nothing — which looks
exactly like a pass.

Closing that gap is the first thing this repository should do that aimon-core could not justify doing
for three leaf modules.

## License

Apache 2.0 — see [LICENSE](LICENSE).
