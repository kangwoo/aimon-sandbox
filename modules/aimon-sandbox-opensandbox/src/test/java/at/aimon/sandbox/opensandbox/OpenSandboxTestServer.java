package at.aimon.sandbox.opensandbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;

import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * The OpenSandbox server of the {@code docker} tier (docs/design/workspace-sandbox.md §16): one container per egress
 * mode, started on first use from the runner's Docker socket and shared by every test class of the run, which labels
 * everything with deployment {@code ci-{runId}} and deletes what is still listed under it afterwards.
 *
 * <p>
 * {@code OPENSANDBOX_TEST_ENDPOINT} and {@code OPENSANDBOX_TEST_API_KEY} point the tier at a server started elsewhere
 * instead (its egress mode is then whatever that server runs).
 */
final class OpenSandboxTestServer {

    /** {@code opensandbox/server:release-1.1.0}, the newest release tag, by digest. */
    static final String SERVER_IMAGE = env("OPENSANDBOX_TEST_SERVER_IMAGE",
            "opensandbox/server@sha256:68ca0212a2749b2c73096ce2ec0264455c64442c45f81007db442f52bf84c9d1");
    /** The execd the docs pin, as the spike ran it (by digest). */
    static final String EXECD_IMAGE = env("OPENSANDBOX_TEST_EXECD_IMAGE",
            "opensandbox/execd:v1.1.0@sha256:6cf7dba2f21f0b536e100563d841ac58a9f31c2b0a081b7ac76796a24d6f47e2");
    /** The egress sidecar the docs pin (by digest). */
    static final String EGRESS_IMAGE = env("OPENSANDBOX_TEST_EGRESS_IMAGE",
            "opensandbox/egress:v1.1.7@sha256:db7345d567b0970f384b8e3fa7a93a71b7f43d4b16bb2009de34096e9a87b3b5");

    /** This run's deployment label: sandboxes of other runs on a shared server are never touched. */
    static final String DEPLOYMENT = "ci-" + UUID.randomUUID().toString().substring(0, 8);

    /** The build step of {@link #sandboxImageWithoutPython3()}: POSIX sh, as the image's {@code /bin/sh} may be. */
    private static final String REMOVE_PYTHON3 = "IFS=:; for dir in $PATH; do rm -f \"$dir\"/python3 \"$dir\"/python3.*; done; "
            + "if command -v python3 >/dev/null 2>&1; then "
            + "echo \"python3 is still on PATH at $(command -v python3); the no-python3 image cannot be built\" >&2; "
            + "exit 1; fi";

    private static final String API_KEY = "aimon-test-" + UUID.randomUUID();
    private static final Map<String, OpenSandboxTestServer> SERVERS = new ConcurrentHashMap<>();
    private static volatile String sandboxImage;
    private static volatile String sandboxImageWithoutPython3;

    private final URI endpoint;
    private final String apiKey;
    private final GenericContainer<?> container;

    private OpenSandboxTestServer(URI endpoint, String apiKey, GenericContainer<?> container) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.container = container;
    }

    private static String env(String name, String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** @return the shared server whose egress sidecar runs {@code dns+nft} */
    static OpenSandboxTestServer dnsNft() {
        return SERVERS.computeIfAbsent("dns+nft", OpenSandboxTestServer::start);
    }

    /** @return a server whose egress sidecar runs {@code dns} only, to check that {@code verify} notices */
    static OpenSandboxTestServer dnsOnly() {
        return SERVERS.computeIfAbsent("dns", OpenSandboxTestServer::start);
    }

    private static OpenSandboxTestServer start(String egressMode) {
        final String external = System.getenv("OPENSANDBOX_TEST_ENDPOINT");
        if (external != null && !external.isBlank()) {
            return new OpenSandboxTestServer(URI.create(external), env("OPENSANDBOX_TEST_API_KEY", ""), null);
        }
        final String config = template().replace("${API_KEY}", API_KEY).replace("${EXECD_IMAGE}", EXECD_IMAGE)
                .replace("${EGRESS_IMAGE}", EGRESS_IMAGE).replace("${EGRESS_MODE}", egressMode);
        final GenericContainer<?> container = new GenericContainer<>(SERVER_IMAGE).withExposedPorts(8090)
                .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock")
                .withExtraHost("host.docker.internal", "host-gateway")
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8)),
                        "/etc/opensandbox/config.toml")
                .waitingFor(Wait.forHttp("/health").forPort(8090).withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        final OpenSandboxTestServer server = new OpenSandboxTestServer(
                URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8090)), API_KEY, container);
        Runtime.getRuntime().addShutdownHook(new Thread(server::cleanUp, "opensandbox-test-cleanup"));
        return server;
    }

    private static String template() {
        try (InputStream in = OpenSandboxTestServer.class.getResourceAsStream("server-config.toml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the sandbox image: the image contract plus a writable {@code /workspace}, built once per run */
    static synchronized String sandboxImage() {
        if (sandboxImage == null) {
            final String override = System.getenv("OPENSANDBOX_TEST_SANDBOX_IMAGE");
            sandboxImage = override != null && !override.isBlank()
                    ? override
                    : new ImageFromDockerfile("aimon-sandbox-opensandbox-test", false)
                            .withDockerfile(Path.of("src/test/docker/Dockerfile")).get();
        }
        return sandboxImage;
    }

    /**
     * @return {@link #sandboxImage()} with {@code python3} removed from {@code PATH} — an image that breaks the
     *         optional {@code python3} item of the image contract (§13.3) and nothing else, built once per run. Every
     *         {@code python3*} in every {@code PATH} directory goes, so an override image with {@code python3}
     *         outside {@code /usr/bin} works too; if one is still found, the build fails saying so.
     */
    static synchronized String sandboxImageWithoutPython3() {
        if (sandboxImageWithoutPython3 == null) {
            final String base = sandboxImage();
            sandboxImageWithoutPython3 = new ImageFromDockerfile("aimon-sandbox-opensandbox-test-no-python3", false)
                    .withDockerfileFromBuilder(dockerfile -> dockerfile.from(base).run(REMOVE_PYTHON3).build()).get();
        }
        return sandboxImageWithoutPython3;
    }

    URI endpoint() {
        return endpoint;
    }

    /** @return how far the server's log has grown, for {@link #logSince}; 0 for a server started elsewhere */
    int logMark() {
        return container == null ? 0 : container.getLogs().length();
    }

    /**
     * @return what the server logged since {@code mark}: the server's account of the calls made since, a failed
     *         create's Docker error among them, provided nothing else called the server meanwhile. Every server is its
     *         test JVM's own (another fork starts its own), and a class that reads it this way runs
     *         {@link org.junit.jupiter.api.parallel.Isolated @Isolated}, so no other class of its JVM runs alongside
     *         it even where JUnit's parallel execution is turned on
     */
    String logSince(int mark) {
        if (container == null) {
            return "(a server started elsewhere: its log is not available here)";
        }
        final String logs = container.getLogs();
        return logs.substring(Math.min(mark, logs.length()));
    }

    /** What a create's retry reads of a server's log: {@link #logMark} and {@link #logSince}. */
    interface Log {
        int mark();

        String since(int mark);
    }

    /**
     * @param caller
     *            the test class that creates, whose simple name prefixes the retry's stderr line as its other lines
     *            ({@code [OpenSandboxEgressIT] …}), so a log search for that prefix finds it
     * @return {@link #retryOnPortConflict(Log, Supplier, Consumer)} on this server's log, the retry said on stderr
     */
    <T> T retryOnPortConflict(Class<?> caller, Supplier<T> create) {
        return retryOnPortConflict(new Log() {
            @Override
            public int mark() {
                return logMark();
            }

            @Override
            public String since(int mark) {
                return logSince(mark);
            }
        }, create, message -> System.err.println("[" + caller.getSimpleName() + "] " + message));
    }

    /**
     * Runs {@code create}, a create on the server whose log is {@code log}. The server answers a sidecar that failed
     * to start with a bare HTTP 500 and logs the Docker error, so a 500 fails with the server's log of this create
     * attached. One cause is the server's, not the tier's: it picks the sidecar's host ports by probing them inside its
     * own container, which does not see what the host already publishes (another sandbox, the other fork's server),
     * and on the egress path it does not retry a port conflict as it does without a policy. A 500 whose log shows that
     * conflict is retried once, said to {@code retrying} (unprefixed: the caller names itself); so is a 500 whose own
     * body shows it, as the main container's start failure does (its server retries a conflict itself, but that one
     * reached CI on PR #55); any other 500, or a second conflict, fails.
     *
     * @return what {@code create} returned
     */
    static <T> T retryOnPortConflict(Log log, Supplier<T> create, Consumer<String> retrying) {
        for (int attempt = 1;; attempt++) {
            final int mark = log.mark();
            try {
                return create.get();
            } catch (SandboxProviderException e) {
                if (e.getMessage() == null || !e.getMessage().contains("HTTP 500")) {
                    throw e;
                }
                final String since = log.since(mark);
                if (attempt > 1 || !isPortConflict(since) && !isPortConflict(e.getMessage())) {
                    e.addSuppressed(
                            new AssertionError("the server's log of this create (attempt " + attempt + "):\n" + since));
                    throw e;
                }
                retrying.accept("create answered HTTP 500 on a host-port conflict of the server's own allocation; "
                        + "retrying once. The server's log of it:\n" + since);
            }
        }
    }

    /**
     * @return whether {@code log} shows Docker refusing a host port the server picked: the messages that the server's
     *         own port-conflict retry, missing on its egress path, recognizes ({@code is_port_publish_error}), and the
     *         head of Docker 28's wording, {@code failed to bind host port …}, which is all a truncated error body
     *         keeps of it
     */
    static boolean isPortConflict(String log) {
        if (log == null) {
            return false;
        }
        final String lower = log.toLowerCase(Locale.ROOT);
        return lower.contains("port is already allocated") || lower.contains("ports are not available")
                || lower.contains("address already in use") || lower.contains("failed to bind host port");
    }

    /** The label the server puts on a sandbox's Docker containers, its own and the egress sidecar: the sandbox's id. */
    static final String SANDBOX_ID_LABEL = "opensandbox.io/id";
    /** The label the server puts on an egress sidecar alone: the id of the sandbox it serves. */
    static final String SIDECAR_LABEL = "opensandbox.io/egress-sidecar-for";

    /**
     * Takes swap away from a sandbox: its container's Docker memory-swap is set to its memory limit, so the cgroup's
     * {@code memory.swap.max} reads 0. The server sets only the memory limit, and Docker's default memory-swap then
     * lets the cgroup swap as much again, which on a runner with a slow swap disk keeps an out-of-memory command in
     * reclaim, with execd beside it, instead of reaching the OOM killer (CI, PR #55). The container is looked up by the
     * server's {@link #SANDBOX_ID_LABEL} and its name ({@link #sandboxContainer}), not assumed to be named
     * {@code sandbox-{id}}.
     *
     * @param ref
     *            the sandbox, on this server's Docker runtime
     * @param memoryBytes
     *            its memory limit, which Docker's update needs alongside the memory-swap
     * @return whether swap was taken away: false for a server started elsewhere, whose Docker need not be this JVM's
     * @throws AssertionError
     *             when no single container of this Docker is the sandbox's, naming what was found
     */
    boolean takeSwapAway(ProviderSandboxRef ref, long memoryBytes) {
        if (container == null) {
            return false;
        }
        final DockerClient docker = DockerClientFactory.instance().client();
        final Map<String, DockerContainer> candidates = new LinkedHashMap<>();
        docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(SANDBOX_ID_LABEL, ref.sandboxId())).exec()
                .forEach(c -> candidates.put(c.getId(), DockerContainer.of(c)));
        docker.listContainersCmd().withShowAll(true).withNameFilter(List.of(containerName(ref))).exec()
                .forEach(c -> candidates.putIfAbsent(c.getId(), DockerContainer.of(c)));
        docker.updateContainerCmd(sandboxContainer(ref, List.copyOf(candidates.values()))).withMemory(memoryBytes)
                .withMemorySwap(memoryBytes).exec();
        return true;
    }

    /** A Docker container as {@link #sandboxContainer} weighs it: its id, its names (without the slash), its labels. */
    record DockerContainer(String id, List<String> names, Map<String, String> labels) {

        static DockerContainer of(Container container) {
            return new DockerContainer(container.getId(), container.getNames() == null
                    ? List.of()
                    : Arrays.stream(container.getNames()).map(n -> n.startsWith("/") ? n.substring(1) : n).toList(),
                    container.getLabels() == null ? Map.of() : container.getLabels());
        }
    }

    /**
     * @param ref
     *            a sandbox of this server
     * @param candidates
     *            the containers of this Docker labelled with its id ({@link #SANDBOX_ID_LABEL}) or named after it
     * @return the id of the sandbox's own container: of the candidates that are not an egress sidecar
     *         ({@link #SIDECAR_LABEL}), the one labelled with the sandbox's id, or else the one the server names
     *         {@code sandbox-{id}} ({@link #containerName})
     * @throws AssertionError
     *             when neither picks out exactly one, naming the candidates: the server labels or names its containers
     *             otherwise than this tier expects
     */
    static String sandboxContainer(ProviderSandboxRef ref, List<DockerContainer> candidates) {
        final List<DockerContainer> own = candidates.stream().filter(c -> !c.labels().containsKey(SIDECAR_LABEL))
                .toList();
        final List<DockerContainer> labelled = own.stream()
                .filter(c -> ref.sandboxId().equals(c.labels().get(SANDBOX_ID_LABEL))).toList();
        if (labelled.size() == 1) {
            return labelled.get(0).id();
        }
        final List<DockerContainer> named = own.stream().filter(c -> c.names().contains(containerName(ref))).toList();
        if (named.size() == 1) {
            return named.get(0).id();
        }
        throw new AssertionError("no single Docker container of sandbox " + ref.sandboxId()
                + " to take swap away from: " + labelled.size() + " labelled " + SANDBOX_ID_LABEL + "="
                + ref.sandboxId() + " and " + named.size() + " named " + containerName(ref)
                + ", egress sidecars left out; the server labels or names its containers otherwise. Candidates: "
                + candidates.stream().map(c -> c.names() + " " + c.labels()).toList());
    }

    /** @return the name the server gives the Docker container of {@code ref} ({@code sandbox-{id}}) */
    static String containerName(ProviderSandboxRef ref) {
        return "sandbox-" + ref.sandboxId();
    }

    /** @return a configuration for this server: Docker runtime, reached through the server proxy */
    OpenSandboxProviderConfig.Builder config() {
        return OpenSandboxProviderConfig.builder().endpoint(endpoint).apiKey(() -> apiKey)
                .runtime(OpenSandboxProviderConfig.Runtime.DOCKER).maxExpiry(Duration.ofHours(2)).useServerProxy(true)
                .egressEnforcement(OpenSandboxProviderConfig.EgressEnforcement.DNS_NFT);
    }

    /** Deletes whatever this run's deployment still has, then stops the container. */
    private void cleanUp() {
        try (OpenSandboxProvider provider = new OpenSandboxProvider(config().build())) {
            for (ProviderSandbox sandbox : provider.list(Map.of(SandboxLabels.DEPLOYMENT, DEPLOYMENT))) {
                provider.destroy(sandbox.ref());
            }
        } catch (RuntimeException e) {
            // best effort: the sandboxes expire on their own
        }
        if (container != null) {
            container.stop();
        }
    }
}
