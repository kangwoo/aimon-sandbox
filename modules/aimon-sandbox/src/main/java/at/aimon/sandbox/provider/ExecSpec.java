package at.aimon.sandbox.provider;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One command for {@link SandboxConnection#run} (docs/design/workspace-sandbox.md §6.1).
 *
 * <p>
 * {@link #command()} is a bash script, run as {@code bash -c} in a process group of its own, as the sandbox's execd
 * user. {@link #environment()} is the profile's static {@code env} — a command's own variables are exported by the
 * script (§9); the one other use is staging verification pinning {@code PATH}. {@link #timeout()} is a provider-side
 * backstop: when it passes, the provider kills the process group and reports {@link ExecOutcome#timedOut()}.
 * {@link #maxCaptureBytes()} caps each of stdout and stderr separately; the rest is counted and dropped, never
 * buffered.
 */
public final class ExecSpec {

    private final String command;
    private final String workingDirectory;
    private final Map<String, String> environment;
    private final Duration timeout;
    private final long maxCaptureBytes;

    private ExecSpec(Builder builder) {
        this.command = Objects.requireNonNull(builder.command, "command must not be null");
        this.workingDirectory = builder.workingDirectory;
        this.environment = Map.copyOf(builder.environment);
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout must not be null");
        if (builder.maxCaptureBytes < 0) {
            throw new IllegalArgumentException("maxCaptureBytes must be >= 0, got " + builder.maxCaptureBytes);
        }
        this.maxCaptureBytes = builder.maxCaptureBytes;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the bash script */
    public String command() {
        return command;
    }

    /** @return the absolute working directory, when not the provider's default */
    public Optional<String> workingDirectory() {
        return Optional.ofNullable(workingDirectory);
    }

    /** @return the profile's static environment */
    public Map<String, String> environment() {
        return environment;
    }

    /** @return the provider-side backstop timeout */
    public Duration timeout() {
        return timeout;
    }

    /** @return the per-stream capture cap in bytes */
    public long maxCaptureBytes() {
        return maxCaptureBytes;
    }

    @Override
    public String toString() {
        return "ExecSpec{workingDirectory=" + workingDirectory + ", timeout=" + timeout + ", maxCaptureBytes="
                + maxCaptureBytes + ", command.length=" + command.length() + '}';
    }

    /** Builder for {@link ExecSpec}. */
    public static final class Builder {
        private String command;
        private String workingDirectory;
        private Map<String, String> environment = Map.of();
        private Duration timeout = Duration.ofMinutes(2);
        private long maxCaptureBytes = 1024 * 1024;

        private Builder() {
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = Objects.requireNonNull(environment, "environment must not be null");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxCaptureBytes(long maxCaptureBytes) {
            this.maxCaptureBytes = maxCaptureBytes;
            return this;
        }

        public ExecSpec build() {
            return new ExecSpec(this);
        }
    }
}
