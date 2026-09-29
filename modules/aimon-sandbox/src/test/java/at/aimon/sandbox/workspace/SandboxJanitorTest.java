package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.binding.ShellKey;
import at.aimon.sandbox.environment.SandboxExecutionEnvironment;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/** {@link SandboxJanitor}: idle enforcement items (a)–(d) and the exec-shell sweep (§10.4 item 1). */
class SandboxJanitorTest {

    private SandboxHarness harness = SandboxHarness.builder()
            .profile(SandboxTestProfiles.local("standard").terminateAfter(Duration.ofHours(2)).build())
            .profile(SandboxTestProfiles.local("long").terminateAfter(Duration.ofHours(6)).build()).build();

    @AfterEach
    void close() {
        harness.close();
    }

    private SessionId started() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        return session;
    }

    @Test
    void terminatesARunningSlotIdleForItsProfilesTerminateAfterAndNotBefore() throws Exception {
        final SessionId session = started();

        harness.clock.advance(Duration.ofMinutes(119));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);

        harness.clock.advance(Duration.ofMinutes(1));
        harness.sandbox.janitor().runOnce();
        final SandboxSlot slot = harness.primary(session);
        assertThat(slot.state()).isEqualTo(SlotState.TERMINATED);
        assertThat(slot.lastActiveAt()).contains(harness.clock.instant());
        assertThat(harness.local.sandboxCount()).isZero();
        assertThat(harness.events).extracting(SandboxEvent::type).contains(SandboxEvent.Type.TERMINATED);
    }

    @Test
    void aSlotWhoseProfileWasRemovedGetsTheLongestConfiguredTerminateAfter() throws Exception {
        harness.close();
        harness = SandboxHarness.builder()
                .profile(SandboxTestProfiles.local("short").terminateAfter(Duration.ofHours(1)).build()).build();
        final SessionId session = started();
        try (SandboxHarness redeployed = SandboxHarness.builder().store(harness.store).local(harness.local)
                .profile(SandboxTestProfiles.local("a").terminateAfter(Duration.ofHours(3)).build())
                .profile(SandboxTestProfiles.local("b").terminateAfter(Duration.ofHours(5)).build()).build()) {
            redeployed.clock.set(harness.clock.instant());

            redeployed.clock.advance(Duration.ofHours(4));
            redeployed.sandbox.janitor().runOnce();
            assertThat(redeployed.primary(session).state()).isEqualTo(SlotState.RUNNING);

            redeployed.clock.advance(Duration.ofHours(1));
            redeployed.sandbox.janitor().runOnce();
            assertThat(redeployed.primary(session).state()).isEqualTo(SlotState.TERMINATED);
            assertThat(redeployed.mainTurn(session, ALICE).fileSystem()).isNotNull();
        }
    }

    @Test
    void idleClosesAWorkspaceWithoutLiveSlotsAfterCloseAfterFromTheLastActiveSlot() throws Exception {
        final SessionId session = started();
        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();

        harness.clock.advance(Duration.ofHours(24).minusMinutes(1));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.OPEN);

        harness.clock.advance(Duration.ofMinutes(1));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.record(session).closeCause()).contains(CloseCause.IDLE);
    }

    @Test
    void aWorkspaceWithALiveSlotIsNotIdleClosed() throws Exception {
        harness.close();
        harness = SandboxHarness.builder()
                .profile(SandboxTestProfiles.local("standard").terminateAfter(Duration.ofDays(3)).build()).build();
        final SessionId session = started();

        harness.clock.advance(Duration.ofHours(30));
        harness.sandbox.janitor().runOnce();

        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.OPEN);
    }

    @Test
    void aWorkspaceNeverProvisionedIsIdleClosedFromItsCreation() {
        final SessionId session = SessionId.generate();
        harness.mainTurn(session, ALICE);
        harness.store.createIfAbsent(SandboxWorkspace.builder().id(SandboxWorkspaceId.of("ws:" + session.value()))
                .owner(WorkspaceOwner.of(TenantId.DEFAULT, "alice")).incarnation("abcdefgh")
                .stateSince(harness.clock.instant()).createdAt(harness.clock.instant())
                .lastActivityAt(harness.clock.instant()).build());

        harness.clock.advance(Duration.ofHours(24));
        harness.sandbox.janitor().runOnce();

        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
    }

    @Test
    void expiredTombstonesAreDeletedAndYoungerOnesKept() throws Exception {
        final SessionId old = started();
        harness.sandbox.manager().close(harness.record(old).id(), ALICE);
        harness.clock.advance(Duration.ofDays(6));
        final SessionId young = started();
        harness.sandbox.manager().close(harness.record(young).id(), ALICE);

        harness.clock.advance(Duration.ofDays(1));
        harness.sandbox.janitor().runOnce();

        assertThat(harness.store.find(SandboxWorkspaceId.of("ws:" + old.value()))).isEmpty();
        assertThat(harness.store.find(SandboxWorkspaceId.of("ws:" + young.value()))).isPresent();
    }

    @Test
    void execShellDirectoriesAreSweptAfterExecShellIdle() throws Exception {
        final ExecutionId execution = ExecutionId.generate();
        final ExecutionEnvironment routine = harness.routine(execution, ALICE);
        bash(routine, "export R=1");
        final ShellKey key = ((SandboxExecutionEnvironment) routine).binding().shellKey();
        final SessionId asSession = SessionId.of(execution.value());
        final java.nio.file.Path dir = harness.host(asSession, "/workspace/.aimon-shell/" + key.directoryName());
        assertThat(Files.isDirectory(dir)).isTrue();

        harness.clock.advance(Duration.ofMinutes(9));
        harness.sandbox.janitor().runOnce();
        assertThat(Files.isDirectory(dir)).isTrue();

        harness.clock.advance(Duration.ofMinutes(1));
        harness.sandbox.janitor().runOnce();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void oneFailingWorkspaceDoesNotStopTheOthers() throws Exception {
        harness.close();
        final InMemorySandboxWorkspaceStore delegate = new InMemorySandboxWorkspaceStore();
        final SandboxWorkspaceStore flaky = new SandboxWorkspaceStore() {
            @Override
            public Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
                return delegate.find(id);
            }

            @Override
            public SandboxWorkspace createIfAbsent(SandboxWorkspace initial) {
                return delegate.createIfAbsent(initial);
            }

            @Override
            public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
                if (id.value().endsWith("-broken")
                        && next.slots().values().stream().anyMatch(s -> s.state() == SlotState.TERMINATED)) {
                    throw new IllegalStateException("store failure");
                }
                return delegate.update(id, expectedVersion, next);
            }

            @Override
            public void delete(SandboxWorkspaceId id, long expectedVersion) {
                delegate.delete(id, expectedVersion);
            }

            @Override
            public List<SandboxWorkspace> scan(WorkspaceScan scan) {
                return delegate.scan(scan);
            }
        };
        harness = SandboxHarness.builder().store(flaky).build();
        final SessionId broken = SessionId.of("a-broken");
        final SessionId fine = SessionId.of("b-fine");
        bash(harness.mainTurn(broken, ALICE), "true");
        bash(harness.mainTurn(fine, ALICE), "true");

        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();

        assertThat(harness.primary(broken).state()).isEqualTo(SlotState.RUNNING);
        assertThat(harness.primary(fine).state()).isEqualTo(SlotState.TERMINATED);
    }

    @Test
    void startSchedulesTheLoopOnceAndCloseStopsIt() {
        harness.sandbox.janitor().start();
        harness.sandbox.janitor().start();
        assertThat(harness.scheduler.scheduled()).isEqualTo(1);

        harness.sandbox.janitor().close();
        assertThat(harness.scheduler.scheduled()).isZero();
    }
}
