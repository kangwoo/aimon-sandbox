package at.aimon.sandbox.workspace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ProviderSandboxState;
import at.aimon.sandbox.provider.SandboxLabels;

/**
 * Sandbox reconciliation (docs/design/workspace-sandbox.md §10.4 item 2): compares what the provider lists under this
 * deployment's labels with the workspace records, destroys what is provably nobody's, and confirms missing sandboxes
 * LOST. It is the only mechanism that reclaims what an {@code InMemory} store forgot on restart — which is also why an
 * {@code InMemory} store must never run on more than one node of a deployment: its janitor would reclaim the other
 * nodes' sandboxes as orphans (§5.3).
 *
 * <p>
 * One {@link #reconcile} pass: scan every record <b>first</b>, list second (a record always exists before its first
 * sandbox, so a sandbox created after the scan belongs to a record the scan may have missed — the grace covers it);
 * classify each listed sandbox with {@link #classify}; re-read the record and re-classify before every destroy;
 * then, for every RUNNING or PAUSED slot whose sandbox was not listed (or listed TERMINATED/FAILED), ask
 * {@code status} and confirm LOST only after {@code lost-confirm-after} of absence. A list or scan failure ends the
 * pass: nothing is inferred from a partial view. Every write is CAS-guarded on generation, provider ref and state, and
 * destroy is idempotent, so two janitors may run it at once.
 *
 * <p>
 * Not here yet: DRIFT (paused/running disagreement — nothing pauses before step 7) and volume reconciliation (item 3,
 * step 5).
 */
final class SandboxReconciler {

    private static final Logger log = LoggerFactory.getLogger(SandboxReconciler.class);

    /** What reconciliation makes of one listed sandbox (the §10.4 table). */
    enum Verdict {
        /** Its labels do not say what it is, or it failed the label comparison (§6.3): not ours to judge. */
        SKIP(false, false),
        /** The record's current sandbox. */
        OK(false, false),
        /** Newer than the record, or of a PROVISIONING generation: someone is creating it. */
        IN_FLIGHT(false, false),
        /** No record, another incarnation, no such slot, or a TERMINATED generation. */
        ORPHAN(true, true),
        /** An older generation than the record's. */
        STALE(true, false),
        /** The generation failed and the record points nobody at it. */
        FAILED_LEFTOVER(true, false),
        /** Same generation as a RUNNING/PAUSED slot, but not its recorded sandbox (a takeover race). */
        DUPLICATE(true, true);

        private final boolean destroys;
        private final boolean afterGrace;

        Verdict(boolean destroys, boolean afterGrace) {
            this.destroys = destroys;
            this.afterGrace = afterGrace;
        }

        boolean destroys() {
            return destroys;
        }

        boolean afterGrace() {
            return afterGrace;
        }
    }

    private final SandboxWorkspaceManager manager;
    private final SandboxSettings settings;
    /** Node-local first sightings: the grace clock when the provider gives no {@code createdAt}, and a skew guard. */
    private final Map<ProviderSandboxRef, Instant> firstSeen = new HashMap<>();
    private final Set<ProviderSandboxRef> warned = new HashSet<>();

    SandboxReconciler(SandboxWorkspaceManager manager) {
        this.manager = manager;
        this.settings = manager.settings();
    }

    /**
     * One pass. Synchronized: the janitor's pass and a direct call (tests) must not interleave the node-local clock.
     *
     * @param now
     *            the pass's time
     */
    synchronized void reconcile(Instant now) {
        final List<SandboxWorkspace> records;
        final List<ProviderSandbox> listed;
        try {
            records = scanAll();
        } catch (RuntimeException e) {
            log.warn("Reconciliation skipped: the workspace scan failed: {}", e.getMessage());
            return;
        }
        try {
            listed = manager.provider()
                    .list(Map.of(SandboxLabels.MANAGED, "true", SandboxLabels.DEPLOYMENT, settings.deployment()));
        } catch (RuntimeException e) {
            log.warn("Reconciliation skipped: listing the deployment's sandboxes failed: {}", e.getMessage());
            return;
        }
        final Map<String, SandboxWorkspace> byHash = new HashMap<>();
        records.forEach(record -> byHash.put(SandboxLabels.h(record.id().value()), record));
        final Map<ProviderSandboxRef, ProviderSandbox> seen = new HashMap<>();
        listed.forEach(sandbox -> seen.putIfAbsent(sandbox.ref(), sandbox));
        firstSeen.keySet().retainAll(seen.keySet());
        warned.retainAll(seen.keySet());
        seen.keySet().forEach(ref -> firstSeen.putIfAbsent(ref, now));

        for (ProviderSandbox sandbox : seen.values()) {
            try {
                act(byHash.get(sandbox.labels().get(SandboxLabels.WORKSPACE)), sandbox, now);
            } catch (RuntimeException e) {
                log.warn("Reconciling a sandbox of workspace-label {} failed; the next pass retries: {}",
                        sandbox.labels().get(SandboxLabels.WORKSPACE), e.getMessage());
            }
        }
        for (SandboxWorkspace record : records) {
            for (SandboxSlot slot : record.slots().values()) {
                if ((slot.state() != SlotState.RUNNING && slot.state() != SlotState.PAUSED)
                        || slot.providerRef().isEmpty()) {
                    continue;
                }
                try {
                    final ProviderSandbox listedAs = seen.get(slot.providerRef().get());
                    if (listedAs == null || gone(listedAs.state())) {
                        lostCheck(record, slot, now);
                    } else if (slot.missingSince().isPresent()) {
                        manager.clearMissing(record.id(), slot);
                    }
                } catch (RuntimeException e) {
                    log.warn("Checking slot {}/{} for a lost sandbox failed; the next pass retries: {}", record.id(),
                            slot.name(), e.getMessage());
                }
            }
        }
    }

    private List<SandboxWorkspace> scanAll() {
        final List<SandboxWorkspace> all = new ArrayList<>();
        WorkspaceScan scan = WorkspaceScan.builder().build();
        while (true) {
            final List<SandboxWorkspace> page = manager.store().scan(scan);
            all.addAll(page);
            if (page.size() < scan.limit()) {
                return all;
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }
    }

    private void act(SandboxWorkspace record, ProviderSandbox sandbox, Instant now) {
        final Verdict verdict = classify(record, sandbox);
        if (verdict == Verdict.SKIP) {
            if (unparseable(sandbox) && warned.add(sandbox.ref())) {
                log.warn(
                        "A sandbox of deployment {} carries unparseable aimon.at labels (workspace-label {}); "
                                + "reconciliation leaves it alone",
                        settings.deployment(), sandbox.labels().get(SandboxLabels.WORKSPACE));
            }
            return;
        }
        final boolean graceOver = !age(sandbox, now).minus(settings.orphanGrace()).isNegative();
        final Verdict effective = verdict == Verdict.IN_FLIGHT && graceOver && inFlightByGeneration(record, sandbox)
                ? Verdict.ORPHAN
                : verdict;
        if (!effective.destroys() || effective.afterGrace() && !graceOver) {
            return;
        }
        if (record == null) {
            // Nothing to re-read by hash: safety is the ordering invariant (a record exists before its sandbox) plus
            // orphan-grace > provision-timeout, validated at startup.
            manager.destroyQuietly(sandbox.ref());
            log.info("Reconciliation destroyed an orphan sandbox of workspace-label {} (no record)",
                    sandbox.labels().get(SandboxLabels.WORKSPACE));
            return;
        }
        final Optional<SandboxWorkspace> fresh = manager.store().find(record.id());
        final Verdict again = fresh.map(current -> {
            final Verdict v = classify(current, sandbox);
            return v == Verdict.IN_FLIGHT && graceOver && inFlightByGeneration(current, sandbox) ? Verdict.ORPHAN : v;
        }).orElse(Verdict.ORPHAN);
        if (again != effective) {
            log.debug("Workspace {} changed since the scan ({} -> {}); its sandbox is left for the next pass",
                    record.id(), effective, again);
            return;
        }
        manager.destroyQuietly(sandbox.ref());
        final SandboxWorkspace current = fresh.orElse(record);
        final SandboxSlot slot = current.slot(sandbox.labels().get(SandboxLabels.SLOT)).orElse(null);
        manager.emit(effective == Verdict.DUPLICATE
                ? SandboxEvent.Type.DUPLICATE_DESTROYED
                : SandboxEvent.Type.ORPHAN_DESTROYED, current, slot, cause(effective, sandbox));
    }

    private static String cause(Verdict verdict, ProviderSandbox sandbox) {
        final String generation = "generation " + sandbox.labels().get(SandboxLabels.GENERATION);
        switch (verdict) {
            case STALE :
                return "stale " + generation;
            case FAILED_LEFTOVER :
                return "failed-leftover " + generation;
            case DUPLICATE :
                return "duplicate " + generation;
            default :
                return "orphan " + generation;
        }
    }

    /** IN-FLIGHT because its generation is ahead of the record's (not a PROVISIONING claim): escalates after grace. */
    private static boolean inFlightByGeneration(SandboxWorkspace record, ProviderSandbox sandbox) {
        return record != null && record.slot(sandbox.labels().get(SandboxLabels.SLOT))
                .map(slot -> parseGeneration(sandbox) > slot.generation()).orElse(false);
    }

    /**
     * {@code min(now − createdAt, now − firstSeen)}: a skewed server clock can only delay a destroy, never hasten it.
     */
    private Duration age(ProviderSandbox sandbox, Instant now) {
        final Duration seen = Duration.between(firstSeen.getOrDefault(sandbox.ref(), now), now);
        return sandbox.createdAt().map(created -> Duration.between(created, now))
                .filter(created -> created.compareTo(seen) < 0).orElse(seen);
    }

    private void lostCheck(SandboxWorkspace record, SandboxSlot slot, Instant now) {
        final Optional<ProviderSandbox> status = manager.provider().status(slot.providerRef().get());
        if (status.isPresent() && !gone(status.get().state())) {
            if (slot.missingSince().isPresent()) {
                manager.clearMissing(record.id(), slot);
            }
            return;
        }
        if (slot.missingSince().isEmpty()) {
            manager.markMissing(record.id(), slot, now);
        } else if (!now.isBefore(slot.missingSince().get().plus(settings.lostConfirmAfter()))) {
            manager.confirmLost(record.id(), slot, now);
        }
    }

    private static boolean gone(ProviderSandboxState state) {
        return state == ProviderSandboxState.TERMINATED || state == ProviderSandboxState.FAILED;
    }

    private static boolean unparseable(ProviderSandbox sandbox) {
        return parseGeneration(sandbox) < 0 || !sandbox.labels().containsKey(SandboxLabels.SLOT)
                || !sandbox.labels().containsKey(SandboxLabels.INCARNATION);
    }

    private static long parseGeneration(ProviderSandbox sandbox) {
        try {
            final long generation = Long.parseLong(sandbox.labels().getOrDefault(SandboxLabels.GENERATION, ""));
            return generation < 1 ? -1 : generation;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The §10.4 table, first matching row wins. Pure: the verdict before grace (an ORPHAN or DUPLICATE is destroyed
     * only once older than {@code orphan-grace}; an IN_FLIGHT ahead of the record's generation becomes ORPHAN then).
     *
     * @param record
     *            the record the sandbox's workspace label points at, or {@code null}
     * @param sandbox
     *            the listed sandbox
     * @return the verdict
     */
    static Verdict classify(SandboxWorkspace record, ProviderSandbox sandbox) {
        if (unparseable(sandbox)) {
            return Verdict.SKIP;
        }
        final long generation = parseGeneration(sandbox);
        final String slotName = sandbox.labels().get(SandboxLabels.SLOT);
        final String incarnation = sandbox.labels().get(SandboxLabels.INCARNATION);
        final SandboxSlot slot = record == null ? null : record.slot(slotName).orElse(null);
        if (slot != null && slot.generation() == generation && slot.state() == SlotState.FAILED
                && slot.failure().map(f -> "labels".equals(f.step())).orElse(false)) {
            // §6.3: a sandbox that failed the label comparison may not be ours; only provider expiry removes it.
            return Verdict.SKIP;
        }
        if (record == null || !record.incarnation().equals(incarnation) || slot == null) {
            return Verdict.ORPHAN;
        }
        if (generation > slot.generation()) {
            return Verdict.IN_FLIGHT;
        }
        if (generation < slot.generation()) {
            return Verdict.STALE;
        }
        switch (slot.state()) {
            case PROVISIONING :
                return Verdict.IN_FLIGHT;
            case FAILED :
                return Verdict.FAILED_LEFTOVER;
            case TERMINATED :
                return Verdict.ORPHAN;
            case RUNNING :
            case PAUSED :
            default :
                return slot.providerRef().map(ref -> ref.equals(sandbox.ref())).orElse(false)
                        ? Verdict.OK
                        : Verdict.DUPLICATE;
        }
    }
}
