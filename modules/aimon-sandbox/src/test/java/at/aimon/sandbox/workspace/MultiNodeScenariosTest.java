package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.shell.ShellCommandResult;
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
}
