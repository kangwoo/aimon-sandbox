package at.aimon.sandbox.kubernetes;

import java.io.FileReader;
import java.io.IOException;
import java.util.Objects;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;

/**
 * Factory for creating {@link ApiClient} instances from {@link KubernetesSandboxConfig}.
 *
 * <p>
 * Encapsulates the Kubernetes client configuration so that callers need not depend on client internals.
 */
public final class KubernetesClientFactory {

    private KubernetesClientFactory() {
        // utility class
    }

    /**
     * Creates an {@link ApiClient} configured from the given {@link KubernetesSandboxConfig}.
     *
     * <p>
     * If {@code kubeConfigPath} is set, the client uses that kubeconfig file. Otherwise, it uses the standard discovery
     * chain (in-cluster config, {@code ~/.kube/config}, etc.).
     *
     * @param config
     *            the Kubernetes sandbox configuration (not null)
     * @return a configured ApiClient
     * @throws IOException
     *             if client creation fails
     */
    public static ApiClient create(KubernetesSandboxConfig config) throws IOException {
        Objects.requireNonNull(config, "KubernetesSandboxConfig cannot be null");

        ApiClient client;
        if (config.getKubeConfigPath() != null) {
            try (FileReader reader = new FileReader(config.getKubeConfigPath())) {
                client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(reader)).build();
            }
        } else {
            client = ClientBuilder.standard().build();
        }

        client.setConnectTimeout(config.getApiConnectionTimeoutMs());
        client.setReadTimeout(config.getApiReadTimeoutMs());
        return client;
    }
}
