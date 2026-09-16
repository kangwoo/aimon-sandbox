package at.aimon.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.InMemorySandboxExpiryStore;
import at.aimon.sandbox.backend.SandboxExpiryStore;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.model.SandboxUser;
import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;

@ExtendWith(MockitoExtension.class)
class KubernetesSandboxBackendTest {

    @Mock
    private CoreV1Api coreV1Api;

    @Mock
    private Exec kubeExec;

    private SandboxConfig sandboxConfig;
    private KubernetesSandboxConfig kubeConfig;
    private SandboxExpiryStore expiryStore;
    private KubernetesSandboxBackend backend;

    @BeforeEach
    void setUp() {
        sandboxConfig = SandboxConfig.builder().build();
        kubeConfig = KubernetesSandboxConfig.builder().podReadyTimeoutMs(1000).build();
        expiryStore = new InMemorySandboxExpiryStore();
        backend = new KubernetesSandboxBackend(coreV1Api, kubeExec, sandboxConfig, kubeConfig, expiryStore);
    }

    // --- Constructor tests ---

    @Test
    void constructor_NullCoreV1Api_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new KubernetesSandboxBackend(null, kubeExec, sandboxConfig, kubeConfig, expiryStore))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("CoreV1Api cannot be null");
    }

    @Test
    void constructor_NullExec_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new KubernetesSandboxBackend(coreV1Api, null, sandboxConfig, kubeConfig, expiryStore))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Exec cannot be null");
    }

    @Test
    void constructor_NullSandboxConfig_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new KubernetesSandboxBackend(coreV1Api, kubeExec, null, kubeConfig, expiryStore))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("SandboxConfig cannot be null");
    }

    @Test
    void constructor_NullKubeConfig_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new KubernetesSandboxBackend(coreV1Api, kubeExec, sandboxConfig, null, expiryStore))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("KubernetesSandboxConfig cannot be null");
    }

    @Test
    void constructor_NullExpiryStore_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new KubernetesSandboxBackend(coreV1Api, kubeExec, sandboxConfig, kubeConfig, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("SandboxExpiryStore cannot be null");
    }

    // --- ensure() tests ---

    @Test
    void ensure_CreatesNewPod() throws Exception {
        String identifier = "test-sandbox";

        mockCreatePodFlow("sandbox-test-sandbox");
        mockReadPodRunning("sandbox-test-sandbox");
        mockExecProcess("sandbox-test-sandbox", "", "", 0);

        Sandbox sandbox = backend.ensure(identifier, 3600);

        assertThat(sandbox.getIdentifier()).isEqualTo(identifier);
        assertThat(sandbox.getSandboxId()).isEqualTo("sandbox-test-sandbox");
        assertThat(sandbox.getName()).isEqualTo("sandbox-test-sandbox");
        assertThat(sandbox.getImage()).isEqualTo("ubuntu:22.04");
        assertThat(sandbox.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void ensure_ConflictException_ReusesExistingPod() throws Exception {
        String identifier = "test-sandbox";

        mockCreatePodConflict();
        mockReadPodRunning("sandbox-test-sandbox");

        Sandbox sandbox = backend.ensure(identifier, 3600);

        assertThat(sandbox.getIdentifier()).isEqualTo(identifier);
        assertThat(sandbox.getSandboxId()).isEqualTo("sandbox-test-sandbox");
    }

    @Test
    void ensure_ConflictException_NonRunningPod_DeletesAndRecreates() throws Exception {
        String identifier = "test-sandbox";

        // createPod: first call throws 409, second call (re-create) succeeds
        V1Pod createdPod = new V1Pod()
                .metadata(new V1ObjectMeta().name("sandbox-test-sandbox").uid("uid-sandbox-test-sandbox"));
        CoreV1Api.APIcreateNamespacedPodRequest createRequest = mock(CoreV1Api.APIcreateNamespacedPodRequest.class);
        when(coreV1Api.createNamespacedPod(anyString(), any(V1Pod.class))).thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "Conflict")).thenReturn(createdPod);

        // readPod: first read returns Failed (reuse check), then 404 (waitForPodDeleted),
        // then Running (waitForPodReady)
        V1Pod failedPod = new V1Pod().metadata(new V1ObjectMeta().name("sandbox-test-sandbox"))
                .status(new V1PodStatus().phase("Failed"));
        V1Pod runningPod = new V1Pod()
                .metadata(new V1ObjectMeta().name("sandbox-test-sandbox")
                        .creationTimestamp(OffsetDateTime.now(ZoneOffset.UTC)))
                .spec(new V1PodSpec().containers(List.of(new V1Container().name("sandbox").image("ubuntu:22.04"))))
                .status(new V1PodStatus().phase("Running"));

        CoreV1Api.APIreadNamespacedPodRequest readRequest = mock(CoreV1Api.APIreadNamespacedPodRequest.class);
        when(coreV1Api.readNamespacedPod("sandbox-test-sandbox", "default")).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(failedPod).thenThrow(new ApiException(404, "Not Found"))
                .thenReturn(runningPod);

        // Mock delete for recovery (deletePodAndWait)
        mockDeletePod("sandbox-test-sandbox");

        // Mock exec for initializePod
        mockExecProcess("sandbox-test-sandbox", "", "", 0);

        Sandbox sandbox = backend.ensure(identifier, 3600);

        assertThat(sandbox.getIdentifier()).isEqualTo(identifier);
        assertThat(sandbox.getSandboxId()).isEqualTo("sandbox-test-sandbox");
    }

    @Test
    void ensure_ConflictException_ReusesExistingPod_ResolvesImageFromPodSpec() throws Exception {
        String identifier = "test-sandbox";
        String customImage = "python:3.12-slim";

        mockCreatePodConflict();

        // readPod returns Running with custom image
        V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta().name("sandbox-test-sandbox")
                        .creationTimestamp(OffsetDateTime.now(ZoneOffset.UTC)))
                .spec(new V1PodSpec().containers(List.of(new V1Container().name("sandbox").image(customImage))))
                .status(new V1PodStatus().phase("Running"));

        CoreV1Api.APIreadNamespacedPodRequest readRequest = mock(CoreV1Api.APIreadNamespacedPodRequest.class);
        when(coreV1Api.readNamespacedPod("sandbox-test-sandbox", "default")).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(pod);

        Sandbox sandbox = backend.ensure(identifier, 3600);

        assertThat(sandbox.getImage()).isEqualTo(customImage);
    }

    @Test
    void ensure_InitializationFailure_DeletesPodAndThrowsIOException() throws Exception {
        String identifier = "test-sandbox";

        mockCreatePodFlow("sandbox-test-sandbox");
        mockReadPodRunning("sandbox-test-sandbox");

        // initializePod exec returns non-zero exit code
        mockExecProcess("sandbox-test-sandbox", "", "useradd: error", 1);

        // Mock delete for cleanup
        mockDeletePod("sandbox-test-sandbox");

        assertThatThrownBy(() -> backend.ensure(identifier, 3600)).isInstanceOf(IOException.class)
                .hasMessageContaining("Pod initialization failed").hasMessageContaining("exit code 1");

        verify(coreV1Api).deleteNamespacedPod("sandbox-test-sandbox", "default");
    }

    @Test
    void ensure_NullIdentifier_ThrowsNullPointerException() {
        assertThatThrownBy(() -> backend.ensure(null, 3600)).isInstanceOf(NullPointerException.class);
    }

    // --- delete() tests ---

    @Test
    void delete_RemovesPod() throws Exception {
        mockDeletePod("sandbox-test-sandbox");

        backend.delete("test-sandbox");

        verify(coreV1Api).deleteNamespacedPod("sandbox-test-sandbox", "default");
    }

    @Test
    void delete_NotFound_Ignored() throws Exception {
        mockDeletePodNotFound("sandbox-test-sandbox");

        backend.delete("test-sandbox");
    }

    @Test
    void delete_NullIdentifier_ThrowsNullPointerException() {
        assertThatThrownBy(() -> backend.delete(null)).isInstanceOf(NullPointerException.class);
    }

    // --- restart() tests ---

    @Test
    void restart_DeletesThenEnsures() throws Exception {
        mockDeletePod("sandbox-test-sandbox");
        mockCreatePodFlow("sandbox-test-sandbox");
        mockReadPodRunning("sandbox-test-sandbox");
        mockExecProcess("sandbox-test-sandbox", "", "", 0);

        Sandbox sandbox = backend.restart("test-sandbox", 1800);

        assertThat(sandbox.getSandboxId()).isEqualTo("sandbox-test-sandbox");
    }

    // --- exec() tests ---

    @Test
    void exec_ReturnsStdoutAndStderr() throws Exception {
        ExecParams params = ExecParams.builder().command("echo hello").timeoutMs(5000).build();

        mockExecProcess("container-1", "hello\n", "warning\n", 0);

        ExecResult result = backend.exec("container-1", params);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).isEqualTo("hello\n");
        assertThat(result.getStderr()).isEqualTo("warning\n");
    }

    @Test
    void exec_WithCwd_WrapsCdCommand() {
        ExecParams params = ExecParams.builder().command("ls").cwd("/workspace").asUser(SandboxUser.ROOT)
                .timeoutMs(5000).build();

        String shellCommand = backend.buildShellCommand(params);

        assertThat(shellCommand).isEqualTo("cd '/workspace' && ls");
    }

    @Test
    void exec_WithSandboxUser_UsesSubstitution() {
        ExecParams params = ExecParams.builder().command("whoami").asUser(SandboxUser.SANDBOX).timeoutMs(5000).build();

        String shellCommand = backend.buildShellCommand(params);

        assertThat(shellCommand).contains("su -s /bin/sh sandbox -c");
        assertThat(shellCommand).contains("whoami");
    }

    @Test
    void exec_WithRootUser_NoSubstitution() {
        ExecParams params = ExecParams.builder().command("whoami").asUser(SandboxUser.ROOT).timeoutMs(5000).build();

        String shellCommand = backend.buildShellCommand(params);

        assertThat(shellCommand).isEqualTo("whoami");
        assertThat(shellCommand).doesNotContain("su ");
    }

    @Test
    void exec_WithEnv_ExportsVariables() {
        ExecParams params = ExecParams.builder().command("env").env(Map.of("FOO", "bar")).asUser(SandboxUser.ROOT)
                .timeoutMs(5000).build();

        String shellCommand = backend.buildShellCommand(params);

        assertThat(shellCommand).contains("export FOO='bar'");
        assertThat(shellCommand).contains("env");
    }

    @Test
    void exec_WithEnvAndSandboxUser_EnvInsideSu() {
        ExecParams params = ExecParams.builder().command("env").env(Map.of("FOO", "bar")).asUser(SandboxUser.SANDBOX)
                .timeoutMs(5000).build();

        String shellCommand = backend.buildShellCommand(params);

        // env export must be inside su -c so the sandbox user can see the variables
        assertThat(shellCommand).startsWith("su -s /bin/sh sandbox -c '");
        assertThat(shellCommand).contains("export FOO='\\''bar'\\''");
        assertThat(shellCommand).contains("env");
    }

    @Test
    void exec_WithInvalidEnvKey_ThrowsIllegalArgumentException() {
        ExecParams params = ExecParams.builder().command("env").env(Map.of("INVALID-KEY", "value"))
                .asUser(SandboxUser.ROOT).timeoutMs(5000).build();

        assertThatThrownBy(() -> backend.buildShellCommand(params)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid environment variable name");
    }

    @Test
    void exec_WithCwdContainingSingleQuotes_EscapesProperly() {
        ExecParams params = ExecParams.builder().command("ls").cwd("/workspace/it's a dir").asUser(SandboxUser.ROOT)
                .timeoutMs(5000).build();

        String shellCommand = backend.buildShellCommand(params);

        assertThat(shellCommand).contains("cd '/workspace/it'\\''s a dir'");
    }

    @Test
    void exec_Timeout_ThrowsIOException() throws Exception {
        ExecParams params = ExecParams.builder().command("sleep 999").timeoutMs(100).build();

        Process process = mock(Process.class);
        when(kubeExec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(process);
        // Streams are accessed asynchronously by daemon threads — may or may not be called before waitFor returns
        lenient().when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        lenient().when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThatThrownBy(() -> backend.exec("container-1", params)).isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void exec_NullSandboxId_ThrowsNullPointerException() {
        ExecParams params = ExecParams.builder().command("echo").build();
        assertThatThrownBy(() -> backend.exec(null, params)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void exec_NullParams_ThrowsNullPointerException() {
        assertThatThrownBy(() -> backend.exec("container-1", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void exec_OutputExceedsMaxOutputBytes_TruncatesAndWarns() throws Exception {
        int maxOutput = 50;
        ExecParams params = ExecParams.builder().command("echo large").timeoutMs(5000).maxOutputBytes(maxOutput)
                .build();

        String largeOutput = "A".repeat(100);
        mockExecProcess("container-1", largeOutput, "", 0);

        ExecResult result = backend.exec("container-1", params);

        assertThat(result.getStdout().length()).isLessThanOrEqualTo(maxOutput);
        assertThat(result.getStderr()).contains("[WARNING] Output truncated");
    }

    // --- readStream() tests ---

    @Test
    void readStream_RespectsMaxBytes() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        InputStream stream = new ByteArrayInputStream(data);
        StringBuilder target = new StringBuilder();

        KubernetesSandboxBackend.readStream(stream, target, 5);

        assertThat(target.length()).isLessThanOrEqualTo(5);
    }

    @Test
    void readStream_MultiByteCutoff_DoesNotCorruptCharacters() {
        // 3-byte UTF-8 characters: Korean "한글" = 6 bytes
        String original = "한글테스트";
        byte[] data = original.getBytes(StandardCharsets.UTF_8);
        InputStream stream = new ByteArrayInputStream(data);
        StringBuilder target = new StringBuilder();

        // Limit to 3 characters — should not produce garbled output
        KubernetesSandboxBackend.readStream(stream, target, 3);

        String result = target.toString();
        assertThat(result.length()).isLessThanOrEqualTo(3);
        // Every character must be a valid Korean character, not a replacement char
        for (char c : result.toCharArray()) {
            assertThat(Character.isHighSurrogate(c) || !Character.toString(c).equals("\uFFFD")).isTrue();
        }
    }

    // --- copyArtifacts() tests ---

    @Test
    void copyArtifacts_ReturnsTarStream() throws Exception {
        Process process = mock(Process.class);
        InputStream expectedStream = new ByteArrayInputStream("tar-data".getBytes());
        when(process.getInputStream()).thenReturn(expectedStream);
        when(kubeExec.exec(eq("default"), eq("container-1"),
                eq(new String[]{"tar", "cf", "-", "-C", "/artifacts", "."}), eq("sandbox"), eq(false), eq(false)))
                .thenReturn(process);

        InputStream result = backend.copyArtifacts("container-1");

        assertThat(result).isNotNull();
        byte[] data = result.readAllBytes();
        assertThat(new String(data)).isEqualTo("tar-data");
    }

    @Test
    void copyArtifacts_NullSandboxId_ThrowsNullPointerException() {
        assertThatThrownBy(() -> backend.copyArtifacts(null)).isInstanceOf(NullPointerException.class);
    }

    // --- copyToSandbox() tests ---

    @Test
    void copyToSandbox_PipesTarStream() throws Exception {
        Process process = mock(Process.class);
        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        when(process.getOutputStream()).thenReturn(capturedOutput);
        when(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        when(kubeExec.exec(eq("default"), eq("container-1"), eq(new String[]{"tar", "xf", "-", "-C", "/workspace"}),
                eq("sandbox"), eq(true), eq(false))).thenReturn(process);

        InputStream tarStream = new ByteArrayInputStream("tar-content".getBytes());
        backend.copyToSandbox("container-1", tarStream, "/workspace");

        assertThat(capturedOutput.toString()).isEqualTo("tar-content");
    }

    @Test
    void copyToSandbox_NullSandboxId_ThrowsNullPointerException() {
        InputStream tarStream = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> backend.copyToSandbox(null, tarStream, "/workspace"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copyToSandbox_NullTarStream_ThrowsNullPointerException() {
        assertThatThrownBy(() -> backend.copyToSandbox("container-1", null, "/workspace"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copyToSandbox_NullDestPath_ThrowsNullPointerException() {
        InputStream tarStream = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> backend.copyToSandbox("container-1", tarStream, null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- count() tests ---

    @Test
    void count_ReturnsPodCount() throws Exception {
        mockListPods(List.of(createPodStub("pod-1", "id1"), createPodStub("pod-2", "id2")));

        assertThat(backend.count()).isEqualTo(2);
    }

    @Test
    void count_EmptyList_ReturnsZero() throws Exception {
        mockListPods(List.of());

        assertThat(backend.count()).isZero();
    }

    // --- reapExpired() tests ---

    @Test
    void reapExpired_RemovesExpiredPods() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        V1Pod pod = createPodWithExpiry("pod-1", "test-id", past);

        mockListPods(List.of(pod));
        mockDeletePod("pod-1");

        int removed = backend.reapExpired();

        assertThat(removed).isEqualTo(1);
        verify(coreV1Api).deleteNamespacedPod("pod-1", "default");
    }

    @Test
    void reapExpired_SkipsNonExpiredPods() throws Exception {
        Instant future = Instant.now().plusSeconds(3600);
        V1Pod pod = createPodWithExpiry("pod-1", "test-id", future);

        mockListPods(List.of(pod));

        int removed = backend.reapExpired();

        assertThat(removed).isZero();
        verify(coreV1Api, never()).deleteNamespacedPod(anyString(), anyString());
    }

    @Test
    void reapExpired_IndividualFailure_ContinuesReaping() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        V1Pod pod1 = createPodWithExpiry("pod-1", "id1", past);
        V1Pod pod2 = createPodWithExpiry("pod-2", "id2", past);

        mockListPods(List.of(pod1, pod2));

        CoreV1Api.APIdeleteNamespacedPodRequest deleteReq1 = mock(CoreV1Api.APIdeleteNamespacedPodRequest.class);
        when(coreV1Api.deleteNamespacedPod("pod-1", "default")).thenReturn(deleteReq1);
        doThrow(new ApiException("delete failed")).when(deleteReq1).execute();

        mockDeletePod("pod-2");

        int removed = backend.reapExpired();

        assertThat(removed).isEqualTo(1);
    }

    @Test
    void reapExpired_NoIdentifierLabel_SkipsPod() throws Exception {
        V1Pod pod = new V1Pod().metadata(new V1ObjectMeta().name("pod-1")
                .labels(Map.of(KubernetesSandboxBackend.LABEL_ROLE, "sandbox")).annotations(Map.of()));

        mockListPods(List.of(pod));

        int removed = backend.reapExpired();

        assertThat(removed).isZero();
    }

    // --- ensure() error propagation tests ---

    @Test
    void ensure_ApiExceptionNon409_ThrowsIOException() throws Exception {
        CoreV1Api.APIcreateNamespacedPodRequest createRequest = mock(CoreV1Api.APIcreateNamespacedPodRequest.class);
        when(coreV1Api.createNamespacedPod(anyString(), any(V1Pod.class))).thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThatThrownBy(() -> backend.ensure("test-sandbox", 3600)).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to ensure sandbox");
    }

    // --- delete() error propagation tests ---

    @Test
    void delete_ApiExceptionNon404_ThrowsIOException() throws Exception {
        CoreV1Api.APIdeleteNamespacedPodRequest deleteRequest = mock(CoreV1Api.APIdeleteNamespacedPodRequest.class);
        when(coreV1Api.deleteNamespacedPod("sandbox-test-sandbox", "default")).thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThatThrownBy(() -> backend.delete("test-sandbox")).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to delete pod");
    }

    // --- exec() error propagation tests ---

    @Test
    void exec_ApiException_ThrowsIOException() throws Exception {
        ExecParams params = ExecParams.builder().command("echo hello").timeoutMs(5000).build();

        when(kubeExec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(), anyBoolean()))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        assertThatThrownBy(() -> backend.exec("container-1", params)).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to execute command in sandbox");
    }

    @Test
    void exec_InterruptedException_ThrowsIOExceptionAndRestoresInterrupt() throws Exception {
        ExecParams params = ExecParams.builder().command("echo hello").timeoutMs(5000).build();

        Process process = mock(Process.class);
        when(kubeExec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(process);
        // Streams are accessed asynchronously by daemon threads — may or may not be called before waitFor throws
        lenient().when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        lenient().when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(any(Long.class), any(TimeUnit.class))).thenThrow(new InterruptedException("interrupted"));

        assertThatThrownBy(() -> backend.exec("container-1", params)).isInstanceOf(IOException.class)
                .hasMessageContaining("interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // Clear the interrupt flag for other tests
        Thread.interrupted();
    }

    // --- copyToSandbox() error propagation tests ---

    @Test
    void copyToSandbox_Timeout_ThrowsIOException() throws Exception {
        Process process = mock(Process.class);
        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        when(process.getOutputStream()).thenReturn(capturedOutput);
        when(process.waitFor(30, TimeUnit.SECONDS)).thenReturn(false);
        when(kubeExec.exec(eq("default"), eq("container-1"), eq(new String[]{"tar", "xf", "-", "-C", "/workspace"}),
                eq("sandbox"), eq(true), eq(false))).thenReturn(process);

        InputStream tarStream = new ByteArrayInputStream("tar-content".getBytes());

        assertThatThrownBy(() -> backend.copyToSandbox("container-1", tarStream, "/workspace"))
                .isInstanceOf(IOException.class).hasMessageContaining("timed out");
    }

    @Test
    void copyToSandbox_NonZeroExitCode_ThrowsIOException() throws Exception {
        Process process = mock(Process.class);
        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        when(process.getOutputStream()).thenReturn(capturedOutput);
        when(process.waitFor(30, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(1);
        when(kubeExec.exec(eq("default"), eq("container-1"), eq(new String[]{"tar", "xf", "-", "-C", "/workspace"}),
                eq("sandbox"), eq(true), eq(false))).thenReturn(process);

        InputStream tarStream = new ByteArrayInputStream("tar-content".getBytes());

        assertThatThrownBy(() -> backend.copyToSandbox("container-1", tarStream, "/workspace"))
                .isInstanceOf(IOException.class).hasMessageContaining("failed with exit code 1");
    }

    @Test
    void copyToSandbox_ApiException_ThrowsIOException() throws Exception {
        when(kubeExec.exec(eq("default"), eq("container-1"), eq(new String[]{"tar", "xf", "-", "-C", "/workspace"}),
                eq("sandbox"), eq(true), eq(false))).thenThrow(new ApiException(500, "Internal Server Error"));

        InputStream tarStream = new ByteArrayInputStream("tar-content".getBytes());

        assertThatThrownBy(() -> backend.copyToSandbox("container-1", tarStream, "/workspace"))
                .isInstanceOf(IOException.class).hasMessageContaining("Failed to copy archive to sandbox");
    }

    // --- copyArtifacts() error propagation tests ---

    @Test
    void copyArtifacts_ApiException_ThrowsIOException() throws Exception {
        when(kubeExec.exec(eq("default"), eq("container-1"),
                eq(new String[]{"tar", "cf", "-", "-C", "/artifacts", "."}), eq("sandbox"), eq(false), eq(false)))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        assertThatThrownBy(() -> backend.copyArtifacts("container-1")).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to copy artifacts from sandbox");
    }

    // --- count() error propagation tests ---

    @Test
    void count_ApiException_ThrowsIOException() throws Exception {
        CoreV1Api.APIlistNamespacedPodRequest listRequest = mock(CoreV1Api.APIlistNamespacedPodRequest.class);
        when(coreV1Api.listNamespacedPod("default")).thenReturn(listRequest);
        when(listRequest.labelSelector(anyString())).thenReturn(listRequest);
        when(listRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThatThrownBy(() -> backend.count()).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to count sandboxes");
    }

    // --- reapExpired() error propagation tests ---

    @Test
    void reapExpired_ApiException_ThrowsIOException() throws Exception {
        CoreV1Api.APIlistNamespacedPodRequest listRequest = mock(CoreV1Api.APIlistNamespacedPodRequest.class);
        when(coreV1Api.listNamespacedPod("default")).thenReturn(listRequest);
        when(listRequest.labelSelector(anyString())).thenReturn(listRequest);
        when(listRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThatThrownBy(() -> backend.reapExpired()).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to reap expired sandboxes");
    }

    // --- readStream() additional tests ---

    @Test
    void readStream_ReturnsTrueWhenTruncated() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        InputStream stream = new ByteArrayInputStream(data);
        StringBuilder target = new StringBuilder();

        boolean truncated = KubernetesSandboxBackend.readStream(stream, target, 5);

        assertThat(truncated).isTrue();
    }

    @Test
    void readStream_ReturnsFalseWhenNotTruncated() {
        byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        InputStream stream = new ByteArrayInputStream(data);
        StringBuilder target = new StringBuilder();

        boolean truncated = KubernetesSandboxBackend.readStream(stream, target, 1000);

        assertThat(truncated).isFalse();
    }

    // --- waitForPodReady() tests ---

    @Test
    void ensure_PodFailedPhase_ThrowsIOException() throws Exception {
        mockCreatePodFlow("sandbox-test-sandbox");

        V1Pod failedPod = new V1Pod().metadata(new V1ObjectMeta().name("sandbox-test-sandbox"))
                .status(new V1PodStatus().phase("Failed"));

        CoreV1Api.APIreadNamespacedPodRequest readRequest = mock(CoreV1Api.APIreadNamespacedPodRequest.class);
        when(coreV1Api.readNamespacedPod("sandbox-test-sandbox", "default")).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(failedPod);

        assertThatThrownBy(() -> backend.ensure("test-sandbox", 3600)).isInstanceOf(IOException.class)
                .hasMessageContaining("terminated with phase: Failed");
    }

    @Test
    void ensure_PodReadyTimeout_ThrowsIOException() throws Exception {
        mockCreatePodFlow("sandbox-test-sandbox");

        V1Pod pendingPod = new V1Pod().metadata(new V1ObjectMeta().name("sandbox-test-sandbox"))
                .status(new V1PodStatus().phase("Pending"));

        CoreV1Api.APIreadNamespacedPodRequest readRequest = mock(CoreV1Api.APIreadNamespacedPodRequest.class);
        when(coreV1Api.readNamespacedPod("sandbox-test-sandbox", "default")).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(pendingPod);

        assertThatThrownBy(() -> backend.ensure("test-sandbox", 3600)).isInstanceOf(IOException.class)
                .hasMessageContaining("Timed out waiting for pod");
    }

    // --- close() tests ---

    @Test
    void close_DoesNotThrow() throws IOException {
        backend.close();
    }

    // --- Helper methods ---

    private void mockCreatePodFlow(String podName) throws ApiException {
        V1Pod createdPod = new V1Pod().metadata(new V1ObjectMeta().name(podName).uid("uid-" + podName));

        CoreV1Api.APIcreateNamespacedPodRequest createRequest = mock(CoreV1Api.APIcreateNamespacedPodRequest.class);
        when(coreV1Api.createNamespacedPod(anyString(), any(V1Pod.class))).thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(createdPod);
    }

    private void mockCreatePodConflict() throws ApiException {
        CoreV1Api.APIcreateNamespacedPodRequest createRequest = mock(CoreV1Api.APIcreateNamespacedPodRequest.class);
        when(coreV1Api.createNamespacedPod(anyString(), any(V1Pod.class))).thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "Conflict"));
    }

    private void mockReadPodRunning(String podName) throws ApiException {
        V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta().name(podName).creationTimestamp(OffsetDateTime.now(ZoneOffset.UTC)))
                .spec(new V1PodSpec().containers(List.of(new V1Container().name("sandbox").image("ubuntu:22.04"))))
                .status(new V1PodStatus().phase("Running"));

        CoreV1Api.APIreadNamespacedPodRequest readRequest = mock(CoreV1Api.APIreadNamespacedPodRequest.class);
        when(coreV1Api.readNamespacedPod(podName, "default")).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(pod);
    }

    private void mockDeletePod(String podName) throws ApiException {
        CoreV1Api.APIdeleteNamespacedPodRequest deleteRequest = mock(CoreV1Api.APIdeleteNamespacedPodRequest.class);
        when(coreV1Api.deleteNamespacedPod(podName, "default")).thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenReturn(new V1Pod());
    }

    private void mockDeletePodNotFound(String podName) throws ApiException {
        CoreV1Api.APIdeleteNamespacedPodRequest deleteRequest = mock(CoreV1Api.APIdeleteNamespacedPodRequest.class);
        when(coreV1Api.deleteNamespacedPod(podName, "default")).thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(404, "Not Found"));
    }

    private void mockExecProcess(String podName, String stdout, String stderr, int exitCode)
            throws ApiException, IOException, InterruptedException {
        Process process = mock(Process.class);
        when(kubeExec.exec(anyString(), eq(podName), any(String[].class), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(process);

        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8)));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(any(Long.class), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(exitCode);
    }

    private void mockListPods(List<V1Pod> pods) throws ApiException {
        V1PodList podList = new V1PodList().items(pods);

        CoreV1Api.APIlistNamespacedPodRequest listRequest = mock(CoreV1Api.APIlistNamespacedPodRequest.class);
        when(coreV1Api.listNamespacedPod("default")).thenReturn(listRequest);
        when(listRequest.labelSelector(anyString())).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(podList);
    }

    private V1Pod createPodStub(String name, String identifier) {
        return new V1Pod().metadata(new V1ObjectMeta().name(name).labels(Map.of(KubernetesSandboxBackend.LABEL_ROLE,
                "sandbox", KubernetesSandboxBackend.LABEL_IDENTIFIER, identifier)));
    }

    private V1Pod createPodWithExpiry(String name, String identifier, Instant expiresAt) {
        return new V1Pod().metadata(new V1ObjectMeta().name(name)
                .labels(Map.of(KubernetesSandboxBackend.LABEL_ROLE, "sandbox",
                        KubernetesSandboxBackend.LABEL_IDENTIFIER, identifier))
                .annotations(Map.of(KubernetesSandboxBackend.ANNOTATION_EXPIRES_AT,
                        DateTimeFormatter.ISO_INSTANT.format(expiresAt))));
    }
}
