package at.aimon.sandbox.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.LocalSandboxLock;
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.model.Sandbox;

@ExtendWith(MockitoExtension.class)
class RestartSandboxToolTest {

    @Mock
    private SandboxBackend backend;

    private SandboxConfig config;
    private SandboxLock sandboxLock;
    private RestartSandboxTool tool;

    @BeforeEach
    void setUp() {
        config = SandboxConfig.builder().build();
        sandboxLock = new LocalSandboxLock();
        tool = new RestartSandboxTool(backend, config, sandboxLock);
    }

    @Test
    void getDefinition_ReturnsCorrectName() {
        assertThat(tool.getDefinition().getName()).isEqualTo("RestartSandbox");
    }

    @Test
    void execute_ValidIdentifier_RestartsAndReturnsSuccess() throws IOException {
        Instant now = Instant.now();
        Sandbox sandbox = Sandbox.builder().identifier("my-sandbox").sandboxId("new-container")
                .name("sandbox-my-sandbox").image("ubuntu:22.04").createdAt(now).expiresAt(now.plusSeconds(1800))
                .build();
        when(backend.restart(eq("my-sandbox"), anyInt())).thenReturn(sandbox);

        ToolInput input = ToolInput.of("identifier", "my-sandbox");
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("my-sandbox");
        assertThat(result.getContent()).contains("new-container");
    }

    @Test
    void execute_InvalidIdentifier_ReturnsError() {
        ToolInput input = ToolInput.of("identifier", "bad id!");
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    void execute_BackendFailure_ReturnsError() throws IOException {
        when(backend.restart(eq("my-sandbox"), anyInt())).thenThrow(new IOException("restart failed"));

        ToolInput input = ToolInput.of("identifier", "my-sandbox");
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Restart failed");
    }

    @Test
    void execute_TtlCappedAtMax() throws IOException {
        Instant now = Instant.now();
        Sandbox sandbox = Sandbox.builder().identifier("my-sandbox").sandboxId("container-1").name("sandbox-my-sandbox")
                .image("ubuntu:22.04").createdAt(now).expiresAt(now.plusSeconds(86400)).build();
        when(backend.restart(eq("my-sandbox"), eq(86400))).thenReturn(sandbox);

        ToolInput input = ToolInput.of("identifier", "my-sandbox", "ttl_seconds", 999999);
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void execute_LockDisabled_RestartsWithoutLock() throws IOException {
        Instant now = Instant.now();
        Sandbox sandbox = Sandbox.builder().identifier("my-sandbox").sandboxId("container-1").name("sandbox-my-sandbox")
                .image("ubuntu:22.04").createdAt(now).expiresAt(now.plusSeconds(1800)).build();
        when(backend.restart(eq("my-sandbox"), anyInt())).thenReturn(sandbox);

        ToolInput input = ToolInput.of("identifier", "my-sandbox", "lock_sandbox", false);
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
    }
}
