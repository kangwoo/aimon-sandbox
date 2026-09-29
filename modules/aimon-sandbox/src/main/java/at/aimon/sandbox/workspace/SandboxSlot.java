package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.sandbox.provider.ProviderSandboxRef;

/**
 * One slot of a workspace: a name, the profile fixed when it was first created, and the current generation of its
 * sandbox (docs/design/workspace-sandbox.md §5.2). Immutable; {@code with*} and {@link #toBuilder()} return copies, and
 * the store's CAS is what makes a change stick.
 *
 * <p>
 * {@link #profileHash()} is the hash of the profile the current generation was created with, so a permanent failure
 * can be retried "when the profile changes" and not before.
 */
public final class SandboxSlot {

    private final String name;
    private final String profile;
    private final String profileHash;
    private final SlotState state;
    private final long generation;
    private final ProvisioningClaim provisioning;
    private final ProviderSandboxRef providerRef;
    private final boolean seeded;
    private final Instant missingSince;
    private final SlotFailure failure;
    private final Instant lastActivityAt;
    private final Instant lastActiveAt;
    private final Instant lostAt;

    private SandboxSlot(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.profile = Objects.requireNonNull(builder.profile, "profile must not be null");
        this.profileHash = Objects.requireNonNull(builder.profileHash, "profileHash must not be null");
        this.state = Objects.requireNonNull(builder.state, "state must not be null");
        this.generation = builder.generation;
        this.provisioning = builder.provisioning;
        this.providerRef = builder.providerRef;
        this.seeded = builder.seeded;
        this.missingSince = builder.missingSince;
        this.failure = builder.failure;
        this.lastActivityAt = Objects.requireNonNull(builder.lastActivityAt, "lastActivityAt must not be null");
        this.lastActiveAt = builder.lastActiveAt;
        this.lostAt = builder.lostAt;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder holding this slot's values */
    public Builder toBuilder() {
        return new Builder().name(name).profile(profile).profileHash(profileHash).state(state).generation(generation)
                .provisioning(provisioning).providerRef(providerRef).seeded(seeded).missingSince(missingSince)
                .failure(failure).lastActivityAt(lastActivityAt).lastActiveAt(lastActiveAt).lostAt(lostAt);
    }

    /** @return the slot name */
    public String name() {
        return name;
    }

    /** @return the profile name, fixed when the slot was first created */
    public String profile() {
        return profile;
    }

    /** @return the hash of the profile the current generation was created with */
    public String profileHash() {
        return profileHash;
    }

    /** @return the state */
    public SlotState state() {
        return state;
    }

    /** @return the generation, +1 on every new provisioning */
    public long generation() {
        return generation;
    }

    /** @return who is provisioning, while {@link SlotState#PROVISIONING} */
    public Optional<ProvisioningClaim> provisioning() {
        return Optional.ofNullable(provisioning);
    }

    /** @return the provider's sandbox of this generation */
    public Optional<ProviderSandboxRef> providerRef() {
        return Optional.ofNullable(providerRef);
    }

    /** @return whether this generation's seed finished */
    public boolean seeded() {
        return seeded;
    }

    /** @return when reconciliation first missed the sandbox (implementation step 4) */
    public Optional<Instant> missingSince() {
        return Optional.ofNullable(missingSince);
    }

    /** @return why the slot is FAILED */
    public Optional<SlotFailure> failure() {
        return Optional.ofNullable(failure);
    }

    /** @return the last recorded activity */
    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    /** @return when the slot last was RUNNING, PAUSED or PROVISIONING — {@code closeAfter}'s reference */
    public Optional<Instant> lastActiveAt() {
        return Optional.ofNullable(lastActiveAt);
    }

    /** @return when the sandbox was found lost */
    public Optional<Instant> lostAt() {
        return Optional.ofNullable(lostAt);
    }

    /** @return whether the slot holds provider resources (PROVISIONING, RUNNING, PAUSED) */
    public boolean live() {
        return state == SlotState.PROVISIONING || state == SlotState.RUNNING || state == SlotState.PAUSED;
    }

    /**
     * @param newState
     *            the state
     * @return a copy in that state
     */
    public SandboxSlot withState(SlotState newState) {
        return toBuilder().state(newState).build();
    }

    /**
     * @param at
     *            the activity time; an earlier value than the recorded one is ignored
     * @return a copy with the later of the two
     */
    public SandboxSlot withLastActivityAt(Instant at) {
        return at.isAfter(lastActivityAt) ? toBuilder().lastActivityAt(at).build() : this;
    }

    /**
     * Leaves PROVISIONING/RUNNING/PAUSED for TERMINATED, stamping {@code lastActiveAt} (§5.2).
     *
     * @param now
     *            the transition time
     * @return the terminated copy
     */
    public SandboxSlot terminated(Instant now) {
        return toBuilder().state(SlotState.TERMINATED).provisioning(null).lastActiveAt(live() ? now : lastActiveAt)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SandboxSlot that)) {
            return false;
        }
        return generation == that.generation && seeded == that.seeded && name.equals(that.name)
                && profile.equals(that.profile) && profileHash.equals(that.profileHash) && state == that.state
                && Objects.equals(provisioning, that.provisioning) && Objects.equals(providerRef, that.providerRef)
                && Objects.equals(missingSince, that.missingSince) && Objects.equals(failure, that.failure)
                && lastActivityAt.equals(that.lastActivityAt) && Objects.equals(lastActiveAt, that.lastActiveAt)
                && Objects.equals(lostAt, that.lostAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, profile, state, generation, providerRef);
    }

    @Override
    public String toString() {
        return "SandboxSlot{" + name + ", profile=" + profile + ", state=" + state + ", generation=" + generation
                + ", ref=" + providerRef + ", seeded=" + seeded + (failure != null ? ", failure=" + failure : "") + '}';
    }

    /** Builder for {@link SandboxSlot}. */
    public static final class Builder {
        private String name;
        private String profile;
        private String profileHash;
        private SlotState state;
        private long generation;
        private ProvisioningClaim provisioning;
        private ProviderSandboxRef providerRef;
        private boolean seeded;
        private Instant missingSince;
        private SlotFailure failure;
        private Instant lastActivityAt;
        private Instant lastActiveAt;
        private Instant lostAt;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        public Builder profileHash(String profileHash) {
            this.profileHash = profileHash;
            return this;
        }

        public Builder state(SlotState state) {
            this.state = state;
            return this;
        }

        public Builder generation(long generation) {
            this.generation = generation;
            return this;
        }

        public Builder provisioning(ProvisioningClaim provisioning) {
            this.provisioning = provisioning;
            return this;
        }

        public Builder providerRef(ProviderSandboxRef providerRef) {
            this.providerRef = providerRef;
            return this;
        }

        public Builder seeded(boolean seeded) {
            this.seeded = seeded;
            return this;
        }

        public Builder missingSince(Instant missingSince) {
            this.missingSince = missingSince;
            return this;
        }

        public Builder failure(SlotFailure failure) {
            this.failure = failure;
            return this;
        }

        public Builder lastActivityAt(Instant lastActivityAt) {
            this.lastActivityAt = lastActivityAt;
            return this;
        }

        public Builder lastActiveAt(Instant lastActiveAt) {
            this.lastActiveAt = lastActiveAt;
            return this;
        }

        public Builder lostAt(Instant lostAt) {
            this.lostAt = lostAt;
            return this;
        }

        public SandboxSlot build() {
            return new SandboxSlot(this);
        }
    }
}
