package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The pure parts of the k8s tier's PID 1 check: PID 1's arguments from its NUL-separated {@code /proc/1/cmdline}
 * ({@link AbstractShellEngineK8sIT#argv}), and which of them are task-executor's
 * ({@link ShellEngineContractK8sPoolIT}).
 */
class PoolPid1Test {

    @Test
    void taskExecutorIsRecognizedWhereverItIsInstalled() {
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(
                List.of("/opt/opensandbox/task-executor", "-listen-addr=0.0.0.0:5758", "-log-dir=/tmp"))).isTrue();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(List.of("/usr/local/bin/task-executor"))).isTrue();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(List.of("./task-executor", "-listen-addr=:5758")))
                .isTrue();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(List.of("task-executor"))).isTrue();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(List.of("/opt/open sandbox/task-executor", "-x")))
                .as("a directory with a space in its name").isTrue();
    }

    @Test
    void aShellThatStartsTaskExecutorIsNotTaskExecutor() {
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(
                List.of("/bin/sh", "-c", "/opt/opensandbox/task-executor -listen-addr=0.0.0.0:5758 -log-dir=/tmp")))
                .isFalse();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(List.of("/opt/opensandbox/task-executor-wrapper")))
                .isFalse();
        assertThat(ShellEngineContractK8sPoolIT
                .isTaskExecutor(List.of("/opt/opensandbox/execd", "--init", "--", "tail", "-f", "/dev/null")))
                .isFalse();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(List.of("/opt/task-executor dir/sh"))).isFalse();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor(List.of())).isFalse();
    }

    @Test
    void argvIsSplitOnNulsOnlySoASpaceOrANewlineStaysInItsArgument() {
        assertThat(AbstractShellEngineK8sIT.argv(od("/opt/open sandbox/task-executor\0-a=b c\0line\nbreak\0")))
                .containsExactly("/opt/open sandbox/task-executor", "-a=b c", "line\nbreak");
        assertThat(AbstractShellEngineK8sIT.argv(od("/bin/sh\0-c\0\0x"))).as("an empty argument; no trailing NUL")
                .containsExactly("/bin/sh", "-c", "", "x");
        assertThat(AbstractShellEngineK8sIT.argv(od(""))).as("a kernel thread's empty cmdline").isEmpty();
    }

    /** {@code text} as {@code od -An -v -tx1} prints it: sixteen hex bytes a line, each after a space. */
    private static String od(String text) {
        final String hex = HexFormat.ofDelimiter(" ").formatHex(text.getBytes(StandardCharsets.UTF_8));
        final StringBuilder out = new StringBuilder();
        final String[] bytes = hex.isEmpty() ? new String[0] : hex.split(" ");
        for (int i = 0; i < bytes.length; i++) {
            out.append(' ').append(bytes[i]).append(i % 16 == 15 ? "\n" : "");
        }
        return out.append('\n').toString();
    }
}
