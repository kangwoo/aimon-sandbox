package at.aimon.sandbox.kubernetes;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.backend.SandboxExpiryStore;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.model.SandboxUser;
import io.kubernetes.client.Exec;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Capabilities;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1SecurityContext;

/**
 * Kubernetes-based implementation of {@link SandboxBackend}.
 *
 * <p>
 * Uses the Kubernetes Java client to manage pod lifecycles, execute commands via WebSocket exec, and transfer artifacts
 * using tar piped through exec. Pods are labelled with {@code aimon.at/role=sandbox} for identification and cleanup.
 *
 * <p>
 * TTL tracking is delegated to a {@link SandboxExpiryStore} so that the storage strategy can be swapped for
 * multi-instance deployments. Pod annotations carry the initial expiry as a fallback for process restarts (annotations
 * are used instead of labels because ISO-8601 timestamps contain characters invalid in label values).
 *
 * <p>
 * Since Kubernetes exec does not support {@code --user} or environment variable injection, user switching is performed
 * via {@code su} and environment variables are set via {@code export} within the shell command.
 */
public class KubernetesSandboxBackend implements SandboxBackend, Closeable {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxBackend.class);

    static final String POD_NAME_PREFIX = "sandbox-";
    static final String LABEL_ROLE = "aimon.at/role";
    static final String LABEL_ROLE_VALUE = "sandbox";
    static final String LABEL_IDENTIFIER = "aimon.at/identifier";
    static final String ANNOTATION_EXPIRES_AT = "aimon.at/expires-at";
    static final String ARTIFACTS_PATH = "/artifacts";
    static final String CONTAINER_NAME = "sandbox";
    static final String OUTPUT_TRUNCATED_WARNING = "\n[WARNING] Output truncated: exceeded maxOutputBytes limit";

    private static final long POLL_INTERVAL_MS = 500;
    private static final long COPY_TIMEOUT_SECONDS = 30;
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final CoreV1Api coreV1Api;
    private final Exec kubeExec;
    private final SandboxConfig sandboxConfig;
    private final KubernetesSandboxConfig kubeConfig;
    private final SandboxExpiryStore expiryStore;

    /**
     * Creates a new KubernetesSandboxBackend.
     *
     * @param apiClient
     *            the Kubernetes API client (not null)
     * @param sandboxConfig
     *            the sandbox configuration (not null)
     * @param kubeConfig
     *            the Kubernetes-specific configuration (not null)
     * @param expiryStore
     *            the expiry store for TTL tracking (not null)
     */
    public KubernetesSandboxBackend(ApiClient apiClient, SandboxConfig sandboxConfig,
            KubernetesSandboxConfig kubeConfig, SandboxExpiryStore expiryStore) {
        this(new CoreV1Api(Objects.requireNonNull(apiClient, "ApiClient cannot be null")), new Exec(apiClient),
                sandboxConfig, kubeConfig, expiryStore);
    }

    /**
     * Package-private constructor for testing with injected dependencies.
     */
    KubernetesSandboxBackend(CoreV1Api coreV1Api, Exec kubeExec, SandboxConfig sandboxConfig,
            KubernetesSandboxConfig kubeConfig, SandboxExpiryStore expiryStore) {
        this.coreV1Api = Objects.requireNonNull(coreV1Api, "CoreV1Api cannot be null");
        this.kubeExec = Objects.requireNonNull(kubeExec, "Exec cannot be null");
        this.sandboxConfig = Objects.requireNonNull(sandboxConfig, "SandboxConfig cannot be null");
        this.kubeConfig = Objects.requireNonNull(kubeConfig, "KubernetesSandboxConfig cannot be null");
        this.expiryStore = Objects.requireNonNull(expiryStore, "SandboxExpiryStore cannot be null");
    }

    @Override
    public Sandbox ensure(String identifier, int ttlSeconds) throws IOException {
        Objects.requireNonNull(identifier, "Identifier cannot be null");

        String podName = POD_NAME_PREFIX + identifier.toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        try {
            V1Pod pod = createPod(podName, identifier, expiresAt);
            log.debug("Created pod {} for identifier {}", podName, identifier);

            waitForPodReady(podName);

            initializePod(podName);

            expiryStore.put(identifier, expiresAt);

            return buildSandbox(identifier, podName, podName, sandboxConfig.getDefaultImage(), now, expiresAt);

        } catch (ApiException e) {
            if (e.getCode() == 409) {
                log.debug("Pod {} already exists, reusing", podName);
                return reuseExistingPod(identifier, podName, expiresAt);
            }
            throw new IOException("Failed to ensure sandbox: " + identifier, e);
        }
    }

    @Override
    public void delete(String identifier) throws IOException {
        Objects.requireNonNull(identifier, "Identifier cannot be null");

        String podName = POD_NAME_PREFIX + identifier.toLowerCase(Locale.ROOT);

        try {
            coreV1Api.deleteNamespacedPod(podName, kubeConfig.getNamespace()).execute();
            log.debug("Deleted pod {}", podName);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw new IOException("Failed to delete pod: " + podName, e);
            }
            log.debug("Pod {} not found, nothing to delete", podName);
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
            String shellCommand = buildShellCommand(params);

            Process process = kubeExec.exec(kubeConfig.getNamespace(), sandboxId,
                    new String[]{"sh", "-c", shellCommand}, CONTAINER_NAME, false, false);

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            int maxOutputBytes = params.getMaxOutputBytes();
            boolean[] truncated = new boolean[2];

            Thread stdoutThread = new Thread(
                    () -> truncated[0] = readStream(process.getInputStream(), stdout, maxOutputBytes), "exec-stdout");
            Thread stderrThread = new Thread(
                    () -> truncated[1] = readStream(process.getErrorStream(), stderr, maxOutputBytes), "exec-stderr");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);

            stdoutThread.start();
            stderrThread.start();

            boolean completed = process.waitFor(params.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroy();
                stdoutThread.interrupt();
                stderrThread.interrupt();
                throw new IOException("Command timed out after " + params.getTimeoutMs() + "ms");
            }

            stdoutThread.join(5000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();

            if (truncated[0] || truncated[1]) {
                stderr.append(OUTPUT_TRUNCATED_WARNING);
            }

            return ExecResult.builder().exitCode(exitCode).stdout(stdout.toString()).stderr(stderr.toString()).build();

        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        } catch (ApiException e) {
            throw new IOException("Failed to execute command in sandbox: " + sandboxId, e);
        }
    }

    @Override
    public InputStream copyArtifacts(String sandboxId) throws IOException {
        Objects.requireNonNull(sandboxId, "SandboxId cannot be null");

        try {
            Process process = kubeExec.exec(kubeConfig.getNamespace(), sandboxId,
                    new String[]{"tar", "cf", "-", "-C", ARTIFACTS_PATH, "."}, CONTAINER_NAME, false, false);

            return new ProcessInputStream(process);
        } catch (ApiException e) {
            throw new IOException("Failed to copy artifacts from sandbox: " + sandboxId, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The caller is responsible for closing {@code tarStream}. This method does not close it.
     */
    @Override
    public void copyToSandbox(String sandboxId, InputStream tarStream, String destPath) throws IOException {
        Objects.requireNonNull(sandboxId, "SandboxId cannot be null");
        Objects.requireNonNull(tarStream, "TarStream cannot be null");
        Objects.requireNonNull(destPath, "DestPath cannot be null");

        try {
            Process process = kubeExec.exec(kubeConfig.getNamespace(), sandboxId,
                    new String[]{"tar", "xf", "-", "-C", destPath}, CONTAINER_NAME, true, false);

            try (OutputStream os = process.getOutputStream()) {
                tarStream.transferTo(os);
            }

            boolean completed = process.waitFor(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroy();
                throw new IOException("copyToSandbox timed out for sandbox: " + sandboxId);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("copyToSandbox failed with exit code " + exitCode);
            }
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("copyToSandbox interrupted for sandbox: " + sandboxId, e);
        } catch (ApiException e) {
            throw new IOException("Failed to copy archive to sandbox: " + sandboxId, e);
        }
    }

    @Override
    public int count() throws IOException {
        try {
            V1PodList podList = coreV1Api.listNamespacedPod(kubeConfig.getNamespace())
                    .labelSelector(LABEL_ROLE + "=" + LABEL_ROLE_VALUE).execute();
            return podList.getItems().size();
        } catch (ApiException e) {
            throw new IOException("Failed to count sandboxes", e);
        }
    }

    @Override
    public int reapExpired() throws IOException {
        try {
            V1PodList podList = coreV1Api.listNamespacedPod(kubeConfig.getNamespace())
                    .labelSelector(LABEL_ROLE + "=" + LABEL_ROLE_VALUE).execute();

            Instant now = Instant.now();
            int removed = 0;

            for (V1Pod pod : podList.getItems()) {
                V1ObjectMeta metadata = pod.getMetadata();
                if (metadata == null) {
                    continue;
                }

                String identifier = metadata.getLabels() != null ? metadata.getLabels().get(LABEL_IDENTIFIER) : null;
                if (identifier == null) {
                    continue;
                }

                Instant expiresAt = resolveExpiry(identifier, metadata.getAnnotations());
                if (expiresAt != null && now.isAfter(expiresAt)) {
                    try {
                        coreV1Api.deleteNamespacedPod(metadata.getName(), kubeConfig.getNamespace()).execute();
                        expiryStore.remove(identifier);
                        removed++;
                        log.debug("Reaped expired sandbox: {}", identifier);
                    } catch (Exception e) {
                        log.warn("Failed to reap sandbox {}: {}", identifier, e.getMessage());
                    }
                }
            }

            return removed;
        } catch (ApiException e) {
            throw new IOException("Failed to reap expired sandboxes", e);
        }
    }

    /**
     * Closes this backend and releases any resources.
     *
     * <p>
     * The Kubernetes Java client does not require explicit close. This method is provided as an extension point for
     * subclasses or future resource cleanup needs.
     */
    @Override
    public void close() throws IOException {
        // Kubernetes Java client does not require explicit close
    }

    // --- Private helpers ---

    private V1Pod createPod(String podName, String identifier, Instant expiresAt) throws ApiException {
        Map<String, String> labels = Map.of(LABEL_ROLE, LABEL_ROLE_VALUE, LABEL_IDENTIFIER, identifier);

        Map<String, String> annotations = Map.of(ANNOTATION_EXPIRES_AT,
                DateTimeFormatter.ISO_INSTANT.format(expiresAt));

        V1SecurityContext securityContext = new V1SecurityContext()
                .allowPrivilegeEscalation(kubeConfig.isAllowPrivilegeEscalation())
                .readOnlyRootFilesystem(kubeConfig.isReadOnlyRootFilesystem())
                .capabilities(new V1Capabilities().drop(kubeConfig.getDropCapabilities())
                        .add(kubeConfig.getAddCapabilities().isEmpty() ? null : kubeConfig.getAddCapabilities()));

        V1ResourceRequirements resources = new V1ResourceRequirements()
                .requests(Map.of("memory", new Quantity(kubeConfig.getMemoryRequest()), "cpu",
                        new Quantity(kubeConfig.getCpuRequest())))
                .limits(Map.of("memory", new Quantity(kubeConfig.getMemoryLimit()), "cpu",
                        new Quantity(kubeConfig.getCpuLimit())));

        V1Container container = new V1Container().name(CONTAINER_NAME).image(sandboxConfig.getDefaultImage())
                .command(List.of("sleep", "infinity")).resources(resources).securityContext(securityContext);

        V1PodSpec podSpec = new V1PodSpec().restartPolicy("Never").containers(List.of(container)).overhead(null);

        if (kubeConfig.getServiceAccountName() != null) {
            podSpec.serviceAccountName(kubeConfig.getServiceAccountName());
        }

        if (!kubeConfig.getNodeSelector().isEmpty()) {
            podSpec.nodeSelector(kubeConfig.getNodeSelector());
        }

        V1Pod pod = new V1Pod().metadata(new V1ObjectMeta().name(podName).namespace(kubeConfig.getNamespace())
                .labels(labels).annotations(annotations)).spec(podSpec);

        return coreV1Api.createNamespacedPod(kubeConfig.getNamespace(), pod).execute();
    }

    private void waitForPodReady(String podName) throws IOException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(kubeConfig.getPodReadyTimeoutMs());

        while (System.nanoTime() - deadlineNanos < 0) {
            try {
                V1Pod pod = coreV1Api.readNamespacedPod(podName, kubeConfig.getNamespace()).execute();
                String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;

                if ("Running".equals(phase)) {
                    log.debug("Pod {} is running", podName);
                    return;
                }
                if ("Failed".equals(phase) || "Succeeded".equals(phase)) {
                    throw new IOException("Pod " + podName + " terminated with phase: " + phase);
                }

                log.debug("Pod {} phase: {}, waiting...", podName, phase);
            } catch (ApiException e) {
                throw new IOException("Failed to read pod status: " + podName, e);
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for pod: " + podName, e);
            }
        }

        throw new IOException("Timed out waiting for pod " + podName + " to be ready after "
                + kubeConfig.getPodReadyTimeoutMs() + "ms");
    }

    private void initializePod(String podName) throws IOException {
        String sandboxUser = kubeConfig.getSandboxUser();
        int uid = kubeConfig.getSandboxUid();

        String initScript = String.format(
                "id -u %s >/dev/null 2>&1 || useradd -m -u %d -s /bin/sh %s; " + "mkdir -p /workspace %s %s/logs; "
                        + "chown %s:%s /workspace %s %s/logs",
                sandboxUser, uid, sandboxUser, ARTIFACTS_PATH, ARTIFACTS_PATH, sandboxUser, sandboxUser, ARTIFACTS_PATH,
                ARTIFACTS_PATH);

        ExecParams initParams = ExecParams.builder().command(initScript).asUser(SandboxUser.ROOT).timeoutMs(30_000)
                .build();

        ExecResult result = exec(podName, initParams);
        if (result.getExitCode() != 0) {
            log.error("Pod initialization failed (exit code {}): {}", result.getExitCode(), result.getStderr());
            deletePodQuietly(podName);
            throw new IOException(
                    "Pod initialization failed (exit code " + result.getExitCode() + "): " + result.getStderr());
        }
    }

    private Sandbox reuseExistingPod(String identifier, String podName, Instant expiresAt) throws IOException {
        try {
            V1Pod pod = coreV1Api.readNamespacedPod(podName, kubeConfig.getNamespace()).execute();

            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
            if (!"Running".equals(phase)) {
                log.warn("Existing pod {} is not running (phase: {}), deleting and recreating", podName, phase);
                deletePodAndWait(podName);
                createPod(podName, identifier, expiresAt);
                waitForPodReady(podName);
                initializePod(podName);
                expiryStore.put(identifier, expiresAt);
                return buildSandbox(identifier, podName, podName, sandboxConfig.getDefaultImage(), Instant.now(),
                        expiresAt);
            }

            expiryStore.put(identifier, expiresAt);

            String image = resolveContainerImage(pod);
            Instant createdAt = Instant.now();
            if (pod.getMetadata() != null && pod.getMetadata().getCreationTimestamp() != null) {
                OffsetDateTime ts = pod.getMetadata().getCreationTimestamp();
                createdAt = ts.toInstant();
            }

            return buildSandbox(identifier, podName, podName, image, createdAt, expiresAt);
        } catch (ApiException e) {
            throw new IOException("Failed to reuse existing pod: " + podName, e);
        }
    }

    private void deletePodQuietly(String podName) {
        try {
            coreV1Api.deleteNamespacedPod(podName, kubeConfig.getNamespace()).execute();
        } catch (ApiException e) {
            log.debug("Failed to delete pod {} during recovery: {}", podName, e.getMessage());
        }
    }

    private void deletePodAndWait(String podName) throws IOException {
        try {
            coreV1Api.deleteNamespacedPod(podName, kubeConfig.getNamespace()).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return;
            }
            throw new IOException("Failed to delete pod: " + podName, e);
        }
        waitForPodDeleted(podName);
    }

    private void waitForPodDeleted(String podName) throws IOException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(kubeConfig.getPodReadyTimeoutMs());

        while (System.nanoTime() - deadlineNanos < 0) {
            try {
                coreV1Api.readNamespacedPod(podName, kubeConfig.getNamespace()).execute();
                log.debug("Pod {} still exists, waiting for deletion...", podName);
            } catch (ApiException e) {
                if (e.getCode() == 404) {
                    log.debug("Pod {} deleted", podName);
                    return;
                }
                throw new IOException("Failed to check pod deletion status: " + podName, e);
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for pod deletion: " + podName, e);
            }
        }

        throw new IOException("Timed out waiting for pod " + podName + " to be deleted after "
                + kubeConfig.getPodReadyTimeoutMs() + "ms");
    }

    private String resolveContainerImage(V1Pod pod) {
        if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            for (V1Container container : pod.getSpec().getContainers()) {
                if (CONTAINER_NAME.equals(container.getName()) && container.getImage() != null) {
                    return container.getImage();
                }
            }
        }
        return sandboxConfig.getDefaultImage();
    }

    private Sandbox buildSandbox(String identifier, String sandboxId, String name, String image, Instant createdAt,
            Instant expiresAt) {
        return Sandbox.builder().identifier(identifier).sandboxId(sandboxId).name(name).image(image)
                .createdAt(createdAt).expiresAt(expiresAt).build();
    }

    /** Package-private for testing. */
    String buildShellCommand(ExecParams params) {
        StringBuilder envPrefix = new StringBuilder();

        Map<String, String> env = params.getEnv();
        if (env != null && !env.isEmpty()) {
            for (Map.Entry<String, String> entry : env.entrySet()) {
                String key = entry.getKey();
                if (!ENV_KEY_PATTERN.matcher(key).matches()) {
                    throw new IllegalArgumentException("Invalid environment variable name: " + key);
                }
                envPrefix.append("export ").append(key).append("='").append(entry.getValue().replace("'", "'\\''"))
                        .append("'; ");
            }
        }

        String command = params.getCommand();
        String cwd = params.getCwd();
        if (cwd != null && !cwd.isEmpty()) {
            command = "cd '" + cwd.replace("'", "'\\''") + "' && " + command;
        }

        // Prepend env exports so they are visible to the command regardless of user switching
        command = envPrefix + command;

        String user = resolveUser(params.getAsUser());
        if (!"root".equals(user)) {
            command = "su -s /bin/sh " + user + " -c '" + command.replace("'", "'\\''") + "'";
        }

        return command;
    }

    private String resolveUser(SandboxUser user) {
        if (user == null) {
            return kubeConfig.getSandboxUser();
        }
        switch (user) {
            case ROOT :
                return "root";
            case SANDBOX :
                return kubeConfig.getSandboxUser();
            default :
                throw new IllegalArgumentException("Unknown SandboxUser: " + user);
        }
    }

    private Instant resolveExpiry(String identifier, Map<String, String> annotations) {
        Instant tracked = expiryStore.get(identifier).orElse(null);
        if (tracked != null) {
            return tracked;
        }

        if (annotations != null) {
            String value = annotations.get(ANNOTATION_EXPIRES_AT);
            if (value != null) {
                try {
                    return Instant.parse(value);
                } catch (Exception e) {
                    log.warn("Failed to parse expires-at annotation for {}: {}", identifier, value);
                }
            }
        }

        return null;
    }

    /**
     * Reads from the stream into target, stopping when maxBytes characters have been appended.
     *
     * @return {@code true} if output was truncated because it reached maxBytes
     */
    static boolean readStream(InputStream stream, StringBuilder target, int maxBytes) {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                if (target.length() >= maxBytes) {
                    return true;
                }
                int remaining = maxBytes - target.length();
                int appendLen = Math.min(n, remaining);
                target.append(buf, 0, appendLen);
            }
        } catch (IOException e) {
            // stream closed — expected on process termination
        }
        return target.length() >= maxBytes;
    }

    /**
     * InputStream wrapper that destroys the underlying process when closed. Drains stderr in a daemon thread to prevent
     * blocking when the stderr buffer fills.
     */
    private static class ProcessInputStream extends FilterInputStream {

        private final Process process;
        private final Thread stderrDrainThread;

        ProcessInputStream(Process process) {
            super(process.getInputStream());
            this.process = process;
            this.stderrDrainThread = new Thread(() -> drainStream(process.getErrorStream()), "stderr-drain");
            this.stderrDrainThread.setDaemon(true);
            this.stderrDrainThread.start();
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                process.destroy();
                stderrDrainThread.interrupt();
                try {
                    stderrDrainThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static void drainStream(InputStream stream) {
            try {
                byte[] buf = new byte[4096];
                while (stream.read(buf) != -1) {
                    // discard
                }
            } catch (IOException e) {
                // expected on process termination
            }
        }
    }
}
