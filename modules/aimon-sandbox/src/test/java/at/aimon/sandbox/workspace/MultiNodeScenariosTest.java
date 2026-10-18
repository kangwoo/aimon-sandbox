package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.sandbox.QueueingExecutor;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;

/**
 * The "two nodes" rows of §16 for implementation step 4, simulated as two managers in one JVM — each with its own
 * node id, connection cache and node-local locks — sharing one {@code InMemory} store and one local provider (§16:
 * the real multi-process run is step 6's).
 */
class MultiNodeScenariosTest {

    private final SandboxHarness nodeA = SandboxHarness.builder().settings(s -> s.nodeId("node-a")).build();
    private final SandboxHarness nodeB = SandboxHarness.builder().store(nodeA.store).local(nodeA.local)
            .settings(s -> s.nodeId("node-b")).build();

    @AfterEach
    void close() {
        nodeB.sandbox.close();
        nodeA.close();
    }

    private static CompletableFuture<ShellCommandResult> async(SandboxHarness node, SessionId session, String cmd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return bash(node.mainTurn(session, ALICE), cmd);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("§16: two nodes connect the same slot at once — the record holds one sandbox")
    void twoNodesConnectingOneSlotShareOneSandbox() throws Exception {
        final SessionId session = SessionId.generate();

        final CompletableFuture<ShellCommandResult> a = async(nodeA, session, "echo a");
        final CompletableFuture<ShellCommandResult> b = async(nodeB, session, "echo b");

        assertThat(a.get(60, TimeUnit.SECONDS).stdout()).isEqualTo("a\n");
        assertThat(b.get(60, TimeUnit.SECONDS).stdout()).isEqualTo("b\n");
        assertThat(nodeA.local.sandboxCount()).isOne();
        assertThat(nodeA.primary(session).generation()).isOne();
    }

    @Test
    @DisplayName("§16: a node dies during the seed; another node's next call cleans up and completes it")
    void aSeedInterruptedByACrashIsCompletedByTheNextNode() throws Exception {
        final SessionId session = SessionId.generate();
        // The first exec of a new sandbox is its seed.
        nodeA.faults.injectOnce(Operation.RUN, Fault.crash());

        final Throwable crash = catchThrowable(() -> bash(nodeA.mainTurn(session, ALICE), "true"));

        assertThat(crash).isInstanceOf(FaultInjectingSandboxProvider.SimulatedCrash.class);
        assertThat(nodeA.primary(session).state()).isEqualTo(SlotState.RUNNING);
        assertThat(nodeA.primary(session).seeded()).isFalse();
        Files.writeString(nodeA.host(session, "/workspace/.tmp-left-by-the-crash"), "half");

        assertThat(bash(nodeB.mainTurn(session, ALICE), "ls -a /workspace").stdout())
                .doesNotContain(".tmp-left-by-the-crash").contains(".aimon-shell");
        assertThat(nodeA.primary(session).seeded()).isTrue();
        assertThat(nodeA.local.sandboxCount()).isOne();
    }

    @Test
    @DisplayName("§16: two nodes seed one slot at once — the seed lock runs them in turn and both succeed")
    void twoNodesSeedingOneSlotBothSucceed() throws Exception {
        final SessionId session = SessionId.generate();
        nodeA.faults.injectOnce(Operation.RUN, Fault.crash());
        catchThrowable(() -> bash(nodeA.mainTurn(session, ALICE), "true"));
        assertThat(nodeA.primary(session).seeded()).isFalse();

        final CompletableFuture<ShellCommandResult> a = async(nodeA, session, "echo a");
        final CompletableFuture<ShellCommandResult> b = async(nodeB, session, "echo b");

        assertThat(a.get(60, TimeUnit.SECONDS).stdout()).isEqualTo("a\n");
        assertThat(b.get(60, TimeUnit.SECONDS).stdout()).isEqualTo("b\n");
        assertThat(nodeA.primary(session).seeded()).isTrue();
        assertThat(nodeA.local.sandboxCount()).isOne();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the durable unannounced reset (docs/design/workspace-sandbox-unannounced-reset.md §6.5)
    // ----------------------------------------------------------------------------------------------------------------

    /** Two nodes sharing the workspace store, the options store and the provider; node A's background queues. */
    private static final class Shared implements AutoCloseable {
        final QueueingExecutor backgroundA = new QueueingExecutor();
        final SandboxHarness a = SandboxHarness.builder().settings(s -> s.nodeId("node-a")).background(backgroundA)
                .build();
        final SandboxHarness b = SandboxHarness.builder().store(a.store).local(a.local).sessionOptions(a.sessionOptions)
                .settings(s -> s.nodeId("node-b")).build();

        /**
         * Idle past {@code closeAfter} and closed, by node A's janitor; both clocks move together. Node A's background
         * queue is left alone.
         */
        void idleClose(SessionId session) {
            for (Duration step : List.of(Duration.ofHours(2), Duration.ofHours(24))) {
                a.clock.advance(step);
                b.clock.advance(step);
                a.sandbox.janitor().runOnce();
            }
            assertThat(a.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        }

        @Override
        public void close() {
            b.sandbox.close();
            a.close();
        }
    }

    private static final ExecutionOptions HOOK = ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).hook(true)
            .build();

    @Test
    @DisplayName("unannounced-reset §6.5: node A's hook-only turn resets; node B's next turn tells it once")
    void aResetKeptOnOneNodeIsToldOnAnother() throws Exception {
        try (Shared nodes = new Shared()) {
            final SessionId session = SessionId.generate();
            bash(nodes.a.mainTurn(session, ALICE), "true");
            nodes.idleClose(session);

            bash(nodes.a.mainTurn(session, ALICE), "true", HOOK);

            final ExecutionEnvironment onB = nodes.b.mainTurn(session, ALICE);
            bash(onB, "true");
            assertThat(onB.drainNotices()).singleElement().asString().startsWith("/workspace was reset at ");
            assertThat(nodes.a.sessionOptions.find(session)).isEmpty();
            final ExecutionEnvironment again = nodes.a.mainTurn(session, ALICE);
            bash(again, "true");
            assertThat(again.drainNotices()).isEmpty();
        }
    }

    @Test
    @DisplayName("unannounced-reset §6.5: node A's late acknowledgement leaves the newer reset node B kept since")
    void aLateAcknowledgementLeavesANewerReset() throws Exception {
        try (Shared nodes = new Shared()) {
            final SessionId session = SessionId.generate();
            bash(nodes.a.mainTurn(session, ALICE), "true");
            nodes.idleClose(session);
            final ExecutionEnvironment onA = nodes.a.mainTurn(session, ALICE);
            bash(onA, "true");
            assertThat(onA.drainNotices()).containsExactly(SandboxWorkspaceManager.RESET_NOTICE);
            assertThat(nodes.backgroundA.queued()).as("A's acknowledgement is late").isOne();

            nodes.idleClose(session);
            bash(nodes.b.mainTurn(session, ALICE), "true", HOOK);
            final UnannouncedReset keptByB = nodes.a.sessionOptions.find(session).orElseThrow().unannouncedReset()
                    .orElseThrow();
            nodes.backgroundA.runAll();

            assertThat(nodes.a.sessionOptions.find(session).orElseThrow().unannouncedReset()).contains(keptByB);
        }
    }
}
