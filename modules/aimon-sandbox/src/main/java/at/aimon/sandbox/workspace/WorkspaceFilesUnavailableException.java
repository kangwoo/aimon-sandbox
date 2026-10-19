package at.aimon.sandbox.workspace;

import java.io.Serial;
import java.util.Objects;

/**
 * A workspace's files cannot be reached through {@code SandboxWorkspaceFiles} right now
 * (docs/design/workspace-sandbox-files.md §6.1). A file explorer shows {@link #reason()}, not the message.
 * {@link Reason#NOT_RUNNING} is the ordinary "nothing to show" state of a workspace whose sandbox is not up, never a
 * failure to retry in a loop — the explorer does not provision.
 */
public final class WorkspaceFilesUnavailableException extends SandboxUnavailableException {

    @Serial
    private static final long serialVersionUID = 4620017958210356413L;

    /** Why the files cannot be reached. */
    public enum Reason {
        /** The workspace does not exist, or the caller may not use it — the two answer alike. */
        NOT_FOUND,
        /**
         * The workspace is OPEN but its {@code primary} sandbox is not running: never provisioned, provisioning,
         * terminated, failed, lost or not yet seeded. Its next use by an agent (or {@code provision}) starts one.
         */
        NOT_RUNNING,
        /** The workspace is being closed. */
        CLOSING,
        /** The workspace is closed, after being idle or explicitly; its files are gone. */
        CLOSED,
        /** The sandbox runs but the provider cannot be reached, or its profile is no longer configured. */
        UNREACHABLE
    }

    private final Reason reason;

    /**
     * @param reason
     *            why
     * @param message
     *            a host-facing explanation
     * @param cause
     *            the underlying failure, or {@code null}
     */
    public WorkspaceFilesUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /** @return why the files cannot be reached */
    public Reason reason() {
        return reason;
    }

    static WorkspaceFilesUnavailableException notFound() {
        return new WorkspaceFilesUnavailableException(Reason.NOT_FOUND,
                "the sandbox workspace does not exist or this principal may not use it", null);
    }

    static WorkspaceFilesUnavailableException notRunning(String why, Throwable cause) {
        return new WorkspaceFilesUnavailableException(Reason.NOT_RUNNING,
                "the sandbox workspace has no running sandbox (" + why + "); its files exist only while one runs",
                cause);
    }
}
