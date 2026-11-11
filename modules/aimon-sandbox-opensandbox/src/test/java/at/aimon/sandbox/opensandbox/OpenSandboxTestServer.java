package at.aimon.sandbox.opensandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Frame;

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

    /** {@code opensandbox/server:release-1.2.0}, the release the provider requires, by digest. */
    static final String SERVER_IMAGE = env("OPENSANDBOX_TEST_SERVER_IMAGE",
            "opensandbox/server:release-1.2.0@sha256:25bebdcf1cf7724eb923faecbdbbf82d4038c6ab35c736151574726d14006f30");
    /** The execd of the same release (by digest). */
    static final String EXECD_IMAGE = env("OPENSANDBOX_TEST_EXECD_IMAGE",
            "opensandbox/execd:release-1.2.0@sha256:c75836c46ef4302afccec182d5d7339eb2f905f51e3d73b7aa17bb76c832526c");
    /** The egress sidecar of the same release (by digest). */
    static final String EGRESS_IMAGE = env("OPENSANDBOX_TEST_EGRESS_IMAGE",
            "opensandbox/egress:release-1.2.0@sha256:a3d30246e557525f3fa44d16226258268cea175532e652822bd1eb4e83e0f54d");

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

    /**
     * @return every host port this Docker publishes, one line each, by port: the container (its name, image and the
     *         server's sandbox labels) and its port, a port published more than once marked; empty for a server
     *         started elsewhere. What a create refused as unauthorized attaches ({@link #retryOnPortConflict})
     */
    String publishedPorts() {
        if (container == null) {
            return "";
        }
        final List<PublishedPort> ports = new ArrayList<>();
        for (Container c : DockerClientFactory.instance().client().listContainersCmd().exec()) {
            final DockerContainer named = DockerContainer.of(c);
            final Map<String, String> labels = new TreeMap<>(named.labels());
            labels.keySet().retainAll(List.of(SANDBOX_ID_LABEL, SIDECAR_LABEL));
            for (ContainerPort port : c.getPorts() == null ? new ContainerPort[0] : c.getPorts()) {
                if (port.getPublicPort() != null) {
                    ports.add(new PublishedPort(port.getPublicPort(), port.getIp(), String.join(",", named.names())
                            + " (" + c.getImage() + ") " + labels + " :" + port.getPrivatePort()));
                }
            }
        }
        return publishedPorts(ports);
    }

    /** A host port {@link #publishedPorts()} lists: the port, the address it is bound on, and who publishes it. */
    record PublishedPort(int port, String ip, String container) {
    }

    /** @return {@code ports} as {@link #publishedPorts()} lists them */
    static String publishedPorts(List<PublishedPort> ports) {
        final Map<Integer, Long> owners = ports.stream()
                .collect(Collectors.groupingBy(PublishedPort::port, Collectors.mapping(PublishedPort::container,
                        Collectors.collectingAndThen(Collectors.toSet(), set -> (long) set.size()))));
        return ports.stream().sorted(Comparator.comparingInt(PublishedPort::port))
                .map(p -> p.port() + " " + p.ip() + " <- " + p.container()
                        + (owners.get(p.port()) > 1 ? "  [published by more than one container]" : "") + "\n")
                .collect(Collectors.joining());
    }

    /** What a create's retry reads of a server's log: {@link #logMark} and {@link #logSince}. */
    interface Log {
        int mark();

        String since(int mark);

        /** @return {@link #publishedPorts()}, by default nothing */
        default String publishedPorts() {
            return "";
        }
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

            @Override
            public String publishedPorts() {
                return OpenSandboxTestServer.this.publishedPorts();
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
     * reached CI on PR #55); any other 500, or a second conflict, fails. A 401 or 403 fails at once, with the server's
     * log and the host ports Docker publishes ({@link Log#publishedPorts}) attached: an egress create once met one
     * while other runs shared the Docker host (the provider's {@code awaitEgressSidecar}), and the server's call to
     * the sidecar's host port is the one call that carries no api-key, so whatever owns that port is the evidence.
     *
     * @return what {@code create} returned
     */
    static <T> T retryOnPortConflict(Log log, Supplier<T> create, Consumer<String> retrying) {
        for (int attempt = 1;; attempt++) {
            final int mark = log.mark();
            try {
                return create.get();
            } catch (SandboxProviderException e) {
                if (e.getMessage() != null
                        && (e.getMessage().contains("HTTP 401") || e.getMessage().contains("HTTP 403"))) {
                    e.addSuppressed(new AssertionError("the server's log of this create (attempt " + attempt + "):\n"
                            + log.since(mark) + "the host ports Docker publishes now:\n" + log.publishedPorts()));
                    throw e;
                }
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
        docker.updateContainerCmd(sandboxContainer(docker, ref)).withMemory(memoryBytes).withMemorySwap(memoryBytes)
                .exec();
        return true;
    }

    /**
     * @return the id of the sandbox's own container on this Docker, as
     *         {@link #sandboxContainer(ProviderSandboxRef, List)} picks it
     */
    private static String sandboxContainer(DockerClient docker, ProviderSandboxRef ref) {
        final Map<String, DockerContainer> candidates = new LinkedHashMap<>();
        docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(SANDBOX_ID_LABEL, ref.sandboxId())).exec()
                .forEach(c -> candidates.put(c.getId(), DockerContainer.of(c)));
        docker.listContainersCmd().withShowAll(true).withNameFilter(List.of(containerName(ref))).exec()
                .forEach(c -> candidates.putIfAbsent(c.getId(), DockerContainer.of(c)));
        return sandboxContainer(ref, List.copyOf(candidates.values()));
    }

    /** How long {@link #hostView} waits for its {@code docker exec}: a cgroup stuck at its limit can stall it too. */
    private static final Duration HOST_VIEW_EXEC = Duration.ofSeconds(30);

    /**
     * The shell command of {@link #hostView}, run by {@code docker exec} as root (POSIX sh, coreutils or busybox): the
     * cgroup's memory counters, its memory pressure, the reclaim lines of {@code memory.stat}, then one line per
     * process with its RSS, OOM score and state.
     */
    static final String HOST_VIEW_COMMAND = "cd /sys/fs/cgroup 2>/dev/null; "
            + "for f in memory.current memory.events memory.pressure; do "
            + "printf '%s: %s\\n' \"$f\" \"$(tr '\\n' ' ' < \"$f\" 2>&1)\"; done; "
            + "printf 'memory.stat: %s\\n' \"$(grep -E '^(anon|file|file_mapped|shmem|kernel|sock|pgmajfault|pgscan|pgsteal"
            + "|workingset_refault_file|workingset_activate_file) ' memory.stat 2>&1 | tr '\\n' ' ')\"; "
            + "for p in /proc/[0-9]*; do [ -r \"$p/status\" ] || continue; "
            + "printf 'pid %s %s rss=%s oom_score=%s adj=%s cmd=%s\\n' \"${p#/proc/}\" "
            + "\"$(sed -n 's/^State:[[:space:]]*//p' \"$p/status\" 2>/dev/null | cut -c1-12)\" "
            + "\"$(sed -n 's/^VmRSS:[[:space:]]*//p' \"$p/status\" 2>/dev/null)\" \"$(cat \"$p/oom_score\" 2>/dev/null)\" "
            + "\"$(cat \"$p/oom_score_adj\" 2>/dev/null)\" \"$(tr '\\0' ' ' < \"$p/cmdline\" 2>/dev/null | cut -c1-120)\"; done";

    /**
     * The sandbox as Docker sees it, without execd: a test whose command never came back cannot ask execd, which may be
     * what stopped (CI, the §12.2 out-of-memory test). The container's state (running, OOM-killed, exit code), then,
     * where it still runs, {@link #HOST_VIEW_COMMAND}'s output through {@code docker exec}, waited for at most
     * {@link #HOST_VIEW_EXEC}.
     *
     * @param ref
     *            the sandbox, on this server's Docker runtime
     * @return what was seen, one item per line; for a server started elsewhere, or where Docker fails, why nothing was.
     *         Never throws: it only feeds a failure message
     */
    String hostView(ProviderSandboxRef ref) {
        if (container == null) {
            return "(no host view: a server started elsewhere, whose Docker need not be this JVM's)\n";
        }
        try {
            final DockerClient docker = DockerClientFactory.instance().client();
            final String id = sandboxContainer(docker, ref);
            final InspectContainerResponse.ContainerState state = docker.inspectContainerCmd(id).exec().getState();
            final StringBuilder view = new StringBuilder(String.format(
                    "container %s: status=%s running=%s oomKilled=%s exitCode=%s startedAt=%s finishedAt=%s%n",
                    id.substring(0, Math.min(12, id.length())), state.getStatus(), state.getRunning(),
                    state.getOOMKilled(), state.getExitCodeLong(), state.getStartedAt(), state.getFinishedAt()));
            if (Boolean.TRUE.equals(state.getRunning())) {
                view.append(exec(docker, id, HOST_VIEW_COMMAND));
            }
            return view.toString();
        } catch (RuntimeException e) {
            return "(no host view: " + e + ")\n";
        }
    }

    /** @return {@code command}'s stdout and stderr through {@code docker exec} as root, or why it did not finish */
    private static String exec(DockerClient docker, String containerId, String command) {
        final String execId = docker.execCreateCmd(containerId).withUser("root").withAttachStdout(true)
                .withAttachStderr(true).withCmd("sh", "-c", command).exec().getId();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                synchronized (output) {
                    output.writeBytes(frame.getPayload());
                }
            }
        };
        boolean finished;
        try {
            finished = docker.execStartCmd(execId).exec(callback).awaitCompletion(HOST_VIEW_EXEC.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        synchronized (output) {
            return output.toString(StandardCharsets.UTF_8)
                    + (finished ? "" : "(docker exec did not finish within " + HOST_VIEW_EXEC + ")\n");
        }
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
     *         ({@link #SIDECAR_LABEL}), the one labelled with the sandbox's id, or else, where none is (an older
     *         server's labels), the one the server names {@code sandbox-{id}} ({@link #containerName})
     * @throws AssertionError
     *             when neither picks out exactly one, naming the candidates: the server labels or names its containers
     *             otherwise than this tier expects. Two or more labelled ones fail too, whatever their names: the name
     *             would pick one of them without saying why the other is there
     */
    static String sandboxContainer(ProviderSandboxRef ref, List<DockerContainer> candidates) {
        final List<DockerContainer> own = candidates.stream().filter(c -> !c.labels().containsKey(SIDECAR_LABEL))
                .toList();
        final List<DockerContainer> labelled = own.stream()
                .filter(c -> ref.sandboxId().equals(c.labels().get(SANDBOX_ID_LABEL))).toList();
        if (labelled.size() == 1) {
            return labelled.get(0).id();
        }
        if (labelled.size() > 1) {
            throw new AssertionError(labelled.size() + " Docker containers of sandbox " + ref.sandboxId()
                    + " to take swap away from are labelled " + SANDBOX_ID_LABEL + "=" + ref.sandboxId()
                    + " and none is an egress sidecar; the server labels its containers otherwise. Labelled: "
                    + labelled.stream().map(c -> c.names() + " " + c.labels()).toList());
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
