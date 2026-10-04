package at.aimon.sandbox.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.provider.Capability;

class SandboxProfileTest {

    private static SandboxProfile.Builder base() {
        return SandboxProfile.builder().name("p").image("img").terminateAfter(Duration.ofHours(2));
    }

    @Test
    void everyProfileRequiresExecFilesExpiryAndIsolation() {
        assertThat(base().build().requiredCapabilities()).containsExactlyInAnyOrder(Capability.EXEC, Capability.FILES,
                Capability.EXPIRY, Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION);
    }

    @Test
    void configurationImpliesCapabilitiesAndAnEmptyEgressListStillNeedsAPolicy() {
        final SandboxProfile profile = base().egress(List.of()).credentials(List.of("vault-binding"))
                .runtimeClass("gvisor").sharedAccess(SharedAccess.RO).pauseAfter(Duration.ofMinutes(5)).build();

        assertThat(profile.requiredCapabilities()).contains(Capability.EGRESS_POLICY, Capability.CREDENTIAL_INJECTION,
                Capability.RUNTIME_CLASS, Capability.SHARED_VOLUME, Capability.PAUSE_RESUME);
        assertThat(base().build().egress()).isEmpty();
        assertThat(profile.egress()).contains(List.of());
    }

    @Test
    void insecureAllowWaivesOnlyWhatItNames() {
        final SandboxProfile profile = base().insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT)).build();

        assertThat(profile.requiredCapabilities()).doesNotContain(Capability.HARDENED_SECURITY_CONTEXT)
                .contains(Capability.NETWORK_ISOLATION);
    }

    @Test
    void theContentHashChangesWithTheContent() {
        final String hash = base().build().contentHash();

        assertThat(base().build().contentHash()).isEqualTo(hash).hasSize(64);
        assertThat(base().environment(Map.of("A", "1")).build().contentHash()).isNotEqualTo(hash);
        assertThat(base().image("other").build().contentHash()).isNotEqualTo(hash);
        assertThat(base().seed(SeedSpec.git("https://x/r.git", "main", null)).build().contentHash()).isNotEqualTo(hash);
    }

    @Test
    void theBackgroundCommandTimeoutFollowsTheHeartbeatLimitUnlessConfigured() {
        assertThat(base().build().backgroundCommandTimeout()).isEqualTo(Duration.ofHours(1));
        assertThat(base().build().configuredBackgroundCommandTimeout()).isEmpty();
        assertThat(base().backgroundHeartbeatLimit(Duration.ofMinutes(10)).build().backgroundCommandTimeout())
                .isEqualTo(Duration.ofMinutes(10));

        final SandboxProfile configured = base().backgroundHeartbeatLimit(Duration.ofMinutes(10))
                .backgroundCommandTimeout(Duration.ofHours(3)).build();

        assertThat(configured.backgroundCommandTimeout()).isEqualTo(Duration.ofHours(3));
        assertThat(configured.configuredBackgroundCommandTimeout()).contains(Duration.ofHours(3));
        assertThat(configured.backgroundHeartbeatLimit()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theContentHashFollowsTheEffectiveBackgroundCommandTimeout() {
        final String hash = base().build().contentHash();

        assertThat(base().backgroundCommandTimeout(Duration.ofMinutes(20)).build().contentHash()).isNotEqualTo(hash);
        // Spelling out the default is not a change.
        assertThat(base().backgroundCommandTimeout(Duration.ofHours(1)).build().contentHash()).isEqualTo(hash);
    }

    @Test
    void registryFindsProfilesAndTheLongestTerminateAfter() {
        final SandboxProfileRegistry registry = new SandboxProfileRegistry(
                List.of(base().name("a").build(), base().name("b").terminateAfter(Duration.ofHours(5)).build()), "a");

        assertThat(registry.find("b")).isPresent();
        assertThat(registry.find("c")).isEmpty();
        assertThat(registry.defaultProfileName()).isEqualTo("a");
        assertThat(registry.longestTerminateAfter()).contains(Duration.ofHours(5));
        assertThatThrownBy(() -> new SandboxProfileRegistry(List.of(base().build(), base().build()), "p"))
                .hasMessageContaining("duplicate");
    }

    @Test
    void seedSpecDescribesItself() {
        assertThat(SeedSpec.git("https://x/r.git", "main", "ro-cred").toString())
                .isEqualTo("git https://x/r.git@main as ro-cred");
    }
}
