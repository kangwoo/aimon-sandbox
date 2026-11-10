package at.aimon.sandbox.opensandbox;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;

import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.testkit.SandboxProviderContract;

/**
 * The provider contract suite against a real OpenSandbox server on the Docker runtime (docs/design/workspace-sandbox.md
 * §16): label acceptance by the real server, idempotent create by key, the files API, kill and truncation. It runs
 * {@code @Isolated}, as {@link OpenSandboxEgressIT} does: a create's port-conflict retry reads the shared server's log
 * since a mark ({@link OpenSandboxTestServer#logSince}), which must be this class's calls alone.
 */
@Tag("docker")
@Isolated("a failed create reads the shared server's log since a mark: no other class may call that server meanwhile")
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

    /**
     * A create that Docker refused for a host port the server picked is retried once
     * ({@link OpenSandboxTestServer#retryOnPortConflict(Class, java.util.function.Supplier)}): the server retries that
     * itself here, but one still reached CI (PR #55, {@code extendExpiryMovesForwardOnly}).
     */
    @Override
    protected ProviderSandboxRef create(CreateSpec spec) {
        return OpenSandboxTestServer.dnsNft().retryOnPortConflict(getClass(), () -> super.create(spec));
    }
}
