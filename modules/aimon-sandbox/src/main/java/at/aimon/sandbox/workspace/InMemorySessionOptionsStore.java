package at.aimon.sandbox.workspace;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import at.aimon.core.agent.session.SessionId;

/**
 * The default options store: a map in this JVM. <b>Single node only</b>, like {@link InMemorySandboxWorkspaceStore}
 * (docs/design/workspace-sandbox-management.md §12 Q4): a restart loses every session's attachment, and its turns fall
 * back to the unattached rule.
 */
public final class InMemorySessionOptionsStore implements SessionOptionsStore {

    // ConcurrentHashMap for the same reason as the workspace store: its compute methods are the CAS.
    private final ConcurrentHashMap<SessionId, SessionSandboxOptions> records = new ConcurrentHashMap<>();
    /** One global floor, as in {@link InMemorySandboxWorkspaceStore}: no deleted key is remembered. */
    private final AtomicLong deletedVersionFloor = new AtomicLong();

    @Override
    public Optional<SessionSandboxOptions> find(SessionId session) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(session, "session must not be null")));
    }

    @Override
    public SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expectedVersion) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(expectedVersion, "expectedVersion must not be null");
        final SessionSandboxOptions[] stored = new SessionSandboxOptions[1];
        records.compute(options.session(), (key, current) -> {
            if (current == null && expectedVersion.isEmpty()) {
                stored[0] = options.toBuilder().version(deletedVersionFloor.get() + 1).build();
                return stored[0];
            }
            if (current != null && expectedVersion.isPresent() && current.version() == expectedVersion.getAsLong()) {
                stored[0] = options.toBuilder().version(current.version() + 1).build();
                return stored[0];
            }
            return current;
        });
        if (stored[0] == null) {
            throw new StaleVersionException(options.session(), expectedVersion.orElse(0));
        }
        return stored[0];
    }

    @Override
    public void delete(SessionId session, long expectedVersion) {
        final boolean[] stale = new boolean[1];
        records.computeIfPresent(session, (key, current) -> {
            if (current.version() != expectedVersion) {
                stale[0] = true;
                return current;
            }
            deletedVersionFloor.accumulateAndGet(current.version(), Math::max);
            return null;
        });
        if (stale[0]) {
            throw new StaleVersionException(session, expectedVersion);
        }
    }

    @Override
    public Page<SessionSandboxOptions, SessionId> byWorkspace(SandboxWorkspaceId id, Optional<SessionId> after,
            int limit) {
        Objects.requireNonNull(id, "id must not be null");
        requirePositive(limit);
        final List<SessionSandboxOptions> matching = records.values().stream()
                .filter(record -> record.workspace().map(id::equals).orElse(false))
                .filter(record -> after.map(a -> record.session().value().compareTo(a.value()) > 0).orElse(true))
                .sorted(Comparator.comparing(record -> record.session().value())).limit(limit + 1L).toList();
        return page(matching, limit, SessionSandboxOptions::session);
    }

    @Override
    public Page<SandboxWorkspaceId, SandboxWorkspaceId> workspaces(Optional<SandboxWorkspaceId> after, int limit) {
        requirePositive(limit);
        final List<SandboxWorkspaceId> matching = records.values().stream()
                .flatMap(record -> record.workspace().stream()).distinct()
                .filter(id -> after.map(a -> id.compareTo(a) > 0).orElse(true)).sorted().limit(limit + 1L).toList();
        return page(matching, limit, id -> id);
    }

    @Override
    public Page<SessionSandboxOptions, SessionId> sessions(Optional<SessionId> after, int limit) {
        requirePositive(limit);
        final List<SessionSandboxOptions> matching = records.values().stream()
                .filter(record -> after.map(a -> record.session().value().compareTo(a.value()) > 0).orElse(true))
                .sorted(Comparator.comparing(record -> record.session().value())).limit(limit + 1L).toList();
        return page(matching, limit, SessionSandboxOptions::session);
    }

    @Override
    public int deleteByWorkspace(SandboxWorkspaceId id) {
        Objects.requireNonNull(id, "id must not be null");
        int changed = 0;
        for (SessionId session : List.copyOf(records.keySet())) {
            final boolean[] cleared = new boolean[1];
            records.computeIfPresent(session, (key, current) -> {
                if (!current.workspace().map(id::equals).orElse(false)) {
                    return current;
                }
                cleared[0] = true;
                return current.toBuilder().workspace(null).version(current.version() + 1).build();
            });
            changed += cleared[0] ? 1 : 0;
        }
        return changed;
    }

    private static <T, K> Page<T, K> page(List<T> matching, int limit, Function<T, K> key) {
        if (matching.size() <= limit) {
            return Page.of(matching, null);
        }
        final List<T> items = matching.subList(0, limit);
        return Page.of(items, key.apply(items.get(limit - 1)));
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
    }
}
