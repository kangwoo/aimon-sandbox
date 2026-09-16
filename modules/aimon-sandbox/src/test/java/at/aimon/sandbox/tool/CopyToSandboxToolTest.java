package at.aimon.sandbox.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.sandbox.artifact.TarCreator;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.LocalSandboxLock;
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.model.Sandbox;

@ExtendWith(MockitoExtension.class)
class CopyToSandboxToolTest {

    @Mock
    private SandboxBackend backend;

    @Mock
    private VirtualFileSystem fileSystem;

    private TarCreator tarCreator;
    private SandboxConfig config;
    private SandboxLock sandboxLock;
    private CopyToSandboxTool tool;

    @BeforeEach
    void setUp() {
        tarCreator = new TarCreator();
        config = SandboxConfig.builder().build();
        sandboxLock = new LocalSandboxLock();
        tool = new CopyToSandboxTool(backend, tarCreator, config, sandboxLock, fileSystem);
    }

    private Sandbox createSandbox() {
        Instant now = Instant.now();
        return Sandbox.builder().identifier("my-sandbox").sandboxId("container-1").name("sandbox-my-sandbox")
                .image("ubuntu:22.04").createdAt(now).expiresAt(now.plusSeconds(1800)).build();
    }

    private FileMetadata createMetadata(String path, long size) {
        Instant now = Instant.now();
        return FileMetadata.builder().path(path).size(size).createdAt(now).modifiedAt(now).build();
    }

    @Test
    void getDefinition_ReturnsCorrectName() {
        assertThat(tool.getDefinition().getName()).isEqualTo("CopyToSandbox");
    }

    @Test
    void execute_SingleFile_ReturnsSuccess() throws IOException {
        byte[] content = "Hello, World!".getBytes();
        when(fileSystem.getMetadata("/data/hello.txt")).thenReturn(createMetadata("/data/hello.txt", content.length));
        when(fileSystem.read("/data/hello.txt")).thenReturn(new ByteArrayInputStream(content));
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/hello.txt")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Copied 1 file(s)");
        assertThat(result.getContent()).contains("/workspace");
        verify(backend).copyToSandbox(eq("container-1"), any(InputStream.class), eq("/workspace"));
    }

    @Test
    void execute_MultipleFiles_ReturnsSuccess() throws IOException {
        stubFile("/data/a.txt", "aaa".getBytes());
        stubFile("/data/b.txt", "bbb".getBytes());
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/a.txt"), Map.of("source", "/data/b.txt")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Copied 2 file(s)");
    }

    @Test
    void execute_CustomDestPath_UsesProvidedPath() throws IOException {
        stubFile("/data/file.txt", "content".getBytes());
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/file.txt")), "dest_path", "/tmp/upload", "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("/tmp/upload");
        verify(backend).copyToSandbox(eq("container-1"), any(InputStream.class), eq("/tmp/upload"));
    }

    @Test
    void execute_CustomDestName_UsesProvidedName() throws IOException {
        stubFile("/data/original.txt", "data".getBytes());
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/original.txt", "dest_name", "renamed.txt")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Copied 1 file(s)");
    }

    @Test
    void execute_NullFileSystem_ReturnsError() {
        CopyToSandboxTool toolNoVfs = new CopyToSandboxTool(backend, tarCreator, config, sandboxLock, null);

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/file.txt")), "lock_sandbox", false));

        ToolResult result = toolNoVfs.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("VirtualFileSystem is not available");
    }

    @Test
    void execute_EmptyFilesList_ReturnsError() {
        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files", List.of()));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Files list must not be empty");
    }

    @Test
    void execute_NullFilesList_ReturnsError() {
        ToolInput input = ToolInput.of("identifier", "my-sandbox");

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        // An absent list is now a binding violation rather than the tool's own "must not be empty" — the parameter is
        // declared required, so it never reaches doExecute. The empty-list case above still hits the tool's check.
        assertThat(result.getContent())
                .contains("Parameter 'files' is required (type: array). The tool was not executed.");
    }

    @Test
    void execute_FileEntryMissingSource_NamesTheOffendingElement() {
        // The old loop threw "File entry missing required 'source' field" with no way to tell which entry it meant.
        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/ok.txt"), Map.of("dest_name", "renamed.txt"))));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("files[1].source");
    }

    @Test
    void execute_InvalidIdentifier_ReturnsError() {
        ToolInput input = ToolInput
                .of(Map.of("identifier", "bad id!", "files", List.of(Map.of("source", "/data/file.txt"))));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    void execute_BackendEnsureFailure_ReturnsError() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenThrow(new IOException("docker unavailable"));

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/file.txt")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Copy to sandbox failed");
    }

    @Test
    void execute_BackendCopyToSandboxFailure_ReturnsError() throws IOException {
        stubFile("/data/file.txt", "content".getBytes());
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        doThrow(new IOException("connection reset")).when(backend).copyToSandbox(eq("container-1"),
                any(InputStream.class), eq("/workspace"));

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "files",
                List.of(Map.of("source", "/data/file.txt")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Copy to sandbox failed");
    }

    private void stubFile(String path, byte[] content) {
        when(fileSystem.getMetadata(path)).thenReturn(createMetadata(path, content.length));
        when(fileSystem.read(path)).thenReturn(new ByteArrayInputStream(content));
    }
}
