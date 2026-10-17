package at.aimon.sandbox.workspace;

import at.aimon.sandbox.testkit.SessionOptionsStoreContract;

class InMemorySessionOptionsStoreTest extends SessionOptionsStoreContract {

    @Override
    protected SessionOptionsStore createStore() {
        return new InMemorySessionOptionsStore();
    }
}
