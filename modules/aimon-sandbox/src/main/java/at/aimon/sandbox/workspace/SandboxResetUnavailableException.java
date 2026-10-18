package at.aimon.sandbox.workspace;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * A {@code connect} that failed after it had gathered notices — typically a reset (an idle reopen, a lost or terminated
 * sandbox) followed by a failed re-provisioning (§15). Its message is the reason with the notices appended, as every
 * caller outside the environment has always read it; the execution environment takes the parts apart instead, queues
 * the notices for core's drain and reports the bare {@link #reason()}
 * (docs/design/workspace-sandbox-unannounced-reset.md §6.3).
 *
 * <p>
 * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
 * release.
 */
public final class SandboxResetUnavailableException extends SandboxUnavailableException {

    @Serial
    private static final long serialVersionUID = 4127755604918436123L;

    private final String reason;
    private final List<String> notices;
    private final boolean reset;
    private final SandboxWorkspaceId workspaceId;
    private final String slot;

    SandboxResetUnavailableException(String reason, List<String> notices, boolean reset, SandboxWorkspaceId workspaceId,
            String slot, Throwable cause) {
        super(reason + " (also: " + String.join("; ", notices) + ")", cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.notices = List.copyOf(notices);
        this.reset = reset;
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
    }

    /** @return the model-facing reason, without the notices */
    public String reason() {
        return reason;
    }

    /** @return the notices the failed call gathered */
    public List<String> notices() {
        return notices;
    }

    /** @return whether the notices say that the slot's files are gone */
    public boolean reset() {
        return reset;
    }

    /** @return the workspace the call connected */
    public SandboxWorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the slot the call connected */
    public String slot() {
        return slot;
    }
}
