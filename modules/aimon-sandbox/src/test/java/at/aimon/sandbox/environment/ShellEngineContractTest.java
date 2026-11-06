package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
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
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/**
 * Core's engine contract suite run with core's Python shell engine on a sandbox environment over the testkit's local
 * provider (shell doc §14; docs/design/ptc-shell-provider.md §5.7). Skipped when the host has no {@code python3} 3.8+.
 *
 * <p>
 * The local provider virtualises {@code /workspace} in command text, environment values, output and files-API paths,
 * but not in the bytes of run-directory files — and core's runtime reads its reply directory from one such file
 * ({@code reply_dir}, an absolute {@code /workspace} path), so it would look on the host's own {@code /workspace}.
 * {@link HostPathEnvironment} rewrites that one file to the host path before the shell places it, keeping every other
 * file and every secret mark; the sandbox shell computes its digests from what it receives, so the integrity check
 * still runs for real. If core stops writing {@code reply_dir}, nothing is rewritten, no reply arrives and the suite
 * fails — loudly, here. The docker tier ({@code ShellEngineContractIT}) runs the suite with nothing rewritten.
 *
 * <p>
 * Every inherited test runs, {@code wallClock} and {@code bridgeLimitStopsAtOnce} included: core's runtime ends with
 * the shell's process-group kill (its anchor stays in the exec's group, and a watchdog kills the runtime's own group
 * when the anchor dies — aimon-core#336), so a timed-out or stopped run leaves no process on the host. After each
 * test the host is checked for that: no {@code aimon_ptc.py} whose run directory lies in the test's sandbox remains.
 */
class ShellEngineContractTest extends AbstractCodeExecutionEngineContractTest {

    /** The run-directory file core's runtime reads its reply directory from. */
    static final String REPLY_DIR_FILE = "reply_dir";

    private final SandboxHarness harness = SandboxHarness.builder()
            .profile(SandboxTestProfiles.local("python").runtimes(Set.of("python3")).build()).build();
    private ExecutionEnvironment environment;
    /** The host path of the sandbox's run directories, which every runtime's command line names; null if skipped. */
    private String runDirectories;

    @BeforeEach
    void provision() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment sandbox = harness.mainTurn(session, ALICE);
        assumeTrue(usablePython(sandbox), "the host has no python3 3.8 or later");
        environment = new HostPathEnvironment(sandbox, path -> harness.host(session, path).toString());
        runDirectories = harness.host(session, "/workspace/.aimon-shell").toString();
    }

    @AfterEach
    void close() throws InterruptedException {
        try {
            if (runDirectories != null) {
                assertNoRuntimeLeft(runDirectories);
            }
        } finally {
            harness.close();
        }
    }

    /**
     * Fails if a runtime of this sandbox is still alive on the host a few seconds after the test — killing it first,
     * so a failure leaves no stray process behind either.
     */
    private static void assertNoRuntimeLeft(String runDirectories) throws InterruptedException {
        List<ProcessHandle> left = runtimes(runDirectories);
        for (int attempt = 0; attempt < 50 && !left.isEmpty(); attempt++) {
            Thread.sleep(100);
            left = runtimes(runDirectories);
        }
        final List<String> described = left.stream()
                .map(process -> process.pid() + " " + process.info().commandLine().orElse("")).toList();
        left.forEach(ProcessHandle::destroyForcibly);
        assertThat(described).as("aimon_ptc.py processes left on the host").isEmpty();
    }

    private static List<ProcessHandle> runtimes(String runDirectories) {
        return ProcessHandle.allProcesses().filter(ProcessHandle::isAlive)
                .filter(process -> process.info().commandLine()
                        .filter(line -> line.contains("aimon_ptc.py") && line.contains(runDirectories)).isPresent())
                .toList();
    }

    /**
     * Whether {@code python3 -I} of 3.8 or later runs in the sandbox — the check core's probe makes. Asked of the
     * sandbox's own {@code PATH}, so a macOS developer-tools stub that only offers to install counts as missing.
     */
    static boolean usablePython(ExecutionEnvironment sandbox) throws ShellExecutionException {
        return bash(sandbox,
                "python3 -I -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)'" + " >/dev/null 2>&1")
                .exitCode() == 0;
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

    /** An environment that delegates everything, but whose shell rewrites {@code reply_dir} to the host path. */
    static final class HostPathEnvironment implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;
        private final VirtualShell shell;

        /**
         * @param hostPath
         *            a sandbox path as the host path of the sandbox's current {@code /workspace}, resolved per call so
         *            a re-created sandbox is followed
         */
        HostPathEnvironment(ExecutionEnvironment delegate, UnaryOperator<String> hostPath) {
            this.delegate = delegate;
            this.shell = new RewritingShell(delegate.shell(), hostPath);
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
            return delegate.runtimeFileSystem();
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
    }

    private static final class RewritingShell implements VirtualShell {
        private final VirtualShell delegate;
        private final UnaryOperator<String> hostPath;

        RewritingShell(VirtualShell delegate, UnaryOperator<String> hostPath) {
            this.delegate = delegate;
            this.hostPath = hostPath;
        }

        private ExecutionOptions rewrite(ExecutionOptions options) {
            final Optional<RunDirectory> run = options.getRunDirectory();
            if (run.isEmpty() || !run.get().getPaths().contains(REPLY_DIR_FILE)) {
                return options;
            }
            final RunDirectory.Builder rewritten = RunDirectory.builder().variable(run.get().getVariable());
            for (String path : run.get().getPaths()) {
                byte[] content = run.get().getContent(path);
                if (REPLY_DIR_FILE.equals(path)) {
                    content = hostPath.apply(new String(content, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                }
                if (run.get().isSecret(path)) {
                    rewritten.secretFile(path, content);
                } else {
                    rewritten.file(path, content);
                }
            }
            return options.toBuilder().runDirectory(rewritten.build()).build();
        }

        @Override
        public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
            return delegate.execute(command);
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options)
                throws ShellExecutionException {
            return delegate.execute(command, rewrite(options));
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options, ShellLineListener listener)
                throws ShellExecutionException {
            return delegate.execute(command, rewrite(options), listener);
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
