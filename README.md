# aimon-sandbox

Isolated execution environments for [AIMON](https://github.com/kangwoo/aimon-core) agents.

An agent that has to run code — try what it just wrote, build, test, process data — needs somewhere to
run it that is not the host. The workspace sandbox gives it one without new tools: the agent keeps using
`Bash`, `Read`, `Write`, `Edit` and `Grep`, and when sandboxing is on those tools run inside an
[OpenSandbox](https://github.com/alibaba/OpenSandbox) environment that the platform — not the model —
picks, provisions and retires. The design is
[`docs/design/workspace-sandbox.md`](docs/design/workspace-sandbox.md).

## Status

IMPORTANT: this repository is between designs and **ships no working code right now.**

The identifier-based sandbox it was split out of aimon-core with — four `*Sandbox` tools, the
`SandboxBackend` SPI and its Docker and Kubernetes backends — has been deleted, not kept alongside the new
design (why: [`workspace-sandbox.md`](docs/design/workspace-sandbox.md) §19; old-to-new map: §17). The
`aimon-sandbox` module is an empty placeholder until implementation step 3 (§18).

| Module | Coordinate | What it will be |
|---|---|---|
| `aimon-sandbox` | `at.aimon.sandbox:aimon-sandbox` | Workspace records, provider SPI, binding policy and the `ExecutionEnvironmentProvider` that aimon-core's file and shell tools resolve per execution (step 3) |

`aimon-sandbox-opensandbox` (step 4), `aimon-sandbox-testkit` (step 3) and `aimon-sandbox-store-jdbc`
(step 6) are added as their steps land.

If you need the old implementation, `at.aimon.core:aimon-sandbox{,-docker,-kubernetes}:0.2.4` is still on
Maven Central and still works; its design document was deleted with it and survives at
[`sandbox.md` @ `704013c`](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md).

## Relationship to aimon-core

The dependency runs one way: this repository compiles against a released `at.aimon.core:aimon-core` from
Maven Central (one line in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)), and aimon-core has no
reference to anything here. The workspace sandbox implements aimon-core's execution-environment SPI
(design §7), which requires a newer core than the one currently pinned; that bump comes with step 3.

## Build

```bash
./gradlew build       # compile + unit tests
./gradlew checkAll    # the gate: Spotless + Checkstyle + unit tests
./gradlew format      # apply formatting
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
