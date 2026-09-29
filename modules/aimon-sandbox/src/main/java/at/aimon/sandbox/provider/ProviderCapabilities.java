package at.aimon.sandbox.provider;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The capabilities a provider advertises, and the longest expiry it accepts.
 *
 * <p>
 * {@link #maxExpiry()} is what startup validation compares every profile's {@code terminateAfter} against
 * (docs/design/workspace-sandbox.md §13.2): the value is provider configuration (OpenSandbox's
 * {@code max_sandbox_timeout_seconds}), so the SPI is the only place the manager can see it.
 */
public final class ProviderCapabilities {

    private final Set<Capability> advertised;
    private final Duration maxExpiry;

    private ProviderCapabilities(Set<Capability> advertised, Duration maxExpiry) {
        Objects.requireNonNull(advertised, "advertised must not be null");
        this.advertised = advertised.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(advertised));
        this.maxExpiry = maxExpiry;
    }

    /**
     * @param advertised
     *            the capabilities the provider verified
     * @param maxExpiry
     *            the longest expiry the provider accepts, or {@code null} when it has none
     * @return the capabilities
     */
    public static ProviderCapabilities of(Set<Capability> advertised, Duration maxExpiry) {
        return new ProviderCapabilities(advertised, maxExpiry);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderCapabilities that)) {
            return false;
        }
        return advertised.equals(that.advertised) && Objects.equals(maxExpiry, that.maxExpiry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(advertised, maxExpiry);
    }

    @Override
    public String toString() {
        return "ProviderCapabilities{advertised=" + advertised + ", maxExpiry=" + maxExpiry + '}';
    }
}
