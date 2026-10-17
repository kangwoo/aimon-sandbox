package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.binding.UnattachedRule;

/**
 * One session's sandbox options (docs/design/workspace-sandbox-management.md §5.2): the managed workspace its turns
 * are attached to, its own unattached rule, and the workspace it last connected to — kept apart from the workspace
 * record, which must not grow with its sessions. Changed only by version CAS through {@link SessionOptionsStore}.
 *
 * <p>
 * Immutable; {@link #toBuilder()} returns a copy. {@link #version()} is assigned by the store.
 */
public final class SessionSandboxOptions {

    private final SessionId session;
    private final WorkspaceOwner setBy;
    private final SandboxWorkspaceId workspace;
    private final UnattachedRule unattached;
    private final SandboxWorkspaceId lastWorkspaceId;
    private final Instant updatedAt;
    private final long version;

    private SessionSandboxOptions(Builder builder) {
        this.session = Objects.requireNonNull(builder.session, "session must not be null");
        this.setBy = Objects.requireNonNull(builder.setBy, "setBy must not be null");
        this.workspace = builder.workspace;
        this.unattached = builder.unattached;
        this.lastWorkspaceId = builder.lastWorkspaceId;
        this.updatedAt = Objects.requireNonNull(builder.updatedAt, "updatedAt must not be null");
        this.version = builder.version;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder holding these values */
    public Builder toBuilder() {
        return new Builder().session(session).setBy(setBy).workspace(workspace).unattached(unattached)
                .lastWorkspaceId(lastWorkspaceId).updatedAt(updatedAt).version(version);
    }

    /** @return the session */
    public SessionId session() {
        return session;
    }

    /** @return who last set the options: the session's owner, or the operator acting on it */
    public WorkspaceOwner setBy() {
        return setBy;
    }

    /** @return the managed workspace the session's root requests bind to */
    public Optional<SandboxWorkspaceId> workspace() {
        return Optional.ofNullable(workspace);
    }

    /** @return the session's own unattached rule — it can only tighten the agent's and the deployment's */
    public Optional<UnattachedRule> unattached() {
        return Optional.ofNullable(unattached);
    }

    /** @return the workspace the session last connected to, for the workspace-switch notice (§6.4) */
    public Optional<SandboxWorkspaceId> lastWorkspaceId() {
        return Optional.ofNullable(lastWorkspaceId);
    }

    /** @return when the record was last written */
    public Instant updatedAt() {
        return updatedAt;
    }

    /** @return the CAS version */
    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionSandboxOptions that)) {
            return false;
        }
        return version == that.version && session.equals(that.session) && setBy.equals(that.setBy)
                && Objects.equals(workspace, that.workspace) && unattached == that.unattached
                && Objects.equals(lastWorkspaceId, that.lastWorkspaceId) && updatedAt.equals(that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(session, version);
    }

    @Override
    public String toString() {
        return "SessionSandboxOptions{" + session + ", workspace=" + workspace + ", unattached=" + unattached
                + ", lastWorkspaceId=" + lastWorkspaceId + ", version=" + version + '}';
    }

    /**
     * What {@code setSessionOptions} writes: the attachment and the session's unattached rule. Both fields are
     * replaced — an absent one is cleared — and the record's {@code lastWorkspaceId} is kept.
     */
    public static final class Update {

        private final SandboxWorkspaceId workspace;
        private final UnattachedRule unattached;

        private Update(SandboxWorkspaceId workspace, UnattachedRule unattached) {
            this.workspace = workspace;
            this.unattached = unattached;
        }

        /**
         * @param workspace
         *            the managed workspace to attach to, or {@code null} for none
         * @param unattached
         *            the session's unattached rule, or {@code null} for none
         * @return the update
         */
        public static Update of(SandboxWorkspaceId workspace, UnattachedRule unattached) {
            return new Update(workspace, unattached);
        }

        /**
         * @param workspace
         *            the managed workspace to attach to
         * @return an update attaching the session, with no rule of its own
         */
        public static Update attach(SandboxWorkspaceId workspace) {
            return new Update(Objects.requireNonNull(workspace, "workspace must not be null"), null);
        }

        /**
         * @param unattached
         *            the session's unattached rule
         * @return an update leaving the session unattached, under that rule
         */
        public static Update rule(UnattachedRule unattached) {
            return new Update(null, Objects.requireNonNull(unattached, "unattached must not be null"));
        }

        /** @return the workspace to attach to */
        public Optional<SandboxWorkspaceId> workspace() {
            return Optional.ofNullable(workspace);
        }

        /** @return the session's unattached rule */
        public Optional<UnattachedRule> unattached() {
            return Optional.ofNullable(unattached);
        }
    }

    /** Builder for {@link SessionSandboxOptions}. */
    public static final class Builder {
        private SessionId session;
        private WorkspaceOwner setBy;
        private SandboxWorkspaceId workspace;
        private UnattachedRule unattached;
        private SandboxWorkspaceId lastWorkspaceId;
        private Instant updatedAt;
        private long version;

        private Builder() {
        }

        public Builder session(SessionId session) {
            this.session = session;
            return this;
        }

        public Builder setBy(WorkspaceOwner setBy) {
            this.setBy = setBy;
            return this;
        }

        public Builder workspace(SandboxWorkspaceId workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder unattached(UnattachedRule unattached) {
            this.unattached = unattached;
            return this;
        }

        public Builder lastWorkspaceId(SandboxWorkspaceId lastWorkspaceId) {
            this.lastWorkspaceId = lastWorkspaceId;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public SessionSandboxOptions build() {
            return new SessionSandboxOptions(this);
        }
    }
}
