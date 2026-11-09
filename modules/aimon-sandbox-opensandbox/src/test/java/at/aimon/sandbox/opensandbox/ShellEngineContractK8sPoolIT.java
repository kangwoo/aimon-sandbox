package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * Core's engine contract suite and the leftover checks on the k8s tier in pool mode: the server takes the sandbox from
 * a pre-created {@code Pool} ({@code extensions.poolRef}, the provider's test seam), whose pod runs the sandbox as a
 * task of task-executor. Skips without {@code OPENSANDBOX_K8S_ENDPOINT} and {@code OPENSANDBOX_K8S_POOL};
 * {@code scripts/k8s-tier.sh test} provisions the pool ({@code src/test/k8s/pool.yaml}) and sets both.
 */
@Tag("k8s")
class ShellEngineContractK8sPoolIT extends AbstractShellEngineK8sIT {

    private static SharedSandbox sandbox;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(K8sTier.env(K8sTier.ENDPOINT) != null, K8sTier.NO_CLUSTER);
        assumeTrue(K8sTier.env(K8sTier.POOL) != null, K8sTier.POOL + " is not set: pool mode needs a Pool resource");
        sandbox = SharedSandbox.k8sPool(K8sTier.env(K8sTier.POOL));
    }

    @AfterAll
    static void stop() {
        if (sandbox != null) {
            sandbox.close();
        }
    }

    @Override
    protected SharedSandbox sandbox() {
        return sandbox;
    }

    /**
     * Something reaps the orphans: PID 1 is not task-executor — the shipped pool's {@code /bin/sh -c}, which Debian's
     * sh does not exec away, stays PID 1 and waits — or execd runs as {@code execd --init}, a subreaper under
     * task-executor, which the server's {@code execd_run_as_init = true} makes it. task-executor as PID 1 reaps
     * nothing, so without the latter every run leaks its watchdog's zombie (docs/design/ptc-shell-provider.md §9,
     * "K8s tier, measured"); this names that cause before the leftover checks show its effect.
     */
    @Override
    protected void checkPid1(String args, List<SandboxLeftovers.Proc> procs) {
        final boolean taskExecutorIsPid1 = isTaskExecutor(args);
        final boolean execdIsSubreaper = procs.stream().anyMatch(proc -> proc.args().contains("execd --init"));
        assertThat(!taskExecutorIsPid1 || execdIsSubreaper)
                .as("PID 1 of a pool-mode sandbox is not task-executor, or execd runs as --init (execd_run_as_init): "
                        + "PID 1 is %s", args)
                .isTrue();
    }

    /**
     * @param args
     *            a command line, its arguments separated by spaces
     * @return whether its program is task-executor, wherever it was installed and however the command line names it
     *         (absolute, relative, bare); a shell whose script starts task-executor is the shell, not task-executor
     */
    static boolean isTaskExecutor(String args) {
        final String program = args.strip().split("\\s+", 2)[0];
        return program.substring(program.lastIndexOf('/') + 1).equals("task-executor");
    }
}
