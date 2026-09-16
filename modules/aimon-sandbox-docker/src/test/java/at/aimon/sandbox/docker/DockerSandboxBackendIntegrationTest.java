package at.aimon.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.github.dockerjava.api.DockerClient;

import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.InMemorySandboxExpiryStore;
import at.aimon.sandbox.backend.SandboxExpiryStore;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.model.SandboxUser;

/**
 * Integration test for {@link DockerSandboxBackend} against a real Docker daemon.
 *
 * <p>
 * Requires the environment variable {@code AIMON_DOCKER_IT=true} to run. This prevents accidental execution in CI
 * environments without Docker access.
 *
 * <p>
 * Usage: {@code AIMON_DOCKER_IT=true ./gradlew :aimon-sandbox-docker:test --tests
 * "at.aimon.sandbox.docker.DockerSandboxBackendIntegrationTest"}
 */
@EnabledIfEnvironmentVariable(named = "AIMON_DOCKER_IT", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DockerSandboxBackendIntegrationTest {

    private static final String TEST_IDENTIFIER = "it-test-sandbox";
    private static final int TTL_SECONDS = 300;

    private DockerClient dockerClient;
    private DockerSandboxBackend backend;
    private SandboxExpiryStore expiryStore;

    @BeforeAll
    static void checkDockerAvailable() {
        DockerSandboxConfig config = DockerSandboxConfig.builder().build();
        try (DockerClient client = DockerClientFactory.create(config)) {
            client.pingCmd().exec();
        } catch (Exception e) {
            throw new IllegalStateException("Docker daemon is not accessible. Skipping integration tests.", e);
        }
    }

    @BeforeEach
    void setUp() {
        // Add capabilities needed for container initialization (useradd, chown, mkdir)
        DockerSandboxConfig dockerConfig = DockerSandboxConfig.builder().networkMode("none")
                .dropCapabilities(List.of("ALL"))
                .addCapabilities(List.of("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID")).build();
        SandboxConfig sandboxConfig = SandboxConfig.builder().defaultImage("ubuntu:22.04").build();
        expiryStore = new InMemorySandboxExpiryStore();

        dockerClient = DockerClientFactory.create(dockerConfig);
        backend = new DockerSandboxBackend(dockerClient, sandboxConfig, dockerConfig, expiryStore);
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
    void ensure_CreatesAndStartsContainer() throws IOException {
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
        assertThat(result.getStderr()).isEmpty();
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

        ExecParams params = ExecParams.builder().command("echo $MY_VAR").env(java.util.Map.of("MY_VAR", "test_value"))
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
    void ensure_ReusesExistingContainer() throws IOException {
        Sandbox first = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);
        Sandbox second = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        assertThat(second).isNotNull();
        assertThat(second.getIdentifier()).isEqualTo(first.getIdentifier());
        assertThat(second.getSandboxId()).isEqualTo(first.getSandboxId());
    }

    @Test
    @Order(11)
    void count_ReturnsNumberOfSandboxContainers() throws IOException {
        backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        int count = backend.count();

        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(12)
    void restart_DeletesAndRecreatesContainer() throws IOException {
        Sandbox original = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);
        Sandbox restarted = backend.restart(TEST_IDENTIFIER, TTL_SECONDS);

        assertThat(restarted).isNotNull();
        assertThat(restarted.getIdentifier()).isEqualTo(original.getIdentifier());
        // New container should have a different ID
        assertThat(restarted.getSandboxId()).isNotEqualTo(original.getSandboxId());
    }

    @Test
    @Order(13)
    void delete_RemovesContainer() throws IOException {
        backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);

        backend.delete(TEST_IDENTIFIER);

        // After deletion, ensure should create a new container (not reuse)
        Sandbox newSandbox = backend.ensure(TEST_IDENTIFIER, TTL_SECONDS);
        assertThat(newSandbox).isNotNull();
    }

    @Test
    @Order(14)
    void delete_NonExistentIdentifier_DoesNotThrow() throws IOException {
        // Should not throw even if container doesn't exist
        backend.delete("non-existent-identifier");
    }
}
