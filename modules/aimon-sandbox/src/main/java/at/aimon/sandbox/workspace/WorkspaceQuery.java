package at.aimon.sandbox.workspace;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An owner's listing of their own workspaces (docs/design/workspace-sandbox-management.md §5.4): optional state,
 * origin and name-prefix filters, and a keyset page ordered by id.
 */
public final class WorkspaceQuery {

    /** The largest page a listing returns. */
    public static final int MAX_LIMIT = 500;

    private final Set<WorkspaceState> states;
    private final WorkspaceOrigin origin;
    private final String namePrefix;
    private final SandboxWorkspaceId after;
    private final int limit;

    private WorkspaceQuery(Builder builder) {
        this.states = builder.states.isEmpty()
                ? Set.copyOf(EnumSet.allOf(WorkspaceState.class))
                : Set.copyOf(builder.states);
        this.origin = builder.origin;
        this.namePrefix = builder.namePrefix;
        this.after = builder.after;
        if (builder.limit < 1 || builder.limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT + ", got " + builder.limit);
        }
        this.limit = builder.limit;
    }

    /** @return a new builder (every state and origin, no name filter, 100 per page) */
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

    /** @return the page starts after this id */
    public Optional<SandboxWorkspaceId> after() {
        return Optional.ofNullable(after);
    }

    /** @return the page size */
    public int limit() {
        return limit;
    }

    /** Builder for {@link WorkspaceQuery}. */
    public static final class Builder {
        private Set<WorkspaceState> states = Set.of();
        private WorkspaceOrigin origin;
        private String namePrefix;
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

        public Builder after(SandboxWorkspaceId after) {
            this.after = after;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public WorkspaceQuery build() {
            return new WorkspaceQuery(this);
        }
    }
}
