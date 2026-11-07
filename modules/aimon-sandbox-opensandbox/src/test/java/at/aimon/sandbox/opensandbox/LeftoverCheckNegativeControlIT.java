package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.programmatic.testkit.PythonPrograms;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

/**
 * The negative control of {@link ShellEngineContractIT}'s leftover check (docs/design/ptc-shell-provider.md §9): the
 * same detector, on a sandbox of its own so the shared one stays clean, turns red for what a broken runtime would
 * leave. A pre-#336 core cannot be pinned, and core is not modified here, so this shows that the check detects an
 * escaped process and an unreaped zombie — not that an old core fails it.
 */
@Tag("docker")
class LeftoverCheckNegativeControlIT {

    private static SharedSandbox sandbox;

    @BeforeAll
    static void start() throws Exception {
        sandbox = SharedSandbox.started();
    }

    @AfterAll
    static void stop() {
        if (sandbox != null) {
            sandbox.close();
        }
    }

    /**
     * The real test's program, except that its child starts a session of its own: outside the exec's group and the
     * runtime's, as the pre-#336 runtime was, so neither kill reaches it. The real test's own {@code awaitNone} must
     * fail on it, naming the child.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("an escaped child of a timed-out run fails the leftover check, named by its mark")
    void anEscapedChildIsCaught() throws Exception {
        final String mark = "62" + ThreadLocalRandom.current().nextInt(1000, 10000);
        final Set<Integer> baseline = SandboxLeftovers.baseline(SandboxLeftovers.snapshot(sandbox::bash),
                SandboxLeftovers.leftOver(mark));
        final CodeExecutionOutcome outcome;
        try (ScriptedToolCallBridge bridge = ScriptedToolCallBridge.succeedingWith("unused");
                CodeExecutionEngine engine = ShellCodeExecutionEngine.create()) {
            outcome = engine.execute(
                    CodeExecutionRequest.builder().toolUseId("toolu_control")
                            .code("import subprocess\nsubprocess.Popen(['sleep', '" + mark
                                    + "'], start_new_session=True)\n" + new PythonPrograms().spinForever())
                            .limits(CodeExecutionLimits.builder().maxStatements(Long.MAX_VALUE).build())
                            .wallClock(Duration.ofSeconds(2)).cancellation(bridge.signal()).build(),
                    bridge,
                    ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, sandbox.environment).build());
        }
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.TIMED_OUT);

        assertThatThrownBy(() -> SandboxLeftovers.awaitNone(sandbox::bash, baseline, SandboxLeftovers.leftOver(mark),
                "the escaped run")).isInstanceOf(AssertionError.class).hasMessageContaining("sleep " + mark)
                .hasMessageContaining("new since this run");

        final ShellCommandResult killed = sandbox.bash("pkill -x -f 'sleep " + mark + "'");
        assertThat(killed.exitCode()).as("killing the escaped child: %s", killed.stderr()).isZero();
        SandboxLeftovers.awaitNone(sandbox::bash, baseline, SandboxLeftovers.leftOver(mark), "the cleanup");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("an unreaped zombie fails the leftover check, named with the parent that did not reap it")
    void anUnreapedZombieIsCaught() throws Exception {
        final Set<Integer> baseline = SandboxLeftovers.baseline(SandboxLeftovers.snapshot(sandbox::bash),
                SandboxLeftovers.leftOver(null));
        final SandboxLeftovers.Zombie zombie = SandboxLeftovers.startUnreapedZombie(sandbox::bash);

        assertThat(SandboxLeftovers.describeNew(SandboxLeftovers.snapshot(sandbox::bash), baseline,
                SandboxLeftovers.leftOver(null))).contains(zombie.zombie() + " " + zombie.parent() + " ")
                .contains("[parent: python3 -c").contains("aimon-zombie-parent]");

        assertThat(sandbox.bash("kill " + zombie.parent()).exitCode()).isZero();
        SandboxLeftovers.awaitGone(sandbox::bash, Set.of(zombie.parent(), zombie.zombie()), "after killing the parent");
    }
}
