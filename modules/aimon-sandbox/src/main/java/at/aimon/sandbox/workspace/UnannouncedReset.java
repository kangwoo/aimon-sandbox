package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;

/**
 * A reset of a session's sandbox slot that the model may not have heard of yet
 * (docs/design/workspace-sandbox-unannounced-reset.md §5.1): kept on the session's options record from the
 * {@code connect} that reset the slot until a drain delivers a notice that covers it. A hook, a workflow step or a
 * staging call can reset a slot on a path core never drains; the session's next root execution then tells the model
 * again.
 *
 * <p>
 * Immutable. Equal when all three values are: {@link #at()} is part of the identity, so a newer reset of the same slot
 * is a different value and an acknowledgement of the older one leaves it alone.
 */
public final class UnannouncedReset {

    private final SandboxWorkspaceId workspace;
    private final String slot;
    private final Instant at;

    private UnannouncedReset(SandboxWorkspaceId workspace, String slot, Instant at) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
        this.at = Objects.requireNonNull(at, "at must not be null");
    }

    /**
     * @param workspace
     *            the workspace whose slot was reset
     * @param slot
     *            the slot
     * @param at
     *            when the reset was observed
     * @return the reset
     */
    public static UnannouncedReset of(SandboxWorkspaceId workspace, String slot, Instant at) {
        return new UnannouncedReset(workspace, slot, at);
    }

    /** @return the workspace whose slot was reset */
    public SandboxWorkspaceId workspace() {
        return workspace;
    }

    /** @return the slot */
    public String slot() {
        return slot;
    }

    /** @return when the reset was observed, as the store keeps it */
    public Instant at() {
        return at;
    }

    /**
     * @param workspace
     *            a workspace
     * @param slot
     *            a slot
     * @return whether this reset is of that slot
     */
    public boolean isOf(SandboxWorkspaceId workspace, String slot) {
        return this.workspace.equals(workspace) && this.slot.equals(slot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UnannouncedReset that)) {
            return false;
        }
        return workspace.equals(that.workspace) && slot.equals(that.slot) && at.equals(that.at);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspace, slot, at);
    }

    @Override
    public String toString() {
        return "UnannouncedReset{" + workspace + "/" + slot + " at " + at + '}';
    }
}
