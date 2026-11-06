package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.CodeOfferKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/**
 * Core's Python shell engine becomes available on a workspace sandbox (docs/design/ptc-shell-provider.md §6.10): the
 * offer facts core extracts from the real environment pass the engine's static availability — for a fork too — and
 * the engine's probe passes through this repository's runtime file system and shell. The contract suite does not
 * cover this: it never builds facts from an environment and never probes.
 */
class ShellEngineAvailabilityTest {

    private final SandboxHarness harness = SandboxHarness.builder()
            .profile(SandboxTestProfiles.local("python").runtimes(Set.of("python3")).build()).build();
    private final ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create();

    @AfterEach
    void close() {
        engine.close();
        harness.close();
    }

    /**
     * The facts as the loop builds them, through core's own extraction. Whether {@code Bash} is unconstrained comes
     * from the agent's allow-list and hooks, not from the environment, so it is set here.
     */
    static OfferFacts offerFacts(ExecutionEnvironment environment) {
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment)
                .build();
        final OfferFacts extracted = CodeOfferKeys
                .factsOf(CodeOfferKeys.putEnvironmentFacts(ToolOfferContext.builder(), context).build());
        return OfferFacts.builder().descriptor(extracted.getDescriptor().orElse(null))
                .shellFeatures(extracted.getShellFeatures()).runtimeFileSystem(extracted.hasRuntimeFileSystem())
                .bashUnconstrained(true).build();
    }

    private static void assertEveryFact(OfferFacts facts) {
        assertThat(facts.getIsolation()).contains(EnvironmentIsolation.SANDBOXED);
        assertThat(facts.getRuntimes()).contains("python3");
        assertThat(facts.getShellFeatures()).contains(ShellFeature.STREAMING, ShellFeature.RUN_DIRECTORY,
                ShellFeature.ISOLATED_EXECUTION);
        assertThat(facts.hasRuntimeFileSystem()).isTrue();
    }

    @Test
    @DisplayName("1/2: the facts of a main-turn environment and of its fork make the engine available; nothing is provisioned")
    void theEngineIsAvailableFromTheEnvironmentsFacts() {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);
        final ExecutionEnvironment fork = harness.fork(main, ALICE);

        for (ExecutionEnvironment environment : new ExecutionEnvironment[]{main, fork}) {
            final OfferFacts facts = offerFacts(environment);
            assertEveryFact(facts);
            assertThat(engine.staticAvailability(facts).isAvailable()).isTrue();
        }
        assertThat(harness.faults.calls(Operation.CREATE)).isZero();
    }

    @Test
    @DisplayName("3: the probe passes through the runtime file system and the isolated shell, and leaves no probe file")
    void theProbePasses() throws Exception {
        final ExecutionEnvironment environment = harness.mainTurn(SessionId.generate(), ALICE);
        assumeTrue(ShellEngineContractTest.usablePython(environment), "the host has no python3 3.8 or later");

        final EngineAvailability probed = engine.probe(environment);

        assertThat(probed.isAvailable()).as("probe: %s", probed.getReason().orElse("")).isTrue();
        assertThat(bash(environment, "ls -A /workspace/repo/.aimon-ptc").stdout()).isEmpty();
    }

    @Test
    @DisplayName("4: a profile that declares no runtime: unavailable, for want of python3")
    void withoutTheDeclarationTheEngineIsUnavailable() {
        try (SandboxHarness plain = SandboxHarness.builder().profile(SandboxTestProfiles.local("plain").build())
                .build()) {
            final EngineAvailability availability = engine
                    .staticAvailability(offerFacts(plain.mainTurn(SessionId.generate(), ALICE)));

            assertThat(availability.isAvailable()).isFalse();
            assertThat(availability.getReason())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("does not declare the python3 runtime"));
        }
    }

    @Test
    @DisplayName("5: an unavailable environment declares no isolation: unavailable, not sandboxed")
    void anUnavailableEnvironmentIsNotSandboxed() {
        final ExecutionEnvironment unavailable = harness.fork(UnavailableExecutionEnvironment.of("no sandbox"), ALICE);

        final EngineAvailability availability = engine.staticAvailability(offerFacts(unavailable));

        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.getReason()).hasValueSatisfying(reason -> assertThat(reason).contains("not sandboxed"));
    }
}
