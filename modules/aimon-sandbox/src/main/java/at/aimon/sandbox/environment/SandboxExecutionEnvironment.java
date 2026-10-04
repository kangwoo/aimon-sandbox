package at.aimon.sandbox.environment;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.VirtualShell;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * One execution's view of its sandbox (docs/design/workspace-sandbox.md §7): a {@link SandboxBinding} plus the file
 * system, shell, staging and content search that act on it. It holds no remote resource — core's contract makes an
 * environment a view with nothing to close — and provisions nothing until the first file or shell call.
 */
public final class SandboxExecutionEnvironment implements ExecutionEnvironment {

    private final SandboxBinding binding;
    private final EnvironmentDescriptor descriptor;
    private final SandboxFileSystem fileSystem;
    private final SandboxShell shell;
    private final SandboxStaging staging;
    private final SandboxContentSearch contentSearch;
    private final Duration backgroundCommandTimeout;

    SandboxExecutionEnvironment(SandboxBinding binding, EnvironmentDescriptor descriptor,
            Duration backgroundCommandTimeout, SandboxWorkspaceManager manager, SandboxConnectionCache connections,
            SandboxSettings settings, Clock clock) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.backgroundCommandTimeout = Objects.requireNonNull(backgroundCommandTimeout,
                "backgroundCommandTimeout must not be null");
        final PendingNotices pending = new PendingNotices();
        this.fileSystem = new SandboxFileSystem(binding, manager, pending);
        this.shell = new SandboxShell(binding, manager, connections, settings, pending, clock);
        this.staging = new SandboxStaging(binding, manager, connections, pending, fileSystem);
        this.contentSearch = new SandboxContentSearch(binding, manager, fileSystem, pending);
    }

    /** @return the binding this environment acts on (read by the orchestrator tools of implementation step 5) */
    public SandboxBinding binding() {
        return binding;
    }

    @Override
    public VirtualFileSystem fileSystem() {
        return fileSystem;
    }

    @Override
    public VirtualShell shell() {
        return shell;
    }

    /** @return the profile's declared values, never read from the image (§7) */
    @Override
    public EnvironmentDescriptor descriptor() {
        return descriptor;
    }

    /** @return {@code false}: {@code /workspace} dies with the sandbox, so core copies artifacts out (§11.5) */
    @Override
    public boolean durable() {
        return false;
    }

    @Override
    public String stage(StagedResource resource) {
        return staging.stage(resource);
    }

    /**
     * @return empty: implementation step 3 has no sandbox worktrees, so core refuses a workflow isolation branch
     *         (step 7 adds them, §11.2)
     */
    @Override
    public Optional<ExecutionEnvironment> isolate(String branchKey) {
        return Optional.empty();
    }

    @Override
    public Optional<ContentSearch> contentSearch() {
        return Optional.of(contentSearch);
    }

    /**
     * @return the declared profile's {@code backgroundCommandTimeout}, always present: a background command ends there
     *         rather than outliving the time it may keep the sandbox awake (§5.3)
     */
    @Override
    public Optional<Duration> backgroundCommandTimeout() {
        return Optional.of(backgroundCommandTimeout);
    }

    @Override
    public String toString() {
        return "SandboxExecutionEnvironment{" + binding + '}';
    }
}
