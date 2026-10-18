package at.aimon.sandbox.binding;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

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
 * Resolves a root request through its request attributes, session options and the agent default before the configured
 * policy (docs/design/workspace-sandbox-management.md §6.3, docs/design/workspace-sandbox-core-033.md §5.1, §6.1).
 * {@code WorkspaceSandbox} wraps whatever policy the host configured in it, so "only a policy makes bindings" still
 * holds. The first row that applies wins:
 *
 * <ol start="0">
 * <li>the request's {@code sandbox.workspace} ({@link SandboxRequestAttributes#WORKSPACE}) names one → that workspace,
 * for this execution only, {@code session:{id}} or {@code exec:{id}};</li>
 * <li>the session's options attach it to a workspace → that workspace, {@code session:{id}};</li>
 * <li>the definition's {@code sandbox.workspace} names one → that workspace, {@code session:{id}} or
 * {@code exec:{id}};</li>
 * <li>otherwise the effective unattached rule: {@code per-session} → the delegate's binding ({@code ws:{sessionId}}
 * by default); {@code reject} → no sandbox.</li>
 * </ol>
 *
 * Rows 0 to 2 bind as the caller, are {@code mustExist} — a purged id is "does not exist", never re-created — and
 * keep the meaning of {@code sandbox.slot} and {@code sandbox.profile}; the manager's use check at {@code connect}
 * decides whether the executing principal may enter. A request attribute redirects only its own execution and writes
 * no session option, so the use right is the whole right it needs. The effective rule is
 * {@code strictest(setting, sandbox.unattached, session option, request sandbox.unattached)}: each layer can only
 * tighten the one above it. A request key under {@code sandbox.} other than those two is refused.
 *
 * <p>
 * Every root request with a session reads the options store once. A failing store fails the bind rather than falling
 * back to {@code per-session}, which would put an attached session in a different workspace — also when row 0
 * applies, since the switch notice needs the record. A session that lands in another workspace than it last connected
 * to carries a {@link WorkspaceSwitch}, and every session's binding the {@link SessionBookkeeping} of the record it
 * read — no second read (docs/design/workspace-sandbox-unannounced-reset.md §3.2). Forks are unchanged: they inherit
 * their parent's binding, row 0 included, and never reach this policy's {@code bind}.
 */
public final class SessionOptionsBindingPolicy implements SandboxBindingPolicy {

    /** The {@code sandbox.*} request keys a host may set; the definition keeps {@code slot} and {@code profile}. */
    private static final Set<String> REQUEST_KEYS = Set.of(SlotChoice.WORKSPACE_ATTRIBUTE,
            SlotChoice.UNATTACHED_ATTRIBUTE);
    private static final String SANDBOX_PREFIX = "sandbox.";

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
     * @param request
     *            the request's {@code sandbox.unattached}, or {@code null}
     * @return {@code strictest(floor, agent, session, request)}
     */
    UnattachedRule effectiveRule(UnattachedRule agent, UnattachedRule session, UnattachedRule request) {
        UnattachedRule rule = floor;
        for (UnattachedRule layer : new UnattachedRule[]{agent, session, request}) {
            if (layer != null) {
                rule = UnattachedRule.strictest(rule, layer);
            }
        }
        return rule;
    }

    @Override
    public SandboxBinding bind(BindingContext context) {
        final Map<String, String> request = requestAttributes(context);
        final Optional<SessionId> session = context.sessionId();
        final Optional<SessionSandboxOptions> record = session.flatMap(this::read);
        final Optional<SandboxWorkspaceId> requested = requestedWorkspace(request);
        final Optional<SandboxWorkspaceId> agentDefault = agentDefault(context);
        final SandboxBinding binding;
        if (requested.isPresent()) {
            binding = managed(context, requested.get());
        } else if (record.flatMap(SessionSandboxOptions::workspace).isPresent()) {
            binding = managed(context, record.get().workspace().get());
        } else if (agentDefault.isPresent()) {
            binding = managed(context, agentDefault.get());
        } else {
            final UnattachedRule rule = effectiveRule(agentRule(context),
                    record.flatMap(SessionSandboxOptions::unattached).orElse(null), requestRule(request));
            if (rule == UnattachedRule.REJECT) {
                throw new BindingRejectedException(session.isPresent()
                        ? "this session is not attached to a sandbox workspace"
                        : "this execution is not attached to a sandbox workspace");
            }
            binding = delegate.bind(context);
        }
        return session.map(id -> withSwitch(binding, id, record, requested.isPresent()).toBuilder()
                .sessionBookkeeping(SessionBookkeeping.of(id, record)).build()).orElse(binding);
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

    /**
     * The request's {@code sandbox.*} keys, refusing any this policy does not read: a placement input dropped silently
     * would be the worse failure (core's reasoning for #252).
     */
    private static Map<String, String> requestAttributes(BindingContext context) {
        final Map<String, String> request = context.requestAttributes();
        for (String key : request.keySet()) {
            if (key.startsWith(SANDBOX_PREFIX) && !REQUEST_KEYS.contains(key)) {
                throw new BindingRejectedException(
                        "request attribute environment." + key + " is not supported; a request may set only "
                                + SandboxRequestAttributes.WORKSPACE + " and " + SandboxRequestAttributes.UNATTACHED);
            }
        }
        return request;
    }

    private static Optional<SandboxWorkspaceId> requestedWorkspace(Map<String, String> request) {
        final String value = request.get(SlotChoice.WORKSPACE_ATTRIBUTE);
        return value == null
                ? Optional.empty()
                : Optional.of(ManagedIds.require(value, "the request's " + SandboxRequestAttributes.WORKSPACE));
    }

    private static UnattachedRule requestRule(Map<String, String> request) {
        final String value = request.get(SlotChoice.UNATTACHED_ATTRIBUTE);
        if (value == null) {
            return null;
        }
        try {
            return UnattachedRule.parse(value);
        } catch (IllegalArgumentException e) {
            throw new BindingRejectedException(
                    "the request's " + SandboxRequestAttributes.UNATTACHED + ": " + e.getMessage());
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

    /** Rows 0–2: the named workspace as the caller, {@code mustExist}, the definition's slot and profile. */
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
     * §6.4: a session that lands somewhere else than it last connected carries a switch. Without an options record
     * only a request-attribute redirect does — otherwise the session never moved — and its notice uses the from-absent
     * wording; the recorder then creates the record. A record that never attached the session
     * ({@code lastWorkspaceId} and {@code workspace} both empty — it only set a rule) is recorded silently when
     * rows 1–3 bound it: the session ran where it runs now, under the delegate or the agent default, before the record
     * existed. Row 0 with no {@code lastWorkspaceId} is always announced (core-033 design §6.4).
     *
     * <p>
     * A record holding nothing but an unannounced reset counts as none here: the environment created it as bookkeeping
     * for a derived-workspace session that never moved, which must not turn into a switch, nor leave a
     * {@code lastWorkspaceId} record behind (docs/design/workspace-sandbox-unannounced-reset.md §3.2).
     */
    private static SandboxBinding withSwitch(SandboxBinding binding, SessionId session,
            Optional<SessionSandboxOptions> record, boolean requested) {
        if ((record.isEmpty() || onlyAReset(record.get())) && !requested) {
            return binding;
        }
        final SandboxWorkspaceId to = binding.workspaceId();
        final Optional<SandboxWorkspaceId> from = record.flatMap(SessionSandboxOptions::lastWorkspaceId);
        if (from.isPresent() && from.get().equals(to)) {
            return binding;
        }
        final OptionalLong version = record.map(r -> OptionalLong.of(r.version())).orElse(OptionalLong.empty());
        final WorkspaceSwitch change = !requested && from.isEmpty() && record.get().workspace().isEmpty()
                ? WorkspaceSwitch.silent(session, to, version)
                : WorkspaceSwitch.announced(session, from.orElse(null), to, version);
        return binding.toBuilder().workspaceSwitch(change).build();
    }

    private static boolean onlyAReset(SessionSandboxOptions record) {
        return record.workspace().isEmpty() && record.unattached().isEmpty() && record.lastWorkspaceId().isEmpty();
    }
}
