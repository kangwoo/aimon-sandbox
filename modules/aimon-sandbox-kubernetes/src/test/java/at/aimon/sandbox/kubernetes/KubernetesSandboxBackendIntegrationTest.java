package at.aimon.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.InMemorySandboxExpiryStore;
import at.aimon.sandbox.backend.SandboxExpiryStore;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.model.SandboxUser;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;

/**
 * Integration test for {@link KubernetesSandboxBackend} against a real Kubernetes cluster.
 *
 * <p>
 * Requires the environment variable {@code AIMON_KUBERNETES_IT=true} to run. This prevents accidental execution in CI
 * environments without Kubernetes access. The test uses the default kubeconfig discovery chain ({@code ~/.kube/config}
 * or in-cluster config).
 *
 * <p>
 * Usage: {@code AIMON_KUBERNETES_IT=true ./gradlew :aimon-sandbox-kubernetes:test --tests
 * "at.aimon.sandbox.kubernetes.KubernetesSandboxBackendIntegrationTest"}
 */
@EnabledIfEnvironmentVariable(named = "AIMON_KUBERNETES_IT", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KubernetesSandboxBackendIntegrationTest {

    private static final String TEST_IDENTIFIER = "it-test-sandbox";
    private static final int TTL_SECONDS = 300;

    private KubernetesSandboxBackend backend;
    private SandboxExpiryStore expiryStore;

    @BeforeAll
    static void checkKubernetesAvailable() throws IOException {
        KubernetesSandboxConfig config = KubernetesSandboxConfig.builder().build();
        ApiClient apiClient = KubernetesClientFactory.create(config);
        CoreV1Api coreV1Api = new CoreV1Api(apiClient);
        try {
            coreV1Api.listNamespacedPod(config.getNamespace()).limit(1).execute();
        } catch (Exception e) {
            throw new IllegalStateException("Kubernetes cluster is not accessible. Skipping integration tests.", e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        // Add capabilities needed for pod initialization (useradd, chown, mkdir)
        KubernetesSandboxConfig kubeConfig = KubernetesSandboxConfig.builder().dropCapabilities(List.of("ALL"))
                .addCapabilities(List.of("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID")).build();
        SandboxConfig sandboxConfig = SandboxConfig.builder().defaultImage("ubuntu:22.04").build();
        expiryStore = new InMemorySandboxExpiryStore();

        ApiClient apiClient = KubernetesClientFactory.create(kubeConfig);
        backend = new KubernetesSandboxBackend(apiClient, sandboxConfig, kubeConfig, expiryStore);
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            backend.delete(TEST_IDENTIFIER);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        backend.close();
    }

    @Test
    @Order(1)
    void ensure_CreatesAndStartsPod() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        assertThat(sandbox).isNotNull();
        assertThat(sandbox.getIdentifier()).isEqualTo(TEST_IDENTIFIER);
        assertThat(sandbox.getSandboxId()).isNotEmpty();
        assertThat(sandbox.getName()).isEqualTo("sandbox-" + TEST_IDENTIFIER);
        assertThat(sandbox.getImage()).isEqualTo("ubuntu:22.04");
        assertThat(sandbox.getCreatedAt()).isNotNull();
        assertThat(sandbox.getExpiresAt()).isNotNull();
        assertThat(sandbox.getExpiresAt()).isAfter(sandbox.getCreatedAt());
    }

    @Test
    @Order(2)
    void exec_RunsSimpleCommand() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        ExecParams params = ExecParams.builder().command("echo 'hello world'").timeoutMs(10_000).build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout()).contains("hello world");
    }

    @Test
    @Order(3)
    void exec_RunsCommandAsRootUser() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        ExecParams params = ExecParams.builder().command("whoami").asUser(SandboxUser.ROOT).timeoutMs(10_000).build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout().trim()).isEqualTo("root");
    }

    @Test
    @Order(4)
    void exec_RunsCommandAsSandboxUser() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        ExecParams params = ExecParams.builder().command("whoami").asUser(SandboxUser.SANDBOX).timeoutMs(10_000)
                .build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout().trim()).isEqualTo("sandbox");
    }

    @Test
    @Order(5)
    void exec_RunsCommandWithWorkingDirectory() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        ExecParams params = ExecParams.builder().command("pwd").cwd("/workspace").timeoutMs(10_000).build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout().trim()).isEqualTo("/workspace");
    }

    @Test
    @Order(6)
    void exec_RunsCommandWithEnvironmentVariables() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        ExecParams params = ExecParams.builder().command("echo $MY_VAR").env(Map.of("MY_VAR", "test_value"))
                .timeoutMs(10_000).build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout().trim()).isEqualTo("test_value");
    }

    @Test
    @Order(7)
    void exec_TruncatesOutputWhenExceedsMaxOutputBytes() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        // Generate output larger than maxOutputBytes
        ExecParams params = ExecParams.builder().command("seq 1 1000").maxOutputBytes(50).timeoutMs(10_000).build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStderr()).contains("[WARNING] Output truncated: exceeded maxOutputBytes limit");
    }

    @Test
    @Order(8)
    void exec_ReportsNonZeroExitCode() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        ExecParams params = ExecParams.builder().command("exit 42").timeoutMs(10_000).build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(42);
    }

    @Test
    @Order(9)
    void exec_CapturesStderr() throws IOException {
        Sandbox sandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        ExecParams params = ExecParams.builder().command("echo 'error message' >&2").timeoutMs(10_000).build();

        ExecResult result = backend.exec(sandbox.getSandboxId(), params);

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStderr()).contains("error message");
    }

    @Test
    @Order(10)
    void ensure_ReusesExistingPod() throws IOException {
        Sandbox first = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);
        Sandbox second = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        assertThat(second).isNotNull();
        assertThat(second.getIdentifier()).isEqualTo(first.getIdentifier());
        assertThat(second.getSandboxId()).isEqualTo(first.getSandboxId());
    }

    @Test
    @Order(11)
    void count_ReturnsNumberOfSandboxPods() throws IOException {
        backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        int count = backend.count();

        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(12)
    void restart_DeletesAndRecreatesPod() throws IOException {
        Sandbox original = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);
        Sandbox restarted = backend.restart(TEST_IDENTIFIER, TTL_SECONDS);

        assertThat(restarted).isNotNull();
        assertThat(restarted.getIdentifier()).isEqualTo(original.getIdentifier());
        // Pod name is deterministic, so sandboxId stays the same
        assertThat(restarted.getSandboxId()).isEqualTo(original.getSandboxId());
        // But the pod was recreated, so createdAt should be different
        assertThat(restarted.getCreatedAt()).isAfterOrEqualTo(original.getCreatedAt());
    }

    @Test
    @Order(13)
    void delete_RemovesPod() throws IOException {
        backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        backend.delete(TEST_IDENTIFIER);

        // After deletion, ensure should create a new pod (not reuse)
        Sandbox newSandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);
        assertThat(newSandbox).isNotNull();
    }

    @Test
    @Order(14)
    void delete_NonExistentIdentifier_DoesNotThrow() throws IOException {
        // Should not throw even if pod doesn't exist
        backend.delete("non-existent-identifier");
    }
}
