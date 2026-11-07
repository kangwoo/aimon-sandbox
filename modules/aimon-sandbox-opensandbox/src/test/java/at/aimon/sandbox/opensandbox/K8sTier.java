package at.aimon.sandbox.opensandbox;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import at.aimon.sandbox.provider.HostPort;

/**
 * The k8s tier's environment (docs/design/workspace-sandbox.md §16; module README): the forwarded server, the image
 * and the declarations every class of the tier builds its provider from. Nothing here provisions anything.
 */
final class K8sTier {

    static final String ENDPOINT = "OPENSANDBOX_K8S_ENDPOINT";
    static final String POOL = "OPENSANDBOX_K8S_POOL";
    static final String NO_CLUSTER = ENDPOINT
            + " is not set: the k8s tier needs a provisioned cluster (see the README)";

    private K8sTier() {
    }

    static String env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(v -> !v.isBlank()).orElse(null);
    }

    static HostPort controlPlane() {
        return HostPort.parse(Optional.ofNullable(env("OPENSANDBOX_K8S_CONTROL_PLANE"))
                .orElse("opensandbox-server.opensandbox-system.svc:80"));
    }

    /** @return the provider configuration of a template-mode sandbox: the server's declarations as deployed */
    static OpenSandboxProviderConfig.Builder config() {
        final OpenSandboxProviderConfig.Builder config = OpenSandboxProviderConfig.builder()
                .endpoint(URI.create(env(ENDPOINT)))
                .apiKey(() -> Optional.ofNullable(env("OPENSANDBOX_K8S_API_KEY")).orElse(""))
                .runtime(OpenSandboxProviderConfig.Runtime.KUBERNETES).maxExpiry(Duration.ofHours(1))
                .useServerProxy(true).egressEnforcement(OpenSandboxProviderConfig.EgressEnforcement.DNS_NFT)
                .networkIsolation(OpenSandboxProviderConfig.Declaration.DECLARED)
                .hardenedSecurityContext(OpenSandboxProviderConfig.Declaration.DECLARED)
                .controlPlaneProbes(List.of(controlPlane())).defaultResources("250m", "256Mi");
        Optional.ofNullable(runtimeClass()).ifPresent(name -> config.runtimeClass(
                OpenSandboxProviderConfig.RuntimeClass.of(name, OpenSandboxProviderConfig.RuntimeKind.OTHER)));
        return config;
    }

    /**
     * @return the provider configuration of a pool-mode sandbox: {@code extensions.poolRef}, and no runtime class —
     *         the controller pre-creates a pool's pods from the {@code Pool} resource, and the server's runtime class
     *         does not reach them, so declaring it would be false
     */
    static OpenSandboxProviderConfig.Builder poolConfig(String pool) {
        return config().runtimeClass(null).extensions(Map.of("poolRef", pool));
    }

    static String image() {
        return Optional.ofNullable(env("OPENSANDBOX_K8S_IMAGE")).orElse("ghcr.io/kangwoo/aimon-sandbox-runtime:1");
    }

    static String runtimeClass() {
        return env("OPENSANDBOX_K8S_RUNTIME_CLASS");
    }
}
