package at.aimon.sandbox.opensandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * The JDK {@link HttpClient} shared by every call of one provider, with the {@code max-concurrent-calls} semaphore over
 * request/response calls. Streams ({@code /command}, downloads) take no permit: a twenty-minute build must not starve
 * the janitor.
 */
final class Transport {

    /** Nothing answered at the address: the caller may re-resolve the endpoint once. */
    static final class ConnectFailed extends SandboxProviderException {
        private static final long serialVersionUID = 1L;

        ConnectFailed(String message, Throwable cause) {
            super(message, Kind.TRANSIENT, cause);
        }
    }

    final ObjectMapper json = new ObjectMapper();
    private final HttpClient client;
    private final Semaphore permits;
    private final Duration requestTimeout;

    Transport(Duration requestTimeout, int maxConcurrentCalls) {
        this.requestTimeout = requestTimeout;
        this.permits = new Semaphore(maxConcurrentCalls);
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(
                        requestTimeout.compareTo(Duration.ofSeconds(10)) < 0 ? requestTimeout : Duration.ofSeconds(10))
                .build();
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * One request/response call, bounded by {@code request-timeout} unless the request sets its own.
     *
     * @param what
     *            the call, for messages
     */
    HttpResponse<byte[]> send(HttpRequest.Builder request, String what) {
        return send(request.timeout(requestTimeout).build(), what);
    }

    /** Like {@link #send(HttpRequest.Builder, String)} with the request's own timeout (the create call's). */
    HttpResponse<byte[]> send(HttpRequest request, String what) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxProviderException("interrupted while waiting to call OpenSandbox (" + what + ")");
        }
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw transportFailure(what, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxProviderException("interrupted while calling OpenSandbox (" + what + ")");
        } finally {
            permits.release();
        }
    }

    /** A streamed response: returns once the headers arrived; takes no permit and has no overall timeout. */
    HttpResponse<InputStream> stream(HttpRequest request, String what) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw transportFailure(what, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxProviderException("interrupted while calling OpenSandbox (" + what + ")");
        }
    }

    JsonNode read(HttpResponse<byte[]> response) {
        try {
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new SandboxProviderException("OpenSandbox answered with malformed JSON",
                    SandboxProviderException.Kind.TRANSIENT, e);
        }
    }

    byte[] write(Object body) {
        try {
            return json.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SandboxProviderException transportFailure(String what, IOException e) {
        final String message = "OpenSandbox call failed (" + what + "): " + e;
        if (HttpErrors.connectFailure(e)) {
            return new ConnectFailed(message, e);
        }
        if (e instanceof HttpTimeoutException) {
            return new SandboxProviderException("OpenSandbox call timed out (" + what + ")",
                    SandboxProviderException.Kind.TRANSIENT, e);
        }
        return new SandboxProviderException(message, SandboxProviderException.Kind.TRANSIENT, e);
    }
}
