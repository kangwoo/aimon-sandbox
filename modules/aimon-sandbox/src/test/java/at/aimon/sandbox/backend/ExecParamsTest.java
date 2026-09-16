package at.aimon.sandbox.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.model.SandboxUser;

class ExecParamsTest {

    @Test
    void builder_MinimalFields_CreatesInstance() {
        ExecParams params = ExecParams.builder().command("echo hello").build();

        assertThat(params.getCommand()).isEqualTo("echo hello");
        assertThat(params.getCwd()).isNull();
        assertThat(params.getEnv()).isEmpty();
        assertThat(params.getAsUser()).isNull();
        assertThat(params.getTimeoutMs()).isEqualTo(120_000);
        assertThat(params.getMaxOutputBytes()).isEqualTo(1_048_576);
    }

    @Test
    void builder_AllFields_CreatesInstance() {
        ExecParams params = ExecParams.builder().command("ls -la").cwd("/workspace").env(Map.of("KEY", "VALUE"))
                .asUser(SandboxUser.ROOT).timeoutMs(5000).maxOutputBytes(1024).build();

        assertThat(params.getCommand()).isEqualTo("ls -la");
        assertThat(params.getCwd()).isEqualTo("/workspace");
        assertThat(params.getEnv()).containsEntry("KEY", "VALUE");
        assertThat(params.getAsUser()).isEqualTo(SandboxUser.ROOT);
        assertThat(params.getTimeoutMs()).isEqualTo(5000);
        assertThat(params.getMaxOutputBytes()).isEqualTo(1024);
    }

    @Test
    void builder_NullCommand_ThrowsException() {
        assertThatThrownBy(() -> ExecParams.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Command cannot be null");
    }

    @Test
    void builder_EmptyCommand_ThrowsException() {
        assertThatThrownBy(() -> ExecParams.builder().command("").build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Command cannot be empty");
    }

    @Test
    void env_DefaultsToEmptyMap() {
        ExecParams params = ExecParams.builder().command("echo").build();
        assertThat(params.getEnv()).isEmpty();
    }

    @Test
    void env_IsImmutable() {
        ExecParams params = ExecParams.builder().command("echo").env(Map.of("K", "V")).build();

        assertThatThrownBy(() -> params.getEnv().put("NEW", "VALUE")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equals_SameFields_AreEqual() {
        ExecParams a = ExecParams.builder().command("echo").cwd("/tmp").timeoutMs(5000).build();
        ExecParams b = ExecParams.builder().command("echo").cwd("/tmp").timeoutMs(5000).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_DifferentCommand_AreNotEqual() {
        ExecParams a = ExecParams.builder().command("echo").build();
        ExecParams b = ExecParams.builder().command("ls").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_Null_IsNotEqual() {
        ExecParams params = ExecParams.builder().command("echo").build();
        assertThat(params).isNotEqualTo(null);
    }

    @Test
    void toString_ContainsCommand() {
        ExecParams params = ExecParams.builder().command("echo hello").build();
        assertThat(params.toString()).contains("echo hello");
    }
}
