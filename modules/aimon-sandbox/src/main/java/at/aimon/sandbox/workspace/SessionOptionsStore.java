package at.aimon.sandbox.workspace;

import java.util.Optional;
import java.util.OptionalLong;

import at.aimon.core.agent.session.SessionId;

/**
 * Persists {@link SessionSandboxOptions}, one record per session, with version CAS
 * (docs/design/workspace-sandbox-management.md §5.2). Every implementation must pass
 * {@code SessionOptionsStoreContract} from {@code aimon-sandbox-testkit}.
 *
 * <p>
 * The CAS rules are {@link SandboxWorkspaceStore}'s, including its no-ABA rule: a record created again after
 * {@link #delete} never reuses a version the deleted one had. What a store returns is what it keeps.
 *
 * <p>
 * <b>Options are deleted with their sessions</b> by registering {@code WorkspaceSandbox.sessionLifecycleListener()} on
 * core's session router (kangwoo/aimon-core#253). A session deleted outside the router fires nothing: its record is
 * removed by {@code SandboxAdministration.deleteSessionOptions}, or by the janitor's sweep once
 * {@code closed-retention} has passed, when core's session store is wired.
 */
public interface SessionOptionsStore {

    /**
     * @param session
     *            the session
     * @return its options, or empty
     */
    Optional<SessionSandboxOptions> find(SessionId session);

    /**
     * Stores {@code options} — created when {@code expectedVersion} is empty, replaced when it is the stored version.
     * The stored copy gets the next version: above every version any deleted record had when created, otherwise
     * {@code expectedVersion + 1}.
     *
     * @param options
     *            the record (its own version is ignored)
     * @param expectedVersion
     *            the version the caller read; empty: the record must not exist
     * @return the stored record
     * @throws StaleVersionException
     *             when the record exists although none was expected, is gone, or moved on
     */
    SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expectedVersion);

    /**
     * Deletes the record when it is still at {@code expectedVersion}. An absent record is success.
     *
     * @param session
     *            the session
     * @param expectedVersion
     *            the version the caller read
     * @throws StaleVersionException
     *             when the record moved on
     */
    void delete(SessionId session, long expectedVersion);

    /**
     * @param id
     *            the workspace
     * @param after
     *            the page starts after this session
     * @param limit
     *            the page size, at least 1
     * @return the records attached to {@code id}, ordered by session id
     */
    Page<SessionSandboxOptions, SessionId> byWorkspace(SandboxWorkspaceId id, Optional<SessionId> after, int limit);

    /**
     * Every workspace some record is attached to — the janitor's sweep of purged ids.
     *
     * @param after
     *            the page starts after this id
     * @param limit
     *            the page size, at least 1
     * @return the attached workspace ids, ordered, each once
     */
    Page<SandboxWorkspaceId, SandboxWorkspaceId> workspaces(Optional<SandboxWorkspaceId> after, int limit);

    /**
     * Every record, attached or not — the janitor's sweep of deleted sessions.
     *
     * @param after
     *            the page starts after this session
     * @param limit
     *            the page size, at least 1
     * @return the records, ordered by session id
     */
    Page<SessionSandboxOptions, SessionId> sessions(Optional<SessionId> after, int limit);

    /**
     * Clears {@code workspace} on every record attached to {@code id}, keeping each record's other fields; each changed
     * record gets a new version. Idempotent.
     *
     * @param id
     *            the workspace, typically just purged
     * @return how many records were changed
     */
    int deleteByWorkspace(SandboxWorkspaceId id);
}
