package at.aimon.sandbox.workspace;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The default store: a map in this JVM. <b>Single node only</b> (docs/design/workspace-sandbox.md §5.3) — a restart
 * loses every record, tombstones included, and the sandboxes those records owned are left to provider expiry until
 * orphan reconciliation arrives (implementation step 4). Multi-node deployments need a persistent store
 * ({@code aimon-sandbox-store-jdbc}, step 6).
 */
public final class InMemorySandboxWorkspaceStore implements SandboxWorkspaceStore {

    // ConcurrentHashMap and not a sorted concurrent map: its compute methods apply the function at most once and
    // atomically, which is what makes them a CAS. ConcurrentSkipListMap may re-run the function under contention.
    private final ConcurrentHashMap<SandboxWorkspaceId, SandboxWorkspace> records = new ConcurrentHashMap<>();
    /**
     * The highest version any deleted record had. A record created afterwards starts above it, whatever its id, so a
     * stale CAS against a deleted record can never match its successor (no ABA) — without remembering every id.
     */
    private final AtomicLong deletedVersionFloor = new AtomicLong();

    @Override
    public Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(id, "id must not be null")));
    }

    @Override
    public SandboxWorkspace createIfAbsent(SandboxWorkspace initial) {
        Objects.requireNonNull(initial, "initial must not be null");
        return records.computeIfAbsent(initial.id(),
                id -> initial.toBuilder().version(deletedVersionFloor.get() + 1).build());
    }

    @Override
    public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
        Objects.requireNonNull(next, "next must not be null");
        if (!next.id().equals(id)) {
            throw new IllegalArgumentException("record id " + next.id() + " does not match " + id);
        }
        final SandboxWorkspace[] stored = new SandboxWorkspace[1];
        records.computeIfPresent(id, (key, current) -> {
            if (current.version() != expectedVersion) {
                return current;
            }
            stored[0] = next.toBuilder().version(expectedVersion + 1).build();
            return stored[0];
        });
        if (stored[0] == null) {
            throw new StaleVersionException(id, expectedVersion);
        }
        return stored[0];
    }

    @Override
    public void delete(SandboxWorkspaceId id, long expectedVersion) {
        final boolean[] stale = new boolean[1];
        records.computeIfPresent(id, (key, current) -> {
            if (current.version() != expectedVersion) {
                stale[0] = true;
                return current;
            }
            // Inside the map's atomic section: a createIfAbsent of this id waits for it, so it sees the new floor.
            deletedVersionFloor.accumulateAndGet(current.version(), Math::max);
            return null;
        });
        if (stale[0]) {
            throw new StaleVersionException(id, expectedVersion);
        }
    }

    @Override
    public List<SandboxWorkspace> scan(WorkspaceScan scan) {
        Objects.requireNonNull(scan, "scan must not be null");
        return records.values().stream()
                .filter(record -> scan.afterId().map(after -> record.id().compareTo(after) > 0).orElse(true))
                .filter(scan::matches).sorted(Comparator.comparing(SandboxWorkspace::id)).limit(scan.limit()).toList();
    }
}
