package at.aimon.sandbox.environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import at.aimon.sandbox.binding.WorkspaceSwitch;

/**
 * Notices waiting for the next shell result of one environment (docs/design/workspace-sandbox.md §7). A file tool
 * cannot carry notices, so when a reset or a recreation is first observed by one, the notice is kept here and
 * reported with the next {@code Bash} result instead of being lost. Duplicates collapse.
 *
 * <p>
 * It also carries the binding's {@link WorkspaceSwitch} (docs/design/workspace-sandbox-management.md §6.4): the
 * first {@link #addAll} — every caller makes it right after a successful {@code connect} — queues the switch notice
 * ahead of the connect's own, and the {@link #drain} that hands it to a command result has the switch recorded. A
 * silent switch is recorded at that first connect.
 */
final class PendingNotices {

    private final Set<String> pending = new LinkedHashSet<>();
    private final WorkspaceSwitch workspaceSwitch;
    private final Consumer<WorkspaceSwitch> recorder;
    private boolean connected;
    private boolean recorded;

    PendingNotices() {
        this(null, change -> {
        });
    }

    /**
     * @param workspaceSwitch
     *            the binding's switch, or {@code null}
     * @param recorder
     *            records a switch as the session's {@code lastWorkspaceId}; best effort, never throws
     */
    PendingNotices(WorkspaceSwitch workspaceSwitch, Consumer<WorkspaceSwitch> recorder) {
        this.workspaceSwitch = workspaceSwitch;
        this.recorder = recorder;
    }

    /** The notices of a successful {@code connect}. */
    void addAll(List<String> notices) {
        final boolean recordNow;
        synchronized (this) {
            recordNow = !connected && workspaceSwitch != null && workspaceSwitch.isSilent();
            if (!connected && workspaceSwitch != null && !workspaceSwitch.isSilent()) {
                pending.add(workspaceSwitch.notice());
            }
            connected = true;
            recorded |= recordNow;
            pending.addAll(notices);
        }
        if (recordNow) {
            recorder.accept(workspaceSwitch);
        }
    }

    synchronized void add(String notice) {
        pending.add(notice);
    }

    List<String> drain() {
        final List<String> out;
        final boolean recordNow;
        synchronized (this) {
            out = new ArrayList<>(pending);
            pending.clear();
            recordNow = !recorded && workspaceSwitch != null && out.contains(workspaceSwitch.notice());
            recorded |= recordNow;
        }
        if (recordNow) {
            recorder.accept(workspaceSwitch);
        }
        return out;
    }
}
