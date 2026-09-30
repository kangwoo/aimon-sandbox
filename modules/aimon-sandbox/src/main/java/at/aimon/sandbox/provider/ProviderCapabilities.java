package at.aimon.sandbox.provider;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The capabilities a provider advertises, and the provider configuration the manager has to check its own against.
 *
 * <p>
 * Everything here is declared configuration the manager reads once at startup; the SPI is the only place it can see
 * it (docs/design/workspace-sandbox.md §13.2):
 * <ul>
 * <li>{@link #maxExpiry()} — every profile's {@code terminateAfter} is compared against it (OpenSandbox's
 * {@code max_sandbox_timeout_seconds});</li>
 * <li>{@link #runtimeClass()} — the runtime class the server uses; a profile's {@code runtimeClass} must equal it;</li>
 * <li>{@link #credentialScopes()} — the credential bindings the provider can inject, by name, without secrets; the
 * startup overlap check reads them (§12.1);</li>
 * <li>{@link #controlPlaneEndpoints()} — what a sandbox must not reach; the seed probes them (§11.3).</li>
 * </ul>
 */
public final class ProviderCapabilities {

    private final Set<Capability> advertised;
    private final Duration maxExpiry;
    private final String runtimeClass;
    private final Map<String, CredentialScope> credentialScopes;
    private final List<HostPort> controlPlaneEndpoints;

    private ProviderCapabilities(Builder builder) {
        Objects.requireNonNull(builder.advertised, "advertised must not be null");
        this.advertised = builder.advertised.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(builder.advertised));
        this.maxExpiry = builder.maxExpiry;
        this.runtimeClass = builder.runtimeClass;
        this.credentialScopes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.credentialScopes));
        this.controlPlaneEndpoints = List.copyOf(builder.controlPlaneEndpoints);
    }

    /**
     * @param advertised
     *            the capabilities the provider verified
     * @param maxExpiry
     *            the longest expiry the provider accepts, or {@code null} when it has none
     * @return the capabilities
     */
    public static ProviderCapabilities of(Set<Capability> advertised, Duration maxExpiry) {
        return builder().advertised(advertised).maxExpiry(maxExpiry).build();
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the advertised capabilities */
    public Set<Capability> advertised() {
        return advertised;
    }

    /**
     * @param capability
     *            the capability
     * @return whether it is advertised
     */
    public boolean supports(Capability capability) {
        return advertised.contains(capability);
    }

    /** @return the longest expiry the provider accepts, when it has a limit */
    public Optional<Duration> maxExpiry() {
        return Optional.ofNullable(maxExpiry);
    }

    /** @return the runtime class every sandbox of this provider runs under, when one is declared */
    public Optional<String> runtimeClass() {
        return Optional.ofNullable(runtimeClass);
    }

    /** @return the credential bindings the provider can inject, by name; never the secrets */
    public Map<String, CredentialScope> credentialScopes() {
        return credentialScopes;
    }

    /** @return the control-plane endpoints a sandbox must not be able to connect to */
    public List<HostPort> controlPlaneEndpoints() {
        return controlPlaneEndpoints;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderCapabilities that)) {
            return false;
        }
        return advertised.equals(that.advertised) && Objects.equals(maxExpiry, that.maxExpiry)
                && Objects.equals(runtimeClass, that.runtimeClass) && credentialScopes.equals(that.credentialScopes)
                && controlPlaneEndpoints.equals(that.controlPlaneEndpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(advertised, maxExpiry, runtimeClass, credentialScopes, controlPlaneEndpoints);
    }

    @Override
    public String toString() {
        return "ProviderCapabilities{advertised=" + advertised + ", maxExpiry=" + maxExpiry + ", runtimeClass="
                + runtimeClass + ", credentialScopes=" + credentialScopes.keySet() + ", controlPlaneEndpoints="
                + controlPlaneEndpoints + '}';
    }

    /** Builder for {@link ProviderCapabilities}. */
    public static final class Builder {
        private Set<Capability> advertised = Set.of();
        private Duration maxExpiry;
        private String runtimeClass;
        private Map<String, CredentialScope> credentialScopes = Map.of();
        private List<HostPort> controlPlaneEndpoints = List.of();

        private Builder() {
        }

        public Builder advertised(Set<Capability> advertised) {
            this.advertised = advertised;
            return this;
        }

        public Builder maxExpiry(Duration maxExpiry) {
            this.maxExpiry = maxExpiry;
            return this;
        }

        public Builder runtimeClass(String runtimeClass) {
            this.runtimeClass = runtimeClass;
            return this;
        }

        public Builder credentialScopes(Map<String, CredentialScope> credentialScopes) {
            this.credentialScopes = Objects.requireNonNull(credentialScopes, "credentialScopes must not be null");
            return this;
        }

        public Builder controlPlaneEndpoints(List<HostPort> controlPlaneEndpoints) {
            this.controlPlaneEndpoints = Objects.requireNonNull(controlPlaneEndpoints,
                    "controlPlaneEndpoints must not be null");
            return this;
        }

        public ProviderCapabilities build() {
            return new ProviderCapabilities(this);
        }
    }
}
