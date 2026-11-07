# Design — ptc-verify-sandbox

Base: `main` @ `f55f0b9` (PR #51 merged). Design only. Every path is relative to the worktree
`/Users/kangwoo/.herdr/worktrees/aimon-sandbox/herdr-ptc-verify-sandbox` unless it is absolute.

## 1. Problem, restated

PR #51 adopted core's Q-C4 fix (aimon-core#336: anchor, runtime and a double-forked watchdog) and left four non-blocking
review findings. First, the docker no-zombie IT checks only the end state and never shows that the watchdog existed.
Second, a zombie anywhere in the class-shared sandbox fails that IT without naming the run that produced it. Third, the
CHANGELOG says "No code change here" although the PR changed javadoc in main source. Fourth, the closed Q-C4 item is
struck through in place in WS §20's *open* list. Two things were also never verified. One is the PTC claims on
Kubernetes: the shell-engine contract and the no-leftover/PID 1 reaping check have never run on the K8s runtime, in
template mode or in pool mode, and pool mode's PID 1 is not even known. The other is evidence that the no-zombie IT can
fail at all. This task closes all four findings. It runs the K8s tier on a dedicated, throwaway kind cluster
(`aimon-sandbox-verify`) in both modes, adding what the tier lacks so those checks exist. It shows the no-leftover
check failing against a deliberately broken program. Each result goes on record, including anything that could not be
run. Core is not modified, and every gate stays green: `checkAll`, `integrationTest`, CI.

## 2. What is already here (grounding)

- **A K8s tier exists.** `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` registers `k8sTest`
  (`includeTags("k8s")`). It is excluded from `test` and `integrationTest`, CI never runs it
  (`.github/workflows/build.yml`), and `release.sh` asks for `--k8s-verified`. Its one class is
  `modules/aimon-sandbox-opensandbox/src/test/java/at/aimon/sandbox/opensandbox/OpenSandboxK8sIT.java`, with labels,
  hardening, seed, east-west, runtime class, dns+nft and vault checks. Every test skips without
  `OPENSANDBOX_K8S_ENDPOINT`. **It has no shell-engine check and no pool mode.**
- **A provisioning script exists:** `scripts/k8s-tier.sh up|test|down`. It sets up kind, the OpenSandbox chart at
  upstream commit `3738975` with `release-1.1.1-rc.1` docker.io images, the container-level hardened template
  (`spike/opensandbox/k8s/deploy/template-hardened-ctr.yaml`), the `sandbox-isolation` NetworkPolicy, the `spike-runc`
  RuntimeClass, and the contract image built from `src/test/docker/Dockerfile` (which has `python3`), loaded into the
  node. The cluster name comes from `AIMON_K8S_CLUSTER` (default `aimon-k8s-tier`). **Hazard:** it uses the user's
  default kubeconfig, `kind create` switches its current-context, and `run_tests` runs `kubectl config use-context`.
  That breaks "never touch other contexts". The probe found `~/.kube/config` present with no current-context.
- **The docker shell-engine IT:** `ShellEngineContractIT` (`@Tag("docker")`) extends core's
  `AbstractCodeExecutionEngineContractTest` over `SharedSandbox`. `SharedSandbox` is hard-wired to
  `OpenSandboxTestServer.dnsNft()` and a waived docker profile. `aTimedOutRunLeavesNothingInTheSandbox` plus
  `leftOver()` (`ps -eo pid=,ppid=,stat=,args=`) is the no-zombie check this task reworks.
- **Pool mode, from upstream source (read at `3738975` in the scratchpad; release-1.1.1-rc.1 may differ):**
  - The server selects pool mode with `extensions.poolRef` in the create body (`batchsandbox_provider.py:155`). It
    refuses `networkPolicy` and `volumes` with it. It ignores `image`/`resourceLimits`: the image comes from the `Pool`
    CR. Labels still land on the BatchSandbox metadata.
  - The pool pod's main container runs `/bin/sh -c "/opt/opensandbox/task-executor …"`. The sandbox is a *task*
    `/bin/sh -c "exec /opt/opensandbox/bootstrap.sh <entrypoint>"`, started by task-executor with `Setpgid`.
  - task-executor (Go) only `cmd.Wait()`s its own child. It has no `wait4(-1)` and does not set
    `PR_SET_CHILD_SUBREAPER` (`internal/task-executor/runtime/process.go`). **If it is PID 1, orphans such as the
    watchdog may never be reaped.** That is the PID 1 question to measure.
  - `execd --init` that is not PID 1 sets `PR_SET_CHILD_SUBREAPER` (`components/execd/pkg/runtime/initmode_linux.go:437`).
    With server `execd_run_as_init = true` the watchdog would then be adopted by execd instead of PID 1. This is a
    candidate mitigation, if rc.1 has the flag.
- **The provider cannot ask for a pool today.** `OpenSandboxProvider.body(CreateSpec)` (`OpenSandboxProvider.java:222`)
  has no `extensions`. Both `OpenSandboxProvider` and `OpenSandboxProviderConfig` are `final`. WS §6 explicitly keeps
  the warm pool out of the SPI.
- **Tool probes (read-only):** kind v0.33.0, kubectl v1.34.1, helm v4.3.0, Docker 29.2.1 (aarch64, 10 CPU, ~8 GB),
  no kind clusters. `docker.io/opensandbox/task-executor:release-1.1.1-rc.1` exists (`v0.1.0`, the sample's tag, does
  not exist on docker.io).
- **WS §20's convention:** closed items stay in place, struck through. There are 19 of them; Q-C4 is the last.

## 3. Approach and rejected alternatives

### 3.1 Items 1–2: watchdog evidence and attributing leftovers

**Chosen:**

- **Watchdog evidence from inside the program.** The test's program inspects `/proc` before it spins and writes what it
  saw to a probe file under `/tmp/aimon-probe-<uuid>`. That covers its own pid/pgid, its parent's pid/pgid and args
  (the anchor), and the `aimon_ptc` processes in its own process group whose PPID is 1 or another non-tree reaper (the
  double-forked watchdog). The program runs in the runtime's interpreter, so it sees exactly the topology #336 creates.
  After the TIMED_OUT return the test reads the file with `sandbox.bash`. It asserts two things: the anchor's pgid
  differs from the runtime's, and at least one watchdog candidate existed. Then it asserts that nothing is left, as
  today. No concurrent exec is needed. `SandboxShell` takes a node-local lock per shell, so a parallel `ps` probe would
  need a background command or a second sandbox.
- **Attribution.** There are two layers:
  1. An `@AfterEach` in `ShellEngineContractIT`, polling as today, fails *the test that just ran* when it left any
     runtime, mark child or zombie. JUnit attributes an `@AfterEach` failure to that test, so a leftover is named by
     test. This mirrors the testkit tier's host `@AfterEach` from #51.
  2. The dedicated test takes a baseline `ps` before its run and diffs after. Its failure message separates "new since
     this run" from "present before this run". Each zombie line carries its parent's pid and args (one more `ps`
     field: `ppid` resolved to args) so the message names who failed to reap. The child mark becomes per-run
     (`sleep 61<random 4 digits>`), so a mark from another run cannot be taken for this one.

**Rejected:**

- *Polling `ps` from a second exec while the run spins.* The shell lock serializes it, a background command adds
  plumbing, and the 2 s wall clock leaves almost no window.
- *Counting `aimon_ptc` processes by number.* It is brittle against core's internal topology. The pgid relation plus
  "a reparented process in the runtime's group" is the mechanism #336 promises, so the test asserts that.
- *Leaving attribution to the baseline diff alone.* That still cannot say which earlier inherited test leaked.

**Dependency:** the exact process topology (is the program run in the runtime's own process, does the watchdog keep
`aimon_ptc` in its argv) must be confirmed against core's `aimon_ptc.py` in the resolved `0.3.4-SNAPSHOT` jar (read
only). If the program does not run in the runtime's process, the probe instead looks up its ancestors via `/proc/<pid>/stat`.
The assertions are unchanged.

### 3.2 Items 3–4: wording

- **CHANGELOG:49.** Replace "No code change here" with "No behaviour change here: main source changed only in javadoc
  (`SandboxShell`, `SandboxExecutionEnvironment`, `OpenSandboxRunningCommand`), the rest is tests". The bullet then
  gains this task's K8s results and the negative control.
- **WS §20.** Add a sub-list `### 닫은 항목` at the end of §20, before §21. Move all 19 struck-through items there in
  their existing order, as a pure move. Drop the `~~` and keep the *(…닫았다)* notes and the text verbatim. §20 then
  lists only open items, as its title and lead sentence promise.
  - *Rejected: moving only Q-C4.* It creates two conventions in one section.
  - *Rejected: deleting closed items.* They are the record other docs link to by name.
  - This choice is listed as open question O1, in case the orchestrator wants the minimal diff.

### 3.3 Item 5: the K8s tier, both modes

**Chosen: extend the existing tier, not a new one.** The provisioning script gains isolation and a pool. The tier gains
a K8s shell-engine contract class per mode. The provider gains a package-private test seam for `extensions`.

1. **Kubeconfig isolation (safety, first).** `scripts/k8s-tier.sh` exports
   `KUBECONFIG="${AIMON_K8S_KUBECONFIG:-$work/kubeconfig}"` for every subcommand. `kind create/delete`, `helm` and
   `kubectl` then read and write only that file, and the user's `~/.kube/config` is never written. The `use-context`
   line becomes harmless, because it now writes the private file; it stays, as a guard. `up` also refuses to run when
   `kind get clusters` already lists the name, so it never adopts a cluster it did not create. The verification run
   uses `AIMON_K8S_CLUSTER=aimon-sandbox-verify`. The default name stays as is, so the documented flow does not change.
2. **Pool provisioning.** A new `modules/aimon-sandbox-opensandbox/src/test/k8s/pool.yaml` sits next to
   `src/test/docker`, the image it uses. It defines a `Pool` named `aimon-pool` in namespace `opensandbox`, so the
   `sandbox-isolation` NetworkPolicy (`podSelector: {}`) covers its pods too:
   - init containers `task-executor-installer` (`docker.io/opensandbox/task-executor:release-1.1.1-rc.1`) and
     `execd-installer` (`docker.io/opensandbox/execd:release-1.1.1-rc.1`, the same execd the server config pins);
   - main container `sandbox`, image `aimon-sandbox-contract:k8s`, `imagePullPolicy: IfNotPresent` (it is loaded into
     the node), command as in upstream's sample, with the same *container-level* hardening as
     `template-hardened-ctr.yaml` (non-root 1000, no privilege escalation, drop ALL, RuntimeDefault seccomp,
     `automountServiceAccountToken: false`);
   - `capacitySpec` `poolMin 0, poolMax 3, bufferMin 1, bufferMax 2`, enough for one shared sandbox plus spares.

   `up` applies it after the chart and waits until a buffer pod is Running. `test` exports
   `OPENSANDBOX_K8S_POOL=aimon-pool`. New subcommand: `scripts/k8s-tier.sh status` prints the cluster, pods and pool,
   for the record.
3. **Provider test seam.** `OpenSandboxProviderConfig.Builder` gets a **package-private** `extensions(Map<String,
   String>)`, with a package-private `extensions()` getter (default empty, null-checked). `OpenSandboxProvider.body`
   writes `"extensions": {…}` when it is non-empty. Tests live in the same package, so only they can reach it. Nothing
   public changes, and WS §6 ("the pool is not in the SPI") still holds. The javadoc says it exists for the k8s tier's
   pool mode only.
   - *Rejected: public `pool-ref` config.* It is real feature work (pool semantics in §6: no egress, volumes or
     credentials, `aimon.at/pool` labelling, reconciliation) beyond "verify". See O2.
   - *Rejected: a test-only `SandboxProvider` decorator that POSTs the create itself.* It would duplicate the
     provider's private readiness waits (`awaitRunning`, `awaitEgressSidecar`, `awaitExecd`) and test a create path
     production never uses.
   - *Rejected: a server-side default pool.* The server has no such setting for BatchSandbox; `fastpath_resource_pool`
     is the fast-sandbox runtime's.
4. **K8s shell-engine contract, per mode.** These are new `@Tag("k8s")` classes in the provider module's test sources:
   - `ShellEngineContractK8sTemplateIT` and `ShellEngineContractK8sPoolIT`. Each extends
     `AbstractCodeExecutionEngineContractTest` with its own static `SharedSandbox`, so the whole core suite runs per mode.
     Each `@BeforeAll` does `assumeTrue` on `OPENSANDBOX_K8S_ENDPOINT`, and the pool class also on
     `OPENSANDBOX_K8S_POOL`. An assumption in `@BeforeAll` reports every test of the class as skipped, which matches
     the tier's "skips without a cluster" contract.
   - Each class also runs the reworked no-leftover test from 3.1, with watchdog evidence, baseline diff and the
     `@AfterEach`.
   - **PID 1 checks (both modes).** `pid1IsRecorded` prints `/proc/1/cmdline` and `ps -o pid,stat,args -p 1` to the
     test's stdout, which goes into the JUnit XML. Template mode *asserts* the doc's claim that the args contain
     `bootstrap.sh`; pool mode only records them. `anOrphanedZombieIsReapedByPid1` starts, in the sandbox,
     `python3 -c` with a parent that forks a child which exits, while the parent sleeps without waiting. It asserts the
     child is `Z` under that parent, kills the parent, then polls until the zombie is gone. The zombie has been
     reparented to PID 1 (or the nearest subreaper), so this tests reaping directly and does not depend on the engine.
   - **Profiles.** Template mode uses the hardened profile with no waivers (`egress(List.of())`, the declared
     `runtimeClass`, `runtimes({"python3"})`, resources `500m/512Mi`). Pool mode sends no egress (null, so no
     `networkPolicy`) and no runtime class (pool pods do not get one from the server). It uses the same
     `runtimes`, and the provider config adds `extensions({"poolRef": pool})`.
5. **Shared test helpers (refactor, no behaviour change for the docker tier):**
   - `K8sTier` (new, package-private, test source) takes over `env`, `config`, `image`, `controlPlane` and
     `runtimeClass` from `OpenSandboxK8sIT`, so both K8s classes build the same provider.
   - `SharedSandbox` gets a private constructor `(OpenSandboxProvider, String deployment, SandboxProfile)`. Its
     existing factories delegate to it unchanged, and it gains `SharedSandbox.k8sTemplate()` and `k8sPool(String
     poolRef)`.
   - `SandboxLeftovers` (new) holds the `ps` parsing, the polling, the baseline diff, the zombie-parent resolution,
     the watchdog-probe program text and its parser. `ShellEngineContractIT`, both K8s classes and the negative control
     use it.
6. **Running and recording.** The dev phase runs, on the dedicated cluster, `scripts/k8s-tier.sh test` with
   `--tests` filters per class: the full existing `OpenSandboxK8sIT` (internet checks included when reachable), the
   template class and the pool class. It saves the JUnit XML summaries and the `status` output under
   `$RUN_DIR/build/k8s/`. The results go into `docs/design/ptc-shell-provider.md` §9 under a new sub-heading "K8s tier,
   measured (date)". That section gives versions (kind, node image, chart commit, server/execd/controller/
   task-executor tags), a class × mode table with passed/failed/skipped counts, PID 1 per mode, the zombie outcome, and
   what could not run and why. It replaces the current sentences "the K8s tier was not run for it. Not covered:
   Kubernetes pool mode …". The module README's k8s section adds `OPENSANDBOX_K8S_POOL`, the pool step and
   `AIMON_K8S_KUBECONFIG`. Then `scripts/k8s-tier.sh down`, and `kind get clusters` must not list
   `aimon-sandbox-verify`.
7. **If pool mode leaves zombies** (task-executor as a non-reaping PID 1): the test stays red as a finding, and nothing
   in this repository works around it. One diagnostic probe follows on the throwaway cluster:
   `helm upgrade … --set` / values with `execd_run_as_init = true` (if rc.1 accepts the key), then rerun only the pool
   class. If execd as subreaper fixes it, the README's K8s section records "pool mode needs `execd_run_as_init = true`"
   as a deployment requirement, and §9 records both runs. The pool IT is then documented as requiring that server
   setting, and `k8s-tier.sh` sets it for the pool run. The decision to merge with a red pool result is the
   orchestrator's (O3).

**Rejected for item 5:**

- *A new K8s tier or a separate Gradle task.* The tier exists, has the right exclusions, and is wired into release
  gating.
- *Running the K8s shell checks through `OpenSandboxK8sIT`.* Core's suite must be inherited (`extends`), and the
  per-mode static sandbox needs its own class.
- *Port-forwarding to execd directly.* The tier already goes through the server proxy (`useServerProxy(true)`), as
  production outside the cluster would.
- *Using the default kubeconfig with save/restore of the current-context.* That is racy, and it still writes the
  user's file.

### 3.4 Item 6: show the no-leftover check can fail

**Chosen:** a negative control on the docker tier. The new class `LeftoverCheckNegativeControlIT` (`@Tag("docker")`)
gets its own `SharedSandbox.unstarted(sandboxImage())`, so the shared sandbox of `ShellEngineContractIT` is never
polluted. It has two tests:

1. **The escaped child is caught.** Run, through the same engine and limits as the real test, a program identical to
   the real one except `subprocess.Popen(['sleep', MARK], start_new_session=True)`. A process outside the exec's and
   the runtime's groups is exactly what the pre-#336 runtime was (it led its own group/session, which the shell's group
   kill missed). Assert TIMED_OUT, then assert `SandboxLeftovers` *reports* `sleep MARK` with the run's mark, within
   the same polling window the real test uses. That proves the window would fail rather than wait out a slow kill.
   Finally kill it (`kill <pid>`) and assert it is gone.
2. **A zombie is caught.** Create the not-reaped zombie of 3.3(4) under a live parent. Assert `SandboxLeftovers`
   reports a `Z` line naming that parent. Kill the parent and assert it is cleared.

Both tests assert the *detector's* positive result, so they stay green in CI and fail if the detector regresses (for
example, a filter that stops matching). The design records honestly that this shows the check detects an escaped
process and a zombie. It does not show a pre-#336 core failing: that artifact cannot be pinned, and it would not be
modified.

**Rejected:**

- *Rebuilding a pre-#336 `aimon_ptc.py` from core's history and injecting it.* It touches core's artifact, the
  injection seam does not exist on the docker tier, and it is out of policy.
- *A one-off manual demonstration recorded only in docs.* It is not repeatable, and the detector could regress
  silently.
- *Putting the controls inside `ShellEngineContractIT`.* They would leave processes in the shared sandbox that the
  `@AfterEach` and baseline logic would then attribute to the wrong test.

## 4. Concrete changes by file

| File | Change |
|---|---|
| `modules/aimon-sandbox-opensandbox/src/test/java/.../ShellEngineContractIT.java` | `@AfterEach` leftover check. The timed-out test gains the watchdog probe, baseline diff and per-run mark. `leftOver()` moves to `SandboxLeftovers`. |
| `.../SandboxLeftovers.java` (new, test) | `ps -eo pid=,ppid=,pgid=,stat=,args=` parsing; `snapshot`, `awaitNone(baseline, label)`, `awaitPresent(predicate)`; zombie parent resolution; `watchdogProbeProgram(path)` + `WatchdogProbe parse(String)`. |
| `.../LeftoverCheckNegativeControlIT.java` (new, test, docker) | The two controls of 3.4. |
| `.../K8sTier.java` (new, test) | env/config/image/controlPlane/runtimeClass, extracted from `OpenSandboxK8sIT`. |
| `.../OpenSandboxK8sIT.java` | Uses `K8sTier`. No test changes. |
| `.../SharedSandbox.java` | General private constructor; `k8sTemplate()`, `k8sPool(String)`. Docker factories unchanged. |
| `.../ShellEngineContractK8sTemplateIT.java`, `.../ShellEngineContractK8sPoolIT.java` (new, k8s) | Core suite + no-leftover + PID 1 record/assert + orphaned-zombie reaping. |
| `.../OpenSandboxProviderConfig.java` (main) | Package-private `extensions(Map)` builder method + getter; immutable copy; javadoc "k8s test tier only". |
| `.../OpenSandboxProvider.java` (main) | `body()` writes `extensions` when non-empty. |
| `.../OpenSandboxProviderTest.java` | Fake-server body assertions: `extensions.poolRef` present when set, absent by default. This keeps the unit-tier coverage floor without Docker. |
| `modules/aimon-sandbox-opensandbox/src/test/k8s/pool.yaml` (new) | The `Pool` CR of 3.3(2). |
| `scripts/k8s-tier.sh` | Private `KUBECONFIG`; refuse an existing cluster; pool apply + wait; `OPENSANDBOX_K8S_POOL` in `test`; `status`; optional `AIMON_K8S_EXECD_INIT=1` toggling `execd_run_as_init` for 3.3(7). |
| `modules/aimon-sandbox-opensandbox/README.md` | k8s section: pool, new env vars, kubeconfig isolation, the two new classes. |
| `docs/design/ptc-shell-provider.md` §9 | "K8s tier, measured" results; the not-covered sentence replaced; negative-control note. |
| `docs/design/workspace-sandbox.md` §20 | `### 닫은 항목` sub-list; 19 items moved verbatim (O1). The moved Q-C4 text says "K8s 템플릿 · 풀 모드" per the measured result. §16's k8s paragraph mentions the shell-engine classes. |
| `CHANGELOG.md` | Line 49 wording; Unreleased gains bullets: k8s tier shell-engine classes in both modes, kubeconfig isolation in `k8s-tier.sh`, negative control, the test-only `extensions` seam (stated as not API). |

Untouched: aimon-core, `.github/workflows/build.yml` (k8sTest stays manual), `release.sh` and its gate test, Gradle
tier wiring.

## 5. Data and interface shapes

- **Create body (provider → server), only when the test seam is set:**
  `{"image": {...}, "entrypoint": [...], ..., "metadata": {...}, "extensions": {"poolRef": "aimon-pool"}}`.
  It has no `networkPolicy`, `volumes` or `credentialProxy`, because the pool profile declares none (the server would
  refuse them). There is no client-side validation: the seam is test-only, and a server 400 is the clearer signal.
- **`OpenSandboxProviderConfig` (package-private additions):** `Builder extensions(Map<String,String>)`,
  `Map<String,String> extensions()`. Public API and `toString` are unchanged.
- **Env vars of the k8s tier:** existing `OPENSANDBOX_K8S_{ENDPOINT,API_KEY,IMAGE,CONTROL_PLANE,RUNTIME_CLASS,INTERNET}`.
  New `OPENSANDBOX_K8S_POOL` (pool class skips without it). Script-only `AIMON_K8S_KUBECONFIG` and
  `AIMON_K8S_EXECD_INIT`.
- **Watchdog probe file** (written by the program, read by the test; `key=value` lines):
  `self=<pid> pgid=<pgid>`, `parent=<pid> pgid=<pgid> args=<…>`, `watchdog=<pid> ppid=<ppid> pgid=<pgid>` (0..n).
- **`SandboxLeftovers.Proc`:** `record Proc(int pid, int ppid, int pgid, String stat, String args)`. A failure line
  renders as `pid ppid pgid stat args [parent: <ppid args>]`.

## 6. Failure modes and handling

| Failure | Handling |
|---|---|
| `kind create` would touch the user's kubeconfig | Prevented by the private `KUBECONFIG`. The run checks that `~/.kube/config`'s mtime and `kubectl config get-contexts` (default file) are unchanged before and after, and records it. |
| A cluster with the name already exists | `up` refuses. A leftover from an aborted run of *this* task is deleted with `down` (same name, same private kubeconfig) only after confirming it is ours (it is in the private kubeconfig). |
| Run aborts midway (test failure, host sleep) | `down` is unconditional at the end of the dev phase. The acceptance check is `kind get clusters` without `aimon-sandbox-verify` plus `pgrep -fl aimon_ptc.py` empty. Run `caffeinate` during the K8s run (the previous task halted on host sleep). |
| rc.1 server differs from source `3738975` (pool field names, `execd_run_as_init` absent) | Observe the 4xx body or the helm error, record it, and mark the affected checks "could not run" with the reason. They do not count as passes. |
| Pool pod fails hardened (task-executor or bootstrap needs root) | Rerun with the pool's hardening removed and the profile waiving `HARDENED_SECURITY_CONTEXT` (`insecureAllow`). Record both; the shell checks are what pool mode is for. |
| Startup validator or seed refuses the pool profile (no egress under a dns+nft config, egress verify) | Add the minimal `insecureAllow` the validator names, and record it as a pool-mode limitation. Do not change the validator. |
| Core contract test fails on K8s timing only (slower exec via port-forward + proxy) | Record it with the measured duration. Core's suite cannot be changed here; if it is a timing artefact, file it as a core follow-up. |
| Watchdog topology differs from the assumption (3.1) | Adapt the probe to the real topology found in `aimon_ptc.py`. If no reparented runtime-group process can be identified, report that to the orchestrator: it means the #336 mechanism is not what the docs say. |
| Pool mode leaves zombies | Red pool test and the 3.3(7) probe. Halt-and-report if the only fix is in core. If it is a deployment setting, document it. |
| Docker memory (~8 GB total; tier wants ~3 GB) | Pool capacity is kept at most 3. Stop other heavy containers if scheduling stalls, and record it. |
| `integrationTest` gets slower from the negative control | One extra sandbox (~seconds). Acceptable within the 45 min CI job. |

## 7. Test strategy

- **Unit tier (`checkAll`).** `OpenSandboxProviderTest` pins the `extensions` body (set and unset). `SandboxLeftovers`'
  parsing gets a small plain unit test, `SandboxLeftoversTest` (untagged): `ps` lines with zombies, marks, and the
  command's own line. The parsers are pure, so this costs nothing.
- **Docker tier (`integrationTest`, CI).** `ShellEngineContractIT` (13 + `@AfterEach`; watchdog evidence asserted) and
  `LeftoverCheckNegativeControlIT` (2). The whole tier must stay green locally and in CI.
- **K8s tier (`k8sTest`, manual, on `aimon-sandbox-verify`).** `OpenSandboxK8sIT` (8), `ShellEngineContractK8sTemplateIT`
  (core suite + 3) and `ShellEngineContractK8sPoolIT` (core suite + 3). Results are recorded per 3.3(6).
  Skipped-for-a-reason is recorded as skipped, never as passed.
- **Skip behaviour.** Without env vars, `k8sTest` reports every new class as skipped. Verify this once locally, so the
  tier stays opt-in.
- **End-of-task checks.** `./gradlew checkAll` and `./gradlew integrationTest` green; `pgrep -fl aimon_ptc.py` empty;
  `kind get clusters` lacks `aimon-sandbox-verify`; the default kubeconfig is unchanged; CI green on the PR.

## 8. Open questions (not silently assumed)

- **O1. WS §20 restructuring scope.** The task says "move it properly", and the reviewer suggested a closed sub-list.
  Recommended: move all 19 closed items. The fallback, if a minimal diff is preferred, is to move only Q-C4 into a new
  sub-list and note that the other items follow the old convention. The orchestrator decides.
- **O2. "구현하지 않은 기능도 구현해줘" (implement what was not implemented).** TASK.md lists no product feature, and pool
  support in the provider is the only unimplemented capability this task touches. This design adds only a test seam,
  not public pool support (which WS §6 / §18-7 places in the lifetime-optimisation step). Should public pool support be
  in scope? If yes, it needs its own design: §6 labels, `aimon.at/pool`, reconciliation, profile-level selection.
- **O3. A red result in pool mode.** If task-executor is a non-reaping PID 1 and `execd_run_as_init` is absent from
  rc.1 (or does not help), pool mode cannot be made green from this repository. Should the PR merge with the pool
  result recorded as a known finding (the pool class documented as expected-failing on the pinned server), or should
  the task halt? Recommended: merge. `k8sTest` is manual and not a CI gate, and the finding is the deliverable.
- **O4. Internet-dependent K8s checks.** `credentialsAreInjectedOnlyWithinTheirScope` and `gitOverHttpsWorksUnderInjection`
  need httpbin.org and github.com from the kind node. Run them when reachable and record them as skipped otherwise.
  Is that acceptable as "verified"?
- **O5. Server version.** The repository's K8s path pins chart commit `3738975` with `release-1.1.1-rc.1` images, while
  the docker tier pins `release-1.1.0` / execd `v1.1.0` by digest. This design verifies the K8s path as the repository
  defines it and does not align the versions. Flag if alignment is wanted.
- **O6. The topology of core's runtime** (3.1) is read from the jar during build. If it contradicts the documented
  anchor/runtime/watchdog shape, the watchdog-evidence assertion is redesigned rather than weakened. That would be
  reported, since it touches the claim #51 rests on.

## 9. Implementation departures

The body above is the design as approved (review 1 passed with non-blocking notes). This section records where the
build departed from it, which review notes it adopted, and how the open questions were answered. Results are in
[`ptc-shell-provider.md`](ptc-shell-provider.md) §9 "K8s tier, measured"; the in-tree copy names this document
`ptc-shell-verification.md`.

### Where the code differs from the design

- **Two abstract test bases instead of three copies (§3.1, §3.3(4)).** `AbstractShellEngineSandboxIT` extends core's
  suite and holds the engine wiring, the per-test snapshot and leftover check, and the timed-out test with the watchdog
  probe. `AbstractShellEngineK8sIT` adds the PID 1 record and the orphaned-zombie test. `ShellEngineContractIT` and both
  K8s classes keep their own static `SharedSandbox`, `@BeforeAll` (with the `assumeTrue`s) and `@Tag`. Each class runs
  the tests §7 lists.
- **Pool mode needs a second NetworkPolicy (§3.3(2)).** `sandbox-isolation` admits only the server, but in pool mode
  the *controller* delivers the task to task-executor on port 5758 (upstream's code-interpreter example), and kind's
  kindnet enforces policies. `pool.yaml` adds `aimon-pool-task-executor`: 5758 on the pool's pods, from
  `control-plane: controller-manager` in `opensandbox-system`, nothing else.
- **Pool manifest details.** The pool mounts an `emptyDir` at `/var/lib/sandbox`, task-executor's default data
  directory. Without it, uid 1000 cannot create the directory and the pod crash-loops (seen on the first `up`).
  `recycleStrategy: Delete` is written out, so no sandbox inherits a pod. There is no `SANDBOX_MAIN_CONTAINER`, which
  belongs to upstream's sidecar layout. A pod label lets the policy and `up` select the pool's pods. `up` waits for the
  pool's `status.available`, not for a Running pod: a crash-looping pod is Running for a moment, which let the first
  `up` pass with a broken pool.
- **`execd_run_as_init` is a `reconfigure` subcommand (§3.3(7)).** The server's config is a TOML string inside the
  chart values, so `helm --set` cannot change one key in it. `k8s-tier.sh` renders `$work/values.yaml` (awk adds the key
  under `[runtime]` when `AIMON_K8S_EXECD_INIT=1`). `up` installs with it, and `reconfigure` runs `helm upgrade` with it
  and restarts the server.
- **A pre-existing script bug, fixed.** `run_tests` kept the port-forward's pid in a `local`. The EXIT trap runs after
  the function has returned, and under `set -u` the unbound name aborted the trap. The forward kept running, and a
  green run exited 1 (seen on the second `OpenSandboxK8sIT` run). The pid is now global.
- **Pool mode is green, so §3.3(7)'s fallback was not needed.** The pool class does not depend on
  `execd_run_as_init`, and `test` always exports `OPENSANDBOX_K8S_POOL`. The release tier is not red by design (review
  note on O3). The fallback was still run, as a diagnostic, and its result is in O3 below.

### Review 1's non-blocking notes

All adopted:
- the `@BeforeEach` snapshot, so `@AfterEach` reports only what is new;
- the watchdog predicate `pgid == self && ppid != self` (`_start_watchdog` in the resolved jar);
- a template PID 1 of `bootstrap.sh` *or* `execd --init`;
- a pool provider config without the runtime-class declaration (`K8sTier.poolConfig`);
- the background plumbing written down (`SandboxLeftovers.startUnreapedZombie`);
- negative control 1 asserting that the real `awaitNone` throws, so no `awaitPresent` exists;
- `KUBECONFIG` for the manual flow (README, script header, guide);
- the probe file deleted in the same call that reads it;
- the pod name recorded with PID 1.

The note's request to record, per test, whether the body passed apart from the leftover verdict is met for the run that
needed it. In the task-executor-as-PID-1 diagnostic, core's 12 engine tests failed only in `@AfterEach`, with no
suppressed body failure. The other runs were green.

### Open questions — answers and where they went

- **O1** — all 19 closed items moved verbatim to `### 닫은 항목` at the end of WS §20 (only the `~~` dropped). The
  moved Q-C4 entry then gained the K8s result.
- **O2** — test seam only. Public pool support stays in WS §18-7. What it must settle first is now the WS §20 item
  "풀 파드의 PID 1".
- **O3** — moot: pool mode passed. The finding it anticipated is real, but it lives in the pool's template, not the
  server: task-executor as PID 1 reaps nothing, and with it every test failed on zombies. A shell as PID 1 (this tier's
  pool) or `execd_run_as_init = true` (execd becomes a subreaper) fixes it. Both were measured. WS §20 "풀 파드의 PID 1".
- **O4** — the internet checks ran and passed. Nothing in the tier was skipped.
- **O5** — not aligned. It is now the WS §20 item "K8s 경로와 docker 계층의 서버 버전".
- **O6** — the topology matched; the probe saw it on both tiers and in both modes.
