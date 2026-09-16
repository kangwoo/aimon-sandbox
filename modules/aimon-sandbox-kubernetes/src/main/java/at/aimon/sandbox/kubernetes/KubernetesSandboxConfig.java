package at.aimon.sandbox.kubernetes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Kubernetes-specific configuration for sandbox pods.
 *
 * <p>
 * Covers resource requests/limits, security context, namespace, and node scheduling. Complements
 * {@link at.aimon.sandbox.config.SandboxConfig} which handles generic sandbox behavior (TTL, timeouts, images).
 *
 * <p>
 * Immutable value object.
 */
public final class KubernetesSandboxConfig {

    /** Matches Kubernetes resource quantities such as {@code 512Mi}, {@code 1}, {@code 500m}, {@code 0.5}. */
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("\\d+(\\.\\d+)?([EPTGMK]i?|[epmunf])?");

    private static final String DEFAULT_NAMESPACE = "default";
    private static final String DEFAULT_MEMORY_LIMIT = "512Mi";
    private static final String DEFAULT_MEMORY_REQUEST = "256Mi";
    private static final String DEFAULT_CPU_LIMIT = "1";
    private static final String DEFAULT_CPU_REQUEST = "500m";
    private static final boolean DEFAULT_ALLOW_PRIVILEGE_ESCALATION = false;
    private static final boolean DEFAULT_READ_ONLY_ROOT_FILESYSTEM = false;
    private static final long DEFAULT_POD_READY_TIMEOUT_MS = 60_000;
    private static final String DEFAULT_SANDBOX_USER = "sandbox";
    private static final int DEFAULT_SANDBOX_UID = 1000;
    private static final int DEFAULT_API_CONNECTION_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_API_READ_TIMEOUT_MS = 30_000;

    private final String kubeConfigPath;
    private final String namespace;
    private final String memoryLimit;
    private final String memoryRequest;
    private final String cpuLimit;
    private final String cpuRequest;
    private final List<String> dropCapabilities;
    private final List<String> addCapabilities;
    private final boolean allowPrivilegeEscalation;
    private final boolean readOnlyRootFilesystem;
    private final String serviceAccountName;
    private final Map<String, String> nodeSelector;
    private final long podReadyTimeoutMs;
    private final String sandboxUser;
    private final int sandboxUid;
    private final int apiConnectionTimeoutMs;
    private final int apiReadTimeoutMs;

    private KubernetesSandboxConfig(Builder builder) {
        this.kubeConfigPath = builder.kubeConfigPath;
        this.namespace = builder.namespace;
        this.memoryLimit = builder.memoryLimit;
        this.memoryRequest = builder.memoryRequest;
        this.cpuLimit = builder.cpuLimit;
        this.cpuRequest = builder.cpuRequest;
        this.dropCapabilities = Collections.unmodifiableList(new ArrayList<>(builder.dropCapabilities));
        this.addCapabilities = Collections.unmodifiableList(new ArrayList<>(builder.addCapabilities));
        this.allowPrivilegeEscalation = builder.allowPrivilegeEscalation;
        this.readOnlyRootFilesystem = builder.readOnlyRootFilesystem;
        this.serviceAccountName = builder.serviceAccountName;
        this.nodeSelector = Collections.unmodifiableMap(new LinkedHashMap<>(builder.nodeSelector));
        this.podReadyTimeoutMs = builder.podReadyTimeoutMs;
        this.sandboxUser = builder.sandboxUser;
        this.sandboxUid = builder.sandboxUid;
        this.apiConnectionTimeoutMs = builder.apiConnectionTimeoutMs;
        this.apiReadTimeoutMs = builder.apiReadTimeoutMs;
        validate();
    }

    private void validate() {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be null or empty");
        }
        if (sandboxUser == null || sandboxUser.isEmpty()) {
            throw new IllegalArgumentException("sandboxUser must not be null or empty");
        }
        if (podReadyTimeoutMs <= 0) {
            throw new IllegalArgumentException("podReadyTimeoutMs must be > 0, got: " + podReadyTimeoutMs);
        }
        if (apiConnectionTimeoutMs <= 0) {
            throw new IllegalArgumentException("apiConnectionTimeoutMs must be > 0, got: " + apiConnectionTimeoutMs);
        }
        if (apiReadTimeoutMs <= 0) {
            throw new IllegalArgumentException("apiReadTimeoutMs must be > 0, got: " + apiReadTimeoutMs);
        }
        validateQuantity("memoryLimit", memoryLimit);
        validateQuantity("memoryRequest", memoryRequest);
        validateQuantity("cpuLimit", cpuLimit);
        validateQuantity("cpuRequest", cpuRequest);
    }

    private static void validateQuantity(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
        if (!QUANTITY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a valid Kubernetes resource quantity, got: " + value);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Kubeconfig file path, or {@code null} for in-cluster / default discovery. */
    public String getKubeConfigPath() {
        return kubeConfigPath;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public String getMemoryRequest() {
        return memoryRequest;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }

    public String getCpuRequest() {
        return cpuRequest;
    }

    public List<String> getDropCapabilities() {
        return dropCapabilities;
    }

    public List<String> getAddCapabilities() {
        return addCapabilities;
    }

    public boolean isAllowPrivilegeEscalation() {
        return allowPrivilegeEscalation;
    }

    public boolean isReadOnlyRootFilesystem() {
        return readOnlyRootFilesystem;
    }

    /** Pod ServiceAccount name, or {@code null} for namespace default. */
    public String getServiceAccountName() {
        return serviceAccountName;
    }

    public Map<String, String> getNodeSelector() {
        return nodeSelector;
    }

    public long getPodReadyTimeoutMs() {
        return podReadyTimeoutMs;
    }

    public String getSandboxUser() {
        return sandboxUser;
    }

    public int getSandboxUid() {
        return sandboxUid;
    }

    /** Connection timeout in milliseconds for the Kubernetes API client. */
    public int getApiConnectionTimeoutMs() {
        return apiConnectionTimeoutMs;
    }

    /** Read timeout in milliseconds for the Kubernetes API client. */
    public int getApiReadTimeoutMs() {
        return apiReadTimeoutMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KubernetesSandboxConfig)) {
            return false;
        }
        KubernetesSandboxConfig that = (KubernetesSandboxConfig) o;
        return allowPrivilegeEscalation == that.allowPrivilegeEscalation
                && readOnlyRootFilesystem == that.readOnlyRootFilesystem && podReadyTimeoutMs == that.podReadyTimeoutMs
                && sandboxUid == that.sandboxUid && apiConnectionTimeoutMs == that.apiConnectionTimeoutMs
                && apiReadTimeoutMs == that.apiReadTimeoutMs && Objects.equals(kubeConfigPath, that.kubeConfigPath)
                && Objects.equals(namespace, that.namespace) && Objects.equals(memoryLimit, that.memoryLimit)
                && Objects.equals(memoryRequest, that.memoryRequest) && Objects.equals(cpuLimit, that.cpuLimit)
                && Objects.equals(cpuRequest, that.cpuRequest)
                && Objects.equals(dropCapabilities, that.dropCapabilities)
                && Objects.equals(addCapabilities, that.addCapabilities)
                && Objects.equals(serviceAccountName, that.serviceAccountName)
                && Objects.equals(nodeSelector, that.nodeSelector) && Objects.equals(sandboxUser, that.sandboxUser);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kubeConfigPath, namespace, memoryLimit, memoryRequest, cpuLimit, cpuRequest,
                dropCapabilities, addCapabilities, allowPrivilegeEscalation, readOnlyRootFilesystem, serviceAccountName,
                nodeSelector, podReadyTimeoutMs, sandboxUser, sandboxUid, apiConnectionTimeoutMs, apiReadTimeoutMs);
    }

    @Override
    public String toString() {
        return "KubernetesSandboxConfig{" + "namespace='" + namespace + "', memoryLimit='" + memoryLimit
                + "', cpuLimit='" + cpuLimit + "', podReadyTimeoutMs=" + podReadyTimeoutMs + "}";
    }

    /** Builder for KubernetesSandboxConfig. */
    public static final class Builder {

        private String kubeConfigPath;
        private String namespace = DEFAULT_NAMESPACE;
        private String memoryLimit = DEFAULT_MEMORY_LIMIT;
        private String memoryRequest = DEFAULT_MEMORY_REQUEST;
        private String cpuLimit = DEFAULT_CPU_LIMIT;
        private String cpuRequest = DEFAULT_CPU_REQUEST;
        private List<String> dropCapabilities = List.of("ALL");
        private List<String> addCapabilities = List.of();
        private boolean allowPrivilegeEscalation = DEFAULT_ALLOW_PRIVILEGE_ESCALATION;
        private boolean readOnlyRootFilesystem = DEFAULT_READ_ONLY_ROOT_FILESYSTEM;
        private String serviceAccountName;
        private Map<String, String> nodeSelector = Map.of();
        private long podReadyTimeoutMs = DEFAULT_POD_READY_TIMEOUT_MS;
        private String sandboxUser = DEFAULT_SANDBOX_USER;
        private int sandboxUid = DEFAULT_SANDBOX_UID;
        private int apiConnectionTimeoutMs = DEFAULT_API_CONNECTION_TIMEOUT_MS;
        private int apiReadTimeoutMs = DEFAULT_API_READ_TIMEOUT_MS;

        private Builder() {
        }

        public Builder kubeConfigPath(String kubeConfigPath) {
            this.kubeConfigPath = kubeConfigPath;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder memoryLimit(String memoryLimit) {
            this.memoryLimit = memoryLimit;
            return this;
        }

        public Builder memoryRequest(String memoryRequest) {
            this.memoryRequest = memoryRequest;
            return this;
        }

        public Builder cpuLimit(String cpuLimit) {
            this.cpuLimit = cpuLimit;
            return this;
        }

        public Builder cpuRequest(String cpuRequest) {
            this.cpuRequest = cpuRequest;
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

        public Builder allowPrivilegeEscalation(boolean allowPrivilegeEscalation) {
            this.allowPrivilegeEscalation = allowPrivilegeEscalation;
            return this;
        }

        public Builder readOnlyRootFilesystem(boolean readOnlyRootFilesystem) {
            this.readOnlyRootFilesystem = readOnlyRootFilesystem;
            return this;
        }

        public Builder serviceAccountName(String serviceAccountName) {
            this.serviceAccountName = serviceAccountName;
            return this;
        }

        public Builder nodeSelector(Map<String, String> nodeSelector) {
            this.nodeSelector = nodeSelector;
            return this;
        }

        public Builder podReadyTimeoutMs(long podReadyTimeoutMs) {
            this.podReadyTimeoutMs = podReadyTimeoutMs;
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

        public Builder apiConnectionTimeoutMs(int apiConnectionTimeoutMs) {
            this.apiConnectionTimeoutMs = apiConnectionTimeoutMs;
            return this;
        }

        public Builder apiReadTimeoutMs(int apiReadTimeoutMs) {
            this.apiReadTimeoutMs = apiReadTimeoutMs;
            return this;
        }

        public KubernetesSandboxConfig build() {
            return new KubernetesSandboxConfig(this);
        }
    }
}
