package at.aimon.sandbox.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ForkDefinition;
import at.aimon.core.llm.LlmModel;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/** The default binding rules (§8.2) and the principal gate (§8.3, §16 row 27). */
class DefaultSandboxBindingPolicyTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("agent");
    private static final SandboxProfileRegistry PROFILES = new SandboxProfileRegistry(
            List.of(SandboxTestProfiles.local("standard").build(), SandboxTestProfiles.local("review").build()),
            "standard");
    private static final SandboxTenantResolver TENANTS = principal -> TenantId.of("acme");

    private static DefaultSandboxBindingPolicy policy(SandboxSettings.Builder settings, SessionOwnerLookup owners) {
        return new DefaultSandboxBindingPolicy(new CallerResolver(settings.deployment("d").build(), TENANTS), PROFILES,
                owners);
    }

    private static DefaultSandboxBindingPolicy singleTenant() {
        return policy(SandboxSettings.builder(), null);
    }

    private static BindingContext request(SessionId session, ExecutionId execution, Principal principal,
            Map<String, String> attributes) {
        final EnvironmentRequest.Builder request = EnvironmentRequest.builder().agentRuntimeId(RUNTIME)
                .sessionId(session).executionId(execution).principal(principal);
        if (!attributes.isEmpty()) {
            request.agent(DefaultAgent.builder().name("a").systemPrompt("p").model(LlmModel.builder().name("m").build())
                    .attributes(attributes).build());
        }
        return BindingContext.from(request.build());
    }

    @Test
    void mainTurnBindsTheSessionsWorkspaceShellAndRoot() {
        final SessionId session = SessionId.of("s1");

        final SandboxBinding binding = singleTenant().bind(request(session, null, Principal.user("alice"), Map.of()));

        assertThat(binding.workspaceId().value()).isEqualTo("ws:s1");
        assertThat(binding.shellKey().value()).isEqualTo("session:s1");
        assertThat(binding.slot()).isEqualTo("primary");
        assertThat(binding.root()).isEqualTo("/workspace/repo");
        assertThat(binding.requiredProfile()).isEmpty();
        assertThat(binding.owner()).isEqualTo(WorkspaceOwner.of(TenantId.of("acme"), "alice"));
        assertThat(binding.caller()).isEqualTo(binding.owner());
    }

    @Test
    void aRoutineBindsItsExecutionsWorkspaceAndShell() {
        final SandboxBinding binding = singleTenant()
                .bind(request(null, ExecutionId.of("e1"), Principal.user("alice"), Map.of()));

        assertThat(binding.workspaceId().value()).isEqualTo("ws:e1");
        assertThat(binding.shellKey().value()).isEqualTo("exec:e1");
        assertThat(binding.shellKey().isExecution()).isTrue();
    }

    @Test
    void aRequestWithNeitherSessionNorExecutionIsRejected() {
        assertThatThrownBy(() -> singleTenant().bind(request(null, null, Principal.user("alice"), Map.of())))
                .isInstanceOf(BindingRejectedException.class).hasMessageContaining("neither a session");
    }

    @Test
    void definitionAttributesChooseSlotAndRequiredProfile() {
        final SandboxBinding binding = singleTenant().bind(request(SessionId.of("s"), null, Principal.user("a"),
                Map.of("sandbox.slot", "review", "sandbox.profile", "review")));

        assertThat(binding.slot()).isEqualTo("review");
        assertThat(binding.requiredProfile()).contains("review");
    }

    @Test
    void anUnknownRequiredProfileOrAnInvalidSlotIsRejected() {
        assertThatThrownBy(() -> singleTenant()
                .bind(request(SessionId.of("s"), null, Principal.user("a"), Map.of("sandbox.profile", "missing"))))
                .hasMessageContaining("'missing'");
        assertThatThrownBy(() -> singleTenant()
                .bind(request(SessionId.of("s"), null, Principal.user("a"), Map.of("sandbox.slot", "Bad_Slot"))))
                .hasMessageContaining("invalid sandbox slot name");
    }

    @Test
    @DisplayName("§16: Principal.system() with require-principal is rejected")
    void systemPrincipalIsRejectedWhenPrincipalRequired() {
        final DefaultSandboxBindingPolicy policy = policy(SandboxSettings.builder().requirePrincipal(true),
                session -> Optional.of(Principal.user("owner")));

        assertThatThrownBy(() -> policy.bind(request(SessionId.of("s"), null, Principal.system(), Map.of())))
                .isInstanceOf(BindingRejectedException.class).hasMessageContaining("USER or GROUP principal")
                .hasMessageContaining("SYSTEM 'system'");
        assertThatThrownBy(() -> policy.bind(request(SessionId.of("s"), null, null, Map.of())))
                .hasMessageContaining("has none");
        assertThatThrownBy(
                () -> policy.bind(request(null, ExecutionId.of("e"), Principal.service("svc", "Service"), Map.of())))
                .hasMessageContaining("SERVICE 'svc'");
    }

    @Test
    void listedSystemPrincipalsAndGroupsPassTheGate() {
        final DefaultSandboxBindingPolicy policy = policy(
                SandboxSettings.builder().requirePrincipal(true).allowedSystemPrincipals(Set.of("scheduler")),
                session -> Optional.of(Principal.user("owner")));

        final SandboxBinding routine = policy.bind(request(null, ExecutionId.of("e"),
                Principal.builder().type(Principal.Type.SYSTEM).id("scheduler").displayName("Scheduler").build(),
                Map.of()));
        final SandboxBinding group = policy
                .bind(request(SessionId.of("s"), null, Principal.group("team", "Team"), Map.of()));

        assertThat(routine.owner().principalId()).isEqualTo("scheduler");
        assertThat(group.caller().principalId()).isEqualTo("team");
    }

    @Test
    void theOwnerOfAMainTurnIsTheSessionsOwnerNotTheCaller() {
        final DefaultSandboxBindingPolicy policy = policy(SandboxSettings.builder(),
                session -> Optional.of(Principal.user("owner")));

        final SandboxBinding binding = policy.bind(request(SessionId.of("s"), null, Principal.user("guest"), Map.of()));

        assertThat(binding.owner().principalId()).isEqualTo("owner");
        assertThat(binding.caller().principalId()).isEqualTo("guest");
    }

    @Test
    void anUnknownSessionOwnerIsRejected() {
        final DefaultSandboxBindingPolicy policy = policy(SandboxSettings.builder(), session -> Optional.empty());

        assertThatThrownBy(() -> policy.bind(request(SessionId.of("s"), null, Principal.user("a"), Map.of())))
                .hasMessageContaining("is not known");
    }

    @Test
    void withoutRequirePrincipalAnAbsentPrincipalIsAnonymous() {
        final SandboxBinding binding = singleTenant().bind(request(SessionId.of("s"), null, null, Map.of()));

        assertThat(binding.caller()).isEqualTo(WorkspaceOwner.of(TenantId.DEFAULT, CallerResolver.ANONYMOUS));
    }

    @Test
    void aPrincipalWithoutATenantIsRejected() {
        final CallerResolver callers = new CallerResolver(SandboxSettings.builder().deployment("d").build(),
                principal -> null);

        assertThatThrownBy(() -> callers.callerOf(Optional.of(Principal.user("x"))))
                .hasMessageContaining("no tenant is known");
    }

    @Test
    void forkSlotDefaultsToTheParentsSlotAndRequirement() {
        final SandboxBinding parent = singleTenant()
                .bind(request(SessionId.of("s"), null, Principal.user("a"), Map.of("sandbox.profile", "review")));

        final SlotChoice same = singleTenant().forkSlot(parent, ForkDefinition.builder().name("f").build());
        final SlotChoice other = singleTenant().forkSlot(parent,
                ForkDefinition.builder().name("f").attributes(Map.of("sandbox.slot", "exp-a")).build());
        final SlotChoice explicit = singleTenant().forkSlot(parent,
                ForkDefinition.builder().name("f").attributes(Map.of("sandbox.profile", "standard")).build());

        assertThat(same.slot()).isEqualTo("primary");
        assertThat(same.requiredProfile()).contains("review");
        assertThat(other.slot()).isEqualTo("exp-a");
        assertThat(other.requiredProfile()).isEmpty();
        assertThat(explicit.requiredProfile()).contains("standard");
    }
}
