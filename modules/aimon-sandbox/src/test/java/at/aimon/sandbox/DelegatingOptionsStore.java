package at.aimon.sandbox;

import java.util.Optional;
import java.util.OptionalLong;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.workspace.Page;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionOptionsStore;
import at.aimon.sandbox.workspace.SessionSandboxOptions;

/** An options store that forwards everything; tests override what they interleave with or fail. */
public class DelegatingOptionsStore implements SessionOptionsStore {

    protected final SessionOptionsStore delegate;

    public DelegatingOptionsStore(SessionOptionsStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<SessionSandboxOptions> find(SessionId session) {
        return delegate.find(session);
    }

    @Override
    public SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expectedVersion) {
        return delegate.put(options, expectedVersion);
    }

    @Override
    public void delete(SessionId session, long expectedVersion) {
        delegate.delete(session, expectedVersion);
    }

    @Override
    public Page<SessionSandboxOptions, SessionId> byWorkspace(SandboxWorkspaceId id, Optional<SessionId> after,
            int limit) {
        return delegate.byWorkspace(id, after, limit);
    }

    @Override
    public Page<SandboxWorkspaceId, SandboxWorkspaceId> workspaces(Optional<SandboxWorkspaceId> after, int limit) {
        return delegate.workspaces(after, limit);
    }

    @Override
    public int deleteByWorkspace(SandboxWorkspaceId id) {
        return delegate.deleteByWorkspace(id);
    }
}
