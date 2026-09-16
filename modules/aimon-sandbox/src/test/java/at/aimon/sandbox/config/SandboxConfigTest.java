package at.aimon.sandbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SandboxConfigTest {

    @Test
    void builder_DefaultValues() {
        SandboxConfig config = SandboxConfig.builder().build();

        assertThat(config.getDefaultTtlSeconds()).isEqualTo(1800);
        assertThat(config.getMaxTtlSeconds()).isEqualTo(86400);
        assertThat(config.getDefaultCommandTimeoutMs()).isEqualTo(120_000);
        assertThat(config.getMaxCommandTimeoutMs()).isEqualTo(600_000);
        assertThat(config.getDefaultImage()).isEqualTo("ubuntu:22.04");
        assertThat(config.getDefaultCwd()).isEqualTo("/workspace");
        assertThat(config.getReaperIntervalMs()).isEqualTo(5000);
        assertThat(config.isDefaultLockSandbox()).isTrue();
    }

    @Test
    void builder_CustomValues() {
        SandboxConfig config = SandboxConfig.builder().defaultTtlSeconds(3600).maxTtlSeconds(7200)
                .defaultCommandTimeoutMs(60_000).maxCommandTimeoutMs(300_000).defaultImage("python:3.11")
                .defaultCwd("/app").reaperIntervalMs(10_000).defaultLockSandbox(false).build();

        assertThat(config.getDefaultTtlSeconds()).isEqualTo(3600);
        assertThat(config.getMaxTtlSeconds()).isEqualTo(7200);
        assertThat(config.getDefaultCommandTimeoutMs()).isEqualTo(60_000);
        assertThat(config.getMaxCommandTimeoutMs()).isEqualTo(300_000);
        assertThat(config.getDefaultImage()).isEqualTo("python:3.11");
        assertThat(config.getDefaultCwd()).isEqualTo("/app");
        assertThat(config.getReaperIntervalMs()).isEqualTo(10_000);
        assertThat(config.isDefaultLockSandbox()).isFalse();
    }

    @Test
    void builder_DefaultTtlSecondsZero_ThrowsException() {
        assertThatThrownBy(() -> SandboxConfig.builder().defaultTtlSeconds(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultTtlSeconds must be > 0");
    }

    @Test
    void builder_DefaultTtlSecondsNegative_ThrowsException() {
        assertThatThrownBy(() -> SandboxConfig.builder().defaultTtlSeconds(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultTtlSeconds must be > 0");
    }

    @Test
    void builder_MaxTtlSecondsLessThanDefault_ThrowsException() {
        assertThatThrownBy(() -> SandboxConfig.builder().defaultTtlSeconds(100).maxTtlSeconds(50).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTtlSeconds must be >= defaultTtlSeconds");
    }

    @Test
    void builder_DefaultCommandTimeoutMsZero_ThrowsException() {
        assertThatThrownBy(() -> SandboxConfig.builder().defaultCommandTimeoutMs(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultCommandTimeoutMs must be > 0");
    }

    @Test
    void builder_DefaultCommandTimeoutMsNegative_ThrowsException() {
        assertThatThrownBy(() -> SandboxConfig.builder().defaultCommandTimeoutMs(-100).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultCommandTimeoutMs must be > 0");
    }

    @Test
    void builder_MaxCommandTimeoutMsLessThanDefault_ThrowsException() {
        assertThatThrownBy(
                () -> SandboxConfig.builder().defaultCommandTimeoutMs(60_000).maxCommandTimeoutMs(30_000).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCommandTimeoutMs must be >= defaultCommandTimeoutMs");
    }

    @Test
    void builder_ReaperIntervalMsZero_ThrowsException() {
        assertThatThrownBy(() -> SandboxConfig.builder().reaperIntervalMs(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reaperIntervalMs must be > 0");
    }

    @Test
    void builder_ReaperIntervalMsNegative_ThrowsException() {
        assertThatThrownBy(() -> SandboxConfig.builder().reaperIntervalMs(-500).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reaperIntervalMs must be > 0");
    }

    @Test
    void builder_BoundaryValues_EqualTtl_Succeeds() {
        SandboxConfig config = SandboxConfig.builder().defaultTtlSeconds(100).maxTtlSeconds(100).build();

        assertThat(config.getDefaultTtlSeconds()).isEqualTo(100);
        assertThat(config.getMaxTtlSeconds()).isEqualTo(100);
    }

    @Test
    void builder_BoundaryValues_EqualCommandTimeout_Succeeds() {
        SandboxConfig config = SandboxConfig.builder().defaultCommandTimeoutMs(5000).maxCommandTimeoutMs(5000).build();

        assertThat(config.getDefaultCommandTimeoutMs()).isEqualTo(5000);
        assertThat(config.getMaxCommandTimeoutMs()).isEqualTo(5000);
    }
}
