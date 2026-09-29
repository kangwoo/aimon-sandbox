package at.aimon.sandbox.binding;

import at.aimon.core.base.Principal;
import at.aimon.sandbox.workspace.TenantId;

/**
 * Maps a principal to its tenant (docs/design/workspace-sandbox.md §8.3) — core's {@link Principal} has no tenant
 * field. Required when {@code require-principal} is on; the single-tenant default puts everyone in
 * {@link TenantId#DEFAULT}.
 */
@FunctionalInterface
public interface SandboxTenantResolver {

    /** Every principal in {@link TenantId#DEFAULT}. */
    SandboxTenantResolver SINGLE_TENANT = principal -> TenantId.DEFAULT;

    /**
     * @param principal
     *            the principal
     * @return its tenant, or {@code null} when it has none (the binding is then rejected)
     */
    TenantId resolve(Principal principal);
}
