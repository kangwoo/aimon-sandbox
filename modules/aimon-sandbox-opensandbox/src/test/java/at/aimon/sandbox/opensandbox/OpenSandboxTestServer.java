package at.aimon.sandbox.opensandbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.SandboxLabels;

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

    private static final String API_KEY = "aimon-test-" + UUID.randomUUID();
    private static final Map<String, OpenSandboxTestServer> SERVERS = new ConcurrentHashMap<>();
    private static volatile String sandboxImage;

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

    URI endpoint() {
        return endpoint;
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
