package at.aimon.sandbox.provider;

import java.util.Objects;

/**
 * How one {@link SandboxConnection#run} ended. The byte arrays hold at most {@link ExecSpec#maxCaptureBytes()} each;
 * the truncation flags say whether more was produced.
 */
public final class ExecOutcome {

    private static final byte[] EMPTY = new byte[0];

    private final int exitCode;
    private final byte[] stdout;
    private final byte[] stderr;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;
    private final boolean timedOut;

    private ExecOutcome(Builder builder) {
        this.exitCode = builder.exitCode;
        this.stdout = builder.stdout.clone();
        this.stderr = builder.stderr.clone();
        this.stdoutTruncated = builder.stdoutTruncated;
        this.stderrTruncated = builder.stderrTruncated;
        this.timedOut = builder.timedOut;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the process exit code; {@code 128 + n} when it died of signal {@code n} */
    public int exitCode() {
        return exitCode;
    }

    /** @return the captured stdout */
    public byte[] stdout() {
        return stdout.clone();
    }

    /** @return the captured stderr */
    public byte[] stderr() {
        return stderr.clone();
    }

    /** @return whether stdout had more than the cap */
    public boolean stdoutTruncated() {
        return stdoutTruncated;
    }

    /** @return whether stderr had more than the cap */
    public boolean stderrTruncated() {
        return stderrTruncated;
    }

    /** @return whether the provider killed the command because a timeout passed */
    public boolean timedOut() {
        return timedOut;
    }

    @Override
    public String toString() {
        return "ExecOutcome{exitCode=" + exitCode + ", stdout=" + stdout.length + "B, stderr=" + stderr.length
                + "B, truncated=" + stdoutTruncated + "/" + stderrTruncated + ", timedOut=" + timedOut + '}';
    }

    /** Builder for {@link ExecOutcome}. */
    public static final class Builder {
        private int exitCode;
        private byte[] stdout = EMPTY;
        private byte[] stderr = EMPTY;
        private boolean stdoutTruncated;
        private boolean stderrTruncated;
        private boolean timedOut;

        private Builder() {
        }

        public Builder exitCode(int exitCode) {
            this.exitCode = exitCode;
            return this;
        }

        public Builder stdout(byte[] stdout) {
            this.stdout = Objects.requireNonNull(stdout, "stdout must not be null");
            return this;
        }

        public Builder stderr(byte[] stderr) {
            this.stderr = Objects.requireNonNull(stderr, "stderr must not be null");
            return this;
        }

        public Builder stdoutTruncated(boolean stdoutTruncated) {
            this.stdoutTruncated = stdoutTruncated;
            return this;
        }

        public Builder stderrTruncated(boolean stderrTruncated) {
            this.stderrTruncated = stderrTruncated;
            return this;
        }

        public Builder timedOut(boolean timedOut) {
            this.timedOut = timedOut;
            return this;
        }

        public ExecOutcome build() {
            return new ExecOutcome(this);
        }
    }
}
