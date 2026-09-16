package at.aimon.sandbox.tool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.sandbox.artifact.TarCreator;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.util.ByteFormatUtils;
import at.aimon.sandbox.util.IdentifierValidator;

/**
 * Copies files from the {@link VirtualFileSystem} into a sandbox environment.
 *
 * <p>
 * This is the reverse direction of artifact extraction in {@link RunSandboxTool}: while RunSandboxTool copies files
 * from
 * sandbox to VFS, CopyToSandboxTool copies files from VFS to sandbox.
 *
 * <p>
 * Files are packaged into a tar archive via {@link TarCreator} and transferred to the sandbox using
 * {@link SandboxBackend#copyToSandbox(String, java.io.InputStream, String)}.
 */
public class CopyToSandboxTool extends GenericTool<CopyToSandboxInput, String> {

    public static final String TOOL_NAME = "CopyToSandbox";

    private static final Logger log = LoggerFactory.getLogger(CopyToSandboxTool.class);
    private static final String DEFAULT_DEST_PATH = "/workspace";

    private final SandboxBackend backend;
    private final TarCreator tarCreator;
    private final SandboxConfig config;
    private final SandboxLock sandboxLock;
    private final VirtualFileSystem fileSystem;

    /**
     * Creates a new CopyToSandboxTool.
     *
     * @param backend
     *            the sandbox backend (not null)
     * @param tarCreator
     *            the tar creator (not null)
     * @param config
     *            the sandbox config (not null)
     * @param sandboxLock
     *            the sandbox lock (not null)
     * @param fileSystem
     *            the virtual file system to read files from (nullable — returns error when null)
     */
    public CopyToSandboxTool(SandboxBackend backend, TarCreator tarCreator, SandboxConfig config,
            SandboxLock sandboxLock, VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "Copy files from the virtual file system into a sandbox environment. "
                        + "Packages specified VFS files into a tar archive and transfers them "
                        + "to the sandbox at the given destination path.",
                ToolCategories.EXECUTION, CopyToSandboxInput.class);
        this.backend = Objects.requireNonNull(backend, "Backend cannot be null");
        this.tarCreator = Objects.requireNonNull(tarCreator, "TarCreator cannot be null");
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.sandboxLock = Objects.requireNonNull(sandboxLock, "SandboxLock cannot be null");
        this.fileSystem = fileSystem;
    }

    @Override
    protected String doExecute(CopyToSandboxInput input, ToolContext context) throws Exception {
        try {
            if (fileSystem == null) {
                throw new ToolExecutionException("VirtualFileSystem is not available. Cannot copy files to sandbox.");
            }

            String identifier = input.identifier();
            IdentifierValidator.validate(identifier);

            if (input.files().isEmpty()) {
                throw new ToolExecutionException("Files list must not be empty");
            }
            List<TarCreator.FileEntry> entries = parseFileEntries(input.files());

            String destPath = input.destPath() != null ? input.destPath() : DEFAULT_DEST_PATH;
            int ttlSeconds = SandboxToolHelper.resolveTtl(input.ttlSeconds(), config);
            boolean lockSandbox = SandboxToolHelper.resolveLockSandbox(input.lockSandbox(), config);

            return SandboxToolHelper.withOptionalLock(sandboxLock, identifier, lockSandbox,
                    () -> executeCopy(identifier, entries, destPath, ttlSeconds));
        } catch (ToolExecutionException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            throw new ToolExecutionException("Invalid parameter: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Copy to sandbox failed: {}", e.getMessage(), e);
            throw new ToolExecutionException("Copy to sandbox failed: " + e.getMessage(), e);
        }
    }

    @Override
    protected ToolResult render(String output) {
        return ToolResult.success(output);
    }

    private String executeCopy(String identifier, List<TarCreator.FileEntry> entries, String destPath, int ttlSeconds)
            throws IOException {
        Sandbox sandbox = backend.ensure(identifier, ttlSeconds);

        byte[] tarBytes = tarCreator.create(fileSystem, entries);
        log.debug("Created tar archive: {} bytes, {} files", tarBytes.length, entries.size());

        backend.copyToSandbox(sandbox.getSandboxId(), new ByteArrayInputStream(tarBytes), destPath);

        return formatResult(entries.size(), tarBytes.length, destPath, identifier);
    }

    private List<TarCreator.FileEntry> parseFileEntries(List<CopyToSandboxInput.FileEntrySpec> specs) {
        List<TarCreator.FileEntry> entries = new ArrayList<>();
        for (CopyToSandboxInput.FileEntrySpec spec : specs) {
            // An empty dest_name still falls back to the source's own file name: the model sometimes sends "" to mean
            // "no override", and binding cannot tell that apart from a deliberate empty name.
            String archiveName = spec.destName() != null && !spec.destName().isEmpty()
                    ? spec.destName()
                    : extractFileName(spec.source());
            entries.add(new TarCreator.FileEntry(spec.source(), archiveName));
        }
        return entries;
    }

    private String extractFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    private String formatResult(int fileCount, long totalBytes, String destPath, String identifier) {
        return "Copied " + fileCount + " file(s) (" + ByteFormatUtils.formatBytes(totalBytes) + ") to sandbox '"
                + identifier + "' at " + destPath;
    }
}
