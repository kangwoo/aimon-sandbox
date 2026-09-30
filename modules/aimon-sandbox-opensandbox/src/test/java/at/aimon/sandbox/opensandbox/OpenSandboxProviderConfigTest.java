package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.SandboxConfigurationException;
import at.aimon.sandbox.opensandbox.OpenSandboxProviderConfig.Declaration;
import at.aimon.sandbox.opensandbox.OpenSandboxProviderConfig.EgressEnforcement;
import at.aimon.sandbox.opensandbox.OpenSandboxProviderConfig.Runtime;
import at.aimon.sandbox.opensandbox.OpenSandboxProviderConfig.RuntimeClass;
import at.aimon.sandbox.opensandbox.OpenSandboxProviderConfig.RuntimeKind;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CredentialScope;
import at.aimon.sandbox.provider.HostPort;

/** {@link OpenSandboxProviderConfig}: the capability derivation table and every refused declaration (§5.2). */
class OpenSandboxProviderConfigTest {

    private static final CredentialDefinition GITHUB = CredentialDefinition.of(
            CredentialScope.builder().hosts(Set.of("github.com")).build(), CredentialDefinition.Auth.bearer(),
            () -> "ghp_secret");

    private static OpenSandboxProviderConfig.Builder kubernetes() {
        return OpenSandboxProviderConfig.builder().endpoint(URI.create("http://opensandbox:8090")).apiKey(() -> "key")
                .runtime(Runtime.KUBERNETES).maxExpiry(Duration.ofHours(24));
    }

    private static List<String> violations(OpenSandboxProviderConfig.Builder builder) {
        try {
            builder.build();
            return List.of();
        } catch (SandboxConfigurationException e) {
            return e.violations();
        }
    }

    @Test
    void theBaseCapabilitiesAreAlwaysAdvertisedAndNothingElseByDefault() {
        assertThat(kubernetes().build().capabilities()).containsExactlyInAnyOrder(Capability.EXEC, Capability.FILES,
                Capability.EXPIRY);
    }

    @Test
    void everyDeclarationAdvertisesItsCapability() {
        final OpenSandboxProviderConfig config = kubernetes().egressEnforcement(EgressEnforcement.DNS_NFT)
                .credentials(Map.of("gh", GITHUB)).networkIsolation(Declaration.DECLARED)
                .hardenedSecurityContext(Declaration.DECLARED)
                .runtimeClass(RuntimeClass.of("kata-qemu", RuntimeKind.KATA)).volumeReclaimer(new VolumeReclaimer() {
                    @Override
                    public List<at.aimon.sandbox.provider.ProviderVolume> list(Map<String, String> labels) {
                        return List.of();
                    }

                    @Override
                    public void delete(at.aimon.sandbox.provider.VolumeRef ref) {
                        // none
                    }
                }).build();

        assertThat(config.capabilities())
                .containsExactlyInAnyOrder(Capability.EXEC, Capability.FILES, Capability.EXPIRY,
                        Capability.EGRESS_POLICY, Capability.CREDENTIAL_INJECTION, Capability.NETWORK_ISOLATION,
                        Capability.HARDENED_SECURITY_CONTEXT, Capability.RUNTIME_CLASS, Capability.SHARED_VOLUME)
                .doesNotContain(Capability.PAUSE_RESUME, Capability.SNAPSHOT, Capability.FORK);
        assertThat(config.credentialScopes()).containsOnlyKeys("gh");
    }

    @Test
    void dnsModeIsAcceptedButNotAdvertised() {
        assertThat(kubernetes().egressEnforcement(EgressEnforcement.DNS).build().capabilities())
                .doesNotContain(Capability.EGRESS_POLICY);
    }

    @Test
    void theDockerRuntimeRefusesIsolationHardeningAndRuntimeClasses() {
        assertThat(violations(kubernetes().runtime(Runtime.DOCKER).networkIsolation(Declaration.DECLARED)
                .hardenedSecurityContext(Declaration.DECLARED).runtimeClass(RuntimeClass.of("kata", RuntimeKind.KATA))))
                .hasSize(3).anyMatch(v -> v.contains("network-isolation cannot be declared on the docker runtime"))
                .anyMatch(v -> v.contains("hardened-security-context cannot be declared"))
                .anyMatch(v -> v.contains("runtime-class cannot be set on the docker runtime"));
    }

    @Test
    void gvisorCannotBeCombinedWithDnsNftButKataCan() {
        assertThat(violations(kubernetes().egressEnforcement(EgressEnforcement.DNS_NFT)
                .runtimeClass(RuntimeClass.of("gvisor", RuntimeKind.GVISOR))))
                .anyMatch(v -> v.contains("cannot be combined with the gVisor runtime class 'gvisor'"));
        assertThat(kubernetes().egressEnforcement(EgressEnforcement.DNS_NFT)
                .runtimeClass(RuntimeClass.of("kata", RuntimeKind.KATA)).build().capabilities())
                .contains(Capability.EGRESS_POLICY, Capability.RUNTIME_CLASS);
        assertThat(kubernetes().runtimeClass(RuntimeClass.of("gvisor", RuntimeKind.GVISOR)).build().capabilities())
                .contains(Capability.RUNTIME_CLASS).doesNotContain(Capability.EGRESS_POLICY);
        assertThatThrownBy(() -> RuntimeClass.of("gvisor", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void credentialsNeedDnsNft() {
        assertThat(violations(kubernetes().credentials(Map.of("gh", GITHUB))))
                .anyMatch(v -> v.contains("credentials need egress-enforcement dns+nft"));
    }

    @Test
    void declaredIsolationWithAnEmptyProbeListIsRefused() {
        assertThat(violations(kubernetes().networkIsolation(Declaration.DECLARED).controlPlaneProbes(List.of())))
                .anyMatch(v -> v.contains("control-plane-probes is empty"));
    }

    @Test
    void probesDefaultToTheEndpointAndOnKubernetesTheApiServer() {
        assertThat(kubernetes().build().controlPlaneProbes()).containsExactly(HostPort.of("opensandbox", 8090),
                HostPort.of("kubernetes.default.svc", 443));
        assertThat(kubernetes().runtime(Runtime.DOCKER).endpoint(URI.create("https://sandbox.example.com")).build()
                .controlPlaneProbes()).containsExactly(HostPort.of("sandbox.example.com", 443));
        assertThat(kubernetes().controlPlaneProbes(List.of(HostPort.of("svc", 80))).build().controlPlaneProbes())
                .containsExactly(HostPort.of("svc", 80));
    }

    @Test
    void theEndpointGetsItsApiVersionOnce() {
        assertThat(kubernetes().build().endpoint()).hasToString("http://opensandbox:8090/v1");
        assertThat(kubernetes().endpoint(URI.create("http://opensandbox:8090/v1/")).build().endpoint())
                .hasToString("http://opensandbox:8090/v1");
    }

    @Test
    void everyViolationIsReportedAtOnce() {
        assertThat(violations(OpenSandboxProviderConfig.builder().maxExpiry(Duration.ofSeconds(10))
                .requestTimeout(Duration.ZERO).retry(0, Duration.ZERO).maxConcurrentCalls(0).entrypoint(List.of())))
                .anyMatch(v -> v.contains("endpoint is required")).anyMatch(v -> v.contains("api-key is required"))
                .anyMatch(v -> v.contains("runtime is required")).anyMatch(v -> v.contains("at least 60s"))
                .anyMatch(v -> v.contains("request-timeout must be positive"))
                .anyMatch(v -> v.contains("retry.max-attempts")).anyMatch(v -> v.contains("max-concurrent-calls"))
                .anyMatch(v -> v.contains("entrypoint must not be empty"));
        assertThat(violations(kubernetes().maxExpiry(null))).anyMatch(v -> v.contains("max-expiry is required"));
        assertThat(violations(kubernetes().endpoint(URI.create("relative")))).anyMatch(v -> v.contains("absolute"));
    }

    @Test
    void secretsAreNeverPrinted() {
        final OpenSandboxProviderConfig config = kubernetes().egressEnforcement(EgressEnforcement.DNS_NFT)
                .credentials(Map.of("gh", GITHUB)).build();

        assertThat(config.toString()).contains("gh").doesNotContain("ghp_secret").doesNotContain("key=");
        assertThat(GITHUB.toString()).contains("<redacted>").doesNotContain("ghp_secret");
        assertThat(CredentialDefinition.Auth.apiKey("X-Api-Key")).hasToString("apiKey(X-Api-Key)");
    }
}
