package at.aimon.sandbox.environment;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.ForkDefinition;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.binding.BindingContext;
import at.aimon.sandbox.binding.BindingRejectedException;
import at.aimon.sandbox.binding.CallerResolver;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.binding.SandboxBindingPolicy;
import at.aimon.sandbox.binding.ShellKey;
import at.aimon.sandbox.binding.SlotChoice;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * aimon-core's {@link ExecutionEnvironmentProvider}, answered with sandboxes (docs/design/workspace-sandbox.md §7).
 *
 * <p>
 * {@link #resolve} makes no provider call and at most one store read: it binds the request (§8) and returns a
 * {@link SandboxExecutionEnvironment} whose descriptor carries the profile's <i>declared</i> platform, OS and shell,
 * so a turn that runs no command provisions nothing and the prompt does not change between generations. A binding that
 * cannot be made is thrown as core's {@code ExecutionEnvironmentUnavailableException}; core then installs an
 * unavailable environment, and the tools fail with the reason — never on the host (§12.1).
 *
 * <p>
 * A fork — {@code request.fork().isPresent()}, never judged by {@code parent} (§8.1) — inherits its parent's
 * workspace, owner and root, gets {@code exec:{executionId}} as its shell key, and asks only the policy's
 * {@code forkSlot}. Its caller is its own principal through the same gate as a root request — a fork without one
 * never inherits its parent's caller. A fork without a parent environment, of an unavailable one or of another
 * provider's is unavailable too, carrying the parent's cause where there is one (§8.2).
 *
 * <p>
 * <b>{@code bindRuntime} is not overridden</b>: this provider keeps nothing per {@code AgentRuntime}. Workspaces are
 * keyed by session or execution, connections by sandbox, shell locks by sandbox and shell key; the runtime id is only
 * passed to the binding policy. The inherited {@code RuntimeBinding.NONE} therefore meets core's contract by
 * construction — closing it stops no command and releases nothing another binding uses, and {@link #resolve} never
 * asks whether an id was bound. Sandboxes end with their workspace (close, idle policy, janitor, provider expiry),
 * not with a runtime (§3.2). Anything later keyed by runtime id must come with a real {@code bindRuntime} whose
 * {@code close()} leaves running commands alone.
 */
public final class SandboxExecutionEnvironmentProvider implements ExecutionEnvironmentProvider {

    private static final String UNAVAILABLE_PREFIX = "Execution environment unavailable: ";

    private final SandboxBindingPolicy policy;
    private final CallerResolver callers;
    private final SandboxWorkspaceManager manager;
    private final SandboxConnectionCache connections;
    private final SandboxProfileRegistry profiles;
    private final SandboxWorkspaceStore store;
    private final SandboxSettings settings;
    private final Clock clock;

    private SandboxExecutionEnvironmentProvider(Builder builder) {
        this.policy = Objects.requireNonNull(builder.policy, "policy must not be null");
        this.callers = Objects.requireNonNull(builder.callers, "callers must not be null");
        this.manager = Objects.requireNonNull(builder.manager, "manager must not be null");
        this.connections = Objects.requireNonNull(builder.connections, "connections must not be null");
        this.profiles = Objects.requireNonNull(builder.profiles, "profiles must not be null");
        this.store = Objects.requireNonNull(builder.store, "store must not be null");
        this.settings = Objects.requireNonNull(builder.settings, "settings must not be null");
        this.clock = Objects.requireNonNull(builder.clock, "clock must not be null");
    }

    /**
     * For tests and custom assemblies. <b>Runs no startup validation</b>: build through {@code WorkspaceSandbox}, which
     * refuses the settings this version cannot honour (§13.2) — a hand-wired provider would silently ignore
     * {@code pause-after}, {@code seed}, shared access and credentials.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ExecutionEnvironment resolve(EnvironmentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final SandboxBinding binding = request.fork().isPresent()
                ? bindFork(request, request.fork().get())
                : bindRoot(request);
        if (!SandboxBinding.PRIMARY.equals(binding.slot())) {
            throw new BindingRejectedException("sandbox slot '" + binding.slot() + "' is not available: only the "
                    + "primary slot is supported until more slots arrive (implementation step 5)");
        }
        final SandboxProfile profile = declaredProfile(binding);
        return new SandboxExecutionEnvironment(binding, descriptor(binding, profile),
                profile.backgroundCommandTimeout(), manager, connections, settings, clock);
    }

    /**
     * The policy chooses the workspace, owner, slot and root; the caller is always the request's principal through
     * the assembly's gate, whatever the policy set. The manager's owner check is the second tenant line (§8.3): a
     * policy that hands out another tenant's workspace, or names its owner as the caller, is still stopped there.
     */
    private SandboxBinding bindRoot(EnvironmentRequest request) {
        return policy.bind(BindingContext.from(request)).toBuilder().caller(callers.callerOf(request.principal()))
                .build();
    }

    private SandboxBinding bindFork(EnvironmentRequest request, ForkDefinition fork) {
        final ExecutionEnvironment parent = request.parent().orElseThrow(() -> new BindingRejectedException(
                "this fork has no parent environment, so it cannot share a sandbox workspace (EE-30)"));
        if (parent instanceof UnavailableExecutionEnvironment unavailable) {
            final String message = unavailable.message();
            throw new BindingRejectedException(
                    message.startsWith(UNAVAILABLE_PREFIX) ? message.substring(UNAVAILABLE_PREFIX.length()) : message);
        }
        if (!(parent instanceof SandboxExecutionEnvironment sandbox)) {
            throw new BindingRejectedException(
                    "the parent environment is not a sandbox environment, so this fork " + "cannot use a sandbox");
        }
        final ExecutionId executionId = request.executionId()
                .orElseThrow(() -> new BindingRejectedException("this fork has no execution id"));
        final SandboxBinding parentBinding = sandbox.binding();
        final SlotChoice choice = policy.forkSlot(parentBinding, fork);
        // Same slot: the parent's root, whatever the policy chose for it (§8.2). Another slot is another sandbox, whose
        // root is the default one.
        final String root = choice.slot().equals(parentBinding.slot())
                ? parentBinding.root()
                : SandboxBinding.DEFAULT_ROOT;
        // The fork's own principal, through the same gate as a root request: every fork path of the supported cores
        // (0.3.1+) forwards the principal its parent was resolved with, so a fork without one acts as a root without
        // one would — refused under require-principal, anonymous otherwise — and the owner check still decides
        // whether that caller may use the parent's workspace (§8.2, §8.3). It never inherits the parent's caller.
        final WorkspaceOwner caller = callers.callerOf(request.principal());
        return parentBinding.toBuilder().caller(caller).slot(choice.slot())
                .requiredProfile(choice.requiredProfile().orElse(null)).shellKey(ShellKey.execution(executionId))
                .root(root).build();
    }

    /**
     * The profile the descriptor declares: the required one, else the slot's (one store read), else the default. The
     * background ceiling is read from it too, while the heartbeat limit it defaults to is applied from the connected
     * slot's profile; the two are the same profile on every path that runs a command, because {@code connect}
     * refuses a slot whose profile differs from the required one or was removed.
     */
    private SandboxProfile declaredProfile(SandboxBinding binding) {
        final Optional<String> required = binding.requiredProfile();
        if (required.isPresent()) {
            return profiles.find(required.get()).orElseThrow(() -> new BindingRejectedException(
                    "the definition requires sandbox profile '" + required.get() + "', which is not configured"));
        }
        // A slot whose profile was removed by a redeploy is refused by connect(); the prompt still needs values.
        return store.find(binding.workspaceId()).flatMap(workspace -> workspace.slot(binding.slot()))
                .flatMap(slot -> profiles.find(slot.profile()))
                .orElseGet(() -> profiles.find(profiles.defaultProfileName()).orElseThrow());
    }

    /**
     * The descriptor of §7: declared platform, OS and shell, {@code workingDirectory = root}, and notes that tell the
     * model it is isolated, what egress it has, how relative paths resolve and what shell state persists.
     */
    static EnvironmentDescriptor descriptor(SandboxBinding binding, SandboxProfile profile) {
        final String egress = profile.egress()
                .map(allowed -> allowed.isEmpty()
                        ? "network egress is blocked"
                        : "network egress is limited to " + String.join(", ", allowed))
                .orElse("the profile sets no egress policy");
        final String notes = String.join("; ",
                List.of("isolated sandbox (profile '" + profile.name() + "')", egress,
                        "file tools resolve relative paths against " + binding.root() + "; prefer absolute paths",
                        "shell state persists cwd and exported variables only"));
        return EnvironmentDescriptor.builder().workingDirectory(binding.root()).platform(profile.platform())
                .osVersion(profile.osVersion().orElse(null)).shellName(profile.shellName()).notes(notes).build();
    }

    /**
     * Builder for {@link SandboxExecutionEnvironmentProvider}; {@code WorkspaceSandbox} is the usual way to get one.
     */
    public static final class Builder {
        private SandboxBindingPolicy policy;
        private CallerResolver callers;
        private SandboxWorkspaceManager manager;
        private SandboxConnectionCache connections;
        private SandboxProfileRegistry profiles;
        private SandboxWorkspaceStore store;
        private SandboxSettings settings;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        public Builder policy(SandboxBindingPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder callers(CallerResolver callers) {
            this.callers = callers;
            return this;
        }

        public Builder manager(SandboxWorkspaceManager manager) {
            this.manager = manager;
            return this;
        }

        public Builder connections(SandboxConnectionCache connections) {
            this.connections = connections;
            return this;
        }

        public Builder profiles(SandboxProfileRegistry profiles) {
            this.profiles = profiles;
            return this;
        }

        public Builder store(SandboxWorkspaceStore store) {
            this.store = store;
            return this;
        }

        public Builder settings(SandboxSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public SandboxExecutionEnvironmentProvider build() {
            return new SandboxExecutionEnvironmentProvider(this);
        }
    }
}
