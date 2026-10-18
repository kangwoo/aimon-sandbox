package at.aimon.sandbox.workspace;

/** Why a workspace was closed (docs/design/workspace-sandbox.md §10.5). */
public enum CloseCause {

    /** The application closed it; the record is a tombstone until {@code reopen} or {@code closedRetention}. */
    EXPLICIT,

    /** {@code closeAfter} passed with no live slot; the next {@code connect} reopens it with a reset notice. */
    IDLE,

    /**
     * Its session was deleted (kangwoo/aimon-core#253, docs/design/workspace-sandbox-core-033.md §6.3): set on a
     * derived {@code ws:{sessionId}} record by the session cleanup, under the session's lease. {@code connect} refuses
     * the record ("being removed") rather than reopening it, and it is deleted as soon as its sandboxes are gone,
     * without {@code closedRetention} — by the cleanup's deferred step or else by the janitor.
     */
    SESSION_DELETED
}
