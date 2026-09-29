package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.sandbox.provider.VolumeRef;

/**
 * The workspace aggregate: owner, lifecycle state and every slot, persisted as one record and changed only by
 * version CAS (docs/design/workspace-sandbox.md §5.1, §5.3). Shell state is not here — it lives in files inside the
 * sandbox (§9).
 *
 * <p>
 * Immutable; {@code with*} and {@link #toBuilder()} return copies. {@link #version()} is assigned by the store.
 */
public final class SandboxWorkspace {

    private final SandboxWorkspaceId id;
    private final WorkspaceOwner owner;
    private final WorkspaceState state;
    private final String incarnation;
    private final VolumeRef sharedVolume;
    private final Instant stateSince;
    private final CloseCause closeCause;
    private final Instant retainVolumeUntil;
    private final List<RetainedVolume> retainedVolumes;
    private final Map<String, SandboxSlot> slots;
    private final WorkspaceQuota quota;
    private final long version;
    private final Instant createdAt;
    private final Instant lastActivityAt;

    private SandboxWorkspace(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id must not be null");
        this.owner = Objects.requireNonNull(builder.owner, "owner must not be null");
        this.state = Objects.requireNonNull(builder.state, "state must not be null");
        this.incarnation = Objects.requireNonNull(builder.incarnation, "incarnation must not be null");
        this.sharedVolume = builder.sharedVolume;
        this.stateSince = builder.stateSince;
        this.closeCause = builder.closeCause;
        this.retainVolumeUntil = builder.retainVolumeUntil;
        this.retainedVolumes = List.copyOf(builder.retainedVolumes);
        this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(builder.slots));
        this.quota = Objects.requireNonNull(builder.quota, "quota must not be null");
        this.version = builder.version;
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt must not be null");
        this.lastActivityAt = Objects.requireNonNull(builder.lastActivityAt, "lastActivityAt must not be null");
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder holding this workspace's values */
    public Builder toBuilder() {
        return new Builder().id(id).owner(owner).state(state).incarnation(incarnation).sharedVolume(sharedVolume)
                .stateSince(stateSince).closeCause(closeCause).retainVolumeUntil(retainVolumeUntil)
                .retainedVolumes(retainedVolumes).slots(slots).quota(quota).version(version).createdAt(createdAt)
                .lastActivityAt(lastActivityAt);
    }

    /** @return the id */
    public SandboxWorkspaceId id() {
        return id;
    }

    /** @return the owner, fixed at creation */
    public WorkspaceOwner owner() {
        return owner;
    }

    /** @return the lifecycle state */
    public WorkspaceState state() {
        return state;
    }

    /** @return the random id drawn at creation and at every reopen; part of every sandbox key */
    public String incarnation() {
        return incarnation;
    }

    /** @return the shared volume (never present in implementation step 3) */
    public Optional<VolumeRef> sharedVolume() {
        return Optional.ofNullable(sharedVolume);
    }

    /**
     * @return when the state last changed: into OPEN (create, reopen, idle reopen), CLOSING or CLOSED — the
     *         reference of {@code closeAfter}, {@code closeResumeAfter} and {@code closedRetention}
     */
    public Optional<Instant> stateSince() {
        return Optional.ofNullable(stateSince);
    }

    /** @return why the workspace is CLOSING or CLOSED */
    public Optional<CloseCause> closeCause() {
        return Optional.ofNullable(closeCause);
    }

    /** @return until when the shared volume is kept after close */
    public Optional<Instant> retainVolumeUntil() {
        return Optional.ofNullable(retainVolumeUntil);
    }

    /** @return volumes of earlier incarnations kept until a deadline */
    public List<RetainedVolume> retainedVolumes() {
        return retainedVolumes;
    }

    /** @return the slots by name */
    public Map<String, SandboxSlot> slots() {
        return slots;
    }

    /**
     * @param name
     *            the slot name
     * @return the slot
     */
    public Optional<SandboxSlot> slot(String name) {
        return Optional.ofNullable(slots.get(name));
    }

    /** @return the limits copied from the settings at creation */
    public WorkspaceQuota quota() {
        return quota;
    }

    /** @return the CAS version */
    public long version() {
        return version;
    }

    /** @return when the record was created */
    public Instant createdAt() {
        return createdAt;
    }

    /** @return the last recorded activity on any slot */
    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    /**
     * @param slot
     *            the new or changed slot
     * @return a copy holding it
     */
    public SandboxWorkspace withSlot(SandboxSlot slot) {
        final Map<String, SandboxSlot> next = new LinkedHashMap<>(slots);
        next.put(slot.name(), slot);
        return toBuilder().slots(next).build();
    }

    /**
     * @param at
     *            the activity time; an earlier value than the recorded one is ignored
     * @return a copy with the later of the two
     */
    public SandboxWorkspace withLastActivityAt(Instant at) {
        return at.isAfter(lastActivityAt) ? toBuilder().lastActivityAt(at).build() : this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SandboxWorkspace that)) {
            return false;
        }
        return version == that.version && id.equals(that.id) && owner.equals(that.owner) && state == that.state
                && incarnation.equals(that.incarnation) && Objects.equals(sharedVolume, that.sharedVolume)
                && Objects.equals(stateSince, that.stateSince) && closeCause == that.closeCause
                && Objects.equals(retainVolumeUntil, that.retainVolumeUntil)
                && retainedVolumes.equals(that.retainedVolumes) && slots.equals(that.slots) && quota.equals(that.quota)
                && createdAt.equals(that.createdAt) && lastActivityAt.equals(that.lastActivityAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return "SandboxWorkspace{" + id + ", owner=" + owner + ", state=" + state + ", incarnation=" + incarnation
                + ", version=" + version + ", slots=" + slots.values() + '}';
    }

    /** Builder for {@link SandboxWorkspace}. */
    public static final class Builder {
        private SandboxWorkspaceId id;
        private WorkspaceOwner owner;
        private WorkspaceState state = WorkspaceState.OPEN;
        private String incarnation;
        private VolumeRef sharedVolume;
        private Instant stateSince;
        private CloseCause closeCause;
        private Instant retainVolumeUntil;
        private List<RetainedVolume> retainedVolumes = List.of();
        private Map<String, SandboxSlot> slots = Map.of();
        private WorkspaceQuota quota = WorkspaceQuota.defaults();
        private long version;
        private Instant createdAt;
        private Instant lastActivityAt;

        private Builder() {
        }

        public Builder id(SandboxWorkspaceId id) {
            this.id = id;
            return this;
        }

        public Builder owner(WorkspaceOwner owner) {
            this.owner = owner;
            return this;
        }

        public Builder state(WorkspaceState state) {
            this.state = state;
            return this;
        }

        public Builder incarnation(String incarnation) {
            this.incarnation = incarnation;
            return this;
        }

        public Builder sharedVolume(VolumeRef sharedVolume) {
            this.sharedVolume = sharedVolume;
            return this;
        }

        public Builder stateSince(Instant stateSince) {
            this.stateSince = stateSince;
            return this;
        }

        public Builder closeCause(CloseCause closeCause) {
            this.closeCause = closeCause;
            return this;
        }

        public Builder retainVolumeUntil(Instant retainVolumeUntil) {
            this.retainVolumeUntil = retainVolumeUntil;
            return this;
        }

        public Builder retainedVolumes(List<RetainedVolume> retainedVolumes) {
            this.retainedVolumes = Objects.requireNonNull(retainedVolumes, "retainedVolumes must not be null");
            return this;
        }

        public Builder slots(Map<String, SandboxSlot> slots) {
            this.slots = Objects.requireNonNull(slots, "slots must not be null");
            return this;
        }

        public Builder quota(WorkspaceQuota quota) {
            this.quota = quota;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder lastActivityAt(Instant lastActivityAt) {
            this.lastActivityAt = lastActivityAt;
            return this;
        }

        public SandboxWorkspace build() {
            return new SandboxWorkspace(this);
        }
    }
}
