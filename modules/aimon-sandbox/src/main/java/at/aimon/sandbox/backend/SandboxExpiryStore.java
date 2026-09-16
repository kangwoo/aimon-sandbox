package at.aimon.sandbox.backend;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage abstraction for sandbox expiry tracking.
 *
 * <p>
 * Implementations must be thread-safe. The default in-memory implementation is suitable for single-instance
 * deployments. For multi-instance environments, provide an implementation backed by an external store (Redis, JDBC,
 * etc.).
 */
public interface SandboxExpiryStore {

    /**
     * Stores or updates the expiry time for a sandbox identifier.
     *
     * @param identifier
     *            the sandbox identifier
     * @param expiresAt
     *            the expiry time
     */
    void put(String identifier, Instant expiresAt);

    /**
     * Returns the expiry time for a sandbox identifier.
     *
     * @param identifier
     *            the sandbox identifier
     * @return the expiry time, or empty if not tracked
     */
    Optional<Instant> get(String identifier);

    /**
     * Removes the expiry entry for a sandbox identifier.
     *
     * @param identifier
     *            the sandbox identifier
     */
    void remove(String identifier);
}
