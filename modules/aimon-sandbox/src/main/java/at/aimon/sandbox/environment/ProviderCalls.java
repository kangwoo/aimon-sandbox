package at.aimon.sandbox.environment;

import java.util.function.Supplier;

import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.workspace.ConnectedSlot;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * Turns provider failures during a file, staging or search call into what core's tools report
 * (docs/design/workspace-sandbox.md §15): a sandbox found gone marks its slot lost and fails with the "lost"
 * message, so the next call recreates it; any other provider failure is "unavailable". Neither reaches a tool as a
 * raw provider exception.
 */
final class ProviderCalls {

    private ProviderCalls() {
    }

    static <T> T guarded(ConnectedSlot slot, Supplier<T> call) {
        try {
            return call.get();
        } catch (SandboxNotFoundException e) {
            slot.activity().markLost();
            throw new SandboxUnavailableException(SandboxWorkspaceManager.LOST_MESSAGE, e);
        } catch (SandboxProviderException e) {
            throw new SandboxUnavailableException("the sandbox cannot be reached: " + e.getMessage(), e);
        }
    }

    static void guarded(ConnectedSlot slot, Runnable call) {
        guarded(slot, () -> {
            call.run();
            return null;
        });
    }
}
