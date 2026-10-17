package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.sandbox.binding.SandboxTenantResolver;
import at.aimon.sandbox.binding.SlotChoice;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.environment.SandboxExecutionEnvironment;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceScan;
import at.aimon.sandbox.workspace.WorkspaceState;

/**
 * Sessions in managed workspaces end to end on the local provider (management design §6.2–§6.4, §9.1): attached turns
 * and their forks, the switch notice, purged and closed targets, a non-owner participant, a SYSTEM routine on an agent
 * default, and the races of options against close and a re-attach.
 */
class ManagedSessionsIT {

    private static final Principal ALICE = Principal.user("t1-alice");
    private static final Principal ALBERT = Principal.user("t1-albert");
    private static final SandboxTenantResolver BY_PREFIX = principal -> principal.getId().contains("-")
            ? TenantId.of(principal.getId().substring(0, principal.getId().indexOf('-')))
            : TenantId.of("t1");

    private final SessionId session = SessionId.generate();
    private SandboxHarness harness = harness(UnaryOperator.identity());

    private static SandboxHarness harness(UnaryOperator<SandboxSettings.Builder> settings) {
        return SandboxHarness.builder().tenantResolver(BY_PREFIX).settings(settings)
                .sessionOwnerLookup(id -> Optional.of(ALICE)).build();
    }

    @AfterEach
    void close() {
        harness.close();
    }

    private SandboxWorkspaces workspaces() {
        return harness.sandbox.workspaces();
    }

    private SandboxWorkspaceId create(String name) {
        return workspaces().create(CreateWorkspace.named(name), ALICE).id();
    }

    private void attach(SandboxWorkspaceId id) {
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), ALICE);
    }

    private static String cause(ExecutionEnvironment environment) {
        assertThat(environment).isInstanceOf(UnavailableExecutionEnvironment.class);
        return ((UnavailableExecutionEnvironment) environment).message();
    }

    @Test
    @DisplayName("§6.3: an attached session's turns and forks run in the managed workspace; no ws:{sid} is made")
    void anAttachedSessionRunsInTheManagedWorkspace() throws Exception {
        final SandboxWorkspaceId id = create("shared");
        attach(id);

        final ExecutionEnvironment turn = harness.mainTurn(session, ALICE);
        bash(turn, "echo from-turn > /workspace/repo/f");
        final ExecutionEnvironment fork = harness.fork(turn, ALICE);

        assertThat(((SandboxExecutionEnvironment) fork).binding().mustExist()).isTrue();
        assertThat(((SandboxExecutionEnvironment) fork).binding().workspaceSwitch()).isEmpty();
        assertThat(bash(fork, "cat /workspace/repo/f").stdout()).isEqualTo("from-turn\n");
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).extracting(r -> r.id()).containsExactly(id);
        assertThat(harness.local.sandboxCount()).isOne();
    }

    @Test
    @DisplayName("§6.4: attach → one notice, repeated turns → none, clear → a notice on the fallback")
    void theSwitchNoticeIsGivenOncePerMove() throws Exception {
        final SandboxWorkspaceId id = create("noticed");
        assertThat(bash(harness.mainTurn(session, ALICE), "true").notices()).as("no options: never redirected")
                .isEmpty();

        attach(id);
        final ShellCommandResult attached = bash(harness.mainTurn(session, ALICE), "true");
        assertThat(attached.notices()).singleElement().asString().contains("now runs in sandbox workspace " + id)
                .doesNotContain("noticed");
        assertThat(harness.sessionOptions.find(session).orElseThrow().lastWorkspaceId()).contains(id);
        assertThat(bash(harness.mainTurn(session, ALICE), "true").notices()).isEmpty();

        workspaces().clearSessionOptions(session, ALICE);
        assertThat(bash(harness.mainTurn(session, ALICE), "true").notices()).singleElement().asString()
                .contains("ws:" + session.value());
        assertThat(bash(harness.mainTurn(session, ALICE), "true").notices()).isEmpty();
    }

    @Test
    @DisplayName("§6.4: a turn of file tools only leaves the switch for the next Bash")
    void theSwitchIsRecordedOnlyOnceItReachedACommandResult() throws Exception {
        final SandboxWorkspaceId id = create("files");
        attach(id);

        harness.mainTurn(session, ALICE).fileSystem().write("/workspace/repo/a.txt", "a");
        assertThat(harness.sessionOptions.find(session).orElseThrow().lastWorkspaceId()).isEmpty();

        final ExecutionEnvironment next = harness.mainTurn(session, ALICE);
        next.fileSystem().write("/workspace/repo/b.txt", "b");
        assertThat(bash(next, "true").notices()).singleElement().asString().contains(id.value());
        assertThat(harness.sessionOptions.find(session).orElseThrow().lastWorkspaceId()).contains(id);
    }

    @Test
    void aFirstRuleOnlyRecordIsRecordedSilently() throws Exception {
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.rule(UnattachedRule.PER_SESSION), ALICE);

        assertThat(bash(harness.mainTurn(session, ALICE), "true").notices()).isEmpty();
        assertThat(harness.sessionOptions.find(session).orElseThrow().lastWorkspaceId())
                .contains(SandboxWorkspaceId.of("ws:" + session.value()));
    }

    @Test
    @DisplayName("§7: explicit close → 'closed' until reopen; purge → 'does not exist' until the options are cleared")
    void closedAndPurgedTargets() throws Exception {
        final SandboxWorkspaceId id = create("ending");
        attach(id);
        bash(harness.mainTurn(session, ALICE), "true");

        workspaces().close(id, ALICE);
        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true")).hasMessageContaining("closed");
        workspaces().purge(id, ALICE);
        harness.sessionOptions.put(harness.sessionOptions.find(session).orElseThrow().toBuilder().workspace(id).build(),
                OptionalLong.of(harness.sessionOptions.find(session).orElseThrow().version()));

        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                .hasMessageContaining("sandbox workspace " + id + " does not exist");
        assertThat(harness.store.find(id)).as("never re-created by a bind").isEmpty();

        harness.sandbox.janitor().runOnce();
        assertThat(bash(harness.mainTurn(session, ALICE), "echo back").stdout()).isEqualTo("back\n");
        assertThat(harness.store.find(SandboxWorkspaceId.of("ws:" + session.value()))).isPresent();
    }

    @Test
    @DisplayName("§6.2: a non-owner participant's turn into the attached workspace passes only if it may use it")
    void aNonOwnerParticipantIsCheckedAtConnect() throws Exception {
        final SandboxWorkspaceId id = create("private");
        attach(id);

        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALBERT), "true")).hasMessageContaining("not permitted");

        harness.close();
        harness = harness(s -> s.allowSharedWorkspaces(true));
        final SandboxWorkspaceId shared = workspaces()
                .create(CreateWorkspace.builder().name("team").access(SandboxSettings.WorkspaceAccess.TENANT).build(),
                        ALICE)
                .id();
        attach(shared);
        assertThat(bash(harness.mainTurn(session, ALBERT), "echo albert").stdout()).isEqualTo("albert\n");
    }

    @Test
    @DisplayName("§6.3: a SYSTEM routine reaches an agent default only when allowed and owner or shared")
    void aSystemRoutineWithAnAgentDefault() throws Exception {
        harness.close();
        harness = harness(
                s -> s.requirePrincipal(true).allowedSystemPrincipals(Set.of("system")).allowSharedWorkspaces(true));
        final SandboxWorkspaceId alices = create("alice's");
        final SandboxWorkspaceId systems = workspaces().create(CreateWorkspace.named("system's"), Principal.system())
                .id();
        final SandboxWorkspaceId shared = workspaces()
                .create(CreateWorkspace.builder().name("shared").access(SandboxSettings.WorkspaceAccess.TENANT).build(),
                        ALICE)
                .id();

        assertThatThrownBy(() -> bash(routine(alices), "true")).hasMessageContaining("not permitted");
        assertThat(bash(routine(systems), "echo own").stdout()).isEqualTo("own\n");
        assertThat(bash(routine(shared), "echo shared").stdout()).isEqualTo("shared\n");

        harness.close();
        harness = harness(s -> s.requirePrincipal(true));
        assertThat(cause(routine(alices))).contains("allowed-system-principals");
    }

    private ExecutionEnvironment routine(SandboxWorkspaceId workspace) {
        return harness.resolve(EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME)
                .executionId(ExecutionId.generate()).principal(Principal.system())
                .agent(DefaultAgent.builder().name("nightly").systemPrompt("p")
                        .model(LlmModel.builder().name("m").build())
                        .attributes(Map.of(SlotChoice.WORKSPACE_ATTRIBUTE, workspace.value())).build())
                .build());
    }

    @Test
    @DisplayName("§7: setSessionOptions racing a close — the attachment lands; the next turn hears 'closed'")
    void setSessionOptionsRacingAClose() throws Exception {
        final SandboxWorkspaceId[] target = new SandboxWorkspaceId[1];
        final SandboxWorkspaces[] facade = new SandboxWorkspaces[1];
        harness.close();
        harness = SandboxHarness.builder().tenantResolver(BY_PREFIX).sessionOwnerLookup(id -> Optional.of(ALICE))
                .sessionOptions(new DelegatingOptionsStore(new InMemorySessionOptionsStore()) {
                    @Override
                    public SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expected) {
                        if (options.workspace().isPresent() && target[0] != null) {
                            facade[0].close(target[0], ALICE);
                            target[0] = null;
                        }
                        return delegate.put(options, expected);
                    }
                }).build();
        facade[0] = workspaces();
        final SandboxWorkspaceId id = create("racing");
        target[0] = id;

        attach(id);

        assertThat(harness.store.find(id).orElseThrow().state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(harness.sessionOptions.find(session).orElseThrow().workspace()).contains(id);
        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true")).hasMessageContaining("closed");
        workspaces().reopen(id, ALICE);
        assertThat(bash(harness.mainTurn(session, ALICE), "echo ok").stdout()).isEqualTo("ok\n");
    }

    @Test
    @DisplayName("§6.4: a re-attach between two turns flips the notice to the new target, once")
    void aReattachFlipsTheNotice() throws Exception {
        final SandboxWorkspaceId first = create("first");
        final SandboxWorkspaceId second = create("second");
        attach(first);
        final ExecutionEnvironment stale = harness.mainTurn(session, ALICE);
        attach(second);

        // The turn bound before the re-attach still runs where it was bound, and its record write loses the CAS.
        assertThat(bash(stale, "true").notices()).singleElement().asString().contains(first.value());
        assertThat(harness.sessionOptions.find(session).orElseThrow().lastWorkspaceId()).isEmpty();

        assertThat(bash(harness.mainTurn(session, ALICE), "true").notices()).singleElement().asString()
                .contains(second.value());
        assertThat(bash(harness.mainTurn(session, ALICE), "true").notices()).isEmpty();
    }
}
