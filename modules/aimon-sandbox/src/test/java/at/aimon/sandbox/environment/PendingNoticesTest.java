package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.binding.SessionBookkeeping;
import at.aimon.sandbox.binding.WorkspaceSwitch;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.UnannouncedReset;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * {@link PendingNotices}: delivery through the drain (kangwoo/aimon-core#254), the switch, generation tracking, and the
 * durable unannounced reset (docs/design/workspace-sandbox-unannounced-reset.md §6.1, §6.2, §9).
 */
class PendingNoticesTest {

    private static final SessionId SESSION = SessionId.of("s");
    private static final SandboxWorkspaceId TO = SandboxWorkspaceId.of("wsm:to");
    private static final SandboxWorkspaceId OTHER = SandboxWorkspaceId.of("wsm:other");
    private static final String PRIMARY = "primary";
    private static final String RESET = "/workspace was reset";
    private static final Instant T0 = Instant.parse("2026-10-18T04:00:00.123Z");
    private static final UnannouncedReset EARLIER = UnannouncedReset.of(TO, PRIMARY, T0);

    private final List<WorkspaceSwitch> recorded = new ArrayList<>();

    /** Adapts the switch-only tests: a switch is recorded when its target is acknowledged. */
    private PendingNotices withSwitch(WorkspaceSwitch change) {
        return new PendingNotices(change, null, new FakeLedger() {
            @Override
            public void acknowledge(SessionId session, OptionalLong expectedVersion,
                    Optional<SandboxWorkspaceId> lastWorkspace, Optional<UnannouncedReset> delivered) {
                lastWorkspace.ifPresent(to -> recorded.add(change));
            }
        });
    }

    private PendingNotices announcing() {
        return withSwitch(WorkspaceSwitch.announced(SESSION, null, TO, OptionalLong.of(3)));
    }

    private static void add(PendingNotices pending, String incarnation, long generation, boolean reset,
            List<String> notices) {
        pending.connected(TO, PRIMARY, incarnation, generation, reset, notices);
    }

    @Test
    @DisplayName("core-033 §6.4: the announced switch is queued at the first connect only and recorded by its drain")
    void theAnnouncedSwitchIsRecordedByTheDrainThatReturnsIt() {
        final PendingNotices pending = announcing();

        add(pending, "i1", 1, false, List.of("sandbox provisioned in 6s"));
        add(pending, "i1", 1, false, List.of());
        assertThat(recorded).as("queued is not delivered").isEmpty();

        assertThat(pending.drain()).containsExactly(
                "this session now runs in sandbox workspace wsm:to; files and shell state from any earlier workspace "
                        + "of this session are not here",
                "sandbox provisioned in 6s");
        assertThat(recorded).hasSize(1);
        assertThat(pending.drain()).as("one-shot").isEmpty();
        add(pending, "i1", 1, false, List.of());
        assertThat(pending.drain()).as("never queued twice").isEmpty();
        assertThat(recorded).hasSize(1);
    }

    @Test
    void aSilentSwitchIsRecordedAtTheFirstConnectAndNeverQueued() {
        final PendingNotices pending = withSwitch(WorkspaceSwitch.silent(SESSION, TO, OptionalLong.empty()));

        add(pending, "i1", 1, false, List.of());
        add(pending, "i1", 1, false, List.of());

        assertThat(recorded).hasSize(1);
        assertThat(pending.drain()).isEmpty();
    }

    @Test
    void duplicatesCollapseAndADrainBeforeAnyConnectIsEmpty() {
        final PendingNotices pending = new PendingNotices();
        assertThat(pending.drain()).isEmpty();

        add(pending, "i1", 1, true, List.of(RESET));
        add(pending, "i1", 1, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
    }

    @Test
    @DisplayName("core-033 §6.5: a newer slot the connect did not reset itself is announced; a first connect is not")
    void aChangedSlotIsAnnouncedOnce() {
        final PendingNotices pending = new PendingNotices();

        add(pending, "i1", 1, false, List.of());
        assertThat(pending.drain()).as("a first connect has no baseline").isEmpty();
        add(pending, "i1", 2, false, List.of());
        add(pending, "i1", 2, false, List.of());
        assertThat(pending.drain()).singleElement().asString()
                .isEqualTo("the sandbox was re-created since this execution last used it (generation 2); files under "
                        + "/workspace from before are gone");

        add(pending, "i2", 1, false, List.of());
        assertThat(pending.drain()).as("a new incarnation too").singleElement().asString().contains("generation 1");
        add(pending, "i2", 3, true, List.of(RESET));
        assertThat(pending.drain()).as("the connect's own reset notice says it").containsExactly(RESET);
        add(pending, "i2", 2, false, List.of());
        assertThat(pending.drain()).as("an older generation: a parallel call that read the slot before").isEmpty();
    }

    @Test
    @DisplayName("review 2: a parallel call queuing the generation notice first is overtaken by the reset of the same slot")
    void theResetNoticeReplacesAGenerationNoticeForTheSameSlot() {
        final PendingNotices pending = new PendingNotices();
        add(pending, "i1", 1, false, List.of());

        add(pending, "i1", 2, false, List.of());
        add(pending, "i1", 2, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
    }

    @Test
    void twoConcurrentConnectsOfANewerSlotAnnounceItOnce() throws Exception {
        final PendingNotices pending = new PendingNotices();
        add(pending, "i1", 1, false, List.of());
        concurrently(8, i -> add(pending, "i1", 2, false, List.of()));

        assertThat(pending.drain()).hasSize(1);
    }

    @Test
    void concurrentDrainsReturnEachNoticeOnce() throws Exception {
        final PendingNotices pending = new PendingNotices();
        final List<String> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            all.add("n" + i);
        }
        add(pending, "i1", 1, false, all);
        final ExecutorService threads = Executors.newFixedThreadPool(4);
        final List<String> seen = new ArrayList<>();
        try {
            final List<Future<List<String>>> drains = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                drains.add(threads.submit(pending::drain));
            }
            for (Future<List<String>> drain : drains) {
                seen.addAll(drain.get(10, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(all);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the durable unannounced reset (docs/design/workspace-sandbox-unannounced-reset.md)
    // ----------------------------------------------------------------------------------------------------------------

    private static SessionBookkeeping bookkeeping(long version, UnannouncedReset reset) {
        return SessionBookkeeping.of(SESSION,
                Optional.of(SessionSandboxOptions.builder().session(SESSION)
                        .setBy(WorkspaceOwner.of(TenantId.DEFAULT, Principal.user("alice"))).unannouncedReset(reset)
                        .updatedAt(T0).version(version).build()));
    }

    @Test
    @DisplayName("§6.1: a reset is durable before its notice can be drained, and the drain acknowledges the stored value")
    void aResetIsMarkedBeforeItIsDrainableAndAcknowledgedByItsDrain() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(null, SessionBookkeeping.of(SESSION, Optional.empty()),
                ledger);
        ledger.duringMark = () -> assertThat(pending.drain()).as("nothing drainable before the mark").isEmpty();

        add(pending, "i1", 1, true, List.of(RESET));

        assertThat(ledger.marks).containsExactly(TO + "/" + PRIMARY);
        assertThat(ledger.acks).as("queued is not delivered").isEmpty();
        assertThat(pending.drain()).containsExactly(RESET);
        assertThat(ledger.acks).singleElement().isEqualTo(new Ack(OptionalLong.of(5), null, ledger.stored(0)));
        assertThat(pending.drain()).isEmpty();
        assertThat(ledger.acks).hasSize(1);
    }

    @Test
    @DisplayName("§6.1: an earlier execution's reset is replayed once, at the first connect of its slot, and acknowledged")
    void anUndeliveredResetIsReplayedOnceAtTheFirstConnect() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(null, bookkeeping(4, EARLIER), ledger);

        add(pending, "i2", 1, false, List.of());
        add(pending, "i2", 1, false, List.of());

        assertThat(pending.drain()).containsExactly("/workspace was reset at 2026-10-18T04:00:00Z and this session may "
                + "not have been told: files and shell state (cwd, exported variables) from before then are gone");
        assertThat(ledger.marks).as("a replay writes nothing").isEmpty();
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(4), null, EARLIER));
        add(pending, "i2", 1, false, List.of());
        assertThat(pending.drain()).as("once per instance").isEmpty();
    }

    @Test
    void noReplayForAnotherWorkspaceOrSlot() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices otherWorkspace = new PendingNotices(null, bookkeeping(4, EARLIER), ledger);
        otherWorkspace.connected(OTHER, PRIMARY, "i1", 1, false, List.of());
        final PendingNotices otherSlot = new PendingNotices(null, bookkeeping(4, EARLIER), ledger);
        otherSlot.connected(TO, "exp-a", "i1", 1, false, List.of());

        assertThat(otherWorkspace.drain()).isEmpty();
        assertThat(otherSlot.drain()).isEmpty();
        assertThat(ledger.acks).as("the field waits for a connect of its slot").isEmpty();
    }

    @Test
    @DisplayName("§6.1: a first connect that resets again tells its own notice only, and acknowledges the newer value")
    void aFirstConnectThatResetsAgainIsNotReplayed() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(null, bookkeeping(4, EARLIER), ledger);

        add(pending, "i2", 1, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(5), null, ledger.stored(0)));
    }

    @Test
    @DisplayName("§7: a mark that fails leaves the older field of the same slot to this delivery")
    void aFailedMarkAcknowledgesTheOlderValue() {
        final FakeLedger ledger = new FakeLedger();
        ledger.failMarks = true;
        final PendingNotices pending = new PendingNotices(null, bookkeeping(4, EARLIER), ledger);

        add(pending, "i2", 1, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(4), null, EARLIER));
    }

    @Test
    @DisplayName("§7: two resets before a drain — both notices, one acknowledgement of the newer value")
    void twoResetsBeforeADrainAcknowledgeTheNewer() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(null, SessionBookkeeping.of(SESSION, Optional.empty()),
                ledger);

        add(pending, "i1", 1, true, List.of(RESET));
        add(pending, "i1", 2, true, List.of("the sandbox was recreated (generation 2)"));

        assertThat(pending.drain()).containsExactly(RESET, "the sandbox was recreated (generation 2)");
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(6), null, ledger.stored(1)));
    }

    @Test
    @DisplayName("§5.2: switch and reset in one drain — one acknowledgement, with the version of the instance's own mark")
    void aSwitchAndAResetAreAcknowledgedTogetherAtTheMarkedVersion() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(
                WorkspaceSwitch.announced(SESSION, OTHER, TO, OptionalLong.of(4)), bookkeeping(4, null), ledger);

        add(pending, "i1", 1, true, List.of(RESET));

        assertThat(pending.drain()).hasSize(2);
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(5), TO, ledger.stored(0)));
    }

    @Test
    @DisplayName("§12: a reset after the drain that delivered the switch writes the switch along, once")
    void aResetAfterADeliveredSwitchWritesItAlong() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(
                WorkspaceSwitch.announced(SESSION, OTHER, TO, OptionalLong.of(4)), bookkeeping(4, null), ledger);
        add(pending, "i1", 1, false, List.of());
        assertThat(pending.drain()).hasSize(1);
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(4), TO, null));

        add(pending, "i1", 2, true, List.of(RESET));
        add(pending, "i1", 3, true, List.of("the sandbox was recreated (generation 3)"));

        assertThat(ledger.marksSwitch).containsExactly(OptionalLong.of(4) + "/" + TO, "-");
        pending.drain();
        assertThat(ledger.acks).as("the switch is not acknowledged twice").last()
                .isEqualTo(new Ack(OptionalLong.of(6), null, ledger.stored(1)));
    }

    @Test
    @DisplayName("§12: a drain that delivers the switch during a parallel mark is acknowledged again at the mark's version")
    void aSwitchDeliveredDuringAMarkIsAcknowledgedAgain() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(
                WorkspaceSwitch.announced(SESSION, OTHER, TO, OptionalLong.of(4)), bookkeeping(4, null), ledger);
        add(pending, "i1", 1, false, List.of());
        ledger.duringMark = () -> assertThat(pending.drain()).as("the switch, delivered mid-mark").hasSize(1);

        add(pending, "i1", 2, true, List.of(RESET));

        assertThat(ledger.marksSwitch).containsExactly("-");
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(4), TO, null),
                new Ack(OptionalLong.of(5), TO, null));
    }

    @Test
    @DisplayName("§12: a silent switch recorded at the first connect is written along by a later mark")
    void aSilentSwitchIsWrittenAlongByALaterMark() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(WorkspaceSwitch.silent(SESSION, TO, OptionalLong.empty()),
                SessionBookkeeping.of(SESSION, Optional.empty()), ledger);
        add(pending, "i1", 1, false, List.of());
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.empty(), TO, null));

        add(pending, "i1", 2, true, List.of(RESET));

        assertThat(ledger.marksSwitch).containsExactly(OptionalLong.empty() + "/" + TO);
    }

    @Test
    @DisplayName("§5.2: a mark before the switch is delivered carries no switch: the drain's acknowledgement does")
    void aMarkBeforeTheDrainCarriesNoSwitch() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(
                WorkspaceSwitch.announced(SESSION, OTHER, TO, OptionalLong.of(4)), bookkeeping(4, null), ledger);

        add(pending, "i1", 1, true, List.of(RESET));

        assertThat(ledger.marksSwitch).containsExactly("-");
    }

    @Test
    @DisplayName("§3.3: a queue without bookkeeping — a fork, a routine — never writes the session's record")
    void withoutBookkeepingNothingIsWritten() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(null, null, ledger);

        add(pending, "i1", 1, true, List.of(RESET));
        pending.failed(TO, PRIMARY, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
        assertThat(ledger.marks).isEmpty();
        assertThat(ledger.acks).isEmpty();
    }

    @Test
    @DisplayName("§6.3: a failed connect after a reset marks and queues it, and sets no baseline")
    void aFailedConnectAfterAResetIsKeptAndQueued() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(
                WorkspaceSwitch.announced(SESSION, OTHER, TO, OptionalLong.of(4)), bookkeeping(4, EARLIER), ledger);

        pending.failed(TO, PRIMARY, true, List.of(RESET));
        assertThat(ledger.marks).hasSize(1);
        add(pending, "i2", 1, false, List.of());
        add(pending, "i2", 2, false, List.of());

        assertThat(pending.drain()).as("the switch waits for the first success; no replay of what this one said")
                .containsExactly(RESET, WorkspaceSwitch.announced(SESSION, OTHER, TO, OptionalLong.of(4)).notice(),
                        "the sandbox was re-created since this execution last used it (generation 2); files under "
                                + "/workspace from before are gone");
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(5), TO, ledger.stored(0)));
    }

    @Test
    @DisplayName("review 1: a replay queued by a parallel call goes when this instance's own reset of the slot arrives")
    void aResetNoticeReplacesAQueuedReplay() {
        final FakeLedger ledger = new FakeLedger();
        final PendingNotices pending = new PendingNotices(null, bookkeeping(4, EARLIER), ledger);

        add(pending, "i2", 1, false, List.of());
        add(pending, "i2", 1, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
        assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(5), null, ledger.stored(0)));
    }

    @Test
    @DisplayName("§9: parallel connects, one of which resets — the reset is told once and nothing is replayed")
    void parallelConnectsWithOneResetTellItOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            final FakeLedger ledger = new FakeLedger();
            final PendingNotices pending = new PendingNotices(null, bookkeeping(4, EARLIER), ledger);

            concurrently(8, i -> add(pending, "i2", 1, i == 3, i == 3 ? List.of(RESET) : List.of()));

            assertThat(pending.drain()).containsExactly(RESET);
            assertThat(ledger.acks).containsExactly(new Ack(OptionalLong.of(5), null, ledger.stored(0)));
        }
    }

    private interface Call {
        void run(int index) throws Exception;
    }

    private static void concurrently(int threadCount, Call call) throws Exception {
        final ExecutorService threads = Executors.newFixedThreadPool(threadCount);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                calls.add(threads.submit(() -> {
                    start.await();
                    call.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : calls) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }
    }

    private record Ack(OptionalLong expectedVersion, SandboxWorkspaceId lastWorkspace, UnannouncedReset delivered) {
    }

    /** Stores each mark at the next version, one second later than the last; records every acknowledgement. */
    private static class FakeLedger implements NoticeLedger {
        final List<String> marks = Collections.synchronizedList(new ArrayList<>());
        /** The switch each mark was asked to write along: {@code "<version>/<target>"}, or {@code "-"}. */
        final List<String> marksSwitch = Collections.synchronizedList(new ArrayList<>());
        final List<Ack> acks = Collections.synchronizedList(new ArrayList<>());
        private final List<UnannouncedReset> stored = Collections.synchronizedList(new ArrayList<>());
        boolean failMarks;
        Runnable duringMark = () -> {
        };

        UnannouncedReset stored(int index) {
            return stored.get(index);
        }

        @Override
        public synchronized Optional<SessionSandboxOptions> markResetUnannounced(SessionId session,
                SandboxWorkspaceId workspace, String slot, OptionalLong switchVersion,
                Optional<SandboxWorkspaceId> lastWorkspace) {
            marks.add(workspace + "/" + slot);
            marksSwitch.add(lastWorkspace.map(to -> switchVersion + "/" + to).orElse("-"));
            duringMark.run();
            if (failMarks) {
                return Optional.empty();
            }
            final UnannouncedReset reset = UnannouncedReset.of(workspace, slot, T0.plusSeconds(stored.size() + 1L));
            stored.add(reset);
            return Optional.of(SessionSandboxOptions.builder().session(session)
                    .setBy(WorkspaceOwner.of(TenantId.DEFAULT, Principal.user("alice"))).unannouncedReset(reset)
                    .updatedAt(T0).version(4 + stored.size()).build());
        }

        @Override
        public void acknowledge(SessionId session, OptionalLong expectedVersion,
                Optional<SandboxWorkspaceId> lastWorkspace, Optional<UnannouncedReset> delivered) {
            acks.add(new Ack(expectedVersion, lastWorkspace.orElse(null), delivered.orElse(null)));
        }
    }
}
