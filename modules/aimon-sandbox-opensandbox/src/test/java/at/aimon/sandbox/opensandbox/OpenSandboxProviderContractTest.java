package at.aimon.sandbox.opensandbox;

import org.junit.jupiter.api.Tag;

import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.testkit.SandboxProviderContract;

/**
 * The provider contract suite against a real OpenSandbox server on the Docker runtime (docs/design/workspace-sandbox.md
 * §16): label acceptance by the real server, idempotent create by key, the files API, kill and truncation.
 */
@Tag("docker")
class OpenSandboxProviderContractTest extends SandboxProviderContract {

    @Override
    protected SandboxProvider createProvider() {
        return new OpenSandboxProvider(OpenSandboxTestServer.dnsNft().config().build());
    }

    @Override
    protected String image() {
        return OpenSandboxTestServer.sandboxImage();
    }

    @Override
    protected String deployment() {
        return OpenSandboxTestServer.DEPLOYMENT;
    }
}
