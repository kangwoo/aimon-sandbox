package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.sandbox.provider.ProviderSandboxState;

/**
 * One sandbox the provider runs for this deployment, as the janitor sees it
 * (docs/design/workspace-sandbox-management.md
 * §5.4, §6.8). It names the sandbox by {@link #refHash()} — {@code h(sandboxId)}, never the id, which with network
 * reach is root in the sandbox (WS §13.3) — and, when its labels resolve to a record, by workspace, slot and
 * generation.
 */
public final class SandboxView {

    private final String refHash;
    private final ProviderSandboxState providerState;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final SandboxWorkspaceId workspaceId;
    private final String slot;
    private final Long generation;
    private final SandboxReconciler.Verdict verdict;
    private final Boolean pastGrace;

    private SandboxView(Builder builder) {
        this.refHash = Objects.requireNonNull(builder.refHash, "refHash must not be null");
        this.providerState = Objects.requireNonNull(builder.providerState, "providerState must not be null");
        this.expiresAt = builder.expiresAt;
        this.createdAt = builder.createdAt;
        this.workspaceId = builder.workspaceId;
        this.slot = builder.slot;
        this.generation = builder.generation;
        this.verdict = Objects.requireNonNull(builder.verdict, "verdict must not be null");
        this.pastGrace = builder.pastGrace;
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
     * release.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return {@code h(sandboxId)}, as {@code ProviderSandboxRef} prints it after the provider name */
    public String refHash() {
        return refHash;
    }

    /** @return what the provider says the sandbox is doing */
    public ProviderSandboxState providerState() {
        return providerState;
    }

    /** @return when the provider will end it */
    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /** @return when the provider created it, when it says */
    public Optional<Instant> createdAt() {
        return Optional.ofNullable(createdAt);
    }

    /** @return the workspace its labels resolve to, when a record exists for them */
    public Optional<SandboxWorkspaceId> workspaceId() {
        return Optional.ofNullable(workspaceId);
    }

    /** @return its slot, when its labels resolve to a record */
    public Optional<String> slot() {
        return Optional.ofNullable(slot);
    }

    /** @return its generation, when its labels resolve to a record */
    public Optional<Long> generation() {
        return Optional.ofNullable(generation);
    }

    /** @return the reconciler's verdict, before grace ({@code SandboxReconciler.classify}) */
    public SandboxReconciler.Verdict verdict() {
        return verdict;
    }

    /**
     * @return for an {@code ORPHAN} or {@code STALE}, whether it is older than {@code orphan-grace} — present only when
     *         the provider reports {@code createdAt}. Informational: only the janitor destroys
     */
    public Optional<Boolean> pastGrace() {
        return Optional.ofNullable(pastGrace);
    }

    @Override
    public String toString() {
        return "SandboxView{#" + refHash + ", " + providerState + ", " + verdict
                + (workspaceId != null ? ", " + workspaceId + "/" + slot + "#" + generation : "") + '}';
    }

    /** Builder for {@link SandboxView}. */
    public static final class Builder {
        private String refHash;
        private ProviderSandboxState providerState;
        private Instant expiresAt;
        private Instant createdAt;
        private SandboxWorkspaceId workspaceId;
        private String slot;
        private Long generation;
        private SandboxReconciler.Verdict verdict;
        private Boolean pastGrace;

        private Builder() {
        }

        public Builder refHash(String refHash) {
            this.refHash = refHash;
            return this;
        }

        public Builder providerState(ProviderSandboxState providerState) {
            this.providerState = providerState;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder workspaceId(SandboxWorkspaceId workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder slot(String slot) {
            this.slot = slot;
            return this;
        }

        public Builder generation(Long generation) {
            this.generation = generation;
            return this;
        }

        public Builder verdict(SandboxReconciler.Verdict verdict) {
            this.verdict = verdict;
            return this;
        }

        public Builder pastGrace(Boolean pastGrace) {
            this.pastGrace = pastGrace;
            return this;
        }

        public SandboxView build() {
            return new SandboxView(this);
        }
    }
}
