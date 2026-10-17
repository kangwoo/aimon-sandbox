package at.aimon.sandbox.testkit;

import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.SessionOptionsStore;

/** Runs the options store contract from the testkit itself, so a broken suite fails here first. */
class InMemorySessionOptionsStoreContractTest extends SessionOptionsStoreContract {

    @Override
    protected SessionOptionsStore createStore() {
        return new InMemorySessionOptionsStore();
    }
}
