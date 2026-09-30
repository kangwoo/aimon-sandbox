# aimon-sandbox

Isolated execution environments for [AIMON](https://github.com/kangwoo/aimon-core) agents.

An agent that has to run code — try what it just wrote, build, test, process data — needs somewhere to
run it that is not the host. The workspace sandbox gives it one without new tools: the agent keeps using
`Bash`, `Read`, `Write`, `Edit` and `Grep`, and when sandboxing is on those tools run inside an
[OpenSandbox](https://github.com/alibaba/OpenSandbox) environment that the platform — not the model —
picks, provisions and retires. The design is
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md).

## Status

IMPORTANT: this repository **has not been released yet.** Implementation steps 3 and 4 (§18) are in — the
workspace domain, the provider SPI and the local path (step 3), and the production OpenSandbox provider with the
janitor's sandbox reconciliation (step 4) — and are released together. The release is still blocked on the
aimon-core SNAPSHOT pin (below) and on the open items of design §20 marked for it.

The identifier-based sandbox it was split out of aimon-core with — four `*Sandbox` tools, the
`SandboxBackend` SPI and its Docker and Kubernetes backends — has been deleted, not kept alongside the new
design (why: [`workspace-sandbox.md`](docs/design/workspace-sandbox.md) §19; old-to-new map: §17). How steps 3
and 4 were built, and where they departed from the plan, is
[`workspace-sandbox-step3.md`](docs/design/workspace-sandbox-step3.md) and
[`workspace-sandbox-step4.md`](docs/design/workspace-sandbox-step4.md); what the OpenSandbox server actually does is
[`opensandbox-spike.md`](docs/design/opensandbox-spike.md).

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
(design §7), which only aimon-core's unreleased 0.3.1 has: the catalog pins `0.3.1-SNAPSHOT`, resolved from
`~/.m2` after `./gradlew publishToMavenLocal` in aimon-core. That pin is a release blocker (see
[CHANGELOG.md](CHANGELOG.md)).

## Build

```bash
# once, in a checkout of aimon-core (until 0.3.1 is on Maven Central):
./gradlew publishToMavenLocal -x test -x javadoc

./gradlew build            # compile + unit tests (no Docker needed)
./gradlew checkAll         # the gate: Spotless + Checkstyle + unit tests
./gradlew format           # apply formatting
./gradlew integrationTest  # @Tag("docker"): the provider against a real OpenSandbox server (needs Docker)
./gradlew k8sTest          # @Tag("k8s"): manual, against a provisioned cluster (see the provider's README)
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
