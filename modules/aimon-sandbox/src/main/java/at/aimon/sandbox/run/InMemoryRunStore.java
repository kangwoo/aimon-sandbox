package at.aimon.sandbox.run;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import at.aimon.sandbox.model.SandboxRun;

/**
 * In-memory implementation of {@link RunStore}.
 *
 * <p>
 * Thread-safe via explicit synchronization on the internal map. Suitable for single-instance deployments. Enforces a
 * maximum capacity to prevent unbounded memory growth — when the limit is reached, the oldest entries are evicted
 * automatically using insertion-order tracking.
 */
public class InMemoryRunStore implements RunStore {

    static final int DEFAULT_MAX_CAPACITY = 1000;

    private final int maxCapacity;
    private final LinkedHashMap<String, SandboxRun> runs;

    public InMemoryRunStore() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public InMemoryRunStore(int maxCapacity) {
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity must be >= 1, got: " + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        this.runs = new LinkedHashMap<>(16, 0.75f, false) {

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SandboxRun> eldest) {
                return size() > maxCapacity;
            }
        };
    }

    @Override
    public void save(SandboxRun run) {
        synchronized (runs) {
            if (runs.containsKey(run.getRunId())) {
                throw new IllegalArgumentException("Run already exists: " + run.getRunId());
            }
            runs.put(run.getRunId(), run);
        }
    }

    @Override
    public Optional<SandboxRun> findById(String runId) {
        synchronized (runs) {
            return Optional.ofNullable(runs.get(runId));
        }
    }

    @Override
    public SandboxRun update(String runId, UnaryOperator<SandboxRun> updateFn) {
        synchronized (runs) {
            SandboxRun existing = runs.get(runId);
            if (existing == null) {
                throw new IllegalArgumentException("Run not found: " + runId);
            }
            SandboxRun updated = updateFn.apply(existing);
            runs.put(runId, updated);
            return updated;
        }
    }
}
