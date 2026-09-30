package at.aimon.sandbox.provider;

/**
 * A node-local handle on one sandbox (docs/design/workspace-sandbox.md §6.1). Cached per {@link ProviderSandboxRef}
 * by {@link SandboxConnectionCache}; never stored in the workspace record.
 */
public interface SandboxConnection extends AutoCloseable {

    /**
     * Starts one command in a process group of its own. Its output may reach the sink and the outcome
     * line-normalized ({@link ExecOutcome}).
     *
     * @param spec
     *            the command
     * @param sink
     *            receives output as it arrives
     * @return the running command
     * @throws SandboxNotFoundException
     *             if the sandbox is gone
     */
    RunningCommand run(ExecSpec spec, OutputSink sink);

    /** @return the sandbox's files API */
    SandboxFiles files();

    /** Releases node-local resources. Does not touch the sandbox. */
    @Override
    void close();
}
