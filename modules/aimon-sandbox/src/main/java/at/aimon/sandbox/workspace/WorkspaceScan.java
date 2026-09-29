package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link SandboxWorkspaceStore#scan} query: state, tenant and time filters, and a keyset page (records ordered by
 * id, starting after {@link #afterId()}).
 */
public final class WorkspaceScan {

    private final Set<WorkspaceState> states;
    private final TenantId tenant;
    private final Instant lastActivityBefore;
    private final Instant stateSinceBefore;
    private final SandboxWorkspaceId afterId;
    private final int limit;

    private WorkspaceScan(Builder builder) {
        this.states = builder.states.isEmpty()
                ? Set.copyOf(EnumSet.allOf(WorkspaceState.class))
                : Set.copyOf(builder.states);
        this.tenant = builder.tenant;
        this.lastActivityBefore = builder.lastActivityBefore;
        this.stateSinceBefore = builder.stateSinceBefore;
        this.afterId = builder.afterId;
        if (builder.limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + builder.limit);
        }
        this.limit = builder.limit;
    }

    /** @return a new builder (every state, no filter, 100 per page) */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param record
     *            a record
     * @return whether it passes the filters (not the page bounds)
     */
    public boolean matches(SandboxWorkspace record) {
        if (!states.contains(record.state())) {
            return false;
        }
        if (tenant != null && !tenant.equals(record.owner().tenant())) {
            return false;
        }
        if (lastActivityBefore != null && !record.lastActivityAt().isBefore(lastActivityBefore)) {
            return false;
        }
        return stateSinceBefore == null
                || record.stateSince().map(since -> since.isBefore(stateSinceBefore)).orElse(false);
    }

    /** @return the states to include */
    public Set<WorkspaceState> states() {
        return states;
    }

    /** @return the owner tenant to include */
    public Optional<TenantId> tenant() {
        return Optional.ofNullable(tenant);
    }

    /** @return include only records whose {@code lastActivityAt} is before this */
    public Optional<Instant> lastActivityBefore() {
        return Optional.ofNullable(lastActivityBefore);
    }

    /** @return include only records whose {@code stateSince} is before this */
    public Optional<Instant> stateSinceBefore() {
        return Optional.ofNullable(stateSinceBefore);
    }

    /** @return the page starts after this id */
    public Optional<SandboxWorkspaceId> afterId() {
        return Optional.ofNullable(afterId);
    }

    /** @return the page size */
    public int limit() {
        return limit;
    }

    /**
     * @param lastId
     *            the last id of the page just read
     * @return the query for the next page
     */
    public WorkspaceScan next(SandboxWorkspaceId lastId) {
        return new Builder().states(states).tenant(tenant).lastActivityBefore(lastActivityBefore)
                .stateSinceBefore(stateSinceBefore).afterId(lastId).limit(limit).build();
    }

    /** Builder for {@link WorkspaceScan}. */
    public static final class Builder {
        private Set<WorkspaceState> states = Set.of();
        private TenantId tenant;
        private Instant lastActivityBefore;
        private Instant stateSinceBefore;
        private SandboxWorkspaceId afterId;
        private int limit = 100;

        private Builder() {
        }

        public Builder states(Set<WorkspaceState> states) {
            this.states = Objects.requireNonNull(states, "states must not be null");
            return this;
        }

        public Builder tenant(TenantId tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder lastActivityBefore(Instant lastActivityBefore) {
            this.lastActivityBefore = lastActivityBefore;
            return this;
        }

        public Builder stateSinceBefore(Instant stateSinceBefore) {
            this.stateSinceBefore = stateSinceBefore;
            return this;
        }

        public Builder afterId(SandboxWorkspaceId afterId) {
            this.afterId = afterId;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public WorkspaceScan build() {
            return new WorkspaceScan(this);
        }
    }
}
