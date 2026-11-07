package at.aimon.sandbox.opensandbox;

import org.junit.jupiter.api.Tag;

/**
 * {@link OpenSandboxK8sIT} in pool mode: every sandbox is taken from the {@code Pool} named by
 * {@code OPENSANDBOX_K8S_POOL} ({@code extensions.poolRef}, the provider's test seam), so the labels, the hardening of
 * the pool's pods, the seed of a profile without waivers and east-west isolation are checked on pool pods. The checks
 * that need a network policy, a credential or the server's runtime class skip, saying why. Skips without
 * {@code OPENSANDBOX_K8S_ENDPOINT} and {@code OPENSANDBOX_K8S_POOL}; {@code scripts/k8s-tier.sh test} sets both.
 */
@Tag("k8s")
class OpenSandboxK8sPoolIT extends OpenSandboxK8sIT {

    @Override
    protected boolean poolMode() {
        return true;
    }
}
