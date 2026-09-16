package at.aimon.sandbox.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.sandbox.artifact.TarExtractor;
import at.aimon.sandbox.artifact.TarTestHelper;
import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.LocalSandboxLock;
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.run.InMemoryRunStore;
import at.aimon.sandbox.run.RunManager;

@ExtendWith(MockitoExtension.class)
class RunSandboxToolTest {

    @Mock
    private SandboxBackend backend;

    @Mock
    private VirtualFileSystem fileSystem;

    private RunManager runManager;
    private TarExtractor tarExtractor;
    private SandboxConfig config;
    private SandboxLock sandboxLock;
    private RunSandboxTool tool;

    @BeforeEach
    void setUp() {
        runManager = new RunManager(new InMemoryRunStore());
        tarExtractor = new TarExtractor();
        config = SandboxConfig.builder().build();
        sandboxLock = new LocalSandboxLock();
        tool = new RunSandboxTool(backend, runManager, tarExtractor, config, sandboxLock, null);
    }

    private Sandbox createSandbox() {
        Instant now = Instant.now();
        return Sandbox.builder().identifier("my-sandbox").sandboxId("container-1").name("sandbox-my-sandbox")
                .image("ubuntu:22.04").createdAt(now).expiresAt(now.plusSeconds(1800)).build();
    }

    @Test
    void getDefinition_ReturnsCorrectName() {
        assertThat(tool.getDefinition().getName()).isEqualTo("RunSandbox");
    }

    @Test
    void execute_SingleCommand_ReturnsSuccess() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(0).stdout("hello").build());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "echo hello")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("completed");
        assertThat(result.getContent()).contains("1 succeeded");
    }

    @Test
    void execute_InvalidIdentifier_ReturnsError() {
        ToolInput input = ToolInput
                .of(Map.of("identifier", "bad id!", "commands", List.of(Map.of("shell", "echo hello"))));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    void execute_EmptyCommands_ReturnsError() {
        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands", List.of()));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Commands must not be empty");
    }

    @Test
    void execute_NullCommands_ReturnsError() {
        ToolInput input = ToolInput.of("identifier", "my-sandbox");

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        // An absent list is now a binding violation rather than the tool's own "must not be empty" — the parameter is
        // declared required, so it never reaches doExecute. The empty-list case above still hits the tool's check.
        assertThat(result.getContent())
                .contains("Parameter 'commands' is required (type: array). " + "The tool was not executed.");
    }

    @Test
    void execute_UnknownCommandKey_ReturnsPositionalError() {
        // Each element is a closed object now: a misspelled key used to be dropped silently, taking the timeout with
        // it. The violation names the offending element, not just the parameter.
        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "echo hi"), Map.of("shell", "echo bye", "timeoutMs", 5))));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("commands[1].timeoutMs");
    }

    @Test
    void execute_CommandFailure_StopsByDefault() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(1).stderr("error").build());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "fail"), Map.of("shell", "never-reached")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("failed");
        assertThat(result.getContent()).contains("1 executed");
    }

    @Test
    void execute_BackendEnsureFailure_ReturnsError() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenThrow(new IOException("docker unavailable"));

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "echo hello")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Run failed");
    }

    @Test
    void execute_ContinueOnError_ExecutesAllCommands() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(1).stderr("error").build())
                .thenReturn(ExecResult.builder().exitCode(0).stdout("ok").build());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "fail-cmd"), Map.of("shell", "second-cmd")), "continue_on_error", true,
                "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("2 executed");
    }

    @Test
    void execute_AllowFailureOnCommand_ContinuesToNextCommand() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(1).stderr("expected failure").build())
                .thenReturn(ExecResult.builder().exitCode(0).stdout("success").build());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "optional-cmd", "allow_failure", true), Map.of("shell", "main-cmd")),
                "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("completed");
        assertThat(result.getContent()).contains("2 executed");
    }

    @Test
    void execute_MultipleCommandsAllSucceed_ReportsAllSucceeded() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(0).stdout("out1").build())
                .thenReturn(ExecResult.builder().exitCode(0).stdout("out2").build())
                .thenReturn(ExecResult.builder().exitCode(0).stdout("out3").build());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "cmd1"), Map.of("shell", "cmd2"), Map.of("shell", "cmd3")), "lock_sandbox",
                false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("completed");
        assertThat(result.getContent()).contains("3 succeeded");
        assertThat(result.getContent()).contains("0 failed");
    }

    @Test
    void execute_WithFileSystem_ExtractsArtifactsToVfs() throws IOException {
        RunSandboxTool toolWithVfs = new RunSandboxTool(backend, runManager, tarExtractor, config, sandboxLock,
                fileSystem);

        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(0).stdout("done").build());
        when(backend.copyArtifacts("container-1"))
                .thenReturn(new ByteArrayInputStream(TarTestHelper.createTarWithFile("output.txt", "result data")));

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "echo done")), "lock_sandbox", false));

        ToolResult result = toolWithVfs.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("1 files extracted");

        verify(fileSystem).write(any(String.class), any(InputStream.class), eq(11L));
    }

    @Test
    void execute_WithFileSystem_RegistersArtifactsInCollector() throws IOException {
        RunSandboxTool toolWithVfs = new RunSandboxTool(backend, runManager, tarExtractor, config, sandboxLock,
                fileSystem);

        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(0).stdout("done").build());
        when(backend.copyArtifacts("container-1"))
                .thenReturn(new ByteArrayInputStream(TarTestHelper.createTarWithFile("report.csv", "a,b,c")));

        ArtifactCollector collector = new ArtifactCollector();
        ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "generate")), "lock_sandbox", false));

        ToolResult result = toolWithVfs.execute(input, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(collector.getArtifacts()).hasSize(1);
        assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("report.csv");
        assertThat(collector.getArtifacts().get(0).getSize()).isEqualTo(5);
        assertThat(collector.getArtifacts().get(0).getPath()).contains("/sandbox-artifacts/my-sandbox/");
    }

    @Test
    void execute_ArtifactExtractionFails_StillReturnsSuccess() throws IOException {
        RunSandboxTool toolWithVfs = new RunSandboxTool(backend, runManager, tarExtractor, config, sandboxLock,
                fileSystem);

        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(0).stdout("ok").build());
        when(backend.copyArtifacts("container-1")).thenThrow(new IOException("connection reset"));

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands", List.of(Map.of("shell", "work")),
                "lock_sandbox", false));

        ToolResult result = toolWithVfs.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("completed");
        assertThat(result.getContent()).contains("Artifacts extraction error");
        assertThat(result.getContent()).contains("connection reset");
    }

    @Test
    void execute_NullFileSystem_SkipsArtifactExtraction() throws IOException {
        when(backend.ensure(eq("my-sandbox"), anyInt())).thenReturn(createSandbox());
        when(backend.exec(eq("container-1"), any(ExecParams.class)))
                .thenReturn(ExecResult.builder().exitCode(0).stdout("done").build());

        ToolInput input = ToolInput.of(Map.of("identifier", "my-sandbox", "commands",
                List.of(Map.of("shell", "echo done")), "lock_sandbox", false));

        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("completed");
        assertThat(result.getContent()).doesNotContain("files extracted");
    }

}
