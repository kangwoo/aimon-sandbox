package at.aimon.sandbox.workspace;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * What {@link SandboxWorkspaceManager#runningFiles} hands back: the files API of a workspace's RUNNING, seeded
 * {@code primary} sandbox, reached without provisioning anything (docs/design/workspace-sandbox-files.md §6.1), and
 * bound to the generation it was opened on, like {@link ConnectedSlot}.
 * <p>
 * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
 * release.
 */
public final class RunningSlotFiles {

    private final SandboxWorkspace workspace;
    private final SandboxSlot slot;
    private final SandboxFiles files;
    private final SlotActivity activity;
    private final BooleanSupplier touch;
    private final Consumer<String> changed;

    RunningSlotFiles(SandboxWorkspace workspace, SandboxSlot slot, SandboxFiles files, SlotActivity activity,
            BooleanSupplier touch, Consumer<String> changed) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.activity = Objects.requireNonNull(activity, "activity must not be null");
        this.touch = Objects.requireNonNull(touch, "touch must not be null");
        this.changed = Objects.requireNonNull(changed, "changed must not be null");
    }

    /** @return the record as read when the files were opened */
    public SandboxWorkspace workspace() {
        return workspace;
    }

    /** @return the slot, RUNNING and seeded when opened */
    public SandboxSlot slot() {
        return slot;
    }

    /**
     * Runs one provider call. A sandbox found gone marks the slot lost — the next agent turn hears "recreated" — and
     * fails {@code NOT_RUNNING}; any other provider failure is {@code UNREACHABLE}. Core VFS exceptions pass.
     *
     * @param call
     *            the call, given the files API
     * @param <T>
     *            its result
     * @return its result
     */
    public <T> T call(Function<SandboxFiles, T> call) {
        return guarded(() -> call.apply(files));
    }

    /**
     * Records a change as activity — throttled like an agent's file call, so it pushes the idle close and the provider
     * expiry out — before the change is made. A sandbox the expiry push finds gone is {@code NOT_RUNNING}; a busy or
     * failing store is {@code UNREACHABLE}, never "lost".
     */
    public void beforeChange() {
        final boolean live;
        try {
            live = touch.getAsBoolean();
        } catch (RuntimeException e) {
            throw WorkspaceFilesUnavailableException
                    .unreachable("the sandbox workspace record cannot be updated right now: " + e.getMessage(), e);
        }
        if (!live) {
            throw WorkspaceFilesUnavailableException.notRunning("the sandbox was lost", null);
        }
    }

    /**
     * Publishes {@code FILES_CHANGED} for a change that was made.
     *
     * @param cause
     *            what changed
     */
    public void changed(String cause) {
        changed.accept(cause);
    }

    private <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (SandboxNotFoundException e) {
            activity.markLost();
            throw WorkspaceFilesUnavailableException.notRunning("the sandbox was lost", e);
        } catch (SandboxProviderException e) {
            throw WorkspaceFilesUnavailableException.unreachable("the sandbox cannot be reached: " + e.getMessage(), e);
        }
    }
}
