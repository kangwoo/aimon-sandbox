package at.aimon.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class KubernetesClientFactoryTest {

    @Test
    void create_NullConfig_ThrowsNullPointerException() {
        assertThatThrownBy(() -> KubernetesClientFactory.create(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("KubernetesSandboxConfig cannot be null");
    }

    @Test
    void create_NonExistentKubeConfigPath_ThrowsIOException() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder()
                .kubeConfigPath("/non/existent/path/kubeconfig").build();

        assertThatThrownBy(() -> KubernetesClientFactory.create(config)).isInstanceOf(IOException.class);
    }
}
