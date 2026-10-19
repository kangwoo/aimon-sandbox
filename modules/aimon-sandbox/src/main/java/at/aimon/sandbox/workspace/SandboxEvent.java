package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;

/**
 * A sandbox lifecycle event for the application's audit log (docs/design/workspace-sandbox.md §14): the workspace, the
 * slot and generation, the owner, the profile and the cause — and, for what a principal asked for, the
 * {@link #actor()} (docs/design/workspace-sandbox-management.md §4.1).
 *
 * <p>
 * The two session types ({@link Type#SESSION_OPTIONS_SET}, {@link Type#SESSION_OPTIONS_CLEARED}) carry the
 * {@link #session()} and the session's owner, and may lack a workspace: read it with {@link #workspace()}. Every other
 * type has one, so {@link #workspaceId()} — kept for listeners written against 0.1.0 — answers for all of them.
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
        WORKSPACE_REOPENED,
        /** A managed workspace's record was created. */
        WORKSPACE_CREATED,
        /** A managed workspace's record was deleted for good. */
        WORKSPACE_PURGED,
        /** A session's sandbox options were set. */
        SESSION_OPTIONS_SET,
        /** A session's sandbox options were cleared. */
        SESSION_OPTIONS_CLEARED,
        /**
         * Files in a workspace were changed through {@code SandboxWorkspaceFiles} — by a person, not an agent's tools;
         * the cause names the operation and path (docs/design/workspace-sandbox-files.md §6.4).
         */
        FILES_CHANGED;

        /** @return whether this is one of the session types, which may lack a workspace */
        public boolean isSessionType() {
            return this == SESSION_OPTIONS_SET || this == SESSION_OPTIONS_CLEARED;
        }
    }

    private final Type type;
    private final SandboxWorkspaceId workspaceId;
    private final WorkspaceOwner owner;
    private final String slot;
    private final Long generation;
    private final String profile;
    private final String cause;
    private final Instant at;
    private final SessionId session;
    private final String actor;

    private SandboxEvent(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type must not be null");
        if (type.isSessionType()) {
            Objects.requireNonNull(builder.session, "session must not be null");
        } else {
            Objects.requireNonNull(builder.workspaceId, "workspaceId must not be null");
        }
        this.workspaceId = builder.workspaceId;
        this.owner = Objects.requireNonNull(builder.owner, "owner must not be null");
        this.session = builder.session;
        this.actor = builder.actor;
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

    /**
     * @return the workspace
     * @throws IllegalStateException
     *             for a session event that names no workspace (a rule-only set, or a clear of one); use
     *             {@link #workspace()} for those types
     */
    public SandboxWorkspaceId workspaceId() {
        if (workspaceId == null) {
            throw new IllegalStateException(type + " for session " + session.value() + " names no workspace");
        }
        return workspaceId;
    }

    /** @return the workspace; empty only for a session event that names none */
    public Optional<SandboxWorkspaceId> workspace() {
        return Optional.ofNullable(workspaceId);
    }

    /** @return the workspace's owner — for the session types, the session's owner */
    public WorkspaceOwner owner() {
        return owner;
    }

    /** @return the session, for the session types */
    public Optional<SessionId> session() {
        return Optional.ofNullable(session);
    }

    /**
     * @return who asked for it — {@code tenant/TYPE:id}, or {@code TYPE:id} when no tenant is known for the actor —
     *         for what a principal asked for; empty for what the system did on its own (idle policy, reconciliation)
     */
    public Optional<String> actor() {
        return Optional.ofNullable(actor);
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
        return type + " " + (workspaceId != null ? workspaceId : "session " + session.value())
                + (slot != null ? "/" + slot + "#" + generation : "") + " (" + cause + ")"
                + (actor != null ? " by " + actor : "");
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
        private SessionId session;
        private String actor;

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

        public Builder session(SessionId session) {
            this.session = session;
            return this;
        }

        public Builder actor(String actor) {
            this.actor = actor;
            return this;
        }

        public SandboxEvent build() {
            return new SandboxEvent(this);
        }
    }
}
