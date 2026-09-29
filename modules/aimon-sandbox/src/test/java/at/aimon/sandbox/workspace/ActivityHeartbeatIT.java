package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.sandbox.DelegatingStore;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.binding.ShellKey;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/** Activity heartbeat and expiry (§5.3, §10.3; §16 rows 9, 13, 29). */
class ActivityHeartbeatIT {

    /** Set before a heartbeat tick: the tick's first write loses its CAS to a concurrent write (§5.3). */
    private final AtomicBoolean interfere = new AtomicBoolean();
    private final AtomicInteger heartbeatConflicts = new AtomicInteger();
    private final Thread testThread = Thread.currentThread();
    private final SandboxHarness harness = SandboxHarness.builder()
            .store(new DelegatingStore(new InMemorySandboxWorkspaceStore()) {
                @Override
                public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
                    // The manual scheduler runs heartbeats on the test thread: only their writes are interfered with.
                    final boolean heartbeat = Thread.currentThread() == testThread;
                    if (heartbeat && interfere.compareAndSet(true, false)) {
                        try {
                            final SandboxWorkspace current = delegate.find(id).orElseThrow();
                            delegate.update(id, current.version(), current);
                        } catch (StaleVersionException e) {
                            // The other writer got there first: a concurrent write all the same.
                        }
                    }
                    try {
                        return delegate.update(id, expectedVersion, next);
                    } catch (StaleVersionException e) {
                        if (heartbeat) {
                            heartbeatConflicts.incrementAndGet();
                        }
                        throw e;
                    }
                }
            }).profile(SandboxTestProfiles.local("standard").terminateAfter(Duration.ofHours(2))
                    .backgroundHeartbeatLimit(Duration.ofHours(1)).build())
            .build();
    private final SessionId session = SessionId.generate();
    private final ExecutionEnvironment env = harness.mainTurn(session, ALICE);

    @AfterEach
    void close() {
        harness.close();
    }

    private CompletableFuture<Object> start(String command, boolean background) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return bash(env, command,
                        ExecutionOptions.builder().timeout(Duration.ofSeconds(30)).background(background).build());
            } catch (ShellExecutionException e) {
                return e;
            }
        });
    }

    private void awaitHeartbeat() throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (harness.scheduler.scheduled() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(harness.scheduler.scheduled()).as("a heartbeat is scheduled").isPositive();
    }

    private String heartbeatFile() {
        return "/workspace/.aimon-shell/" + ShellKey.session(session).directoryName() + "/heartbeat";
    }

    @Test
    @DisplayName("§16: a command longer than terminateAfter, no tool calls, concurrent record writes — not "
            + "terminated, expiry pushed")
    void longCommandIsNotTerminatedAndExpiryIsPushed() throws Exception {
        bash(env, "true");
        final ProviderSandboxRef ref = harness.primary(session).providerRef().orElseThrow();
        final SandboxWorkspaceId id = harness.record(session).id();
        final CompletableFuture<Object> command = start("sleep 3; echo finished", false);
        awaitHeartbeat();
        final AtomicBoolean writing = new AtomicBoolean(true);
        final AtomicInteger conflicts = new AtomicInteger();
        final CompletableFuture<Void> otherWriter = CompletableFuture.runAsync(() -> {
            while (writing.get()) {
                final SandboxWorkspace current = harness.store.find(id).orElseThrow();
                try {
                    harness.store.update(id, current.version(), current.toBuilder().build());
                } catch (StaleVersionException e) {
                    conflicts.incrementAndGet();
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        Instant previous = harness.primary(session).lastActivityAt();
        for (int tick = 0; tick < 4; tick++) {
            final Instant now = harness.clock.advance(Duration.ofMinutes(40));
            interfere.set(true);
            harness.scheduler.runDue();
            assertThat(interfere.get()).as("the heartbeat wrote, and its first CAS lost").isFalse();
            final Instant recorded = harness.primary(session).lastActivityAt();
            assertThat(recorded).as("every heartbeat write landed").isEqualTo(now).isAfter(previous);
            previous = recorded;
            harness.sandbox.janitor().runOnce();
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
            assertThat(harness.local.expiryOf(ref))
                    .hasValueSatisfying(expiry -> assertThat(expiry).isAfterOrEqualTo(now.plus(Duration.ofHours(2))));
        }
        writing.set(false);
        otherWriter.get(10, TimeUnit.SECONDS);
        // Every tick's write lost a CAS and was retried, never dropped: each still landed (asserted above).
        assertThat(heartbeatConflicts.get()).as("heartbeat CAS conflicts").isGreaterThanOrEqualTo(4);

        assertThat(command.get(20, TimeUnit.SECONDS)).isInstanceOfSatisfying(ShellCommandResult.class,
                result -> assertThat(result.stdout()).isEqualTo("finished\n"));
        assertThat(Files.exists(harness.host(session, heartbeatFile()))).as("the lock heartbeat was written").isTrue();
        assertThat(harness.scheduler.scheduled()).as("the heartbeat stops with the command").isZero();
    }

    @Test
    @DisplayName("§16: a background command past backgroundHeartbeatLimit stops keeping the slot awake")
    void backgroundHeartbeatStopsAtLimitThenIdlePolicyApplies() throws Exception {
        bash(env, "true");
        final Instant started = harness.clock.instant();
        final CompletableFuture<Object> background = start("sleep 5", true);
        awaitHeartbeat();

        harness.scheduler.advance(Duration.ofMinutes(30));
        assertThat(harness.primary(session).lastActivityAt()).isEqualTo(started.plus(Duration.ofMinutes(30)));
        harness.scheduler.advance(Duration.ofMinutes(31));
        assertThat(harness.primary(session).lastActivityAt()).as("no write past the limit")
                .isEqualTo(started.plus(Duration.ofMinutes(30)));
        assertThat(harness.scheduler.scheduled()).isZero();
        assertThat(Files.exists(harness.host(session, heartbeatFile())))
                .as("background never touches the lock " + "heartbeat").isFalse();

        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();

        assertThat(harness.primary(session).state()).isEqualTo(SlotState.TERMINATED);
        assertThat(background.get(20, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    @DisplayName("§16: extendExpiry answers not-found mid-command — the command fails, the slot is lost, the next "
            + "call recreates")
    void expiryNotFoundMidCommandMarksLostAndNextCallRecreates() throws Exception {
        bash(env, "true");
        final CompletableFuture<Object> command = start("sleep 10; echo never", false);
        awaitHeartbeat();
        harness.faults.injectOnce(Operation.EXTEND_EXPIRY, Fault.notFound());

        harness.scheduler.advance(Duration.ofSeconds(31));

        assertThat(command.get(20, TimeUnit.SECONDS)).isInstanceOfSatisfying(ShellExecutionException.class,
                e -> assertThat(e.getMessage()).isEqualTo(SandboxWorkspaceManager.LOST_MESSAGE));
        final SandboxSlot lost = harness.primary(session);
        assertThat(lost.state()).isEqualTo(SlotState.TERMINATED);
        assertThat(lost.lostAt()).isPresent();
        assertThat(harness.events).extracting(SandboxEvent::type).contains(SandboxEvent.Type.LOST);

        final ShellCommandResult next = bash(env, "echo again");

        assertThat(next.stdout()).isEqualTo("again\n");
        assertThat(harness.primary(session).generation()).isEqualTo(2);
        assertThat(next.notices()).anyMatch(notice -> notice.contains("recreated (generation 2)"));
    }

    @Test
    void activityIsThrottledToTheWriteInterval() throws Exception {
        bash(env, "true");
        final long version = harness.record(session).version();

        bash(env, "true");
        assertThat(harness.record(session).version()).as("no write within the interval").isEqualTo(version);

        harness.clock.advance(Duration.ofSeconds(31));
        bash(env, "true");
        assertThat(harness.record(session).version()).isGreaterThan(version);
        assertThat(harness.primary(session).lastActivityAt()).isEqualTo(harness.clock.instant());
    }
}
