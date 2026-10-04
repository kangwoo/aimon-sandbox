package at.aimon.sandbox.workspace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.profile.SandboxProfile;

/**
 * The application-scoped loop that enforces idle policy and reconciles sandboxes (docs/design/workspace-sandbox.md
 * §10.4 items 1–2). One {@link #runOnce()} does, in order:
 *
 * <ol>
 * <li><b>terminate</b> — every RUNNING slot idle for its profile's {@code terminateAfter} (the longest configured
 * value when its profile was removed);</li>
 * <li><b>reclaim</b> — every PROVISIONING slot whose claim is older than twice {@code provisionTimeout}: its claimer
 * is gone, and nobody connected to take it over (§10.1). It becomes TERMINATED, so it no longer blocks the idle close
 * or holds an admission slot;</li>
 * <li><b>idle close</b> — every OPEN workspace with no live slot for {@code closeAfter}, counted from the latest of
 * its creation, its last reopen and its slots' {@code lastActiveAt};</li>
 * <li><b>resume</b> — every close stuck in CLOSING for {@code closeResumeAfter}, from the top;</li>
 * <li><b>tombstones</b> — every CLOSED record older than {@code closedRetention} is deleted;</li>
 * <li>node-local: {@code exec:} shell directories unused for {@code execShellIdle};</li>
 * <li><b>reconcile</b> — the deployment's sandboxes against the records: orphans, stale generations, failed leftovers
 * and duplicates are destroyed, missing sandboxes confirmed LOST ({@link SandboxReconciler}).</li>
 * </ol>
 *
 * Every transition re-checks its condition on a freshly read record inside the CAS — a heartbeat that pushed
 * {@code lastActivityAt} in the meantime wins — and calls the provider only after the CAS. One workspace failing is
 * logged and the loop goes on. Volume reconciliation (§10.4 item 3) arrives with implementation step 5.
 */
public final class SandboxJanitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SandboxJanitor.class);

    private final SandboxWorkspaceManager manager;
    private final SandboxScheduler scheduler;
    private final SandboxSettings settings;
    private final SandboxReconciler reconciler;
    private SandboxScheduler.Cancellable task;

    /**
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param manager
     *            the manager whose close path this uses
     * @param scheduler
     *            runs the loop every {@code janitor.interval} once {@linkplain #start() started}
     */
    public SandboxJanitor(SandboxWorkspaceManager manager, SandboxScheduler scheduler) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.settings = manager.settings();
        this.reconciler = new SandboxReconciler(manager);
    }

    /**
     * Schedules {@link #runOnce()} every {@code janitor.interval}. Idempotent.
     *
     * @return this janitor
     */
    public synchronized SandboxJanitor start() {
        if (task == null) {
            task = scheduler.scheduleAtFixedRate(this::runOnce, settings.janitorInterval());
        }
        return this;
    }

    /** One pass over every item; safe to call directly (tests do). */
    public void runOnce() {
        final Instant now = manager.clock().instant();
        forEach(Set.of(WorkspaceState.OPEN), workspace -> enforceIdle(workspace, now));
        forEach(Set.of(WorkspaceState.CLOSING), workspace -> resumeClose(workspace, now));
        forEach(Set.of(WorkspaceState.CLOSED), workspace -> deleteTombstone(workspace, now));
        try {
            manager.connections().sweepExecShells(settings.execShellIdle());
        } catch (RuntimeException e) {
            log.warn("Sweeping exec shell directories failed: {}", e.getMessage(), e);
        }
        reconciler.reconcile(manager.clock().instant());
    }

    private void forEach(Set<WorkspaceState> states, Consumer<SandboxWorkspace> action) {
        WorkspaceScan scan = WorkspaceScan.builder().states(states).build();
        while (true) {
            final List<SandboxWorkspace> page;
            try {
                page = manager.store().scan(scan);
            } catch (RuntimeException e) {
                log.warn("Janitor scan of {} workspaces failed: {}", states, e.getMessage(), e);
                return;
            }
            for (SandboxWorkspace workspace : page) {
                try {
                    action.accept(workspace);
                } catch (RuntimeException e) {
                    log.warn("Janitor failed on workspace {}; the next cycle retries: {}", workspace.id(),
                            e.getMessage(), e);
                }
            }
            if (page.size() < scan.limit()) {
                return;
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }
    }

    private void enforceIdle(SandboxWorkspace workspace, Instant now) {
        for (SandboxSlot slot : workspace.slots().values()) {
            if (slot.state() == SlotState.PROVISIONING
                    && slot.provisioning().map(claim -> manager.provisioningAbandoned(claim, now)).orElse(false)) {
                manager.reclaimProvisioning(workspace.id(), slot);
                continue;
            }
            if (slot.state() != SlotState.RUNNING) {
                continue;
            }
            final Duration terminateAfter = terminateAfter(slot);
            if (idleFor(slot.lastActivityAt(), now, terminateAfter)) {
                manager.terminateSlot(workspace.id(), slot.name(), "idle for " + terminateAfter,
                        current -> current.state() == SlotState.RUNNING && current.generation() == slot.generation()
                                && idleFor(current.lastActivityAt(), manager.clock().instant(), terminateAfter));
            }
        }
        manager.store().find(workspace.id()).filter(current -> idleClosable(current, now))
                .ifPresent(current -> manager.closeProcedure(current.id(), CloseCause.IDLE,
                        fresh -> idleClosable(fresh, manager.clock().instant())));
    }

    /** A removed profile's slot gets the longest configured {@code terminateAfter}: never earlier than any live one. */
    private Duration terminateAfter(SandboxSlot slot) {
        return manager.profiles().find(slot.profile()).flatMap(SandboxProfile::terminateAfter)
                .or(() -> manager.profiles().longestTerminateAfter()).orElse(Duration.ofDays(365));
    }

    private boolean idleClosable(SandboxWorkspace workspace, Instant now) {
        if (workspace.state() != WorkspaceState.OPEN
                || workspace.slots().values().stream().anyMatch(SandboxSlot::live)) {
            return false;
        }
        Instant lastUse = workspace.createdAt();
        final Optional<Instant> opened = workspace.stateSince();
        if (opened.isPresent() && opened.get().isAfter(lastUse)) {
            lastUse = opened.get();
        }
        for (SandboxSlot slot : workspace.slots().values()) {
            final Optional<Instant> active = slot.lastActiveAt();
            if (active.isPresent() && active.get().isAfter(lastUse)) {
                lastUse = active.get();
            }
        }
        return idleFor(lastUse, now, settings.closeAfter());
    }

    private void resumeClose(SandboxWorkspace workspace, Instant now) {
        if (workspace.stateSince().map(since -> idleFor(since, now, settings.closeResumeAfter())).orElse(true)) {
            log.info("Resuming the close of workspace {}, stuck in CLOSING since {}", workspace.id(),
                    workspace.stateSince().orElse(null));
            // Only the close this pass scanned: one finished and reopened since (another closer, an idle auto-reopen)
            // is a live workspace, and a later close of it is its own closer's to finish.
            manager.closeProcedure(workspace.id(), workspace.closeCause().orElse(CloseCause.EXPLICIT),
                    current -> current.state() == WorkspaceState.CLOSING
                            && current.incarnation().equals(workspace.incarnation()));
        }
    }

    private void deleteTombstone(SandboxWorkspace workspace, Instant now) {
        if (workspace.stateSince().map(since -> idleFor(since, now, settings.closedRetention())).orElse(true)) {
            try {
                manager.store().delete(workspace.id(), workspace.version());
                log.debug("Deleted the expired CLOSED record of workspace {}", workspace.id());
            } catch (StaleVersionException e) {
                log.debug("Workspace {} changed since the scan; its record is kept", workspace.id());
            }
        }
    }

    private static boolean idleFor(Instant since, Instant now, Duration limit) {
        return Duration.between(since, now).compareTo(limit) >= 0;
    }

    /** Stops the scheduled loop. */
    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
