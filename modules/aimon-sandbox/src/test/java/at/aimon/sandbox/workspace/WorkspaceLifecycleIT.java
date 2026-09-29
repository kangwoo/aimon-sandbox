package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/** Close, reopen, idle close and redeploys (§16 rows 19–23, Q1/D12). */
class WorkspaceLifecycleIT {

    private SandboxHarness harness = SandboxHarness.standard();

    @AfterEach
    void close() {
        harness.close();
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private SandboxWorkspaceId id(SessionId session) {
        return SandboxWorkspaceId.of("ws:" + session.value());
    }

    @Test
    @DisplayName("§16: connect on a CLOSED workspace answers 'closed'; after reopen a new generation works")
    void closedWorkspaceIsUnavailableUntilReopenedWithNewGeneration() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        bash(env, "echo before > /workspace/repo/f");
        final String incarnation = harness.record(session).incarnation();

        harness.sandbox.manager().close(id(session), ALICE);

        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.record(session).closeCause()).contains(CloseCause.EXPLICIT);
        assertThat(harness.local.sandboxCount()).isZero();
        assertThatThrownBy(() -> bash(env, "true")).isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> harness.mainTurn(session, ALICE).fileSystem().exists("f"))
                .hasMessageContaining("closed");

        harness.sandbox.manager().reopen(id(session), ALICE);
        final ShellCommandResult after = bash(env, "ls /workspace/repo; echo done");

        assertThat(after.stdout()).isEqualTo("done\n");
        assertThat(harness.primary(session).generation()).isEqualTo(2);
        assertThat(harness.record(session).incarnation()).isNotEqualTo(incarnation);
        assertThat(harness.events).extracting(SandboxEvent::type).contains(SandboxEvent.Type.WORKSPACE_CLOSED,
                SandboxEvent.Type.WORKSPACE_REOPENED);
    }

    @Test
    void closeIsIdempotentAndReopenOfAnOpenWorkspaceChangesNothing() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        final String incarnation = harness.record(session).incarnation();

        harness.sandbox.manager().reopen(id(session), ALICE);
        assertThat(harness.record(session).incarnation()).isEqualTo(incarnation);

        harness.sandbox.manager().close(id(session), ALICE);
        final long version = harness.record(session).version();
        harness.sandbox.manager().close(id(session), ALICE);
        harness.sandbox.manager().close(SandboxWorkspaceId.of("ws:unknown"), ALICE);

        assertThat(harness.record(session).version()).isEqualTo(version);
    }

    @Test
    @DisplayName("§16: connect during close leaves no slot behind once CLOSED")
    void connectDuringCloseLeavesNoSlotAfterClosed() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        bash(env, "true");
        harness.faults.inject(Operation.DESTROY, Fault.delay(Duration.ofMillis(800)));

        final CompletableFuture<Void> closing = CompletableFuture
                .runAsync(() -> harness.sandbox.manager().close(id(session), ALICE));
        awaitUntil(() -> harness.record(session).state() == WorkspaceState.CLOSING);
        assertThatThrownBy(() -> bash(env, "echo late")).hasMessageContaining("closed");
        closing.get(10, TimeUnit.SECONDS);

        final SandboxWorkspace closed = harness.record(session);
        assertThat(closed.state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(closed.slots().values()).noneMatch(SandboxSlot::live);
    }

    @Test
    void closeRacingAProvisioningConnectStillEndsWithNoLiveSlot() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        env.fileSystem().exists("warm-up");
        harness.sandbox.manager().close(id(session), ALICE);
        harness.sandbox.manager().reopen(id(session), ALICE);
        harness.faults.inject(Operation.CREATE, Fault.delay(Duration.ofMillis(800)));

        final CompletableFuture<Throwable> connecting = CompletableFuture.supplyAsync(() -> {
            try {
                bash(env, "true");
                return null;
            } catch (Throwable e) {
                return e;
            }
        });
        awaitUntil(() -> harness.faults.calls(Operation.CREATE) > 0);
        harness.sandbox.manager().close(id(session), ALICE);

        assertThat(connecting.get(10, TimeUnit.SECONDS)).isNotNull().hasMessageContaining("closed");
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.record(session).slots().values()).noneMatch(SandboxSlot::live);
    }

    @Test
    @DisplayName("§16: the node dies during close; the janitor resumes it to CLOSED")
    void janitorResumesStuckClosing() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        harness.faults.injectOnce(Operation.DESTROY, Fault.crash());

        assertThatThrownBy(() -> harness.sandbox.manager().close(id(session), ALICE))
                .isInstanceOf(FaultInjectingSandboxProvider.SimulatedCrash.class);
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSING);

        harness.sandbox.janitor().runOnce();
        assertThat(harness.record(session).state()).as("not before close-resume-after")
                .isEqualTo(WorkspaceState.CLOSING);

        harness.clock.advance(Duration.ofMinutes(5));
        harness.sandbox.janitor().runOnce();

        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.record(session).closeCause()).contains(CloseCause.EXPLICIT);
        assertThat(harness.local.sandboxCount()).isZero();
    }

    @Test
    void aStaleCloserLeavesAWorkspaceReopenedMeanwhileAlone() throws Exception {
        harness.close();
        final InMemorySandboxWorkspaceStore delegate = new InMemorySandboxWorkspaceStore();
        final java.util.concurrent.atomic.AtomicInteger armed = new java.util.concurrent.atomic.AtomicInteger(-1);
        final Runnable[] meanwhile = new Runnable[1];
        final SandboxWorkspaceStore store = new SandboxWorkspaceStore() {
            @Override
            public java.util.Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
                // The second read after arming: another node finishes the close and the user reopens and works.
                if (armed.get() >= 0 && armed.incrementAndGet() == 2) {
                    armed.set(-1);
                    meanwhile[0].run();
                }
                return delegate.find(id);
            }

            @Override
            public SandboxWorkspace createIfAbsent(SandboxWorkspace initial) {
                return delegate.createIfAbsent(initial);
            }

            @Override
            public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
                return delegate.update(id, expectedVersion, next);
            }

            @Override
            public void delete(SandboxWorkspaceId id, long expectedVersion) {
                delegate.delete(id, expectedVersion);
            }

            @Override
            public java.util.List<SandboxWorkspace> scan(WorkspaceScan scan) {
                return delegate.scan(scan);
            }
        };
        harness = SandboxHarness.builder().store(store).build();
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        bash(env, "true");
        harness.faults.injectOnce(Operation.DESTROY, Fault.crash());
        assertThatThrownBy(() -> harness.sandbox.manager().close(id(session), ALICE))
                .isInstanceOf(FaultInjectingSandboxProvider.SimulatedCrash.class);
        meanwhile[0] = () -> {
            final SandboxWorkspace stuck = delegate.find(id(session)).orElseThrow();
            delegate.update(stuck.id(), stuck.version(), stuck.toBuilder().state(WorkspaceState.CLOSED).build());
            harness.sandbox.manager().reopen(id(session), ALICE);
            try {
                bash(env, "echo alive > /workspace/repo/f");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        harness.clock.advance(Duration.ofMinutes(5));

        armed.set(0);
        harness.sandbox.janitor().runOnce();

        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.OPEN);
        assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
        assertThat(bash(env, "cat /workspace/repo/f").stdout()).isEqualTo("alive\n");
    }

    @Test
    void theJanitorLeavesAStuckCloseThatWasFinishedAndReopenedAfterItsScanAlone() throws Exception {
        harness.close();
        final InMemorySandboxWorkspaceStore delegate = new InMemorySandboxWorkspaceStore();
        final Runnable[] afterClosingScan = new Runnable[1];
        final SandboxWorkspaceStore store = new SandboxWorkspaceStore() {
            @Override
            public java.util.Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
                return delegate.find(id);
            }

            @Override
            public SandboxWorkspace createIfAbsent(SandboxWorkspace initial) {
                return delegate.createIfAbsent(initial);
            }

            @Override
            public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
                return delegate.update(id, expectedVersion, next);
            }

            @Override
            public void delete(SandboxWorkspaceId id, long expectedVersion) {
                delegate.delete(id, expectedVersion);
            }

            @Override
            public java.util.List<SandboxWorkspace> scan(WorkspaceScan scan) {
                final java.util.List<SandboxWorkspace> page = delegate.scan(scan);
                // The janitor has the CLOSING snapshot in hand: before it resumes that close, the application's
                // retried close finishes it and the user reopens and works.
                if (afterClosingScan[0] != null && page.stream().anyMatch(w -> w.state() == WorkspaceState.CLOSING)) {
                    final Runnable interleaved = afterClosingScan[0];
                    afterClosingScan[0] = null;
                    interleaved.run();
                }
                return page;
            }
        };
        harness = SandboxHarness.builder().store(store).build();
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        bash(env, "true");
        harness.faults.injectOnce(Operation.DESTROY, Fault.crash());
        assertThatThrownBy(() -> harness.sandbox.manager().close(id(session), ALICE))
                .isInstanceOf(FaultInjectingSandboxProvider.SimulatedCrash.class);
        final String stuckIncarnation = harness.record(session).incarnation();
        harness.clock.advance(Duration.ofMinutes(5));
        afterClosingScan[0] = () -> {
            harness.sandbox.manager().close(id(session), ALICE);
            harness.sandbox.manager().reopen(id(session), ALICE);
            try {
                bash(env, "echo alive > /workspace/repo/f");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        harness.sandbox.janitor().runOnce();

        assertThat(afterClosingScan[0]).as("the reopen ran after the scan").isNull();
        final SandboxWorkspace reopened = harness.record(session);
        assertThat(reopened.state()).isEqualTo(WorkspaceState.OPEN);
        assertThat(reopened.incarnation()).isNotEqualTo(stuckIncarnation);
        assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
        assertThat(harness.local.sandboxCount()).isEqualTo(1);
        assertThat(bash(env, "cat /workspace/repo/f").stdout()).isEqualTo("alive\n");
    }

    @Test
    @DisplayName("§16: after an idle close the next turn gets a fresh workspace and a reset notice")
    void idleCloseThenNextTurnGetsFreshWorkspaceAndResetNotice() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "echo old > /workspace/repo/f");
        final String incarnation = harness.record(session).incarnation();

        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.primary(session).state()).isEqualTo(SlotState.TERMINATED);
        harness.clock.advance(Duration.ofHours(24));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.record(session).closeCause()).contains(CloseCause.IDLE);

        final ShellCommandResult next = bash(harness.mainTurn(session, ALICE), "ls /workspace/repo; echo fresh");

        assertThat(next.stdout()).isEqualTo("fresh\n");
        assertThat(next.notices()).contains(SandboxWorkspaceManager.RESET_NOTICE);
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.OPEN);
        assertThat(harness.record(session).incarnation()).isNotEqualTo(incarnation);
    }

    @Test
    void anExplicitCloseAfterAnIdleCloseLeavesABlockingTombstone() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();
        harness.clock.advance(Duration.ofHours(24));
        harness.sandbox.janitor().runOnce();
        harness.clock.advance(Duration.ofMinutes(1));
        assertThat(harness.record(session).closeCause()).contains(CloseCause.IDLE);

        harness.sandbox.manager().close(id(session), ALICE);

        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.record(session).closeCause()).contains(CloseCause.EXPLICIT);
        assertThat(harness.record(session).stateSince()).contains(harness.clock.instant());
        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true")).hasMessageContaining("closed");
        assertThat(harness.record(session).state()).as("no silent auto-reopen").isEqualTo(WorkspaceState.CLOSED);
    }

    @Test
    void anIdleReopenRacingAnExplicitCloseCannotSwallowIt() throws Exception {
        harness.close();
        final InMemorySandboxWorkspaceStore delegate = new InMemorySandboxWorkspaceStore();
        final Runnable[] beforeTombstone = new Runnable[1];
        final SandboxWorkspaceStore store = new SandboxWorkspaceStore() {
            @Override
            public java.util.Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
                return delegate.find(id);
            }

            @Override
            public SandboxWorkspace createIfAbsent(SandboxWorkspace initial) {
                return delegate.createIfAbsent(initial);
            }

            @Override
            public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
                // The explicit close has read CLOSED(idle) and is about to write the tombstone: a call in the same
                // session reopens the workspace first.
                if (beforeTombstone[0] != null && next.state() == WorkspaceState.CLOSED
                        && next.closeCause().orElse(null) == CloseCause.EXPLICIT) {
                    final Runnable interleaved = beforeTombstone[0];
                    beforeTombstone[0] = null;
                    interleaved.run();
                }
                return delegate.update(id, expectedVersion, next);
            }

            @Override
            public void delete(SandboxWorkspaceId id, long expectedVersion) {
                delegate.delete(id, expectedVersion);
            }

            @Override
            public java.util.List<SandboxWorkspace> scan(WorkspaceScan scan) {
                return delegate.scan(scan);
            }
        };
        harness = SandboxHarness.builder().store(store).build();
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        bash(env, "true");
        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();
        harness.clock.advance(Duration.ofHours(24));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.record(session).closeCause()).contains(CloseCause.IDLE);
        final ShellCommandResult[] lastCall = new ShellCommandResult[1];
        beforeTombstone[0] = () -> {
            try {
                lastCall[0] = bash(env, "echo last-call");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        harness.sandbox.manager().close(id(session), ALICE);

        assertThat(lastCall[0].stdout()).as("the reopen went first").isEqualTo("last-call\n");
        final SandboxWorkspace closed = harness.record(session);
        assertThat(closed.state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(closed.closeCause()).contains(CloseCause.EXPLICIT);
        assertThat(closed.slots().values()).noneMatch(SandboxSlot::live);
        assertThat(harness.local.sandboxCount()).isZero();
        assertThatThrownBy(() -> bash(env, "true")).hasMessageContaining("closed");
    }

    @Test
    void anExplicitCloseOvertakingAStuckIdleCloseEndsAsATombstone() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();
        harness.clock.advance(Duration.ofHours(24));
        harness.faults.injectOnce(Operation.DESTROY, Fault.crash());
        assertThatThrownBy(() -> harness.sandbox.janitor().runOnce())
                .isInstanceOf(FaultInjectingSandboxProvider.SimulatedCrash.class);
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSING);
        assertThat(harness.record(session).closeCause()).contains(CloseCause.IDLE);

        harness.sandbox.manager().close(id(session), ALICE);

        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.record(session).closeCause()).contains(CloseCause.EXPLICIT);
        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true")).hasMessageContaining("closed");
    }

    @Test
    void anExpiredClosedRecordIsDeletedAndTheIdStartsFreshWithoutANotice() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        harness.sandbox.manager().close(id(session), ALICE);

        harness.clock.advance(Duration.ofDays(7));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.store.find(id(session))).isEmpty();

        final ShellCommandResult next = bash(harness.mainTurn(session, ALICE), "echo new");

        assertThat(next.stdout()).isEqualTo("new\n");
        assertThat(next.notices()).doesNotContain(SandboxWorkspaceManager.RESET_NOTICE);
        assertThat(harness.primary(session).generation()).isEqualTo(1);
    }

    @Test
    @DisplayName("§16: a redeploy with a new default-profile keeps the existing primary slot's profile")
    void redeployWithNewDefaultProfileKeepsExistingPrimaryProfile() throws Exception {
        harness.close();
        harness = SandboxHarness.builder().profile(SandboxTestProfiles.local("standard").build())
                .profile(SandboxTestProfiles.local("fresh").environment(java.util.Map.of("FRESH", "1")).build())
                .build();
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        final SandboxSlot before = harness.primary(session);

        try (SandboxHarness redeployed = SandboxHarness.builder().store(harness.store).local(harness.local)
                .profile(SandboxTestProfiles.local("standard").build())
                .profile(SandboxTestProfiles.local("fresh").environment(java.util.Map.of("FRESH", "1")).build())
                .settings(settings -> settings.defaultProfile("fresh")).build()) {
            final ShellCommandResult result = bash(redeployed.mainTurn(session, ALICE), "echo \"[$FRESH]\"");

            assertThat(result.stdout()).isEqualTo("[]\n");
            assertThat(redeployed.primary(session).profile()).isEqualTo("standard");
            assertThat(redeployed.primary(session).generation()).isEqualTo(before.generation());
            assertThat(redeployed.mainTurn(SessionId.generate(), ALICE).descriptor().notes().orElseThrow())
                    .contains("profile 'fresh'");
        }
    }

    @Test
    void aReopenRacingACloseIsRefusedNotSilentlyIgnored() throws Exception {
        harness.close();
        final InMemorySandboxWorkspaceStore delegate = new InMemorySandboxWorkspaceStore();
        final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        final SandboxWorkspaceStore store = new at.aimon.sandbox.DelegatingStore(delegate) {
            @Override
            public java.util.Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
                final java.util.Optional<SandboxWorkspace> read = delegate.find(id);
                // After reopen's first read: a close of the same workspace starts (reopened elsewhere, closing again).
                if (armed.compareAndSet(true, false)) {
                    final SandboxWorkspace closed = read.orElseThrow();
                    delegate.update(id, closed.version(), closed.toBuilder().state(WorkspaceState.CLOSING).build());
                }
                return read;
            }
        };
        harness = SandboxHarness.builder().store(store).build();
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        harness.sandbox.manager().close(id(session), ALICE);

        armed.set(true);

        assertThatThrownBy(() -> harness.sandbox.manager().reopen(id(session), ALICE))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("still closing");
        assertThat(harness.record(session).state()).isEqualTo(WorkspaceState.CLOSING);
    }
}
