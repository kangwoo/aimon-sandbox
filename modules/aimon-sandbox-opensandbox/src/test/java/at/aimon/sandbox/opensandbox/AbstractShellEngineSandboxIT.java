package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.programmatic.testkit.AbstractCodeExecutionEngineContractTest;
import at.aimon.programmatic.testkit.EnginePrograms;
import at.aimon.programmatic.testkit.PythonPrograms;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

/**
 * Core's engine contract suite with core's Python shell engine on one shared OpenSandbox sandbox (shell doc §14;
 * docs/design/ptc-shell-provider.md §5.7), and this module's checks of what a run leaves behind (§9 Q-C4). The docker
 * tier and both modes of the k8s tier extend it, each with a sandbox of its own.
 *
 * <p>
 * Every test, inherited ones included, is followed by a leftover check against a snapshot taken before it: a runtime,
 * a child of the program or a zombie that is new fails <em>that</em> test, by name, and one an earlier test left is
 * not blamed on it. {@link #aTimedOutRunLeavesNothingInTheSandbox} also shows, from inside the run, that the topology
 * aimon-core#336 promises was there: a runtime leading its own group, its anchor in another, and a watchdog.
 */
abstract class AbstractShellEngineSandboxIT extends AbstractCodeExecutionEngineContractTest {

    private Set<SandboxLeftovers.Key> before;

    /** @return the class's shared sandbox, started */
    protected abstract SharedSandbox sandbox();

    @Override
    protected CodeExecutionEngine engine() {
        return ShellCodeExecutionEngine.create();
    }

    @Override
    protected EnginePrograms programs() {
        return new PythonPrograms();
    }

    @Override
    protected ToolContext toolContext() {
        return ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, sandbox().environment).build();
    }

    @BeforeEach
    void snapshotLeftovers() throws Exception {
        before = SandboxLeftovers.baseline(SandboxLeftovers.snapshot(sandbox()::bash), SandboxLeftovers.leftOver(null));
    }

    @AfterEach
    void nothingNewIsLeft(TestInfo test) throws Exception {
        SandboxLeftovers.awaitNone(sandbox()::bash, before, SandboxLeftovers.leftOver(null), test.getDisplayName());
    }

    /**
     * The runtime's watchdog is double-forked, so it is reparented to the sandbox's PID 1 (or the nearest subreaper)
     * and becomes that process's zombie once it has killed its group; that process must reap it
     * (docs/design/ptc-shell-provider.md §9). The program records what it sees before it spins, and that record is
     * checked after the timed-out return; its {@code sleep} child carries a mark of this run only.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a timed-out run had its watchdog, and leaves no aimon_ptc process, child or zombie in the sandbox")
    void aTimedOutRunLeavesNothingInTheSandbox() throws Exception {
        final String mark = "61" + ThreadLocalRandom.current().nextInt(1000, 10000);
        final String probe = "/tmp/aimon-probe-" + UUID.randomUUID();
        final Set<SandboxLeftovers.Key> baseline = SandboxLeftovers.baseline(SandboxLeftovers.snapshot(sandbox()::bash),
                SandboxLeftovers.leftOver(mark));
        final CodeExecutionOutcome outcome;
        try (ScriptedToolCallBridge bridge = ScriptedToolCallBridge.succeedingWith("unused")) {
            outcome = run(
                    SandboxLeftovers.watchdogProbeProgram(probe) + "import subprocess\nsubprocess.Popen(['sleep', '"
                            + mark + "'])\n" + programs().spinForever(),
                    bridge, CodeExecutionLimits.builder().maxStatements(Long.MAX_VALUE).build(), Duration.ofSeconds(2));
        }
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.TIMED_OUT);

        final ShellCommandResult read = sandbox().bash("cat " + probe + " && rm -f " + probe);
        assertThat(read.exitCode()).as("the program's probe file: %s", read.stderr()).isZero();
        final SandboxLeftovers.WatchdogProbe seen = SandboxLeftovers.parseProbe(read.stdout());
        assertThat(seen.runtimeLeadsItsOwnGroup())
                .as("the runtime leads its own group and its anchor is in another: %s", read.stdout()).isTrue();
        assertThat(seen.watchdogs()).as(
                "a watchdog: an aimon_ptc process in the runtime's group, parented by neither it nor its anchor: %s",
                read.stdout()).isNotEmpty();

        SandboxLeftovers.awaitNone(sandbox()::bash, baseline, SandboxLeftovers.leftOver(mark),
                "the timed-out run (child mark sleep " + mark + ")");
    }
}
