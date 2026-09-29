package at.aimon.sandbox.workspace;

import java.io.Serial;

import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;

/**
 * The sandbox cannot be used right now, with a reason the model can act on (docs/design/workspace-sandbox.md §15). A
 * core {@link ExecutionEnvironmentUnavailableException}: core's file, shell and skill tools catch exactly that type
 * and return its message as the tool error, so the model sees this wording rather than a generic failure.
 */
public class SandboxUnavailableException extends ExecutionEnvironmentUnavailableException {

    @Serial
    private static final long serialVersionUID = -7719402301846320178L;

    /**
     * @param message
     *            the model-facing reason
     */
    public SandboxUnavailableException(String message) {
        super(message, null);
    }

    /**
     * @param message
     *            the model-facing reason
     * @param cause
     *            the underlying failure
     */
    public SandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
