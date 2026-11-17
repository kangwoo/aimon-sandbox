package at.aimon.sandbox.workspace;

/** Why a workspace was closed (docs/design/workspace-sandbox-lifecycle.md §7). */
public enum CloseCause {

    /** The application closed it; the record is a tombstone until {@code reopen} or {@code closedRetention}. */
    EXPLICIT,

    /** {@code closeAfter} passed with no live slot; the next {@code connect} reopens it with a reset notice. */
    IDLE,

    /**
     * Its session was deleted (kangwoo/aimon-core#253, §7.4): set on a derived {@code ws:{sessionId}} record by the
     * session cleanup, under the session's lease. {@code connect} and {@code reopen} refuse the record ("being
     * removed") rather than reopening it, and it is deleted as soon as its sandboxes are gone, without
     * {@code closedRetention} — by the cleanup's deferred step or else by the janitor.
     */
    SESSION_DELETED
}
