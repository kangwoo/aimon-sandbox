package at.aimon.sandbox.opensandbox;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Function;
import java.util.function.Supplier;

import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxNotFoundException;

/**
 * One sandbox's in-sandbox daemon (execd on port 44772, or the egress sidecar on 18080), reached at the address the
 * lifecycle server resolves. The address is cached and resolved again once after a connect failure — on Kubernetes a
 * resumed sandbox comes back with a new IP (docs/design/opensandbox-spike.md §3). A sandbox that is gone is
 * {@link SandboxNotFoundException}; which 404 means "gone" and which "no such file" is settled by asking the lifecycle
 * server, since through the server proxy both are 404.
 */
final class ExecdClient {

    static final int EXECD_PORT = 44772;
    static final int EGRESS_PORT = 18080;

    /** execd's own code for a missing file: the one 404 that needs no second call to tell apart. */
    private static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";

    private final Transport transport;
    private final LifecycleClient lifecycle;
    private final ProviderSandboxRef ref;
    private final int port;
    private volatile LifecycleClient.Endpoint endpoint;

    ExecdClient(Transport transport, LifecycleClient lifecycle, ProviderSandboxRef ref, int port) {
        this.transport = transport;
        this.lifecycle = lifecycle;
        this.ref = ref;
        this.port = port;
    }

    ProviderSandboxRef ref() {
        return ref;
    }

    Transport transport() {
        return transport;
    }

    private LifecycleClient.Endpoint endpoint(boolean fresh) {
        LifecycleClient.Endpoint current = endpoint;
        if (current == null || fresh) {
            try {
                current = lifecycle.endpoint(ref.sandboxId(), port);
            } catch (HttpErrors.NotFound e) {
                throw new SandboxNotFoundException(ref);
            }
            endpoint = current;
        }
        return current;
    }

    /** Resolves now, so a connection to an absent sandbox fails at {@code connect}. */
    void resolve() {
        endpoint(false);
    }

    /** A request/response call; one re-resolution after a connect failure. */
    HttpResponse<byte[]> send(Function<LifecycleClient.Endpoint, HttpRequest.Builder> request, String what) {
        return withReresolve(ep -> transport.send(request.apply(ep), what));
    }

    /** A streamed call; one re-resolution after a connect failure. */
    HttpResponse<InputStream> stream(Function<LifecycleClient.Endpoint, HttpRequest> request, String what) {
        return withReresolve(ep -> transport.stream(request.apply(ep), what));
    }

    private <T> T withReresolve(Function<LifecycleClient.Endpoint, T> call) {
        try {
            return call.apply(endpoint(false));
        } catch (Transport.ConnectFailed first) {
            final LifecycleClient.Endpoint moved = endpoint(true);
            try {
                return call.apply(moved);
            } catch (Transport.ConnectFailed again) {
                requireSandbox();
                throw again;
            }
        }
    }

    /**
     * @throws SandboxNotFoundException
     *             when the lifecycle server no longer knows the sandbox
     */
    void requireSandbox() {
        if (lifecycle.get(ref.sandboxId()).isEmpty()) {
            endpoint = null;
            throw new SandboxNotFoundException(ref);
        }
    }

    /**
     * What a 404 from this daemon means: the sandbox is gone ({@link SandboxNotFoundException}), or the call's own
     * target is missing ({@code otherwise}).
     */
    RuntimeException notFound(HttpErrors.NotFound e, Supplier<RuntimeException> otherwise) {
        if (!FILE_NOT_FOUND.equals(e.code())) {
            requireSandbox();
        }
        return otherwise.get();
    }
}
