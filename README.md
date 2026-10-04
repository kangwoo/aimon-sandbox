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
open items of design §20 marked for it. (The aimon-core SNAPSHOT pin that also blocked it is gone: the build now
uses the released aimon-core 0.3.1.)

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
(design §7), which aimon-core has since 0.3.1: the catalog pins the released `0.3.1` (core PRs up to #208).

## Wiring

```java
WorkspaceSandbox sandbox = WorkspaceSandbox.builder().settings(settings).provider(provider).build();
sandbox.janitor().start();

// a hand-built runtime
runtimeBuilder.executionEnvironmentProvider(sandbox.environmentProvider());

// through aimon-bootstrap
ExecutionEnvironmentSpec.shared(sandbox.environmentProvider());
```

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
  §12.1). A hook currently runs as a foreground command of the session's shell: it takes the shell lock, and its
  `cd`/`export` persist.

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
`OPENSANDBOX_TEST_SANDBOX_IMAGE` is set, because either one points the docker tier away from what CI tests.
`ReleaseGateMatchesCiGateTest` fails the build if the gate and CI stop matching.

## License

Apache 2.0 — see [LICENSE](LICENSE).
