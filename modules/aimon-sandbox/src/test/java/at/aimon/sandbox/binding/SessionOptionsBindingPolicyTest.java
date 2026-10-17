package at.aimon.sandbox.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

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
import at.aimon.sandbox.DelegatingOptionsStore;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionOptionsStore;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/** {@link SessionOptionsBindingPolicy}: the order of management design §6.3, the unattached layers, the switch. */
class SessionOptionsBindingPolicyTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("agent");
    private static final Principal ALICE = Principal.user("alice");
    private static final SandboxWorkspaceId ATTACHED = ManagedIds.generate();
    private static final SandboxWorkspaceId DEFAULT = ManagedIds.generate();
    private static final SandboxProfileRegistry PROFILES = new SandboxProfileRegistry(
            List.of(SandboxTestProfiles.local("standard").build(), SandboxTestProfiles.local("review").build()),
            "standard");
    private static final CallerResolver CALLERS = new CallerResolver(SandboxSettings.builder().deployment("d").build(),
            SandboxTenantResolver.SINGLE_TENANT);

    private final SessionOptionsStore store = new InMemorySessionOptionsStore();
    private final SessionId session = SessionId.generate();

    private SessionOptionsBindingPolicy policy(UnattachedRule floor) {
        return new SessionOptionsBindingPolicy(new DefaultSandboxBindingPolicy(CALLERS, PROFILES, null), CALLERS,
                PROFILES, store, floor);
    }

    private BindingContext turn(Map<String, String> attributes) {
        return context(session, null, attributes);
    }

    private static BindingContext context(SessionId session, ExecutionId execution, Map<String, String> attributes) {
        final EnvironmentRequest.Builder request = EnvironmentRequest.builder().agentRuntimeId(RUNTIME)
                .sessionId(session).executionId(execution).principal(ALICE);
        if (!attributes.isEmpty()) {
            request.agent(DefaultAgent.builder().name("helper").systemPrompt("p")
                    .model(LlmModel.builder().name("m").build()).attributes(attributes).build());
        }
        return BindingContext.from(request.build());
    }

    private SessionSandboxOptions options(SandboxWorkspaceId workspace, UnattachedRule rule, SandboxWorkspaceId last) {
        return store.put(
                SessionSandboxOptions.builder().session(session).setBy(WorkspaceOwner.of(TenantId.DEFAULT, ALICE))
                        .workspace(workspace).unattached(rule).lastWorkspaceId(last).updatedAt(Instant.EPOCH).build(),
                OptionalLong.empty());
    }

    private static Map<String, String> attributes(String... pairs) {
        final Map<String, String> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    @DisplayName("§6.3 row 1: the session's attachment wins over the agent default")
    void sessionOptionsComeFirst() {
        options(ATTACHED, null, null);

        final SandboxBinding binding = policy(UnattachedRule.PER_SESSION)
                .bind(turn(attributes(SlotChoice.WORKSPACE_ATTRIBUTE, DEFAULT.value())));

        assertThat(binding.workspaceId()).isEqualTo(ATTACHED);
        assertThat(binding.mustExist()).isTrue();
        assertThat(binding.shellKey()).isEqualTo(ShellKey.session(session));
        assertThat(binding.owner()).isEqualTo(binding.caller());
    }

    @Test
    @DisplayName("§6.3 row 2: the agent default, with session or exec shell, slot and profile kept")
    void theAgentDefaultIsNext() {
        final Map<String, String> attributes = attributes(SlotChoice.WORKSPACE_ATTRIBUTE, DEFAULT.value(),
                SlotChoice.PROFILE_ATTRIBUTE, "review");

        final SandboxBinding turn = policy(UnattachedRule.REJECT).bind(turn(attributes));
        final ExecutionId execution = ExecutionId.generate();
        final SandboxBinding routine = policy(UnattachedRule.REJECT).bind(context(null, execution, attributes));

        assertThat(turn.workspaceId()).isEqualTo(DEFAULT);
        assertThat(turn.requiredProfile()).contains("review");
        assertThat(turn.mustExist()).isTrue();
        assertThat(routine.workspaceId()).isEqualTo(DEFAULT);
        assertThat(routine.shellKey()).isEqualTo(ShellKey.execution(execution));
        assertThatThrownBy(() -> policy(UnattachedRule.REJECT).bind(turn(
                attributes(SlotChoice.WORKSPACE_ATTRIBUTE, DEFAULT.value(), SlotChoice.PROFILE_ATTRIBUTE, "nope"))))
                .hasMessageContaining("'nope', which is not configured");
    }

    @Test
    @DisplayName("§6.3 row 3: unattached under per-session goes to the delegate's own binding")
    void unattachedPerSessionIsTheDelegatesBinding() {
        final SandboxBinding binding = policy(UnattachedRule.PER_SESSION).bind(turn(Map.of()));

        assertThat(binding.workspaceId().value()).isEqualTo("ws:" + session.value());
        assertThat(binding.mustExist()).isFalse();
        assertThat(binding.workspaceSwitch()).as("no options record: never redirected").isEmpty();
    }

    @Test
    @DisplayName("§6.3: strictest(floor, agent, session) — every layer only tightens the one above it")
    void theUnattachedRuleOnlyTightens() {
        final UnattachedRule p = UnattachedRule.PER_SESSION;
        final UnattachedRule r = UnattachedRule.REJECT;
        final SessionOptionsBindingPolicy open = policy(p);
        final SessionOptionsBindingPolicy closed = policy(r);

        assertThat(open.effectiveRule(null, null)).isEqualTo(p);
        assertThat(open.effectiveRule(p, p)).as("both defaults: the session option decides").isEqualTo(p);
        assertThat(open.effectiveRule(null, r)).as("the session tightens").isEqualTo(r);
        assertThat(open.effectiveRule(r, null)).as("the agent tightens").isEqualTo(r);
        assertThat(open.effectiveRule(r, p)).as("the session cannot loosen the agent").isEqualTo(r);
        assertThat(closed.effectiveRule(p, null)).as("the agent cannot loosen the floor").isEqualTo(r);
        assertThat(closed.effectiveRule(p, p)).as("nor the session").isEqualTo(r);
    }

    @Test
    void rejectRefusesTheBindWithAModelFacingReason() {
        options(null, UnattachedRule.REJECT, null);

        assertThatThrownBy(() -> policy(UnattachedRule.PER_SESSION).bind(turn(Map.of())))
                .isInstanceOf(BindingRejectedException.class)
                .hasMessage("this session is not attached to a sandbox workspace");
        assertThatThrownBy(() -> policy(UnattachedRule.PER_SESSION)
                .bind(turn(attributes(SlotChoice.UNATTACHED_ATTRIBUTE, "reject"))))
                .hasMessage("this session is not attached to a sandbox workspace");
        assertThatThrownBy(() -> policy(UnattachedRule.REJECT).bind(context(null, ExecutionId.generate(), Map.of())))
                .hasMessage("this execution is not attached to a sandbox workspace");
    }

    @Test
    @DisplayName("§6.3: sandbox.workspace must be a wsm: id — a derived or malformed one names the definition")
    void malformedAndDerivedDefaultsAreRejected() {
        for (String value : List.of("ws:" + session.value(), "wsm:not-a-uuid", "")) {
            assertThatThrownBy(() -> policy(UnattachedRule.PER_SESSION)
                    .bind(turn(attributes(SlotChoice.WORKSPACE_ATTRIBUTE, value))))
                    .isInstanceOf(BindingRejectedException.class)
                    .hasMessageContaining("agent definition 'helper''s sandbox.workspace")
                    .hasMessageContaining("not a valid managed sandbox workspace id");
        }
        assertThatThrownBy(() -> policy(UnattachedRule.PER_SESSION)
                .bind(turn(attributes(SlotChoice.UNATTACHED_ATTRIBUTE, "sometimes"))))
                .hasMessageContaining("sandbox.unattached");
        assertThat(UnattachedRule.parse("Per_Session")).isEqualTo(UnattachedRule.PER_SESSION);
    }

    @Test
    @DisplayName("§6.3: a failing options store fails the bind — no silent fallback to per-session")
    void aFailingOptionsStoreFailsTheBind() {
        final SessionOptionsBindingPolicy failing = new SessionOptionsBindingPolicy(
                new DefaultSandboxBindingPolicy(CALLERS, PROFILES, null), CALLERS, PROFILES,
                new DelegatingOptionsStore(store) {
                    @Override
                    public Optional<SessionSandboxOptions> find(SessionId id) {
                        throw new IllegalStateException("database down");
                    }
                }, UnattachedRule.PER_SESSION);

        assertThatThrownBy(() -> failing.bind(turn(Map.of()))).isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("database down");
        assertThat(failing.bind(context(null, ExecutionId.generate(), Map.of())).workspaceId().value())
                .as("a routine reads no options").startsWith("ws:");
    }

    @Test
    void forksAreTheDelegatesChoice() {
        final SandboxBinding parent = policy(UnattachedRule.PER_SESSION).bind(turn(Map.of()));
        final ForkDefinition fork = ForkDefinition.builder().name("f")
                .attributes(Map.of(SlotChoice.PROFILE_ATTRIBUTE, "review")).build();

        final SlotChoice choice = policy(UnattachedRule.PER_SESSION).forkSlot(parent, fork);

        assertThat(choice.slot()).isEqualTo("primary");
        assertThat(choice.requiredProfile()).contains("review");
    }

    @Test
    @DisplayName("§6.4: a switch is carried when the bound workspace differs from the last one, silent for a first rule")
    void theSwitchFollowsLastWorkspaceId() {
        final SessionSandboxOptions attached = options(ATTACHED, null, null);
        final SandboxBinding first = policy(UnattachedRule.PER_SESSION).bind(turn(Map.of()));
        assertThat(first.workspaceSwitch()).hasValueSatisfying(change -> {
            assertThat(change.from()).isEmpty();
            assertThat(change.to()).isEqualTo(ATTACHED);
            assertThat(change.isSilent()).isFalse();
            assertThat(change.optionsVersion()).isEqualTo(attached.version());
            assertThat(change.notice()).contains(ATTACHED.value()).doesNotContain("files from");
        });

        final SessionSandboxOptions settled = store.put(attached.toBuilder().lastWorkspaceId(ATTACHED).build(),
                OptionalLong.of(attached.version()));
        assertThat(policy(UnattachedRule.PER_SESSION).bind(turn(Map.of())).workspaceSwitch()).isEmpty();

        store.put(settled.toBuilder().workspace(null).build(), OptionalLong.of(settled.version()));
        assertThat(policy(UnattachedRule.PER_SESSION).bind(turn(Map.of())).workspaceSwitch())
                .hasValueSatisfying(change -> {
                    assertThat(change.from()).contains(ATTACHED);
                    assertThat(change.to().value()).isEqualTo("ws:" + session.value());
                    assertThat(change.isSilent()).isFalse();
                });
    }

    @Test
    void aFirstRecordThatOnlySetsARuleIsRecordedSilently() {
        options(null, UnattachedRule.PER_SESSION, null);

        assertThat(policy(UnattachedRule.PER_SESSION).bind(turn(Map.of())).workspaceSwitch())
                .hasValueSatisfying(change -> assertThat(change.isSilent()).isTrue());
    }
}
