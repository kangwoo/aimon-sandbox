package at.aimon.sandbox.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommandResultTest {

    @Test
    void builder_MinimalFields_CreatesInstance() {
        CommandResult result = CommandResult.builder().index(0).command("echo hello").exitCode(0).build();

        assertThat(result.getIndex()).isZero();
        assertThat(result.getCommand()).isEqualTo("echo hello");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).isNull();
        assertThat(result.getStderr()).isNull();
        assertThat(result.getError()).isNull();
        assertThat(result.getDurationMs()).isZero();
    }

    @Test
    void builder_AllFields_CreatesInstance() {
        CommandResult result = CommandResult.builder().index(2).command("ls -la").exitCode(1).stdout("file.txt")
                .stderr("permission denied").error("exec error").durationMs(500).build();

        assertThat(result.getIndex()).isEqualTo(2);
        assertThat(result.getCommand()).isEqualTo("ls -la");
        assertThat(result.getExitCode()).isEqualTo(1);
        assertThat(result.getStdout()).isEqualTo("file.txt");
        assertThat(result.getStderr()).isEqualTo("permission denied");
        assertThat(result.getError()).isEqualTo("exec error");
        assertThat(result.getDurationMs()).isEqualTo(500);
    }

    @Test
    void builder_NullCommand_ThrowsException() {
        assertThatThrownBy(() -> CommandResult.builder().index(0).exitCode(0).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Command cannot be null");
    }

    @Test
    void equals_SameFields_AreEqual() {
        CommandResult a = CommandResult.builder().index(0).command("echo").exitCode(0).stdout("hi").durationMs(100)
                .build();
        CommandResult b = CommandResult.builder().index(0).command("echo").exitCode(0).stdout("hi").durationMs(100)
                .build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_DifferentIndex_AreNotEqual() {
        CommandResult a = CommandResult.builder().index(0).command("echo").exitCode(0).build();
        CommandResult b = CommandResult.builder().index(1).command("echo").exitCode(0).build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_DifferentExitCode_AreNotEqual() {
        CommandResult a = CommandResult.builder().index(0).command("echo").exitCode(0).build();
        CommandResult b = CommandResult.builder().index(0).command("echo").exitCode(1).build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_Null_IsNotEqual() {
        CommandResult result = CommandResult.builder().index(0).command("echo").exitCode(0).build();
        assertThat(result).isNotEqualTo(null);
    }

    @Test
    void toString_ContainsCommandAndExitCode() {
        CommandResult result = CommandResult.builder().index(1).command("echo hello").exitCode(0).build();
        String str = result.toString();

        assertThat(str).contains("echo hello");
        assertThat(str).contains("exitCode=0");
    }
}
