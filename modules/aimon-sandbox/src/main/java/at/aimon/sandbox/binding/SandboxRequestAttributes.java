package at.aimon.sandbox.binding;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.environment.EnvironmentRequestAttributes;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;

/**
 * The execution attributes a host sets to place one execution (kangwoo/aimon-core#252,
 * docs/design/workspace-sandbox-core-033.md §5.1): on a main turn through {@code SubmitOptions.executionAttributes},
 * on a routine through a {@code RoutineExecutionAttributes} resolver. Core forwards them, {@code environment.}
 * stripped, to {@link BindingContext#requestAttributes()}; forks never carry them.
 *
 * <ul>
 * <li>{@link #WORKSPACE} binds this execution to a managed workspace, ahead of the session's options and the agent
 * default. It is {@code mustExist}, and the executing principal must be allowed to use the workspace — the same use
 * check as every other row. It writes no session option.</li>
 * <li>{@link #UNATTACHED} joins the unattached rule, which it can only tighten: {@code reject} refuses this execution
 * when nothing attaches it.</li>
 * </ul>
 *
 * Any other {@code environment.sandbox.*} key is refused: {@code sandbox.slot} and {@code sandbox.profile} are the
 * definition author's choice.
 *
 * <p>
 * <b>Trust.</b> Request attributes are placement input supplied by the host. Never fill them from model output — a
 * tool argument, a task the model created. The rights check above applies whatever the host passes.
 *
 * <pre>{@code
 * live.submit(prompt, SubmitOptions.builder()
 *         .executionAttributes(SandboxRequestAttributes.workspace(workspaceId)).build());
 * }</pre>
 */
public final class SandboxRequestAttributes {

    /** The execution attribute naming this execution's managed workspace: {@code environment.sandbox.workspace}. */
    public static final String WORKSPACE = EnvironmentRequestAttributes.PREFIX + SlotChoice.WORKSPACE_ATTRIBUTE;

    /** The execution attribute tightening this execution's unattached rule: {@code environment.sandbox.unattached}. */
    public static final String UNATTACHED = EnvironmentRequestAttributes.PREFIX + SlotChoice.UNATTACHED_ATTRIBUTE;

    private SandboxRequestAttributes() {
    }

    /**
     * @param workspace
     *            a managed ({@code wsm:}) workspace
     * @return the execution attributes that bind one execution to it
     */
    public static Map<String, Object> workspace(SandboxWorkspaceId workspace) {
        return Map.of(WORKSPACE, Objects.requireNonNull(workspace, "workspace must not be null").value());
    }

    /**
     * @param rule
     *            the rule
     * @return the execution attributes that tighten one execution's unattached rule to it
     */
    public static Map<String, Object> unattached(UnattachedRule rule) {
        return Map.of(UNATTACHED, Objects.requireNonNull(rule, "rule must not be null").name().toLowerCase(Locale.ROOT)
                .replace('_', '-'));
    }
}
