package at.aimon.sandbox.workspace;

import java.io.Serial;

import at.aimon.core.agent.session.SessionId;

/**
 * A CAS lost: the record's version was not the expected one, or the record is gone. Every writer re-reads, re-checks
 * its condition and retries (docs/design/workspace-sandbox.md §5.3) — no write is dropped for a conflict.
 */
public class StaleVersionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -4312870183745223344L;

    /**
     * @param id
     *            the workspace
     * @param expected
     *            the version the writer read
     */
    public StaleVersionException(SandboxWorkspaceId id, long expected) {
        super("workspace " + id + " is no longer at version " + expected);
    }

    /**
     * @param session
     *            the session whose options record moved on ({@link SessionOptionsStore})
     * @param expected
     *            the version the writer read
     */
    public StaleVersionException(SessionId session, long expected) {
        super("the sandbox options of session " + session.value() + " are no longer at version " + expected);
    }
}
