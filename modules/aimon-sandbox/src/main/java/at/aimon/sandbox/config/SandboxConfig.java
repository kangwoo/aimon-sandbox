package at.aimon.sandbox.config;

import java.util.Objects;

/**
 * Configuration for the sandbox system.
 *
 * <p>
 * Immutable value object.
 */
public final class SandboxConfig {

    private static final int DEFAULT_TTL_SECONDS = 1800;
    private static final int DEFAULT_MAX_TTL_SECONDS = 86400;
    private static final int DEFAULT_COMMAND_TIMEOUT_MS = 120_000;
    private static final int DEFAULT_MAX_COMMAND_TIMEOUT_MS = 600_000;
    private static final String DEFAULT_IMAGE = "ubuntu:22.04";
    private static final String DEFAULT_CWD = "/workspace";
    private static final long DEFAULT_REAPER_INTERVAL_MS = 5000;

    private final int defaultTtlSeconds;
    private final int maxTtlSeconds;
    private final int defaultCommandTimeoutMs;
    private final int maxCommandTimeoutMs;
    private final String defaultImage;
    private final String defaultCwd;
    private final long reaperIntervalMs;
    private final boolean defaultLockSandbox;

    private SandboxConfig(Builder builder) {
        this.defaultTtlSeconds = builder.defaultTtlSeconds;
        this.maxTtlSeconds = builder.maxTtlSeconds;
        this.defaultCommandTimeoutMs = builder.defaultCommandTimeoutMs;
        this.maxCommandTimeoutMs = builder.maxCommandTimeoutMs;
        this.defaultImage = Objects.requireNonNull(builder.defaultImage, "Default image cannot be null");
        this.defaultCwd = Objects.requireNonNull(builder.defaultCwd, "Default cwd cannot be null");
        this.reaperIntervalMs = builder.reaperIntervalMs;
        this.defaultLockSandbox = builder.defaultLockSandbox;
        validate();
    }

    private void validate() {
        if (defaultTtlSeconds <= 0) {
            throw new IllegalArgumentException("defaultTtlSeconds must be > 0, got: " + defaultTtlSeconds);
        }
        if (maxTtlSeconds < defaultTtlSeconds) {
            throw new IllegalArgumentException(
                    "maxTtlSeconds must be >= defaultTtlSeconds, got: " + maxTtlSeconds + " < " + defaultTtlSeconds);
        }
        if (defaultCommandTimeoutMs <= 0) {
            throw new IllegalArgumentException("defaultCommandTimeoutMs must be > 0, got: " + defaultCommandTimeoutMs);
        }
        if (maxCommandTimeoutMs < defaultCommandTimeoutMs) {
            throw new IllegalArgumentException("maxCommandTimeoutMs must be >= defaultCommandTimeoutMs, got: "
                    + maxCommandTimeoutMs + " < " + defaultCommandTimeoutMs);
        }
        if (reaperIntervalMs <= 0) {
            throw new IllegalArgumentException("reaperIntervalMs must be > 0, got: " + reaperIntervalMs);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public int getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public int getDefaultCommandTimeoutMs() {
        return defaultCommandTimeoutMs;
    }

    public int getMaxCommandTimeoutMs() {
        return maxCommandTimeoutMs;
    }

    public String getDefaultImage() {
        return defaultImage;
    }

    public String getDefaultCwd() {
        return defaultCwd;
    }

    public long getReaperIntervalMs() {
        return reaperIntervalMs;
    }

    public boolean isDefaultLockSandbox() {
        return defaultLockSandbox;
    }

    @Override
    public String toString() {
        return "SandboxConfig{" + "defaultTtlSeconds=" + defaultTtlSeconds + ", maxTtlSeconds=" + maxTtlSeconds
                + ", defaultImage='" + defaultImage + "'}";
    }

    /** Builder for SandboxConfig. */
    public static final class Builder {

        private int defaultTtlSeconds = DEFAULT_TTL_SECONDS;
        private int maxTtlSeconds = DEFAULT_MAX_TTL_SECONDS;
        private int defaultCommandTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS;
        private int maxCommandTimeoutMs = DEFAULT_MAX_COMMAND_TIMEOUT_MS;
        private String defaultImage = DEFAULT_IMAGE;
        private String defaultCwd = DEFAULT_CWD;
        private long reaperIntervalMs = DEFAULT_REAPER_INTERVAL_MS;
        private boolean defaultLockSandbox = true;

        private Builder() {
        }

        public Builder defaultTtlSeconds(int defaultTtlSeconds) {
            this.defaultTtlSeconds = defaultTtlSeconds;
            return this;
        }

        public Builder maxTtlSeconds(int maxTtlSeconds) {
            this.maxTtlSeconds = maxTtlSeconds;
            return this;
        }

        public Builder defaultCommandTimeoutMs(int defaultCommandTimeoutMs) {
            this.defaultCommandTimeoutMs = defaultCommandTimeoutMs;
            return this;
        }

        public Builder maxCommandTimeoutMs(int maxCommandTimeoutMs) {
            this.maxCommandTimeoutMs = maxCommandTimeoutMs;
            return this;
        }

        public Builder defaultImage(String defaultImage) {
            this.defaultImage = defaultImage;
            return this;
        }

        public Builder defaultCwd(String defaultCwd) {
            this.defaultCwd = defaultCwd;
            return this;
        }

        public Builder reaperIntervalMs(long reaperIntervalMs) {
            this.reaperIntervalMs = reaperIntervalMs;
            return this;
        }

        public Builder defaultLockSandbox(boolean defaultLockSandbox) {
            this.defaultLockSandbox = defaultLockSandbox;
            return this;
        }

        public SandboxConfig build() {
            return new SandboxConfig(this);
        }
    }
}
