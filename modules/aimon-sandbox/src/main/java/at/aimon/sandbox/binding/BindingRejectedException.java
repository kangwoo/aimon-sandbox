package at.aimon.sandbox.binding;

import java.io.Serial;

import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;

/**
 * No binding can be made for this execution (docs/design/workspace-sandbox.md §8, §15). A core
 * {@link ExecutionEnvironmentUnavailableException}, so {@code resolve()} can throw it as is: core publishes an
 * unavailable environment carrying the message, and every tool reports it — the execution never falls back to the
 * host.
 */
public class BindingRejectedException extends ExecutionEnvironmentUnavailableException {

    @Serial
    private static final long serialVersionUID = 3318720014532780291L;

    /**
     * @param message
     *            why, in words the model can act on
     */
    public BindingRejectedException(String message) {
        super(message, null);
    }
}
