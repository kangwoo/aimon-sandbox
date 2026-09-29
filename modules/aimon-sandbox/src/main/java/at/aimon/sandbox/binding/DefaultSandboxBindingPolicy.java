package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * The default binding rules (docs/design/workspace-sandbox.md §8.2).
 *
 * <ul>
 * <li>workspace: {@code ws:{sessionId}}, else {@code ws:{executionId}}; a request with neither is rejected — no id
 * is invented for it;</li>
 * <li>owner: the session's owner from {@link SessionOwnerLookup} for a main turn (the executing principal when no
 * lookup is configured — single tenant only), the executing principal for a routine;</li>
 * <li>slot: {@code sandbox.slot} else {@code primary}; required profile: {@code sandbox.profile}, which must be
 * configured;</li>
 * <li>shell key: {@code session:{sessionId}} or {@code exec:{executionId}}; root: {@code /workspace/repo}.</li>
 * </ul>
 */
public final class DefaultSandboxBindingPolicy implements SandboxBindingPolicy {

    private final CallerResolver callers;
    private final SandboxProfileRegistry profiles;
    private final SessionOwnerLookup sessionOwners;

    /**
     * @param callers
     *            the principal gate and tenant resolution
     * @param profiles
     *            the configured profiles
     * @param sessionOwners
     *            the session owner lookup, or {@code null} in a single-tenant deployment
     */
    public DefaultSandboxBindingPolicy(CallerResolver callers, SandboxProfileRegistry profiles,
            SessionOwnerLookup sessionOwners) {
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.sessionOwners = sessionOwners;
    }

    @Override
    public SandboxBinding bind(BindingContext context) {
        final WorkspaceOwner caller = callers.callerOf(context.principal());
        final SandboxBinding.Builder binding = SandboxBinding.builder().caller(caller);
        final Optional<SessionId> session = context.sessionId();
        final Optional<ExecutionId> execution = context.executionId();
        if (session.isPresent()) {
            binding.workspaceId(SandboxWorkspaceId.of("ws:" + session.get().value()))
                    .owner(sessionOwner(session.get(), caller)).shellKey(ShellKey.session(session.get()));
        } else if (execution.isPresent()) {
            binding.workspaceId(SandboxWorkspaceId.of("ws:" + execution.get().value())).owner(caller)
                    .shellKey(ShellKey.execution(execution.get()));
        } else {
            throw new BindingRejectedException(
                    "this execution has neither a session nor an execution id, so no sandbox workspace can be chosen");
        }
        final String slot = context.attributes().getOrDefault(SlotChoice.SLOT_ATTRIBUTE, SandboxBinding.PRIMARY);
        final String profile = context.attributes().get(SlotChoice.PROFILE_ATTRIBUTE);
        if (profile != null && profiles.find(profile).isEmpty()) {
            throw new BindingRejectedException(
                    "the definition requires sandbox profile '" + profile + "', which is not configured");
        }
        return binding.slot(slot).requiredProfile(profile).build();
    }

    private WorkspaceOwner sessionOwner(SessionId session, WorkspaceOwner caller) {
        if (sessionOwners == null) {
            return caller;
        }
        return sessionOwners.ownerOf(session).map(callers::ownerOf).orElseThrow(
                () -> new BindingRejectedException("the owner of session " + session.value() + " is not known"));
    }
}
