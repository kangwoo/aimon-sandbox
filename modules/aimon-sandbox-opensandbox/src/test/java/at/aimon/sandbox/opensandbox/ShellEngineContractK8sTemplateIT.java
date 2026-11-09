package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * Core's engine contract suite and the leftover checks on the k8s tier in template mode: the server renders the
 * sandbox's pod from its BatchSandbox template, and the profile has no waivers (deny-all egress, the declared runtime
 * class, the hardened template). Skips without {@code OPENSANDBOX_K8S_ENDPOINT}; {@code scripts/k8s-tier.sh test}
 * runs it.
 */
@Tag("k8s")
class ShellEngineContractK8sTemplateIT extends AbstractShellEngineK8sIT {

    private static SharedSandbox sandbox;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(K8sTier.env(K8sTier.ENDPOINT) != null, K8sTier.NO_CLUSTER);
        sandbox = SharedSandbox.k8sTemplate();
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
     * execd's bootstrap is PID 1 and waits for the entrypoint, reaping any child (docs/design/ptc-shell-provider.md
     * §9); with the server's {@code execd_run_as_init} it is {@code execd --init} instead, which reaps too.
     */
    @Override
    protected void checkPid1(String args, List<SandboxLeftovers.Proc> procs) {
        assertThat(args).as("PID 1 of a template-mode sandbox").containsAnyOf("bootstrap.sh", "execd --init");
    }
}
