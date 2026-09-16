package at.aimon.sandbox.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalSandboxLockTest {

    private LocalSandboxLock lock;

    @BeforeEach
    void setUp() {
        lock = new LocalSandboxLock();
    }

    @Test
    void lockAndUnlock_SingleThread_Succeeds() {
        lock.lock("test-id");
        lock.unlock("test-id");
    }

    @Test
    void unlock_WithoutLock_DoesNotThrow() {
        lock.unlock("nonexistent");
    }

    @Test
    void lock_DifferentIdentifiers_Independent() {
        lock.lock("id-1");
        lock.lock("id-2");
        lock.unlock("id-1");
        lock.unlock("id-2");
    }

    @Test
    void lock_SameIdentifier_SerializesConcurrentAccess() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    lock.lock("shared-id");
                    try {
                        int current = currentConcurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        Thread.sleep(10);
                        currentConcurrent.decrementAndGet();
                    } finally {
                        lock.unlock("shared-id");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void removeLock_ExistingIdentifier_RemovesEntry() {
        lock.lock("to-remove");
        lock.unlock("to-remove");

        lock.removeLock("to-remove");

        // After removal, a new lock/unlock should still work (creates fresh entry)
        lock.lock("to-remove");
        lock.unlock("to-remove");
    }

    @Test
    void removeLock_NonexistentIdentifier_DoesNotThrow() {
        lock.removeLock("nonexistent");
    }

    @Test
    void removeLock_WhileLockHeld_PreservesSerializationForHolder() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    lock.lock("contested-id");
                    try {
                        // Attempt removal while lock is held — should not break serialization
                        lock.removeLock("contested-id");

                        int current = currentConcurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        Thread.sleep(10);
                        currentConcurrent.decrementAndGet();
                    } finally {
                        lock.unlock("contested-id");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }
}
