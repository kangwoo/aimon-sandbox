package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A sandbox lifecycle event for the application's audit log (docs/design/workspace-sandbox.md §14): the workspace, the
 * slot and generation, the owner, the profile and the cause.
 */
public final class SandboxEvent {

    /** What happened. */
    public enum Type {
        /** A slot's sandbox was created and verified. */
        PROVISIONED,
        /** A slot was paused (implementation step 7). */
        PAUSED,
        /** A slot's sandbox was terminated (idle, close). */
        TERMINATED,
        /** A slot's sandbox was found gone. */
        LOST,
        /** Reconciliation destroyed an orphan (implementation step 4). */
        ORPHAN_DESTROYED,
        /** Reconciliation destroyed a duplicate (implementation step 4). */
        DUPLICATE_DESTROYED,
        /** A shared volume was deleted (implementation step 5). */
        VOLUME_DELETED,
        /** A workspace reached CLOSED. */
        WORKSPACE_CLOSED,
        /** A workspace went back to OPEN (explicitly, or automatically after an idle close). */
        WORKSPACE_REOPENED
    }

    private final Type type;
    private final SandboxWorkspaceId workspaceId;
    private final WorkspaceOwner owner;
    private final String slot;
    private final Long generation;
    private final String profile;
    private final String cause;
    private final Instant at;

    private SandboxEvent(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type must not be null");
        this.workspaceId = Objects.requireNonNull(builder.workspaceId, "workspaceId must not be null");
        this.owner = Objects.requireNonNull(builder.owner, "owner must not be null");
        this.slot = builder.slot;
        this.generation = builder.generation;
        this.profile = builder.profile;
        this.cause = Objects.requireNonNull(builder.cause, "cause must not be null");
        this.at = Objects.requireNonNull(builder.at, "at must not be null");
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return what happened */
    public Type type() {
        return type;
    }

    /** @return the workspace */
    public SandboxWorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the workspace's owner */
    public WorkspaceOwner owner() {
        return owner;
    }

    /** @return the slot, for slot events */
    public Optional<String> slot() {
        return Optional.ofNullable(slot);
    }

    /** @return the generation, for slot events */
    public Optional<Long> generation() {
        return Optional.ofNullable(generation);
    }

    /** @return the profile, for slot events */
    public Optional<String> profile() {
        return Optional.ofNullable(profile);
    }

    /** @return why */
    public String cause() {
        return cause;
    }

    /** @return when */
    public Instant at() {
        return at;
    }

    @Override
    public String toString() {
        return type + " " + workspaceId + (slot != null ? "/" + slot + "#" + generation : "") + " (" + cause + ")";
    }

    /** Builder for {@link SandboxEvent}. */
    public static final class Builder {
        private Type type;
        private SandboxWorkspaceId workspaceId;
        private WorkspaceOwner owner;
        private String slot;
        private Long generation;
        private String profile;
        private String cause;
        private Instant at;

        private Builder() {
        }

        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        public Builder workspaceId(SandboxWorkspaceId workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder owner(WorkspaceOwner owner) {
            this.owner = owner;
            return this;
        }

        public Builder slot(SandboxSlot slot) {
            this.slot = slot.name();
            this.generation = slot.generation();
            this.profile = slot.profile();
            return this;
        }

        public Builder cause(String cause) {
            this.cause = cause;
            return this;
        }

        public Builder at(Instant at) {
            this.at = at;
            return this;
        }

        public SandboxEvent build() {
            return new SandboxEvent(this);
        }
    }
}
