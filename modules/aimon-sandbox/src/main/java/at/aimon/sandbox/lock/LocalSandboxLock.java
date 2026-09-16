package at.aimon.sandbox.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-process implementation of {@link SandboxLock} using reference-counted {@link ReentrantLock}.
 *
 * <p>
 * Suitable for single-instance deployments. For multi-instance environments, use a distributed lock implementation.
 *
 * <p>
 * Uses a reference count to prevent removal of locks that are still in use by other threads. This avoids a race
 * condition where {@code removeLock()} could discard a lock held by another thread, causing a new lock object to be
 * created and breaking serialization.
 */
public class LocalSandboxLock implements SandboxLock {

    private final ConcurrentHashMap<String, RefCountedLock> locks = new ConcurrentHashMap<>();

    @Override
    public void lock(String identifier) {
        RefCountedLock ref = locks.compute(identifier, (k, existing) -> {
            if (existing == null) {
                existing = new RefCountedLock();
            }
            existing.refCount.incrementAndGet();
            return existing;
        });
        ref.lock.lock();
    }

    @Override
    public void unlock(String identifier) {
        RefCountedLock ref = locks.get(identifier);
        if (ref != null) {
            ref.lock.unlock();
            int remaining = ref.refCount.decrementAndGet();
            if (remaining == 0) {
                locks.remove(identifier, ref);
            }
        }
    }

    @Override
    public void removeLock(String identifier) {
        locks.computeIfPresent(identifier, (k, ref) -> {
            if (ref.refCount.get() == 0) {
                return null; // remove from map
            }
            return ref; // keep — still in use
        });
    }

    private static final class RefCountedLock {

        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger refCount = new AtomicInteger(0);
    }
}
