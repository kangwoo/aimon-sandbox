package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

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
 * Two inherited tests do not run on this tier ({@link RuntimeOutlivesItsKill}): core's runtime moves into a process
 * group of its own, so the shell's kill does not reach it (docs/design/workspace-sandbox.md §20). Here the runtime is a
 * host process: {@code wallClock} would leave its endless loop running on the developer's machine after every build,
 * and {@code bridgeLimitStopsAtOnce} fails when the runtime gets more calls out before the shell call returns. Both
 * run on the docker tier, where the runtime dies with the sandbox.
 */
@ExtendWith(ShellEngineContractTest.RuntimeOutlivesItsKill.class)
class ShellEngineContractTest extends AbstractCodeExecutionEngineContractTest {

    /** The run-directory file core's runtime reads its reply directory from. */
    static final String REPLY_DIR_FILE = "reply_dir";

    private final SandboxHarness harness = SandboxHarness.builder()
            .profile(SandboxTestProfiles.local("python").runtimes(Set.of("python3")).build()).build();
    private ExecutionEnvironment environment;

    @BeforeEach
    void provision() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment sandbox = harness.mainTurn(session, ALICE);
        assumeTrue(usablePython(sandbox), "the host has no python3 3.8 or later");
        environment = new HostPathEnvironment(sandbox, path -> harness.host(session, path).toString());
    }

    @AfterEach
    void close() {
        harness.close();
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

    /**
     * Disables the inherited tests that core's runtime, which outlives the shell's kill, breaks on a host (class doc).
     */
    static final class RuntimeOutlivesItsKill implements ExecutionCondition {
        private static final Set<String> TESTS = Set.of("wallClock", "bridgeLimitStopsAtOnce");

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return context.getTestMethod().map(Method::getName).filter(TESTS::contains)
                    .map(name -> ConditionEvaluationResult.disabled("core's runtime leaves the exec's process group, "
                            + "so the shell's kill misses it; on the host it would outlive the test "
                            + "(docs/design/workspace-sandbox.md §20)"))
                    .orElse(ConditionEvaluationResult.enabled("not affected"));
        }
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
