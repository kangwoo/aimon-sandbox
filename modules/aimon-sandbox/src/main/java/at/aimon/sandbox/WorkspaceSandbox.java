package at.aimon.sandbox;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionLifecycleListener;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.sandbox.binding.CallerResolver;
import at.aimon.sandbox.binding.DefaultSandboxBindingPolicy;
import at.aimon.sandbox.binding.SandboxBindingPolicy;
import at.aimon.sandbox.binding.SandboxTenantResolver;
import at.aimon.sandbox.binding.SessionOptionsBindingPolicy;
import at.aimon.sandbox.binding.SessionOwnerLookup;
import at.aimon.sandbox.environment.SandboxExecutionEnvironmentProvider;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.workspace.DefaultSandboxAdmission;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.SandboxAdmission;
import at.aimon.sandbox.workspace.SandboxEventListener;
import at.aimon.sandbox.workspace.SandboxJanitor;
import at.aimon.sandbox.workspace.SandboxScheduler;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SessionOptionsStore;

/**
 * The assembly: builds, validates and closes the application-scoped singletons of docs/design/workspace-sandbox.md
 * §3.2 in one place, since this module does not assume a DI container.
 *
 * <pre>{@code
 * WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
 *         .settings(settings).provider(provider)       // provider borrowed unless ownProvider(true)
 *         .tenantResolver(...)                          // required with require-principal
 *         .sessionRecords(stack::sessionRecordStore)    // core's recorded owner; and/or .sessionOwnerLookup(...)
 *         .build();                                     // SandboxConfigurationException on any §13.2 violation
 * runtimeBuilder.executionEnvironmentProvider(sandbox.environmentProvider());
 * sessionSpec.lifecycleListener(sandbox.sessionLifecycleListener()); // drops a deleted session's sandbox state
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
 * Skill-declared shell hooks need nothing from this assembly: aimon-core runs such a hook in the execution's sandbox
 * shell — never on the host — and a guard whose sandbox is unavailable blocks what it guards (§12.1). The assembly
 * recommends against registering core's
 * {@code GitStatusContextProvider} and {@code DirectorySummaryContextProvider}: they read the filesystem every turn
 * and so would provision a sandbox for turns that run no command (§11.1).
 */
public final class WorkspaceSandbox implements AutoCloseable {

    /** How long {@link #close()} lets the owned background executor finish what it holds. */
    static final Duration BACKGROUND_CLOSE_WAIT = Duration.ofSeconds(5);
    static final int BACKGROUND_THREADS = 2;
    static final int BACKGROUND_QUEUE = 1000;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSandbox.class);

    private final SandboxSettings settings;
    private final SandboxProvider provider;
    private final boolean ownProvider;
    private final SandboxScheduler scheduler;
    private final SandboxScheduler janitorScheduler;
    private final boolean ownScheduler;
    private final Executor background;
    private final boolean ownBackground;
    private final SandboxWorkspaceStore store;
    private final SessionOptionsStore sessionOptions;
    private final SandboxProfileRegistry profiles;
    private final SandboxConnectionCache connections;
    private final SandboxWorkspaceManager manager;
    private final SandboxWorkspaces workspaces;
    private final SandboxJanitor janitor;
    private final SandboxExecutionEnvironmentProvider environmentProvider;
    private final SandboxSessionCleanup sessionCleanup;

    private WorkspaceSandbox(Builder builder) {
        this.settings = Objects.requireNonNull(builder.settings, "settings must not be null");
        this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
        SandboxStartupValidator.validate(settings, new SandboxStartupValidator.Wiring(provider.capabilities(),
                builder.tenantResolver != null, builder.sessionOwnerLookup != null || builder.sessionRecords != null));
        this.ownProvider = builder.ownProvider;
        this.ownScheduler = builder.scheduler == null;
        // The janitor gets a thread of its own: one pass can wait up to close-wait per closing workspace, and the
        // heartbeats that keep running commands alive must not queue behind it.
        this.scheduler = ownScheduler ? SandboxScheduler.daemon("aimon-sandbox-heartbeat", 2) : builder.scheduler;
        this.janitorScheduler = ownScheduler ? SandboxScheduler.daemon("aimon-sandbox-janitor", 1) : builder.scheduler;
        // One-shot work apart from both: a deleted session's close can wait up to close-wait, and a heartbeat must not
        // queue behind it (core-033 design §3.5).
        this.ownBackground = builder.backgroundExecutor == null;
        this.background = ownBackground ? backgroundPool() : builder.backgroundExecutor;
        try {
            this.store = builder.store != null ? builder.store : new InMemorySandboxWorkspaceStore();
            this.sessionOptions = builder.sessionOptionsStore != null
                    ? builder.sessionOptionsStore
                    : new InMemorySessionOptionsStore();
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
            final SessionOwnerLookup sessionOwners = sessionOwners(builder);
            this.manager = SandboxWorkspaceManager.builder().settings(settings).profiles(profiles).provider(provider)
                    .store(store).sessionOptions(sessionOptions).connections(connections).admission(admission)
                    .events(builder.eventListener).callers(callers).sessionOwners(sessionOwners)
                    .sessionRecords(builder.sessionRecords).clock(clock).scheduler(scheduler).background(background)
                    .build();
            this.sessionCleanup = new SandboxSessionCleanup(manager);
            this.workspaces = new SandboxWorkspaces(manager);
            this.janitor = new SandboxJanitor(manager, janitorScheduler);
            final SandboxBindingPolicy configured = builder.bindingPolicy != null
                    ? builder.bindingPolicy
                    : new DefaultSandboxBindingPolicy(callers, profiles, sessionOwners);
            final SandboxBindingPolicy policy = new SessionOptionsBindingPolicy(configured, callers, profiles,
                    sessionOptions, settings.unattached());
            this.environmentProvider = SandboxExecutionEnvironmentProvider.builder().policy(policy).callers(callers)
                    .manager(manager).connections(connections).profiles(profiles).store(store).settings(settings)
                    .clock(clock).build();
        } catch (RuntimeException e) {
            // Nothing is returned to close: the threads this constructor started must not outlive the failure.
            closeSchedulers();
            closeBackground();
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
     * The owner sources, in the order of docs/design/workspace-sandbox-core-033.md §5.2: the owner core recorded, else
     * the host's lookup; either alone; or none (single tenant: a main turn's caller owns its workspace).
     */
    private static SessionOwnerLookup sessionOwners(Builder builder) {
        if (builder.sessionRecords == null) {
            return builder.sessionOwnerLookup;
        }
        final SessionOwnerLookup recorded = SessionOwnerLookup.recorded(builder.sessionRecords);
        return builder.sessionOwnerLookup == null ? recorded : recorded.orElse(builder.sessionOwnerLookup);
    }

    /** Two daemon threads and a bounded queue; a task it cannot take is refused, and the manager logs it. */
    private static ExecutorService backgroundPool() {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(BACKGROUND_THREADS, BACKGROUND_THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BACKGROUND_QUEUE), runnable -> {
                    final Thread thread = new Thread(runnable, "aimon-sandbox-background-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * The listener that drops a deleted session's sandbox state (kangwoo/aimon-core#253): its options record and its
     * derived workspace. Register it once with core's session router — {@code SessionRouterBuilder.lifecycleListener},
     * {@code SessionSpec.Builder.lifecycleListener}, or a Spring bean. Without it, a deleted session's options stay
     * until an operator deletes them or, with {@link Builder#sessionRecords}, the janitor's sweep does, and its
     * workspace ends by the idle policy.
     *
     * @return the listener, one per assembly
     */
    public SessionLifecycleListener sessionLifecycleListener() {
        return sessionCleanup;
    }

    /** @return the provider to hand to aimon-core's runtime */
    public SandboxExecutionEnvironmentProvider environmentProvider() {
        return environmentProvider;
    }

    /** @return the manager ({@code close} and {@code reopen} for the application) */
    public SandboxWorkspaceManager manager() {
        return manager;
    }

    /** @return the owner-facing management of workspaces: create, attach sessions, close, reopen, purge */
    public SandboxWorkspaces workspaces() {
        return workspaces;
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

    /** @return the node-local connection cache; package-private: it is plumbing, not API */
    SandboxConnectionCache connections() {
        return connections;
    }

    /**
     * Stops the janitor and the background executor, closes cached connections, and closes the scheduler and provider
     * when this assembly owns them. Sandboxes are not touched: they live as long as their workspaces (§3.2).
     *
     * <p>
     * The owned background executor takes no new work and gets {@link #BACKGROUND_CLOSE_WAIT} to finish what it holds;
     * then it is interrupted. A deleted session's workspace whose close was cut short this way stays marked, and the
     * janitor of the next run finishes it; a dropped {@code lastWorkspaceId} write repeats one notice.
     */
    @Override
    public void close() {
        janitor.close();
        closeBackground();
        connections.close();
        closeSchedulers();
        if (ownProvider) {
            provider.close();
        }
    }

    private void closeBackground() {
        if (!ownBackground) {
            return;
        }
        final ExecutorService pool = (ExecutorService) background;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(BACKGROUND_CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Background sandbox work did not finish within {}; it is interrupted and left to the janitor",
                        BACKGROUND_CLOSE_WAIT);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
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

    /** @return the executor one-shot work runs on */
    Executor backgroundExecutor() {
        return background;
    }

    /** Builder for {@link WorkspaceSandbox}. */
    public static final class Builder {
        private SandboxSettings settings;
        private SandboxProvider provider;
        private boolean ownProvider;
        private SandboxWorkspaceStore store;
        private SessionOptionsStore sessionOptionsStore;
        private SandboxTenantResolver tenantResolver;
        private SessionOwnerLookup sessionOwnerLookup;
        private Supplier<? extends SessionRecordStore> sessionRecords;
        private SandboxAdmission admission;
        private SandboxEventListener eventListener = SandboxEventListener.NOOP;
        private SandboxBindingPolicy bindingPolicy;
        private Clock clock = Clock.systemUTC();
        private SandboxScheduler scheduler;
        private Executor backgroundExecutor;

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

        /**
         * Default: a new {@link InMemorySessionOptionsStore} — <b>single node only</b>, like the in-memory workspace
         * store (docs/design/workspace-sandbox-management.md §12 Q4).
         */
        public Builder sessionOptionsStore(SessionOptionsStore sessionOptionsStore) {
            this.sessionOptionsStore = sessionOptionsStore;
            return this;
        }

        /** Required with {@code require-principal}; default: everyone in one tenant. */
        public Builder tenantResolver(SandboxTenantResolver tenantResolver) {
            this.tenantResolver = tenantResolver;
            return this;
        }

        /**
         * The host's answer to "who owns this session". With {@link #sessionRecords} too, it is asked only for a
         * session core recorded no owner for. This, {@code sessionRecords}, or both are required with
         * {@code require-principal}; default: none, a main turn's caller owns its workspace.
         */
        public Builder sessionOwnerLookup(SessionOwnerLookup sessionOwnerLookup) {
            this.sessionOwnerLookup = sessionOwnerLookup;
            return this;
        }

        /**
         * Core's session store (kangwoo/aimon-core#253): the owner it recorded — set by
         * {@code SubmitRequest.owner(...)}
         * on the submit that creates a session — becomes the session's owner, with {@link #sessionOwnerLookup} as the
         * fallback for sessions recorded without one. The janitor also uses it to sweep the options of sessions deleted
         * outside the router. Each main-turn bind then loads the session's record once.
         */
        public Builder sessionRecords(SessionRecordStore sessionRecords) {
            Objects.requireNonNull(sessionRecords, "sessionRecords must not be null");
            return sessionRecords(() -> sessionRecords);
        }

        /** {@link #sessionRecords(SessionRecordStore)}, resolved when first needed: core's stack is built later. */
        public Builder sessionRecords(Supplier<? extends SessionRecordStore> sessionRecords) {
            this.sessionRecords = sessionRecords;
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

        /**
         * Default: {@link DefaultSandboxBindingPolicy}. Either way it is wrapped in a
         * {@link SessionOptionsBindingPolicy}:
         * session options and a definition's {@code sandbox.workspace} resolve first, and this policy binds what is
         * left unattached under {@code per-session}.
         */
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
         * Runs one-shot work: the slow part of a deleted session's cleanup (terminating its sandboxes, up to
         * {@code close-wait}) and the {@code lastWorkspaceId} write after a notice was delivered. Default: two daemon
         * threads with a queue of 1 000, owned and shut down by the assembly, apart from the heartbeat and janitor
         * schedulers. A supplied executor is borrowed, never shut down; a task it refuses is logged and dropped — the
         * janitor finishes a deletion, a dropped write repeats one notice.
         */
        public Builder backgroundExecutor(Executor backgroundExecutor) {
            this.backgroundExecutor = backgroundExecutor;
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
