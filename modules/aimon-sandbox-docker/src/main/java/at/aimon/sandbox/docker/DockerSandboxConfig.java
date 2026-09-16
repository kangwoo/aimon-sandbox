package at.aimon.sandbox.docker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Docker-specific configuration for sandbox containers.
 *
 * <p>
 * Covers resource limits, network isolation, and security settings. Complements
 * {@link at.aimon.sandbox.config.SandboxConfig} which handles generic sandbox behavior (TTL, timeouts, images).
 *
 * <p>
 * Immutable value object.
 */
public final class DockerSandboxConfig {

    private static final Pattern SAFE_USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");

    private static final long DEFAULT_MEMORY_LIMIT = 512L * 1024 * 1024; // 512 MB
    private static final long DEFAULT_CPU_COUNT = 1;
    private static final long DEFAULT_PIDS_LIMIT = 256;
    private static final String DEFAULT_NETWORK_MODE = "none";
    private static final boolean DEFAULT_NO_NEW_PRIVILEGES = true;
    private static final boolean DEFAULT_READONLY_ROOTFS = false;
    private static final String DEFAULT_SANDBOX_USER = "sandbox";
    private static final int DEFAULT_SANDBOX_UID = 1000;

    private final String dockerHost;
    private final long memoryLimit;
    private final long cpuCount;
    private final long pidsLimit;
    private final String networkMode;
    private final List<String> dropCapabilities;
    private final List<String> addCapabilities;
    private final boolean noNewPrivileges;
    private final boolean readonlyRootfs;
    private final Map<String, String> tmpfsBinds;
    private final String sandboxUser;
    private final int sandboxUid;

    private DockerSandboxConfig(Builder builder) {
        this.dockerHost = builder.dockerHost;
        this.memoryLimit = builder.memoryLimit;
        this.cpuCount = builder.cpuCount;
        this.pidsLimit = builder.pidsLimit;
        this.networkMode = builder.networkMode;
        this.dropCapabilities = Collections.unmodifiableList(new ArrayList<>(builder.dropCapabilities));
        this.addCapabilities = Collections.unmodifiableList(new ArrayList<>(builder.addCapabilities));
        this.noNewPrivileges = builder.noNewPrivileges;
        this.readonlyRootfs = builder.readonlyRootfs;
        this.tmpfsBinds = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tmpfsBinds));
        this.sandboxUser = builder.sandboxUser;
        this.sandboxUid = builder.sandboxUid;
        validate();
    }

    private void validate() {
        if (memoryLimit <= 0) {
            throw new IllegalArgumentException("memoryLimit must be > 0, got: " + memoryLimit);
        }
        if (cpuCount <= 0) {
            throw new IllegalArgumentException("cpuCount must be > 0, got: " + cpuCount);
        }
        if (pidsLimit <= 0) {
            throw new IllegalArgumentException("pidsLimit must be > 0, got: " + pidsLimit);
        }
        if (sandboxUser == null || sandboxUser.isBlank()) {
            throw new IllegalArgumentException("sandboxUser must not be null or blank");
        }
        if (!SAFE_USERNAME_PATTERN.matcher(sandboxUser).matches()) {
            throw new IllegalArgumentException(
                    "sandboxUser must match Linux username pattern [a-z_][a-z0-9_-]* (max 32 chars), got: "
                            + sandboxUser);
        }
        if (networkMode == null || networkMode.isBlank()) {
            throw new IllegalArgumentException("networkMode must not be null or blank");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Docker host URI, or {@code null} for auto-detect. */
    public String getDockerHost() {
        return dockerHost;
    }

    public long getMemoryLimit() {
        return memoryLimit;
    }

    public long getCpuCount() {
        return cpuCount;
    }

    public long getPidsLimit() {
        return pidsLimit;
    }

    public String getNetworkMode() {
        return networkMode;
    }

    public List<String> getDropCapabilities() {
        return dropCapabilities;
    }

    public List<String> getAddCapabilities() {
        return addCapabilities;
    }

    public boolean isNoNewPrivileges() {
        return noNewPrivileges;
    }

    public boolean isReadonlyRootfs() {
        return readonlyRootfs;
    }

    public Map<String, String> getTmpfsBinds() {
        return tmpfsBinds;
    }

    public String getSandboxUser() {
        return sandboxUser;
    }

    public int getSandboxUid() {
        return sandboxUid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DockerSandboxConfig that = (DockerSandboxConfig) o;
        return memoryLimit == that.memoryLimit && cpuCount == that.cpuCount && pidsLimit == that.pidsLimit
                && noNewPrivileges == that.noNewPrivileges && readonlyRootfs == that.readonlyRootfs
                && sandboxUid == that.sandboxUid && Objects.equals(dockerHost, that.dockerHost)
                && Objects.equals(networkMode, that.networkMode)
                && Objects.equals(dropCapabilities, that.dropCapabilities)
                && Objects.equals(addCapabilities, that.addCapabilities) && Objects.equals(tmpfsBinds, that.tmpfsBinds)
                && Objects.equals(sandboxUser, that.sandboxUser);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dockerHost, memoryLimit, cpuCount, pidsLimit, networkMode, dropCapabilities,
                addCapabilities, noNewPrivileges, readonlyRootfs, tmpfsBinds, sandboxUser, sandboxUid);
    }

    @Override
    public String toString() {
        return "DockerSandboxConfig{" + "dockerHost='" + dockerHost + "', memoryLimit=" + memoryLimit + ", cpuCount="
                + cpuCount + ", networkMode='" + networkMode + "'}";
    }

    /** Builder for DockerSandboxConfig. */
    public static final class Builder {

        private String dockerHost;
        private long memoryLimit = DEFAULT_MEMORY_LIMIT;
        private long cpuCount = DEFAULT_CPU_COUNT;
        private long pidsLimit = DEFAULT_PIDS_LIMIT;
        private String networkMode = DEFAULT_NETWORK_MODE;
        private List<String> dropCapabilities = List.of("ALL");
        private List<String> addCapabilities = List.of();
        private boolean noNewPrivileges = DEFAULT_NO_NEW_PRIVILEGES;
        private boolean readonlyRootfs = DEFAULT_READONLY_ROOTFS;
        private Map<String, String> tmpfsBinds = Map.of("/tmp", "size=100m,noexec");
        private String sandboxUser = DEFAULT_SANDBOX_USER;
        private int sandboxUid = DEFAULT_SANDBOX_UID;

        private Builder() {
        }

        public Builder dockerHost(String dockerHost) {
            this.dockerHost = dockerHost;
            return this;
        }

        public Builder memoryLimit(long memoryLimit) {
            this.memoryLimit = memoryLimit;
            return this;
        }

        public Builder cpuCount(long cpuCount) {
            this.cpuCount = cpuCount;
            return this;
        }

        public Builder pidsLimit(long pidsLimit) {
            this.pidsLimit = pidsLimit;
            return this;
        }

        public Builder networkMode(String networkMode) {
            this.networkMode = networkMode;
            return this;
        }

        public Builder dropCapabilities(List<String> dropCapabilities) {
            this.dropCapabilities = dropCapabilities;
            return this;
        }

        public Builder addCapabilities(List<String> addCapabilities) {
            this.addCapabilities = addCapabilities;
            return this;
        }

        public Builder noNewPrivileges(boolean noNewPrivileges) {
            this.noNewPrivileges = noNewPrivileges;
            return this;
        }

        public Builder readonlyRootfs(boolean readonlyRootfs) {
            this.readonlyRootfs = readonlyRootfs;
            return this;
        }

        public Builder tmpfsBinds(Map<String, String> tmpfsBinds) {
            this.tmpfsBinds = tmpfsBinds;
            return this;
        }

        public Builder sandboxUser(String sandboxUser) {
            this.sandboxUser = sandboxUser;
            return this;
        }

        public Builder sandboxUid(int sandboxUid) {
            this.sandboxUid = sandboxUid;
            return this;
        }

        public DockerSandboxConfig build() {
            return new DockerSandboxConfig(this);
        }
    }
}
