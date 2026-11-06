package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellKillCause;
import at.aimon.core.shell.ShellLineListener;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.ExecKillCause;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.RunningCommand;

/**
 * S8 at the {@link SandboxShell} boundary (docs/design/ptc-shell-provider.md §5.4): a provider outcome that carries
 * {@link ExecKillCause#LINE_LIMIT_EXCEEDED} becomes a result with {@link ShellKillCause#LINE_LIMIT_EXCEEDED} for an
 * isolated command that is not a hook's — the call shape of core's shell engine — and an exception naming the cause
 * for every other stream run. The provider's outcome is scripted; the command itself runs on the local provider.
 */
class SandboxShellKillCauseTest {

    private final RecordingProvider[] recording = new RecordingProvider[1];
    private final SandboxHarness harness = SandboxHarness.builder()
            .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build();
    private ExecutionEnvironment env;

    @BeforeEach
    void setUp() throws Exception {
        env = harness.mainTurn(SessionId.generate(), ALICE);
        bash(env, "true");
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /**
     * Replaces every later command's outcome, after it ran, as a provider that ended it for a long line reports.
     * Without a trailer, the wrapper is also replaced by a script that prints none, so the stream carries none either.
     */
    private void lineKill(boolean trailer, Duration delay) {
        if (!trailer) {
            recording[0].rewrite(spec -> ExecSpec.builder().command("printf 'partial\\n'")
                    .workingDirectory(spec.workingDirectory().orElse(null)).environment(spec.environment())
                    .timeout(spec.timeout()).maxCaptureBytes(spec.maxCaptureBytes())
                    .transientOutput(spec.transientOutput()).build());
        }
        final UnaryOperator<ExecOutcome> outcome = trailer
                ? SandboxShellKillCauseTest::withTrailer
                : SandboxShellKillCauseTest::withoutTrailer;
        recording[0].commands(command -> new RunningCommand() {
            @Override
            public ExecOutcome await(Duration timeout) throws InterruptedException {
                final ExecOutcome real = command.await(timeout);
                Thread.sleep(delay.toMillis());
                return outcome.apply(real);
            }

            @Override
            public void kill() {
                command.kill();
            }
        });
    }

    /** The trailer never arrived: the provider killed the wrapper. */
    private static ExecOutcome withoutTrailer(ExecOutcome real) {
        return ExecOutcome.builder().exitCode(137).stdout("partial\n".getBytes(StandardCharsets.UTF_8))
                .stdoutTruncated(true).stderrTruncated(true).killCause(ExecKillCause.LINE_LIMIT_EXCEEDED).build();
    }

    /** The trailer arrived, then the provider ended the exec for a line after it. */
    private static ExecOutcome withTrailer(ExecOutcome real) {
        return ExecOutcome.builder().exitCode(real.exitCode()).stdout(real.stdout()).stderr(real.stderr())
                .stdoutTruncated(true).stderrTruncated(true).killCause(ExecKillCause.LINE_LIMIT_EXCEEDED).build();
    }

    private static ExecutionOptions.Builder isolated() {
        return ExecutionOptions.builder().isolated(true).timeout(Duration.ofSeconds(20));
    }

    private static final ShellLineListener LISTENER = line -> {
    };

    @Test
    @DisplayName("isolated, streamed, not a hook: a result with exit 137, truncated, and the kill cause")
    void theEngineShapeGetsAResult() throws Exception {
        lineKill(false, Duration.ZERO);

        final ShellCommandResult result = env.shell().execute(() -> "echo partial", isolated().build(), LISTENER);

        assertThat(result.exitCode()).isEqualTo(137);
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.stdout()).isEqualTo("partial\n");
        assertThat(result.killCause()).contains(ShellKillCause.LINE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("isolated without a listener (the engine's probe shape): a result with the kill cause too")
    void anIsolatedRunWithoutAListenerGetsAResult() throws Exception {
        lineKill(false, Duration.ZERO);

        final ShellCommandResult result = bash(env, "echo partial", isolated().build());

        assertThat(result.killCause()).contains(ShellKillCause.LINE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("an isolated hook: an exception whose message names the line limit")
    void anIsolatedHookFails() {
        lineKill(false, Duration.ZERO);

        assertThatThrownBy(() -> bash(env, "echo partial", isolated().hook(true).build()))
                .isExactlyInstanceOf(ShellExecutionException.class).hasMessageContaining("exit 137")
                .hasMessageContaining(
                        "the provider ended the command: an output line exceeded the provider's line " + "limit");
    }

    @Test
    @DisplayName("a streamed background command (a monitor): an exception naming the cause")
    void aMonitorFails() {
        lineKill(false, Duration.ZERO);

        assertThatThrownBy(() -> env.shell().execute(() -> "echo partial",
                ExecutionOptions.builder().background(true).timeout(Duration.ofSeconds(20)).build(), LISTENER))
                .isExactlyInstanceOf(ShellExecutionException.class).hasMessageContaining("line limit");
    }

    @Test
    @DisplayName("past the command's timeout, the provider's kill still wins over the clock")
    void theKillCauseGoesBeforeTheClock() throws Exception {
        lineKill(false, Duration.ofMillis(300));

        final ShellCommandResult result = env.shell().execute(() -> "echo partial",
                isolated().timeout(Duration.ofMillis(100)).build(), LISTENER);

        assertThat(result.exitCode()).isEqualTo(137);
        assertThat(result.killCause()).contains(ShellKillCause.LINE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("a trailer and a kill cause on an isolated run: the trailer's exit code, with the cause attached")
    void aTrailerKeepsItsExitCodeAndGetsTheCause() throws Exception {
        lineKill(true, Duration.ZERO);

        final ShellCommandResult result = env.shell().execute(() -> "echo done; exit 3", isolated().build(), LISTENER);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.killCause()).contains(ShellKillCause.LINE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("a trailer and a kill cause on a hook: the trailer's result, unchanged")
    void aHookWithATrailerIsUnchanged() throws Exception {
        lineKill(true, Duration.ZERO);

        final ShellCommandResult result = bash(env, "echo done; exit 2", isolated().hook(true).build());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.killCause()).isEmpty();
    }

    @Test
    @DisplayName("every provider cause maps onto a core cause")
    void everyCauseMaps() {
        for (ExecKillCause cause : ExecKillCause.values()) {
            assertThat(SandboxShell.shellKillCause(cause)).isNotNull();
        }
    }

    @Test
    @DisplayName("an outcome is never both timed out and killed by the provider")
    void anOutcomeIsNotBoth() {
        assertThatThrownBy(
                () -> ExecOutcome.builder().timedOut(true).killCause(ExecKillCause.LINE_LIMIT_EXCEEDED).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ExecOutcome.builder().killCause(ExecKillCause.LINE_LIMIT_EXCEEDED).build().toString())
                .contains("killCause=LINE_LIMIT_EXCEEDED");
        assertThat(ExecOutcome.builder().build().killCause()).isEmpty();
    }
}
