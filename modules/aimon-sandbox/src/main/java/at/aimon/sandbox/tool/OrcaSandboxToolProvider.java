package at.aimon.sandbox.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.sandbox.artifact.TarCreator;
import at.aimon.sandbox.artifact.TarExtractor;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.run.RunManager;
import at.aimon.sandbox.run.RunStore;

/**
 * Provides sandbox tools to the Orca agent system.
 *
 * <p>
 * Registers {@link RunSandboxTool}, {@link DeleteSandboxTool}, {@link RestartSandboxTool}, and
 * {@link CopyToSandboxTool} with the tool registry.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * OrcaSandboxToolProvider provider = new OrcaSandboxToolProvider(backend, config, runStore, sandboxLock);
 * provider.registerTools(toolRegistry, toolProviderContext);
 * }
 * </pre>
 */
public class OrcaSandboxToolProvider implements OrcaToolProvider {

    private final SandboxBackend backend;
    private final SandboxConfig config;
    private final RunStore runStore;
    private final SandboxLock sandboxLock;

    public OrcaSandboxToolProvider(SandboxBackend backend, SandboxConfig config, RunStore runStore,
            SandboxLock sandboxLock) {
        this.backend = Objects.requireNonNull(backend, "Backend cannot be null");
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.runStore = Objects.requireNonNull(runStore, "RunStore cannot be null");
        this.sandboxLock = Objects.requireNonNull(sandboxLock, "SandboxLock cannot be null");
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "Registry must not be null");
        Objects.requireNonNull(context, "Context must not be null");

        RunManager runManager = new RunManager(runStore);
        TarExtractor tarExtractor = new TarExtractor();
        TarCreator tarCreator = new TarCreator();
        VirtualFileSystem fileSystem = context.getFileSystem(); // nullable

        registry.register(new RunSandboxTool(backend, runManager, tarExtractor, config, sandboxLock, fileSystem));
        registry.register(new DeleteSandboxTool(backend, sandboxLock));
        registry.register(new RestartSandboxTool(backend, config, sandboxLock));
        registry.register(new CopyToSandboxTool(backend, tarCreator, config, sandboxLock, fileSystem));
    }
}
