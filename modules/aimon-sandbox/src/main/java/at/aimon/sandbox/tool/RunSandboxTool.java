package at.aimon.sandbox.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.sandbox.artifact.TarExtractor;
import at.aimon.sandbox.backend.ExecParams;
import at.aimon.sandbox.backend.ExecResult;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.model.CommandInput;
import at.aimon.sandbox.model.CommandResult;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.model.SandboxRun;
import at.aimon.sandbox.run.RunManager;
import at.aimon.sandbox.util.IdentifierValidator;

/**
 * Executes commands in a persistent sandbox environment.
 *
 * <p>
 * Creates or reuses a sandbox identified by a unique identifier, runs commands sequentially, and extracts artifacts
 * from the {@code /artifacts} directory into the {@link VirtualFileSystem}.
 */
public class RunSandboxTool extends GenericTool<RunSandboxInput, String> {

    public static final String TOOL_NAME = "RunSandbox";

    private static final Logger log = LoggerFactory.getLogger(RunSandboxTool.class);
    private static final String ARTIFACT_BASE_PATH = "/sandbox-artifacts";
    private static final Pattern LOG_PATH_PATTERN = Pattern.compile("^/artifacts/logs/command-\\d+\\.(stdout|stderr)$");
    private static final int LOG_CHUNK_SIZE = 64 * 1024;

    private final SandboxBackend backend;
    private final RunManager runManager;
    private final TarExtractor tarExtractor;
    private final SandboxConfig config;
    private final SandboxLock sandboxLock;
    private final VirtualFileSystem fileSystem;

    /**
     * Creates a new RunSandboxTool.
     *
     * @param backend
     *            the sandbox backend (not null)
     * @param runManager
     *            the run manager (not null)
     * @param tarExtractor
     *            the tar extractor (not null)
     * @param config
     *            the sandbox config (not null)
     * @param sandboxLock
     *            the sandbox lock (not null)
     * @param fileSystem
     *            the virtual file system for artifact extraction (nullable — artifact extraction is skipped when null)
     */
    public RunSandboxTool(SandboxBackend backend, RunManager runManager, TarExtractor tarExtractor,
            SandboxConfig config, SandboxLock sandboxLock, VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "Execute commands in a persistent sandbox environment (container/pod). "
                        + "Creates or reuses a sandbox identified by a unique identifier, "
                        + "runs commands sequentially, and extracts artifacts from /artifacts directory.",
                ToolCategories.EXECUTION, RunSandboxInput.class);
        this.backend = Objects.requireNonNull(backend, "Backend cannot be null");
        this.runManager = Objects.requireNonNull(runManager, "RunManager cannot be null");
        this.tarExtractor = Objects.requireNonNull(tarExtractor, "TarExtractor cannot be null");
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.sandboxLock = Objects.requireNonNull(sandboxLock, "SandboxLock cannot be null");
        this.fileSystem = fileSystem;
    }

    /**
     * Runs the commands in the sandbox, optionally under the per-identifier lock.
     *
     * <p>
     * The shape checks the schema now expresses — {@code identifier} and {@code commands} being present, each command
     * being an object with typed fields — are done by binding. What remains here is the identifier's regex, which a
     * schema cannot state, and the empty-list case, which {@code required} does not cover.
     *
     * @param input
     *            the bound parameters (never null)
     * @param context
     *            the execution context, read for the artifact collector (never null)
     * @return the formatted run report
     * @throws ToolExecutionException
     *             if the identifier is invalid, the command list is empty, or the run fails
     * @throws Exception
     *             if acquiring or releasing the sandbox lock fails
     */
    @Override
    protected String doExecute(RunSandboxInput input, ToolContext context) throws Exception {
        try {
            final String identifier = input.identifier();
            IdentifierValidator.validate(identifier);

            if (input.commands().isEmpty()) {
                throw new ToolExecutionException("Commands must not be empty");
            }
            final List<CommandInput> commands = parseCommands(input.commands());

            final int ttlSeconds = SandboxToolHelper.resolveTtl(input.ttlSeconds(), config);
            final boolean continueOnError = input.continueOnError() != null && input.continueOnError();
            final boolean lockSandbox = SandboxToolHelper.resolveLockSandbox(input.lockSandbox(), config);

            return SandboxToolHelper.withOptionalLock(sandboxLock, identifier, lockSandbox,
                    () -> executeRun(identifier, commands, ttlSeconds, continueOnError, context));
        } catch (ToolExecutionException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            throw new ToolExecutionException("Invalid parameter: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Run failed: {}", e.getMessage(), e);
            throw new ToolExecutionException("Run failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wraps the run report.
     *
     * @param output
     *            the formatted report
     * @return a success result carrying it
     */
    @Override
    protected ToolResult render(String output) {
        return ToolResult.success(output);
    }

    private String executeRun(String identifier, List<CommandInput> commands, int ttlSeconds, boolean continueOnError,
            ToolContext context) throws IOException {
        Sandbox sandbox = backend.ensure(identifier, ttlSeconds);

        SandboxRun run = runManager.createRun(identifier, sandbox.getSandboxId());
        run = runManager.start(run.getRunId());

        boolean failed = false;
        for (int i = 0; i < commands.size(); i++) {
            CommandInput cmd = commands.get(i);
            ExecParams params = buildExecParams(cmd);

            long startMs = System.currentTimeMillis();
            ExecResult execResult;
            try {
                execResult = backend.exec(sandbox.getSandboxId(), params);
            } catch (IOException e) {
                CommandResult cmdResult = CommandResult.builder().index(i).command(cmd.getCommandString()).exitCode(-1)
                        .error(e.getMessage()).durationMs(System.currentTimeMillis() - startMs).build();
                runManager.addCommandResult(run.getRunId(), cmdResult);
                if (!cmd.isAllowFailure() && !continueOnError) {
                    failed = true;
                    break;
                }
                continue;
            }
            long durationMs = System.currentTimeMillis() - startMs;

            CommandResult cmdResult = CommandResult.builder().index(i).command(cmd.getCommandString())
                    .exitCode(execResult.getExitCode()).stdout(execResult.getStdout()).stderr(execResult.getStderr())
                    .durationMs(durationMs).build();
            run = runManager.addCommandResult(run.getRunId(), cmdResult);

            if (execResult.getExitCode() != 0 && !cmd.isAllowFailure()) {
                if (!continueOnError) {
                    failed = true;
                    break;
                }
            }
        }

        writeCommandLogs(sandbox.getSandboxId(), run);

        List<FileArtifact> extractedArtifacts = List.of();
        String artifactsError = null;
        if (fileSystem != null) {
            try (InputStream tarStream = backend.copyArtifacts(sandbox.getSandboxId())) {
                String basePath = ARTIFACT_BASE_PATH + "/" + identifier + "/" + run.getRunId();
                extractedArtifacts = tarExtractor.extract(fileSystem, basePath, tarStream);
            } catch (Exception e) {
                artifactsError = e.getMessage();
                log.debug("Artifacts extraction failed: {}", e.getMessage());
            }
        }

        // Register artifacts with the ArtifactCollector (core pattern)
        final List<FileArtifact> artifacts = extractedArtifacts;
        if (!artifacts.isEmpty()) {
            context.get(ToolContextKeys.ARTIFACT_COLLECTOR).ifPresent(collector -> artifacts.forEach(collector::add));
        }

        long totalBytes = artifacts.stream().mapToLong(FileArtifact::getSize).sum();

        if (failed) {
            run = runManager.fail(run.getRunId(), "Command execution failed");
        } else {
            run = runManager.complete(run.getRunId(), artifacts.size(), totalBytes);
        }

        return RunResultFormatter.format(run, artifactsError);
    }

    private void writeCommandLogs(String sandboxId, SandboxRun run) {
        try {
            backend.exec(sandboxId, ExecParams.builder().command("mkdir -p /artifacts/logs").build());
        } catch (IOException e) {
            log.debug("Failed to create logs directory: {}", e.getMessage());
            return;
        }

        for (int i = 0; i < run.getCommands().size(); i++) {
            CommandResult cmd = run.getCommands().get(i);
            try {
                if (cmd.getStdout() != null && !cmd.getStdout().isEmpty()) {
                    writeLogFile(sandboxId, cmd.getStdout(), "/artifacts/logs/command-" + i + ".stdout");
                }
                if (cmd.getStderr() != null && !cmd.getStderr().isEmpty()) {
                    writeLogFile(sandboxId, cmd.getStderr(), "/artifacts/logs/command-" + i + ".stderr");
                }
            } catch (IOException e) {
                log.debug("Failed to write command log for index {}: {}", i, e.getMessage());
            }
        }
    }

    private void writeLogFile(String sandboxId, String content, String path) throws IOException {
        if (!LOG_PATH_PATTERN.matcher(path).matches()) {
            log.warn("Rejected invalid log path: {}", path);
            return;
        }

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);

        if (contentBytes.length <= LOG_CHUNK_SIZE) {
            String encoded = Base64.getEncoder().encodeToString(contentBytes);
            backend.exec(sandboxId,
                    ExecParams.builder().command("echo '" + encoded + "' | base64 -d > '" + path + "'").build());
        } else {
            // Write first chunk (truncate mode)
            String firstChunk = Base64.getEncoder().encodeToString(Arrays.copyOfRange(contentBytes, 0, LOG_CHUNK_SIZE));
            backend.exec(sandboxId,
                    ExecParams.builder().command("echo '" + firstChunk + "' | base64 -d > '" + path + "'").build());

            // Append remaining chunks
            for (int offset = LOG_CHUNK_SIZE; offset < contentBytes.length; offset += LOG_CHUNK_SIZE) {
                int end = Math.min(offset + LOG_CHUNK_SIZE, contentBytes.length);
                String chunk = Base64.getEncoder().encodeToString(Arrays.copyOfRange(contentBytes, offset, end));
                backend.exec(sandboxId,
                        ExecParams.builder().command("echo '" + chunk + "' | base64 -d >> '" + path + "'").build());
            }
        }
    }

    /**
     * Turns the bound command specs into the domain type.
     *
     * <p>
     * Every {@code instanceof} that used to guard these reads is gone: binding already rejected a command whose
     * {@code argv} was not an array of strings, naming the offending position. What is left is the null check that
     * distinguishes "not supplied" from a supplied value, which the builder needs either way.
     */
    private List<CommandInput> parseCommands(List<RunSandboxInput.CommandSpec> specs) {
        List<CommandInput> commands = new ArrayList<>();
        for (RunSandboxInput.CommandSpec spec : specs) {
            CommandInput.Builder builder = CommandInput.builder();

            if (spec.shell() != null) {
                builder.shell(spec.shell());
            }
            if (spec.argv() != null) {
                builder.argv(spec.argv());
            }
            if (spec.cwd() != null) {
                builder.cwd(spec.cwd());
            }
            if (spec.timeoutMs() != null) {
                builder.timeoutMs(spec.timeoutMs());
            }
            if (spec.allowFailure() != null) {
                builder.allowFailure(spec.allowFailure());
            }

            commands.add(builder.build());
        }
        return commands;
    }

    private ExecParams buildExecParams(CommandInput cmd) {
        ExecParams.Builder builder = ExecParams.builder().command(cmd.getCommandString());

        if (cmd.getCwd() != null) {
            builder.cwd(cmd.getCwd());
        }
        if (!cmd.getEnv().isEmpty()) {
            builder.env(cmd.getEnv());
        }
        if (cmd.getTimeoutMs() != null) {
            builder.timeoutMs(Math.min(cmd.getTimeoutMs(), config.getMaxCommandTimeoutMs()));
        } else {
            builder.timeoutMs(config.getDefaultCommandTimeoutMs());
        }

        return builder.build();
    }

}
