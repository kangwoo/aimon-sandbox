package at.aimon.sandbox;

import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillHookSetParser;
import at.aimon.core.skill.render.ShellArgumentTokenizer;
import at.aimon.sandbox.binding.CallerResolver;
import at.aimon.sandbox.binding.DefaultSandboxBindingPolicy;
import at.aimon.sandbox.binding.SandboxBindingPolicy;
import at.aimon.sandbox.binding.SandboxTenantResolver;
import at.aimon.sandbox.binding.SessionOwnerLookup;
import at.aimon.sandbox.environment.SandboxExecutionEnvironmentProvider;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.workspace.DefaultSandboxAdmission;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SandboxAdmission;
import at.aimon.sandbox.workspace.SandboxEventListener;
import at.aimon.sandbox.workspace.SandboxJanitor;
import at.aimon.sandbox.workspace.SandboxScheduler;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;

/**
 * The assembly: builds, validates and closes the application-scoped singletons of docs/design/workspace-sandbox.md
 * §3.2 in one place, since this module does not assume a DI container.
 *
 * <pre>{@code
 * WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
 *         .settings(settings).provider(provider)       // provider borrowed unless ownProvider(true)
 *         .tenantResolver(...).sessionOwnerLookup(...)  // required with require-principal
 *         .build();                                     // SandboxConfigurationException on any §13.2 violation
 * runtimeBuilder.executionEnvironmentProvider(sandbox.environmentProvider());
 * sandbox.janitor().start();
 * ...
 * coreStack.close();                                    // first: it stops background commands through their shells
 * sandbox.close();
 * }</pre>
 *
 * <p>
 * <b>Close order.</b> Close aimon-core's stack (or its runtimes) before this assembly. Core's shutdown stops the
 * background commands still running by signalling them through their shells, and a sandbox shell reaches its command
 * over a connection this assembly closes: closed first, the stop requests fail and the commands end only with their
 * sandboxes.
 *
 * <p>
 * Skills may be parsed with {@link #markdownSkillParser()} (or {@link #skillHookSetParser()}) to refuse
 * skill-declared shell hooks altogether: a skill that declares one is then not loaded. Without it, aimon-core 0.3.1
 * and later run such a hook in the execution's sandbox shell — never on the host — and a guard whose sandbox is
 * unavailable blocks what it guards (§12.1). The assembly also recommends against registering core's
 * {@code GitStatusContextProvider} and {@code DirectorySummaryContextProvider}: they read the filesystem every turn
 * and so would provision a sandbox for turns that run no command (§11.1).
 */
public final class WorkspaceSandbox implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSandbox.class);

    private final SandboxSettings settings;
    private final SandboxProvider provider;
    private final boolean ownProvider;
    private final SandboxScheduler scheduler;
    private final SandboxScheduler janitorScheduler;
    private final boolean ownScheduler;
    private final SandboxWorkspaceStore store;
    private final SandboxProfileRegistry profiles;
    private final SandboxConnectionCache connections;
    private final SandboxWorkspaceManager manager;
    private final SandboxJanitor janitor;
    private final SandboxExecutionEnvironmentProvider environmentProvider;

    private WorkspaceSandbox(Builder builder) {
        this.settings = Objects.requireNonNull(builder.settings, "settings must not be null");
        this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
        SandboxStartupValidator.validate(settings, new SandboxStartupValidator.Wiring(provider.capabilities(),
                builder.tenantResolver != null, builder.sessionOwnerLookup != null));
        this.ownProvider = builder.ownProvider;
        this.ownScheduler = builder.scheduler == null;
        // The janitor gets a thread of its own: one pass can wait up to close-wait per closing workspace, and the
        // heartbeats that keep running commands alive must not queue behind it.
        this.scheduler = ownScheduler ? SandboxScheduler.daemon("aimon-sandbox-heartbeat", 2) : builder.scheduler;
        this.janitorScheduler = ownScheduler ? SandboxScheduler.daemon("aimon-sandbox-janitor", 1) : builder.scheduler;
        try {
            this.store = builder.store != null ? builder.store : new InMemorySandboxWorkspaceStore();
            this.profiles = new SandboxProfileRegistry(settings.profiles(), settings.defaultProfile());
            final Clock clock = builder.clock;
            this.connections = new SandboxConnectionCache(provider, clock);
            final CallerResolver callers = new CallerResolver(settings,
                    builder.tenantResolver != null ? builder.tenantResolver : SandboxTenantResolver.SINGLE_TENANT);
            final SandboxAdmission admission = builder.admission != null
                    ? builder.admission
                    : settings.maxRunningPerTenant() == SandboxSettings.UNLIMITED
                            ? SandboxAdmission.UNLIMITED
                            : new DefaultSandboxAdmission(store, settings.maxRunningPerTenant());
            this.manager = SandboxWorkspaceManager.builder().settings(settings).profiles(profiles).provider(provider)
                    .store(store).connections(connections).admission(admission).events(builder.eventListener)
                    .callers(callers).clock(clock).scheduler(scheduler).build();
            this.janitor = new SandboxJanitor(manager, janitorScheduler);
            final SandboxBindingPolicy policy = builder.bindingPolicy != null
                    ? builder.bindingPolicy
                    : new DefaultSandboxBindingPolicy(callers, profiles, builder.sessionOwnerLookup);
            this.environmentProvider = SandboxExecutionEnvironmentProvider.builder().policy(policy).callers(callers)
                    .manager(manager).connections(connections).profiles(profiles).store(store).settings(settings)
                    .clock(clock).build();
        } catch (RuntimeException e) {
            // Nothing is returned to close: the threads this constructor started must not outlive the failure.
            closeSchedulers();
            throw e;
        }
        log.info("Workspace sandbox ready: deployment={}, node={}, profiles={}, default={}", settings.deployment(),
                settings.nodeId(), profiles.all().keySet(), settings.defaultProfile());
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The stricter hook parser, for a deployment that wants no skill-declared shell code at all: shell actions are
     * refused at parse time, so a skill declaring one does not load (§12.1).
     *
     * @return a parser wired to {@link NoOpShellActionExecutor}
     */
    public static SkillHookSetParser skillHookSetParser() {
        return new SkillHookSetParser(NoOpShellActionExecutor.INSTANCE);
    }

    /** @return a skill parser wired with {@link #skillHookSetParser()} */
    public static MarkdownSkillParser markdownSkillParser() {
        return new MarkdownSkillParser(new ShellArgumentTokenizer(), skillHookSetParser());
    }

    /** @return the provider to hand to aimon-core's runtime */
    public SandboxExecutionEnvironmentProvider environmentProvider() {
        return environmentProvider;
    }

    /** @return the manager ({@code close} and {@code reopen} for the application) */
    public SandboxWorkspaceManager manager() {
        return manager;
    }

    /** @return the janitor; call {@link SandboxJanitor#start()} to run it */
    public SandboxJanitor janitor() {
        return janitor;
    }

    /** @return the workspace store */
    public SandboxWorkspaceStore store() {
        return store;
    }

    /** @return the configured profiles */
    public SandboxProfileRegistry profiles() {
        return profiles;
    }

    /** @return the settings */
    public SandboxSettings settings() {
        return settings;
    }

    /** @return the node-local connection cache */
    public SandboxConnectionCache connections() {
        return connections;
    }

    /**
     * Stops the janitor, closes cached connections, and closes the scheduler and provider when this assembly owns
     * them. Sandboxes are not touched: they live as long as their workspaces (§3.2).
     */
    @Override
    public void close() {
        janitor.close();
        connections.close();
        closeSchedulers();
        if (ownProvider) {
            provider.close();
        }
    }

    private void closeSchedulers() {
        if (ownScheduler) {
            scheduler.close();
            janitorScheduler.close();
        }
    }

    /** @return the scheduler heartbeats run on */
    SandboxScheduler heartbeatScheduler() {
        return scheduler;
    }

    /** @return the scheduler the janitor runs on: its own unless one scheduler was supplied for both */
    SandboxScheduler janitorScheduler() {
        return janitorScheduler;
    }

    /** Builder for {@link WorkspaceSandbox}. */
    public static final class Builder {
        private SandboxSettings settings;
        private SandboxProvider provider;
        private boolean ownProvider;
        private SandboxWorkspaceStore store;
        private SandboxTenantResolver tenantResolver;
        private SessionOwnerLookup sessionOwnerLookup;
        private SandboxAdmission admission;
        private SandboxEventListener eventListener = SandboxEventListener.NOOP;
        private SandboxBindingPolicy bindingPolicy;
        private Clock clock = Clock.systemUTC();
        private SandboxScheduler scheduler;

        private Builder() {
        }

        public Builder settings(SandboxSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder provider(SandboxProvider provider) {
            this.provider = provider;
            return this;
        }

        /** Whether {@link WorkspaceSandbox#close()} closes the provider too (default: borrowed). */
        public Builder ownProvider(boolean ownProvider) {
            this.ownProvider = ownProvider;
            return this;
        }

        /**
         * Default: a new {@link InMemorySandboxWorkspaceStore} — <b>single node only</b>: the janitor's reconciliation
         * destroys every sandbox of the deployment its store has no record of, so two nodes with an in-memory store
         * each
         * would reclaim each other's sandboxes as orphans (docs/design/workspace-sandbox.md §5.3, §10.4).
         */
        public Builder store(SandboxWorkspaceStore store) {
            this.store = store;
            return this;
        }

        /** Required with {@code require-principal}; default: everyone in one tenant. */
        public Builder tenantResolver(SandboxTenantResolver tenantResolver) {
            this.tenantResolver = tenantResolver;
            return this;
        }

        /** Required with {@code require-principal}; default: none, a main turn's caller owns its workspace. */
        public Builder sessionOwnerLookup(SessionOwnerLookup sessionOwnerLookup) {
            this.sessionOwnerLookup = sessionOwnerLookup;
            return this;
        }

        /** Default: {@link DefaultSandboxAdmission} with {@code max-running-per-tenant} — never allow-all. */
        public Builder admission(SandboxAdmission admission) {
            this.admission = admission;
            return this;
        }

        public Builder eventListener(SandboxEventListener eventListener) {
            this.eventListener = Objects.requireNonNull(eventListener, "eventListener must not be null");
            return this;
        }

        /** Default: {@link DefaultSandboxBindingPolicy}. */
        public Builder bindingPolicy(SandboxBindingPolicy bindingPolicy) {
            this.bindingPolicy = bindingPolicy;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * Default: daemon schedulers owned (and closed) by the assembly, one for heartbeats and one for the janitor.
         * A supplied scheduler runs both.
         */
        public Builder scheduler(SandboxScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * @return the assembly
         * @throws SandboxConfigurationException
         *             listing every startup violation (§13.2)
         */
        public WorkspaceSandbox build() {
            return new WorkspaceSandbox(this);
        }
    }
}
