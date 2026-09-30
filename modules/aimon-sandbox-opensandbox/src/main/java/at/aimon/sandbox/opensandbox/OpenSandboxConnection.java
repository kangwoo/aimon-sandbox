package at.aimon.sandbox.opensandbox;

import java.util.concurrent.Executor;

import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;

/** A node-local handle on one sandbox's execd: its resolved address, commands and files. Holds no socket. */
final class OpenSandboxConnection implements SandboxConnection {

    private final ExecdClient execd;
    private final Executor executor;
    private final OpenSandboxFiles files;

    OpenSandboxConnection(ExecdClient execd, Retry retry, Executor executor) {
        this.execd = execd;
        this.executor = executor;
        this.files = new OpenSandboxFiles(this, execd, retry);
    }

    @Override
    public RunningCommand run(ExecSpec spec, OutputSink sink) {
        return OpenSandboxRunningCommand.start(execd, spec, sink, executor);
    }

    @Override
    public SandboxFiles files() {
        return files;
    }

    @Override
    public void close() {
        // Nothing node-local is held between calls.
    }
}
