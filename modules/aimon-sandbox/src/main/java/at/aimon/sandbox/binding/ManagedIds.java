package at.aimon.sandbox.binding;

import java.util.UUID;
import java.util.regex.Pattern;

import at.aimon.sandbox.workspace.SandboxWorkspaceId;

/**
 * The one home of the managed workspace id format, {@code wsm:{UUID}} (docs/design/workspace-sandbox-management.md
 * §3.1, §6.3). A managed id is generated, never chosen, and never reused, so whatever names one — session options, a
 * definition's {@code sandbox.workspace} — can never be redirected to another workspace. A derived {@code ws:} id is
 * reused after its tombstone expires, so nothing may name one.
 */
public final class ManagedIds {

    /** The prefix of every managed id. */
    public static final String PREFIX = "wsm:";

    private static final Pattern FORMAT = Pattern
            .compile("^wsm:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private ManagedIds() {
    }

    /** @return a new managed id */
    public static SandboxWorkspaceId generate() {
        return SandboxWorkspaceId.of(PREFIX + UUID.randomUUID());
    }

    /**
     * @param value
     *            a candidate id
     * @return whether it is a managed id
     */
    public static boolean isManaged(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    /**
     * @param value
     *            the id a session option or definition names
     * @param source
     *            what named it, for the message ("the definition's sandbox.workspace")
     * @return the id
     * @throws BindingRejectedException
     *             when it is not a managed id
     */
    public static SandboxWorkspaceId require(String value, String source) {
        if (!isManaged(value)) {
            throw new BindingRejectedException(source + " '" + value + "' is not a valid managed sandbox workspace id ("
                    + PREFIX + "{uuid}); only a workspace created through SandboxWorkspaces can be named");
        }
        return SandboxWorkspaceId.of(value);
    }
}
