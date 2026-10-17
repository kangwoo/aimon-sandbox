package at.aimon.sandbox.workspace;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An operator's listing of every workspace (docs/design/workspace-sandbox-management.md §5.4): the owner-facing
 * filters of {@link WorkspaceQuery} plus tenant and owner, and a keyset page ordered by id.
 */
public final class AdminWorkspaceQuery {

    private final Set<WorkspaceState> states;
    private final WorkspaceOrigin origin;
    private final String namePrefix;
    private final TenantId tenant;
    private final WorkspaceOwner owner;
    private final SandboxWorkspaceId after;
    private final int limit;

    private AdminWorkspaceQuery(Builder builder) {
        this.states = builder.states.isEmpty()
                ? Set.copyOf(EnumSet.allOf(WorkspaceState.class))
                : Set.copyOf(builder.states);
        this.origin = builder.origin;
        this.namePrefix = builder.namePrefix;
        this.tenant = builder.tenant;
        this.owner = builder.owner;
        this.after = builder.after;
        if (builder.limit < 1 || builder.limit > WorkspaceQuery.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be 1.." + WorkspaceQuery.MAX_LIMIT + ", got " + builder.limit);
        }
        this.limit = builder.limit;
    }

    /** @return a new builder (every workspace, 100 per page) */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the states to include */
    public Set<WorkspaceState> states() {
        return states;
    }

    /** @return the origin to include */
    public Optional<WorkspaceOrigin> origin() {
        return Optional.ofNullable(origin);
    }

    /** @return include only workspaces whose name starts with this */
    public Optional<String> namePrefix() {
        return Optional.ofNullable(namePrefix);
    }

    /** @return the owner tenant to include */
    public Optional<TenantId> tenant() {
        return Optional.ofNullable(tenant);
    }

    /** @return the owner to include: tenant and principal */
    public Optional<WorkspaceOwner> owner() {
        return Optional.ofNullable(owner);
    }

    /** @return the page starts after this id */
    public Optional<SandboxWorkspaceId> after() {
        return Optional.ofNullable(after);
    }

    /** @return the page size, at most {@link WorkspaceQuery#MAX_LIMIT} */
    public int limit() {
        return limit;
    }

    /** Builder for {@link AdminWorkspaceQuery}. */
    public static final class Builder {
        private Set<WorkspaceState> states = Set.of();
        private WorkspaceOrigin origin;
        private String namePrefix;
        private TenantId tenant;
        private WorkspaceOwner owner;
        private SandboxWorkspaceId after;
        private int limit = 100;

        private Builder() {
        }

        public Builder states(Set<WorkspaceState> states) {
            this.states = Objects.requireNonNull(states, "states must not be null");
            return this;
        }

        public Builder origin(WorkspaceOrigin origin) {
            this.origin = origin;
            return this;
        }

        public Builder namePrefix(String namePrefix) {
            this.namePrefix = namePrefix;
            return this;
        }

        public Builder tenant(TenantId tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder owner(WorkspaceOwner owner) {
            this.owner = owner;
            return this;
        }

        public Builder after(SandboxWorkspaceId after) {
            this.after = after;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public AdminWorkspaceQuery build() {
            return new AdminWorkspaceQuery(this);
        }
    }
}
