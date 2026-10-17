package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.binding.WorkspaceSwitch;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;

/** {@link PendingNotices}: delivery through the drain (kangwoo/aimon-core#254), the switch, generation tracking. */
class PendingNoticesTest {

    private static final SessionId SESSION = SessionId.of("s");
    private static final SandboxWorkspaceId TO = SandboxWorkspaceId.of("wsm:to");
    private static final String RESET = "/workspace was reset";

    private final List<WorkspaceSwitch> recorded = new ArrayList<>();

    private PendingNotices announcing() {
        return new PendingNotices(WorkspaceSwitch.announced(SESSION, null, TO, OptionalLong.of(3)), recorded::add);
    }

    @Test
    @DisplayName("core-033 §6.4: the announced switch is queued at the first connect only and recorded by its drain")
    void theAnnouncedSwitchIsRecordedByTheDrainThatReturnsIt() {
        final PendingNotices pending = announcing();

        pending.addAll("i1", 1, false, List.of("sandbox provisioned in 6s"));
        pending.addAll("i1", 1, false, List.of());
        assertThat(recorded).as("queued is not delivered").isEmpty();

        assertThat(pending.drain()).containsExactly(
                "this session now runs in sandbox workspace wsm:to; files and shell state from any earlier workspace "
                        + "of this session are not here",
                "sandbox provisioned in 6s");
        assertThat(recorded).hasSize(1);
        assertThat(pending.drain()).as("one-shot").isEmpty();
        pending.addAll("i1", 1, false, List.of());
        assertThat(pending.drain()).as("never queued twice").isEmpty();
        assertThat(recorded).hasSize(1);
    }

    @Test
    void aSilentSwitchIsRecordedAtTheFirstConnectAndNeverQueued() {
        final PendingNotices pending = new PendingNotices(WorkspaceSwitch.silent(SESSION, TO, OptionalLong.empty()),
                recorded::add);

        pending.addAll("i1", 1, false, List.of());
        pending.addAll("i1", 1, false, List.of());

        assertThat(recorded).hasSize(1);
        assertThat(pending.drain()).isEmpty();
    }

    @Test
    void duplicatesCollapseAndADrainBeforeAnyConnectIsEmpty() {
        final PendingNotices pending = new PendingNotices();
        assertThat(pending.drain()).isEmpty();

        pending.addAll("i1", 1, true, List.of(RESET));
        pending.addAll("i1", 1, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
    }

    @Test
    @DisplayName("core-033 §6.5: a newer slot the connect did not reset itself is announced; a first connect is not")
    void aChangedSlotIsAnnouncedOnce() {
        final PendingNotices pending = new PendingNotices();

        pending.addAll("i1", 1, false, List.of());
        assertThat(pending.drain()).as("a first connect has no baseline").isEmpty();
        pending.addAll("i1", 2, false, List.of());
        pending.addAll("i1", 2, false, List.of());
        assertThat(pending.drain()).singleElement().asString()
                .isEqualTo("the sandbox was re-created since this execution last used it (generation 2); files under "
                        + "/workspace from before are gone");

        pending.addAll("i2", 1, false, List.of());
        assertThat(pending.drain()).as("a new incarnation too").singleElement().asString().contains("generation 1");
        pending.addAll("i2", 3, true, List.of(RESET));
        assertThat(pending.drain()).as("the connect's own reset notice says it").containsExactly(RESET);
        pending.addAll("i2", 2, false, List.of());
        assertThat(pending.drain()).as("an older generation: a parallel call that read the slot before").isEmpty();
    }

    @Test
    @DisplayName("review 2: a parallel call queuing the generation notice first is overtaken by the reset of the same slot")
    void theResetNoticeReplacesAGenerationNoticeForTheSameSlot() {
        final PendingNotices pending = new PendingNotices();
        pending.addAll("i1", 1, false, List.of());

        pending.addAll("i1", 2, false, List.of());
        pending.addAll("i1", 2, true, List.of(RESET));

        assertThat(pending.drain()).containsExactly(RESET);
    }

    @Test
    void twoConcurrentConnectsOfANewerSlotAnnounceItOnce() throws Exception {
        final PendingNotices pending = new PendingNotices();
        pending.addAll("i1", 1, false, List.of());
        final ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(threads.submit(() -> {
                    start.await();
                    pending.addAll("i1", 2, false, List.of());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(pending.drain()).hasSize(1);
    }

    @Test
    void concurrentDrainsReturnEachNoticeOnce() throws Exception {
        final PendingNotices pending = new PendingNotices();
        final List<String> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            all.add("n" + i);
        }
        pending.addAll("i1", 1, false, all);
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
}
