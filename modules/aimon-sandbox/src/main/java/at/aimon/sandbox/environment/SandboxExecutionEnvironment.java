package at.aimon.sandbox.environment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.VirtualShell;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.UnannouncedReset;

/**
 * One execution's view of its sandbox (docs/design/workspace-sandbox.md §7): a {@link SandboxBinding} plus the file
 * system, shell, staging and content search that act on it. It holds no remote resource — core's contract makes an
 * environment a view with nothing to close — and provisions nothing until the first file or shell call.
 *
 * <p>
 * Environment-level facts — a reset, a re-creation, a lost sandbox, a workspace switch — reach the model through
 * {@link #drainNotices()}, which core calls after every model-facing tool call (kangwoo/aimon-core#254). Each instance
 * has one queue of its own, never shared with a fork (core EE-107). A session's root environment also keeps a reset on
 * the session's options record until a drain delivers it, so a reset that a hook, a workflow step or a staging call
 * caused reaches the model on the session's next tool result, a turn later if need be
 * (docs/design/workspace-sandbox-unannounced-reset.md). Facts about one command — it was killed, its working directory
 * was gone — stay on that command's result.
 */
public final class SandboxExecutionEnvironment implements ExecutionEnvironment {

    private final SandboxBinding binding;
    private final EnvironmentDescriptor descriptor;
    private final SandboxFileSystem fileSystem;
    private final SandboxShell shell;
    private final SandboxStaging staging;
    private final SandboxContentSearch contentSearch;
    private final Duration backgroundCommandTimeout;
    private final PendingNotices pending;

    SandboxExecutionEnvironment(SandboxBinding binding, EnvironmentDescriptor descriptor,
            Duration backgroundCommandTimeout, SandboxWorkspaceManager manager, SandboxConnectionCache connections,
            SandboxSettings settings, Clock clock) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.backgroundCommandTimeout = Objects.requireNonNull(backgroundCommandTimeout,
                "backgroundCommandTimeout must not be null");
        // §6.4 of the management design: the switch is announced after the first successful connect, and recorded
        // once core drained the notice. A reset is kept on the session's record before its notice is queued, and
        // cleared once core drained it (docs/design/workspace-sandbox-unannounced-reset.md §6). The drain's writes go
        // to the manager's background executor: a drain must not wait on the options store.
        this.pending = new PendingNotices(binding.workspaceSwitch().orElse(null),
                binding.sessionBookkeeping().orElse(null), new NoticeLedger() {
                    @Override
                    public Optional<SessionSandboxOptions> markResetUnannounced(SessionId session,
                            SandboxWorkspaceId workspace, String slot, OptionalLong switchVersion,
                            Optional<SandboxWorkspaceId> lastWorkspace) {
                        return manager.markResetUnannounced(session, workspace, slot, switchVersion, lastWorkspace,
                                binding.caller());
                    }

                    @Override
                    public void acknowledge(SessionId session, OptionalLong expectedVersion,
                            Optional<SandboxWorkspaceId> lastWorkspace, Optional<UnannouncedReset> delivered) {
                        manager.acknowledgeLater(session, expectedVersion, lastWorkspace, delivered, binding.caller());
                    }
                });
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
     * @return the declared profile's {@code backgroundCommandTimeout}, always present (24 hours unless the profile
     *         configures one, §5.3)
     */
    @Override
    public Optional<Duration> backgroundCommandTimeout() {
        return Optional.of(backgroundCommandTimeout);
    }

    /**
     * @return the notices queued since the last drain, oldest first — each returned once, and recorded as delivered
     *         (a workspace switch among them is recorded as the session's {@code lastWorkspaceId}, and a reset among
     *         them is cleared from the session's options record)
     */
    @Override
    public List<String> drainNotices() {
        return pending.drain();
    }

    @Override
    public String toString() {
        return "SandboxExecutionEnvironment{" + binding + '}';
    }
}
