package at.aimon.sandbox.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CommandInputTest {

    @Test
    void builder_ShellCommand_CreatesInstance() {
        CommandInput cmd = CommandInput.builder().shell("echo hello").build();

        assertThat(cmd.getShell()).isEqualTo("echo hello");
        assertThat(cmd.getArgv()).isNull();
        assertThat(cmd.getCommandString()).isEqualTo("echo hello");
        assertThat(cmd.isAllowFailure()).isFalse();
    }

    @Test
    void builder_ArgvCommand_CreatesInstance() {
        CommandInput cmd = CommandInput.builder().argv(List.of("echo", "hello")).build();

        assertThat(cmd.getShell()).isNull();
        assertThat(cmd.getArgv()).containsExactly("echo", "hello");
        assertThat(cmd.getCommandString()).isEqualTo("echo hello");
    }

    @Test
    void builder_NeitherShellNorArgv_ThrowsException() {
        assertThatThrownBy(() -> CommandInput.builder().build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Either shell or argv must be provided");
    }

    @Test
    void builder_BothShellAndArgv_ThrowsException() {
        assertThatThrownBy(() -> CommandInput.builder().shell("echo").argv(List.of("echo")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only one of shell or argv can be provided");
    }

    @Test
    void builder_WithOptionalFields_CreatesInstance() {
        CommandInput cmd = CommandInput.builder().shell("ls -la").cwd("/workspace").env(Map.of("KEY", "VALUE"))
                .timeoutMs(5000).allowFailure(true).build();

        assertThat(cmd.getCwd()).isEqualTo("/workspace");
        assertThat(cmd.getEnv()).containsEntry("KEY", "VALUE");
        assertThat(cmd.getTimeoutMs()).isEqualTo(5000);
        assertThat(cmd.isAllowFailure()).isTrue();
    }

    @Test
    void env_DefaultsToEmptyMap() {
        CommandInput cmd = CommandInput.builder().shell("echo").build();
        assertThat(cmd.getEnv()).isEmpty();
    }
}
