package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.binding.ShellKey;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;

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
        final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(harness.host(session, sandboxPath)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(Files.exists(harness.host(session, sandboxPath))).as(sandboxPath).isTrue();
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
        final ShellCommandResult first = bash(env, "sleep 600 >/dev/null 2>&1 & echo $!");
        final long leftover = Long.parseLong(first.stdout().strip());
        try {
            final long started = System.nanoTime();
            final ShellCommandResult next = bash(env, "echo next");

            assertThat(next.stdout()).isEqualTo("next\n");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            ProcessHandle.of(leftover).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    @DisplayName("§16: time waiting for the shell lock is not counted in the command timeout")
    void lockWaitIsNotCountedInCommandTimeout() throws Exception {
        final Future<ShellCommandResult> holder = executor
                .submit(() -> bash(env, ": > /workspace/holding; sleep 1.5; echo held"));
        awaitHostFile("/workspace/holding");

        final long started = System.nanoTime();
        final ShellCommandResult waited = bash(env, "sleep 1; echo ran", timeout(Duration.ofSeconds(2)));

        assertThat(waited.stdout()).isEqualTo("ran\n");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(2000));
        assertThat(holder.get(10, TimeUnit.SECONDS).stdout()).isEqualTo("held\n");
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
        final ExecutionOptions options = timeout(Duration.ofMillis(17_321));
        for (int i = 0; i < 10; i++) {
            bash(env, "true", options);
        }
        final Process pgrep = new ProcessBuilder("pgrep", "-f", "sleep 17.321").start();
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

    @Test
    void runFilesAreRemovedAfterEachCommand() throws Exception {
        bash(env, "echo out; echo err >&2");

        final Path shellDir = harness.host(session, ShellWrapper.directory(ShellKey.session(session).directoryName()));
        try (var files = Files.list(shellDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith("run-"));
        }
    }
}
