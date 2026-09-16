package at.aimon.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;

import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.InMemorySandboxExpiryStore;
import at.aimon.sandbox.backend.SandboxExpiryStore;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.model.SandboxUser;

@ExtendWith(MockitoExtension.class)
class DockerSandboxBackendTest {

    @Mock
    private DockerClient dockerClient;

    private SandboxConfig sandboxConfig;
    private DockerSandboxConfig dockerConfig;
    private SandboxExpiryStore expiryStore;
    private DockerSandboxBackend backend;

    @BeforeEach
    void setUp() {
        sandboxConfig = SandboxConfig.builder().build();
        dockerConfig = DockerSandboxConfig.builder().build();
        expiryStore = new InMemorySandboxExpiryStore();
        backend = new DockerSandboxBackend(dockerClient, sandboxConfig, dockerConfig, expiryStore);
    }

    // --- Constructor tests ---

    @Test
    void constructor_NullDockerClient_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new DockerSandboxBackend(null, sandboxConfig, dockerConfig, expiryStore))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("DockerClient cannot be null");
    }

    @Test
    void constructor_NullSandboxConfig_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new DockerSandboxBackend(dockerClient, null, dockerConfig, expiryStore))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("SandboxConfig cannot be null");
    }

    @Test
    void constructor_NullDockerConfig_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new DockerSandboxBackend(dockerClient, sandboxConfig, null, expiryStore))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("DockerSandboxConfig cannot be null");
    }

    @Test
    void constructor_NullExpiryStore_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new DockerSandboxBackend(dockerClient, sandboxConfig, dockerConfig, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("SandboxExpiryStore cannot be null");
    }

    // --- ensure() tests ---

    @Test
    void ensure_CreatesNewContainer() throws Exception {
        String identifier = "test-sandbox";

        mockCreateContainerFlow("container-id-1");
        mockExecFlow("container-id-1", "exec-init", "", "", 0);

        Sandbox sandbox = backend.ensure(identifier, 3600);

        assertThat(sandbox.getIdentifier()).isEqualTo(identifier);
        assertThat(sandbox.getSandboxId()).isEqualTo("container-id-1");
        assertThat(sandbox.getName()).isEqualTo("sandbox-test-sandbox");
        assertThat(sandbox.getImage()).isEqualTo("ubuntu:22.04");
        assertThat(sandbox.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void ensure_ConflictException_ReusesExistingContainer() throws IOException {
        String identifier = "test-sandbox";

        mockCreateContainerConflict();
        mockInspectContainer("sandbox-test-sandbox", "existing-container-id", true);

        Sandbox sandbox = backend.ensure(identifier, 3600);

        assertThat(sandbox.getIdentifier()).isEqualTo(identifier);
        assertThat(sandbox.getSandboxId()).isEqualTo("existing-container-id");
    }

    @Test
    void ensure_ConflictException_StartsStoppedContainer() throws IOException {
        String identifier = "test-sandbox";

        mockCreateContainerConflict();
        mockInspectContainer("sandbox-test-sandbox", "stopped-container-id", false);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("sandbox-test-sandbox")).thenReturn(startCmd);

        Sandbox sandbox = backend.ensure(identifier, 3600);

        verify(dockerClient).startContainerCmd("sandbox-test-sandbox");
        assertThat(sandbox.getSandboxId()).isEqualTo("stopped-container-id");
    }

    @Test
    void ensure_InitializationFails_RemovesContainerAndThrows() throws Exception {
        String identifier = "test-sandbox";

        mockCreateContainerFlow("container-id-1");
        mockExecFlow("container-id-1", "exec-init", "", "useradd: error", 1);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-id-1")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        assertThatThrownBy(() -> backend.ensure(identifier, 3600)).isInstanceOf(IOException.class)
                .hasMessageContaining("Container initialization failed").hasMessageContaining("exit code 1");

        verify(removeCmd).exec();
    }

    @Test
    void ensure_InitializationFails_CleanupFails_StillThrows() throws Exception {
        String identifier = "test-sandbox";

        mockCreateContainerFlow("container-id-1");
        mockExecFlow("container-id-1", "exec-init", "", "error", 1);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-id-1")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        doThrow(new RuntimeException("cleanup failed")).when(removeCmd).exec();

        assertThatThrownBy(() -> backend.ensure(identifier, 3600)).isInstanceOf(IOException.class)
                .hasMessageContaining("Container initialization failed");
    }

    @Test
    void ensure_ZeroTtl_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> backend.ensure("test-sandbox", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlSeconds must be > 0");
    }

    @Test
    void ensure_NegativeTtl_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> backend.ensure("test-sandbox", -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlSeconds must be > 0");
    }

    // --- delete() tests ---

    @Test
    void delete_RemovesContainer() throws IOException {
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("sandbox-test-sandbox")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        backend.delete("test-sandbox");

        verify(removeCmd).exec();
    }

    @Test
    void delete_NotFoundException_Ignored() throws IOException {
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("sandbox-test-sandbox")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        doThrow(new NotFoundException("not found")).when(removeCmd).exec();

        backend.delete("test-sandbox");
    }

    @Test
    void delete_NullIdentifier_ThrowsNullPointerException() {
        assertThatThrownBy(() -> backend.delete(null)).isInstanceOf(NullPointerException.class);
    }

    // --- restart() tests ---

    @Test
    void restart_DeletesThenEnsures() throws Exception {
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("sandbox-test-sandbox")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        mockCreateContainerFlow("new-container-id");
        mockExecFlow("new-container-id", "exec-init", "", "", 0);

        Sandbox sandbox = backend.restart("test-sandbox", 1800);

        verify(removeCmd).exec();
        assertThat(sandbox.getSandboxId()).isEqualTo("new-container-id");
    }

    // --- exec() tests ---

    @Test
    void exec_ReturnsStdoutAndStderr() throws Exception {
        ExecParams params = ExecParams.builder().command("echo hello").timeoutMs(5000).build();

        mockExecFlow("container-1", "exec-1", "hello\n", "warning\n", 0);

        ExecResult result = backend.exec("container-1", params);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).isEqualTo("hello\n");
        assertThat(result.getStderr()).isEqualTo("warning\n");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exec_WithCwd_WrapsCdCommand() throws Exception {
        ExecParams params = ExecParams.builder().command("ls").cwd("/workspace").timeoutMs(5000).build();

        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        mockExecFlowWithCaptors("container-1", "exec-1", cmdCaptor, null, null, 0);

        backend.exec("container-1", params);

        String[] cmd = cmdCaptor.getValue();
        assertThat(cmd).containsExactly("sh", "-c", "cd '/workspace' && ls");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exec_WithCwdContainingSingleQuote_EscapesProperly() throws Exception {
        ExecParams params = ExecParams.builder().command("ls").cwd("/workspace/it's here").timeoutMs(5000).build();

        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        mockExecFlowWithCaptors("container-1", "exec-1", cmdCaptor, null, null, 0);

        backend.exec("container-1", params);

        String[] cmd = cmdCaptor.getValue();
        assertThat(cmd).containsExactly("sh", "-c", "cd '/workspace/it'\\''s here' && ls");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exec_WithSandboxUser_UsesConfiguredUser() throws Exception {
        ExecParams params = ExecParams.builder().command("whoami").asUser(SandboxUser.SANDBOX).timeoutMs(5000).build();

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        mockExecFlowWithCaptors("container-1", "exec-1", null, userCaptor, null, 0);

        backend.exec("container-1", params);

        assertThat(userCaptor.getValue()).isEqualTo("sandbox");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exec_WithRootUser_UsesRoot() throws Exception {
        ExecParams params = ExecParams.builder().command("whoami").asUser(SandboxUser.ROOT).timeoutMs(5000).build();

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        mockExecFlowWithCaptors("container-1", "exec-1", null, userCaptor, null, 0);

        backend.exec("container-1", params);

        assertThat(userCaptor.getValue()).isEqualTo("root");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exec_WithEnv_PassesEnvironmentVariables() throws Exception {
        ExecParams params = ExecParams.builder().command("env").env(Map.of("FOO", "bar")).timeoutMs(5000).build();

        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        mockExecFlowWithCaptors("container-1", "exec-1", null, null, envCaptor, 0);

        backend.exec("container-1", params);

        assertThat(envCaptor.getValue()).contains("FOO=bar");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exec_Timeout_ThrowsIOException() throws Exception {
        ExecParams params = ExecParams.builder().command("sleep 999").timeoutMs(100).build();

        ExecCreateCmd execCreateCmd = mockExecCreateCmd("container-1", "exec-1");

        ExecStartCmd execStartCmd = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd("exec-1")).thenReturn(execStartCmd);
        ResultCallback.Adapter<Frame> callback = mock(ResultCallback.Adapter.class);
        when(execStartCmd.exec(any(ResultCallback.Adapter.class))).thenReturn(callback);
        when(callback.awaitCompletion(100, TimeUnit.MILLISECONDS)).thenReturn(false);

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
    @SuppressWarnings("unchecked")
    void exec_OutputExceedsMaxOutputBytes_TruncatesAndWarns() throws Exception {
        int maxOutput = 50;
        ExecParams params = ExecParams.builder().command("echo large").timeoutMs(5000).maxOutputBytes(maxOutput)
                .build();

        String largeOutput = "A".repeat(100);
        mockExecFlow("container-1", "exec-1", largeOutput, "", 0);

        ExecResult result = backend.exec("container-1", params);

        assertThat(result.getStdout().length()).isLessThanOrEqualTo(largeOutput.length());
        assertThat(result.getStderr()).contains("[WARNING] Output truncated");
    }

    @Test
    void exec_WithInvalidEnvKey_ThrowsIOException() {
        ExecParams params = ExecParams.builder().command("echo").env(Map.of("INVALID=KEY", "value")).timeoutMs(5000)
                .build();

        assertThatThrownBy(() -> backend.exec("container-1", params)).isInstanceOf(IOException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class).rootCause()
                .hasMessageContaining("Invalid environment variable name");
    }

    @Test
    void exec_WithEmptyEnvKey_ThrowsIOException() {
        ExecParams params = ExecParams.builder().command("echo").env(Map.of("", "value")).timeoutMs(5000).build();

        assertThatThrownBy(() -> backend.exec("container-1", params)).isInstanceOf(IOException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class).rootCause()
                .hasMessageContaining("Invalid environment variable name");
    }

    // --- copyArtifacts() tests ---

    @Test
    void copyArtifacts_DelegatesToDockerClient() throws IOException {
        CopyArchiveFromContainerCmd copyCmd = mock(CopyArchiveFromContainerCmd.class);
        when(dockerClient.copyArchiveFromContainerCmd("container-1", "/artifacts")).thenReturn(copyCmd);
        InputStream tarStream = new ByteArrayInputStream(new byte[0]);
        when(copyCmd.exec()).thenReturn(tarStream);

        InputStream result = backend.copyArtifacts("container-1");

        assertThat(result).isSameAs(tarStream);
    }

    @Test
    void copyArtifacts_NullSandboxId_ThrowsNullPointerException() {
        assertThatThrownBy(() -> backend.copyArtifacts(null)).isInstanceOf(NullPointerException.class);
    }

    // --- copyToSandbox() tests ---

    @Test
    void copyToSandbox_DelegatesToDockerClient() throws IOException {
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class);
        when(dockerClient.copyArchiveToContainerCmd("container-1")).thenReturn(copyCmd);
        when(copyCmd.withTarInputStream(any(InputStream.class))).thenReturn(copyCmd);
        when(copyCmd.withRemotePath("/workspace")).thenReturn(copyCmd);

        InputStream tarStream = new ByteArrayInputStream(new byte[0]);
        backend.copyToSandbox("container-1", tarStream, "/workspace");

        verify(copyCmd).exec();
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
    @SuppressWarnings("unchecked")
    void count_ReturnsContainerCount() throws IOException {
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);

        Container c1 = mock(Container.class);
        Container c2 = mock(Container.class);
        when(listCmd.exec()).thenReturn(List.of(c1, c2));

        assertThat(backend.count()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void count_EmptyList_ReturnsZero() throws IOException {
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());

        assertThat(backend.count()).isZero();
    }

    // --- reapExpired() tests ---

    @Test
    @SuppressWarnings("unchecked")
    void reapExpired_RemovesExpiredContainers() throws IOException {
        Instant past = Instant.now().minusSeconds(3600);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-1");
        when(container.getLabels()).thenReturn(Map.of(DockerSandboxBackend.LABEL_IDENTIFIER, "test-id",
                DockerSandboxBackend.LABEL_EXPIRES_AT, DateTimeFormatter.ISO_INSTANT.format(past)));

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(container));

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-1")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        int removed = backend.reapExpired();

        assertThat(removed).isEqualTo(1);
        verify(removeCmd).exec();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reapExpired_SkipsNonExpiredContainers() throws IOException {
        Instant future = Instant.now().plusSeconds(3600);

        Container container = mock(Container.class);
        when(container.getLabels()).thenReturn(Map.of(DockerSandboxBackend.LABEL_IDENTIFIER, "test-id",
                DockerSandboxBackend.LABEL_EXPIRES_AT, DateTimeFormatter.ISO_INSTANT.format(future)));

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(container));

        int removed = backend.reapExpired();

        assertThat(removed).isZero();
        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reapExpired_IndividualFailure_ContinuesReaping() throws IOException {
        Instant past = Instant.now().minusSeconds(3600);

        Container c1 = mock(Container.class);
        when(c1.getId()).thenReturn("c1");
        when(c1.getLabels()).thenReturn(Map.of(DockerSandboxBackend.LABEL_IDENTIFIER, "id1",
                DockerSandboxBackend.LABEL_EXPIRES_AT, DateTimeFormatter.ISO_INSTANT.format(past)));

        Container c2 = mock(Container.class);
        when(c2.getId()).thenReturn("c2");
        when(c2.getLabels()).thenReturn(Map.of(DockerSandboxBackend.LABEL_IDENTIFIER, "id2",
                DockerSandboxBackend.LABEL_EXPIRES_AT, DateTimeFormatter.ISO_INSTANT.format(past)));

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(c1, c2));

        RemoveContainerCmd removeCmd1 = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("c1")).thenReturn(removeCmd1);
        when(removeCmd1.withForce(true)).thenReturn(removeCmd1);
        doThrow(new RuntimeException("remove failed")).when(removeCmd1).exec();

        RemoveContainerCmd removeCmd2 = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("c2")).thenReturn(removeCmd2);
        when(removeCmd2.withForce(true)).thenReturn(removeCmd2);

        int removed = backend.reapExpired();

        assertThat(removed).isEqualTo(1);
        verify(removeCmd1).exec();
        verify(removeCmd2).exec();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reapExpired_NoIdentifierLabel_SkipsContainer() throws IOException {
        Container container = mock(Container.class);
        when(container.getLabels()).thenReturn(Map.of(DockerSandboxBackend.LABEL_ROLE, "sandbox"));

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(container));

        int removed = backend.reapExpired();

        assertThat(removed).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reapExpired_InvalidExpiresAtLabel_TreatsAsExpired() throws IOException {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-bad-label");
        when(container.getLabels()).thenReturn(Map.of(DockerSandboxBackend.LABEL_IDENTIFIER, "bad-id",
                DockerSandboxBackend.LABEL_EXPIRES_AT, "not-a-valid-timestamp"));

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(container));

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-bad-label")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        int removed = backend.reapExpired();

        assertThat(removed).isEqualTo(1);
        verify(removeCmd).exec();
    }

    // --- close() tests ---

    @Test
    void close_ClosesDockerClient() throws IOException {
        backend.close();

        verify(dockerClient).close();
    }

    // --- Helper methods ---

    private void mockCreateContainerFlow(String containerId) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withLabels(any())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any(HostConfig.class))).thenReturn(createCmd);
        when(createCmd.withCmd(anyString(), anyString())).thenReturn(createCmd);

        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createCmd.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn(containerId);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(containerId)).thenReturn(startCmd);
    }

    private void mockCreateContainerConflict() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withLabels(any())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any(HostConfig.class))).thenReturn(createCmd);
        when(createCmd.withCmd(anyString(), anyString())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("container already exists"));
    }

    private void mockInspectContainer(String containerName, String containerId, boolean running) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(containerName)).thenReturn(inspectCmd);
        InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
        when(inspectCmd.exec()).thenReturn(inspectResponse);

        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(inspectResponse.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(running);
        when(inspectResponse.getId()).thenReturn(containerId);
        ContainerConfig containerConfig = mock(ContainerConfig.class);
        when(inspectResponse.getConfig()).thenReturn(containerConfig);
        when(containerConfig.getImage()).thenReturn("ubuntu:22.04");
        when(inspectResponse.getCreated()).thenReturn(Instant.now().toString());
    }

    @SuppressWarnings("unchecked")
    private void mockExecFlow(String sandboxId, String execId, String stdout, String stderr, int exitCode)
            throws InterruptedException {
        ExecCreateCmd execCreateCmd = mockExecCreateCmd(sandboxId, execId);

        ExecStartCmd execStartCmd = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd(execId)).thenReturn(execStartCmd);

        when(execStartCmd.exec(any(ResultCallback.Adapter.class))).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> cb = invocation.getArgument(0);
            if (stdout != null && !stdout.isEmpty()) {
                cb.onNext(new Frame(StreamType.STDOUT, stdout.getBytes(StandardCharsets.UTF_8)));
            }
            if (stderr != null && !stderr.isEmpty()) {
                cb.onNext(new Frame(StreamType.STDERR, stderr.getBytes(StandardCharsets.UTF_8)));
            }
            cb.onComplete();
            return cb;
        });

        InspectExecCmd inspectExecCmd = mock(InspectExecCmd.class);
        when(dockerClient.inspectExecCmd(execId)).thenReturn(inspectExecCmd);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecCmd.exec()).thenReturn(inspectExecResponse);
        when(inspectExecResponse.getExitCodeLong()).thenReturn((long) exitCode);
    }

    @SuppressWarnings("unchecked")
    private void mockExecFlowWithCaptors(String sandboxId, String execId, ArgumentCaptor<String[]> cmdCaptor,
            ArgumentCaptor<String> userCaptor, ArgumentCaptor<List<String>> envCaptor, int exitCode)
            throws InterruptedException {
        ExecCreateCmd execCreateCmd = mock(ExecCreateCmd.class);
        when(dockerClient.execCreateCmd(sandboxId)).thenReturn(execCreateCmd);

        if (cmdCaptor != null) {
            when(execCreateCmd.withCmd(cmdCaptor.capture())).thenReturn(execCreateCmd);
        } else {
            when(execCreateCmd.withCmd(any(String[].class))).thenReturn(execCreateCmd);
        }
        if (envCaptor != null) {
            when(execCreateCmd.withEnv(envCaptor.capture())).thenReturn(execCreateCmd);
        } else {
            when(execCreateCmd.withEnv(anyList())).thenReturn(execCreateCmd);
        }
        if (userCaptor != null) {
            when(execCreateCmd.withUser(userCaptor.capture())).thenReturn(execCreateCmd);
        } else {
            when(execCreateCmd.withUser(anyString())).thenReturn(execCreateCmd);
        }
        when(execCreateCmd.withAttachStdout(anyBoolean())).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStderr(anyBoolean())).thenReturn(execCreateCmd);

        ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
        when(execCreateCmd.exec()).thenReturn(execCreateResponse);
        when(execCreateResponse.getId()).thenReturn(execId);

        ExecStartCmd execStartCmd = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd(execId)).thenReturn(execStartCmd);
        ResultCallback.Adapter<Frame> callback = mock(ResultCallback.Adapter.class);
        when(execStartCmd.exec(any(ResultCallback.Adapter.class))).thenReturn(callback);
        when(callback.awaitCompletion(any(Long.class), any(TimeUnit.class))).thenReturn(true);

        InspectExecCmd inspectExecCmd = mock(InspectExecCmd.class);
        when(dockerClient.inspectExecCmd(execId)).thenReturn(inspectExecCmd);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecCmd.exec()).thenReturn(inspectExecResponse);
        when(inspectExecResponse.getExitCodeLong()).thenReturn((long) exitCode);
    }

    private ExecCreateCmd mockExecCreateCmd(String sandboxId, String execId) {
        ExecCreateCmd execCreateCmd = mock(ExecCreateCmd.class);
        when(dockerClient.execCreateCmd(sandboxId)).thenReturn(execCreateCmd);
        when(execCreateCmd.withCmd(any(String[].class))).thenReturn(execCreateCmd);
        when(execCreateCmd.withEnv(anyList())).thenReturn(execCreateCmd);
        when(execCreateCmd.withUser(anyString())).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStdout(anyBoolean())).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStderr(anyBoolean())).thenReturn(execCreateCmd);

        ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
        when(execCreateCmd.exec()).thenReturn(execCreateResponse);
        when(execCreateResponse.getId()).thenReturn(execId);

        return execCreateCmd;
    }
}
