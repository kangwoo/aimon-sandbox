package at.aimon.sandbox.workspace;

import java.util.Objects;

import at.aimon.core.agent.session.SessionId;

/**
 * An operator's view of one workspace (docs/design/workspace-sandbox-management.md §5.4): the workspace and the first
 * page of the sessions attached to it; {@code SandboxAdministration.sessions} pages on.
 */
public final class WorkspaceDetail {

    private final WorkspaceView workspace;
    private final Page<SessionOptionsView, SessionId> sessions;

    private WorkspaceDetail(WorkspaceView workspace, Page<SessionOptionsView, SessionId> sessions) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    }

    /**
     * @param workspace
     *            the workspace
     * @param sessions
     *            the first page of its attached sessions
     * @return the detail
     */
    public static WorkspaceDetail of(WorkspaceView workspace, Page<SessionOptionsView, SessionId> sessions) {
        return new WorkspaceDetail(workspace, sessions);
    }

    /** @return the workspace */
    public WorkspaceView workspace() {
        return workspace;
    }

    /** @return the first page of the sessions attached to it */
    public Page<SessionOptionsView, SessionId> sessions() {
        return sessions;
    }

    @Override
    public String toString() {
        return "WorkspaceDetail{" + workspace + ", sessions=" + sessions + '}';
    }
}
