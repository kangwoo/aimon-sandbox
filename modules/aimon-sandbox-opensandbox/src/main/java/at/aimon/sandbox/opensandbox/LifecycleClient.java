package at.aimon.sandbox.opensandbox;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The lifecycle server's {@code /v1/sandboxes} API, authenticated with {@code OPEN-SANDBOX-API-KEY}. Idempotent calls
 * go through {@link Retry}; {@code create} never does.
 */
final class LifecycleClient {

    /** The page size {@code list} asks for: the server's maximum (docs/design/opensandbox-spike.md §2). */
    static final int PAGE_SIZE = 200;

    /** Where a sandbox's port is reached: a base URI and the headers to send with every call. */
    static final class Endpoint {
        final URI base;
        final Map<String, String> headers;

        Endpoint(URI base, Map<String, String> headers) {
            this.base = base;
            this.headers = Map.copyOf(headers);
        }

        HttpRequest.Builder request(String pathAndQuery) {
            final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + pathAndQuery));
            headers.forEach(request::header);
            return request;
        }
    }

    private final Transport transport;
    private final URI base;
    private final Supplier<String> apiKey;
    private final Retry retry;
    private final boolean useServerProxy;

    LifecycleClient(Transport transport, URI base, Supplier<String> apiKey, Retry retry, boolean useServerProxy) {
        this.transport = transport;
        this.base = base;
        this.apiKey = apiKey;
        this.retry = retry;
        this.useServerProxy = useServerProxy;
    }

    private HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(base + pathAndQuery)).header("OPEN-SANDBOX-API-KEY", apiKey.get())
                .header("Accept", "application/json");
    }

    /** {@code POST /sandboxes}: never retried (a lost response is what the record and reconciliation absorb). */
    JsonNode create(ObjectNode body, Duration timeout) {
        final HttpResponse<byte[]> response = transport
                .send(request("/sandboxes").timeout(timeout).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(transport.write(body))).build(), "create sandbox");
        try {
            return transport.read(HttpErrors.check(response, "create sandbox"));
        } catch (HttpErrors.NotFound e) {
            throw HttpErrors.failure(404, "create sandbox", e.getMessage());
        }
    }

    /** @return the sandbox, or empty on 404 */
    Optional<JsonNode> get(String id) {
        return retry.call(() -> {
            final HttpResponse<byte[]> response = transport.send(request("/sandboxes/" + id).GET(), "get sandbox");
            try {
                return Optional.of(transport.read(HttpErrors.check(response, "get sandbox")));
            } catch (HttpErrors.NotFound e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Every sandbox carrying all the labels, every page, each id once: pages are numbered and newest-first, so a create
     * during the listing shifts them — a duplicate is dropped here, a miss is what reconciliation tolerates.
     */
    List<JsonNode> list(Map<String, String> labels) {
        final String filter = metadataFilter(labels);
        final Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (int page = 1;; page++) {
            final String query = "/sandboxes?pageSize=" + PAGE_SIZE + "&page=" + page
                    + (filter.isEmpty() ? "" : "&metadata=" + encode(filter));
            final JsonNode body = retry.call(() -> transport
                    .read(checkOrFail(transport.send(request(query).GET(), "list sandboxes"), "list sandboxes")));
            for (JsonNode item : body.path("items")) {
                byId.putIfAbsent(item.path("id").asText(), item);
            }
            if (!body.path("pagination").path("hasNextPage").asBoolean(false) || body.path("items").isEmpty()) {
                return new ArrayList<>(byId.values());
            }
        }
    }

    /**
     * The metadata filter is ONE parameter holding {@code k=v&k=v} (repeating the parameter keeps only the last one),
     * each key and value URL-encoded inside it (B §a).
     */
    static String metadataFilter(Map<String, String> labels) {
        final StringJoiner filter = new StringJoiner("&");
        labels.forEach((key, value) -> filter.add(encode(key) + "=" + encode(value)));
        return filter.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** {@code DELETE /sandboxes/{id}}: 404 is success. */
    void delete(String id) {
        retry.run(() -> {
            final HttpResponse<byte[]> response = transport.send(request("/sandboxes/" + id).DELETE(),
                    "delete sandbox");
            try {
                HttpErrors.check(response, "delete sandbox");
            } catch (HttpErrors.NotFound e) {
                // already gone
            }
        });
    }

    /**
     * {@code POST /sandboxes/{id}/renew-expiration}.
     *
     * @throws HttpErrors.NotFound
     *             when the sandbox is gone
     */
    void renew(String id, Instant expiresAt) {
        final ObjectNode body = transport.json.createObjectNode().put("expiresAt", expiresAt.toString());
        retry.run(
                () -> HttpErrors.check(transport.send(
                        request("/sandboxes/" + id + "/renew-expiration").header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(transport.write(body))),
                        "renew sandbox expiration"), "renew sandbox expiration"));
    }

    /**
     * Resolves where one of the sandbox's ports is reached. Under {@code use-server-proxy} only the returned path is
     * kept and resolved against the configured endpoint: the authority the server returns is its own view of its
     * address, which a client behind NAT or a port-forward cannot use.
     *
     * @throws HttpErrors.NotFound
     *             when the sandbox is gone
     */
    Endpoint endpoint(String id, int port) {
        final JsonNode body = retry
                .call(() -> transport.read(HttpErrors.check(
                        transport.send(request("/sandboxes/" + id + "/endpoints/" + port
                                + (useServerProxy ? "?use_server_proxy=true" : "")).GET(), "resolve endpoint"),
                        "resolve endpoint")));
        final String endpoint = body.path("endpoint").asText("");
        final Map<String, String> headers = new LinkedHashMap<>();
        body.path("headers").properties().forEach(h -> headers.put(h.getKey(), h.getValue().asText()));
        final URI resolved;
        if (useServerProxy) {
            final int slash = endpoint.indexOf('/');
            final String path = slash < 0 ? "" : endpoint.substring(slash);
            resolved = URI.create(base.getScheme() + "://" + base.getRawAuthority() + path);
        } else {
            resolved = URI.create(endpoint.contains("://") ? endpoint : "http://" + endpoint);
        }
        return new Endpoint(URI.create(resolved.toString().replaceAll("/+$", "")), headers);
    }

    /**
     * {@code GET /sandboxes/{id}/networkpolicy}: the mode the egress sidecar enforces.
     *
     * @throws HttpErrors.NotFound
     *             when the sandbox is gone
     */
    JsonNode networkPolicy(String id) {
        return retry.call(() -> transport.read(HttpErrors.check(
                transport.send(request("/sandboxes/" + id + "/networkpolicy").GET(), "read network policy"),
                "read network policy")));
    }

    private static HttpResponse<byte[]> checkOrFail(HttpResponse<byte[]> response, String what) {
        try {
            return HttpErrors.check(response, what);
        } catch (HttpErrors.NotFound e) {
            throw HttpErrors.failure(404, what, e.getMessage());
        }
    }
}
