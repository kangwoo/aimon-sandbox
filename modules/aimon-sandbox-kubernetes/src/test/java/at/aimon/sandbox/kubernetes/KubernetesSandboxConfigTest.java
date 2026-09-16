package at.aimon.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KubernetesSandboxConfigTest {

    @Test
    void builder_DefaultValues() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThat(config.getKubeConfigPath()).isNull();
        assertThat(config.getNamespace()).isEqualTo("default");
        assertThat(config.getMemoryLimit()).isEqualTo("512Mi");
        assertThat(config.getMemoryRequest()).isEqualTo("256Mi");
        assertThat(config.getCpuLimit()).isEqualTo("1");
        assertThat(config.getCpuRequest()).isEqualTo("500m");
        assertThat(config.getDropCapabilities()).containsExactly("ALL");
        assertThat(config.getAddCapabilities()).isEmpty();
        assertThat(config.isAllowPrivilegeEscalation()).isFalse();
        assertThat(config.isReadOnlyRootFilesystem()).isFalse();
        assertThat(config.getServiceAccountName()).isNull();
        assertThat(config.getNodeSelector()).isEmpty();
        assertThat(config.getPodReadyTimeoutMs()).isEqualTo(60_000);
        assertThat(config.getSandboxUser()).isEqualTo("sandbox");
        assertThat(config.getSandboxUid()).isEqualTo(1000);
    }

    @Test
    void builder_CustomValues() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().kubeConfigPath("/home/user/.kube/config")
                .namespace("sandbox-ns").memoryLimit("1Gi").memoryRequest("512Mi").cpuLimit("2").cpuRequest("1")
                .dropCapabilities(List.of("NET_RAW")).addCapabilities(List.of("SYS_PTRACE"))
                .allowPrivilegeEscalation(true).readOnlyRootFilesystem(true).serviceAccountName("sandbox-sa")
                .nodeSelector(Map.of("node-type", "sandbox")).podReadyTimeoutMs(120_000).sandboxUser("appuser")
                .sandboxUid(2000).build();

        assertThat(config.getKubeConfigPath()).isEqualTo("/home/user/.kube/config");
        assertThat(config.getNamespace()).isEqualTo("sandbox-ns");
        assertThat(config.getMemoryLimit()).isEqualTo("1Gi");
        assertThat(config.getMemoryRequest()).isEqualTo("512Mi");
        assertThat(config.getCpuLimit()).isEqualTo("2");
        assertThat(config.getCpuRequest()).isEqualTo("1");
        assertThat(config.getDropCapabilities()).containsExactly("NET_RAW");
        assertThat(config.getAddCapabilities()).containsExactly("SYS_PTRACE");
        assertThat(config.isAllowPrivilegeEscalation()).isTrue();
        assertThat(config.isReadOnlyRootFilesystem()).isTrue();
        assertThat(config.getServiceAccountName()).isEqualTo("sandbox-sa");
        assertThat(config.getNodeSelector()).containsEntry("node-type", "sandbox");
        assertThat(config.getPodReadyTimeoutMs()).isEqualTo(120_000);
        assertThat(config.getSandboxUser()).isEqualTo("appuser");
        assertThat(config.getSandboxUid()).isEqualTo(2000);
    }

    @Test
    void builder_NullNamespace_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().namespace(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace must not be null or empty");
    }

    @Test
    void builder_EmptyNamespace_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().namespace("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace must not be null or empty");
    }

    @Test
    void builder_NullSandboxUser_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().sandboxUser(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must not be null or empty");
    }

    @Test
    void builder_EmptySandboxUser_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().sandboxUser("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must not be null or empty");
    }

    @Test
    void builder_PodReadyTimeoutZero_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().podReadyTimeoutMs(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("podReadyTimeoutMs must be > 0");
    }

    @Test
    void builder_PodReadyTimeoutNegative_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().podReadyTimeoutMs(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("podReadyTimeoutMs must be > 0");
    }

    @Test
    void dropCapabilities_IsImmutable() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThatThrownBy(() -> config.getDropCapabilities().add("NET_RAW"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void addCapabilities_IsImmutable() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThatThrownBy(() -> config.getAddCapabilities().add("SYS_PTRACE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nodeSelector_IsImmutable() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThatThrownBy(() -> config.getNodeSelector().put("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toString_ContainsKeyFields() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().namespace("test-ns").build();

        assertThat(config.toString()).contains("test-ns");
        assertThat(config.toString()).contains("memoryLimit=");
        assertThat(config.toString()).contains("cpuLimit=");
        assertThat(config.toString()).contains("podReadyTimeoutMs=");
    }

    // --- equals() / hashCode() tests ---

    @Test
    void equals_SameValues_ReturnsTrue() {
        KubernetesSandboxConfig config1 = KubernetesSandboxConfig.builder().namespace("ns1").build();
        KubernetesSandboxConfig config2 = KubernetesSandboxConfig.builder().namespace("ns1").build();

        assertThat(config1).isEqualTo(config2);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void equals_DifferentValues_ReturnsFalse() {
        KubernetesSandboxConfig config1 = KubernetesSandboxConfig.builder().namespace("ns1").build();
        KubernetesSandboxConfig config2 = KubernetesSandboxConfig.builder().namespace("ns2").build();

        assertThat(config1).isNotEqualTo(config2);
    }

    @Test
    void equals_SameInstance_ReturnsTrue() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThat(config).isEqualTo(config);
    }

    @Test
    void equals_Null_ReturnsFalse() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThat(config).isNotEqualTo(null);
    }

    @Test
    void equals_DifferentType_ReturnsFalse() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThat(config).isNotEqualTo("not a config");
    }

    // --- API timeout configuration tests ---

    @Test
    void builder_DefaultApiTimeoutValues() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();

        assertThat(config.getApiConnectionTimeoutMs()).isEqualTo(10_000);
        assertThat(config.getApiReadTimeoutMs()).isEqualTo(30_000);
    }

    @Test
    void builder_CustomApiTimeoutValues() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().apiConnectionTimeoutMs(5000)
                .apiReadTimeoutMs(15000).build();

        assertThat(config.getApiConnectionTimeoutMs()).isEqualTo(5000);
        assertThat(config.getApiReadTimeoutMs()).isEqualTo(15000);
    }

    @Test
    void builder_ApiConnectionTimeoutZero_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().apiConnectionTimeoutMs(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiConnectionTimeoutMs must be > 0");
    }

    @Test
    void builder_ApiReadTimeoutNegative_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().apiReadTimeoutMs(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("apiReadTimeoutMs must be > 0");
    }

    // --- Resource quantity validation tests ---

    @Test
    void builder_InvalidMemoryLimit_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().memoryLimit("abc").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryLimit must be a valid Kubernetes resource quantity");
    }

    @Test
    void builder_InvalidCpuRequest_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().cpuRequest("invalid").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cpuRequest must be a valid Kubernetes resource quantity");
    }

    @Test
    void builder_EmptyMemoryLimit_ThrowsException() {
        assertThatThrownBy(() -> KubernetesSandboxConfig.builder().memoryLimit("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryLimit must not be null or empty");
    }

    @Test
    void builder_ValidQuantityFormats() {
        // Should not throw for valid Kubernetes quantity formats
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().memoryLimit("1Gi").memoryRequest("256Mi")
                .cpuLimit("2").cpuRequest("500m").build();

        assertThat(config.getMemoryLimit()).isEqualTo("1Gi");
        assertThat(config.getCpuRequest()).isEqualTo("500m");
    }

    @Test
    void builder_DecimalQuantityFormat() {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().cpuLimit("0.5").cpuRequest("0.25").build();

        assertThat(config.getCpuLimit()).isEqualTo("0.5");
        assertThat(config.getCpuRequest()).isEqualTo("0.25");
    }
}
