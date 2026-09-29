package at.aimon.sandbox.binding;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;

/**
 * What a {@link SandboxBindingPolicy} sees of core's {@link EnvironmentRequest} (docs/design/workspace-sandbox.md
 * §8.1): the agent, the runtime, the session or execution id, the invoking session, the principal and the
 * definition's flattened attributes.
 */
public final class BindingContext {

    private final Agent agent;
    private final AgentRuntimeId agentRuntimeId;
    private final SessionId sessionId;
    private final ExecutionId executionId;
    private final SessionId invokingSessionId;
    private final Principal principal;
    private final Map<String, String> attributes;

    private BindingContext(EnvironmentRequest request) {
        this.agent = request.agent().orElse(null);
        this.agentRuntimeId = request.agentRuntimeId();
        this.sessionId = request.sessionId().orElse(null);
        this.executionId = request.executionId().orElse(null);
        this.invokingSessionId = request.invokingSessionId().orElse(null);
        this.principal = request.principal().orElse(null);
        this.attributes = Map.copyOf(request.definitionAttributes());
    }

    /**
     * @param request
     *            core's request
     * @return its binding view
     */
    public static BindingContext from(EnvironmentRequest request) {
        return new BindingContext(Objects.requireNonNull(request, "request must not be null"));
    }

    /** @return the agent */
    public Optional<Agent> agent() {
        return Optional.ofNullable(agent);
    }

    /** @return the runtime */
    public AgentRuntimeId agentRuntimeId() {
        return agentRuntimeId;
    }

    /** @return the session of a main turn */
    public Optional<SessionId> sessionId() {
        return Optional.ofNullable(sessionId);
    }

    /** @return the execution of a routine or fork */
    public Optional<ExecutionId> executionId() {
        return Optional.ofNullable(executionId);
    }

    /** @return the session a fork was invoked from */
    public Optional<SessionId> invokingSessionId() {
        return Optional.ofNullable(invokingSessionId);
    }

    /** @return the principal */
    public Optional<Principal> principal() {
        return Optional.ofNullable(principal);
    }

    /** @return the definition's attributes ({@code sandbox.slot}, {@code sandbox.profile}) */
    public Map<String, String> attributes() {
        return attributes;
    }
}
