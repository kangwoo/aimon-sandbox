package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.environment.SandboxExecutionEnvironment;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.SlotState;
import at.aimon.sandbox.workspace.StaleVersionException;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.UnannouncedReset;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * A reset the model never heard of — caused by a hook, a staging call or any other call core does not drain — is kept
 * on the session's options record and told at the session's next tool result, once
 * (kangwoo/aimon-sandbox#28, docs/design/workspace-sandbox-unannounced-reset.md §7, §9). Local provider, manual clock;
 * the background executor is inline unless a test queues it.
 */
class UnannouncedResetIT {

    private static final String KEPT = "/workspace/repo/kept.txt";
    private static final WorkspaceOwner ALICES = WorkspaceOwner.of(TenantId.DEFAULT, ALICE);

    @TempDir
    Path source;

    private final SessionId session = SessionId.generate();
    private SandboxHarness harness = SandboxHarness.builder().sessionOwnerLookup(id -> Optional.of(ALICE)).build();

    @AfterEach
    void close() {
        harness.close();
    }

    private SandboxWorkspaceId derived() {
        return SandboxWorkspaceId.of("ws:" + session.value());
    }

    private Optional<SessionSandboxOptions> options() {
        return harness.sessionOptions.find(session);
    }

    /** A turn with one model-facing call, as core makes it: the command, then the drain. */
    private List<String> modelFacingTurn(String command) throws Exception {
        final ExecutionEnvironment turn = harness.mainTurn(session, ALICE);
        bash(turn, command);
        return turn.drainNotices();
    }

    /** A turn whose only sandbox use is a Stop hook's command: nothing drains it. */
    private void hookOnlyTurn() throws Exception {
        bash(harness.mainTurn(session, ALICE), "true",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).hook(true).build());
    }

    private void idleClose() {
        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();
        harness.clock.advance(Duration.ofHours(24));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.record(session).state()).isEqualTo(at.aimon.sandbox.workspace.WorkspaceState.CLOSED);
    }

    private static void assertReplay(List<String> drained) {
        assertThat(drained).singleElement().asString().startsWith("/workspace was reset at ")
                .contains("this session may not have been told").contains("from before then are gone")
                .doesNotContain(SandboxWorkspaceManager.RESET_NOTICE);
    }

    @Test
    @DisplayName("#28: idle past closeAfter, a Stop-hook-only turn resets — the next tool result hears it exactly once")
    void aHookOnlyTurnsResetIsToldOnTheNextTurnOnce() throws Exception {
        assertThat(modelFacingTurn("echo before > " + KEPT)).isEmpty();
        idleClose();

        hookOnlyTurn();
        final UnannouncedReset kept = options().orElseThrow().unannouncedReset().orElseThrow();
        assertThat(kept.workspace()).isEqualTo(derived());
        assertThat(kept.slot()).isEqualTo("primary");

        final ExecutionEnvironment next = harness.mainTurn(session, ALICE);
        assertThat(((SandboxExecutionEnvironment) next).binding().workspaceSwitch())
                .as("a record holding only the reset is no move").isEmpty();
        assertThat(next.fileSystem().exists(KEPT)).isFalse();
        assertReplay(next.drainNotices());
        assertThat(next.drainNotices()).isEmpty();
        assertThat(options()).as("the bookkeeping record goes with its last fact").isEmpty();

        final ExecutionEnvironment after = harness.mainTurn(session, ALICE);
        after.fileSystem().exists(KEPT);
        assertThat(after.drainNotices()).isEmpty();
    }

    @Test
    @DisplayName("§9: a staging call after the last tool result resets — the next turn hears it once")
    void aStagingCallsResetIsToldOnTheNextTurn() throws Exception {
        Files.createDirectories(source.resolve("demo"));
        Files.writeString(source.resolve("demo/run.sh"), "echo hi\n");
        final StagedResource skill = StagedResource.scan(VirtualFileSystems.readOnlyLocal(source), "demo", "demo");
        modelFacingTurn("true");
        idleClose();

        harness.mainTurn(session, ALICE).stage(skill);

        assertReplay(modelFacingTurn("true"));
        assertThat(modelFacingTurn("true")).isEmpty();
    }

    @Test
    @DisplayName("§6.3: the hook's re-provisioning fails after the reset — the next turn still hears the reset once")
    void aHooksFailedRecreateKeepsTheReset() throws Exception {
        modelFacingTurn("true");
        idleClose();
        harness.faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

        assertThatThrownBy(this::hookOnlyTurn).isInstanceOf(SandboxUnavailableException.class).message()
                .doesNotContain("also:");
        assertThat(harness.primary(session).state()).isEqualTo(SlotState.FAILED);
        assertThat(options().orElseThrow().unannouncedReset()).isPresent();

        harness.clock.advance(Duration.ofMinutes(2));
        assertReplay(modelFacingTurn("true"));
        assertThat(modelFacingTurn("true")).isEmpty();
    }

    @Test
    @DisplayName("§7: the ordinary path — a reset drained in its own turn is never told again")
    void aResetDrainedInItsTurnIsNotRepeated() throws Exception {
        modelFacingTurn("true");
        idleClose();

        assertThat(modelFacingTurn("true")).containsExactly(SandboxWorkspaceManager.RESET_NOTICE);
        assertThat(options()).isEmpty();
        assertThat(modelFacingTurn("true")).isEmpty();
    }

    @Test
    @DisplayName("§7: an acknowledgement still queued at the next turn's bind repeats the reset once — the bounded window")
    void aDelayedAcknowledgementRepeatsTheResetOnce() throws Exception {
        final QueueingExecutor background = new QueueingExecutor();
        harness.close();
        harness = SandboxHarness.builder().background(background).build();
        modelFacingTurn("true");
        idleClose();
        background.runAll();

        assertThat(modelFacingTurn("true")).containsExactly(SandboxWorkspaceManager.RESET_NOTICE);
        assertThat(background.queued()).as("the acknowledgement waits on the background executor").isOne();

        assertReplay(modelFacingTurn("true"));
        background.runAll();
        assertThat(options()).isEmpty();
        assertThat(modelFacingTurn("true")).isEmpty();
    }

    @Test
    @DisplayName("§2.2: a fork's reset is told to the fork only and never kept on the session's record")
    void aForksResetIsNeverKept() throws Exception {
        final ExecutionEnvironment parent = harness.mainTurn(session, ALICE);
        bash(parent, "true");
        parent.drainNotices();
        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.primary(session).state()).isEqualTo(SlotState.TERMINATED);

        final ExecutionEnvironment fork = harness.fork(parent, ALICE);
        bash(fork, "true");

        assertThat(((SandboxExecutionEnvironment) parent).binding().sessionBookkeeping()).isPresent();
        assertThat(((SandboxExecutionEnvironment) fork).binding().sessionBookkeeping()).isEmpty();
        assertThat(fork.drainNotices()).singleElement().asString().contains("recreated");
        assertThat(options()).isEmpty();
    }

    @Test
    @DisplayName("§3.1 (c), (d): setting and clearing a session's options keep its unannounced reset")
    void setAndClearKeepTheReset() throws Exception {
        modelFacingTurn("true");
        idleClose();
        hookOnlyTurn();
        final UnannouncedReset kept = options().orElseThrow().unannouncedReset().orElseThrow();

        harness.sandbox.workspaces().setSessionOptions(session,
                SessionSandboxOptions.Update.rule(UnattachedRule.PER_SESSION), ALICE);
        assertThat(options().orElseThrow().unannouncedReset()).contains(kept);
        harness.sandbox.workspaces().clearSessionOptions(session, ALICE);
        assertThat(options().orElseThrow().unannouncedReset()).as("not deleted: it still holds the reset")
                .contains(kept);

        assertReplay(modelFacingTurn("true"));
        assertThat(options()).isEmpty();
    }

    @Test
    @DisplayName("§5.3: the acknowledgement clears only the value it delivered; the switch part keeps its strict CAS")
    void theAcknowledgementComparesTheValue() {
        final SandboxWorkspaceManager manager = harness.sandbox.manager();
        final UnannouncedReset older = UnannouncedReset.of(derived(), "primary", Instant.parse("2026-01-01T00:00:00Z"));
        final UnannouncedReset newer = UnannouncedReset.of(derived(), "primary", Instant.parse("2026-01-01T00:01:00Z"));
        final SessionSandboxOptions stored = harness.sessionOptions.put(SessionSandboxOptions.builder().session(session)
                .setBy(ALICES).unannouncedReset(newer).updatedAt(harness.clock.instant()).build(),
                OptionalLong.empty());

        manager.acknowledgeLater(session, OptionalLong.of(stored.version()), Optional.empty(), Optional.of(older),
                ALICES);
        assertThat(options()).contains(stored);

        final SandboxWorkspaceId elsewhere = SandboxWorkspaceId.of("wsm:elsewhere");
        manager.acknowledgeLater(session, OptionalLong.of(stored.version() - 1), Optional.of(elsewhere),
                Optional.of(newer), ALICES);
        assertThat(options()).as("cleared despite the stale version; no switch written; nothing left").isEmpty();
    }

    @Test
    @DisplayName("§3.1 (a): the mark retries a lost CAS")
    void theMarkRetriesALostCas() throws Exception {
        final int[] conflicts = {1};
        harness.close();
        harness = SandboxHarness.builder()
                .sessionOptions(new DelegatingOptionsStore(new InMemorySessionOptionsStore()) {
                    @Override
                    public SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expected) {
                        if (options.unannouncedReset().isPresent() && conflicts[0]-- > 0) {
                            throw new StaleVersionException(options.session(), expected.orElse(0));
                        }
                        return delegate.put(options, expected);
                    }
                }).build();
        modelFacingTurn("true");
        idleClose();

        hookOnlyTurn();

        assertThat(conflicts[0]).isNegative();
        assertThat(options().orElseThrow().unannouncedReset()).isPresent();
    }
}
