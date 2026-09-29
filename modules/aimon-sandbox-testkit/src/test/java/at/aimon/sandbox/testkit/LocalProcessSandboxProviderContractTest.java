package at.aimon.sandbox.testkit;

import at.aimon.sandbox.provider.SandboxProvider;

class LocalProcessSandboxProviderContractTest extends SandboxProviderContract {

    @Override
    protected SandboxProvider createProvider() {
        return LocalProcessSandboxProvider.builder().build();
    }
}
