package at.aimon.sandbox.backend;

import java.util.Map;
import java.util.Objects;

import at.aimon.sandbox.model.SandboxUser;

/**
 * Parameters for executing a command in a sandbox.
 *
 * <p>
 * Immutable value object.
 */
public final class ExecParams {

    private final String command;
    private final String cwd;
    private final Map<String, String> env;
    private final SandboxUser asUser;
    private final int timeoutMs;
    private final int maxOutputBytes;

    private ExecParams(Builder builder) {
        this.command = Objects.requireNonNull(builder.command, "Command cannot be null");
        if (this.command.isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        this.cwd = builder.cwd;
        this.env = builder.env != null ? Map.copyOf(builder.env) : Map.of();
        this.asUser = builder.asUser;
        this.timeoutMs = builder.timeoutMs;
        this.maxOutputBytes = builder.maxOutputBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCommand() {
        return command;
    }

    public String getCwd() {
        return cwd;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public SandboxUser getAsUser() {
        return asUser;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExecParams that = (ExecParams) o;
        return timeoutMs == that.timeoutMs && maxOutputBytes == that.maxOutputBytes && command.equals(that.command)
                && Objects.equals(cwd, that.cwd) && env.equals(that.env) && asUser == that.asUser;
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, cwd, env, asUser, timeoutMs, maxOutputBytes);
    }

    @Override
    public String toString() {
        return "ExecParams{" + "command='" + command + "', cwd='" + cwd + "', timeoutMs=" + timeoutMs
                + ", maxOutputBytes=" + maxOutputBytes + '}';
    }

    /** Builder for ExecParams. */
    public static final class Builder {

        private String command;
        private String cwd;
        private Map<String, String> env;
        private SandboxUser asUser;
        private int timeoutMs = 120_000;
        private int maxOutputBytes = 1_048_576;

        private Builder() {
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder cwd(String cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public Builder asUser(SandboxUser asUser) {
            this.asUser = asUser;
            return this;
        }

        public Builder timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder maxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        public ExecParams build() {
            return new ExecParams(this);
        }
    }
}
