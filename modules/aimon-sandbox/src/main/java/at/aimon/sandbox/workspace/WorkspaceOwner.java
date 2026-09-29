package at.aimon.sandbox.workspace;

import java.util.Objects;

import at.aimon.core.base.Principal;

/**
 * {@code (tenantId, principal)} — the owner of a workspace, fixed when the record is created
 * (docs/design/workspace-sandbox.md §5.1), or the caller of an entry point when compared against it (§8.3).
 *
 * <p>
 * The principal is recorded as {@code TYPE:id} (e.g. {@code USER:alice}), because core's identity of a principal is
 * type + id ({@link Principal#equals(Object)}): a USER {@code eng} and a GROUP {@code eng} are different owners. An
 * execution without a principal (only when {@code require-principal} is off) is {@link #ANONYMOUS}, which carries no
 * type and so never equals a principal's form, not even a USER named {@code anonymous}.
 */
public final class WorkspaceOwner {

    /** The recorded form of an execution without a principal. */
    public static final String ANONYMOUS = "anonymous";

    private final TenantId tenant;
    private final String principal;

    private WorkspaceOwner(TenantId tenant, String principal) {
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.principal = Objects.requireNonNull(principal, "principal must not be null");
    }

    /**
     * @param tenant
     *            the tenant
     * @param principal
     *            the principal
     * @return the owner, recording the principal as {@code TYPE:id}
     */
    public static WorkspaceOwner of(TenantId tenant, Principal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        return new WorkspaceOwner(tenant, principal.getType().name() + ":" + principal.getId());
    }

    /**
     * @param tenant
     *            the tenant
     * @return the owner an execution without a principal acts as
     */
    public static WorkspaceOwner anonymous(TenantId tenant) {
        return new WorkspaceOwner(tenant, ANONYMOUS);
    }

    /**
     * Rebuilds an owner from its recorded form, e.g. when a store reads it back. A bare id is refused rather than
     * taken as some principal: build owners from a {@link Principal} with {@link #of(TenantId, Principal)}.
     *
     * @param tenant
     *            the tenant
     * @param principal
     *            the principal as {@link #principal()} returned it — {@code TYPE:id}, or {@link #ANONYMOUS}
     * @return the owner
     * @throws IllegalArgumentException
     *             when {@code principal} is neither form, or its id is blank (core refuses a blank id)
     */
    public static WorkspaceOwner parse(TenantId tenant, String principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.equals(ANONYMOUS)) {
            final int colon = principal.indexOf(':');
            if (colon <= 0 || principal.substring(colon + 1).isBlank() || !isType(principal.substring(0, colon))) {
                throw new IllegalArgumentException(
                        "a recorded principal is TYPE:id or '" + ANONYMOUS + "', not '" + principal + "'");
            }
        }
        return new WorkspaceOwner(tenant, principal);
    }

    private static boolean isType(String name) {
        for (Principal.Type type : Principal.Type.values()) {
            if (type.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** @return the tenant */
    public TenantId tenant() {
        return tenant;
    }

    /** @return the principal as {@code TYPE:id}, or {@link #ANONYMOUS} */
    public String principal() {
        return principal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkspaceOwner that)) {
            return false;
        }
        return tenant.equals(that.tenant) && principal.equals(that.principal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenant, principal);
    }

    @Override
    public String toString() {
        return tenant + "/" + principal;
    }
}
