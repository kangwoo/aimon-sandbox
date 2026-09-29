package at.aimon.sandbox.binding;

import at.aimon.core.environment.ForkDefinition;

/**
 * Decides which workspace and slot an execution uses (docs/design/workspace-sandbox.md §8.1). The only maker of
 * {@link SandboxBinding}s; an application injects its own to map, say, a ticket's sessions to one workspace.
 */
public interface SandboxBindingPolicy {

    /**
     * Binds a root request (main turn or routine). The returned {@link SandboxBinding#caller()} is not trusted: the
     * environment provider replaces it with the request's principal through the assembly's principal gate, so the
     * manager's owner check always sees who is actually executing (§8.3). Any non-null caller satisfies the builder.
     *
     * @param context
     *            the request
     * @return the binding
     * @throws BindingRejectedException
     *             when no binding may be made
     */
    SandboxBinding bind(BindingContext context);

    /**
     * Chooses a fork's slot and profile. Workspace, owner and root come from the parent regardless.
     *
     * @param parent
     *            the parent's binding
     * @param fork
     *            the fork's definition
     * @return the choice
     */
    default SlotChoice forkSlot(SandboxBinding parent, ForkDefinition fork) {
        return SlotChoice.fromAttributes(fork.attributes(), parent);
    }
}
