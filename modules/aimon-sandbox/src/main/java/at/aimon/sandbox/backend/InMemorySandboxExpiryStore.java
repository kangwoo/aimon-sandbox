package at.aimon.sandbox.backend;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link SandboxExpiryStore}.
 *
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}. Suitable for single-instance deployments.
 */
public class InMemorySandboxExpiryStore implements SandboxExpiryStore {

    private final ConcurrentHashMap<String, Instant> entries = new ConcurrentHashMap<>();

    @Override
    public void put(String identifier, Instant expiresAt) {
        entries.put(identifier, expiresAt);
    }

    @Override
    public Optional<Instant> get(String identifier) {
        return Optional.ofNullable(entries.get(identifier));
    }

    @Override
    public void remove(String identifier) {
        entries.remove(identifier);
    }
}
