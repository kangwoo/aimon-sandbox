package at.aimon.sandbox.backend;

import java.io.IOException;
import java.io.InputStream;

import at.aimon.sandbox.model.Sandbox;

/**
 * Abstraction for sandbox lifecycle management, command execution, and artifact extraction.
 *
 * <p>
 * Implementations provide the actual runtime environment (Docker containers, Kubernetes pods, etc.). The core sandbox
 * module depends only on this interface; backend-specific dependencies are isolated in separate modules.
 *
 * @see at.aimon.sandbox.model.Sandbox
 */
public interface SandboxBackend {

    // --- Lifecycle management ---

    /**
     * Ensures a sandbox exists for the given identifier. Creates a new sandbox if none exists, or reuses the existing
     * one.
     *
     * <p>
     * This method must be idempotent. Concurrent calls for the same identifier must result in exactly one sandbox.
     * Implementations must handle name collisions internally.
     *
     * <p>
     * If an existing sandbox is found, its {@code expiresAt} is renewed to {@code now + ttlSeconds}.
     *
     * @param identifier
     *            the sandbox identifier
     * @param ttlSeconds
     *            the time-to-live in seconds
     * @return the sandbox metadata
     * @throws IOException
     *             if sandbox creation or lookup fails
     */
    Sandbox ensure(String identifier, int ttlSeconds) throws IOException;

    /**
     * Deletes the sandbox for the given identifier.
     *
     * @param identifier
     *            the sandbox identifier
     * @throws IOException
     *             if deletion fails
     */
    void delete(String identifier) throws IOException;

    /**
     * Restarts the sandbox by deleting and recreating it. Succeeds even if no existing sandbox is found.
     *
     * @param identifier
     *            the sandbox identifier
     * @param ttlSeconds
     *            the time-to-live in seconds for the new sandbox
     * @return the new sandbox metadata
     * @throws IOException
     *             if restart fails
     */
    Sandbox restart(String identifier, int ttlSeconds) throws IOException;

    /**
     * Returns the count of active sandboxes managed by this backend.
     *
     * @return the number of active sandboxes
     * @throws IOException
     *             if counting fails
     */
    int count() throws IOException;

    /**
     * Removes expired sandboxes.
     *
     * @return the number of sandboxes removed
     * @throws IOException
     *             if reaping fails
     */
    int reapExpired() throws IOException;

    // --- Command execution ---

    /**
     * Executes a command in the specified sandbox.
     *
     * @param sandboxId
     *            the sandbox ID (container ID or pod name)
     * @param params
     *            the execution parameters
     * @return the execution result
     * @throws IOException
     *             if execution fails
     */
    ExecResult exec(String sandboxId, ExecParams params) throws IOException;

    // --- Artifact transfer ---

    /**
     * Copies the {@code /artifacts} directory from the sandbox as a tar stream.
     *
     * @param sandboxId
     *            the sandbox ID
     * @return an input stream of the tar archive
     * @throws IOException
     *             if copy fails
     */
    InputStream copyArtifacts(String sandboxId) throws IOException;

    /**
     * Copies a tar archive into the sandbox at the specified destination path.
     *
     * @param sandboxId
     *            the sandbox ID (container ID or pod name)
     * @param tarStream
     *            the tar archive input stream
     * @param destPath
     *            the destination path inside the sandbox (e.g. "/workspace")
     * @throws IOException
     *             if copy fails
     */
    void copyToSandbox(String sandboxId, InputStream tarStream, String destPath) throws IOException;
}
