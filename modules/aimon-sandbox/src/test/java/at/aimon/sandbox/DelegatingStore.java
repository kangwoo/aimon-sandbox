package at.aimon.sandbox;

import java.util.List;
import java.util.Optional;

import at.aimon.sandbox.workspace.SandboxWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;
import at.aimon.sandbox.workspace.WorkspaceScan;

/** A store that forwards everything; tests override what they interleave with or tamper with. */
public class DelegatingStore implements SandboxWorkspaceStore {

    protected final SandboxWorkspaceStore delegate;

    public DelegatingStore(SandboxWorkspaceStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
        return delegate.find(id);
    }

    @Override
    public SandboxWorkspace createIfAbsent(SandboxWorkspace initial) {
        return delegate.createIfAbsent(initial);
    }

    @Override
    public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
        return delegate.update(id, expectedVersion, next);
    }

    @Override
    public void delete(SandboxWorkspaceId id, long expectedVersion) {
        delegate.delete(id, expectedVersion);
    }

    @Override
    public List<SandboxWorkspace> scan(WorkspaceScan scan) {
        return delegate.scan(scan);
    }
}
