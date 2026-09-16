package at.aimon.sandbox.lock;

/**
 * Serialization lock for sandbox operations on a per-identifier basis.
 *
 * <p>
 * Protects the ensure → exec → artifact extraction sequence from concurrent access. The default in-process
 * implementation uses {@link java.util.concurrent.locks.ReentrantLock}. For multi-instance environments, provide a
 * distributed lock implementation (Redis, ZooKeeper, etc.).
 */
public interface SandboxLock {

    /**
     * Acquires the lock for the given identifier. Blocks until the lock is available.
     *
     * @param identifier
     *            the sandbox identifier
     */
    void lock(String identifier);

    /**
     * Releases the lock for the given identifier.
     *
     * @param identifier
     *            the sandbox identifier
     */
    void unlock(String identifier);

    /**
     * Removes the lock entry for the given identifier, releasing associated resources.
     *
     * <p>
     * Should be called when a sandbox is permanently deleted and no further locking is needed. Callers must ensure no
     * threads hold or are waiting on the lock before calling this method.
     *
     * @param identifier
     *            the sandbox identifier
     */
    default void removeLock(String identifier) {
        // Default no-op for distributed implementations that manage lifecycle externally
    }
}
