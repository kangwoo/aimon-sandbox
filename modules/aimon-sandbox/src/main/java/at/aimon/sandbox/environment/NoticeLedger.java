package at.aimon.sandbox.environment;

import java.util.Optional;
import java.util.OptionalLong;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.UnannouncedReset;

/**
 * What {@link PendingNotices} writes to the session's options record: the manager in production, a fake in tests
 * (docs/design/workspace-sandbox-unannounced-reset.md §3.3). Neither method throws.
 */
interface NoticeLedger {

    /** Writes nothing: for an environment without a session. */
    NoticeLedger NONE = new NoticeLedger() {
        @Override
        public Optional<SessionSandboxOptions> markResetUnannounced(SessionId session, SandboxWorkspaceId workspace,
                String slot) {
            return Optional.empty();
        }

        @Override
        public void acknowledge(SessionId session, OptionalLong expectedVersion,
                Optional<SandboxWorkspaceId> lastWorkspace, Optional<UnannouncedReset> delivered) {
        }
    };

    /**
     * Keeps a reset on the session's record, synchronously.
     *
     * @param session
     *            the session
     * @param workspace
     *            the workspace whose slot was reset
     * @param slot
     *            the slot
     * @return the stored record, or empty when nothing was stored
     */
    Optional<SessionSandboxOptions> markResetUnannounced(SessionId session, SandboxWorkspaceId workspace, String slot);

    /**
     * Records what a drain delivered; must not block on remote I/O.
     *
     * @param session
     *            the session
     * @param expectedVersion
     *            the record version the environment knows
     * @param lastWorkspace
     *            the switch's target, when one was delivered or recorded silently
     * @param delivered
     *            the unannounced reset the drained notices covered
     */
    void acknowledge(SessionId session, OptionalLong expectedVersion, Optional<SandboxWorkspaceId> lastWorkspace,
            Optional<UnannouncedReset> delivered);
}
