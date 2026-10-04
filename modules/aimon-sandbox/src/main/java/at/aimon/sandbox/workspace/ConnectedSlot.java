package at.aimon.sandbox.workspace;

import java.util.List;
import java.util.Objects;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxConnection;

/**
 * What {@link SandboxWorkspaceManager#connect} hands back: the record as read, the RUNNING and seeded slot, a
 * connection to its sandbox, the notices this call produced (reset, recreated, provisioning time) and the slot's
 * {@link SlotActivity}.
 * <p>
 * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
 * any release.
 */
public final class ConnectedSlot {

    private final SandboxWorkspace workspace;
    private final SandboxSlot slot;
    private final SandboxProfile profile;
    private final SandboxConnection connection;
    private final List<String> notices;
    private final SlotActivity activity;

    ConnectedSlot(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile, SandboxConnection connection,
            List<String> notices, SlotActivity activity) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.notices = List.copyOf(notices);
        this.activity = Objects.requireNonNull(activity, "activity must not be null");
    }

    /** @return the record as read by this call */
    public SandboxWorkspace workspace() {
        return workspace;
    }

    /** @return the slot, RUNNING and seeded */
    public SandboxSlot slot() {
        return slot;
    }

    /** @return the slot's profile */
    public SandboxProfile profile() {
        return profile;
    }

    /** @return the slot's sandbox */
    public ProviderSandboxRef ref() {
        return slot.providerRef().orElseThrow();
    }

    /** @return a connection to the slot's sandbox */
    public SandboxConnection connection() {
        return connection;
    }

    /** @return notices for the model produced by this call */
    public List<String> notices() {
        return notices;
    }

    /** @return activity recording bound to this generation */
    public SlotActivity activity() {
        return activity;
    }
}
