package at.aimon.sandbox.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.lock.SandboxLock;

@ExtendWith(MockitoExtension.class)
class DeleteSandboxToolTest {

    @Mock
    private SandboxBackend backend;

    @Mock
    private SandboxLock sandboxLock;

    private DeleteSandboxTool tool;

    @BeforeEach
    void setUp() {
        tool = new DeleteSandboxTool(backend, sandboxLock);
    }

    @Test
    void constructor_NullBackend_ThrowsException() {
        assertThatThrownBy(() -> new DeleteSandboxTool(null, sandboxLock)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_NullSandboxLock_ThrowsException() {
        assertThatThrownBy(() -> new DeleteSandboxTool(backend, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void getDefinition_ReturnsCorrectName() {
        assertThat(tool.getDefinition().getName()).isEqualTo("DeleteSandbox");
    }

    @Test
    void execute_ValidIdentifier_DeletesAndReturnsSuccess() throws IOException {
        ToolInput input = ToolInput.of("identifier", "my-sandbox");
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("my-sandbox");
        verify(backend).delete("my-sandbox");
    }

    @Test
    void execute_ValidIdentifier_RemovesLockAfterDelete() throws IOException {
        ToolInput input = ToolInput.of("identifier", "my-sandbox");
        tool.execute(input, ToolContext.empty());

        verify(backend).delete("my-sandbox");
        verify(sandboxLock).removeLock("my-sandbox");
    }

    @Test
    void execute_InvalidIdentifier_ReturnsError() {
        ToolInput input = ToolInput.of("identifier", "invalid identifier!");
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    void execute_BackendFailure_ReturnsError() throws IOException {
        doThrow(new IOException("connection refused")).when(backend).delete("my-sandbox");

        ToolInput input = ToolInput.of("identifier", "my-sandbox");
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Delete failed");
    }
}
