package at.aimon.sandbox.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single command to execute in a sandbox.
 *
 * <p>
 * Either {@code shell} or {@code argv} must be provided, but not both.
 *
 * <p>
 * Immutable value object.
 */
public final class CommandInput {

    private final String shell;
    private final List<String> argv;
    private final String cwd;
    private final Map<String, String> env;
    private final Integer timeoutMs;
    private final boolean allowFailure;

    private CommandInput(Builder builder) {
        this.shell = builder.shell;
        this.argv = builder.argv != null ? List.copyOf(builder.argv) : null;
        this.cwd = builder.cwd;
        this.env = builder.env != null ? Map.copyOf(builder.env) : Map.of();
        this.timeoutMs = builder.timeoutMs;
        this.allowFailure = builder.allowFailure;

        if (shell == null && argv == null) {
            throw new IllegalArgumentException("Either shell or argv must be provided");
        }
        if (shell != null && argv != null) {
            throw new IllegalArgumentException("Only one of shell or argv can be provided");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getShell() {
        return shell;
    }

    public List<String> getArgv() {
        return argv;
    }

    public String getCwd() {
        return cwd;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public boolean isAllowFailure() {
        return allowFailure;
    }

    /**
     * Returns the command string representation. For shell commands, returns the shell string. For argv commands,
     * returns the arguments joined by space.
     */
    public String getCommandString() {
        if (shell != null) {
            return shell;
        }
        return String.join(" ", argv);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CommandInput that = (CommandInput) o;
        return allowFailure == that.allowFailure && Objects.equals(shell, that.shell) && Objects.equals(argv, that.argv)
                && Objects.equals(cwd, that.cwd) && env.equals(that.env) && Objects.equals(timeoutMs, that.timeoutMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shell, argv, cwd, env, timeoutMs, allowFailure);
    }

    @Override
    public String toString() {
        return "CommandInput{" + "command='" + getCommandString() + "'}";
    }

    /** Builder for CommandInput. */
    public static final class Builder {

        private String shell;
        private List<String> argv;
        private String cwd;
        private Map<String, String> env;
        private Integer timeoutMs;
        private boolean allowFailure;

        private Builder() {
        }

        public Builder shell(String shell) {
            this.shell = shell;
            return this;
        }

        public Builder argv(List<String> argv) {
            this.argv = argv;
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

        public Builder timeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder allowFailure(boolean allowFailure) {
            this.allowFailure = allowFailure;
            return this;
        }

        public CommandInput build() {
            return new CommandInput(this);
        }
    }
}
