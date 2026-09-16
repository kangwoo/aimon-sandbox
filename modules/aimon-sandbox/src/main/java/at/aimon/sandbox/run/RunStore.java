package at.aimon.sandbox.run;

import java.util.Optional;
import java.util.function.UnaryOperator;

import at.aimon.sandbox.model.SandboxRun;

/**
 * Storage abstraction for sandbox run data.
 *
 * <p>
 * Implementations must be thread-safe. The default in-memory implementation is suitable for single-instance
 * deployments. For multi-instance environments, provide an implementation backed by an external store (Redis, JDBC,
 * etc.).
 */
public interface RunStore {

    /**
     * Saves a new run.
     *
     * @param run
     *            the run to save
     */
    void save(SandboxRun run);

    /**
     * Finds a run by its ID.
     *
     * @param runId
     *            the run ID
     * @return the run, or empty if not found
     */
    Optional<SandboxRun> findById(String runId);

    /**
     * Atomically updates a run by applying the given function.
     *
     * @param runId
     *            the run ID
     * @param updateFn
     *            function that receives the current run and returns the updated run
     * @return the updated run
     * @throws IllegalArgumentException
     *             if the run does not exist
     */
    SandboxRun update(String runId, UnaryOperator<SandboxRun> updateFn);
}
