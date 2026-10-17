package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ForkDefinition;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionOptionsStore;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * Resolves a root request through session options and the agent default before the configured policy
 * (docs/design/workspace-sandbox-management.md §6.3). {@code WorkspaceSandbox} wraps whatever policy the host
 * configured in it, so "only a policy makes bindings" still holds.
 *
 * <ol>
 * <li>the session's options attach it to a workspace → that workspace, {@code session:{id}};</li>
 * <li>the definition's {@code sandbox.workspace} names one → that workspace, {@code session:{id}} or
 * {@code exec:{id}};</li>
 * <li>otherwise the effective unattached rule: {@code per-session} → the delegate's binding ({@code ws:{sessionId}}
 * by default); {@code reject} → no sandbox.</li>
 * </ol>
 *
 * Rows 1 and 2 bind as the caller, are {@code mustExist} — a purged id is "does not exist", never re-created — and
 * keep the meaning of {@code sandbox.slot} and {@code sandbox.profile}; the manager's use check at {@code connect}
 * decides whether the executing principal may enter. The effective rule is
 * {@code strictest(setting, sandbox.unattached, session option)}: each layer can only tighten the one above it.
 *
 * <p>
 * Every root request with a session reads the options store once. A failing store fails the bind rather than falling
 * back to {@code per-session}, which would put an attached session in a different workspace. A session that has an
 * options record and lands in another workspace than it last connected to carries a {@link WorkspaceSwitch}. Forks are
 * unchanged: they inherit their parent's binding.
 */
public final class SessionOptionsBindingPolicy implements SandboxBindingPolicy {

    private final SandboxBindingPolicy delegate;
    private final CallerResolver callers;
    private final SandboxProfileRegistry profiles;
    private final SessionOptionsStore options;
    private final UnattachedRule floor;

    /**
     * @param delegate
     *            the configured policy, used for unattached requests under {@code per-session}
     * @param callers
     *            the principal gate
     * @param profiles
     *            the configured profiles
     * @param options
     *            the session options
     * @param floor
     *            the {@code unattached} setting
     */
    public SessionOptionsBindingPolicy(SandboxBindingPolicy delegate, CallerResolver callers,
            SandboxProfileRegistry profiles, SessionOptionsStore options, UnattachedRule floor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.floor = Objects.requireNonNull(floor, "floor must not be null");
    }

    /**
     * @param agent
     *            the definition's {@code sandbox.unattached}, or {@code null}
     * @param session
     *            the session's own rule, or {@code null}
     * @return {@code strictest(floor, agent, session)}
     */
    UnattachedRule effectiveRule(UnattachedRule agent, UnattachedRule session) {
        UnattachedRule rule = floor;
        if (agent != null) {
            rule = UnattachedRule.strictest(rule, agent);
        }
        if (session != null) {
            rule = UnattachedRule.strictest(rule, session);
        }
        return rule;
    }

    @Override
    public SandboxBinding bind(BindingContext context) {
        final Optional<SessionId> session = context.sessionId();
        final Optional<SessionSandboxOptions> record = session.flatMap(this::read);
        final Optional<SandboxWorkspaceId> agentDefault = agentDefault(context);
        final SandboxBinding binding;
        if (record.flatMap(SessionSandboxOptions::workspace).isPresent()) {
            binding = managed(context, record.get().workspace().get());
        } else if (agentDefault.isPresent()) {
            binding = managed(context, agentDefault.get());
        } else {
            final UnattachedRule rule = effectiveRule(agentRule(context),
                    record.flatMap(SessionSandboxOptions::unattached).orElse(null));
            if (rule == UnattachedRule.REJECT) {
                throw new BindingRejectedException(session.isPresent()
                        ? "this session is not attached to a sandbox workspace"
                        : "this execution is not attached to a sandbox workspace");
            }
            binding = delegate.bind(context);
        }
        return record.map(r -> withSwitch(binding, r)).orElse(binding);
    }

    @Override
    public SlotChoice forkSlot(SandboxBinding parent, ForkDefinition fork) {
        return delegate.forkSlot(parent, fork);
    }

    private Optional<SessionSandboxOptions> read(SessionId session) {
        try {
            return options.find(session);
        } catch (RuntimeException e) {
            throw new SandboxUnavailableException(
                    "the session's sandbox options cannot be read right now: " + e.getMessage(), e);
        }
    }

    private static Optional<SandboxWorkspaceId> agentDefault(BindingContext context) {
        final String value = context.attributes().get(SlotChoice.WORKSPACE_ATTRIBUTE);
        return value == null
                ? Optional.empty()
                : Optional.of(ManagedIds.require(value, definition(context) + "'s " + SlotChoice.WORKSPACE_ATTRIBUTE));
    }

    private static UnattachedRule agentRule(BindingContext context) {
        final String value = context.attributes().get(SlotChoice.UNATTACHED_ATTRIBUTE);
        if (value == null) {
            return null;
        }
        try {
            return UnattachedRule.parse(value);
        } catch (IllegalArgumentException e) {
            throw new BindingRejectedException(
                    definition(context) + "'s " + SlotChoice.UNATTACHED_ATTRIBUTE + ": " + e.getMessage());
        }
    }

    private static String definition(BindingContext context) {
        return context.agent().map(agent -> "agent definition '" + agent.getName() + "'").orElse("the definition");
    }

    /** Rows 1–2: the named workspace as the caller, {@code mustExist}, the definition's slot and profile. */
    private SandboxBinding managed(BindingContext context, SandboxWorkspaceId workspace) {
        final WorkspaceOwner caller = callers.callerOf(context.principal());
        final ShellKey shellKey;
        if (context.sessionId().isPresent()) {
            shellKey = ShellKey.session(context.sessionId().get());
        } else {
            final ExecutionId execution = context.executionId().orElseThrow(() -> new BindingRejectedException(
                    "this execution has neither a session nor an execution id, so it has no shell to run in"));
            shellKey = ShellKey.execution(execution);
        }
        final String slot = context.attributes().getOrDefault(SlotChoice.SLOT_ATTRIBUTE, SandboxBinding.PRIMARY);
        final String profile = context.attributes().get(SlotChoice.PROFILE_ATTRIBUTE);
        if (profile != null && profiles.find(profile).isEmpty()) {
            throw new BindingRejectedException(
                    "the definition requires sandbox profile '" + profile + "', which is not configured");
        }
        return SandboxBinding.builder().workspaceId(workspace).owner(caller).caller(caller).slot(slot)
                .requiredProfile(profile).shellKey(shellKey).mustExist(true).build();
    }

    /**
     * §6.4: a session with an options record that lands somewhere else than it last connected carries a switch. A
     * record that never attached the session ({@code lastWorkspaceId} and {@code workspace} both empty — it only set a
     * rule) is recorded silently, whatever row bound it: the session ran where it runs now, under the delegate or the
     * agent default, before the record existed.
     */
    private static SandboxBinding withSwitch(SandboxBinding binding, SessionSandboxOptions record) {
        final SandboxWorkspaceId to = binding.workspaceId();
        final Optional<SandboxWorkspaceId> from = record.lastWorkspaceId();
        if (from.isPresent() && from.get().equals(to)) {
            return binding;
        }
        final WorkspaceSwitch change = from.isEmpty() && record.workspace().isEmpty()
                ? WorkspaceSwitch.silent(record.session(), to, record.version())
                : WorkspaceSwitch.announced(record.session(), from.orElse(null), to, record.version());
        return binding.toBuilder().workspaceSwitch(change).build();
    }
}
