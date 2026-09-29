package at.aimon.sandbox.testkit;

import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;

/** Runs the store contract from the testkit itself, so a broken suite fails here first. */
class InMemoryStoreContractTest extends SandboxWorkspaceStoreContract {

    @Override
    protected SandboxWorkspaceStore createStore() {
        return new InMemorySandboxWorkspaceStore();
    }
}
