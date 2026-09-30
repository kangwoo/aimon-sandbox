package at.aimon.sandbox.opensandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A scripted OpenSandbox for the default test tier, on the JDK's {@link HttpServer}: a small stateful lifecycle API
 * (sandboxes, paged listing with the metadata filter, renew, endpoints through the server proxy, network policy), an
 * in-memory execd file system, and a {@code /command} whose events each test scripts. Every request is recorded; any
 * route can be overridden to inject a failure.
 */
final class FakeOpenSandboxServer implements AutoCloseable {

    static final String API_KEY = "fake-key";

    /** One recorded request. */
    static final class Request {
        final String method;
        final String path;
        final String query;
        final Map<String, List<String>> headers;
        final byte[] body;

        Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query == null ? "" : query;
            this.headers = headers;
            this.body = body;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(e -> e.getValue().get(0)).orElse(null);
        }

        String param(String name) {
            for (String pair : query.split("&")) {
                final int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return method + " " + path + (query.isEmpty() ? "" : "?" + query);
        }
    }

    /** A reply: status, body, and for a stream the events to send (and whether to hold it open). */
    static final class Reply {
        final int status;
        final byte[] body;
        final List<String> events;
        final CountDownLatch hold;
        final CountDownLatch gate;

        private Reply(int status, byte[] body, List<String> events, CountDownLatch hold) {
            this(status, body, events, hold, null);
        }

        private Reply(int status, byte[] body, List<String> events, CountDownLatch hold, CountDownLatch gate) {
            this.status = status;
            this.body = body;
            this.events = events;
            this.hold = hold;
            this.gate = gate;
        }

        /** Like {@link #events}, but nothing is sent after the headers until {@code gate} is counted down. */
        static Reply gated(CountDownLatch gate, List<String> events, CountDownLatch hold) {
            return new Reply(200, new byte[0], events, hold, gate);
        }

        static Reply json(int status, Object body) {
            try {
                return new Reply(status, JSON.writeValueAsBytes(body), null, null);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static Reply status(int status) {
            return new Reply(status, new byte[0], null, null);
        }

        static Reply error(int status, String code, String message) {
            return json(status, Map.of("code", code, "message", message));
        }

        static Reply bytes(int status, byte[] body) {
            return new Reply(status, body, null, null);
        }

        /** An SSE stream of these event objects; {@code hold} keeps it open after them until counted down. */
        static Reply events(List<String> events, CountDownLatch hold) {
            return new Reply(200, new byte[0], events, hold);
        }
    }

    /** One sandbox of the fake lifecycle API. */
    static final class Sandbox {
        final String id;
        volatile String state;
        final Map<String, String> metadata;
        volatile Instant expiresAt;
        final Instant createdAt;
        final JsonNode request;
        int pendingPolls;

        Sandbox(String id, String state, Map<String, String> metadata, Instant expiresAt, Instant createdAt,
                JsonNode request) {
            this.id = id;
            this.state = state;
            this.metadata = metadata;
            this.expiresAt = expiresAt;
            this.createdAt = createdAt;
            this.request = request;
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();
    /** The execd file system: path → content ({@code null} for a directory). */
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final List<String> interrupted = new CopyOnWriteArrayList<>();
    final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    /** Overrides by {@code "METHOD /path-prefix"}; a function returning null falls through. */
    final Map<String, Function<Request, Reply>> overrides = new ConcurrentHashMap<>();
    volatile Function<JsonNode, Reply> commands = body -> Reply.events(List.of(init("c1"), complete()), null);
    volatile JsonNode networkPolicy = JSON.createObjectNode().put("status", "ok").put("enforcementMode", "dns+nft")
            .set("policy", JSON.createObjectNode().put("defaultAction", "deny"));
    volatile JsonNode vault;
    volatile JsonNode commandStatus = JSON.createObjectNode().put("running", false).put("exit_code", 0);
    volatile String createState = "Running";
    volatile int pendingPolls;

    FakeOpenSandboxServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            final Thread thread = new Thread(r, "fake-opensandbox");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
        files.put("/", new byte[0]);
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    OpenSandboxProviderConfig.Builder config() {
        return OpenSandboxProviderConfig.builder().endpoint(endpoint()).apiKey(() -> API_KEY)
                .runtime(OpenSandboxProviderConfig.Runtime.DOCKER).maxExpiry(Duration.ofHours(2)).useServerProxy(true)
                .retry(3, Duration.ofMillis(10)).createTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5));
    }

    static String init(String id) {
        return "{\"type\":\"init\",\"text\":\"" + id + "\"}";
    }

    static String out(String text) {
        return event("stdout", text);
    }

    static String err(String text) {
        return event("stderr", text);
    }

    static String event(String type, String text) {
        return JSON.createObjectNode().put("type", type).put("text", text).toString();
    }

    static String complete() {
        return "{\"type\":\"execution_complete\",\"execution_time\":1}";
    }

    static String exit(String value, String traceback) {
        final ObjectNode event = JSON.createObjectNode().put("type", "error");
        event.putObject("error").put("ename", "CommandExecError").put("evalue", value).putArray("traceback")
                .add(traceback);
        return event.toString();
    }

    /** Adds a sandbox as if created earlier. */
    Sandbox add(String id, String state, Map<String, String> metadata, Instant createdAt) {
        final Sandbox sandbox = new Sandbox(id, state, new LinkedHashMap<>(metadata),
                Instant.now().plus(Duration.ofHours(1)), createdAt, null);
        sandboxes.put(id, sandbox);
        return sandbox;
    }

    List<Request> requests(String method, String pathPrefix) {
        final List<Request> matching = new ArrayList<>();
        for (Request request : requests) {
            if (request.method.equals(method) && request.path.startsWith(pathPrefix)) {
                matching.add(request);
            }
        }
        return matching;
    }

    private void handle(HttpExchange exchange) throws IOException {
        final byte[] body = exchange.getRequestBody().readAllBytes();
        final Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(request);
        Reply reply = null;
        for (Map.Entry<String, Function<Request, Reply>> override : overrides.entrySet()) {
            final String[] key = override.getKey().split(" ", 2);
            if (key[0].equals(request.method) && request.path.startsWith(key[1])) {
                reply = override.getValue().apply(request);
                if (reply != null) {
                    break;
                }
            }
        }
        if (reply == null) {
            reply = route(request);
        }
        send(exchange, reply);
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        if (reply.events != null) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.flush();
                if (reply.gate != null) {
                    try {
                        reply.gate.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                for (String event : reply.events) {
                    out.write((event + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (reply.hold != null) {
                    try {
                        reply.hold.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (IOException e) {
                // the client went away
            }
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (reply.body.length == 0) {
            exchange.sendResponseHeaders(reply.status, -1);
        } else {
            exchange.sendResponseHeaders(reply.status, reply.body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply.body);
            }
        }
        exchange.close();
    }

    private Reply route(Request request) {
        if (!request.path.startsWith("/v1/sandboxes")) {
            return Reply.status(404);
        }
        final String[] parts = request.path.substring("/v1/sandboxes".length()).split("/", -1);
        // parts[0] is "" (the path after /v1/sandboxes starts with / or is empty)
        if (parts.length >= 5 && "proxy".equals(parts[2])) {
            final Sandbox sandbox = sandboxes.get(parts[1]);
            if (sandbox == null) {
                return Reply.error(404, "DOCKER::SANDBOX_NOT_FOUND", "Sandbox " + parts[1] + " not found.");
            }
            final String rest = "/" + String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
            return "18080".equals(parts[3]) ? egress(request, rest) : execd(request, rest);
        }
        if (!API_KEY.equals(request.header("OPEN-SANDBOX-API-KEY"))) {
            return Reply.error(401, "INVALID_API_KEY", "bad key");
        }
        if (parts.length == 1 || parts[1].isEmpty()) {
            return "POST".equals(request.method) ? create(request) : list(request);
        }
        final Sandbox sandbox = sandboxes.get(parts[1]);
        if (sandbox == null) {
            return Reply.error(404, "DOCKER::SANDBOX_NOT_FOUND", "Sandbox " + parts[1] + " not found.");
        }
        if (parts.length == 2) {
            if ("DELETE".equals(request.method)) {
                sandboxes.remove(sandbox.id);
                return Reply.status(204);
            }
            if (sandbox.pendingPolls > 0) {
                sandbox.pendingPolls--;
                if (sandbox.pendingPolls == 0) {
                    sandbox.state = "Running";
                }
            }
            return Reply.json(200, describe(sandbox));
        }
        switch (parts[2]) {
            case "renew-expiration" :
                sandbox.expiresAt = Instant.parse(request.json().path("expiresAt").asText());
                return Reply.json(200, Map.of("expiresAt", sandbox.expiresAt.toString()));
            case "endpoints" : {
                final Map<String, Object> endpoint = new LinkedHashMap<>();
                endpoint.put("endpoint", "server.internal:8090/v1/sandboxes/" + sandbox.id + "/proxy/" + parts[3]);
                if ("18080".equals(parts[3])) {
                    endpoint.put("headers", Map.of("OPENSANDBOX-EGRESS-AUTH", "egress-token"));
                }
                return Reply.json(200, endpoint);
            }
            case "networkpolicy" :
                return Reply.json(200, networkPolicy);
            default :
                return Reply.status(404);
        }
    }

    private Reply create(Request request) {
        final JsonNode body = request.json();
        final Map<String, String> metadata = new LinkedHashMap<>();
        body.path("metadata").properties().forEach(e -> metadata.put(e.getKey(), e.getValue().asText()));
        final Instant now = Instant.now();
        final Sandbox sandbox = new Sandbox(UUID.randomUUID().toString(), createState, metadata,
                now.plusSeconds(body.path("timeout").asLong(0)), now, body);
        sandbox.pendingPolls = pendingPolls;
        sandboxes.put(sandbox.id, sandbox);
        return Reply.json(202, describe(sandbox));
    }

    private Reply list(Request request) {
        final Map<String, String> filter = new LinkedHashMap<>();
        final String metadata = request.param("metadata");
        if (metadata != null && !metadata.isEmpty()) {
            for (String pair : metadata.split("&")) {
                final int eq = pair.indexOf('=');
                filter.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        final List<Sandbox> matching = new ArrayList<>();
        for (Sandbox sandbox : sandboxes.values()) {
            if (sandbox.metadata.entrySet().containsAll(filter.entrySet())) {
                matching.add(sandbox);
            }
        }
        matching.sort(Comparator.comparing((Sandbox s) -> s.createdAt).reversed().thenComparing(s -> s.id));
        final int page = Integer.parseInt(Optional.ofNullable(request.param("page")).orElse("1"));
        final int size = Integer.parseInt(Optional.ofNullable(request.param("pageSize")).orElse("20"));
        final ObjectNode reply = JSON.createObjectNode();
        final ArrayNode items = reply.putArray("items");
        matching.stream().skip((long) (page - 1) * size).limit(size).forEach(s -> items.add(describe(s)));
        reply.putObject("pagination").put("page", page).put("pageSize", size).put("totalItems", matching.size())
                .put("hasNextPage", (long) page * size < matching.size());
        return Reply.json(200, reply);
    }

    private static ObjectNode describe(Sandbox sandbox) {
        final ObjectNode node = JSON.createObjectNode().put("id", sandbox.id);
        node.putObject("status").put("state", sandbox.state);
        final ObjectNode metadata = node.putObject("metadata");
        sandbox.metadata.forEach(metadata::put);
        node.put("expiresAt", sandbox.expiresAt.toString());
        node.put("createdAt", sandbox.createdAt.toString());
        return node;
    }

    private Reply egress(Request request, String rest) {
        if (!"egress-token".equals(request.header("OPENSANDBOX-EGRESS-AUTH"))) {
            return Reply.error(401, "UNAUTHORIZED", "unauthorized");
        }
        if (!"/credential-vault".equals(rest)) {
            return Reply.status(404);
        }
        if ("POST".equals(request.method)) {
            if (vault != null) {
                return Reply.error(409, "CONFLICT", "vault exists");
            }
            vault = request.json();
            return Reply.json(201, vault);
        }
        if ("DELETE".equals(request.method)) {
            final boolean existed = vault != null;
            vault = null;
            return existed ? Reply.status(204) : Reply.error(404, "NOT_FOUND", "no vault");
        }
        return vault == null ? Reply.error(404, "NOT_FOUND", "no vault") : Reply.json(200, vault);
    }

    private Reply execd(Request request, String rest) {
        final String path = request.param("path");
        switch (request.method + " " + rest) {
            case "POST /command" :
                return commands.apply(request.json());
            case "DELETE /command" : {
                interrupted.add(request.param("id"));
                final CountDownLatch hold = holds.remove(request.param("id"));
                if (hold != null) {
                    hold.countDown();
                }
                return Reply.status(200);
            }
            case "GET /files/info" : {
                if (!files.containsKey(path)) {
                    return Reply.error(404, "FILE_NOT_FOUND", "file not found: " + path);
                }
                return Reply.json(200, Map.of(path, info(path)));
            }
            case "GET /files/download" : {
                final byte[] content = files.get(path);
                if (content == null) {
                    return Reply.error(404, "FILE_NOT_FOUND", "file not found: " + path);
                }
                final String range = request.header("Range");
                if (range == null) {
                    return Reply.bytes(200, content);
                }
                final String[] bounds = range.substring("bytes=".length()).split("-", -1);
                final int from = Integer.parseInt(bounds[0]);
                if (from >= content.length) {
                    return Reply.status(416);
                }
                final int to = bounds[1].isEmpty()
                        ? content.length - 1
                        : Math.min(content.length - 1, Integer.parseInt(bounds[1]));
                return Reply.bytes(206, java.util.Arrays.copyOfRange(content, from, to + 1));
            }
            case "POST /files/upload" :
                return upload(request);
            case "GET /directories/list" : {
                if (!files.containsKey(path)) {
                    return Reply.error(404, "FILE_NOT_FOUND", "file not found: " + path);
                }
                if (files.get(path).length > 0 || !isDirectory(path)) {
                    return Reply.error(400, "INVALID_REQUEST_BODY", "path is not a directory: " + path);
                }
                final int depth = Integer.parseInt(Optional.ofNullable(request.param("depth")).orElse("1"));
                final ArrayNode entries = JSON.createArrayNode();
                new TreeMap<>(files).keySet().stream().filter(p -> p.startsWith(path.equals("/") ? "/" : path + "/")
                        && !p.equals(path) && depthBelow(path, p) <= depth).forEach(p -> entries.add(info(p)));
                return Reply.json(200, entries);
            }
            case "POST /directories" : {
                request.json().fieldNames().forEachRemaining(dir -> mkdirs(dir));
                return Reply.status(200);
            }
            case "DELETE /files" :
                files.remove(path);
                return Reply.status(200);
            case "DELETE /directories" :
                files.keySet().removeIf(p -> p.equals(path) || p.startsWith(path + "/"));
                return Reply.status(200);
            case "POST /files/mv" : {
                final JsonNode move = request.json().get(0);
                final String src = move.path("src").asText();
                final String dest = move.path("dest").asText();
                if (!files.containsKey(src)) {
                    return Reply.error(404, "FILE_NOT_FOUND", "file not found: " + src);
                }
                if (files.containsKey(dest)) {
                    return Reply.error(500, "RUNTIME_ERROR",
                            "error accessing file: destination path already exists: " + dest);
                }
                files.put(dest, files.remove(src));
                return Reply.status(200);
            }
            default :
                if (rest.startsWith("/command/status/")) {
                    return Reply.json(200, commandStatus);
                }
                return Reply.status(404);
        }
    }

    /** Directories are stored as empty content under a trailing marker set. */
    private final java.util.Set<String> directories = ConcurrentHashMap.newKeySet();

    void mkdirs(String dir) {
        String current = "";
        for (String part : dir.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            current = current + "/" + part;
            files.putIfAbsent(current, new byte[0]);
            directories.add(current);
        }
    }

    boolean isDirectory(String path) {
        return "/".equals(path) || directories.contains(path);
    }

    private static int depthBelow(String parent, String child) {
        final String relative = child.substring(parent.equals("/") ? 1 : parent.length() + 1);
        return relative.split("/").length;
    }

    private ObjectNode info(String path) {
        final boolean directory = isDirectory(path);
        return JSON.createObjectNode().put("path", path).put("type", directory ? "directory" : "file")
                .put("size", directory ? 4096 : files.get(path).length)
                .put("modified_at", "2026-09-29T23:43:46.891791008Z").put("mode", directory ? 755 : 644);
    }

    /** Multipart: the metadata part must be a file part (filename), as execd requires. */
    private Reply upload(Request request) {
        final String type = request.header("Content-Type");
        final String boundary = type.substring(type.indexOf("boundary=") + "boundary=".length());
        final String text = new String(request.body, StandardCharsets.ISO_8859_1);
        final List<String> parts = new ArrayList<>(List.of(text.split("--" + java.util.regex.Pattern.quote(boundary))));
        String path = null;
        byte[] content = null;
        for (String part : parts) {
            final int split = part.indexOf("\r\n\r\n");
            if (split < 0) {
                continue;
            }
            final String headers = part.substring(0, split);
            final String data = part.substring(split + 4, part.length() - 2);
            if (headers.contains("name=\"metadata\"")) {
                if (!headers.contains("filename=")) {
                    return Reply.error(400, "INVALID_FILE_METADATA", "metadata file is missing");
                }
                try {
                    path = JSON.readTree(data).path("path").asText();
                } catch (IOException e) {
                    return Reply.error(400, "INVALID_FILE_METADATA", "bad metadata");
                }
            } else if (headers.contains("name=\"file\"")) {
                content = data.getBytes(StandardCharsets.ISO_8859_1);
            }
        }
        if (path == null || content == null) {
            return Reply.error(400, "INVALID_REQUEST_BODY", "missing parts");
        }
        mkdirs(path.substring(0, Math.max(1, path.lastIndexOf('/'))));
        files.put(path, content);
        directories.remove(path);
        return Reply.status(200);
    }

    /**
     * A {@code /command} that runs the provider's file helpers against the in-memory file system: {@code set -- 'a'
     * 'b'; …} with {@code ln} (refused when the target exists) or {@code mv -f}.
     */
    Function<JsonNode, Reply> fileHelpers() {
        final java.util.regex.Pattern args = java.util.regex.Pattern.compile("^set -- '([^']*)' '([^']*)'; ");
        return body -> {
            final String command = body.path("command").asText();
            final java.util.regex.Matcher m = args.matcher(command);
            if (!m.find()) {
                return Reply.events(List.of(init("h"), complete()), null);
            }
            final String from = m.group(1);
            final String to = m.group(2);
            if (command.contains("ln -- ")) {
                final byte[] content = files.remove(from);
                if (files.containsKey(to)) {
                    return Reply
                            .events(List.of(init("h"), err("ln: failed to create hard link '" + to + "': File exists"),
                                    exit("1", "exit status 1")), null);
                }
                files.put(to, content);
                return Reply.events(List.of(init("h"), complete()), null);
            }
            if (isDirectory(to)) {
                files.remove(from);
                return Reply.events(List.of(init("h"), err(to + ": Is a directory"), exit("21", "exit status 21")),
                        null);
            }
            mkdirs(to.substring(0, Math.max(1, to.lastIndexOf('/'))));
            files.put(to, files.remove(from));
            return Reply.events(List.of(init("h"), complete()), null);
        };
    }

    List<String> fileNames() {
        return Collections.unmodifiableList(new ArrayList<>(new TreeMap<>(files).keySet()));
    }

    @Override
    public void close() {
        holds.values().forEach(CountDownLatch::countDown);
        server.stop(0);
    }
}
