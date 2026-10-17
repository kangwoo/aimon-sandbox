package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.sandbox.SandboxAdministration;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.SandboxWorkspaces;
import at.aimon.sandbox.WorkspaceSandbox;
import at.aimon.sandbox.binding.ShellKey;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.testkit.ManualClock;
import at.aimon.sandbox.testkit.ManualScheduler;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.SandboxVerdict;
import at.aimon.sandbox.workspace.SandboxView;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionOptionsStore;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.SlotState;
import at.aimon.sandbox.workspace.WorkspaceQuery;
import at.aimon.sandbox.workspace.WorkspaceScan;
import at.aimon.sandbox.workspace.WorkspaceState;
import at.aimon.sandbox.workspace.WorkspaceView;

/**
 * Managed workspaces on a real OpenSandbox server (Docker runtime) — the docker tier of
 * docs/design/workspace-sandbox-management.md §9.2: create provisions a real sandbox, the owner and the administration
 * list it (verdict {@code OK}), an admin terminate is followed by a new generation,
 * two sessions of one owner share its files but not their cwd, an explicit close answers "closed" and a purge "does
 * not exist" — and, as documented behaviour of a shared workspace (§6.5), one session can read another's shell state.
 */
@Tag("docker")
class ManagedWorkspaceSandboxIT {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("managed-it");
    private static final Principal ALICE = Principal.user("alice");

    private final ManualClock clock = new ManualClock(Instant.now());
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final SessionOptionsStore sessionOptions = new InMemorySessionOptionsStore();
    private OpenSandboxProvider provider;
    private WorkspaceSandbox assembly;

    @BeforeEach
    void start() {
        provider = new OpenSandboxProvider(OpenSandboxTestServer.dnsNft().config().build());
        final SandboxProfile profile = SandboxProfile.builder().name("it").image(OpenSandboxTestServer.sandboxImage())
                .platform("linux").terminateAfter(Duration.ofHours(1))
                .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION)).build();
        final SandboxSettings settings = SandboxSettings.builder().deployment(OpenSandboxTestServer.DEPLOYMENT)
                .nodeId("managed-it-" + UUID.randomUUID()).profiles(List.of(profile)).defaultProfile("it")
                .closeWait(Duration.ofSeconds(20)).build();
        assembly = WorkspaceSandbox.builder().settings(settings).provider(provider)
                .store(new InMemorySandboxWorkspaceStore()).sessionOptionsStore(sessionOptions)
                .sessionOwnerLookup(session -> Optional.of(ALICE)).clock(clock).scheduler(scheduler).build();
    }

    @AfterEach
    void close() {
        final List<SandboxWorkspaceId> ids = new ArrayList<>();
        assembly.store().scan(WorkspaceScan.builder().build()).forEach(record -> ids.add(record.id()));
        ids.forEach(id -> assembly.manager().close(id, ALICE));
        assembly.close();
        provider.close();
    }

    private SandboxWorkspaces workspaces() {
        return assembly.workspaces();
    }

    private ExecutionEnvironment mainTurn(SessionId session) {
        return ExecutionEnvironments.resolveOrUnavailable(assembly.environmentProvider(),
                EnvironmentRequest.builder().agentRuntimeId(RUNTIME).sessionId(session).principal(ALICE).build());
    }

    private static ShellCommandResult bash(ExecutionEnvironment env, String command) throws Exception {
        return env.shell().execute(() -> command, ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).build());
    }

    private SessionId attached(SandboxWorkspaceId id) {
        final SessionId session = SessionId.generate();
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), ALICE);
        return session;
    }

    @Test
    @DisplayName("§9.2: create provisions a real sandbox before returning, and the owner's listing shows it")
    void createProvisionsARealSandboxAndListsIt() {
        final WorkspaceView created = workspaces().create(CreateWorkspace.named("it workspace"), ALICE);

        assertThat(created.slot("primary").orElseThrow().state()).isEqualTo(SlotState.RUNNING);
        final ProviderSandboxRef ref = assembly.store().find(created.id()).orElseThrow().slot("primary").orElseThrow()
                .providerRef().orElseThrow();
        assertThat(provider.status(ref)).isPresent();
        assertThat(workspaces().list(ALICE, WorkspaceQuery.builder().build()).items()).extracting(WorkspaceView::id)
                .containsExactly(created.id());
    }

    @Test
    @DisplayName("§9.2: two sessions of one owner attached — shared files, separate cwd")
    void twoAttachedSessionsShareFilesNotTheirCwd() throws Exception {
        final SandboxWorkspaceId id = workspaces().create(CreateWorkspace.named("pair"), ALICE).id();
        final SessionId first = attached(id);
        final SessionId second = attached(id);

        bash(mainTurn(first), "mkdir -p /workspace/repo/sub && cd /workspace/repo/sub && echo one > note.txt");

        assertThat(bash(mainTurn(second), "pwd; cat /workspace/repo/sub/note.txt").stdout())
                .isEqualTo("/workspace/repo\none\n");
        assertThat(bash(mainTurn(first), "pwd").stdout()).isEqualTo("/workspace/repo/sub\n");
        assertThat(assembly.store().scan(WorkspaceScan.builder().build())).hasSize(1);
    }

    @Test
    @DisplayName("§6.5, documented behaviour: in a shared workspace one session can read another's shell state")
    void aSessionCanReadAnotherSessionsShellStateInASharedWorkspace() throws Exception {
        final SandboxWorkspaceId id = workspaces().create(CreateWorkspace.named("one trust domain"), ALICE).id();
        final SessionId first = attached(id);
        final SessionId second = attached(id);
        bash(mainTurn(first), "export API_TOKEN=first-sessions-secret");

        final String state = "/workspace/.aimon-shell/" + ShellKey.session(first).directoryName() + "/state";
        assertThat(bash(mainTurn(second), "cat " + state).stdout()).contains("first-sessions-secret");
    }

    @Test
    @DisplayName("§9.2: close → 'workspace closed' for attached sessions; purge → 'does not exist'")
    void closeThenPurge() throws Exception {
        final SandboxWorkspaceId id = workspaces().create(CreateWorkspace.named("ending"), ALICE).id();
        final SessionId session = attached(id);
        bash(mainTurn(session), "true");
        final ProviderSandboxRef ref = assembly.store().find(id).orElseThrow().slot("primary").orElseThrow()
                .providerRef().orElseThrow();

        workspaces().close(id, ALICE);
        assertThat(provider.status(ref)).isEmpty();
        assertThat(assembly.store().find(id).orElseThrow().state()).isEqualTo(WorkspaceState.CLOSED);
        assertThatThrownBy(() -> bash(mainTurn(session), "true")).hasMessageContaining("closed");

        // The facade clears the attachment on purge; an option left pointing at the id (a failed second step) reads
        // "does not exist" until the janitor's sweep.
        final SessionSandboxOptions options = sessionOptions.find(session).orElseThrow();
        workspaces().purge(id, ALICE);
        sessionOptions.put(options.toBuilder().lastWorkspaceId(id).build(),
                java.util.OptionalLong.of(sessionOptions.find(session).orElseThrow().version()));
        assertThatThrownBy(() -> bash(mainTurn(session), "true")).hasMessageContaining("does not exist");
        assertThat(assembly.store().find(id)).isEmpty();
    }

    @Test
    @DisplayName("§9.2: the administration lists the created sandbox with verdict OK, and nothing raw")
    void theAdministrationListsTheSandboxAsOk() {
        final WorkspaceView created = workspaces().create(CreateWorkspace.named("listed"), ALICE);
        final ProviderSandboxRef ref = assembly.store().find(created.id()).orElseThrow().slot("primary").orElseThrow()
                .providerRef().orElseThrow();

        final List<SandboxView> sandboxes = SandboxAdministration.of(assembly).sandboxes();

        assertThat(sandboxes).filteredOn(view -> view.refHash().equals(SandboxLabels.h(ref.sandboxId())))
                .singleElement().satisfies(view -> {
                    assertThat(view.verdict()).isEqualTo(SandboxVerdict.OK);
                    assertThat(view.workspaceId()).contains(created.id());
                    assertThat(view.generation()).contains(1L);
                });
        assertThat(sandboxes.toString()).doesNotContain(ref.sandboxId());
    }

    @Test
    @DisplayName("§9.2: an admin terminate ends the real sandbox; the next command re-provisions a new generation")
    void anAdminTerminateIsFollowedByANewGeneration() throws Exception {
        final SandboxWorkspaceId id = workspaces().create(CreateWorkspace.named("terminated"), ALICE).id();
        final SessionId session = attached(id);
        bash(mainTurn(session), "echo before > /workspace/repo/f");
        final ProviderSandboxRef first = assembly.store().find(id).orElseThrow().slot("primary").orElseThrow()
                .providerRef().orElseThrow();

        SandboxAdministration.of(assembly).terminateSlot(id, "primary", Principal.user("operator"));

        assertThat(provider.status(first)).isEmpty();
        final ShellCommandResult after = bash(mainTurn(session), "ls /workspace/repo");
        assertThat(after.stdout()).doesNotContain("f");
        assertThat(after.notices()).anyMatch(notice -> notice.contains("recreated (generation 2)"));
        assertThat(assembly.store().find(id).orElseThrow().slot("primary").orElseThrow().generation()).isEqualTo(2);
    }
}
