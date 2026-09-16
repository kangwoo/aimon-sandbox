package at.aimon.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DockerSandboxConfigTest {

    @Test
    void builder_DefaultValues() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        assertThat(config.getDockerHost()).isNull();
        assertThat(config.getMemoryLimit()).isEqualTo(512L * 1024 * 1024);
        assertThat(config.getCpuCount()).isEqualTo(1);
        assertThat(config.getPidsLimit()).isEqualTo(256);
        assertThat(config.getNetworkMode()).isEqualTo("none");
        assertThat(config.getDropCapabilities()).containsExactly("ALL");
        assertThat(config.getAddCapabilities()).isEmpty();
        assertThat(config.isNoNewPrivileges()).isTrue();
        assertThat(config.isReadonlyRootfs()).isFalse();
        assertThat(config.getTmpfsBinds()).containsEntry("/tmp", "size=100m,noexec");
        assertThat(config.getSandboxUser()).isEqualTo("sandbox");
        assertThat(config.getSandboxUid()).isEqualTo(1000);
    }

    @Test
    void builder_CustomValues() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().dockerHost("tcp://localhost:2375")
                .memoryLimit(1024L * 1024 * 1024).cpuCount(2).pidsLimit(512).networkMode("bridge")
                .dropCapabilities(List.of("NET_RAW")).addCapabilities(List.of("SYS_PTRACE")).noNewPrivileges(false)
                .readonlyRootfs(true).tmpfsBinds(Map.of("/tmp", "size=200m")).sandboxUser("appuser").sandboxUid(2000)
                .build();

        assertThat(config.getDockerHost()).isEqualTo("tcp://localhost:2375");
        assertThat(config.getMemoryLimit()).isEqualTo(1024L * 1024 * 1024);
        assertThat(config.getCpuCount()).isEqualTo(2);
        assertThat(config.getPidsLimit()).isEqualTo(512);
        assertThat(config.getNetworkMode()).isEqualTo("bridge");
        assertThat(config.getDropCapabilities()).containsExactly("NET_RAW");
        assertThat(config.getAddCapabilities()).containsExactly("SYS_PTRACE");
        assertThat(config.isNoNewPrivileges()).isFalse();
        assertThat(config.isReadonlyRootfs()).isTrue();
        assertThat(config.getTmpfsBinds()).containsEntry("/tmp", "size=200m");
        assertThat(config.getSandboxUser()).isEqualTo("appuser");
        assertThat(config.getSandboxUid()).isEqualTo(2000);
    }

    @Test
    void builder_MemoryLimitZero_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().memoryLimit(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memoryLimit must be > 0");
    }

    @Test
    void builder_MemoryLimitNegative_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().memoryLimit(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memoryLimit must be > 0");
    }

    @Test
    void builder_CpuCountZero_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().cpuCount(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cpuCount must be > 0");
    }

    @Test
    void builder_CpuCountNegative_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().cpuCount(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cpuCount must be > 0");
    }

    @Test
    void builder_PidsLimitZero_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().pidsLimit(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pidsLimit must be > 0");
    }

    @Test
    void builder_PidsLimitNegative_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().pidsLimit(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pidsLimit must be > 0");
    }

    @Test
    void dropCapabilities_IsImmutable() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        assertThatThrownBy(() -> config.getDropCapabilities().add("NET_RAW"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void addCapabilities_IsImmutable() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        assertThatThrownBy(() -> config.getAddCapabilities().add("SYS_PTRACE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tmpfsBinds_IsImmutable() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        assertThatThrownBy(() -> config.getTmpfsBinds().put("/var", "size=50m"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builder_SandboxUserNull_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().sandboxUser(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must not be null or blank");
    }

    @Test
    void builder_SandboxUserBlank_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().sandboxUser("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must not be null or blank");
    }

    @Test
    void builder_NetworkModeNull_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().networkMode(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("networkMode must not be null or blank");
    }

    @Test
    void builder_NetworkModeBlank_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().networkMode("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("networkMode must not be null or blank");
    }

    // --- sandboxUser pattern validation tests ---

    @Test
    void builder_SandboxUserValidPattern_Accepted() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().sandboxUser("app_user").build();
        assertThat(config.getSandboxUser()).isEqualTo("app_user");
    }

    @Test
    void builder_SandboxUserWithUnderscore_Accepted() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().sandboxUser("_backup").build();
        assertThat(config.getSandboxUser()).isEqualTo("_backup");
    }

    @Test
    void builder_SandboxUserWithHyphen_Accepted() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().sandboxUser("app-user").build();
        assertThat(config.getSandboxUser()).isEqualTo("app-user");
    }

    @Test
    void builder_SandboxUserWithDigits_Accepted() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().sandboxUser("user01").build();
        assertThat(config.getSandboxUser()).isEqualTo("user01");
    }

    @Test
    void builder_SandboxUserUpperCase_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().sandboxUser("Admin").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must match Linux username pattern");
    }

    @Test
    void builder_SandboxUserWithSpecialChars_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().sandboxUser("user;rm -rf").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must match Linux username pattern");
    }

    @Test
    void builder_SandboxUserStartsWithDigit_ThrowsException() {
        assertThatThrownBy(() -> DockerSandboxConfig.builder().sandboxUser("1user").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must match Linux username pattern");
    }

    @Test
    void builder_SandboxUserTooLong_ThrowsException() {
        String longName = "a".repeat(33);
        assertThatThrownBy(() -> DockerSandboxConfig.builder().sandboxUser(longName).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxUser must match Linux username pattern");
    }

    // --- equals()/hashCode() tests ---

    @Test
    void equals_SameValues_ReturnsTrue() {
        DockerSandboxConfig config1 = DockerSandboxConfig.builder().dockerHost("tcp://localhost:2375").memoryLimit(1024)
                .cpuCount(2).build();
        DockerSandboxConfig config2 = DockerSandboxConfig.builder().dockerHost("tcp://localhost:2375").memoryLimit(1024)
                .cpuCount(2).build();

        assertThat(config1).isEqualTo(config2);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void equals_DifferentMemoryLimit_ReturnsFalse() {
        DockerSandboxConfig config1 = DockerSandboxConfig.builder().memoryLimit(1024).build();
        DockerSandboxConfig config2 = DockerSandboxConfig.builder().memoryLimit(2048).build();

        assertThat(config1).isNotEqualTo(config2);
    }

    @Test
    void equals_DifferentDockerHost_ReturnsFalse() {
        DockerSandboxConfig config1 = DockerSandboxConfig.builder().dockerHost("tcp://host1:2375").build();
        DockerSandboxConfig config2 = DockerSandboxConfig.builder().dockerHost("tcp://host2:2375").build();

        assertThat(config1).isNotEqualTo(config2);
    }

    @Test
    void equals_Null_ReturnsFalse() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        assertThat(config).isNotEqualTo(null);
    }

    @Test
    void equals_DifferentClass_ReturnsFalse() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        assertThat(config).isNotEqualTo("not a config");
    }

    @Test
    void equals_SameInstance_ReturnsTrue() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();

        assertThat(config).isEqualTo(config);
    }

    @Test
    void toString_ContainsKeyFields() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().dockerHost("tcp://localhost:2375").build();

        assertThat(config.toString()).contains("tcp://localhost:2375");
        assertThat(config.toString()).contains("memoryLimit=");
        assertThat(config.toString()).contains("cpuCount=");
        assertThat(config.toString()).contains("networkMode=");
    }
}
