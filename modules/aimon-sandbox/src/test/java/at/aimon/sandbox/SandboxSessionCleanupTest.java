package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.sandbox.binding.SlotChoice;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.workspace.CloseCause;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOwner;
import at.aimon.sandbox.workspace.WorkspaceState;

/**
 * {@link SandboxSessionCleanup}: what a deleted session leaves behind (kangwoo/aimon-core#253,
 * docs/design/workspace-sandbox-core-033.md §6.3, §9). The background executor queues; each test runs it by hand.
 */
class SandboxSessionCleanupTest {

    private static final Principal BOB = Principal.user("bob");

    private final QueueingExecutor background = new QueueingExecutor();
    private final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
    private final SessionId session = SessionId.generate();
    private SandboxHarness harness = SandboxHarness.builder().sessionRecords(records).background(background).build();

    @AfterEach
    void close() {
        harness.close();
    }

    private SandboxWorkspaceId derived() {
        return SandboxWorkspaceId.of("ws:" + session.value());
    }

    private Optional<SandboxWorkspace> record() {
        return harness.store.find(derived());
    }

    private long events(SandboxEvent.Type type) {
        return harness.events.stream().filter(e -> e.type() == type).count();
    }

    /** A session with a record owned by ALICE, an options record and a RUNNING {@code ws:{sid}}. */
    private void aliveWithOptions() throws Exception {
        records.provision(session, "agent", ALICE);
        bash(harness.mainTurn(session, ALICE), "echo old > /workspace/repo/f");
        harness.sandbox.workspaces().setSessionOptions(session,
                SessionSandboxOptions.Update.rule(UnattachedRule.PER_SESSION), ALICE);
        background.runAll();
    }

    /** What core's router does: delete the record, then tell the listener under the lease. */
    private void delete() {
        records.delete(session);
        harness.sessionDeleted(session, ALICE);
    }

    @Test
    @DisplayName("core-033 §6.3: under the lease only store writes — options gone, ws:{sid} CLOSING; the rest deferred")
    void theListenerMarksAndDefers() throws Exception {
        aliveWithOptions();
        harness.faults.resetCounts();

        delete();

        assertThat(harness.sessionOptions.find(session)).isEmpty();
        assertThat(harness.events).filteredOn(e -> e.type() == SandboxEvent.Type.SESSION_OPTIONS_CLEARED).last()
                .satisfies(e -> {
                    assertThat(e.cause()).isEqualTo("session deleted");
                    assertThat(e.actor()).isEmpty();
                });
        assertThat(record().orElseThrow().state()).isEqualTo(WorkspaceState.CLOSING);
        assertThat(record().orElseThrow().closeCause()).contains(CloseCause.SESSION_DELETED);
        assertThat(harness.faults.calls(Operation.DESTROY) + harness.faults.calls(Operation.LIST))
                .as("no provider call on the calling thread").isZero();
        assertThat(events(SandboxEvent.Type.WORKSPACE_CLOSED)).as("emitted once, when it is CLOSED").isZero();

        background.runAll();

        assertThat(record()).isEmpty();
        assertThat(harness.local.sandboxCount()).isZero();
        assertThat(events(SandboxEvent.Type.WORKSPACE_CLOSED)).isOne();
        assertThat(harness.events).filteredOn(e -> e.type() == SandboxEvent.Type.WORKSPACE_PURGED).singleElement()
                .satisfies(e -> assertThat(e.cause()).isEqualTo("session deleted"));
    }

    @Test
    @DisplayName("review 1 B1: a re-created session hears 'being removed', never the old sandbox; then a fresh one")
    void aRecreatedSessionNeverReachesTheDeletedOnesSandbox() throws Exception {
        aliveWithOptions();
        final String oldIncarnation = record().orElseThrow().incarnation();
        delete();
        harness.faults.resetCounts();

        // The host re-creates the session under the same id, owned by someone else, before the deferred close ran.
        records.provision(session, "agent", BOB);
        assertThatThrownBy(() -> bash(harness.mainTurn(session, BOB), "cat /workspace/repo/f"))
                .hasMessageContaining("being removed; retry shortly");
        assertThat(harness.faults.calls(Operation.CREATE) + harness.faults.calls(Operation.RUN)).isZero();

        background.runAll();
        final ExecutionEnvironment fresh = harness.mainTurn(session, BOB);
        assertThat(bash(fresh, "cat /workspace/repo/f 2>/dev/null; echo fresh").stdout()).isEqualTo("fresh\n");
        assertThat(record().orElseThrow().incarnation()).isNotEqualTo(oldIncarnation);
        assertThat(record().orElseThrow().owner()).isEqualTo(WorkspaceOwner.of(TenantId.DEFAULT, BOB));
    }

    @Test
    @DisplayName("core-033 §6.3: a dropped deferred task — the janitor resumes the close and deletes the record")
    void theJanitorFinishesADroppedDeletion() throws Exception {
        aliveWithOptions();
        delete();
        background.drop();

        harness.sandbox.janitor().runOnce();
        assertThat(record().orElseThrow().state()).as("before close-resume-after").isEqualTo(WorkspaceState.CLOSING);

        harness.clock.advance(Duration.ofMinutes(6));
        harness.sandbox.janitor().runOnce();

        assertThat(record()).isEmpty();
        assertThat(harness.local.sandboxCount()).isZero();
        records.provision(session, "agent", BOB);
        assertThat(bash(harness.mainTurn(session, BOB), "echo new").stdout()).isEqualTo("new\n");
    }

    @Test
    @DisplayName("review 1 n1: an idle-closed and an explicitly closed ws:{sid} are marked and deleted, no sandbox call")
    void closedRecordsAreDeletedWithoutClosedRetention() throws Exception {
        aliveWithOptions();
        harness.sandbox.manager().close(derived(), ALICE);
        assertThat(record().orElseThrow().closeCause()).contains(CloseCause.EXPLICIT);
        harness.faults.resetCounts();

        delete();
        assertThat(record().orElseThrow().state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(record().orElseThrow().closeCause()).contains(CloseCause.SESSION_DELETED);
        background.runAll();

        assertThat(record()).isEmpty();
        assertThat(harness.faults.calls(Operation.DESTROY)).isZero();

        final SessionId idle = SessionId.generate();
        records.provision(idle, "agent", ALICE);
        bash(harness.mainTurn(idle, ALICE), "true");
        final SandboxWorkspaceId idleId = SandboxWorkspaceId.of("ws:" + idle.value());
        for (int day = 0; day < 5 && harness.store.find(idleId).orElseThrow().state() != WorkspaceState.CLOSED; day++) {
            harness.clock.advance(Duration.ofDays(1));
            harness.sandbox.janitor().runOnce();
        }
        assertThat(harness.store.find(idleId).orElseThrow().closeCause()).contains(CloseCause.IDLE);
        records.delete(idle);
        harness.sessionDeleted(idle, ALICE);
        background.drop();
        harness.sandbox.janitor().runOnce();
        assertThat(harness.store.find(idleId)).as("the janitor deletes it on its first pass").isEmpty();
    }

    @Test
    @DisplayName("core-033 §6.3: an attached managed workspace is shared — it stays OPEN with its sandbox")
    void anAttachedManagedWorkspaceIsKept() throws Exception {
        records.provision(session, "agent", ALICE);
        final SandboxWorkspaceId managed = harness.sandbox.workspaces().create(CreateWorkspace.named("team"), ALICE)
                .id();
        harness.sandbox.workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(managed), ALICE);
        bash(harness.mainTurn(session, ALICE), "true");

        delete();
        background.runAll();

        assertThat(harness.sessionOptions.find(session)).isEmpty();
        assertThat(harness.store.find(managed).orElseThrow().state()).isEqualTo(WorkspaceState.OPEN);
        assertThat(harness.local.sandboxCount()).isOne();
    }

    @Test
    void anUnknownSessionAndASecondFiringAreNoOps() throws Exception {
        harness.sessionDeleted(SessionId.generate(), null);
        assertThat(background.queued()).isZero();

        aliveWithOptions();
        delete();
        background.runAll();
        final int eventsAfterFirst = harness.events.size();
        harness.sessionDeleted(session, ALICE);
        background.runAll();

        assertThat(harness.events).hasSize(eventsAfterFirst);
    }

    @Test
    @DisplayName("core-033 §7: a failing store never escapes the listener")
    void storeFailuresAreContained() {
        harness.close();
        harness = SandboxHarness.builder().sessionRecords(records).background(background)
                .sessionOptions(new DelegatingOptionsStore(new InMemorySessionOptionsStore()) {
                    @Override
                    public Optional<SessionSandboxOptions> find(SessionId id) {
                        throw new IllegalStateException("options down");
                    }
                }).store(new DelegatingStore(new InMemorySandboxWorkspaceStore()) {
                    @Override
                    public Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
                        throw new IllegalStateException("workspaces down");
                    }
                }).build();

        assertThatCode(() -> harness.sessionDeleted(session, ALICE)).doesNotThrowAnyException();
        assertThat(background.queued()).isZero();
    }

    @Test
    @DisplayName("review 2: a lastWorkspaceId write queued behind the deletion does not bring the session back")
    void aQueuedRecordWriteAfterTheDeletionCreatesNothing() throws Exception {
        records.provision(session, "agent", ALICE);
        final SandboxWorkspaceId managed = harness.sandbox.workspaces().create(CreateWorkspace.named("pinned"), ALICE)
                .id();
        final ExecutionEnvironment turn = harness.mainTurn(session, ALICE,
                Map.of(SlotChoice.WORKSPACE_ATTRIBUTE, managed.value()));
        bash(turn, "true");
        assertThat(turn.drainNotices()).singleElement().asString().contains(managed.value());
        assertThat(background.queued()).as("the create of the bookkeeping record waits").isOne();

        delete();
        background.runAll();

        assertThat(harness.sessionOptions.find(session)).isEmpty();
    }

    @Test
    @DisplayName("core-033 §6.3: the janitor sweeps the options of sessions core no longer has, after closed-retention")
    void theJanitorSweepsOptionsOfDeletedSessions() {
        final SessionId gone = SessionId.generate();
        final SessionId young = SessionId.generate();
        final SessionId alive = SessionId.generate();
        records.provision(alive, "agent", ALICE);
        for (SessionId id : List.of(gone, young, alive)) {
            harness.sessionOptions.put(SessionSandboxOptions.builder().session(id)
                    .setBy(WorkspaceOwner.of(TenantId.DEFAULT, ALICE))
                    .lastWorkspaceId(SandboxWorkspaceId.of("ws:" + id.value()))
                    .updatedAt(id == young ? harness.clock.instant().plus(Duration.ofDays(7)) : harness.clock.instant())
                    .build(), OptionalLong.empty());
        }
        harness.clock.advance(Duration.ofDays(8));

        harness.sandbox.janitor().runOnce();

        assertThat(harness.sessionOptions.find(gone)).isEmpty();
        assertThat(harness.sessionOptions.find(young)).as("within closed-retention").isPresent();
        assertThat(harness.sessionOptions.find(alive)).as("its session exists").isPresent();
    }

    @Test
    void withoutCoresStoreTheJanitorSweepsNothing() {
        harness.close();
        harness = SandboxHarness.builder().sessionOwnerLookup(id -> Optional.of(ALICE)).background(background).build();
        final SessionId gone = SessionId.generate();
        harness.sessionOptions.put(
                SessionSandboxOptions.builder().session(gone).setBy(WorkspaceOwner.of(TenantId.DEFAULT, ALICE))
                        .lastWorkspaceId(SandboxWorkspaceId.of("ws:x")).updatedAt(harness.clock.instant()).build(),
                OptionalLong.empty());
        harness.clock.advance(Duration.ofDays(8));

        harness.sandbox.janitor().runOnce();

        assertThat(harness.sessionOptions.find(gone)).isPresent();
    }
}
