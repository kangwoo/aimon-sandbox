package at.aimon.sandbox.provider;

import java.io.Serial;
import java.util.Objects;

/**
 * A provider call failed. {@link #kind()} is what the manager's failure classification reads
 * (docs/design/workspace-sandbox.md §10.1): a {@link Kind#TRANSIENT} failure is retried with backoff, a
 * {@link Kind#PERMANENT} one waits until the profile changes.
 */
public class SandboxProviderException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 5871239914237120411L;

    /** Whether retrying can help. */
    public enum Kind {
        /** 5xx, timeouts, lost connections: retry after backoff. */
        TRANSIENT,
        /** The request itself is wrong for this provider: retrying gives the same answer. */
        PERMANENT
    }

    private final Kind kind;

    /**
     * A transient failure.
     *
     * @param message
     *            what failed
     */
    public SandboxProviderException(String message) {
        this(message, Kind.TRANSIENT, null);
    }

    /**
     * @param message
     *            what failed
     * @param kind
     *            whether retrying can help
     * @param cause
     *            the underlying failure, or {@code null}
     */
    public SandboxProviderException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    /** @return whether retrying can help */
    public Kind kind() {
        return kind;
    }
}
