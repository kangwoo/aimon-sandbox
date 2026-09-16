package at.aimon.sandbox.backend;

import java.util.Objects;

/**
 * Result of executing a command in a sandbox.
 *
 * <p>
 * Immutable value object.
 */
public final class ExecResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;

    private ExecResult(Builder builder) {
        this.exitCode = builder.exitCode;
        this.stdout = builder.stdout != null ? builder.stdout : "";
        this.stderr = builder.stderr != null ? builder.stderr : "";
    }

    public static Builder builder() {
        return new Builder();
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExecResult that = (ExecResult) o;
        return exitCode == that.exitCode && stdout.equals(that.stdout) && stderr.equals(that.stderr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exitCode, stdout, stderr);
    }

    @Override
    public String toString() {
        return "ExecResult{" + "exitCode=" + exitCode + '}';
    }

    /** Builder for ExecResult. */
    public static final class Builder {

        private int exitCode;
        private String stdout;
        private String stderr;

        private Builder() {
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

        public ExecResult build() {
            return new ExecResult(this);
        }
    }
}
