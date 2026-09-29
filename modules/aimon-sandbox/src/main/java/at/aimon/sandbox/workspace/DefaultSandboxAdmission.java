package at.aimon.sandbox.workspace;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import at.aimon.sandbox.profile.SandboxProfile;

/**
 * The default admission: counts the tenant's RUNNING and PROVISIONING slots with a store scan and refuses once
 * {@code max-running-per-tenant} is reached (docs/design/workspace-sandbox.md §12.2). The count is not CAS-protected,
 * so concurrent starts can overshoot a little — a best-effort cap; an application that needs an exact one implements
 * {@link SandboxAdmission} on its own store.
 */
public final class DefaultSandboxAdmission implements SandboxAdmission {

    private final SandboxWorkspaceStore store;
    private final int maxRunningPerTenant;

    /**
     * @param store
     *            the workspace store
     * @param maxRunningPerTenant
     *            the cap (at least 1)
     */
    public DefaultSandboxAdmission(SandboxWorkspaceStore store, int maxRunningPerTenant) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        if (maxRunningPerTenant < 1) {
            throw new IllegalArgumentException("maxRunningPerTenant must be >= 1, got " + maxRunningPerTenant);
        }
        this.maxRunningPerTenant = maxRunningPerTenant;
    }

    @Override
    public AdmissionDecision admit(WorkspaceOwner owner, SandboxProfile profile) {
        int running = 0;
        WorkspaceScan scan = WorkspaceScan.builder().states(Set.of(WorkspaceState.OPEN)).tenant(owner.tenant()).build();
        while (true) {
            final List<SandboxWorkspace> page = store.scan(scan);
            for (SandboxWorkspace workspace : page) {
                running += (int) workspace.slots().values().stream()
                        .filter(slot -> slot.state() == SlotState.RUNNING || slot.state() == SlotState.PROVISIONING)
                        .count();
            }
            if (page.size() < scan.limit()) {
                break;
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }
        if (running >= maxRunningPerTenant) {
            return AdmissionDecision.rejected(
                    "tenant '" + owner.tenant() + "' already runs " + running + " sandboxes (max-running-per-tenant = "
                            + maxRunningPerTenant + "); stop or finish other " + "sessions' work first");
        }
        return AdmissionDecision.admitted();
    }
}
