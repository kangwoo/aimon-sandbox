package at.aimon.sandbox.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecResultTest {

    @Test
    void builder_MinimalFields_DefaultsToEmptyStrings() {
        ExecResult result = ExecResult.builder().exitCode(0).build();

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).isEmpty();
        assertThat(result.getStderr()).isEmpty();
    }

    @Test
    void builder_AllFields_CreatesInstance() {
        ExecResult result = ExecResult.builder().exitCode(1).stdout("output").stderr("error").build();

        assertThat(result.getExitCode()).isEqualTo(1);
        assertThat(result.getStdout()).isEqualTo("output");
        assertThat(result.getStderr()).isEqualTo("error");
    }

    @Test
    void builder_NullStdout_DefaultsToEmpty() {
        ExecResult result = ExecResult.builder().exitCode(0).stdout(null).build();
        assertThat(result.getStdout()).isEmpty();
    }

    @Test
    void builder_NullStderr_DefaultsToEmpty() {
        ExecResult result = ExecResult.builder().exitCode(0).stderr(null).build();
        assertThat(result.getStderr()).isEmpty();
    }

    @Test
    void equals_SameFields_AreEqual() {
        ExecResult a = ExecResult.builder().exitCode(0).stdout("out").stderr("err").build();
        ExecResult b = ExecResult.builder().exitCode(0).stdout("out").stderr("err").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_DifferentExitCode_AreNotEqual() {
        ExecResult a = ExecResult.builder().exitCode(0).build();
        ExecResult b = ExecResult.builder().exitCode(1).build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_DifferentStdout_AreNotEqual() {
        ExecResult a = ExecResult.builder().exitCode(0).stdout("a").build();
        ExecResult b = ExecResult.builder().exitCode(0).stdout("b").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_Null_IsNotEqual() {
        ExecResult result = ExecResult.builder().exitCode(0).build();
        assertThat(result).isNotEqualTo(null);
    }

    @Test
    void toString_ContainsExitCode() {
        ExecResult result = ExecResult.builder().exitCode(42).build();
        assertThat(result.toString()).contains("42");
    }
}
