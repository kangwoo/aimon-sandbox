package at.aimon.sandbox.environment;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.binding.SessionBookkeeping;
import at.aimon.sandbox.binding.WorkspaceSwitch;
import at.aimon.sandbox.workspace.ConnectedSlot;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxUnavailableWithNoticesException;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.UnannouncedReset;

/**
 * The environment-level notices of one environment, waiting for core to drain them (docs/design/workspace-sandbox.md
 * §7, kangwoo/aimon-core#254): core calls {@code drainNotices()} after every model-facing tool call — a file tool
 * included — and puts each notice on that call's result. Every call of the environment connects through
 * {@link #connect}, which queues what {@code connect} reports (a reset, a re-creation, a lost sandbox, a slow
 * provisioning) to be delivered once. Duplicates collapse.
 *
 * <p>
 * One queue per environment instance, never shared with a fork or seeded from a parent (core EE-107): the provider
 * builds a new environment, and so a new queue, for every request.
 *
 * <p>
 * It carries the binding's {@link WorkspaceSwitch} (docs/design/workspace-sandbox-management.md §6.4): the first
 * successful connect queues the switch notice ahead of the connect's own, and the {@link #drain} that returns it has
 * the switch recorded, since core treats what a drain returns as delivered. A silent switch is recorded at that first
 * connect.
 *
 * <p>
 * It also tracks the slot this instance last connected (docs/design/workspace-sandbox-core-033.md §6.5). When a later
 * connect finds another incarnation or a newer generation and did not itself report the reset, someone else re-created
 * the sandbox in between — a fork of this execution, typically — and the model is told its files are gone. A reset
 * notice that arrives afterwards for the same slot replaces a generation notice still queued for it, so one event is
 * told once.
 *
 * <p>
 * A session's root environment keeps its resets durable (docs/design/workspace-sandbox-unannounced-reset.md): a hook, a
 * workflow step or a staging call can connect and reset the slot on a path core never drains, and the instance then
 * dies with the notice. So a connect that reset the slot first writes an {@link UnannouncedReset} to the session's
 * options record — before the notice can be drained — and the drain that returns a notice covering it clears it again.
 * The session's next root environment finds a reset still there in its {@link SessionBookkeeping} and tells it once at
 * its first successful connect of that slot. A fork's queue has no bookkeeping and keeps nothing.
 */
final class PendingNotices {

    private final Set<String> pending = new LinkedHashSet<>();
    private final WorkspaceSwitch workspaceSwitch;
    private final SessionBookkeeping bookkeeping;
    private final NoticeLedger ledger;
    private boolean connected;
    private boolean recorded;
    private String incarnation;
    private long generation;
    /** The generation notice still queued, with the slot it was queued for; {@code null} when none is. */
    private String generationNotice;
    private String noticedIncarnation;
    private long noticedGeneration;
    /** The options record version this instance knows: the binding policy's read, or its own mark's. */
    private OptionalLong knownVersion;
    /** The unannounced reset this instance believes the session's record holds; {@code null} when none. */
    private UnannouncedReset durable;
    /** The unannounced reset that queued, undrained notices cover: the next drain acknowledges it. */
    private UnannouncedReset ackDue;
    /**
     * The reset the binding read and this instance has not told yet: replayed at the first successful connect of its
     * slot, unless a reset notice of this instance's own says it first.
     */
    private UnannouncedReset replayable;
    /** The replay notice while it is still queued; {@code null} when none is. */
    private String replayNotice;

    PendingNotices() {
        this(null, null, NoticeLedger.NONE);
    }

    /**
     * @param workspaceSwitch
     *            the binding's switch, or {@code null}
     * @param bookkeeping
     *            what the binding policy read of the session's options record, or {@code null} — a fork, a routine, a
     *            policy that sets none: no reset is kept
     * @param ledger
     *            writes the session's options record
     */
    PendingNotices(WorkspaceSwitch workspaceSwitch, SessionBookkeeping bookkeeping, NoticeLedger ledger) {
        this.workspaceSwitch = workspaceSwitch;
        this.bookkeeping = bookkeeping;
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.knownVersion = bookkeeping != null
                ? bookkeeping.optionsVersion()
                : workspaceSwitch != null ? workspaceSwitch.optionsVersion() : OptionalLong.empty();
        this.durable = bookkeeping != null ? bookkeeping.unannouncedReset().orElse(null) : null;
        this.replayable = durable;
    }

    /**
     * The one way this environment's calls connect: {@code manager.connect}, then what it reported is queued. A
     * failure after a reset (§15) queues the reset's notices too — told on this call's error result when the call is
     * model-facing, kept for the next drain otherwise — and reports the bare reason.
     *
     * @param manager
     *            the manager
     * @param binding
     *            the environment's binding
     * @return the connected slot
     * @throws SandboxUnavailableException
     *             as {@code connect} does
     */
    ConnectedSlot connect(SandboxWorkspaceManager manager, SandboxBinding binding) {
        final ConnectedSlot slot;
        try {
            slot = manager.connect(binding);
        } catch (SandboxUnavailableWithNoticesException e) {
            failed(e.workspaceId(), e.slot(), e.reset(), e.notices());
            throw new SandboxUnavailableException(e.reason(), e);
        }
        connected(slot.workspace().id(), slot.slot().name(), slot.incarnation(), slot.generation(), slot.reset(),
                slot.notices());
        return slot;
    }

    /**
     * What a successful {@code connect} reported, and the slot it connected.
     *
     * @param workspace
     *            the workspace connected
     * @param slot
     *            the slot connected
     * @param incarnation
     *            the workspace incarnation connected
     * @param generation
     *            the slot generation connected
     * @param reset
     *            whether {@code notices} already say that the slot's files are gone
     * @param notices
     *            the connect's notices
     */
    void connected(SandboxWorkspaceId workspace, String slot, String incarnation, long generation, boolean reset,
            List<String> notices) {
        Objects.requireNonNull(incarnation, "incarnation must not be null");
        // Durable before it is queued: no drain may return a reset notice that a crash could still lose.
        final UnannouncedReset covered = reset ? mark(workspace, slot) : null;
        final boolean recordNow;
        final OptionalLong version;
        synchronized (this) {
            recordNow = !connected && workspaceSwitch != null && workspaceSwitch.isSilent();
            if (!connected && workspaceSwitch != null && !workspaceSwitch.isSilent()) {
                pending.add(workspaceSwitch.notice());
            }
            if (!connected && !reset && replayable != null && replayable.isOf(workspace, slot)) {
                // An earlier execution of the session reset this slot and its notice was never drained — or its
                // acknowledgement never landed.
                replayNotice = replayNotice(replayable);
                pending.add(replayNotice);
                ackDue = replayable;
                replayable = null;
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
            if (reset) {
                supersedeReplay(covered);
            }
            connected = true;
            recorded |= recordNow;
            version = knownVersion;
            pending.addAll(notices);
        }
        if (recordNow) {
            ledger.acknowledge(workspaceSwitch.session(), version, Optional.of(workspaceSwitch.to()), Optional.empty());
        }
    }

    /**
     * What a failed {@code connect} reported. Sets no baseline: the switch, a replay and the generation tracking wait
     * for the first successful connect.
     *
     * @param workspace
     *            the workspace the call connected
     * @param slot
     *            the slot it connected
     * @param reset
     *            whether {@code notices} say that the slot's files are gone
     * @param notices
     *            the call's notices
     */
    void failed(SandboxWorkspaceId workspace, String slot, boolean reset, List<String> notices) {
        final UnannouncedReset covered = reset ? mark(workspace, slot) : null;
        synchronized (this) {
            if (reset) {
                supersedeReplay(covered);
            }
            pending.addAll(notices);
        }
    }

    /**
     * Keeps a reset on the session's record — a root environment's only.
     *
     * @return the reset this call's notice covers: the one stored, else (nothing stored) an older one of the same slot
     *         this instance knows of; {@code null} when none
     */
    private UnannouncedReset mark(SandboxWorkspaceId workspace, String slot) {
        if (bookkeeping == null) {
            return null;
        }
        final Optional<SessionSandboxOptions> stored = ledger.markResetUnannounced(bookkeeping.session(), workspace,
                slot);
        synchronized (this) {
            if (stored.isPresent()) {
                knownVersion = OptionalLong.of(stored.get().version());
                durable = stored.get().unannouncedReset().orElse(null);
                return durable;
            }
            return durable != null && durable.isOf(workspace, slot) ? durable : null;
        }
    }

    /**
     * A reset of the slot this instance connected, under the lock: its own notice says what a replay would — one still
     * queued was queued first by a parallel call of this instance (review 1 of the design) and goes, and none is queued
     * later — and the reset's value is the one the next drain acknowledges; it is the newer, and covers the older.
     */
    private void supersedeReplay(UnannouncedReset covered) {
        replayable = null;
        if (replayNotice != null) {
            pending.remove(replayNotice);
            replayNotice = null;
        }
        if (covered != null) {
            ackDue = covered;
        }
    }

    /**
     * The replay of a reset the model may not have heard of. It names the time, so a model that was told and made files
     * since — the acknowledgement was lost — can tell which files are meant; it never names the workspace's name.
     */
    static String replayNotice(UnannouncedReset reset) {
        return "/workspace was reset at " + reset.at().truncatedTo(ChronoUnit.SECONDS)
                + " and this session may not have been told: files and shell state (cwd, exported variables) from "
                + "before then are gone";
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
     * returns counts as delivered, so a switch among it is recorded and a reset it covers is cleared from the
     * session's record.
     */
    List<String> drain() {
        final List<String> out;
        final boolean switchDue;
        final UnannouncedReset resetDue;
        final OptionalLong version;
        synchronized (this) {
            out = new ArrayList<>(pending);
            pending.clear();
            generationNotice = null;
            replayNotice = null;
            switchDue = !recorded && workspaceSwitch != null && out.contains(workspaceSwitch.notice());
            recorded |= switchDue;
            resetDue = ackDue;
            ackDue = null;
            version = knownVersion;
        }
        if (switchDue || resetDue != null) {
            ledger.acknowledge(bookkeeping != null ? bookkeeping.session() : workspaceSwitch.session(), version,
                    switchDue ? Optional.of(workspaceSwitch.to()) : Optional.empty(), Optional.ofNullable(resetDue));
        }
        return out;
    }
}
