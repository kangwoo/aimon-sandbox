package at.aimon.sandbox.docker;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;

import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.backend.SandboxExpiryStore;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.model.SandboxUser;

/**
 * Docker-based implementation of {@link SandboxBackend}.
 *
 * <p>
 * Uses docker-java to manage container lifecycles, execute commands, and transfer artifacts. Containers are labelled
 * with {@code aimon.at/role=sandbox} for identification and cleanup.
 *
 * <p>
 * TTL tracking is delegated to a {@link SandboxExpiryStore} so that the storage strategy can be swapped for
 * multi-instance deployments. Docker labels carry the initial expiry as a fallback for process restarts.
 */
public class DockerSandboxBackend implements SandboxBackend, Closeable {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxBackend.class);

    static final String CONTAINER_NAME_PREFIX = "sandbox-";
    static final String LABEL_ROLE = "aimon.at/role";
    static final String LABEL_ROLE_VALUE = "sandbox";
    static final String LABEL_IDENTIFIER = "aimon.at/identifier";
    static final String LABEL_EXPIRES_AT = "aimon.at/expires-at";
    static final String ARTIFACTS_PATH = "/artifacts";
    static final String OUTPUT_TRUNCATED_WARNING = "\n[WARNING] Output truncated: exceeded maxOutputBytes limit";

    private final DockerClient dockerClient;
    private final SandboxConfig sandboxConfig;
    private final DockerSandboxConfig dockerConfig;
    private final SandboxExpiryStore expiryStore;

    /**
     * Creates a new DockerSandboxBackend.
     *
     * @param dockerClient
     *            the Docker client (not null)
     * @param sandboxConfig
     *            the sandbox configuration (not null)
     * @param dockerConfig
     *            the Docker-specific configuration (not null)
     * @param expiryStore
     *            the expiry store for TTL tracking (not null)
     */
    public DockerSandboxBackend(DockerClient dockerClient, SandboxConfig sandboxConfig,
            DockerSandboxConfig dockerConfig, SandboxExpiryStore expiryStore) {
        this.dockerClient = Objects.requireNonNull(dockerClient, "DockerClient cannot be null");
        this.sandboxConfig = Objects.requireNonNull(sandboxConfig, "SandboxConfig cannot be null");
        this.dockerConfig = Objects.requireNonNull(dockerConfig, "DockerSandboxConfig cannot be null");
        this.expiryStore = Objects.requireNonNull(expiryStore, "SandboxExpiryStore cannot be null");
    }

    @Override
    public Sandbox ensure(String identifier, int ttlSeconds) throws IOException {
        Objects.requireNonNull(identifier, "Identifier cannot be null");
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be > 0, got: " + ttlSeconds);
        }

        String containerName = CONTAINER_NAME_PREFIX + identifier.toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        try {
            CreateContainerResponse response = createContainer(containerName, identifier, expiresAt);
            String containerId = response.getId();
            log.debug("Created container {} for identifier {}", containerId, identifier);

            dockerClient.startContainerCmd(containerId).exec();
            log.debug("Started container {}", containerId);

            initializeContainer(containerId);

            expiryStore.put(identifier, expiresAt);

            return buildSandbox(identifier, containerId, containerName, sandboxConfig.getDefaultImage(), now,
                    expiresAt);

        } catch (ConflictException e) {
            log.debug("Container {} already exists, reusing", containerName);
            return reuseExistingContainer(identifier, containerName, expiresAt);
        } catch (NotFoundException | IllegalStateException e) {
            throw new IOException("Failed to ensure sandbox: " + identifier, e);
        }
    }

    @Override
    public void delete(String identifier) throws IOException {
        Objects.requireNonNull(identifier, "Identifier cannot be null");

        String containerName = CONTAINER_NAME_PREFIX + identifier.toLowerCase(Locale.ROOT);

        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
            log.debug("Deleted container {}", containerName);
        } catch (NotFoundException e) {
            log.debug("Container {} not found, nothing to delete", containerName);
        }

        expiryStore.remove(identifier);
    }

    @Override
    public Sandbox restart(String identifier, int ttlSeconds) throws IOException {
        delete(identifier);
        return ensure(identifier, ttlSeconds);
    }

    @Override
    public ExecResult exec(String sandboxId, ExecParams params) throws IOException {
        Objects.requireNonNull(sandboxId, "SandboxId cannot be null");
        Objects.requireNonNull(params, "ExecParams cannot be null");

        try {
            String[] cmd = buildExecCommand(params);
            List<String> envList = buildEnvList(params.getEnv());
            String user = resolveUser(params.getAsUser());

            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(sandboxId).withCmd(cmd).withEnv(envList)
                    .withUser(user).withAttachStdout(true).withAttachStderr(true).exec();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            int maxOutputBytes = params.getMaxOutputBytes();
            AtomicBoolean truncated = new AtomicBoolean(false);
            Object outputLock = new Object();

            ResultCallback.Adapter<Frame> callback = dockerClient.execStartCmd(execCreate.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {

                        @Override
                        public void onNext(Frame frame) {
                            if (frame != null && !truncated.get()) {
                                synchronized (outputLock) {
                                    String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
                                    int currentSize = stdout.length() + stderr.length();
                                    if (currentSize >= maxOutputBytes) {
                                        truncated.set(true);
                                        return;
                                    }
                                    switch (frame.getStreamType()) {
                                        case STDOUT :
                                            stdout.append(payload);
                                            break;
                                        case STDERR :
                                            stderr.append(payload);
                                            break;
                                        default :
                                            break;
                                    }
                                    if (stdout.length() + stderr.length() >= maxOutputBytes) {
                                        truncated.set(true);
                                    }
                                }
                            }
                        }
                    });

            boolean completed = callback.awaitCompletion(params.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new IOException("Command timed out after " + params.getTimeoutMs() + "ms");
            }

            Long exitCodeLong = dockerClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
            int exitCode = exitCodeLong != null ? exitCodeLong.intValue() : -1;

            if (truncated.get()) {
                stderr.append(OUTPUT_TRUNCATED_WARNING);
            }

            return ExecResult.builder().exitCode(exitCode).stdout(stdout.toString()).stderr(stderr.toString()).build();

        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        } catch (Exception e) {
            throw new IOException("Failed to execute command in sandbox: " + sandboxId, e);
        }
    }

    @Override
    public InputStream copyArtifacts(String sandboxId) throws IOException {
        Objects.requireNonNull(sandboxId, "SandboxId cannot be null");

        try {
            return dockerClient.copyArchiveFromContainerCmd(sandboxId, ARTIFACTS_PATH).exec();
        } catch (Exception e) {
            throw new IOException("Failed to copy artifacts from sandbox: " + sandboxId, e);
        }
    }

    @Override
    public void copyToSandbox(String sandboxId, InputStream tarStream, String destPath) throws IOException {
        Objects.requireNonNull(sandboxId, "SandboxId cannot be null");
        Objects.requireNonNull(tarStream, "TarStream cannot be null");
        Objects.requireNonNull(destPath, "DestPath cannot be null");

        try {
            dockerClient.copyArchiveToContainerCmd(sandboxId).withTarInputStream(tarStream).withRemotePath(destPath)
                    .exec();
        } catch (Exception e) {
            throw new IOException("Failed to copy archive to sandbox: " + sandboxId, e);
        }
    }

    @Override
    public int count() throws IOException {
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of(LABEL_ROLE, LABEL_ROLE_VALUE)).exec();
            return containers.size();
        } catch (Exception e) {
            throw new IOException("Failed to count sandboxes", e);
        }
    }

    @Override
    public int reapExpired() throws IOException {
        try {
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(LABEL_ROLE, LABEL_ROLE_VALUE)).exec();

            Instant now = Instant.now();
            int removed = 0;

            for (Container container : containers) {
                String identifier = container.getLabels().get(LABEL_IDENTIFIER);
                if (identifier == null) {
                    continue;
                }

                Instant expiresAt = resolveExpiry(identifier, container.getLabels());
                if (expiresAt != null && now.isAfter(expiresAt)) {
                    try {
                        dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
                        expiryStore.remove(identifier);
                        removed++;
                        log.debug("Reaped expired sandbox: {}", identifier);
                    } catch (Exception e) {
                        log.warn("Failed to reap sandbox {}: {}", identifier, e.getMessage());
                    }
                }
            }

            return removed;
        } catch (Exception e) {
            throw new IOException("Failed to reap expired sandboxes", e);
        }
    }

    @Override
    public void close() throws IOException {
        dockerClient.close();
    }

    // --- Private helpers ---

    private CreateContainerResponse createContainer(String containerName, String identifier, Instant expiresAt) {
        Map<String, String> labels = Map.of(LABEL_ROLE, LABEL_ROLE_VALUE, LABEL_IDENTIFIER, identifier,
                LABEL_EXPIRES_AT, DateTimeFormatter.ISO_INSTANT.format(expiresAt));

        HostConfig hostConfig = HostConfig.newHostConfig().withMemory(dockerConfig.getMemoryLimit())
                .withCpuCount(dockerConfig.getCpuCount()).withPidsLimit(dockerConfig.getPidsLimit())
                .withNetworkMode(dockerConfig.getNetworkMode())
                .withCapDrop(toCapabilities(dockerConfig.getDropCapabilities()))
                .withCapAdd(toCapabilities(dockerConfig.getAddCapabilities())).withSecurityOpts(buildSecurityOpts());

        if (!dockerConfig.getTmpfsBinds().isEmpty()) {
            hostConfig = hostConfig.withTmpFs(dockerConfig.getTmpfsBinds());
        }

        if (dockerConfig.isReadonlyRootfs()) {
            hostConfig = hostConfig.withReadonlyRootfs(true);
        }

        return dockerClient.createContainerCmd(sandboxConfig.getDefaultImage()).withName(containerName)
                .withLabels(labels).withHostConfig(hostConfig).withCmd("sleep", "infinity").exec();
    }

    private void initializeContainer(String containerId) throws IOException {
        String sandboxUser = dockerConfig.getSandboxUser();
        int uid = dockerConfig.getSandboxUid();

        String initScript = String.format(
                "id -u %s >/dev/null 2>&1 || useradd -m -u %d -s /bin/sh %s; " + "mkdir -p /workspace %s %s/logs; "
                        + "chown %s:%s /workspace %s %s/logs",
                sandboxUser, uid, sandboxUser, ARTIFACTS_PATH, ARTIFACTS_PATH, sandboxUser, sandboxUser, ARTIFACTS_PATH,
                ARTIFACTS_PATH);

        ExecParams initParams = ExecParams.builder().command(initScript).asUser(SandboxUser.ROOT).timeoutMs(30_000)
                .build();

        ExecResult result = exec(containerId, initParams);
        if (result.getExitCode() != 0) {
            log.error("Container initialization failed (exit code {}): {}", result.getExitCode(), result.getStderr());
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception cleanup) {
                log.warn("Failed to clean up container {} after init failure: {}", containerId, cleanup.getMessage());
            }
            throw new IOException(
                    "Container initialization failed (exit code " + result.getExitCode() + "): " + result.getStderr());
        }
    }

    private Sandbox reuseExistingContainer(String identifier, String containerName, Instant expiresAt)
            throws IOException {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerName).exec();

            Boolean running = inspect.getState().getRunning();
            if (running == null || !running) {
                log.debug("Restarting stopped container {}", containerName);
                dockerClient.startContainerCmd(containerName).exec();
            }

            expiryStore.put(identifier, expiresAt);

            String containerId = inspect.getId();
            String image = inspect.getConfig() != null
                    ? inspect.getConfig().getImage()
                    : sandboxConfig.getDefaultImage();
            Instant createdAt = inspect.getCreated() != null ? Instant.parse(inspect.getCreated()) : Instant.now();

            return buildSandbox(identifier, containerId, containerName, image, createdAt, expiresAt);
        } catch (Exception e) {
            throw new IOException("Failed to reuse existing container: " + containerName, e);
        }
    }

    private Sandbox buildSandbox(String identifier, String sandboxId, String name, String image, Instant createdAt,
            Instant expiresAt) {
        return Sandbox.builder().identifier(identifier).sandboxId(sandboxId).name(name).image(image)
                .createdAt(createdAt).expiresAt(expiresAt).build();
    }

    private String[] buildExecCommand(ExecParams params) {
        String cwd = params.getCwd();
        String command = params.getCommand();

        if (cwd != null && !cwd.isEmpty()) {
            String escapedCwd = cwd.replace("'", "'\\''");
            return new String[]{"sh", "-c", "cd '" + escapedCwd + "' && " + command};
        }
        return new String[]{"sh", "-c", command};
    }

    private List<String> buildEnvList(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return List.of();
        }

        List<String> envList = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getKey().contains("=")) {
                throw new IllegalArgumentException("Invalid environment variable name: " + entry.getKey());
            }
            envList.add(entry.getKey() + "=" + entry.getValue());
        }
        return envList;
    }

    private String resolveUser(SandboxUser user) {
        if (user == null) {
            return dockerConfig.getSandboxUser();
        }
        switch (user) {
            case ROOT :
                return "root";
            case SANDBOX :
                return dockerConfig.getSandboxUser();
            default :
                throw new IllegalArgumentException("Unknown SandboxUser: " + user);
        }
    }

    private Capability[] toCapabilities(List<String> names) {
        if (names == null || names.isEmpty()) {
            return new Capability[0];
        }
        return names.stream().map(name -> {
            try {
                return Capability.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown Linux capability: " + name, e);
            }
        }).toArray(Capability[]::new);
    }

    private List<String> buildSecurityOpts() {
        List<String> opts = new ArrayList<>();
        if (dockerConfig.isNoNewPrivileges()) {
            opts.add("no-new-privileges:true");
        }
        return opts;
    }

    private Instant resolveExpiry(String identifier, Map<String, String> labels) {
        Instant tracked = expiryStore.get(identifier).orElse(null);
        if (tracked != null) {
            return tracked;
        }

        String labelValue = labels.get(LABEL_EXPIRES_AT);
        if (labelValue != null) {
            try {
                return Instant.parse(labelValue);
            } catch (Exception e) {
                log.warn("Failed to parse expires-at label for {}: {}, treating as expired", identifier, labelValue);
                return Instant.EPOCH;
            }
        }

        return null;
    }
}
