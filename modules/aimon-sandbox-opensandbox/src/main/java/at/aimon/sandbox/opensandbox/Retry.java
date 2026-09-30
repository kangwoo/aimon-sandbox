package at.aimon.sandbox.opensandbox;

import java.time.Duration;
import java.util.function.Supplier;

import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * Retries an <b>idempotent</b> call on a transient failure ({@code retry.max-attempts}, {@code retry.backoff}
 * doubling): status, list, destroy, renew, endpoint resolution, file stat/list/read. Never create, {@code /command} or
 * an upload — a lost response of those is absorbed by the record and reconciliation, not by resending.
 */
final class Retry {

    private final int maxAttempts;
    private final Duration backoff;

    Retry(int maxAttempts, Duration backoff) {
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    <T> T call(Supplier<T> call) {
        Duration delay = backoff;
        for (int attempt = 1;; attempt++) {
            try {
                return call.get();
            } catch (SandboxProviderException e) {
                if (e.kind() != SandboxProviderException.Kind.TRANSIENT || attempt >= maxAttempts) {
                    throw e;
                }
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SandboxProviderException("interrupted while retrying an OpenSandbox call");
            }
            delay = delay.multipliedBy(2);
        }
    }

    void run(Runnable call) {
        call(() -> {
            call.run();
            return null;
        });
    }
}
