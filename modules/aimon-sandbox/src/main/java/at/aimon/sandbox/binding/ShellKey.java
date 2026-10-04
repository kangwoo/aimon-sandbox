package at.aimon.sandbox.binding;

import java.util.Objects;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.provider.SandboxLabels;

/**
 * Which persistent shell state a command continues (docs/design/workspace-sandbox.md §3.1, §9): {@code session:{id}}
 * for a main turn — the cwd survives turns — and {@code exec:{id}} for routines and forks, which must not disturb
 * their parent's shell.
 */
public final class ShellKey {

    private final String value;

    private ShellKey(String value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    /**
     * @param sessionId
     *            the session
     * @return {@code session:{sessionId}}
     */
    public static ShellKey session(SessionId sessionId) {
        return new ShellKey("session:" + sessionId.value());
    }

    /**
     * @param executionId
     *            the execution
     * @return {@code exec:{executionId}}
     */
    public static ShellKey execution(ExecutionId executionId) {
        return new ShellKey("exec:" + executionId.value());
    }

    /** @return the key */
    public String value() {
        return value;
    }

    /** @return whether this is an {@code exec:} key, whose state directory is swept after {@code execShellIdle} */
    public boolean isExecution() {
        return value.startsWith("exec:");
    }

    /**
     * @return {@code h(value)}, the state directory's name under {@code /workspace/.aimon-shell/}
     *
     *         <p>
     *         <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change
     *         in
     *         any release.
     */
    public String directoryName() {
        return SandboxLabels.h(value);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ShellKey that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
