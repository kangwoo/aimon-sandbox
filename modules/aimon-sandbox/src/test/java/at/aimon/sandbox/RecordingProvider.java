package at.aimon.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxProvider;

/**
 * Records every {@link ExecSpec} its connections run, and lets a test replace one before it runs — what the exec
 * server would receive, which the local provider never limits. It also counts the kills asked of the commands it
 * started and the connections closed, and lets a test wrap the commands.
 */
public class RecordingProvider extends DelegatingProvider {

    public final List<ExecSpec> runs = new CopyOnWriteArrayList<>();
    public final AtomicInteger kills = new AtomicInteger();
    public final AtomicInteger connectionsClosed = new AtomicInteger();
    private volatile UnaryOperator<RunningCommand> commands = UnaryOperator.identity();
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

    /** Wraps the commands started from now on, e.g. to trip a signal as one ends or to make its kill do nothing. */
    public void commands(UnaryOperator<RunningCommand> commands) {
        this.commands = commands;
    }

    @Override
    public SandboxConnection connect(ProviderSandboxRef ref) {
        final SandboxConnection connection = delegate.connect(ref);
        final SandboxFiles wrapped = files.apply(connection.files());
        return new SandboxConnection() {
            @Override
            public RunningCommand run(ExecSpec spec, OutputSink sink) {
                runs.add(spec);
                final RunningCommand command = commands.apply(connection.run(rewrite.apply(spec), sink));
                return new RunningCommand() {
                    @Override
                    public ExecOutcome await(Duration timeout) throws InterruptedException {
                        return command.await(timeout);
                    }

                    @Override
                    public void kill() {
                        kills.incrementAndGet();
                        command.kill();
                    }
                };
            }

            @Override
            public SandboxFiles files() {
                return wrapped;
            }

            @Override
            public void close() {
                connectionsClosed.incrementAndGet();
                connection.close();
            }
        };
    }
}
