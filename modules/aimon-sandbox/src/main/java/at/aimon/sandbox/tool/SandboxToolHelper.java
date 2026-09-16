package at.aimon.sandbox.tool;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.SandboxLock;

/**
 * Shared helper utilities for sandbox tool implementations.
 *
 * <p>
 * Eliminates duplicated lock-acquire/release and TTL resolution patterns across
 * {@link RunSandboxTool}, {@link CopyToSandboxTool}, and {@link RestartSandboxTool}.
 */
final class SandboxToolHelper {

    private SandboxToolHelper() {
    }

    /**
     * Executes an action with an optional per-identifier lock.
     *
     * @param lock
     *            the sandbox lock
     * @param identifier
     *            the sandbox identifier
     * @param lockSandbox
     *            whether to acquire the lock
     * @param action
     *            the action to execute
     * @param <T>
     *            what the action produces — a {@code ToolResult} for a tool that composes its own, or the payload a
     *            {@code GenericTool} hands to {@code render}
     * @return whatever the action returned
     * @throws Exception
     *             if the action throws
     */
    static <T> T withOptionalLock(SandboxLock lock, String identifier, boolean lockSandbox, SandboxToolAction<T> action)
            throws Exception {
        if (lockSandbox) {
            lock.lock(identifier);
        }
        try {
            return action.execute();
        } finally {
            if (lockSandbox) {
                lock.unlock(identifier);
            }
        }
    }

    /**
     * Resolves the effective TTL in seconds, capped at the configured maximum.
     *
     * @param input
     *            the tool input containing an optional {@code ttl_seconds} parameter
     * @param config
     *            the sandbox configuration
     * @return the effective TTL in seconds
     */
    static int resolveTtl(ToolInput input, SandboxConfig config) {
        return Math.min(input.getInteger("ttl_seconds", config.getDefaultTtlSeconds()), config.getMaxTtlSeconds());
    }

    /**
     * Resolves the effective TTL from an already-bound value, capped at the configured maximum.
     *
     * <p>
     * The counterpart of {@link #resolveTtl(ToolInput, SandboxConfig)} for tools that read their parameters through a
     * bound input record instead of the raw map. Both overloads exist because the sandbox tools are migrating to
     * {@code GenericTool} one at a time and {@code RestartSandboxTool} is below the size threshold for it.
     *
     * @param ttlSeconds
     *            the requested TTL, or null when the caller did not supply one
     * @param config
     *            the sandbox configuration
     * @return the effective TTL in seconds
     */
    static int resolveTtl(Integer ttlSeconds, SandboxConfig config) {
        return Math.min(ttlSeconds != null ? ttlSeconds : config.getDefaultTtlSeconds(), config.getMaxTtlSeconds());
    }

    /**
     * Resolves the lock_sandbox parameter from input, falling back to config default.
     *
     * @param input
     *            the tool input containing an optional {@code lock_sandbox} parameter
     * @param config
     *            the sandbox configuration
     * @return whether to lock the sandbox
     */
    static boolean resolveLockSandbox(ToolInput input, SandboxConfig config) {
        return input.getBoolean("lock_sandbox", config.isDefaultLockSandbox());
    }

    /**
     * Resolves the lock flag from an already-bound value, falling back to the configured default.
     *
     * @param lockSandbox
     *            the requested flag, or null when the caller did not supply one
     * @param config
     *            the sandbox configuration
     * @return whether to lock the sandbox
     */
    static boolean resolveLockSandbox(Boolean lockSandbox, SandboxConfig config) {
        return lockSandbox != null ? lockSandbox : config.isDefaultLockSandbox();
    }

    /**
     * Action that may throw checked exceptions, executed within the lock scope.
     *
     * @param <T>
     *            what the action produces
     */
    @FunctionalInterface
    interface SandboxToolAction<T> {

        T execute() throws Exception;
    }
}
