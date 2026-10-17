package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;

/**
 * A session's root binding landed in another workspace than the one it last connected to
 * (docs/design/workspace-sandbox-management.md §6.4): attached, re-attached, cleared, or its agent default changed.
 * The environment tells the model once, after its first successful connect, and then records {@link #to()} as the
 * session's {@code lastWorkspaceId} — once the notice has reached a command result, so a turn of file tools only
 * leaves it for the next {@code Bash}. A {@linkplain #isSilent() silent} switch is only recorded: nothing moved.
 */
public final class WorkspaceSwitch {

    private final SessionId session;
    private final SandboxWorkspaceId from;
    private final SandboxWorkspaceId to;
    private final long optionsVersion;
    private final boolean silent;

    private WorkspaceSwitch(SessionId session, SandboxWorkspaceId from, SandboxWorkspaceId to, long optionsVersion,
            boolean silent) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.from = from;
        this.to = Objects.requireNonNull(to, "to must not be null");
        this.optionsVersion = optionsVersion;
        this.silent = silent;
    }

    /**
     * @param session
     *            the session
     * @param from
     *            where it last connected, or {@code null} when it never did
     * @param to
     *            where it is bound now
     * @param optionsVersion
     *            the version of the options record the policy read; the record write is a CAS on it
     * @return a switch the model is told of
     */
    public static WorkspaceSwitch announced(SessionId session, SandboxWorkspaceId from, SandboxWorkspaceId to,
            long optionsVersion) {
        return new WorkspaceSwitch(session, from, to, optionsVersion, false);
    }

    /**
     * @param session
     *            the session
     * @param to
     *            where it is bound now — the derived workspace it would have used anyway
     * @param optionsVersion
     *            the version of the options record the policy read
     * @return a switch that is only recorded
     */
    public static WorkspaceSwitch silent(SessionId session, SandboxWorkspaceId to, long optionsVersion) {
        return new WorkspaceSwitch(session, null, to, optionsVersion, true);
    }

    /** @return the session */
    public SessionId session() {
        return session;
    }

    /** @return where the session last connected */
    public Optional<SandboxWorkspaceId> from() {
        return Optional.ofNullable(from);
    }

    /** @return where it is bound now */
    public SandboxWorkspaceId to() {
        return to;
    }

    /** @return the version of the options record the policy read */
    public long optionsVersion() {
        return optionsVersion;
    }

    /** @return whether the switch is recorded without a notice */
    public boolean isSilent() {
        return silent;
    }

    /** @return the model-facing notice: the id only, never the workspace's name */
    public String notice() {
        return "this session now runs in sandbox workspace " + to.value()
                + "; files and shell state from the previous workspace are not here";
    }

    @Override
    public String toString() {
        return "WorkspaceSwitch{" + session.value() + ": " + from + " -> " + to + (silent ? ", silent" : "") + '}';
    }
}
