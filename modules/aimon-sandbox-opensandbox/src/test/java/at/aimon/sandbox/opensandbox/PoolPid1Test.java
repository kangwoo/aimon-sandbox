package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The pure part of {@link ShellEngineContractK8sPoolIT}'s PID 1 check: which command line is task-executor's. */
class PoolPid1Test {

    @Test
    void taskExecutorIsRecognizedWhereverItIsInstalled() {
        assertThat(ShellEngineContractK8sPoolIT
                .isTaskExecutor("/opt/opensandbox/task-executor -listen-addr=0.0.0.0:5758 -log-dir=/tmp")).isTrue();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor("/usr/local/bin/task-executor")).isTrue();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor("./task-executor -listen-addr=:5758")).isTrue();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor("  task-executor")).isTrue();
    }

    @Test
    void aShellThatStartsTaskExecutorIsNotTaskExecutor() {
        assertThat(ShellEngineContractK8sPoolIT
                .isTaskExecutor("/bin/sh -c /opt/opensandbox/task-executor -listen-addr=0.0.0.0:5758 -log-dir=/tmp"))
                .isFalse();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor("/opt/opensandbox/task-executor-wrapper")).isFalse();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor("/opt/opensandbox/execd --init -- tail -f /dev/null"))
                .isFalse();
        assertThat(ShellEngineContractK8sPoolIT.isTaskExecutor("")).isFalse();
    }
}
