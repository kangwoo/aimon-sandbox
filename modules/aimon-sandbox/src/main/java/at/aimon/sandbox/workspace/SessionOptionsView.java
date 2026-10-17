package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.binding.UnattachedRule;

/**
 * What an owner or operator sees of a session's sandbox options (docs/design/workspace-sandbox-management.md §5.4):
 * the record without its version and its switch-notice bookkeeping.
 */
public final class SessionOptionsView {

    private final SessionId session;
    private final WorkspaceOwner setBy;
    private final SandboxWorkspaceId workspace;
    private final UnattachedRule unattached;
    private final Instant updatedAt;

    private SessionOptionsView(SessionSandboxOptions options) {
        this.session = options.session();
        this.setBy = options.setBy();
        this.workspace = options.workspace().orElse(null);
        this.unattached = options.unattached().orElse(null);
        this.updatedAt = options.updatedAt();
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
     * release.
     *
     * @param options
     *            the record
     * @return its view
     */
    public static SessionOptionsView of(SessionSandboxOptions options) {
        return new SessionOptionsView(Objects.requireNonNull(options, "options must not be null"));
    }

    /** @return the session */
    public SessionId session() {
        return session;
    }

    /** @return who last set the options */
    public WorkspaceOwner setBy() {
        return setBy;
    }

    /** @return the attached workspace */
    public Optional<SandboxWorkspaceId> workspace() {
        return Optional.ofNullable(workspace);
    }

    /** @return the session's own unattached rule */
    public Optional<UnattachedRule> unattached() {
        return Optional.ofNullable(unattached);
    }

    /** @return when the options were last written */
    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "SessionOptionsView{" + session.value() + ", workspace=" + workspace + ", unattached=" + unattached
                + '}';
    }
}
