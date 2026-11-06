package at.aimon.sandbox.opensandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellation;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.ShellLineListener;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.programmatic.testkit.AbstractCodeExecutionEngineContractTest;
import at.aimon.programmatic.testkit.EnginePrograms;
import at.aimon.programmatic.testkit.PythonPrograms;

/**
 * Core's engine contract suite with core's Python shell engine on a real OpenSandbox sandbox (shell doc §14;
 * docs/design/ptc-shell-provider.md §5.7): the real execd, the real {@code /workspace}, the tier's image with
 * {@code python3}. It exercises the seed's {@code runtime-python3} check, isolated streamed runs whose run directory
 * carries a secret file (the S4 upload path), the runtime file system over execd, the spool removal of S1 and the
 * deletion of each run's directory. Not the engine's offer-time availability or its probe: the suite calls
 * {@code execute} directly — {@link ShellEngineAvailabilityIT} covers those.
 *
 * <p>
 * One change to what the engine sees ({@link StoppedRunsGetNoReply}): once a run has been stopped, its replies are
 * dropped instead of written. Core's runtime leaves the exec's process group, so the shell's kill does not reach it
 * (docs/design/workspace-sandbox.md §20), and the engine keeps answering until the shell call returns; every reply
 * written in that window lets the runtime make another call, which made {@code bridgeLimitStopsAtOnce} (at most 5
 * calls) depend on how fast the kill returned. Without the reply the runtime waits, and gives up at its own deadline.
 * Every other inherited test is unaffected: none of them looks at a reply after its run was stopped.
 */
@Tag("docker")
class ShellEngineContractIT extends AbstractCodeExecutionEngineContractTest {

    private static SharedSandbox sandbox;
    private static ExecutionEnvironment environment;

    @BeforeAll
    static void start() throws Exception {
        sandbox = SharedSandbox.started();
        environment = new StoppedRunsGetNoReply(sandbox.environment);
    }

    @AfterAll
    static void stop() {
        if (sandbox != null) {
            sandbox.close();
        }
    }

    @Override
    protected CodeExecutionEngine engine() {
        return ShellCodeExecutionEngine.create();
    }

    @Override
    protected EnginePrograms programs() {
        return new PythonPrograms();
    }

    @Override
    protected ToolContext toolContext() {
        return ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment).build();
    }

    /**
     * An environment that delegates everything, but whose runtime file system drops a write made while the shell
     * command in flight has been cancelled — the engine's reply to a run it already stopped (class doc). Writes outside
     * a shell command (the engine's probe) and before the stop pass through.
     */
    static final class StoppedRunsGetNoReply implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;
        private final VirtualShell shell;
        private final RuntimeFileSystem files;
        /** The cancellation of the shell command in flight; {@link ShellCancellation#none()} between commands. */
        private volatile ShellCancellation running = ShellCancellation.none();

        StoppedRunsGetNoReply(ExecutionEnvironment delegate) {
            this.delegate = delegate;
            this.shell = new TrackingShell(delegate.shell());
            final RuntimeFileSystem real = delegate.runtimeFileSystem().orElseThrow();
            this.files = new RuntimeFileSystem() {
                @Override
                public void writeNew(String path, byte[] bytes) throws IOException {
                    if (!running.isCancelled()) {
                        real.writeNew(path, bytes);
                    }
                }

                @Override
                public byte[] read(String path, int maxBytes) throws IOException {
                    return real.read(path, maxBytes);
                }

                @Override
                public void delete(String path) throws IOException {
                    real.delete(path);
                }

                @Override
                public void createDirectories(String path) throws IOException {
                    real.createDirectories(path);
                }
            };
        }

        @Override
        public VirtualFileSystem fileSystem() {
            return delegate.fileSystem();
        }

        @Override
        public VirtualShell shell() {
            return shell;
        }

        @Override
        public EnvironmentDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public boolean durable() {
            return delegate.durable();
        }

        @Override
        public String stage(StagedResource resource) {
            return delegate.stage(resource);
        }

        @Override
        public Optional<RuntimeFileSystem> runtimeFileSystem() {
            return Optional.of(files);
        }

        @Override
        public Optional<ContentSearch> contentSearch() {
            return delegate.contentSearch();
        }

        @Override
        public Optional<Duration> backgroundCommandTimeout() {
            return delegate.backgroundCommandTimeout();
        }

        @Override
        public List<String> drainNotices() {
            return delegate.drainNotices();
        }

        /** Records the cancellation of the streamed command in flight — the engine's run. */
        private final class TrackingShell implements VirtualShell {
            private final VirtualShell delegate;

            TrackingShell(VirtualShell delegate) {
                this.delegate = delegate;
            }

            @Override
            public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
                return delegate.execute(command);
            }

            @Override
            public ShellCommandResult execute(ShellCommand command, ExecutionOptions options)
                    throws ShellExecutionException {
                return delegate.execute(command, options);
            }

            @Override
            public ShellCommandResult execute(ShellCommand command, ExecutionOptions options,
                    ShellLineListener listener) throws ShellExecutionException {
                running = options.getCancellation();
                try {
                    return delegate.execute(command, options, listener);
                } finally {
                    // Past this point no line reaches the listener, so no reply can turn into a recorded call.
                    running = ShellCancellation.none();
                }
            }

            @Override
            public String getWorkingDirectory() {
                return delegate.getWorkingDirectory();
            }

            @Override
            public boolean supports(ShellFeature feature) {
                return delegate.supports(feature);
            }

            @Override
            public void close() {
                delegate.close();
            }
        }
    }
}
