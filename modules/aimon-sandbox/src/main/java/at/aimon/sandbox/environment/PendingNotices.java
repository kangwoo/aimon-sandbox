package at.aimon.sandbox.environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import at.aimon.sandbox.binding.WorkspaceSwitch;
import at.aimon.sandbox.workspace.ConnectedSlot;

/**
 * The environment-level notices of one environment, waiting for core to drain them (docs/design/workspace-sandbox.md
 * §7, kangwoo/aimon-core#254): core calls {@code drainNotices()} after every model-facing tool call — a file tool
 * included — and puts each notice on that call's result. What {@code connect} reports (a reset, a re-creation, a lost
 * sandbox, a slow provisioning) is queued here by whichever call connected, and delivered once. Duplicates collapse.
 *
 * <p>
 * One queue per environment instance, never shared with a fork or seeded from a parent (core EE-107): the provider
 * builds a new environment, and so a new queue, for every request.
 *
 * <p>
 * It carries the binding's {@link WorkspaceSwitch} (docs/design/workspace-sandbox-management.md §6.4): the first
 * {@link #addAll} — every caller makes it right after a successful {@code connect} — queues the switch notice ahead of
 * the connect's own, and the {@link #drain} that returns it has the switch recorded, since core treats what a drain
 * returns as delivered. A silent switch is recorded at that first connect.
 *
 * <p>
 * It also tracks the slot this instance last connected (docs/design/workspace-sandbox-core-033.md §6.5). When a later
 * connect finds another incarnation or a newer generation and did not itself report the reset, someone else re-created
 * the sandbox in between — a fork of this execution, typically — and the model is told its files are gone. A reset
 * notice that arrives afterwards for the same slot replaces a generation notice still queued for it, so one event is
 * told once.
 */
final class PendingNotices {

    private final Set<String> pending = new LinkedHashSet<>();
    private final WorkspaceSwitch workspaceSwitch;
    private final Consumer<WorkspaceSwitch> recorder;
    private boolean connected;
    private boolean recorded;
    private String incarnation;
    private long generation;
    /** The generation notice still queued, with the slot it was queued for; {@code null} when none is. */
    private String generationNotice;
    private String noticedIncarnation;
    private long noticedGeneration;

    PendingNotices() {
        this(null, change -> {
        });
    }

    /**
     * @param workspaceSwitch
     *            the binding's switch, or {@code null}
     * @param recorder
     *            records a switch as the session's {@code lastWorkspaceId}; must not block on remote I/O and never
     *            throws
     */
    PendingNotices(WorkspaceSwitch workspaceSwitch, Consumer<WorkspaceSwitch> recorder) {
        this.workspaceSwitch = workspaceSwitch;
        this.recorder = recorder;
    }

    /** What a successful {@code connect} reported, and the slot it connected. */
    void addAll(ConnectedSlot slot) {
        addAll(slot.incarnation(), slot.generation(), slot.reset(), slot.notices());
    }

    /**
     * @param incarnation
     *            the workspace incarnation connected
     * @param generation
     *            the slot generation connected
     * @param reset
     *            whether {@code notices} already say that the slot's files are gone
     * @param notices
     *            the connect's notices
     */
    void addAll(String incarnation, long generation, boolean reset, List<String> notices) {
        Objects.requireNonNull(incarnation, "incarnation must not be null");
        final boolean recordNow;
        synchronized (this) {
            recordNow = !connected && workspaceSwitch != null && workspaceSwitch.isSilent();
            if (!connected && workspaceSwitch != null && !workspaceSwitch.isSilent()) {
                pending.add(workspaceSwitch.notice());
            }
            if (!connected) {
                this.incarnation = incarnation;
                this.generation = generation;
            } else if (newer(incarnation, generation)) {
                this.incarnation = incarnation;
                this.generation = generation;
                if (!reset) {
                    generationNotice = "the sandbox was re-created since this execution last used it (generation "
                            + generation + "); files under /workspace from before are gone";
                    noticedIncarnation = incarnation;
                    noticedGeneration = generation;
                    pending.add(generationNotice);
                }
            }
            if (reset && generationNotice != null && incarnation.equals(noticedIncarnation)
                    && generation == noticedGeneration) {
                // A parallel call of this instance reached here first with the slot that this call's connect
                // re-created: the reset notice is the better wording of the same event.
                pending.remove(generationNotice);
                generationNotice = null;
            }
            connected = true;
            recorded |= recordNow;
            pending.addAll(notices);
        }
        if (recordNow) {
            recorder.accept(workspaceSwitch);
        }
    }

    /**
     * Another incarnation, or a later generation of the same one. An older generation is a parallel call that read
     * the slot before it was re-created, and changes nothing.
     */
    private boolean newer(String incarnation, long generation) {
        return !incarnation.equals(this.incarnation) || generation > this.generation;
    }

    /**
     * Returns and forgets every queued notice — core's {@code drainNotices()}, and nothing else may call it: what it
     * returns counts as delivered.
     */
    List<String> drain() {
        final List<String> out;
        final boolean recordNow;
        synchronized (this) {
            out = new ArrayList<>(pending);
            pending.clear();
            generationNotice = null;
            recordNow = !recorded && workspaceSwitch != null && out.contains(workspaceSwitch.notice());
            recorded |= recordNow;
        }
        if (recordNow) {
            recorder.accept(workspaceSwitch);
        }
        return out;
    }
}
