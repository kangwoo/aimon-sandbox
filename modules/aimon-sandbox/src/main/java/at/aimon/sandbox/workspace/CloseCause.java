package at.aimon.sandbox.workspace;

/** Why a workspace was closed (docs/design/workspace-sandbox.md §10.5). */
public enum CloseCause {

    /** The application closed it; the record is a tombstone until {@code reopen} or {@code closedRetention}. */
    EXPLICIT,

    /** {@code closeAfter} passed with no live slot; the next {@code connect} reopens it with a reset notice. */
    IDLE
}
