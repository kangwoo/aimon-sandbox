package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ForkDefinition;
import at.aimon.core.environment.RuntimeBinding;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.llm.LlmModel;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.WorkspaceScan;

/** {@link SandboxExecutionEnvironmentProvider}: forks (§16 rows 6–8), the declared descriptor, the step-3 guards. */
class SandboxEnvironmentProviderTest {

    private final SandboxHarness harness = SandboxHarness.builder()
            .profile(SandboxTestProfiles.local("standard")
                    .osVersion(SandboxTestProfiles.hostPlatform().equals("darwin") ? "Darwin *" : "Linux *").build())
            .profile(SandboxTestProfiles.local("other").backgroundCommandTimeout(Duration.ofMinutes(20)).build())
            .build();

    @AfterEach
    void close() {
        harness.close();
    }

    private static SandboxBinding binding(ExecutionEnvironment environment) {
        assertThat(environment).isInstanceOf(SandboxExecutionEnvironment.class);
        return ((SandboxExecutionEnvironment) environment).binding();
    }

    private static String cause(ExecutionEnvironment environment) {
        assertThat(environment).isInstanceOf(UnavailableExecutionEnvironment.class);
        return ((UnavailableExecutionEnvironment) environment).message();
    }

    @Test
    @DisplayName("§16: session-less routine → fork → grandchild fork share one workspace and slot")
    void routineForkChainStaysInOneWorkspaceAndSlot() throws Exception {
        final ExecutionEnvironment routine = harness.routine(ExecutionId.generate(), ALICE);
        final ExecutionEnvironment fork = harness.fork(routine, ALICE);
        final ExecutionEnvironment grandchild = harness.fork(fork, ALICE);

        assertThat(binding(fork).workspaceId()).isEqualTo(binding(routine).workspaceId());
        assertThat(binding(grandchild).workspaceId()).isEqualTo(binding(routine).workspaceId());
        assertThat(List.of(binding(routine).slot(), binding(fork).slot(), binding(grandchild).slot()))
                .containsOnly(SandboxBinding.PRIMARY);
        assertThat(List.of(binding(routine).shellKey(), binding(fork).shellKey(), binding(grandchild).shellKey()))
                .doesNotHaveDuplicates().allMatch(key -> key.isExecution());
        assertThat(binding(routine).workspaceId().value()).startsWith("ws:");

        bash(routine, "echo from-routine > /workspace/repo/shared.txt");
        assertThat(bash(grandchild, "cat /workspace/repo/shared.txt").stdout()).isEqualTo("from-routine\n");
        assertThat(harness.local.sandboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("§16: fork → grandchild of an unavailable parent are unavailable with the parent's cause")
    void forksOfUnavailableParentCarryParentCause() {
        final ExecutionEnvironment parent = harness
                .resolve(EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME).principal(ALICE).build());
        final ExecutionEnvironment fork = harness.fork(parent, ALICE);
        final ExecutionEnvironment grandchild = harness.fork(fork, ALICE);

        final String parentCause = cause(parent);
        assertThat(parentCause).contains("neither a session nor an execution id");
        assertThat(cause(fork)).isEqualTo(parentCause);
        assertThat(cause(grandchild)).isEqualTo(parentCause);
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).isEmpty();
    }

    @Test
    @DisplayName("§16: a parent-less fork (workflow runner step) is unavailable and creates no workspace")
    void parentlessForkIsUnavailableAndCreatesNoWorkspace() {
        final ExecutionEnvironment step = harness.resolve(
                EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME).executionId(ExecutionId.generate())
                        .principal(ALICE).fork(ForkDefinition.builder().name("step").build()).build());

        assertThat(cause(step)).contains("no parent environment");
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).isEmpty();
        assertThat(harness.faults.calls(Operation.CREATE)).isZero();
    }

    @Test
    void forkOfAnotherProvidersEnvironmentIsUnavailable() {
        final ExecutionEnvironment foreign = UnavailableExecutionEnvironment.of("x");
        final ExecutionEnvironment notSandbox = new at.aimon.core.environment.ExecutionEnvironment() {
            @Override
            public at.aimon.core.filesystem.VirtualFileSystem fileSystem() {
                return foreign.fileSystem();
            }

            @Override
            public at.aimon.core.shell.VirtualShell shell() {
                return foreign.shell();
            }

            @Override
            public EnvironmentDescriptor descriptor() {
                return foreign.descriptor();
            }

            @Override
            public String stage(at.aimon.core.environment.StagedResource resource) {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(cause(harness.fork(notSandbox, ALICE))).contains("not a sandbox environment");
    }

    @Test
    void resolveProvisionsNothingAndDescribesTheDeclaredProfile() {
        final ExecutionEnvironment env = harness.mainTurn(SessionId.generate(), ALICE);

        final EnvironmentDescriptor descriptor = env.descriptor();

        assertThat(harness.faults.calls(Operation.CREATE)).isZero();
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).isEmpty();
        assertThat(descriptor.workingDirectory()).isEqualTo("/workspace/repo");
        assertThat(descriptor.platform()).contains(SandboxTestProfiles.hostPlatform());
        assertThat(descriptor.osVersion()).hasValueSatisfying(v -> assertThat(v).endsWith(" *"));
        assertThat(descriptor.shellName()).contains("bash");
        assertThat(descriptor.notes().orElseThrow()).contains("isolated sandbox (profile 'standard')")
                .contains("the profile sets no egress policy")
                .contains("file tools resolve relative paths against /workspace/repo; prefer absolute paths")
                .contains("shell state persists cwd and exported variables only");
    }

    @Test
    void descriptorSummarisesEgress() {
        final SandboxBinding binding = SandboxBinding.builder()
                .workspaceId(at.aimon.sandbox.workspace.SandboxWorkspaceId.of("ws:x"))
                .owner(at.aimon.sandbox.workspace.WorkspaceOwner.of(at.aimon.sandbox.workspace.TenantId.DEFAULT, ALICE))
                .caller(at.aimon.sandbox.workspace.WorkspaceOwner.of(at.aimon.sandbox.workspace.TenantId.DEFAULT,
                        ALICE))
                .shellKey(at.aimon.sandbox.binding.ShellKey.session(SessionId.of("s"))).build();

        assertThat(SandboxExecutionEnvironmentProvider
                .descriptor(binding, SandboxTestProfiles.local("p").egress(List.of()).build()).notes().orElseThrow())
                .contains("network egress is blocked");
        assertThat(SandboxExecutionEnvironmentProvider
                .descriptor(binding,
                        SandboxTestProfiles.local("p").egress(List.of("github.com", "repo.maven.apache.org")).build())
                .notes().orElseThrow()).contains("network egress is limited to github.com, repo.maven.apache.org");
    }

    @Test
    void descriptorFollowsTheSlotsProfileOnceItExists() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment first = harness
                .resolve(EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME).sessionId(session)
                        .principal(ALICE).agent(agent(Map.of("sandbox.profile", "other"))).build());
        bash(first, "true");

        final ExecutionEnvironment next = harness.mainTurn(session, ALICE);

        assertThat(next.descriptor().notes().orElseThrow()).contains("profile 'other'");
    }

    @Test
    @DisplayName("§16: the environment states its profile's background ceiling — the heartbeat limit by default")
    void theBackgroundCeilingIsTheDeclaredProfiles() {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);
        final ExecutionEnvironment configured = harness.resolve(
                EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME).sessionId(SessionId.generate())
                        .principal(ALICE).agent(agent(Map.of("sandbox.profile", "other"))).build());
        final ExecutionEnvironment fork = harness.fork(main, ExecutionId.generate(), ALICE,
                Map.of("sandbox.profile", "other"));

        assertThat(main.backgroundCommandTimeout()).contains(SandboxProfile.DEFAULT_BACKGROUND_COMMAND_TIMEOUT);
        assertThat(configured.backgroundCommandTimeout()).contains(Duration.ofMinutes(20));
        assertThat(fork.backgroundCommandTimeout()).as("a fork states the profile it declares")
                .contains(Duration.ofMinutes(20));
        assertThat(harness.fork(main, ALICE).backgroundCommandTimeout()).isEqualTo(main.backgroundCommandTimeout());
    }

    @Test
    @DisplayName("§7: the provider keeps nothing per runtime — a binding closes to nothing and resolve needs none")
    void runtimeBindingsHoldNothingAndResolveNeedsNone() throws Exception {
        final SandboxExecutionEnvironmentProvider provider = harness.sandbox.environmentProvider();
        final AgentRuntimeId bound = AgentRuntimeId.fromName("bound");
        final SessionId session = SessionId.generate();
        final EnvironmentRequest request = EnvironmentRequest.builder().agentRuntimeId(bound).sessionId(session)
                .principal(ALICE).build();

        final RuntimeBinding binding = provider.bindRuntime(bound);
        bash(harness.resolve(request), "echo kept > /workspace/repo/kept.txt");
        binding.close();
        binding.close();

        assertThat(bash(harness.resolve(request), "cat /workspace/repo/kept.txt").stdout()).isEqualTo("kept\n");
        assertThat(bash(harness.resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("unbound"))
                .sessionId(SessionId.generate()).principal(ALICE).build()), "echo unbound").stdout())
                .isEqualTo("unbound\n");
        assertThat(harness.faults.calls(Operation.DESTROY)).as("closing a binding destroys no sandbox").isZero();
    }

    @Test
    void nonPrimarySlotsAreRefusedUntilStepFive() {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);

        final ExecutionEnvironment review = harness.fork(main, ExecutionId.generate(), ALICE,
                Map.of("sandbox.slot", "review"));
        final ExecutionEnvironment rooted = harness.resolve(
                EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME).sessionId(SessionId.generate())
                        .principal(ALICE).agent(agent(Map.of("sandbox.slot", "exp-a"))).build());

        assertThat(cause(review)).contains("only the primary slot is supported");
        assertThat(cause(rooted)).contains("only the primary slot is supported");
    }

    @Test
    void forkRequiringAnUnknownProfileIsUnavailable() {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);

        final ExecutionEnvironment fork = harness.fork(main, ExecutionId.generate(), ALICE,
                Map.of("sandbox.profile", "missing"));

        assertThat(cause(fork)).contains("'missing'").contains("not configured");
    }

    @Test
    void forkRequiringAnotherProfileThanTheSlotsIsRefusedAtConnect() {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);
        main.fileSystem().write("x.txt", "x");
        final ExecutionEnvironment fork = harness.fork(main, ExecutionId.generate(), ALICE,
                Map.of("sandbox.profile", "other"));

        assertThatThrownBy(() -> fork.fileSystem().read("x.txt"))
                .hasMessageContaining("already runs profile " + "'standard'")
                .hasMessageContaining("requires profile 'other'");
    }

    @Test
    void aForkInTheParentsSlotKeepsTheParentsRoot() throws Exception {
        try (SandboxHarness custom = SandboxHarness.builder().bindingPolicy(context -> {
            final at.aimon.sandbox.workspace.WorkspaceOwner owner = at.aimon.sandbox.workspace.WorkspaceOwner
                    .of(at.aimon.sandbox.workspace.TenantId.DEFAULT, ALICE);
            return SandboxBinding.builder()
                    .workspaceId(at.aimon.sandbox.workspace.SandboxWorkspaceId
                            .of("ws:" + context.sessionId().orElseThrow().value()))
                    .owner(owner).caller(owner)
                    .shellKey(at.aimon.sandbox.binding.ShellKey.session(context.sessionId().orElseThrow()))
                    .root("/workspace/app").build();
        }).build()) {
            final ExecutionEnvironment main = custom.mainTurn(SessionId.generate(), ALICE);
            final ExecutionEnvironment fork = custom.fork(main, ALICE);
            final ExecutionEnvironment grandchild = custom.fork(fork, ALICE);

            assertThat(binding(fork).root()).isEqualTo("/workspace/app");
            assertThat(binding(grandchild).root()).isEqualTo("/workspace/app");
            assertThat(fork.descriptor().workingDirectory()).isEqualTo("/workspace/app");
            fork.fileSystem().write("from-fork.txt", "x");
            assertThat(bash(main, "cat /workspace/app/from-fork.txt; pwd").stdout()).isEqualTo("x/workspace/app\n");
            assertThat(bash(fork, "pwd").stdout()).isEqualTo("/workspace/app\n");
        }
    }

    @Test
    void aPolicyThatNamesTheOwnerAsCallerCannotLetAnotherPrincipalIn() throws Exception {
        final at.aimon.sandbox.workspace.WorkspaceOwner owner = at.aimon.sandbox.workspace.WorkspaceOwner
                .of(at.aimon.sandbox.workspace.TenantId.DEFAULT, ALICE);
        // A ticket policy: every session of the ticket shares alice's workspace, and it names her as the caller.
        try (SandboxHarness custom = SandboxHarness.builder()
                .bindingPolicy(context -> SandboxBinding.builder()
                        .workspaceId(at.aimon.sandbox.workspace.SandboxWorkspaceId.of("ws:ticket-42")).owner(owner)
                        .caller(owner)
                        .shellKey(at.aimon.sandbox.binding.ShellKey.session(context.sessionId().orElseThrow())).build())
                .build()) {
            bash(custom.mainTurn(SessionId.generate(), ALICE), "true");
            final ExecutionEnvironment bobs = custom.mainTurn(SessionId.generate(),
                    at.aimon.core.base.Principal.user("bob"));

            assertThat(binding(bobs).caller()).isNotEqualTo(owner);
            assertThat(binding(bobs).caller().principal()).isEqualTo("USER:bob");
            assertThatThrownBy(() -> bash(bobs, "true")).hasMessageContaining("not permitted");
            assertThatThrownBy(() -> bobs.fileSystem().read("anything.txt")).hasMessageContaining("not permitted");
        }
    }

    @Test
    @DisplayName("§16: Principal.system() or no principal + require-principal is refused under a custom policy too")
    void requirePrincipalHoldsWhateverCallerACustomPolicyNames() throws Exception {
        final at.aimon.sandbox.workspace.WorkspaceOwner owner = at.aimon.sandbox.workspace.WorkspaceOwner
                .of(at.aimon.sandbox.workspace.TenantId.of("acme"), ALICE);
        // A policy that hands out a valid owner and caller for every request, principal or not.
        try (SandboxHarness custom = SandboxHarness.builder().settings(s -> s.requirePrincipal(true))
                .tenantResolver(principal -> at.aimon.sandbox.workspace.TenantId.of("acme"))
                .sessionOwnerLookup(
                        session -> java.util.Optional.of(ALICE))
                .bindingPolicy(context -> SandboxBinding.builder()
                        .workspaceId(at.aimon.sandbox.workspace.SandboxWorkspaceId.of("ws:shared")).owner(owner)
                        .caller(owner).shellKey(at.aimon.sandbox.binding.ShellKey
                                .session(context.sessionId().orElse(SessionId.of("none"))))
                        .build())
                .build()) {
            final ExecutionEnvironment system = custom
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME)
                            .sessionId(SessionId.generate()).principal(at.aimon.core.base.Principal.system()).build());
            final ExecutionEnvironment anonymous = custom.resolve(EnvironmentRequest.builder()
                    .agentRuntimeId(SandboxHarness.RUNTIME).sessionId(SessionId.generate()).build());

            assertThat(cause(system)).contains("require-principal is on");
            assertThat(cause(anonymous)).contains("require-principal is on");
            assertThat(bash(custom.mainTurn(SessionId.generate(), ALICE), "echo allowed").stdout())
                    .isEqualTo("allowed\n");
            assertThat(custom.faults.calls(Operation.CREATE)).as("only alice's call provisioned").isEqualTo(1);
        }
    }

    @Test
    void aForkWithoutAPrincipalActsForItsParentAndOneWithAPrincipalForThatPrincipal() throws Exception {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);
        bash(main, "true");

        // aimon-core's skill-fork path forwards no principal: the fork keeps its parent's caller.
        final ExecutionEnvironment anonymous = harness.resolve(
                EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME).executionId(ExecutionId.generate())
                        .parent(main).fork(ForkDefinition.builder().name("skill").build()).build());
        final ExecutionEnvironment bobs = harness.fork(main, at.aimon.core.base.Principal.user("bob"));

        assertThat(binding(anonymous).caller()).isEqualTo(binding(main).caller());
        assertThat(bash(anonymous, "echo fork").stdout()).isEqualTo("fork\n");
        assertThat(binding(bobs).caller().principal()).isEqualTo("USER:bob");
    }

    @Test
    void forkWithoutAnExecutionIdIsUnavailable() {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);

        final ExecutionEnvironment fork = harness
                .resolve(EnvironmentRequest.builder().agentRuntimeId(SandboxHarness.RUNTIME).parent(main)
                        .principal(ALICE).fork(ForkDefinition.builder().name("f").build()).build());

        assertThat(cause(fork)).contains("no execution id");
    }

    private static DefaultAgent agent(Map<String, String> attributes) {
        return DefaultAgent.builder().name("agent").systemPrompt("p").model(LlmModel.builder().name("m").build())
                .attributes(attributes).build();
    }
}
