package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
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
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.programmatic.testkit.AbstractCodeExecutionEngineContractTest;
import at.aimon.programmatic.testkit.EnginePrograms;
import at.aimon.programmatic.testkit.PythonPrograms;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

/**
 * Core's engine contract suite with core's Python shell engine on a real OpenSandbox sandbox (shell doc §14;
 * docs/design/ptc-shell-provider.md §5.7): the real execd, the real {@code /workspace}, the tier's image with
 * {@code python3}. It exercises the seed's {@code runtime-python3} check, isolated streamed runs whose run directory
 * carries a secret file (the S4 upload path), the runtime file system over execd, the spool removal of S1 and the
 * deletion of each run's directory. Not the engine's offer-time availability or its probe: the suite calls
 * {@code execute} directly — {@link ShellEngineAvailabilityIT} covers those.
 *
 * <p>
 * The engine sees the sandbox's environment unchanged. Core's runtime ends with the shell's process-group kill (its
 * anchor stays in the exec's group; a watchdog kills the runtime's own group when the anchor dies — aimon-core#336),
 * which is all this module's kill reaches (docs/design/workspace-sandbox.md §9). One test of this class's own checks
 * what that leaves in the sandbox: nothing.
 */
@Tag("docker")
class ShellEngineContractIT extends AbstractCodeExecutionEngineContractTest {

    /** The argument of the {@code sleep} the program below starts: a child of the program to look for afterwards. */
    private static final String CHILD_MARK = "6123";

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
        return ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, sandbox.environment).build();
    }

    /**
     * The runtime's watchdog is double-forked, so it is reparented to the sandbox's PID 1 and becomes that process's
     * zombie once it has killed its group; on OpenSandbox PID 1 is {@code bootstrap.sh}'s {@code sh}, which reaps any
     * child while it waits for the entrypoint (docs/design/ptc-shell-provider.md §9). A process left over from an
     * earlier test of this class counts too: none should be.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a timed-out run leaves no aimon_ptc process, no child of the program and no zombie in the sandbox")
    void aTimedOutRunLeavesNothingInTheSandbox() throws Exception {
        final CodeExecutionOutcome outcome;
        try (ScriptedToolCallBridge bridge = ScriptedToolCallBridge.succeedingWith("unused")) {
            outcome = run(
                    "import subprocess\nsubprocess.Popen(['sleep', '" + CHILD_MARK + "'])\n" + programs().spinForever(),
                    bridge, CodeExecutionLimits.builder().maxStatements(Long.MAX_VALUE).build(), Duration.ofSeconds(2));
        }
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.TIMED_OUT);

        // The watchdog's kill and PID 1's reaping are asynchronous to the shell call's return: poll for a while.
        List<String> left = leftOver();
        for (int attempt = 0; attempt < 50 && !left.isEmpty(); attempt++) {
            Thread.sleep(200);
            left = leftOver();
        }
        assertThat(left).as("processes left in the sandbox (pid ppid stat args)").isEmpty();
    }

    /**
     * @return every process of the sandbox that is a zombie, a runtime or the program's child, as {@code ps} prints it
     *         — filtered here, so the command's own text cannot match. A {@code ps} that fails or lists nothing fails
     *         the test instead of passing it.
     */
    private static List<String> leftOver() throws Exception {
        final ShellCommandResult ps = sandbox.bash("ps -eo pid=,ppid=,stat=,args=");
        assertThat(ps.exitCode()).as("ps in the sandbox: %s", ps.stderr()).isZero();
        assertThat(ps.stdout()).as("ps lists the sandbox's PID 1").containsPattern("(?m)^\\s*1\\s");
        return ps.stdout().lines().map(String::strip).filter(line -> {
            final String[] fields = line.split("\\s+", 4);
            return fields.length == 4 && (fields[2].startsWith("Z") || fields[3].contains("aimon_ptc")
                    || fields[3].equals("sleep " + CHILD_MARK));
        }).toList();
    }
}
