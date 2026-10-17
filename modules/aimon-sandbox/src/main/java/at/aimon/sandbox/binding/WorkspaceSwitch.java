package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;

/**
 * A session's root binding landed in another workspace than the one it last connected to
 * (docs/design/workspace-sandbox-management.md §6.4): attached, re-attached, cleared, its agent default changed, or
 * a request attribute redirected this execution (docs/design/workspace-sandbox-core-033.md §6.4). The environment
 * queues the notice at its first successful connect, core delivers it with the next tool result of any kind
 * ({@code drainNotices()}), and the drain that returns it has {@link #to()} recorded as the session's
 * {@code lastWorkspaceId}. A {@linkplain #isSilent() silent} switch is only recorded: nothing moved.
 */
public final class WorkspaceSwitch {

    private final SessionId session;
    private final SandboxWorkspaceId from;
    private final SandboxWorkspaceId to;
    private final OptionalLong optionsVersion;
    private final boolean silent;

    private WorkspaceSwitch(SessionId session, SandboxWorkspaceId from, SandboxWorkspaceId to,
            OptionalLong optionsVersion, boolean silent) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.from = from;
        this.to = Objects.requireNonNull(to, "to must not be null");
        this.optionsVersion = Objects.requireNonNull(optionsVersion, "optionsVersion must not be null");
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
     *            the version of the options record the policy read, the record write being a CAS on it; empty when
     *            the session has no record yet, which the write then creates
     * @return a switch the model is told of
     */
    public static WorkspaceSwitch announced(SessionId session, SandboxWorkspaceId from, SandboxWorkspaceId to,
            OptionalLong optionsVersion) {
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
    public static WorkspaceSwitch silent(SessionId session, SandboxWorkspaceId to, OptionalLong optionsVersion) {
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

    /** @return the version of the options record the policy read; empty when there was none */
    public OptionalLong optionsVersion() {
        return optionsVersion;
    }

    /** @return whether the switch is recorded without a notice */
    public boolean isSilent() {
        return silent;
    }

    /** @return the model-facing notice: the id only, never the workspace's name */
    public String notice() {
        return "this session now runs in sandbox workspace " + to.value()
                + (from == null
                        ? "; files and shell state from any earlier workspace of this session are not here"
                        : "; files and shell state from the previous workspace are not here");
    }

    @Override
    public String toString() {
        return "WorkspaceSwitch{" + session.value() + ": " + from + " -> " + to + (silent ? ", silent" : "") + '}';
    }
}
