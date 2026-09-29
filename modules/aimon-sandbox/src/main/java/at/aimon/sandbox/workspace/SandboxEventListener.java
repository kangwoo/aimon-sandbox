package at.aimon.sandbox.workspace;

/**
 * Receives lifecycle events (docs/design/workspace-sandbox.md §14). Called synchronously on the thread that made the
 * change; an exception is logged and swallowed, never propagated into the tool call.
 */
@FunctionalInterface
public interface SandboxEventListener {

    /** Ignores every event. */
    SandboxEventListener NOOP = event -> {
    };

    /**
     * @param event
     *            what happened
     */
    void onEvent(SandboxEvent event);
}
