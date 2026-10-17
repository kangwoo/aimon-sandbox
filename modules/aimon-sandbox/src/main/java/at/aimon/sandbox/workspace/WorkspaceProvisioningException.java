package at.aimon.sandbox.workspace;

import java.io.Serial;
import java.util.Objects;

/**
 * A managed workspace was created but its first provisioning did not finish
 * (docs/design/workspace-sandbox-management.md
 * §6.1): the record is kept — its slot FAILED with the recorded failure, or absent when admission refused — and the
 * caller retries with {@code provision(id)} or purges it.
 */
public class WorkspaceProvisioningException extends SandboxUnavailableException {

    @Serial
    private static final long serialVersionUID = 2871140986651037712L;

    private final SandboxWorkspaceId workspaceId;

    /**
     * @param workspaceId
     *            the workspace that was created
     * @param cause
     *            why provisioning failed
     */
    public WorkspaceProvisioningException(SandboxWorkspaceId workspaceId, SandboxUnavailableException cause) {
        super("sandbox workspace " + workspaceId + " was created, but provisioning it failed: " + cause.getMessage(),
                cause);
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    }

    /** @return the id of the workspace that was created */
    public SandboxWorkspaceId workspaceId() {
        return workspaceId;
    }
}
