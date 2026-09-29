package at.aimon.sandbox.workspace;

/** The state of one slot (docs/design/workspace-sandbox.md §5.2). */
public enum SlotState {

    /** A node claimed the slot and is creating its sandbox. */
    PROVISIONING,

    /** The sandbox exists and takes commands. */
    RUNNING,

    /** Paused ({@code PAUSE_RESUME}; not reachable in implementation step 3). */
    PAUSED,

    /** No sandbox; the next use provisions generation + 1. */
    TERMINATED,

    /** Provisioning failed; see {@link SlotFailure}. */
    FAILED
}
