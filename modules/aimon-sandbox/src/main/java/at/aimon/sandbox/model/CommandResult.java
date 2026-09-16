package at.aimon.sandbox.model;

import java.util.Objects;

/**
 * Result of a single command execution within a sandbox run.
 *
 * <p>
 * Immutable value object.
 */
public final class CommandResult {

    private final int index;
    private final String command;
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final String error;
    private final long durationMs;

    private CommandResult(Builder builder) {
        this.index = builder.index;
        this.command = Objects.requireNonNull(builder.command, "Command cannot be null");
        this.exitCode = builder.exitCode;
        this.stdout = builder.stdout;
        this.stderr = builder.stderr;
        this.error = builder.error;
        this.durationMs = builder.durationMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getIndex() {
        return index;
    }

    public String getCommand() {
        return command;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public String getError() {
        return error;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CommandResult that = (CommandResult) o;
        return index == that.index && exitCode == that.exitCode && durationMs == that.durationMs
                && command.equals(that.command) && Objects.equals(stdout, that.stdout)
                && Objects.equals(stderr, that.stderr) && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, command, exitCode, stdout, stderr, error, durationMs);
    }

    @Override
    public String toString() {
        return "CommandResult{" + "index=" + index + ", command='" + command + "', exitCode=" + exitCode + '}';
    }

    /** Builder for CommandResult. */
    public static final class Builder {

        private int index;
        private String command;
        private int exitCode;
        private String stdout;
        private String stderr;
        private String error;
        private long durationMs;

        private Builder() {
        }

        public Builder index(int index) {
            this.index = index;
            return this;
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder exitCode(int exitCode) {
            this.exitCode = exitCode;
            return this;
        }

        public Builder stdout(String stdout) {
            this.stdout = stdout;
            return this;
        }

        public Builder stderr(String stderr) {
            this.stderr = stderr;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public CommandResult build() {
            return new CommandResult(this);
        }
    }
}
