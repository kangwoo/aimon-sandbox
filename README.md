# aimon-sandbox

Isolated execution environments for [AIMON](https://github.com/kangwoo/aimon-core) agents.

An agent that has to run code — try what it just wrote, build, test, process data — needs somewhere to
run it that is not the host. The workspace sandbox gives it one without new tools: the agent keeps using
`Bash`, `Read`, `Write`, `Edit` and `Grep`, and when sandboxing is on those tools run inside an
[OpenSandbox](https://github.com/alibaba/OpenSandbox) environment that the platform — not the model —
picks, provisions and retires. The design is
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md).

## Status

IMPORTANT: this repository **ships nothing usable in production yet.** Implementation step 3 (§18) is in —
the workspace domain, the provider SPI and the local path — but the only provider is a test-only one; the
production OpenSandbox provider is step 4, and steps 3 and 4 are released together.

The identifier-based sandbox it was split out of aimon-core with — four `*Sandbox` tools, the
`SandboxBackend` SPI and its Docker and Kubernetes backends — has been deleted, not kept alongside the new
design (why: [`workspace-sandbox.md`](docs/design/workspace-sandbox.md) §19; old-to-new map: §17). How step 3
was built, and where it departed from the plan, is
[`workspace-sandbox-step3.md`](docs/design/workspace-sandbox-step3.md).

| Module | Coordinate | What it is |
|---|---|---|
| `aimon-sandbox` | `at.aimon.sandbox:aimon-sandbox` | Workspace records and CAS store, the manager and janitor, provider SPI, binding policy, and the `ExecutionEnvironmentProvider` that aimon-core's file and shell tools resolve per execution (step 3) |
| `aimon-sandbox-testkit` | `at.aimon.sandbox:aimon-sandbox-testkit` | Provider and store contract suites every implementation must pass, a local-process provider **for tests only**, fault injection, a manual clock (step 3) |

`aimon-sandbox-opensandbox` (step 4) and `aimon-sandbox-store-jdbc` (step 6) are added as their steps land.

If you need the old implementation, `at.aimon.core:aimon-sandbox{,-docker,-kubernetes}:0.2.4` is still on
Maven Central and still works; its design document was deleted with it and survives at
[`sandbox.md` @ `704013c`](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md).

## Relationship to aimon-core

The dependency runs one way: this repository compiles against a released `at.aimon.core:aimon-core` from
Maven Central (one line in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)), and aimon-core has no
reference to anything here. The workspace sandbox implements aimon-core's execution-environment SPI
(design §7), which only aimon-core's unreleased 0.3.1 has: the catalog pins `0.3.1-SNAPSHOT`, resolved from
`~/.m2` after `./gradlew publishToMavenLocal` in aimon-core. That pin is a release blocker (see
[CHANGELOG.md](CHANGELOG.md)).

## Build

```bash
# once, in a checkout of aimon-core (until 0.3.1 is on Maven Central):
./gradlew publishToMavenLocal -x test -x javadoc

./gradlew build       # compile + unit tests
./gradlew checkAll    # the gate: Spotless + Checkstyle + unit tests
./gradlew format      # apply formatting
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
