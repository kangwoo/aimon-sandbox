package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.sandbox.SandboxSettings;

/**
 * What an owner or operator sees of a workspace (docs/design/workspace-sandbox-management.md §5.4). It carries no
 * provider sandbox id, credentials, seed parameters or endpoints; the {@link #name()} is for people, never for the
 * model.
 */
public final class WorkspaceView {

    private final SandboxWorkspaceId id;
    private final String name;
    private final WorkspaceOwner owner;
    private final WorkspaceOrigin origin;
    private final SandboxSettings.WorkspaceAccess access;
    private final WorkspaceState state;
    private final CloseCause closeCause;
    private final Instant createdAt;
    private final Instant lastActivityAt;
    private final List<SlotView> slots;

    private WorkspaceView(SandboxWorkspace workspace, SandboxSettings.WorkspaceAccess deploymentAccess) {
        this.id = workspace.id();
        this.name = workspace.name().orElse(null);
        this.owner = workspace.owner();
        this.origin = workspace.origin();
        this.access = workspace.access().orElse(deploymentAccess);
        this.state = workspace.state();
        this.closeCause = workspace.closeCause().orElse(null);
        this.createdAt = workspace.createdAt();
        this.lastActivityAt = workspace.lastActivityAt();
        this.slots = workspace.slots().values().stream().map(SlotView::of).toList();
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
     * release.
     *
     * @param workspace
     *            the record
     * @param deploymentAccess
     *            {@code workspace-access}, the access of a record that has none of its own
     * @return its view
     */
    public static WorkspaceView of(SandboxWorkspace workspace, SandboxSettings.WorkspaceAccess deploymentAccess) {
        return new WorkspaceView(Objects.requireNonNull(workspace, "workspace must not be null"),
                Objects.requireNonNull(deploymentAccess, "deploymentAccess must not be null"));
    }

    /** @return the id */
    public SandboxWorkspaceId id() {
        return id;
    }

    /** @return the display name of a managed workspace */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /** @return the owner */
    public WorkspaceOwner owner() {
        return owner;
    }

    /** @return how the record came to exist */
    public WorkspaceOrigin origin() {
        return origin;
    }

    /** @return the effective access mode: the record's own, else the deployment's */
    public SandboxSettings.WorkspaceAccess access() {
        return access;
    }

    /** @return the lifecycle state */
    public WorkspaceState state() {
        return state;
    }

    /** @return why it is CLOSING or CLOSED */
    public Optional<CloseCause> closeCause() {
        return Optional.ofNullable(closeCause);
    }

    /** @return when the record was created */
    public Instant createdAt() {
        return createdAt;
    }

    /** @return the last recorded activity on any slot */
    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    /** @return the slots */
    public List<SlotView> slots() {
        return slots;
    }

    /**
     * @param name
     *            the slot name
     * @return the slot
     */
    public Optional<SlotView> slot(String name) {
        return slots.stream().filter(slot -> slot.name().equals(name)).findFirst();
    }

    @Override
    public String toString() {
        return "WorkspaceView{" + id + ", owner=" + owner + ", origin=" + origin + ", access=" + access + ", state="
                + state + ", slots=" + slots + '}';
    }
}
