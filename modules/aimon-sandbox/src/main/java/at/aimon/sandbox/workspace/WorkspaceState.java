package at.aimon.sandbox.workspace;

/** The state of a workspace record (docs/design/workspace-sandbox.md §5.1, §10.5). */
public enum WorkspaceState {

    /** In use; {@code connect} provisions slots. */
    OPEN,

    /** Being closed; {@code connect} answers "workspace closed". The janitor resumes a close stuck here. */
    CLOSING,

    /** Closed. An explicit close blocks {@code connect} until {@code reopen}; an idle close does not. */
    CLOSED
}
