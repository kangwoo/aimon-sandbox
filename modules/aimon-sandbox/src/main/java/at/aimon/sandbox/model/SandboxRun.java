package at.aimon.sandbox.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single run execution within a sandbox.
 *
 * <p>
 * Immutable value object. State transitions produce new instances via {@code with*()} methods.
 */
public final class SandboxRun {

    private final String runId;
    private final String identifier;
    private final RunState state;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant endedAt;
    private final String sandboxId;
    private final List<CommandResult> commands;
    private final int artifactCount;
    private final long artifactTotalBytes;
    private final String error;

    private SandboxRun(Builder builder) {
        this.runId = Objects.requireNonNull(builder.runId, "Run ID cannot be null");
        this.identifier = Objects.requireNonNull(builder.identifier, "Identifier cannot be null");
        this.state = Objects.requireNonNull(builder.state, "State cannot be null");
        this.createdAt = Objects.requireNonNull(builder.createdAt, "CreatedAt cannot be null");
        this.startedAt = builder.startedAt;
        this.endedAt = builder.endedAt;
        this.sandboxId = Objects.requireNonNull(builder.sandboxId, "Sandbox ID cannot be null");
        this.commands = builder.commands != null ? List.copyOf(builder.commands) : List.of();
        this.artifactCount = builder.artifactCount;
        this.artifactTotalBytes = builder.artifactTotalBytes;
        this.error = builder.error;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getRunId() {
        return runId;
    }

    public String getIdentifier() {
        return identifier;
    }

    public RunState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public List<CommandResult> getCommands() {
        return commands;
    }

    public int getArtifactCount() {
        return artifactCount;
    }

    public long getArtifactTotalBytes() {
        return artifactTotalBytes;
    }

    public String getError() {
        return error;
    }

    /**
     * Creates a new SandboxRun with the given state.
     */
    public SandboxRun withState(RunState newState) {
        return toBuilder().state(newState).build();
    }

    /**
     * Creates a new SandboxRun with the given startedAt timestamp.
     */
    public SandboxRun withStartedAt(Instant startedAt) {
        return toBuilder().startedAt(startedAt).build();
    }

    /**
     * Creates a new SandboxRun with the given endedAt timestamp.
     */
    public SandboxRun withEndedAt(Instant endedAt) {
        return toBuilder().endedAt(endedAt).build();
    }

    /**
     * Creates a new SandboxRun with the command result appended.
     */
    public SandboxRun withCommandResult(CommandResult result) {
        List<CommandResult> newCommands = new ArrayList<>(this.commands);
        newCommands.add(result);
        return toBuilder().commands(newCommands).build();
    }

    /**
     * Creates a new SandboxRun with the given artifact summary.
     */
    public SandboxRun withArtifactSummary(int count, long totalBytes) {
        return toBuilder().artifactCount(count).artifactTotalBytes(totalBytes).build();
    }

    /**
     * Creates a new SandboxRun with the given error message.
     */
    public SandboxRun withError(String error) {
        return toBuilder().error(error).build();
    }

    private Builder toBuilder() {
        return new Builder().runId(runId).identifier(identifier).state(state).createdAt(createdAt).startedAt(startedAt)
                .endedAt(endedAt).sandboxId(sandboxId).commands(new ArrayList<>(commands)).artifactCount(artifactCount)
                .artifactTotalBytes(artifactTotalBytes).error(error);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SandboxRun that = (SandboxRun) o;
        return runId.equals(that.runId);
    }

    @Override
    public int hashCode() {
        return runId.hashCode();
    }

    @Override
    public String toString() {
        return "SandboxRun{" + "runId='" + runId + "', identifier='" + identifier + "', state=" + state + '}';
    }

    /** Builder for SandboxRun. */
    public static final class Builder {

        private String runId;
        private String identifier;
        private RunState state;
        private Instant createdAt;
        private Instant startedAt;
        private Instant endedAt;
        private String sandboxId;
        private List<CommandResult> commands;
        private int artifactCount;
        private long artifactTotalBytes;
        private String error;

        private Builder() {
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder identifier(String identifier) {
            this.identifier = identifier;
            return this;
        }

        public Builder state(RunState state) {
            this.state = state;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder endedAt(Instant endedAt) {
            this.endedAt = endedAt;
            return this;
        }

        public Builder sandboxId(String sandboxId) {
            this.sandboxId = sandboxId;
            return this;
        }

        public Builder commands(List<CommandResult> commands) {
            this.commands = commands;
            return this;
        }

        public Builder artifactCount(int artifactCount) {
            this.artifactCount = artifactCount;
            return this;
        }

        public Builder artifactTotalBytes(long artifactTotalBytes) {
            this.artifactTotalBytes = artifactTotalBytes;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public SandboxRun build() {
            return new SandboxRun(this);
        }
    }
}
