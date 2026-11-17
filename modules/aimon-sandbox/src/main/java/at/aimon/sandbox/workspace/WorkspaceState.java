package at.aimon.sandbox.workspace;

/**
 * The state of a workspace record (docs/design/workspace-sandbox-domain.md §1;
 * docs/design/workspace-sandbox-lifecycle.md §7).
 */
public enum WorkspaceState {

    /** In use; {@code connect} provisions slots. */
    OPEN,

    /** Being closed; {@code connect} answers "being closed; retry shortly". The janitor resumes a close stuck here. */
    CLOSING,

    /** Closed. An explicit close blocks {@code connect} until {@code reopen}; an idle close does not. */
    CLOSED
}
