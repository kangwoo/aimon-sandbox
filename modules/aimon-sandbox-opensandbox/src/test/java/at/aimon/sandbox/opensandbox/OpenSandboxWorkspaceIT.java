package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.ShellKillCause;
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.WorkspaceSandbox;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ResourceSpec;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider;
import at.aimon.sandbox.testkit.ManualClock;
import at.aimon.sandbox.testkit.ManualScheduler;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;

/**
 * {@code WorkspaceSandbox} end to end on a real OpenSandbox server (Docker runtime) — the implementation-step-4 rows of
 * docs/design/workspace-sandbox.md §16 that need the real server: labels accepted for a {@code ws:{uuid}} workspace,
 * shell state across calls, timeout kill, cancellation of a running background command, reconciliation of a restarted
 * node's orphan, resource limits, the line normalization of output, and hook runs with their run directories
 * (kangwoo/aimon-sandbox#33).
 */
@Tag("docker")
class OpenSandboxWorkspaceIT {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("opensandbox-it");
    private static final Principal ALICE = Principal.user("alice");

    /** The out-of-memory test's memory limit, 256 Mi. */
    private static final long OOM_MEMORY = 256L * 1024 * 1024;
    /**
     * The out-of-memory command's timeout: far above the kill's well under a second without swap, so that a server
     * whose Docker is not this JVM's, where swap stays (see the test), still has room, and a hang is still told apart.
     */
    private static final Duration OOM_COMMAND = Duration.ofSeconds(180);
    /** The cgroup v2 memory files the out-of-memory test records, in {@code /sys/fs/cgroup}. */
    private static final String MEMORY_FILES = "memory.max memory.swap.max memory.current memory.swap.current "
            + "memory.peak memory.events memory.swap.events";
    /** Their cgroup v1 counterparts, in {@code /sys/fs/cgroup/memory}; {@code memory.oom_control} has the count. */
    private static final String MEMORY_FILES_V1 = "memory.limit_in_bytes memory.memsw.limit_in_bytes "
            + "memory.usage_in_bytes memory.memsw.usage_in_bytes memory.max_usage_in_bytes memory.failcnt "
            + "memory.oom_control";

    private final ManualClock clock = new ManualClock(Instant.now());
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final List<SandboxEvent> events = new ArrayList<>();
    private final List<WorkspaceSandbox> assemblies = new ArrayList<>();
    private OpenSandboxProvider provider;

    @BeforeEach
    void startProvider() {
        provider = new OpenSandboxProvider(OpenSandboxTestServer.dnsNft().config().build());
    }

    @AfterEach
    void close() {
        assemblies.forEach(assembly -> {
            assembly.store().scan(at.aimon.sandbox.workspace.WorkspaceScan.builder().build())
                    .forEach(record -> assembly.manager().close(record.id(), ALICE));
            assembly.close();
        });
        provider.close();
    }

    private static SandboxProfile.Builder profile() {
        return SandboxProfile.builder().name("it").image(OpenSandboxTestServer.sandboxImage()).platform("linux")
                .terminateAfter(Duration.ofHours(1))
                .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION));
    }

    private WorkspaceSandbox assembly(SandboxProfile profile, SandboxWorkspaceStore store) {
        return assembly(profile, store, provider);
    }

    private WorkspaceSandbox assembly(SandboxProfile profile, SandboxWorkspaceStore store, SandboxProvider provider) {
        final SandboxSettings settings = SandboxSettings.builder().deployment(OpenSandboxTestServer.DEPLOYMENT)
                .nodeId("it-" + UUID.randomUUID()).profiles(List.of(profile)).defaultProfile(profile.name())
                .closeWait(Duration.ofSeconds(20)).build();
        final WorkspaceSandbox assembly = WorkspaceSandbox.builder().settings(settings).provider(provider).store(store)
                .clock(clock).scheduler(scheduler).eventListener(events::add).build();
        assemblies.add(assembly);
        return assembly;
    }

    private static ExecutionEnvironment mainTurn(WorkspaceSandbox assembly, SessionId session) {
        return ExecutionEnvironments.resolveOrUnavailable(assembly.environmentProvider(),
                EnvironmentRequest.builder().agentRuntimeId(RUNTIME).sessionId(session).principal(ALICE).build());
    }

    private static ShellCommandResult bash(ExecutionEnvironment env, String command, Duration timeout)
            throws Exception {
        return env.shell().execute(() -> command, ExecutionOptions.builder().timeout(timeout).build());
    }

    private static ShellCommandResult bash(ExecutionEnvironment env, String command) throws Exception {
        return bash(env, command, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("§16: a ws:{uuid} workspace provisions on the real server; Write then Bash sees the file")
    void writeThenBashOnARealSandbox() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = mainTurn(assembly, session);

        env.fileSystem().write("/workspace/hello.txt", "hello from the file tool");
        final ShellCommandResult cat = bash(env, "cat /workspace/hello.txt");

        assertThat(cat.stdout()).isEqualTo("hello from the file tool\n");
        assertThat(events).extracting(SandboxEvent::type).contains(SandboxEvent.Type.PROVISIONED);
        final ProviderSandboxRef ref = assembly.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();
        assertThat(ref.provider()).isEqualTo(OpenSandboxProvider.NAME);
    }

    @Test
    @DisplayName("§16: cd/export persist across calls, and a timeout kill keeps the state from before")
    void shellStatePersistsAndATimeoutKillKeepsIt() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final ExecutionEnvironment env = mainTurn(assembly, SessionId.generate());

        bash(env, "mkdir -p /workspace/src && cd /workspace/src && export KEEP=kept");
        assertThatThrownBy(() -> bash(env, "cd /tmp; export LOST=1; sleep 30", Duration.ofSeconds(2)))
                .isInstanceOf(ShellTimeoutException.class);
        final ShellCommandResult next = bash(env, "pwd; echo \"KEEP=$KEEP LOST=$LOST\"");

        assertThat(next.stdout()).isEqualTo("/workspace/src\nKEEP=kept LOST=\n");
    }

    @Test
    @DisplayName("§16: cancelling a running background command ends it in the real sandbox (DELETE /command)")
    void cancellingABackgroundCommandEndsItInTheSandbox() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final ExecutionEnvironment env = mainTurn(assembly, SessionId.generate());
        bash(env, "true");
        final ShellCancellationSource source = ShellCancellationSource.create();
        final CompletableFuture<Object> background = CompletableFuture.supplyAsync(() -> {
            try {
                return env.shell().execute(() -> "echo before; sleep 300 & echo \"$$ $!\" > /workspace/pids; wait",
                        ExecutionOptions.builder().timeout(Duration.ofMinutes(5)).background(true)
                                .cancellation(source.token()).build());
            } catch (ShellExecutionException e) {
                return e;
            }
        });
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (bash(env, "cat /workspace/pids 2>/dev/null").stdout().isBlank() && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        final String pids = bash(env, "cat /workspace/pids").stdout().strip();

        source.cancel();
        final Object outcome = background.get(30, TimeUnit.SECONDS);
        // execd sends SIGKILL three seconds after its SIGTERM; both processes take the SIGTERM.
        Thread.sleep(500);
        final ShellCommandResult alive = bash(env, "for p in " + pids + "; do kill -0 $p 2>/dev/null && echo $p; done");

        assertThat(env.shell().supports(ShellFeature.CANCELLATION)).isTrue();
        assertThat(pids.split(" ")).hasSize(2);
        assertThat(outcome).isInstanceOfSatisfying(ShellCancelledException.class,
                e -> assertThat(e.stdout()).isEqualTo("before\n"));
        assertThat(alive.stdout()).as("the command and its child are gone").isEmpty();
    }

    @Test
    @DisplayName("§10.4: a node restarted with an empty InMemory store reclaims its old sandbox after orphan-grace")
    void reconciliationReclaimsTheOrphanOfARestartedNode() throws Exception {
        final WorkspaceSandbox before = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        bash(mainTurn(before, session), "true");
        final ProviderSandboxRef orphan = before.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();

        final WorkspaceSandbox after = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        after.janitor().runOnce();
        assertThat(provider.status(orphan)).as("within orphan-grace").isPresent();

        // The manual clock stood still while the server's did not: the age is measured by the later of the two.
        clock.advance(Duration.ofMinutes(11));
        after.janitor().runOnce();
        assertThat(provider.status(orphan)).as("after orphan-grace").isEmpty();
    }

    /**
     * {@code tail} grows until the cgroup's OOM killer ends it. The server sets only Docker's memory limit, and
     * Docker's
     * default memory-swap (twice the limit) lets the cgroup swap up to 256 Mi more. Where the swap disk is slow, as on
     * CI runners, reclaim then keeps making a little progress, the OOM killer waits on it, and execd, in the same
     * cgroup, stalls with the command: past 60 s once (PR #52), past 180 s with the sandbox unreachable afterwards (PR
     * #55, cgroup v2, {@code memory.swap.max} 268435456), where an attempt that passed took 8 s and a local Docker
     * Desktop, with fast swap, 2–3 s. So the test takes swap away from its sandbox first
     * ({@link OpenSandboxTestServer#takeSwapAway}) and checks that the cgroup now allows none
     * ({@link #swapAllowance}); without swap the kill takes well under a second. A server started elsewhere keeps its
     * swap, and only the {@link #OOM_COMMAND} margin is left there. The sandbox's memory accounting (limits, swap, the
     * OOM events) before and after goes to stdout, and into the failure when the command times out, so a slow kill can
     * be told apart from one that never came. The kill itself is checked twice: exit 137, and the cgroup's
     * {@code oom_kill} count went up. That count is in {@code memory.events} on cgroup v2 and in
     * {@code memory.oom_control} on v1, both from Linux 4.13, which every host this tier runs on (Docker with cgroup
     * v2) exceeds. Only a v1 cgroup without it skips the second check, saying so, once everything else has passed (see
     * {@link #lacksOomKillCount}); on v2, or where the version could not be read, a missing count fails.
     */
    @Test
    @DisplayName("§12.2: an out-of-memory command dies inside its sandbox; the sandbox and its neighbours answer")
    void resourceLimitsHoldInsideTheSandbox() throws Exception {
        final WorkspaceSandbox assembly = assembly(
                profile().resources(ResourceSpec.of("1", "256Mi", null, null)).build(),
                new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = mainTurn(assembly, session);
        final ExecutionEnvironment neighbour = mainTurn(assembly, SessionId.generate());

        final ShellCommandResult pids = bash(env, "cat /sys/fs/cgroup/pids.max");
        final ProviderSandboxRef ref = assembly.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();
        final boolean swapTaken = OpenSandboxTestServer.dnsNft().takeSwapAway(ref, OOM_MEMORY);
        final String before = memory(env);
        if (swapTaken) {
            assertThat(swapAllowance(before))
                    .as("the swap the cgroup allows once it was taken away, before:%n%s", before).isZero();
        }
        final long started = System.nanoTime();
        final ShellCommandResult oom;
        try {
            oom = bash(env, "head -c 600000000 /dev/zero | tail -n 1 > /dev/null; echo rc=$?", OOM_COMMAND);
        } catch (ShellTimeoutException e) {
            throw new AssertionError(
                    "the out-of-memory command was not killed within " + OOM_COMMAND + " (swap taken away: " + swapTaken
                            + "). The sandbox's memory before it:\n" + before + "after it:\n" + memory(env),
                    e);
        }
        final String after = memory(env);
        System.out.printf(
                "[§12.2] the out-of-memory command ended in %d ms (swap taken away: %s); memory before:%n%s"
                        + "after:%n%s",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), swapTaken, before, after);

        assertThat(pids.stdout().strip()).as("the server's pids_limit").isEqualTo("4096");
        assertThat(oom.stdout()).isEqualTo("rc=137\n");
        assertThat(bash(env, "echo alive").stdout()).isEqualTo("alive\n");
        assertThat(bash(neighbour, "echo neighbour").stdout()).isEqualTo("neighbour\n");
        assumeTrue(!lacksOomKillCount(before, after),
                () -> String.format("the exit 137 and the rest passed, but this "
                        + "cgroup v1 has no oom_kill count to compare (memory.oom_control has it from Linux 4.13); "
                        + "before:%n%safter:%n%s", before, after));
        assertThat(List.of(oomKills(before), oomKills(after)))
                .as("the cgroup's oom_kill count is readable, before:%n%safter:%n%s", before, after)
                .allMatch(count -> count >= 0);
        assertThat(oomKills(after)).as("the cgroup's oom_kill count, before:%n%safter:%n%s", before, after)
                .isGreaterThan(oomKills(before));
    }

    /**
     * @return the sandbox's cgroup version, then its memory files, one {@code name: content} line each (an unreadable
     *         one says so), or why they could not be read at all: a sandbox whose execd stopped answering must not
     *         hide the failure that asked
     */
    private static String memory(ExecutionEnvironment env) {
        try {
            return bash(env,
                    "if [ -f /sys/fs/cgroup/cgroup.controllers ]; then echo 'cgroup: v2'; "
                            + "cd /sys/fs/cgroup && files='" + MEMORY_FILES + "'; else echo 'cgroup: v1'; "
                            + "cd /sys/fs/cgroup/memory && files='" + MEMORY_FILES_V1 + "'; fi && for f in $files; "
                            + "do printf '%s: %s\\n' \"$f\" \"$(tr '\\n' ' ' 2>&1 < \"$f\")\"; done")
                    .stdout();
        } catch (Exception e) {
            return "(unreadable: " + e + ")\n";
        }
    }

    /**
     * @return the {@code oom_kill} count of {@link #memory}'s {@code memory.events} line (cgroup v2) or
     *         {@code memory.oom_control} line (v1), -1 when it has neither
     */
    static long oomKills(String memory) {
        final Matcher matcher = Pattern.compile("(?m)^memory\\.(?:events|oom_control): .*\\boom_kill (\\d+)")
                .matcher(memory);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    /**
     * @return the swap, in bytes, {@link #memory}'s cgroup allows beyond its memory: {@code memory.swap.max} on cgroup
     *         v2 ({@link Long#MAX_VALUE} for {@code max}), {@code memory.memsw.limit_in_bytes} less
     *         {@code memory.limit_in_bytes} on v1; -1 when the reading has neither
     */
    static long swapAllowance(String memory) {
        final Matcher v2 = Pattern.compile("(?m)^memory\\.swap\\.max: (\\d+|max) *$").matcher(memory);
        if (v2.find()) {
            return "max".equals(v2.group(1)) ? Long.MAX_VALUE : Long.parseLong(v2.group(1));
        }
        final Matcher limit = Pattern.compile("(?m)^memory\\.limit_in_bytes: (\\d+) *$").matcher(memory);
        final Matcher memsw = Pattern.compile("(?m)^memory\\.memsw\\.limit_in_bytes: (\\d+) *$").matcher(memory);
        return limit.find() && memsw.find() ? Long.parseLong(memsw.group(1)) - Long.parseLong(limit.group(1)) : -1;
    }

    /**
     * @param before
     *            {@link #memory} before the out-of-memory command
     * @param after
     *            and after it
     * @return whether the {@code oom_kill} check is skipped: one reading has no count and both are of a cgroup v1,
     *         whose host may run a kernel before 4.13. On cgroup v2 a missing count is a failure to read it, and so is
     *         a reading that does not say its version (execd did not answer)
     */
    static boolean lacksOomKillCount(String before, String after) {
        final Pattern v1 = Pattern.compile("(?m)^cgroup: v1$");
        return (oomKills(before) < 0 || oomKills(after) < 0) && v1.matcher(before).find() && v1.matcher(after).find();
    }

    @Test
    @DisplayName("§6.1: output arrives line-normalized — a lone CR becomes a line break, a final newline is added")
    void outputIsLineNormalized() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        bash(mainTurn(assembly, session), "true");
        final ProviderSandboxRef ref = assembly.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();

        try (SandboxConnection connection = provider.connect(ref)) {
            final ExecOutcome outcome = connection.run(
                    ExecSpec.builder().command("printf 'x\\ry'; printf 'A\\r\\nB\\n\\nC'; printf '\\377' >&2").build(),
                    OutputSink.DISCARD).await(Duration.ofSeconds(30));

            assertThat(new String(outcome.stdout(), StandardCharsets.UTF_8)).isEqualTo("x\nyA\nB\n\nC\n");
            assertThat(new String(outcome.stderr(), StandardCharsets.UTF_8)).isEqualTo("�\n");
        }
    }

    @Test
    @DisplayName("#33: RUN_DIRECTORY inline, uploaded, tampered; a hook's & job killed at its trailer; timeout")
    void hookAndIsolatedRunsOnARealServer() throws Exception {
        // One sandbox for every row: provisioning it is the expensive part, and its seed checks base64 too. Faults
        // are injected only for the tamper row.
        final FaultInjectingSandboxProvider faults = new FaultInjectingSandboxProvider(provider);
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore(), faults);
        final ExecutionEnvironment env = mainTurn(assembly, SessionId.generate());
        final ExecutionOptions.Builder hook = ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).hook(true);
        final String large = "x".repeat(40 * 1024);
        final RunDirectory files = RunDirectory.builder().variable("AIMON_HOOK_DIR")
                .file("guard.sh", "echo guarded\n".getBytes(StandardCharsets.UTF_8))
                .file("lib/스크립트.txt", "한글".getBytes(StandardCharsets.UTF_8)).build();
        final RunDirectory uploaded = RunDirectory.builder().variable("AIMON_HOOK_DIR")
                .file("big", large.getBytes(StandardCharsets.UTF_8)).build();

        final ShellCommandResult inline = env.shell().execute(
                () -> "bash \"$AIMON_HOOK_DIR/guard.sh\"; cat \"$AIMON_HOOK_DIR/lib/스크립트.txt\"; echo; echo \"$AIMON_HOOK_DIR\"",
                hook.isolated(true).runDirectory(files).build());
        assertThat(inline.stdout()).startsWith("guarded\n한글\n/workspace/.aimon-shell/.run.").endsWith("/d\n");
        assertThat(env.shell().execute(() -> "wc -c < \"$AIMON_HOOK_DIR/big\"", hook.runDirectory(uploaded).build())
                .stdout()).isEqualTo(large.length() + "\n");
        assertThat(bash(env, "ls -A /workspace/.aimon-shell | grep -c '^\\.run\\.' || true").stdout())
                .as("no private directory left").isEqualTo("0\n");

        // execd holds the exec open while any holder of its streams lives: the kill on the trailer ends it.
        final long started = System.nanoTime();
        final ShellCommandResult backgrounded = env.shell().execute(() -> "sleep 30 & echo done",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).hook(true).build());
        assertThat(backgrounded.stdout()).isEqualTo("done\n");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));

        // Many stdout lines then an immediate exit: the drain grace lets execd forward them before the kill.
        final ShellCommandResult lines = env.shell().execute(() -> "seq 1 40000",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).hook(true).isolated(true).build());
        assertThat(lines.stdout()).startsWith("1\n2\n").endsWith("\n39999\n40000\n");
        assertThat(lines.stdout().split("\n")).hasSize(40000);
        assertThat(lines.outputTruncated()).isFalse();

        final ShellCommandResult exact = env.shell().execute(() -> "printf 'a\\nb\\n'; printf 'e\\n' >&2; exit 2",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).hook(true).isolated(true).build());
        assertThat(exact.stdout()).isEqualTo("a\nb\n");
        assertThat(exact.stderr()).isEqualTo("e\n");
        assertThat(exact.exitCode()).isEqualTo(2);

        // The timeout of a stream run is the JVM's clock plus execd's report of the watchdog's SIGKILL (137): were it
        // reported otherwise, a hook's timeout would read as a wrapper failure, which a failOpen guard passes.
        assertThatThrownBy(() -> env.shell().execute(() -> "echo started; sleep 30",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(1)).hook(true).isolated(true).build()))
                .isInstanceOfSatisfying(ShellTimeoutException.class,
                        e -> assertThat(e.stdout()).isEqualTo("started\n"));

        // A same-uid writer rewrites the uploaded blob in the window between the upload and the exec — held open here
        // by a delay on the exec — and the wrapper's digest check refuses it: the command does not run.
        final CompletableFuture<Object> writer = CompletableFuture.supplyAsync(() -> {
            try {
                return env.shell().execute(() -> ": > /workspace/writer-up; for i in $(seq 1 300); do"
                        + " for f in /workspace/.aimon-shell/*/run-*.blob; do [ -f \"$f\" ] && echo Zm9yZ2Vk > \"$f\";"
                        + " done; sleep 0.05; done",
                        ExecutionOptions.builder().timeout(Duration.ofMinutes(2)).background(true).build());
            } catch (ShellExecutionException e) {
                return e;
            }
        });
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!env.fileSystem().exists("/workspace/writer-up") && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        faults.inject(FaultInjectingSandboxProvider.Operation.RUN,
                FaultInjectingSandboxProvider.Fault.delay(Duration.ofSeconds(3)));
        try {
            assertThatThrownBy(
                    () -> env.shell().execute(() -> "touch /workspace/ran", hook.runDirectory(uploaded).build()))
                    .isExactlyInstanceOf(RunDirectoryIntegrityException.class);
        } finally {
            faults.clear();
        }
        assertThat(env.fileSystem().exists("/workspace/ran")).isFalse();
        assertThat(bash(env, "ls -A /workspace/.aimon-shell | grep -c '^\\.run\\.' || true").stdout())
                .as("no private directory left").isEqualTo("0\n");
        writer.cancel(true);
    }

    @Test
    @DisplayName("S1/S2: stream runs leave no execd spool behind; an output line over the SSE limit kills the run")
    void streamRunsLeaveNoSpoolAndAHugeLineIsRefused() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = mainTurn(assembly, session);
        final String id = UUID.randomUUID().toString().replace("-", "");
        final ExecutionOptions.Builder isolated = ExecutionOptions.builder().timeout(Duration.ofSeconds(60))
                .isolated(true);

        // Each run prints the marker on both streams; the check below spells it so that its own text never holds it.
        final String print = "echo SPOOLMARK" + id + "; echo SPOOLMARK" + id + " >&2";
        env.shell().execute(() -> print, isolated.build());
        env.shell().execute(() -> print, ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).hook(true).build());
        env.shell().execute(() -> print, isolated.build(), line -> {
        });
        assertThatThrownBy(
                () -> env.shell().execute(() -> print + "; sleep 30", isolated.timeout(Duration.ofSeconds(1)).build()))
                .isInstanceOf(ShellTimeoutException.class);

        // Negative control: the same output from a run that is not transient does stay in a spool file this check
        // finds — so finding none above means the provider removed them, not that execd spooled elsewhere. This holds
        // for docker/execd/v1.1.0, which this tier pins; release-1.1.0+ removes every spool itself.
        final ProviderSandboxRef ref = assembly.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();
        try (SandboxConnection connection = provider.connect(ref)) {
            assertThat(connection
                    .run(ExecSpec.builder().command("echo KEPTMARK" + id + "; echo KEPTMARK" + id + " >&2")
                            .timeout(Duration.ofSeconds(60)).build(), OutputSink.DISCARD)
                    .await(Duration.ofSeconds(60)).exitCode()).isZero();
        }

        // execd release-1.1.0+ spools to $TMPDIR/opensandbox-execd and removes it itself; docker/execd/v1.1.0 spools
        // to $TMPDIR and keeps it — what the provider removes.
        // ${m}, not $m: the id goes right after it, and "$m<id>" names one unset variable — an empty pattern that
        // every non-empty file matches.
        assertThat(bash(env, "m=SPOOLMARK; grep -rlF -- \"${m}" + id + "\" \"${TMPDIR:-/tmp}\" 2>/dev/null | wc -l")
                .stdout().strip()).as("spool files holding the runs' output").isEqualTo("0");
        assertThat(bash(env, "m=KEPTMARK; grep -rlF -- \"${m}" + id + "\" \"${TMPDIR:-/tmp}\" 2>/dev/null | wc -l")
                .stdout().strip()).as("spool files holding a non-transient run's output").isNotEqualTo("0");

        // One output line of ~300 KiB arrives as one SSE event line: the reader stops before holding it and kills.
        final long started = System.nanoTime();
        assertThatThrownBy(() -> env.shell().execute(() -> "head -c 300000 /dev/zero | tr '\\0' x; echo; sleep 30",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).hook(true).isolated(true).build()))
                .isInstanceOf(ShellExecutionException.class).isNotInstanceOf(ShellTimeoutException.class)
                .hasMessageContaining("line limit");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));

        // S8: the same line from an isolated run that is not a hook's — the shell engine's call shape — is a result
        // that says the provider ended it, which core's engine reads as a protocol violation.
        final long isolatedStarted = System.nanoTime();
        final ShellCommandResult ended = env.shell().execute(
                () -> "head -c 300000 /dev/zero | tr '\\0' x; echo; sleep 30",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).isolated(true).build(), line -> {
                });
        assertThat(ended.killCause()).contains(ShellKillCause.LINE_LIMIT_EXCEEDED);
        assertThat(ended.outputTruncated()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - isolatedStarted)).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("S4: a secret run file is on no process's command line while its run is going; a plain one is")
    void aSecretRunFileIsOnNoCommandLine() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final ExecutionEnvironment env = mainTurn(assembly, SessionId.generate());
        bash(env, "true");
        final SecureRandom random = new SecureRandom();
        final byte[] key = new byte[32];
        random.nextBytes(key);
        final byte[] plain = new byte[32];
        random.nextBytes(plain);

        // The secret's base64 and hex, and in a second run a plain file's base64.
        assertThat(cmdlineHits(env, RunDirectory.builder().variable("AIMON_RUN_DIR").secretFile("key", key).build(),
                List.of(Base64.getEncoder().encodeToString(key), HexFormat.of().formatHex(key))))
                .as("hits of the secret").isZero();
        // Control, a run of its own: under S4 a plain file next to the key would be uploaded with it.
        assertThat(cmdlineHits(env, RunDirectory.builder().variable("AIMON_RUN_DIR").file("plain", plain).build(),
                List.of(Base64.getEncoder().encodeToString(plain)))).as("hits of the inlined control").isPositive();
    }

    /**
     * Runs an isolated streamed command with {@code run} — the shell engine's call shape — that waits for a release
     * file, and while it waits counts the command lines in the sandbox that hold any of {@code patterns}. The patterns
     * reach the counting {@code grep} in a file, never on its own command line.
     */
    private static long cmdlineHits(ExecutionEnvironment env, RunDirectory run, List<String> patterns)
            throws Exception {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final String started = "/workspace/started-" + id;
        final String release = "/workspace/release-" + id;
        final String patternFile = "/workspace/patterns-" + id;
        env.fileSystem().write(patternFile, String.join("\n", patterns) + "\n");
        final CompletableFuture<ShellCommandResult> running = CompletableFuture.supplyAsync(() -> {
            try {
                return env.shell().execute(
                        () -> "touch " + started + "; while [ ! -e " + release + " ]; do sleep 0.1; done",
                        ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).isolated(true).runDirectory(run)
                                .build(),
                        line -> {
                        });
            } catch (ShellExecutionException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!env.fileSystem().exists(started) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(env.fileSystem().exists(started)).as("the run started").isTrue();
            return Long
                    .parseLong(
                            bash(env,
                                    "for f in /proc/[0-9]*/cmdline; do tr '\\0' '\\n' < \"$f\"; done"
                                            + " 2>/dev/null | grep -cF -f " + patternFile + " || true")
                                    .stdout().strip());
        } finally {
            env.fileSystem().write(release, "");
            assertThat(running.get(30, TimeUnit.SECONDS).exitCode()).isZero();
        }
    }
}
