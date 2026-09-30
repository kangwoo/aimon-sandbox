package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.DelegatingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.HostPort;
import at.aimon.sandbox.provider.ProviderCapabilities;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.VerificationFailure;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/**
 * The seed step's checks of implementation step 4 (§11.3): the provider's {@link SandboxProvider#verify} and the
 * control-plane network probe, end to end through {@code WorkspaceSandbox} on the local provider.
 */
class SeedVerificationTest {

    /** Advertises NETWORK_ISOLATION with the given control plane, and answers verify as told. */
    private static final class Declaring extends DelegatingProvider {
        private final List<HostPort> controlPlane;
        private final AtomicReference<List<VerificationFailure>> verification = new AtomicReference<>(List.of());
        private final AtomicReference<RuntimeException> verifyError = new AtomicReference<>();

        Declaring(SandboxProvider delegate, List<HostPort> controlPlane) {
            super(delegate);
            this.controlPlane = controlPlane;
        }

        @Override
        public ProviderCapabilities capabilities() {
            final Set<Capability> advertised = EnumSet.copyOf(delegate.capabilities().advertised());
            advertised.add(Capability.NETWORK_ISOLATION);
            return ProviderCapabilities.builder().advertised(advertised)
                    .maxExpiry(delegate.capabilities().maxExpiry().orElse(null)).controlPlaneEndpoints(controlPlane)
                    .build();
        }

        @Override
        public List<VerificationFailure> verify(ProviderSandboxRef ref, Set<Capability> required) {
            if (verifyError.get() != null) {
                throw verifyError.get();
            }
            return verification.get();
        }
    }

    private Declaring declaring;

    private SandboxHarness harness(List<HostPort> controlPlane) {
        return SandboxHarness.builder()
                .profile(SandboxTestProfiles.local("isolated")
                        .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT)).build())
                .decorate((local, clock) -> declaring = new Declaring(local, controlPlane)).build();
    }

    @Test
    void aSandboxThatReachesTheControlPlaneFailsPermanentlyAndIsDestroyed() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                SandboxHarness harness = harness(List.of(HostPort.of("127.0.0.1", listening.getLocalPort())))) {
            final SessionId session = SessionId.generate();

            assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                    .hasMessageContaining("[step network-isolation]")
                    .hasMessageContaining("it is not retried until the profile changes");

            assertThat(harness.primary(session).state()).isEqualTo(SlotState.FAILED);
            assertThat(harness.local.sandboxCount()).isZero();
        }
    }

    @Test
    void aSandboxThatCannotReachTheControlPlaneSeedsNormally() throws Exception {
        final int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        try (SandboxHarness harness = harness(List.of(HostPort.of("127.0.0.1", closed)))) {
            final SessionId session = SessionId.generate();

            assertThat(bash(harness.mainTurn(session, ALICE), "echo ok").stdout()).isEqualTo("ok\n");
            assertThat(harness.primary(session).seeded()).isTrue();
        }
    }

    @Test
    void aFailedProviderVerificationFailsTheSlotWithItsStep() throws Exception {
        try (SandboxHarness harness = harness(List.of())) {
            declaring.verification.set(List.of(VerificationFailure.of("egress", "enforcementMode is dns")));
            final SessionId session = SessionId.generate();

            assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                    .hasMessageContaining("enforcementMode is dns [step egress]");
            assertThat(harness.primary(session).failure().orElseThrow().step()).isEqualTo("egress");
            assertThat(harness.local.sandboxCount()).isZero();
        }
    }

    @Test
    void aVerificationThatCannotRunLeavesTheSlotUnseededAndIsRetried() throws Exception {
        try (SandboxHarness harness = harness(List.of())) {
            declaring.verifyError.set(new at.aimon.sandbox.provider.SandboxProviderException("503"));
            final SessionId session = SessionId.generate();

            assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                    .hasMessageContaining("could not be prepared: 503");
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
            assertThat(harness.primary(session).seeded()).isFalse();

            declaring.verifyError.set(null);
            assertThat(bash(harness.mainTurn(session, ALICE), "echo ok").stdout()).isEqualTo("ok\n");
        }
    }

    @Test
    void aSandboxGoneDuringVerificationTakesTheLostPath() throws Exception {
        try (SandboxHarness harness = harness(List.of())) {
            declaring.verifyError.set(new SandboxNotFoundException(ProviderSandboxRef.of("local", "gone")));
            final SessionId session = SessionId.generate();

            assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                    .hasMessageContaining("the sandbox was lost");
            assertThat(harness.primary(session).lostAt()).isPresent();
        }
    }
}
