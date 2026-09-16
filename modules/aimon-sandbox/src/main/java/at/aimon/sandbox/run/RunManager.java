package at.aimon.sandbox.run;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import at.aimon.sandbox.model.CommandResult;
import at.aimon.sandbox.model.RunState;
import at.aimon.sandbox.model.SandboxRun;

/**
 * Manages the lifecycle of sandbox runs.
 *
 * <p>
 * Orchestrates run state transitions and delegates storage to {@link RunStore}.
 */
public class RunManager {

    private final RunStore store;

    public RunManager(RunStore store) {
        this.store = Objects.requireNonNull(store, "RunStore cannot be null");
    }

    /**
     * Creates a new run in QUEUED state.
     */
    public SandboxRun createRun(String identifier, String sandboxId) {
        String runId = UUID.randomUUID().toString();
        SandboxRun run = SandboxRun.builder().runId(runId).identifier(identifier).sandboxId(sandboxId)
                .state(RunState.QUEUED).createdAt(Instant.now()).commands(List.of()).build();
        store.save(run);
        return run;
    }

    /**
     * Transitions a run from QUEUED to RUNNING state.
     *
     * @throws IllegalStateException
     *             if the run is not in QUEUED state
     */
    public SandboxRun start(String runId) {
        return store.update(runId, run -> {
            if (run.getState() != RunState.QUEUED) {
                throw new IllegalStateException(
                        "Cannot start run " + runId + ": expected QUEUED but was " + run.getState());
            }
            return run.withState(RunState.RUNNING).withStartedAt(Instant.now());
        });
    }

    /**
     * Finds a run by ID.
     */
    public Optional<SandboxRun> getRun(String runId) {
        return store.findById(runId);
    }

    /**
     * Appends a command result to the run.
     */
    public SandboxRun addCommandResult(String runId, CommandResult result) {
        return store.update(runId, run -> run.withCommandResult(result));
    }

    /**
     * Transitions a run from RUNNING to COMPLETED state with artifact summary.
     *
     * @throws IllegalStateException
     *             if the run is not in RUNNING state
     */
    public SandboxRun complete(String runId, int artifactCount, long artifactTotalBytes) {
        return store.update(runId, run -> {
            if (run.getState() != RunState.RUNNING) {
                throw new IllegalStateException(
                        "Cannot complete run " + runId + ": expected RUNNING but was " + run.getState());
            }
            return run.withState(RunState.COMPLETED).withArtifactSummary(artifactCount, artifactTotalBytes)
                    .withEndedAt(Instant.now());
        });
    }

    /**
     * Transitions a run from RUNNING to FAILED state with an error message.
     *
     * @throws IllegalStateException
     *             if the run is not in RUNNING state
     */
    public SandboxRun fail(String runId, String error) {
        return store.update(runId, run -> {
            if (run.getState() != RunState.RUNNING) {
                throw new IllegalStateException(
                        "Cannot fail run " + runId + ": expected RUNNING but was " + run.getState());
            }
            return run.withState(RunState.FAILED).withError(error).withEndedAt(Instant.now());
        });
    }
}
