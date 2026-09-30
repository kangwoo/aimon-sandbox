package at.aimon.sandbox.opensandbox;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * Classifies a failed call by its HTTP status, never by the server's error code — codes carry a runtime prefix
 * ({@code DOCKER::…}) that differs between runtimes and even appears on the wrong one (docs/design/opensandbox-spike.md
 * §2). 404 is the caller's to interpret (sandbox gone, file missing, success on destroy).
 */
final class HttpErrors {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpErrors() {
    }

    /** Thrown for a 404, so each caller can say what was not found. */
    static final class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        NotFound(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        /** @return the server's error code, when the body had one */
        String code() {
            return code;
        }
    }

    /**
     * @param response
     *            a response
     * @param what
     *            the call, for the message
     * @return the response, when it succeeded
     * @throws NotFound
     *             on 404
     * @throws SandboxProviderException
     *             on any other failure, classified by status
     */
    static <T> HttpResponse<T> check(HttpResponse<T> response, String what) {
        check(response.statusCode(), response.body(), what);
        return response;
    }

    /** {@link #check(HttpResponse, String)} for a status and a body read separately (a streamed response). */
    static void check(int status, Object body, String what) {
        if (status >= 200 && status < 300) {
            return;
        }
        final JsonNode error = errorBody(body);
        final String code = error == null ? null : error.path("code").asText(null);
        final String message = error == null ? bodyText(body) : error.path("message").asText("");
        if (status == 404) {
            throw new NotFound(code, message);
        }
        throw failure(status, what, message);
    }

    /** @return the exception a failed status maps to (§8.1 of the step-4 design) */
    static SandboxProviderException failure(int status, String what, String message) {
        final String text = what + " failed (HTTP " + status + "): " + message;
        if (status == 400 || status == 422) {
            return new SandboxProviderException(text, SandboxProviderException.Kind.PERMANENT, null);
        }
        if (status == 401 || status == 403) {
            return new SandboxProviderException(text + " (check the OpenSandbox api-key)",
                    SandboxProviderException.Kind.PERMANENT, null);
        }
        return new SandboxProviderException(text, SandboxProviderException.Kind.TRANSIENT, null);
    }

    /** Whether a transport failure means nothing answered at the address (the endpoint may have moved). */
    static boolean connectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode errorBody(Object body) {
        final String text = bodyText(body);
        if (text.isEmpty() || text.charAt(0) != '{') {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String bodyText(Object body) {
        if (body instanceof byte[] bytes) {
            final String text = new String(bytes, StandardCharsets.UTF_8).strip();
            return text.length() > 500 ? text.substring(0, 500) + "…" : text;
        }
        if (body instanceof String text) {
            return text.strip();
        }
        return "";
    }
}
