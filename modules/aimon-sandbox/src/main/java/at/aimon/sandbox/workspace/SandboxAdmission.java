package at.aimon.sandbox.workspace;

import at.aimon.sandbox.profile.SandboxProfile;

/**
 * The per-tenant limit (docs/design/workspace-sandbox.md §12.2). Workspaces appear implicitly per session, so a
 * workspace quota cannot bound a tenant; this can. {@code connect} asks before every branch that adds provider
 * resources — new provisioning and a retry of a failed one in implementation step 3.
 */
@FunctionalInterface
public interface SandboxAdmission {

    /** Admits everything. Only for an explicit {@code max-running-per-tenant: unlimited}. */
    SandboxAdmission UNLIMITED = (owner, profile) -> AdmissionDecision.admitted();

    /**
     * @param owner
     *            the workspace's owner (its tenant is what is limited)
     * @param profile
     *            the profile about to be started
     * @return whether it may start
     */
    AdmissionDecision admit(WorkspaceOwner owner, SandboxProfile profile);
}
