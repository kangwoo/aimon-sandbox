package at.aimon.sandbox.opensandbox;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /** Recorded only: what PID 1 is in pool mode is what this class measures. */
    @Override
    protected void checkPid1(String args) {
    }
}
