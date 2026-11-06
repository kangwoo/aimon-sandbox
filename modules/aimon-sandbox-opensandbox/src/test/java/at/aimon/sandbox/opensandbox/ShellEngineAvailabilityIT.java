package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.CodeOfferKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;

/**
 * Core's Python shell engine becomes available on a real OpenSandbox sandbox (docs/design/ptc-shell-provider.md
 * §6.10): the facts core extracts from the environment pass its static availability, and its probe passes — the
 * runtime file system's {@code createDirectories} and {@code writeNew} over execd, the isolated exec that checks
 * {@code python3} and sees the probe file, and the {@code delete} after.
 */
@Tag("docker")
class ShellEngineAvailabilityIT {

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

    /** The facts as the loop builds them; whether Bash is unconstrained is the agent's, not the environment's. */
    private static OfferFacts offerFacts(ExecutionEnvironment environment) {
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment)
                .build();
        final OfferFacts extracted = CodeOfferKeys
                .factsOf(CodeOfferKeys.putEnvironmentFacts(ToolOfferContext.builder(), context).build());
        return OfferFacts.builder().descriptor(extracted.getDescriptor().orElse(null))
                .shellFeatures(extracted.getShellFeatures()).runtimeFileSystem(extracted.hasRuntimeFileSystem())
                .bashUnconstrained(true).build();
    }

    @Test
    @DisplayName("the environment's facts make the engine available, and its probe passes and leaves nothing")
    void theEngineIsAvailableAndItsProbePasses() throws Exception {
        try (ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create()) {
            final OfferFacts facts = offerFacts(sandbox.environment);
            assertThat(facts.getIsolation()).contains(EnvironmentIsolation.SANDBOXED);
            assertThat(facts.getRuntimes()).contains("python3");
            assertThat(facts.getShellFeatures()).contains(ShellFeature.STREAMING, ShellFeature.RUN_DIRECTORY,
                    ShellFeature.ISOLATED_EXECUTION);
            assertThat(facts.hasRuntimeFileSystem()).isTrue();
            assertThat(engine.staticAvailability(facts).isAvailable()).isTrue();

            final EngineAvailability probed = engine.probe(sandbox.environment);

            assertThat(probed.isAvailable()).as("probe: %s", probed.getReason().orElse("")).isTrue();
            assertThat(sandbox.bash("ls -A /workspace/repo/.aimon-ptc").stdout()).isEmpty();
        }
    }
}
