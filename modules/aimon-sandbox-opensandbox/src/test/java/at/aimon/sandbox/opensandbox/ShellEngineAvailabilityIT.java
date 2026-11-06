package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * {@code python3} and sees the probe file, and the {@code delete} after. And the negative side of the seed's
 * {@code runtime-python3} check: an image without {@code python3} under a profile that declares it is a permanent
 * provisioning failure, and the probe reports the engine unavailable instead of failing.
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
    @DisplayName("the facts core extracts from the environment: sandboxed, python3, the shell features, a runtime FS")
    void theEnvironmentsFacts() {
        final OfferFacts facts = offerFacts(sandbox.environment);

        assertThat(facts.getIsolation()).contains(EnvironmentIsolation.SANDBOXED);
        assertThat(facts.getRuntimes()).contains("python3");
        assertThat(facts.getShellFeatures()).contains(ShellFeature.STREAMING, ShellFeature.RUN_DIRECTORY,
                ShellFeature.ISOLATED_EXECUTION);
        assertThat(facts.hasRuntimeFileSystem()).isTrue();
    }

    @Test
    @DisplayName("those facts pass the engine's static availability")
    void theFactsMakeTheEngineAvailable() {
        try (ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create()) {
            assertThat(engine.staticAvailability(offerFacts(sandbox.environment)).isAvailable()).isTrue();
        }
    }

    @Test
    @DisplayName("the engine's probe passes and leaves nothing behind")
    void theProbePassesAndLeavesNothing() throws Exception {
        try (ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create()) {
            final EngineAvailability probed = engine.probe(sandbox.environment);

            assertThat(probed.isAvailable()).as("probe: %s", probed.getReason().orElse("")).isTrue();
            assertThat(sandbox.bash("ls -A /workspace/repo/.aimon-ptc").stdout()).isEmpty();
        }
    }

    @Test
    @DisplayName("an image without python3 under a profile declaring it: the seed fails permanently, the probe too")
    void anImageWithoutPython3FailsTheSeedAndTheProbe() {
        try (SharedSandbox broken = SharedSandbox.unstarted(OpenSandboxTestServer.sandboxImageWithoutPython3());
                ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create()) {
            assertThatThrownBy(() -> broken.bash("true")).hasMessageContaining("[step runtime-python3]")
                    .hasMessageContaining("the profile declares python3 but the image lacks it")
                    .hasMessageContaining("not retried until the profile changes");

            // The profile still declares python3, so only the probe can tell — and it does, cleanly.
            assertThat(engine.staticAvailability(offerFacts(broken.environment)).isAvailable()).isTrue();
            final EngineAvailability probed = engine.probe(broken.environment);
            assertThat(probed.isAvailable()).isFalse();
            assertThat(probed.getReason()).hasValueSatisfying(reason -> assertThat(reason).contains("python3"));
        }
    }
}
