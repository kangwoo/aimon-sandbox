package at.aimon.sandbox.opensandbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.programmatic.testkit.AbstractCodeExecutionEngineContractTest;
import at.aimon.programmatic.testkit.EnginePrograms;
import at.aimon.programmatic.testkit.PythonPrograms;

/**
 * Core's engine contract suite with core's Python shell engine on a real OpenSandbox sandbox (shell doc §14;
 * docs/design/ptc-shell-provider.md §5.7) — unmodified: the real execd, the real {@code /workspace}, the tier's image
 * with {@code python3}. It exercises the seed's {@code runtime-python3} check, isolated streamed runs whose run
 * directory carries a secret file (the S4 upload path), the runtime file system over execd, the spool removal of S1
 * and the deletion of each run's directory. Not the engine's offer-time availability or its probe: the suite calls
 * {@code execute} directly — {@link ShellEngineAvailabilityIT} covers those.
 */
@Tag("docker")
class ShellEngineContractIT extends AbstractCodeExecutionEngineContractTest {

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
}
