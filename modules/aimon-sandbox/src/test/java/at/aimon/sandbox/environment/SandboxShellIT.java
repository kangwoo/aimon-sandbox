package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeBinding;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.binding.ShellKey;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.workspace.WorkspaceScan;

/**
 * The shell rows of docs/design/workspace-sandbox.md §16 and the wrapper's robustness cases, through the local
 * provider.
 */
class SandboxShellIT {

    private final SandboxHarness harness = SandboxHarness.standard();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private SessionId session;
    private ExecutionEnvironment env;

    @BeforeEach
    void setUp() throws Exception {
        session = SessionId.generate();
        env = harness.mainTurn(session, ALICE);
        bash(env, "true");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        harness.close();
    }

    private static ExecutionOptions timeout(Duration timeout) {
        return ExecutionOptions.builder().timeout(timeout).build();
    }

    /** Waits until a command has created a file: the command is running and holds what it holds. */
    private void awaitHostFile(String sandboxPath) throws InterruptedException {
        awaitHostFile(harness, session, sandboxPath);
    }

    private static void awaitHostFile(SandboxHarness in, SessionId of, String sandboxPath) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(in.host(of, sandboxPath)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(Files.exists(in.host(of, sandboxPath))).as(sandboxPath).isTrue();
    }

    private static ExecutionOptions.Builder cancellable(ShellCancellationSource source) {
        return ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).cancellation(source.token());
    }

    /** The failure of a command run on the executor, unwrapped. */
    private static Throwable failureOf(Future<ShellCommandResult> command) throws Exception {
        try {
            command.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        throw new AssertionError("the command returned a result");
    }

    private static void awaitDead(long pid) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("process " + pid + " is alive")
                .isFalse();
    }

    private String stateFile() {
        return ShellWrapper.directory(ShellKey.session(session).directoryName()) + "/state";
    }

    @Test
    @DisplayName("§16: A export FOO=A → B echo $FOO is empty (shell state is per shell key)")
    void shellStateIsIsolatedPerShellKey() throws Exception {
        final ExecutionEnvironment fork = harness.fork(env, ALICE);

        bash(env, "export FOO=A");
        final ShellCommandResult other = bash(fork, "echo \"[$FOO]\"");
        final ShellCommandResult same = bash(env, "echo \"[$FOO]\"");

        assertThat(other.stdout()).isEqualTo("[]\n");
        assertThat(same.stdout()).isEqualTo("[A]\n");
    }

    @Test
    @DisplayName("§16: cd src && export X=1 → next Bash has cwd src, X=1, no unexported variable")
    void cwdAndExportedVariablesPersistButPlainVariablesDoNot() throws Exception {
        bash(env, "mkdir -p src && cd src && export X=1; PLAIN=2");

        final ShellCommandResult next = bash(env, "pwd; echo \"X=$X PLAIN=$PLAIN\"");

        assertThat(next.stdout()).isEqualTo("/workspace/repo/src\nX=1 PLAIN=\n");
    }

    @Test
    @DisplayName("§16: timeout kill → the next command runs with the state from before, with a notice")
    void killedCommandLeavesPreviousStateAndNotice() throws Exception {
        bash(env, "cd /workspace && export KEEP=kept");

        assertThatThrownBy(() -> bash(env, "mkdir -p /workspace/lost && cd /workspace/lost; export LOST=1; sleep 10",
                timeout(Duration.ofSeconds(1)))).isInstanceOfSatisfying(ShellTimeoutException.class,
                        e -> assertThat(e.notices()).contains(SandboxShell.KILLED_NOTICE));
        final ShellCommandResult next = bash(env, "pwd; echo \"KEEP=$KEEP LOST=$LOST\"");

        assertThat(next.stdout()).isEqualTo("/workspace\nKEEP=kept LOST=\n");
    }

    @Test
    @DisplayName("§16: a command leaving `sleep 600 &` behind does not hold the next command")
    void leftoverDescendantDoesNotHoldTheLock() throws Exception {
        // No redirection: the leftover keeps every descriptor the command gave it — the hazard itself. The sandbox's
        // destroy (the harness's close) ends it.
        final ShellCommandResult first = bash(env, "sleep 600 & echo $!");
        final long leftover = Long.parseLong(first.stdout().strip());
        assertThat(ProcessHandle.of(leftover).map(ProcessHandle::isAlive)).as("the leftover still runs").contains(true);

        final long started = System.nanoTime();
        final ShellCommandResult next = bash(env, "echo next");

        assertThat(next.stdout()).isEqualTo("next\n");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("§16: time waiting for the shell lock is not counted in the command timeout")
    void lockWaitIsNotCountedInCommandTimeout() throws Exception {
        // The holder is not a command of this node: it takes the in-sandbox lock directly, so the only thing that
        // makes the next command wait is the wrapper's `flock -w`, never the node-local lock.
        final String lock = ShellWrapper.directory(ShellKey.session(session).directoryName()) + "/lock";
        final var connection = harness.local.connect(harness.primary(session).providerRef().orElseThrow());
        final var holder = connection.run(
                at.aimon.sandbox.provider.ExecSpec.builder()
                        .command("exec 9>" + lock + "; flock 9; : > /workspace/holding; sleep 1.5").build(),
                at.aimon.sandbox.provider.OutputSink.DISCARD);
        awaitHostFile("/workspace/holding");

        final long started = System.nanoTime();
        final ShellCommandResult waited = bash(env, "sleep 1; echo ran", timeout(Duration.ofSeconds(2)));

        assertThat(waited.stdout()).isEqualTo("ran\n");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(2000));
        assertThat(holder.await(Duration.ofSeconds(10)).exitCode()).isZero();
    }

    @Test
    @DisplayName("§16: a running background command does not make the next Bash wait")
    void backgroundCommandTakesNoLock() throws Exception {
        final Future<ShellCommandResult> background = executor.submit(() -> bash(env, "sleep 3; echo done",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).background(true).build()));
        Thread.sleep(300);

        final long started = System.nanoTime();
        final ShellCommandResult quick = bash(env, "echo quick");

        assertThat(quick.stdout()).isEqualTo("quick\n");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        assertThat(background.isDone()).isFalse();
        assertThat(background.get(20, TimeUnit.SECONDS).stdout()).isEqualTo("done\n");
    }

    @Test
    void backgroundCommandReadsStateButDoesNotSaveIt() throws Exception {
        bash(env, "export SEEN=yes");

        final ShellCommandResult background = bash(env, "echo $SEEN; export BG=1; cd /",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).background(true).build());
        final ShellCommandResult next = bash(env, "pwd; echo \"BG=$BG\"");

        assertThat(background.stdout()).isEqualTo("yes\n");
        assertThat(next.stdout()).isEqualTo("/workspace/repo\nBG=\n");
    }

    @Test
    void aCommandChangingIfsKeepsEveryPersistedExport() throws Exception {
        bash(env, "export FOO=1 BAR='two words'");

        bash(env, "IFS=,; mkdir -p sub; cd sub");
        final ShellCommandResult next = bash(env, "echo \"$FOO|$BAR\"; pwd");

        assertThat(next.stdout()).isEqualTo("1|two words\n/workspace/repo/sub\n");
    }

    @Test
    void tracingInTheCommandDoesNotLeakTheSaveIntoStderr() throws Exception {
        final ShellCommandResult traced = bash(env, "set -x; export T=1");

        assertThat(traced.stderr()).doesNotContain("__aimon");
        assertThat(bash(env, "echo $T").stdout()).isEqualTo("1\n");
    }

    @Test
    void commandTextIsDataForTheWrapper() throws Exception {
        assertThat(bash(env, "echo hi # trailing comment").stdout()).isEqualTo("hi\n");
        assertThat(bash(env, "cat > heredoc.txt <<EOF\nline one\nEOF\ncat heredoc.txt").stdout())
                .isEqualTo("line one\n");
        assertThat(bash(env, "echo 'it'\"'\"'s'; echo $'a\\tb'").stdout()).isEqualTo("it's\na\tb\n");
    }

    @Test
    void unbalancedQuoteIsTheCommandsOwnSyntaxErrorAndStateSurvives() throws Exception {
        bash(env, "cd /workspace && export BEFORE=1");

        final ShellCommandResult broken = bash(env, "echo \"oops");
        final ShellCommandResult next = bash(env, "pwd; echo $BEFORE");

        assertThat(broken.exitCode()).isNotZero();
        assertThat(broken.stderr()).contains("unexpected EOF");
        assertThat(next.stdout()).isEqualTo("/workspace\n1\n");
    }

    @Test
    void commandsOwnExitCodesAreReportedAsTheCommands() throws Exception {
        assertThat(bash(env, "exit 75").exitCode()).isEqualTo(75);
        assertThat(bash(env, "exit 137").exitCode()).isEqualTo(137);
        final ShellCommandResult zero = bash(env, "echo x; exit 0");
        assertThat(zero.exitCode()).isZero();
        assertThat(zero.stdout()).isEqualTo("x\n");
        final ShellCommandResult three = bash(env, "echo x; exit 3");
        assertThat(three.exitCode()).isEqualTo(3);
        assertThat(three.stdout()).isEqualTo("x\n");
        final ShellCommandResult setE = bash(env, "set -e; false; echo after");
        assertThat(setE.exitCode()).isEqualTo(1);
        assertThat(setE.stdout()).isEmpty();
    }

    @Test
    void cdAndExportBeforeAnExitPersist() throws Exception {
        final ShellCommandResult exited = bash(env, "mkdir -p /workspace/e && cd /workspace/e && export E=1; exit 3");
        final ShellCommandResult next = bash(env, "pwd; echo $E");

        assertThat(exited.exitCode()).isEqualTo(3);
        assertThat(next.stdout()).isEqualTo("/workspace/e\n1\n");
    }

    @Test
    void interruptKillsTheCommandAndKeepsThePreviousState() throws Exception {
        bash(env, "cd /workspace && export Y=0");
        final AtomicReference<Thread> runner = new AtomicReference<>();
        final CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        final Thread thread = new Thread(() -> {
            try {
                bash(env, "mkdir -p /workspace/x && cd /workspace/x; export Y=1; : > /workspace/running; sleep 5");
                outcome.complete(null);
            } catch (Throwable e) {
                outcome.complete(e);
            }
        });
        runner.set(thread);
        thread.start();
        awaitHostFile("/workspace/running");

        runner.get().interrupt();
        final Throwable failure = outcome.get(10, TimeUnit.SECONDS);

        assertThat(failure).isInstanceOf(ShellExecutionException.class).isNotInstanceOf(ShellTimeoutException.class)
                .hasMessageContaining("interrupted");
        assertThat(((ShellExecutionException) failure).notices()).contains(SandboxShell.KILLED_NOTICE);
        assertThat(bash(env, "pwd; echo $Y").stdout()).isEqualTo("/workspace\n0\n");
    }

    @Test
    void redirectErrorStreamInterleavesIntoStdout() throws Exception {
        final ShellCommandResult merged = bash(env, "echo a; echo b >&2; echo c",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).redirectErrorStream(true).build());

        assertThat(merged.stdout()).isEqualTo("a\nb\nc\n");
        assertThat(merged.stderr()).isEmpty();
    }

    @Test
    void nulInTheCommandIsRejectedBeforeAnythingRuns() {
        final int runs = harness.faults.calls(Operation.RUN);

        assertThatThrownBy(() -> bash(env, "echo a\0b")).isInstanceOf(ShellExecutionException.class)
                .hasMessageContaining("NUL");
        assertThat(harness.faults.calls(Operation.RUN)).isEqualTo(runs);
    }

    @Test
    void positionalParametersSetByTheCommandDoNotRedirectTheSave() throws Exception {
        final Path elsewhere = Path.of("/tmp", "aimon-zz-" + UUID.randomUUID());

        bash(env, "set -- " + elsewhere + "; export Z=3");
        bash(env, "mkdir -p src; set -- src; export Z2=4");

        assertThat(Files.exists(elsewhere)).isFalse();
        assertThat(Files.exists(harness.host(session, "/workspace/repo/src/state"))).isFalse();
        assertThat(bash(env, "echo \"$Z $Z2\"").stdout()).isEqualTo("3 4\n");
    }

    @Test
    void assigningTheBaseVariableDoesNotWidenTheSave() throws Exception {
        bash(env, "base=x; export Z=3");

        final String state = harness.hostFile(session, stateFile());

        assertThat(state).contains("export Z=3").doesNotContain("export PATH=").doesNotContain("export HOME=");
    }

    @Test
    void reservedVariablesAreReadOnlyAndTheStateIsStillSaved() throws Exception {
        bash(env, "export KEPT=1");

        final ShellCommandResult attempt = bash(env, "__aimon_dir=/tmp; export AFTER=1");

        assertThat(attempt.stderr()).contains("readonly variable");
        assertThat(bash(env, "echo \"$KEPT\"").stdout()).isEqualTo("1\n");
    }

    @Test
    void quickCommandsLeaveNoWatchdogBehind() throws Exception {
        // A timeout no other run picks: pgrep sees the whole host, where other worktrees may run this very test.
        final long millis = 1_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextLong(8_000_000);
        final ExecutionOptions options = timeout(Duration.ofMillis(millis));
        for (int i = 0; i < 10; i++) {
            bash(env, "true", options);
        }
        final String watchdog = "sleep " + (millis / 1000) + "."
                + String.format(java.util.Locale.ROOT, "%03d", millis % 1000);
        final Process pgrep = new ProcessBuilder("pgrep", "-f", watchdog).start();
        pgrep.waitFor(5, TimeUnit.SECONDS);

        assertThat(new String(pgrep.getInputStream().readAllBytes()).strip()).isEmpty();
    }

    @Test
    void stderrAtTheCapKeepsTheTrailerAndOnlyOverflowTruncates() throws Exception {
        final ExecutionOptions capped = ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).maxCaptureBytes(100)
                .build();

        final ShellCommandResult exact = bash(env, "head -c 100 /dev/zero | tr '\\0' e >&2; exit 4", capped);
        final ShellCommandResult over = bash(env, "head -c 110 /dev/zero | tr '\\0' e >&2; exit 4", capped);

        assertThat(exact.exitCode()).isEqualTo(4);
        assertThat(exact.stderr()).hasSize(100);
        assertThat(exact.outputTruncated()).isFalse();
        assertThat(over.exitCode()).isEqualTo(4);
        assertThat(over.stderr()).hasSize(100);
        assertThat(over.outputTruncated()).isTrue();
    }

    @Test
    void perCommandWorkingDirectoryAndEnvironmentDoNotTouchTheState() throws Exception {
        bash(env, "mkdir -p /workspace/repo/sub");

        final ShellCommandResult scoped = bash(env, "pwd; echo $ONLY",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).workingDirectory("/workspace/repo/sub")
                        .environment(java.util.Map.of("ONLY", "here")).build());
        final ShellCommandResult next = bash(env, "pwd; echo \"[$ONLY]\"");

        assertThat(scoped.stdout()).isEqualTo("/workspace/repo/sub\nhere\n");
        assertThat(next.stdout()).isEqualTo("/workspace/repo\n[]\n");
    }

    @Test
    void stdinIsFedFromAFile() throws Exception {
        final ShellCommandResult read = bash(env, "cat",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).stdin("from stdin").build());

        assertThat(read.stdout()).isEqualTo("from stdin");
    }

    @Test
    void aVanishedWorkingDirectoryFallsBackToTheRootWithANotice() throws Exception {
        bash(env, "mkdir -p /workspace/gone && cd /workspace/gone");
        bash(env, "rm -rf /workspace/gone");

        final ShellCommandResult next = bash(env, "pwd");

        assertThat(next.stdout()).isEqualTo("/workspace/repo\n");
        assertThat(next.notices()).anyMatch(notice -> notice.contains("no longer exists"));
    }

    private void assertNoRunFiles() throws Exception {
        final Path shellDir = harness.host(session, ShellWrapper.directory(ShellKey.session(session).directoryName()));
        try (var files = Files.list(shellDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith("run-"));
        }
    }

    @Test
    void runFilesAreRemovedAfterEachCommand() throws Exception {
        bash(env, "echo out; echo err >&2");

        assertNoRunFiles();
    }

    @Test
    @DisplayName("the wrapper removes its own run files: a normal command makes no per-file cleanup calls")
    void theNormalPathMakesNoPerFileCleanupCalls() throws Exception {
        harness.faults.resetCounts();

        final ShellCommandResult result = bash(env, "cat; echo err >&2; cd /workspace",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).stdin("in").build());

        assertThat(result.stdout()).isEqualTo("in");
        assertThat(harness.faults.calls(Operation.FILES_STAT)).as("stat calls").isZero();
        assertThat(harness.faults.calls(Operation.FILES_DELETE)).as("delete calls").isZero();
        assertNoRunFiles();
    }

    @Test
    void aTimedOutCommandsRunFilesAreRemovedFromTheJvm() throws Exception {
        harness.faults.resetCounts();
        assertThatThrownBy(() -> bash(env, "echo partial; sleep 10", timeout(Duration.ofSeconds(1))))
                .isInstanceOfSatisfying(ShellTimeoutException.class,
                        e -> assertThat(e.stdout()).isEqualTo("partial\n"));

        assertThat(harness.faults.calls(Operation.FILES_DELETE)).isPositive();
        assertNoRunFiles();
    }

    @Test
    void anExportedPathWithoutMvOrNoPathAtAllKeepsTheState() throws Exception {
        final ShellCommandResult broken = bash(env,
                "mkdir -p /workspace/p && cd /workspace/p; export BAR=2; export PATH=/nonexistent");
        final ShellCommandResult next = bash(env, "pwd; echo \"$BAR|$PATH\"");
        final ShellCommandResult unset = bash(env,
                "export PATH=/usr/bin:/bin; cd /workspace; export BAZ=3; unset PATH");
        final ShellCommandResult after = bash(env, "pwd; echo \"$BAZ|${PATH-unset}\"");

        assertThat(broken.stderr()).doesNotContain("command not found");
        assertThat(next.stdout()).isEqualTo("/workspace/p\n2|/nonexistent\n");
        assertThat(unset.stderr()).doesNotContain("command not found");
        // The save still ran: cd and BAZ persist, and an unset PATH is simply not saved, so the base one returns.
        assertThat(after.stdout()).startsWith("/workspace\n3|").doesNotContain("/nonexistent").doesNotContain("unset");
        final Path shellDir = harness.host(session, ShellWrapper.directory(ShellKey.session(session).directoryName()));
        try (var files = Files.list(shellDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith("state."));
        }
    }

    @Test
    void aDebugTrapInTheCommandDoesNotTraceTheSave() throws Exception {
        final ShellCommandResult traced = bash(env,
                "set -T; trap 'echo \"DBG $BASH_COMMAND\" >&2' DEBUG RETURN; export T=1");

        assertThat(traced.stderr()).contains("DBG export T=1").doesNotContain("__aimon").doesNotContain("set +");
        assertThat(bash(env, "echo $T").stdout()).isEqualTo("1\n");
    }

    @Test
    void verboseModeLeaksAtMostTheOneLineExitTrap() throws Exception {
        final ShellCommandResult verbose = bash(env, "set -v; export V=1");

        assertThat(verbose.stderr().lines().filter(line -> line.contains("__aimon"))).hasSizeLessThanOrEqualTo(1);
        assertThat(verbose.stderr()).doesNotContain("declare").doesNotContain("compgen");
        assertThat(bash(env, "echo $V").stdout()).isEqualTo("1\n");
    }

    @Test
    void aCommandThatRemovesTheShellDirectoryIsReportedNotAnEmptySuccess() throws Exception {
        assertThatThrownBy(() -> bash(env, "echo x; rm -rf /workspace/.aimon-shell"))
                .isInstanceOf(ShellExecutionException.class).hasMessageContaining("output is lost");

        assertThat(bash(env, "echo again").stdout()).isEqualTo("again\n");
    }

    @Test
    void aCommandRemovingTheWorkingRootLeavesTheNextCommandRunnable() throws Exception {
        bash(env, "rm -rf /workspace/repo");

        final ShellCommandResult next = bash(env, "pwd");

        // The exec itself runs in /workspace, so an exec server that refuses a missing directory never sees one.
        assertThat(next.stdout()).isEqualTo("/workspace\n");
        assertThat(next.notices()).anyMatch(notice -> notice.contains("/workspace/repo no longer exist")
                && notice.contains("the command ran in /workspace"));
    }

    @Test
    void aVanishedWorkingDirectoryWithAPerCommandDirectorySaysWhereTheShellContinues() throws Exception {
        bash(env, "mkdir -p /workspace/gone /workspace/elsewhere && cd /workspace/gone");
        bash(env, "rm -rf /workspace/gone");

        final ShellCommandResult scoped = bash(env, "pwd", ExecutionOptions.builder().timeout(Duration.ofSeconds(20))
                .workingDirectory("/workspace/elsewhere").build());

        assertThat(scoped.stdout()).isEqualTo("/workspace/elsewhere\n");
        assertThat(scoped.notices())
                .anyMatch(notice -> notice.contains("no longer exists")
                        && notice.contains("the shell continues in /workspace/repo"))
                .noneMatch(notice -> notice.contains("ran in"));
    }

    @Test
    void nulInAVariableValueOrTheWorkingDirectoryIsRejectedBeforeAnythingRuns() {
        final int runs = harness.faults.calls(Operation.RUN);

        assertThatThrownBy(() -> bash(env, "true",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).environment(java.util.Map.of("BAD", "a\0b"))
                        .build()))
                .isInstanceOf(ShellExecutionException.class).hasMessageContaining("NUL").hasMessageContaining("BAD");
        assertThatThrownBy(() -> bash(env, "true",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).workingDirectory("/workspace/a\0b").build()))
                .isInstanceOf(ShellExecutionException.class).hasMessageContaining("NUL");
        assertThat(harness.faults.calls(Operation.RUN)).isEqualTo(runs);
    }

    @Test
    void anOversizedEnvironmentIsRefusedAsABadArgumentBeforeAnythingRuns() {
        harness.faults.resetCounts();
        final String large = "v".repeat(ShellWrapper.INLINE_ENVIRONMENT_LIMIT);

        assertThatThrownBy(() -> bash(env, "true",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).environment(java.util.Map.of("BIG", large))
                        .build()))
                .isInstanceOf(ShellExecutionException.class).hasMessageContaining("too large to pass to the sandbox")
                .hasMessageNotContaining("cannot be reached");
        assertThat(harness.faults.calls(Operation.RUN)).isZero();
    }

    @Test
    void anEnvironmentJustUnderTheLimitStillRuns() throws Exception {
        final String value = "v".repeat(ShellWrapper.INLINE_ENVIRONMENT_LIMIT - 16);

        final ShellCommandResult result = bash(env, "printf %s \"${#BIG}\"", ExecutionOptions.builder()
                .timeout(Duration.ofSeconds(20)).environment(java.util.Map.of("BIG", value)).build());

        assertThat(result.stdout()).isEqualTo(String.valueOf(value.length()));
    }

    @Test
    void theCommandNeverTravelsAsAnArgumentAndALargeOneIsUploaded() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment otherEnv = recorded.mainTurn(other, ALICE);
            final String payload = "x".repeat(200 * 1024);

            final ShellCommandResult large = bash(otherEnv,
                    "cat > big.txt <<'EOF'\n" + payload + "\nEOF\nwc -c < big.txt");
            final ShellCommandResult small = bash(otherEnv, "echo small");

            assertThat(large.stdout().strip()).isEqualTo(String.valueOf(payload.length() + 1));
            assertThat(small.stdout()).isEqualTo("small\n");
            // Linux refuses one argument over 128 KiB (MAX_ARG_STRLEN); the exec's script is one.
            assertThat(recording[0].runs).allSatisfy(
                    spec -> assertThat(spec.command().length()).as("exec argument length").isLessThan(128 * 1024));
            assertThat(recording[0].runs).noneMatch(spec -> spec.command().contains("\"$r\" \"$__aimon_cmd\""));
        }
    }

    @Test
    void aNullTimeoutIsNoTimeoutNotTwoMinutes() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final ExecutionEnvironment otherEnv = recorded.mainTurn(SessionId.generate(), ALICE);
            bash(otherEnv, "true");
            recording[0].runs.clear();

            final ShellCommandResult untimed = bash(otherEnv, "echo untimed", ExecutionOptions.builder().build());

            assertThat(untimed.stdout()).isEqualTo("untimed\n");
            assertThat(recording[0].runs).singleElement().satisfies(spec -> {
                assertThat(spec.timeout()).isGreaterThanOrEqualTo(SandboxShell.NO_TIMEOUT_BACKSTOP);
                assertThat(spec.command()).as("no watchdog").doesNotContain("kill -KILL 0");
            });
        }
    }

    @Test
    void aFilesystemErrorPassingStdinIsTheCommandsFailure() {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness failing = SandboxHarness.builder().decorate((provider, clock) -> {
            recording[0] = new RecordingProvider(provider);
            recording[0].files(files -> new at.aimon.sandbox.FailingFiles(files, path -> path.endsWith(".in")));
            return recording[0];
        }).build()) {
            final ExecutionEnvironment failingEnv = failing.mainTurn(SessionId.generate(), ALICE);

            assertThatThrownBy(() -> bash(failingEnv, "cat",
                    ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).stdin("input").build()))
                    .isInstanceOf(ShellExecutionException.class).hasMessageContaining("could not pass stdin")
                    .hasMessageContaining("No space left on device");
        }
    }

    @Test
    void supportsCancellation() {
        assertThat(env.shell().supports(ShellFeature.CANCELLATION)).isTrue();
    }

    @Test
    @DisplayName("§16: cancelling a running background command kills it and its children, with the output so far")
    void cancellingARunningBackgroundCommandKillsItAndThrowsCancelled() throws Exception {
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Future<ShellCommandResult> background = executor.submit(() -> bash(env,
                "echo before; sleep 61 & echo \"$$ $!\" > /workspace/pids.tmp; mv /workspace/pids.tmp /workspace/pids;"
                        + " wait",
                cancellable(source).background(true).build()));
        awaitHostFile("/workspace/pids");
        final String[] pids = harness.hostFile(session, "/workspace/pids").strip().split(" ");

        final long started = System.nanoTime();
        source.cancel();
        final Throwable failure = failureOf(background);

        assertThat(failure).isInstanceOf(ShellCancelledException.class).hasMessage(SandboxShell.CANCELLED_MESSAGE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(((ShellCancelledException) failure).stdout()).isEqualTo("before\n");
        assertThat(((ShellCancelledException) failure).notices()).contains(SandboxShell.KILLED_NOTICE);
        awaitDead(Long.parseLong(pids[0]));
        awaitDead(Long.parseLong(pids[1]));
        assertNoRunFiles();
    }

    @Test
    @DisplayName("§16: cancelling a foreground command leaves the state from before and frees the shell")
    void cancellingARunningForegroundCommandKeepsThePreviousState() throws Exception {
        bash(env, "cd /workspace && export KEEP=kept");
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Future<ShellCommandResult> foreground = executor.submit(() -> bash(env,
                "mkdir -p /workspace/x && cd /workspace/x; export LOST=1; : > /workspace/running; sleep 60",
                cancellable(source).build()));
        awaitHostFile("/workspace/running");

        source.cancel();
        final Throwable failure = failureOf(foreground);
        final long started = System.nanoTime();
        final ShellCommandResult next = bash(env, "pwd; echo \"KEEP=$KEEP LOST=$LOST\"");

        assertThat(failure).isInstanceOf(ShellCancelledException.class).hasMessage(SandboxShell.CANCELLED_MESSAGE);
        assertThat(((ShellCancelledException) failure).notices()).contains(SandboxShell.KILLED_NOTICE);
        assertThat(next.stdout()).isEqualTo("/workspace\nKEEP=kept LOST=\n");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("the shell lock was released")
                .isLessThan(Duration.ofSeconds(5));
        assertNoRunFiles();
    }

    @Test
    void cancellingACommandThatIgnoresSigtermStillEndsIt() throws Exception {
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Future<ShellCommandResult> background = executor.submit(() -> bash(env,
                "trap '' TERM; echo $$ > /workspace/pid.tmp; mv /workspace/pid.tmp /workspace/pid; sleep 60",
                cancellable(source).background(true).build()));
        awaitHostFile("/workspace/pid");
        final long pid = Long.parseLong(harness.hostFile(session, "/workspace/pid").strip());

        source.cancel();

        assertThat(failureOf(background)).isInstanceOf(ShellCancelledException.class);
        // SIGKILL follows the SIGTERM it ignored.
        awaitDead(pid);
    }

    @Test
    @DisplayName("§16: a signal tripped before execute starts nothing — no sandbox, no record, no exec")
    void aSignalTrippedBeforeExecuteStartsNothing() {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final ExecutionEnvironment fresh = recorded.mainTurn(SessionId.generate(), ALICE);
            final ShellCancellationSource source = ShellCancellationSource.create();
            source.cancel();

            assertThatThrownBy(() -> bash(fresh, ": > /workspace/ran", cancellable(source).build()))
                    .isInstanceOf(ShellCancelledException.class)
                    .hasMessage(SandboxShell.CANCELLED_BEFORE_START_MESSAGE);

            assertThat(recorded.faults.calls(Operation.CREATE)).isZero();
            assertThat(recorded.store.scan(WorkspaceScan.builder().build())).isEmpty();
            assertThat(recording[0].runs).isEmpty();
        }
    }

    @Test
    @DisplayName("§16: a signal tripped while waiting for the shell lock starts nothing and uploads nothing")
    void aSignalTrippedWhileWaitingForTheLockStartsNothing() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        final List<String> written = new CopyOnWriteArrayList<>();
        try (SandboxHarness recorded = SandboxHarness.builder().decorate((provider, clock) -> {
            recording[0] = new RecordingProvider(provider);
            recording[0].files(files -> new at.aimon.sandbox.FailingFiles(files, path -> {
                written.add(path);
                return false;
            }));
            return recording[0];
        }).build()) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment otherEnv = recorded.mainTurn(other, ALICE);
            bash(otherEnv, "true");
            final Future<ShellCommandResult> holder = executor
                    .submit(() -> bash(otherEnv, ": > /workspace/holding; sleep 2"));
            awaitHostFile(recorded, other, "/workspace/holding");
            recording[0].runs.clear();
            written.clear();
            final ShellCancellationSource source = ShellCancellationSource.create();
            final Future<ShellCommandResult> waiting = executor.submit(
                    () -> bash(otherEnv, "cat > /workspace/second-ran", cancellable(source).stdin("x").build()));
            Thread.sleep(300);

            source.cancel();
            final Throwable failure = failureOf(waiting);

            assertThat(failure).isInstanceOf(ShellCancelledException.class)
                    .hasMessage(SandboxShell.CANCELLED_BEFORE_START_MESSAGE);
            assertThat(holder.get(20, TimeUnit.SECONDS).exitCode()).isZero();
            assertThat(Files.exists(recorded.host(other, "/workspace/second-ran"))).isFalse();
            assertThat(recording[0].runs).as("no exec for the cancelled command").isEmpty();
            assertThat(written).as("no run file uploaded for it").noneMatch(path -> path.endsWith(".in"));
        }
    }

    @Test
    void aSignalTrippedAfterTheCommandEndedChangesNothing() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final ExecutionEnvironment otherEnv = recorded.mainTurn(SessionId.generate(), ALICE);
            final ShellCancellationSource source = ShellCancellationSource.create();

            final ShellCommandResult result = bash(otherEnv, "echo done", cancellable(source).build());
            final int runs = recording[0].runs.size();
            source.cancel();

            assertThat(result.stdout()).isEqualTo("done\n");
            assertThat(recording[0].kills).as("the listener was removed with the command").hasValue(0);
            assertThat(recording[0].runs).hasSize(runs);
        }
    }

    @Test
    @DisplayName("§16: a cancel racing the command's own end returns the result when the trailer arrived")
    void cancelRacingCompletionReturnsTheResultWhenTheTrailerArrived() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final ExecutionEnvironment otherEnv = recorded.mainTurn(SessionId.generate(), ALICE);
            bash(otherEnv, "true");
            final ShellCancellationSource source = ShellCancellationSource.create();
            // The signal trips after the command ended and before the shell has looked at the outcome.
            recording[0].commands(command -> new RunningCommand() {
                @Override
                public ExecOutcome await(Duration timeout) throws InterruptedException {
                    final ExecOutcome outcome = command.await(timeout);
                    source.cancel();
                    return outcome;
                }

                @Override
                public void kill() {
                    command.kill();
                }
            });

            final ShellCommandResult result = bash(otherEnv, "echo raced; exit 3", cancellable(source).build());

            assertThat(result.stdout()).isEqualTo("raced\n");
            assertThat(result.exitCode()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("§16: a cancelled command that ends at its timeout — the kill request failed — is cancelled, not "
            + "timed out")
    void aCancelWhoseKillFailedIsStillCancelledWhenTheTimeoutEndsTheCommand() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment otherEnv = recorded.mainTurn(other, ALICE);
            bash(otherEnv, "true");
            recording[0].commands(command -> new RunningCommand() {
                @Override
                public ExecOutcome await(Duration timeout) throws InterruptedException {
                    return command.await(timeout);
                }

                @Override
                public void kill() {
                    // The request never reaches the sandbox.
                }
            });
            final ShellCancellationSource source = ShellCancellationSource.create();
            final Future<ShellCommandResult> command = executor.submit(() -> bash(otherEnv,
                    "echo partial; : > /workspace/running; sleep 30",
                    ExecutionOptions.builder().timeout(Duration.ofSeconds(2)).cancellation(source.token()).build()));
            awaitHostFile(recorded, other, "/workspace/running");

            source.cancel();
            final Throwable failure = failureOf(command);

            assertThat(failure).isInstanceOf(ShellCancelledException.class).hasMessage(SandboxShell.CANCELLED_MESSAGE);
            assertThat(((ShellCancelledException) failure).stdout()).isEqualTo("partial\n");
        }
    }

    @Test
    @DisplayName("a kill that lands after the wrapper printed the output and removed its run files keeps the output")
    void aCancelLandingInTheWrappersLastLinesReportsTheOutputFromTheStream() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final ExecutionEnvironment otherEnv = recorded.mainTurn(SessionId.generate(), ALICE);
            bash(otherEnv, "true");
            final ShellCancellationSource source = ShellCancellationSource.create();
            // The wrapper ran to its end — output on the stream, run files removed — but its trailer never arrived.
            recording[0].commands(command -> new RunningCommand() {
                @Override
                public ExecOutcome await(Duration timeout) throws InterruptedException {
                    final ExecOutcome outcome = command.await(timeout);
                    source.cancel();
                    final String stderr = new String(outcome.stderr(), java.nio.charset.StandardCharsets.UTF_8);
                    return ExecOutcome.builder().exitCode(143).stdout(outcome.stdout())
                            .stderr(stderr.substring(0, stderr.lastIndexOf("\nAIMON-"))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .build();
                }

                @Override
                public void kill() {
                    command.kill();
                }
            });

            assertThatThrownBy(() -> bash(otherEnv, "echo streamed; echo warned >&2", cancellable(source).build()))
                    .isInstanceOfSatisfying(ShellCancelledException.class, e -> {
                        assertThat(e.stdout()).isEqualTo("streamed\n");
                        assertThat(e.stderr()).isEqualTo("warned\n");
                    });
        }
    }

    @Test
    @DisplayName("§16: a background command given the environment's ceiling as its timeout ends there")
    void aBackgroundCommandEndsAtTheCeilingItIsGiven() throws Exception {
        try (SandboxHarness capped = SandboxHarness.builder().profile(at.aimon.sandbox.testkit.SandboxTestProfiles
                .local("standard").backgroundCommandTimeout(Duration.ofSeconds(1)).build()).build()) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment cappedEnv = capped.mainTurn(other, ALICE);
            final Duration ceiling = cappedEnv.backgroundCommandTimeout().orElseThrow();

            // What core's Bash does with the ceiling: it is the background command's timeout.
            assertThatThrownBy(() -> bash(cappedEnv, "echo $$ > /workspace/pid; echo started; sleep 60",
                    ExecutionOptions.builder().timeout(ceiling).background(true).build())).isInstanceOfSatisfying(
                            ShellTimeoutException.class, e -> assertThat(e.stdout()).isEqualTo("started\n"));

            assertThat(ceiling).isEqualTo(Duration.ofSeconds(1));
            awaitDead(Long.parseLong(capped.hostFile(other, "/workspace/pid").strip()));
        }
    }

    @Test
    @DisplayName("§16: closing a runtime binding stops no command and releases nothing another runtime uses")
    void closingARuntimeBindingLeavesRunningCommandsAlone() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final var provider = recorded.sandbox.environmentProvider();
            final AgentRuntimeId a = AgentRuntimeId.fromName("runtime-a");
            final AgentRuntimeId b = AgentRuntimeId.fromName("runtime-b");
            final RuntimeBinding first = provider.bindRuntime(a);
            final RuntimeBinding second = provider.bindRuntime(a);
            final RuntimeBinding ofB = provider.bindRuntime(b);
            final SessionId sessionA = SessionId.generate();
            final SessionId sessionB = SessionId.generate();
            final EnvironmentRequest requestA = EnvironmentRequest.builder().agentRuntimeId(a).sessionId(sessionA)
                    .principal(ALICE).build();
            final ExecutionEnvironment envA = recorded.resolve(requestA);
            final ExecutionEnvironment envB = recorded.resolve(
                    EnvironmentRequest.builder().agentRuntimeId(b).sessionId(sessionB).principal(ALICE).build());
            final ExecutionOptions background = ExecutionOptions.builder().timeout(Duration.ofSeconds(20))
                    .background(true).build();
            bash(envA, "true");
            bash(envB, "true");
            final Future<ShellCommandResult> commandA = executor
                    .submit(() -> bash(envA, ": > /workspace/running; sleep 2; echo a-done", background));
            final Future<ShellCommandResult> commandB = executor
                    .submit(() -> bash(envB, ": > /workspace/running; sleep 2; echo b-done", background));
            awaitHostFile(recorded, sessionA, "/workspace/running");
            awaitHostFile(recorded, sessionB, "/workspace/running");

            // An evicted runtime's handle, closed twice; a second binding of the same id is still in use.
            first.close();
            first.close();

            assertThat(bash(envA, "echo a-still").stdout()).isEqualTo("a-still\n");
            assertThat(bash(envB, "echo b-still").stdout()).isEqualTo("b-still\n");
            assertThat(commandA.get(20, TimeUnit.SECONDS).stdout()).isEqualTo("a-done\n");
            assertThat(commandB.get(20, TimeUnit.SECONDS).stdout()).isEqualTo("b-done\n");
            second.close();
            ofB.close();
            // resolve answers for an id whose bindings are all closed, as for one nobody bound.
            assertThat(bash(recorded.resolve(requestA), "cat /workspace/running; echo again").stdout())
                    .isEqualTo("again\n");
            assertThat(recorded.local.sandboxCount()).isEqualTo(2);
            assertThat(recording[0].kills).as("no command was killed").hasValue(0);
            assertThat(recording[0].connectionsClosed).as("no connection was closed").hasValue(0);
        }
    }
}
