package at.aimon.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.github.dockerjava.api.DockerClient;

class DockerClientFactoryTest {

    @Test
    void create_WithDefaultConfig_ReturnsNonNullClient() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        DockerClient client = DockerClientFactory.create(config);

        assertThat(client).isNotNull();
    }

    @Test
    void create_WithCustomDockerHost_ReturnsNonNullClient() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().dockerHost("tcp://localhost:2375").build();

        DockerClient client = DockerClientFactory.create(config);

        assertThat(client).isNotNull();
    }

    @Test
    void create_NullConfig_ThrowsNullPointerException() {
        assertThatThrownBy(() -> DockerClientFactory.create(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("DockerSandboxConfig cannot be null");
    }
}
