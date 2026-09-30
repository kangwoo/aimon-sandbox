package at.aimon.sandbox.opensandbox;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.SandboxConfigurationException;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CredentialScope;
import at.aimon.sandbox.provider.HostPort;

/**
 * The provider's configuration — the {@code aimon.sandbox.opensandbox.*} keys of docs/design/workspace-sandbox.md
 * §13.2 ({@code max-expiry} is {@link #maxExpiry()}, and so on).
 *
 * <p>
 * Capabilities are <b>derived from these declarations</b>, never probed at startup (§6.4): the server exposes none of
 * the deciding facts before a sandbox exists. What can be checked on a live sandbox is checked once per generation by
 * {@link OpenSandboxProvider#verify}. {@link Builder#build()} refuses a declaration the runtime cannot honour and
 * lists every violation at once:
 * <ul>
 * <li>{@code network-isolation}, {@code hardened-security-context} and {@code runtime-class} on the {@code docker}
 * runtime — the Docker runtime has no way to provide them (docs/design/opensandbox-spike.md §1, §7);</li>
 * <li>{@code egress-enforcement: dns+nft} with a gVisor runtime class — the server refuses every {@code networkPolicy}
 * then (gVisor's netstack has no iptables nat table);</li>
 * <li>{@code credentials} without {@code dns+nft} — the server refuses {@code credentialProxy} then;</li>
 * <li>{@code network-isolation: declared} with an empty {@code control-plane-probes} list — the seed's probe would
 * check nothing.</li>
 * </ul>
 */
public final class OpenSandboxProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenSandboxProviderConfig.class);

    /** The OpenSandbox runtime the server runs sandboxes on; it decides which declarations are legal. */
    public enum Runtime {
        /** {@code runtime.type = "docker"}: local development only. */
        DOCKER,
        /** {@code runtime.type = "kubernetes"}. */
        KUBERNETES
    }

    /** What the server's {@code [egress].mode} enforces. */
    public enum EgressEnforcement {
        /** No egress sidecar is configured: {@code EGRESS_POLICY} is not advertised. */
        NONE,
        /** DNS filtering only; leaks by IP, so {@code EGRESS_POLICY} is not advertised. */
        DNS,
        /** DNS filtering plus nftables: {@code EGRESS_POLICY} is advertised. */
        DNS_NFT
    }

    /** An operator declaration: something the operator put in place and AIMON cannot see from outside. */
    public enum Declaration {
        /** Not declared: the capability is not advertised. */
        UNDECLARED,
        /** Declared by the operator. */
        DECLARED
    }

    /** What a runtime class selects; the provider cannot tell from its name. */
    public enum RuntimeKind {
        /** gVisor: no {@code networkPolicy} with it. */
        GVISOR,
        /** Kata Containers. */
        KATA,
        /** Anything else. */
        OTHER
    }

    /** The server's runtime class ({@code [secure_runtime].k8s_runtime_class} or its sandbox template). */
    public static final class RuntimeClass {
        private final String name;
        private final RuntimeKind kind;

        private RuntimeClass(String name, RuntimeKind kind) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
        }

        /**
         * @param name
         *            the class name profiles must request
         * @param kind
         *            what it selects
         * @return the runtime class
         */
        public static RuntimeClass of(String name, RuntimeKind kind) {
            return new RuntimeClass(name, kind);
        }

        /** @return the class name */
        public String name() {
            return name;
        }

        /** @return what it selects */
        public RuntimeKind kind() {
            return kind;
        }

        @Override
        public String toString() {
            return name + " (" + kind.name().toLowerCase(Locale.ROOT) + ")";
        }
    }

    private final URI endpoint;
    private final Supplier<String> apiKey;
    private final Runtime runtime;
    private final Duration maxExpiry;
    private final EgressEnforcement egressEnforcement;
    private final Declaration networkIsolation;
    private final Declaration hardenedSecurityContext;
    private final RuntimeClass runtimeClass;
    private final Map<String, CredentialDefinition> credentials;
    private final List<HostPort> controlPlaneProbes;
    private final VolumeReclaimer volumeReclaimer;
    private final boolean useServerProxy;
    private final Duration requestTimeout;
    private final Duration createTimeout;
    private final int retryMaxAttempts;
    private final Duration retryBackoff;
    private final int maxConcurrentCalls;
    private final List<String> entrypoint;
    private final String defaultCpu;
    private final String defaultMemory;

    private OpenSandboxProviderConfig(Builder builder) {
        this.endpoint = normalize(builder.endpoint);
        this.apiKey = builder.apiKey;
        this.runtime = builder.runtime;
        this.maxExpiry = builder.maxExpiry;
        this.egressEnforcement = builder.egressEnforcement;
        this.networkIsolation = builder.networkIsolation;
        this.hardenedSecurityContext = builder.hardenedSecurityContext;
        this.runtimeClass = builder.runtimeClass;
        this.credentials = Collections.unmodifiableMap(new LinkedHashMap<>(builder.credentials));
        this.controlPlaneProbes = builder.controlPlaneProbes != null
                ? List.copyOf(builder.controlPlaneProbes)
                : derivedProbes(endpoint, runtime);
        this.volumeReclaimer = builder.volumeReclaimer;
        this.useServerProxy = builder.useServerProxy;
        this.requestTimeout = builder.requestTimeout;
        this.createTimeout = builder.createTimeout;
        this.retryMaxAttempts = builder.retryMaxAttempts;
        this.retryBackoff = builder.retryBackoff;
        this.maxConcurrentCalls = builder.maxConcurrentCalls;
        this.entrypoint = List.copyOf(builder.entrypoint);
        this.defaultCpu = builder.defaultCpu;
        this.defaultMemory = builder.defaultMemory;
    }

    /** @return a new builder with the §13.2 defaults */
    public static Builder builder() {
        return new Builder();
    }

    /** The lifecycle API's base: {@code …/v1}, appended when the configured endpoint lacks it. */
    private static URI normalize(URI endpoint) {
        final String text = endpoint.toString().replaceAll("/+$", "");
        return URI.create(text.endsWith("/v1") ? text : text + "/v1");
    }

    /**
     * The endpoint as AIMON reaches it, and on Kubernetes the API server: what a sandbox must not reach. Wrong when
     * AIMON reaches the server through NAT or a port-forward — then {@code control-plane-probes} is set explicitly.
     */
    private static List<HostPort> derivedProbes(URI endpoint, Runtime runtime) {
        final List<HostPort> probes = new ArrayList<>();
        if (endpoint.getHost() != null) {
            final int port = endpoint.getPort() > 0
                    ? endpoint.getPort()
                    : "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
            probes.add(HostPort.of(endpoint.getHost(), port));
        }
        if (runtime == Runtime.KUBERNETES) {
            probes.add(HostPort.of("kubernetes.default.svc", 443));
        }
        return List.copyOf(probes);
    }

    /**
     * The capabilities these declarations justify (§6.4, docs/design/opensandbox-spike.md §1). {@code PAUSE_RESUME},
     * {@code SNAPSHOT} and {@code FORK} are never advertised before implementation steps 7 and 8.
     *
     * @return the advertised capabilities
     */
    public Set<Capability> capabilities() {
        final EnumSet<Capability> advertised = EnumSet.of(Capability.EXEC, Capability.FILES, Capability.EXPIRY);
        if (egressEnforcement == EgressEnforcement.DNS_NFT) {
            advertised.add(Capability.EGRESS_POLICY);
            if (!credentials.isEmpty()) {
                advertised.add(Capability.CREDENTIAL_INJECTION);
            }
        }
        if (networkIsolation == Declaration.DECLARED) {
            advertised.add(Capability.NETWORK_ISOLATION);
        }
        if (hardenedSecurityContext == Declaration.DECLARED) {
            advertised.add(Capability.HARDENED_SECURITY_CONTEXT);
        }
        if (runtimeClass != null) {
            advertised.add(Capability.RUNTIME_CLASS);
        }
        if (volumeReclaimer != null) {
            advertised.add(Capability.SHARED_VOLUME);
        }
        return Collections.unmodifiableSet(advertised);
    }

    /** @return {@code endpoint}: the lifecycle API base, ending in {@code /v1} */
    public URI endpoint() {
        return endpoint;
    }

    /** @return {@code api-key}; read per request, never logged */
    Supplier<String> apiKey() {
        return apiKey;
    }

    /** @return {@code runtime} */
    public Runtime runtime() {
        return runtime;
    }

    /** @return {@code max-expiry}: the server's {@code max_sandbox_timeout_seconds} */
    public Duration maxExpiry() {
        return maxExpiry;
    }

    /** @return {@code egress-enforcement} */
    public EgressEnforcement egressEnforcement() {
        return egressEnforcement;
    }

    /** @return {@code network-isolation} */
    public Declaration networkIsolation() {
        return networkIsolation;
    }

    /** @return {@code hardened-security-context} */
    public Declaration hardenedSecurityContext() {
        return hardenedSecurityContext;
    }

    /** @return {@code runtime-class} */
    public Optional<RuntimeClass> runtimeClass() {
        return Optional.ofNullable(runtimeClass);
    }

    /** @return {@code credentials}: the vault bindings, by name */
    public Map<String, CredentialDefinition> credentials() {
        return credentials;
    }

    /** @return the credential scopes, by binding name, for the startup overlap check */
    public Map<String, CredentialScope> credentialScopes() {
        final Map<String, CredentialScope> scopes = new LinkedHashMap<>();
        credentials.forEach((name, definition) -> scopes.put(name, definition.scope()));
        return Collections.unmodifiableMap(scopes);
    }

    /** @return {@code control-plane-probes}: what the seed checks a sandbox cannot connect to */
    public List<HostPort> controlPlaneProbes() {
        return controlPlaneProbes;
    }

    /** @return {@code volume-reclaimer} */
    public Optional<VolumeReclaimer> volumeReclaimer() {
        return Optional.ofNullable(volumeReclaimer);
    }

    /** @return {@code use-server-proxy}: reach execd and the egress sidecar through the server */
    public boolean useServerProxy() {
        return useServerProxy;
    }

    /** @return {@code request-timeout} (30s): one non-streaming call */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** @return {@code create-timeout} (90s): {@code POST /sandboxes} plus waiting for {@code Running} */
    public Duration createTimeout() {
        return createTimeout;
    }

    /** @return {@code retry.max-attempts} (3): idempotent calls only */
    public int retryMaxAttempts() {
        return retryMaxAttempts;
    }

    /** @return {@code retry.backoff} (500ms), doubling */
    public Duration retryBackoff() {
        return retryBackoff;
    }

    /** @return {@code max-concurrent-calls} (32): request/response calls in flight; streams do not count */
    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /** @return {@code entrypoint} ({@code tail -f /dev/null}): keeps a sandbox alive whatever the image's CMD */
    public List<String> entrypoint() {
        return entrypoint;
    }

    /** @return {@code default-resources.cpu} (1): sent when the profile gives none (Kubernetes requires one) */
    public String defaultCpu() {
        return defaultCpu;
    }

    /** @return {@code default-resources.memory} (1Gi) */
    public String defaultMemory() {
        return defaultMemory;
    }

    @Override
    public String toString() {
        return "OpenSandboxProviderConfig{endpoint=" + endpoint + ", runtime=" + runtime + ", maxExpiry=" + maxExpiry
                + ", egressEnforcement=" + egressEnforcement + ", networkIsolation=" + networkIsolation
                + ", hardenedSecurityContext=" + hardenedSecurityContext + ", runtimeClass=" + runtimeClass
                + ", credentials=" + credentials.keySet() + ", useServerProxy=" + useServerProxy + '}';
    }

    /** Builder for {@link OpenSandboxProviderConfig}, holding the §13.2 defaults. */
    public static final class Builder {
        private URI endpoint;
        private Supplier<String> apiKey;
        private Runtime runtime;
        private Duration maxExpiry;
        private EgressEnforcement egressEnforcement = EgressEnforcement.NONE;
        private Declaration networkIsolation = Declaration.UNDECLARED;
        private Declaration hardenedSecurityContext = Declaration.UNDECLARED;
        private RuntimeClass runtimeClass;
        private Map<String, CredentialDefinition> credentials = Map.of();
        private List<HostPort> controlPlaneProbes;
        private VolumeReclaimer volumeReclaimer;
        private boolean useServerProxy;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration createTimeout = Duration.ofSeconds(90);
        private int retryMaxAttempts = 3;
        private Duration retryBackoff = Duration.ofMillis(500);
        private int maxConcurrentCalls = 32;
        private List<String> entrypoint = List.of("tail", "-f", "/dev/null");
        private String defaultCpu = "1";
        private String defaultMemory = "1Gi";

        private Builder() {
        }

        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder apiKey(Supplier<String> apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder runtime(Runtime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder maxExpiry(Duration maxExpiry) {
            this.maxExpiry = maxExpiry;
            return this;
        }

        public Builder egressEnforcement(EgressEnforcement egressEnforcement) {
            this.egressEnforcement = Objects.requireNonNull(egressEnforcement, "egressEnforcement must not be null");
            return this;
        }

        public Builder networkIsolation(Declaration networkIsolation) {
            this.networkIsolation = Objects.requireNonNull(networkIsolation, "networkIsolation must not be null");
            return this;
        }

        public Builder hardenedSecurityContext(Declaration hardenedSecurityContext) {
            this.hardenedSecurityContext = Objects.requireNonNull(hardenedSecurityContext,
                    "hardenedSecurityContext must not be null");
            return this;
        }

        public Builder runtimeClass(RuntimeClass runtimeClass) {
            this.runtimeClass = runtimeClass;
            return this;
        }

        public Builder credentials(Map<String, CredentialDefinition> credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
            return this;
        }

        /** Default: the endpoint's host and port, plus {@code kubernetes.default.svc:443} on Kubernetes. */
        public Builder controlPlaneProbes(List<HostPort> controlPlaneProbes) {
            this.controlPlaneProbes = controlPlaneProbes;
            return this;
        }

        public Builder volumeReclaimer(VolumeReclaimer volumeReclaimer) {
            this.volumeReclaimer = volumeReclaimer;
            return this;
        }

        public Builder useServerProxy(boolean useServerProxy) {
            this.useServerProxy = useServerProxy;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder createTimeout(Duration createTimeout) {
            this.createTimeout = createTimeout;
            return this;
        }

        public Builder retry(int maxAttempts, Duration backoff) {
            this.retryMaxAttempts = maxAttempts;
            this.retryBackoff = backoff;
            return this;
        }

        public Builder maxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
            return this;
        }

        public Builder entrypoint(List<String> entrypoint) {
            this.entrypoint = Objects.requireNonNull(entrypoint, "entrypoint must not be null");
            return this;
        }

        public Builder defaultResources(String cpu, String memory) {
            this.defaultCpu = cpu;
            this.defaultMemory = memory;
            return this;
        }

        /**
         * @return the configuration
         * @throws SandboxConfigurationException
         *             listing every violation
         */
        public OpenSandboxProviderConfig build() {
            final List<String> violations = new ArrayList<>();
            final String p = "opensandbox.";
            if (endpoint == null) {
                violations.add(p + "endpoint is required");
            } else if (endpoint.getScheme() == null || endpoint.getHost() == null) {
                violations.add(p + "endpoint '" + endpoint + "' must be an absolute http(s) URI");
            }
            if (apiKey == null) {
                violations.add(p + "api-key is required");
            }
            if (runtime == null) {
                violations.add(p + "runtime is required (docker | kubernetes): it decides which declarations are "
                        + "legal, and has no default");
            }
            if (maxExpiry == null) {
                violations.add(p + "max-expiry is required: the server's max_sandbox_timeout_seconds, which its API "
                        + "does not expose");
            } else if (maxExpiry.compareTo(Duration.ofSeconds(60)) < 0) {
                violations.add(p + "max-expiry must be at least 60s (the server's minimum timeout), got " + maxExpiry);
            }
            if (runtime == Runtime.DOCKER) {
                if (networkIsolation == Declaration.DECLARED) {
                    violations.add(p + "network-isolation cannot be declared on the docker runtime: execd is open to "
                            + "every other sandbox there");
                }
                if (hardenedSecurityContext == Declaration.DECLARED) {
                    violations.add(p + "hardened-security-context cannot be declared on the docker runtime: sandboxes "
                            + "run as root with a writable root filesystem there");
                }
                if (runtimeClass != null) {
                    violations.add(p + "runtime-class cannot be set on the docker runtime");
                }
            }
            if (runtimeClass != null && runtimeClass.kind() == RuntimeKind.GVISOR
                    && egressEnforcement == EgressEnforcement.DNS_NFT) {
                violations.add(p + "egress-enforcement dns+nft cannot be combined with the gVisor runtime class '"
                        + runtimeClass.name() + "': the server refuses every networkPolicy under gVisor");
            }
            if (!credentials.isEmpty() && egressEnforcement != EgressEnforcement.DNS_NFT) {
                violations.add(p + "credentials need egress-enforcement dns+nft: the server refuses the credential "
                        + "proxy otherwise");
            }
            credentials.forEach((name, definition) -> {
                if (name == null || name.isBlank()) {
                    violations.add(p + "credentials: a binding has no name");
                }
            });
            if (networkIsolation == Declaration.DECLARED && controlPlaneProbes != null
                    && controlPlaneProbes.isEmpty()) {
                violations.add(p + "network-isolation is declared but control-plane-probes is empty: the seed would "
                        + "check nothing");
            }
            positive(violations, p + "request-timeout", requestTimeout);
            positive(violations, p + "create-timeout", createTimeout);
            positive(violations, p + "retry.backoff", retryBackoff);
            if (retryMaxAttempts < 1) {
                violations.add(p + "retry.max-attempts must be at least 1");
            }
            if (maxConcurrentCalls < 1) {
                violations.add(p + "max-concurrent-calls must be at least 1");
            }
            if (entrypoint.isEmpty()) {
                violations.add(p + "entrypoint must not be empty");
            }
            if (defaultCpu == null || defaultMemory == null) {
                violations.add(p + "default-resources needs both cpu and memory");
            }
            if (!violations.isEmpty()) {
                throw new SandboxConfigurationException(violations);
            }
            final OpenSandboxProviderConfig config = new OpenSandboxProviderConfig(this);
            if (egressEnforcement == EgressEnforcement.DNS) {
                log.warn("OpenSandbox egress-enforcement is dns: EGRESS_POLICY is not advertised, since dns mode "
                        + "blocks names but not addresses");
            }
            if (networkIsolation == Declaration.DECLARED && controlPlaneProbes == null
                    && runtime == Runtime.KUBERNETES) {
                log.warn("OpenSandbox network-isolation is declared with derived control-plane-probes {}: set "
                        + "control-plane-probes to the server's in-cluster address, or the seed's probe of an address "
                        + "a sandbox cannot resolve passes vacuously", config.controlPlaneProbes());
            }
            return config;
        }

        private static void positive(List<String> violations, String key, Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                violations.add(key + " must be positive, got " + value);
            }
        }
    }
}
