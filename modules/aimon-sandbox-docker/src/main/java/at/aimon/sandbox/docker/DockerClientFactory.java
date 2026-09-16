package at.aimon.sandbox.docker;

import java.io.IOException;
import java.util.Objects;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

/**
 * Factory for creating {@link DockerClient} instances from {@link DockerSandboxConfig}.
 *
 * <p>
 * Encapsulates the docker-java transport configuration so that callers need not depend on transport internals.
 */
public final class DockerClientFactory {

    private DockerClientFactory() {
        // utility class
    }

    /**
     * Creates a {@link DockerClient} configured from the given {@link DockerSandboxConfig}.
     *
     * <p>
     * The caller is responsible for closing the returned client when it is no longer needed.
     *
     * @param config
     *            the Docker sandbox configuration (not null)
     * @return a configured DockerClient; the caller must close it when done
     */
    public static DockerClient create(DockerSandboxConfig config) {
        Objects.requireNonNull(config, "DockerSandboxConfig cannot be null");

        DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder();

        if (config.getDockerHost() != null) {
            configBuilder.withDockerHost(config.getDockerHost());
        }

        DockerClientConfig clientConfig = configBuilder.build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder().dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig()).build();

        try {
            return DockerClientImpl.getInstance(clientConfig, httpClient);
        } catch (Exception e) {
            try {
                httpClient.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }
}
