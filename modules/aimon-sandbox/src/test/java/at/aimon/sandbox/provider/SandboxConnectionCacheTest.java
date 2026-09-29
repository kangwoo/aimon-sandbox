package at.aimon.sandbox.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;

class SandboxConnectionCacheTest {

    private final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
    private final SandboxConnectionCache cache = new SandboxConnectionCache(provider, Clock.systemUTC());
    private final ProviderSandboxRef ref = ProviderSandboxRef.of("local", "x");
    private final AtomicInteger connects = new AtomicInteger();

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

    @Test
    void aSlowConnectDoesNotStallTheLookupOfAnotherSandbox() throws Exception {
        final CountDownLatch connecting = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ProviderSandboxRef slow = ProviderSandboxRef.of("local", "slow");
        // A second sandbox in the same bin of a fresh map: computeIfAbsent would hold that bin through the connect.
        final ProviderSandboxRef neighbour = sameBin(slow);
        final SandboxConnection fast = provider.connect(provider.create(CreateSpec.builder().key("fast").image("local")
                .expiresAt(java.time.Instant.now().plusSeconds(3600)).build()));
        final SandboxProvider stalling = new at.aimon.sandbox.DelegatingProvider(provider) {
            @Override
            public SandboxConnection connect(ProviderSandboxRef ref) {
                if (ref.equals(slow)) {
                    connecting.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return fast;
                }
                return fast;
            }
        };
        final SandboxConnectionCache stalled = new SandboxConnectionCache(stalling, Clock.systemUTC());
        final CompletableFuture<SandboxConnection> first = CompletableFuture.supplyAsync(() -> stalled.get(slow));
        try {
            assertThat(connecting.await(5, TimeUnit.SECONDS)).isTrue();

            final CompletableFuture<SandboxConnection> second = CompletableFuture
                    .supplyAsync(() -> stalled.get(neighbour));

            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(fast);
        } finally {
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aShellFinishedAfterItsSandboxWasEvictedIsNotRemembered() {
        final SandboxProvider counting = new at.aimon.sandbox.DelegatingProvider(provider) {
            @Override
            public SandboxConnection connect(ProviderSandboxRef ref) {
                connects.incrementAndGet();
                return super.connect(ref);
            }
        };
        final SandboxConnectionCache cache = new SandboxConnectionCache(counting, Clock.systemUTC());
        cache.execShellStarted(ref, "/workspace/.aimon-shell/x");
        cache.evict(ref);
        cache.execShellFinished(ref, "/workspace/.aimon-shell/x");

        // Nothing to sweep: re-adding it would connect to a sandbox that is gone, on every pass.
        assertThat(cache.sweepExecShells(Duration.ZERO)).isZero();
        assertThat(connects.get()).isZero();
    }

    @Test
    void aCommandStartingOnAShellBeingSweptWaitsForTheSweep() throws Exception {
        final ProviderSandboxRef created = provider.create(CreateSpec.builder().key("sweep").image("local")
                .expiresAt(java.time.Instant.now().plusSeconds(3600)).build());
        final CountDownLatch sweeping = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final SandboxProvider slowSweep = new at.aimon.sandbox.DelegatingProvider(provider) {
            @Override
            public SandboxConnection connect(ProviderSandboxRef ref) {
                final SandboxConnection connection = super.connect(ref);
                return new SandboxConnection() {
                    @Override
                    public RunningCommand run(ExecSpec spec, OutputSink sink) {
                        sweeping.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return connection.run(spec, sink);
                    }

                    @Override
                    public SandboxFiles files() {
                        return connection.files();
                    }

                    @Override
                    public void close() {
                        connection.close();
                    }
                };
            }
        };
        final SandboxConnectionCache cache = new SandboxConnectionCache(slowSweep, Clock.systemUTC());
        cache.execShellStarted(created, "/workspace/.aimon-shell/x");
        cache.execShellFinished(created, "/workspace/.aimon-shell/x");
        final CompletableFuture<Integer> sweep = CompletableFuture
                .supplyAsync(() -> cache.sweepExecShells(Duration.ZERO));
        assertThat(sweeping.await(5, TimeUnit.SECONDS)).isTrue();

        final CompletableFuture<Void> started = CompletableFuture
                .runAsync(() -> cache.execShellStarted(created, "/workspace/.aimon-shell/x"));
        Thread.sleep(200);
        assertThat(started.isDone()).as("the command waits while its directory is being deleted").isFalse();
        release.countDown();

        assertThat(sweep.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        started.get(5, TimeUnit.SECONDS);
        // The new command owns the directory now: the next sweep leaves it alone while it runs.
        assertThat(cache.sweepExecShells(Duration.ZERO)).isZero();
    }

    /** A ref that lands in the same bin as {@code ref} in a new ConcurrentHashMap (16 bins). */
    private static ProviderSandboxRef sameBin(ProviderSandboxRef ref) {
        for (int i = 0;; i++) {
            final ProviderSandboxRef candidate = ProviderSandboxRef.of("local", "n" + i);
            if (bin(candidate) == bin(ref) && !candidate.equals(ref)) {
                return candidate;
            }
        }
    }

    private static int bin(ProviderSandboxRef ref) {
        final int h = ref.hashCode();
        return ((h ^ (h >>> 16)) & 0x7fffffff) & 15;
    }
}
