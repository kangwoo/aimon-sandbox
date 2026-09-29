package at.aimon.sandbox.workspace;

import java.util.List;
import java.util.Optional;

/**
 * Persists workspace records, one aggregate per workspace, with version CAS (docs/design/workspace-sandbox.md §5.3).
 * Every implementation must pass {@code SandboxWorkspaceStoreContract} from {@code aimon-sandbox-testkit}.
 *
 * <p>
 * What a store returns is what it keeps: a later {@link #find} gives a record equal to the one {@link #update} or
 * {@link #createIfAbsent} returned. A store may round what it keeps (an {@code Instant} to its column's precision,
 * at least milliseconds), so callers compare with the returned record, never with the one they passed in.
 */
public interface SandboxWorkspaceStore {

    /**
     * @param id
     *            the workspace
     * @return its record, or empty
     */
    Optional<SandboxWorkspace> find(SandboxWorkspaceId id);

    /**
     * Stores {@code initial} unless a record with its id exists. The version is 1 in a store that never held the id,
     * and above every version the id had before otherwise: a record deleted and created again must not reach a
     * version that a caller still holding the deleted one could CAS against.
     *
     * @param initial
     *            the new record
     * @return the stored record — {@code initial}'s or the one that was already there
     */
    SandboxWorkspace createIfAbsent(SandboxWorkspace initial);

    /**
     * Replaces the record when it is still at {@code expectedVersion}; the stored copy gets
     * {@code expectedVersion + 1}.
     *
     * @param id
     *            the workspace
     * @param expectedVersion
     *            the version the caller read
     * @param next
     *            the new record (its own version is ignored)
     * @return the stored record
     * @throws StaleVersionException
     *             when the record moved on or is gone
     */
    SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next);

    /**
     * Deletes the record when it is still at {@code expectedVersion} (expired tombstones, §10.5). An absent record is
     * success.
     *
     * @param id
     *            the workspace
     * @param expectedVersion
     *            the version the caller read
     * @throws StaleVersionException
     *             when the record moved on
     */
    void delete(SandboxWorkspaceId id, long expectedVersion);

    /**
     * @param scan
     *            filters and page
     * @return matching records ordered by id, at most {@link WorkspaceScan#limit()}
     */
    List<SandboxWorkspace> scan(WorkspaceScan scan);
}
