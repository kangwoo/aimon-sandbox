package at.aimon.sandbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxProvider;

/**
 * Records every {@link ExecSpec} its connections run, and lets a test replace one before it runs — what the exec
 * server would receive, which the local provider never limits.
 */
public class RecordingProvider extends DelegatingProvider {

    public final List<ExecSpec> runs = new CopyOnWriteArrayList<>();
    private volatile UnaryOperator<ExecSpec> rewrite = UnaryOperator.identity();
    private volatile UnaryOperator<SandboxFiles> files = UnaryOperator.identity();

    public RecordingProvider(SandboxProvider delegate) {
        super(delegate);
    }

    /** Replaces what later runs execute (the recorded spec is the original). */
    public void rewrite(UnaryOperator<ExecSpec> rewrite) {
        this.rewrite = rewrite;
    }

    /** Wraps the files API of connections opened from now on, e.g. to make a write fail as a full disk would. */
    public void files(UnaryOperator<SandboxFiles> files) {
        this.files = files;
    }

    @Override
    public SandboxConnection connect(ProviderSandboxRef ref) {
        final SandboxConnection connection = delegate.connect(ref);
        final SandboxFiles wrapped = files.apply(connection.files());
        return new SandboxConnection() {
            @Override
            public RunningCommand run(ExecSpec spec, OutputSink sink) {
                runs.add(spec);
                return connection.run(rewrite.apply(spec), sink);
            }

            @Override
            public SandboxFiles files() {
                return wrapped;
            }

            @Override
            public void close() {
                connection.close();
            }
        };
    }
}
