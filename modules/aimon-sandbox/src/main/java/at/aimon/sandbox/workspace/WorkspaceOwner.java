package at.aimon.sandbox.workspace;

import java.util.Objects;

/**
 * {@code (tenantId, principalId)} — the owner of a workspace, fixed when the record is created
 * (docs/design/workspace-sandbox.md §5.1), or the caller of an entry point when compared against it (§8.3).
 */
public final class WorkspaceOwner {

    private final TenantId tenant;
    private final String principalId;

    private WorkspaceOwner(TenantId tenant, String principalId) {
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.principalId = Objects.requireNonNull(principalId, "principalId must not be null");
    }

    /**
     * @param tenant
     *            the tenant
     * @param principalId
     *            the principal's id
     * @return the owner
     */
    public static WorkspaceOwner of(TenantId tenant, String principalId) {
        return new WorkspaceOwner(tenant, principalId);
    }

    /** @return the tenant */
    public TenantId tenant() {
        return tenant;
    }

    /** @return the principal's id */
    public String principalId() {
        return principalId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkspaceOwner that)) {
            return false;
        }
        return tenant.equals(that.tenant) && principalId.equals(that.principalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenant, principalId);
    }

    @Override
    public String toString() {
        return tenant + "/" + principalId;
    }
}
