package at.aimon.sandbox.workspace;

import at.aimon.sandbox.testkit.SandboxWorkspaceStoreContract;

class InMemorySandboxWorkspaceStoreContractTest extends SandboxWorkspaceStoreContract {

    @Override
    protected SandboxWorkspaceStore createStore() {
        return new InMemorySandboxWorkspaceStore();
    }
}
