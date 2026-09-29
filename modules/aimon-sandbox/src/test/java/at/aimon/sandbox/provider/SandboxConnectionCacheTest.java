package at.aimon.sandbox.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;

class SandboxConnectionCacheTest {

    private final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
    private final SandboxConnectionCache cache = new SandboxConnectionCache(provider, Clock.systemUTC());
    private final ProviderSandboxRef ref = ProviderSandboxRef.of("local", "x");

    @AfterEach
    void close() {
        cache.close();
        provider.close();
    }

    @Test
    void aShellLockTimesOutWhileHeldAndItsEntryDisappearsWithItsUsers() throws Exception {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (SandboxConnectionCache.Lease lease = cache.lockShell(ref, "session:s", Duration.ofSeconds(1))) {
                assertThat(lease.acquired()).isTrue();
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        held.await(5, TimeUnit.SECONDS);

        try (SandboxConnectionCache.Lease busy = cache.lockShell(ref, "session:s", Duration.ofMillis(100))) {
            assertThat(busy.acquired()).isFalse();
        }
        try (SandboxConnectionCache.Lease other = cache.lockShell(ref, "session:other", Duration.ofMillis(100))) {
            assertThat(other.acquired()).isTrue();
        }
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);

        assertThat(cache.lockEntries()).isZero();
    }

    @Test
    void stagingLocksSerialiseOneTarget() throws Exception {
        try (SandboxConnectionCache.Lease lease = cache.lockStaging(ref, "/t")) {
            assertThat(lease.acquired()).isTrue();
            final CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> {
                try (SandboxConnectionCache.Lease next = cache.lockStaging(ref, "/t")) {
                    return next.acquired();
                } catch (InterruptedException e) {
                    return false;
                }
            });
            Thread.sleep(100);
            assertThat(second.isDone()).isFalse();
            lease.close();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(cache.lockEntries()).isZero();
    }

    @Test
    void connectionsAreCachedAndEvicted() {
        final ProviderSandboxRef created = provider.create(CreateSpec.builder().key("k").image("local")
                .expiresAt(java.time.Instant.now().plusSeconds(3600)).build());

        final SandboxConnection first = cache.get(created);
        assertThat(cache.get(created)).isSameAs(first);
        cache.evict(created);
        assertThat(cache.get(created)).isNotSameAs(first);
    }
}
