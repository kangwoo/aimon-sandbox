package at.aimon.sandbox.provider;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a provider reports about one sandbox: its reference, its state, its labels, its expiry and, when the provider
 * knows it, its creation time — reconciliation measures an orphan's grace from it (docs/design/workspace-sandbox.md
 * §10.4).
 */
public final class ProviderSandbox {

    private final ProviderSandboxRef ref;
    private final ProviderSandboxState state;
    private final Map<String, String> labels;
    private final Instant expiresAt;
    private final Instant createdAt;

    private ProviderSandbox(ProviderSandboxRef ref, ProviderSandboxState state, Map<String, String> labels,
            Instant expiresAt, Instant createdAt) {
        this.ref = Objects.requireNonNull(ref, "ref must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.labels = Map.copyOf(Objects.requireNonNull(labels, "labels must not be null"));
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /**
     * @param ref
     *            the sandbox
     * @param state
     *            its state
     * @param labels
     *            its labels
     * @param expiresAt
     *            its provider-side expiry, or {@code null} when it has none
     * @return the description
     */
    public static ProviderSandbox of(ProviderSandboxRef ref, ProviderSandboxState state, Map<String, String> labels,
            Instant expiresAt) {
        return new ProviderSandbox(ref, state, labels, expiresAt, null);
    }

    /**
     * @param ref
     *            the sandbox
     * @param state
     *            its state
     * @param labels
     *            its labels
     * @param expiresAt
     *            its provider-side expiry, or {@code null} when it has none
     * @param createdAt
     *            when the provider created it, or {@code null} when unknown
     * @return the description
     */
    public static ProviderSandbox of(ProviderSandboxRef ref, ProviderSandboxState state, Map<String, String> labels,
            Instant expiresAt, Instant createdAt) {
        return new ProviderSandbox(ref, state, labels, expiresAt, createdAt);
    }

    /** @return the sandbox */
    public ProviderSandboxRef ref() {
        return ref;
    }

    /** @return its state */
    public ProviderSandboxState state() {
        return state;
    }

    /** @return its labels */
    public Map<String, String> labels() {
        return labels;
    }

    /** @return its provider-side expiry */
    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /** @return when the provider created it, when the provider reports that (by the provider's clock) */
    public Optional<Instant> createdAt() {
        return Optional.ofNullable(createdAt);
    }

    @Override
    public String toString() {
        return "ProviderSandbox{ref=" + ref + ", state=" + state + ", labels=" + labels + ", expiresAt=" + expiresAt
                + ", createdAt=" + createdAt + '}';
    }
}
