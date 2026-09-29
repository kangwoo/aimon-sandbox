package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * Turns a principal into the {@link WorkspaceOwner} it acts as, applying the principal gate
 * (docs/design/workspace-sandbox.md §8.3). Shared by the default binding policy and the manager's
 * {@code close}/{@code reopen}, so every entry point applies the same gate.
 *
 * <p>
 * With {@code require-principal} only USER and GROUP principals pass, and SYSTEM/SERVICE principals listed in
 * {@code allowed-system-principals}; an absent principal is rejected. Without it an absent principal acts as
 * {@link WorkspaceOwner#anonymous anonymous} in {@link TenantId#DEFAULT}.
 */
public final class CallerResolver {

    private final SandboxSettings settings;
    private final SandboxTenantResolver tenantResolver;

    /**
     * @param settings
     *            the settings
     * @param tenantResolver
     *            the tenant resolver
     */
    public CallerResolver(SandboxSettings settings, SandboxTenantResolver tenantResolver) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.tenantResolver = Objects.requireNonNull(tenantResolver, "tenantResolver must not be null");
    }

    /**
     * @param principal
     *            the executing principal
     * @return who it acts as
     * @throws BindingRejectedException
     *             when the gate refuses it
     */
    public WorkspaceOwner callerOf(Optional<Principal> principal) {
        if (principal.isEmpty()) {
            if (settings.requirePrincipal()) {
                throw new BindingRejectedException("a sandbox needs a USER or GROUP principal, and this execution has "
                        + "none (require-principal is on)");
            }
            return WorkspaceOwner.anonymous(TenantId.DEFAULT);
        }
        final Principal p = principal.get();
        if (settings.requirePrincipal() && !p.isUser() && !p.isGroup()
                && !settings.allowedSystemPrincipals().contains(p.getId())) {
            throw new BindingRejectedException("a sandbox needs a USER or GROUP principal, not " + p.getType() + " '"
                    + p.getId() + "' (require-principal is on; list it in allowed-system-principals to " + "allow it)");
        }
        return ownerOf(p);
    }

    /**
     * The owner a principal is recorded as, without the gate (a session's owner is not the executing principal).
     *
     * @param principal
     *            the principal
     * @return {@code (tenant, TYPE:id)}
     * @throws BindingRejectedException
     *             when the tenant resolver has no tenant for it
     */
    public WorkspaceOwner ownerOf(Principal principal) {
        final TenantId tenant = tenantResolver.resolve(principal);
        if (tenant == null) {
            throw new BindingRejectedException("no tenant is known for principal '" + principal.getId() + "'");
        }
        return WorkspaceOwner.of(tenant, principal);
    }
}
