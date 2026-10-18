package at.aimon.sandbox.workspace;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.SandboxConfigurationException;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.binding.BindingRejectedException;
import at.aimon.sandbox.binding.CallerResolver;
import at.aimon.sandbox.binding.ManagedIds;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.binding.SessionOwnerLookup;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.profile.SandboxProfileRegistry;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * The workspace state machine (docs/design/workspace-sandbox.md §10): lazy provisioning on {@link #connect}, owner
 * checks at every public entry point, explicit and idle close with tombstones, and reopen.
 *
 * <p>
 * Build it through {@code WorkspaceSandbox}: {@link #builder()} runs none of the startup validation (§13.2), so a
 * hand-wired manager would silently accept profiles this version refuses — {@code pause-after}, {@code seed},
 * shared access, unchecked credential bindings — and act on them as if they were not there.
 *
 * <p>
 * Every write is read → decide → {@code update(id, expectedVersion, next)}; on {@link StaleVersionException} the
 * record is re-read and the decision re-made, up to {@code casRetries} times (§5.3). Provider calls happen only after
 * the CAS that authorises them. A CAS lost after a provider call never destroys what the call made — the sandbox may be
 * the winner's (§21); the janitor's reconciliation ({@code SandboxReconciler}) reclaims it once it is provably
 * nobody's.
 *
 * <p>
 * Provisions {@code primary} only, without {@code PAUSE_RESUME} (implementation steps 3–4).
 */
public final class SandboxWorkspaceManager {

    /**
     * The notice a caller gets when its workspace came back empty after an idle close (§10.5, §15).
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     */
    public static final String RESET_NOTICE = "/workspace was reset: this workspace was closed after being idle, and "
            + "its files and shell state are gone";

    /**
     * The error of a command whose sandbox disappeared under it (§15).
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     */
    public static final String LOST_MESSAGE = "the sandbox was lost mid-command; the environment will be recreated on "
            + "the next call and /workspace will be reset";

    private static final Logger log = LoggerFactory.getLogger(SandboxWorkspaceManager.class);
    private static final Duration SLOW_PROVISIONING = Duration.ofSeconds(5);
    private static final Duration FIRST_POLL = Duration.ofMillis(100);
    private static final Duration MAX_POLL = Duration.ofSeconds(2);
    /** Activity writes are never dropped for a conflict (§5.3): they retry far longer than other writes. */
    private static final int ACTIVITY_RETRIES = 1000;
    private static final int CONFLICTS_BEFORE_BACKOFF = 10;
    /** A call that keeps losing what it just created stops instead of creating sandboxes without end. */
    private static final int MAX_PROVISIONINGS_PER_CALL = 3;
    private static final char[] INCARNATION_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SandboxSettings settings;
    private final SandboxProfileRegistry profiles;
    private final SandboxProvider provider;
    private final SandboxWorkspaceStore store;
    private final SessionOptionsStore sessionOptions;
    private final SandboxConnectionCache connections;
    private final SandboxAdmission admission;
    private final SandboxEventListener events;
    private final CallerResolver callers;
    private final SessionOwnerLookup sessionOwners;
    private final Supplier<? extends SessionRecordStore> sessionRecords;
    private final Clock clock;
    private final SandboxScheduler scheduler;
    private final Executor background;
    private final SandboxSeeder seeder;

    private SandboxWorkspaceManager(Builder builder) {
        this.settings = Objects.requireNonNull(builder.settings, "settings must not be null");
        this.profiles = Objects.requireNonNull(builder.profiles, "profiles must not be null");
        this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
        this.store = Objects.requireNonNull(builder.store, "store must not be null");
        this.sessionOptions = Objects.requireNonNull(builder.sessionOptions, "sessionOptions must not be null");
        this.connections = Objects.requireNonNull(builder.connections, "connections must not be null");
        this.admission = Objects.requireNonNull(builder.admission, "admission must not be null");
        this.events = Objects.requireNonNull(builder.events, "events must not be null");
        this.callers = Objects.requireNonNull(builder.callers, "callers must not be null");
        this.sessionOwners = builder.sessionOwners;
        this.sessionRecords = builder.sessionRecords;
        this.clock = Objects.requireNonNull(builder.clock, "clock must not be null");
        this.scheduler = Objects.requireNonNull(builder.scheduler, "scheduler must not be null");
        this.background = Objects.requireNonNull(builder.background, "background must not be null");
        this.seeder = new SandboxSeeder(settings.provisionTimeout(), provider.capabilities().controlPlaneEndpoints());
    }

    /**
     * For tests and custom assemblies. <b>Runs no startup validation</b>: build through {@code WorkspaceSandbox}, which
     * refuses the settings this version cannot honour (§13.2).
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // connect (§10.1)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Returns the binding's slot RUNNING and seeded, provisioning it first when needed (§10.1). Called before every
     * command and file operation — reads are per call, writes are throttled.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param binding
     *            the execution's binding
     * @return the connected slot
     * @throws SandboxUnavailableException
     *             with a model-facing reason when the slot cannot be used — also when the store or the admission
     *             fails, and carrying the notices this call gathered (a reset the model must hear of)
     */
    public ConnectedSlot connect(SandboxBinding binding) {
        return connect(Target.of(binding));
    }

    private ConnectedSlot connect(Target binding) {
        final Connect attempt = new Connect(binding);
        try {
            while (true) {
                try {
                    final ConnectedSlot connected = attempt.step();
                    if (connected != null) {
                        return connected;
                    }
                } catch (StaleVersionException e) {
                    if (++attempt.conflicts > settings.casRetries()) {
                        throw busy(binding.workspaceId());
                    }
                }
            }
        } catch (SandboxUnavailableException e) {
            throw attempt.withNotices(e);
        } catch (RuntimeException e) {
            // A store or an application's admission failing: core's tools report "unavailable", not a raw exception.
            throw attempt.withNotices(new SandboxUnavailableException(
                    "the sandbox workspace cannot be used right now: " + e.getMessage(), e));
        }
    }

    /**
     * What {@link Connect} reads of a binding. {@link #provisionSlot} has no binding — it runs no command, so it has no
     * shell key — and builds one of these directly.
     */
    private static final class Target {
        private final SandboxWorkspaceId workspaceId;
        private final WorkspaceOwner owner;
        private final WorkspaceOwner caller;
        private final String slot;
        private final Optional<String> requiredProfile;
        private final String root;
        private final boolean mustExist;

        private Target(SandboxWorkspaceId workspaceId, WorkspaceOwner owner, WorkspaceOwner caller, String slot,
                Optional<String> requiredProfile, String root, boolean mustExist) {
            this.workspaceId = workspaceId;
            this.owner = owner;
            this.caller = caller;
            this.slot = slot;
            this.requiredProfile = requiredProfile;
            this.root = root;
            this.mustExist = mustExist;
        }

        private static Target of(SandboxBinding binding) {
            return new Target(binding.workspaceId(), binding.owner(), binding.caller(), binding.slot(),
                    binding.requiredProfile(), binding.root(), binding.mustExist());
        }

        private SandboxWorkspaceId workspaceId() {
            return workspaceId;
        }

        private WorkspaceOwner owner() {
            return owner;
        }

        private WorkspaceOwner caller() {
            return caller;
        }

        private String slot() {
            return slot;
        }

        private Optional<String> requiredProfile() {
            return requiredProfile;
        }

        private String root() {
            return root;
        }

        private boolean mustExist() {
            return mustExist;
        }
    }

    /** One {@link #connect} call's state across its loop iterations. */
    private final class Connect {
        private final Target binding;
        private final Instant started;
        private final long startedNanos = System.nanoTime();
        private final List<String> notices = new ArrayList<>();
        private int conflicts;
        private boolean provisioned;
        /** Whether this call reported that the slot's files are gone (an idle reopen, a re-creation, a loss). */
        private boolean reset;
        private int provisionings;
        private Duration pollDelay = FIRST_POLL;

        private Connect(Target binding) {
            this.binding = binding;
            this.started = clock.instant();
        }

        /**
         * The error with this call's notices appended. A reset notice (idle reopen, a lost or terminated sandbox) is
         * otherwise lost when the recreate that follows it fails: the next call finds the slot FAILED, not
         * TERMINATED, and has nothing to tell (§15). The execution environment takes the parts apart again and queues
         * the notices for core's drain (docs/design/workspace-sandbox-unannounced-reset.md §6.3).
         */
        private SandboxUnavailableException withNotices(SandboxUnavailableException e) {
            if (notices.isEmpty()) {
                return e;
            }
            return new SandboxUnavailableWithNoticesException(e.getMessage(), notices, reset, binding.workspaceId(),
                    binding.slot(), e);
        }

        /** @return the connected slot, or {@code null} to go round again */
        private ConnectedSlot step() {
            SandboxWorkspace workspace = loadOrCreate(binding);
            if (workspace.state() != WorkspaceState.OPEN
                    && workspace.closeCause().orElse(null) == CloseCause.SESSION_DELETED) {
                // Ahead of the use check: a re-created session with the same id and another owner must hear the same
                // answer as its predecessor's owner would, never "not permitted" (core-033 design §6.3).
                throw new SandboxUnavailableException(
                        "this session's sandbox workspace is being removed; retry shortly");
            }
            checkUse(workspace, binding.caller());
            if (workspace.state() == WorkspaceState.CLOSED
                    && workspace.closeCause().orElse(CloseCause.EXPLICIT) == CloseCause.IDLE) {
                workspace = store.update(workspace.id(), workspace.version(), reopened(workspace, clock.instant()));
                notices.add(RESET_NOTICE);
                reset = true;
                emit(SandboxEvent.Type.WORKSPACE_REOPENED, workspace, null, "reopened after an idle close");
            } else if (workspace.state() == WorkspaceState.CLOSING) {
                throw new SandboxUnavailableException("the sandbox workspace is being closed; retry shortly");
            } else if (workspace.state() != WorkspaceState.OPEN) {
                throw new SandboxUnavailableException("the sandbox workspace is closed; the application has to reopen "
                        + "it before it can be used again");
            }
            final SandboxSlot slot = workspace.slot(binding.slot()).orElse(null);
            final SandboxProfile profile = profileFor(binding, workspace, slot);
            if (slot == null || slot.state() == SlotState.TERMINATED || retryable(slot, profile)) {
                provisionNew(workspace, slot, profile);
                return null;
            }
            switch (slot.state()) {
                case RUNNING :
                    return running(workspace, slot, profile);
                case PROVISIONING :
                    waitOrTakeOver(workspace, slot, profile);
                    return null;
                case FAILED :
                    throw failed(slot);
                case PAUSED :
                default :
                    throw new SandboxUnavailableException(
                            "slot '" + slot.name() + "' is " + slot.state() + ", which this version cannot resume");
            }
        }

        private void provisionNew(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile) {
            if (provisionings >= MAX_PROVISIONINGS_PER_CALL) {
                throw new SandboxUnavailableException("the sandbox was lost again right after being recreated ("
                        + MAX_PROVISIONINGS_PER_CALL + " times in this call); retry later");
            }
            final AdmissionDecision decision = admission.admit(workspace.owner(), profile);
            if (!decision.isAdmitted()) {
                throw new SandboxUnavailableException("cannot start a sandbox: " + decision.rejection().orElse(""));
            }
            final Instant now = clock.instant();
            final SandboxSlot next = SandboxSlot.builder().name(binding.slot()).profile(profile.name())
                    .profileHash(profile.contentHash()).state(SlotState.PROVISIONING)
                    .generation(slot == null ? 1 : slot.generation() + 1)
                    .provisioning(ProvisioningClaim.of(now, settings.nodeId()))
                    .failure(slot == null ? null : slot.failure().orElse(null)).lastActivityAt(now)
                    .lastActiveAt(slot == null ? null : slot.lastActiveAt().orElse(null)).build();
            final SandboxWorkspace claimed = store.update(workspace.id(), workspace.version(),
                    workspace.withSlot(next).withLastActivityAt(now));
            // Counted once the claim is ours: a plain version conflict above is not a lost sandbox.
            provisionings++;
            if (slot != null && slot.state() == SlotState.TERMINATED && notices.isEmpty()) {
                notices.add("the sandbox was recreated (generation " + next.generation() + "): files under "
                        + "/workspace and shell state (cwd, exported variables) from before are gone");
            }
            reset |= slot != null && slot.state() == SlotState.TERMINATED;
            provisioned = true;
            // The stored slot, not the in-memory one: a store may round what it keeps (an Instant's precision).
            create(claimed, claimed.slot(binding.slot()).orElseThrow(), profile);
        }

        private ConnectedSlot running(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile) {
            final ProviderSandboxRef ref = slot.providerRef().orElseThrow();
            final SandboxConnection connection;
            try {
                connection = connections.get(ref);
            } catch (SandboxNotFoundException e) {
                activity(workspace, slot, profile).markLost();
                notices.add("the sandbox was lost and has been recreated; /workspace was reset");
                reset = true;
                return null;
            } catch (SandboxProviderException e) {
                throw new SandboxUnavailableException("the sandbox cannot be reached: " + e.getMessage(), e);
            }
            if (!slot.seeded()) {
                seed(workspace, slot, profile, connection, binding.root());
                return null;
            }
            final Duration took = Duration.between(started, clock.instant());
            if (provisioned && took.compareTo(SLOW_PROVISIONING) >= 0) {
                notices.add("sandbox provisioned in " + took.toSeconds() + "s");
            }
            return new ConnectedSlot(workspace, slot, profile, connection, notices, activity(workspace, slot, profile),
                    reset);
        }

        private void waitOrTakeOver(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile) {
            final ProvisioningClaim claim = slot.provisioning().orElseThrow();
            final Instant now = clock.instant();
            if (!now.isBefore(claim.since().plus(settings.provisionTimeout()))) {
                final ProvisioningClaim takeover = ProvisioningClaim.of(now, settings.nodeId());
                final Optional<SandboxWorkspace> taken = mutate(workspace.id(), current -> current.slot(slot.name())
                        .filter(s -> current.state() == WorkspaceState.OPEN && s.state() == SlotState.PROVISIONING
                                && s.generation() == slot.generation() && s.provisioning().equals(Optional.of(claim)))
                        .map(s -> current.withSlot(s.toBuilder().provisioning(takeover).build())).orElse(null));
                if (taken.isPresent()) {
                    log.info("Took over provisioning of {}/{} generation {} from {}", workspace.id(), slot.name(),
                            slot.generation(), claim);
                    provisioned = true;
                    create(taken.get(), taken.get().slot(slot.name()).orElseThrow(), profile);
                }
                return;
            }
            // Bounded by both clocks: the injected one decides takeovers, but a wait must end in real time too.
            if (!now.isBefore(started.plus(settings.provisionTimeout()))
                    || System.nanoTime() - startedNanos >= settings.provisionTimeout().toNanos()) {
                throw new SandboxUnavailableException("another execution is provisioning this sandbox and it did not "
                        + "finish within " + settings.provisionTimeout().toSeconds() + "s; the next call takes over");
            }
            sleep(pollDelay);
            pollDelay = pollDelay.multipliedBy(2).compareTo(MAX_POLL) > 0 ? MAX_POLL : pollDelay.multipliedBy(2);
        }
    }

    private SandboxWorkspace loadOrCreate(Target binding) {
        final Optional<SandboxWorkspace> found = store.find(binding.workspaceId());
        if (found.isPresent()) {
            return found.get();
        }
        if (binding.mustExist()) {
            // A purged managed id must not come back owned by whoever binds to it next (management design §5.3).
            throw new SandboxUnavailableException("sandbox workspace " + binding.workspaceId() + " does not exist");
        }
        final Instant now = clock.instant();
        return store.createIfAbsent(SandboxWorkspace.builder().id(binding.workspaceId()).owner(binding.owner())
                .state(WorkspaceState.OPEN).incarnation(newIncarnation()).stateSince(now).quota(settings.quota())
                .createdAt(now).lastActivityAt(now).build());
    }

    /**
     * The slot's profile, which a required one must equal; for a new slot the required profile, else the one a managed
     * workspace was created with, else the default — so a lazy bind after a refused first provisioning builds what
     * {@code create} asked for (management design §6.1).
     */
    private SandboxProfile profileFor(Target binding, SandboxWorkspace workspace, SandboxSlot slot) {
        if (slot != null) {
            final Optional<String> required = binding.requiredProfile();
            if (required.isPresent() && !required.get().equals(slot.profile())) {
                throw new SandboxUnavailableException(
                        "slot '" + slot.name() + "' of this workspace already runs " + "profile '" + slot.profile()
                                + "', but this definition requires profile '" + required.get() + "'");
            }
            return profiles.find(slot.profile()).orElseThrow(
                    () -> new SandboxUnavailableException("slot '" + slot.name() + "' was created with profile '"
                            + slot.profile() + "', which is no longer " + "configured"));
        }
        final String name = binding.requiredProfile()
                .or(() -> SandboxBinding.PRIMARY.equals(binding.slot()) ? workspace.profile() : Optional.empty())
                .orElse(profiles.defaultProfileName());
        return profiles.find(name)
                .orElseThrow(() -> new SandboxUnavailableException("sandbox profile '" + name + "' is not configured"));
    }

    /** Whether a FAILED slot may be provisioned again now (§10.1). */
    private boolean retryable(SandboxSlot slot, SandboxProfile profile) {
        if (slot.state() != SlotState.FAILED) {
            return false;
        }
        final SlotFailure failure = slot.failure().orElse(null);
        if (failure == null) {
            return true;
        }
        if (failure.kind() == SlotFailure.Kind.PERMANENT) {
            return !failure.profileHash().equals(profile.contentHash());
        }
        return !clock.instant().isBefore(failure.at().plus(backoff(failure.attempts())));
    }

    /** {@code min(failureBackoff * 2^(attempts-1), maxFailureBackoff)}. */
    Duration backoff(int attempts) {
        Duration delay = settings.failureBackoff();
        for (int i = 1; i < attempts && delay.compareTo(settings.maxFailureBackoff()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(settings.maxFailureBackoff()) > 0 ? settings.maxFailureBackoff() : delay;
    }

    private SandboxUnavailableException failed(SandboxSlot slot) {
        final SlotFailure failure = slot.failure().orElseThrow();
        final String retry = failure.kind() == SlotFailure.Kind.PERMANENT
                ? "it is not retried until the profile changes"
                : "the next attempt is after " + failure.at().plus(backoff(failure.attempts()));
        return new SandboxUnavailableException("the sandbox (profile '" + slot.profile() + "') could not be "
                + "provisioned: " + failure.reason() + " [step " + failure.step() + "]; " + retry);
    }

    /** Creates the claimed generation's sandbox, verifies it and marks it RUNNING (§10.1 step 4). */
    private void create(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile) {
        final Instant now = clock.instant();
        final String key = SandboxLabels.key(settings.deployment(), workspace.id().value(), workspace.incarnation(),
                slot.name(), slot.generation());
        final Map<String, String> labels = SandboxLabels.labels(settings.deployment(), workspace.id().value(),
                workspace.incarnation(), slot.name(), slot.generation(), workspace.owner().tenant().value());
        final CreateSpec spec = CreateSpec.builder().key(key).image(profile.image()).platform(profile.platform())
                .resources(profile.resources()).runtimeClass(profile.runtimeClass().orElse(null))
                .egress(profile.egress().orElse(null)).credentials(profile.credentials())
                .environment(profile.environment()).labels(labels)
                .expiresAt(now.plus(profile.terminateAfter().orElseThrow())).build();
        final ProviderSandboxRef ref;
        try {
            ref = provider.create(spec);
        } catch (SandboxProviderException e) {
            throw fail(workspace, slot, profile, kindOf(e), "create", e.getMessage(), null);
        } catch (RuntimeException e) {
            throw fail(workspace, slot, profile, SlotFailure.Kind.TRANSIENT, "create", String.valueOf(e), null);
        }
        // From here the sandbox is this claim's own (created under this generation's key): a failure that records
        // FAILED also destroys it, since no record points anyone at it any more.
        final Optional<ProviderSandbox> status;
        try {
            status = provider.status(ref);
        } catch (SandboxProviderException e) {
            throw fail(workspace, slot, profile, kindOf(e), "create", redact(e.getMessage(), ref), ref);
        } catch (RuntimeException e) {
            throw fail(workspace, slot, profile, SlotFailure.Kind.TRANSIENT, "create", redact(String.valueOf(e), ref),
                    ref);
        }
        if (status.isEmpty()) {
            throw fail(workspace, slot, profile, SlotFailure.Kind.TRANSIENT, "create",
                    "the created sandbox " + ref + " is not visible to the provider", ref);
        }
        final List<String> mismatched = SandboxLabels.mismatches(labels, status.get().labels());
        if (!mismatched.isEmpty()) {
            // Not destroyed: a sandbox whose labels do not match may not be ours.
            throw fail(workspace, slot, profile, SlotFailure.Kind.PERMANENT, "labels",
                    "the provider returned sandbox " + ref + " whose labels " + mismatched + " do not match", null);
        }
        final Optional<SandboxWorkspace> running = mutate(workspace.id(),
                current -> sameClaim(current, slot).map(s -> current.withSlot(s.toBuilder().state(SlotState.RUNNING)
                        .providerRef(ref).provisioning(null).failure(null).build())).orElse(null));
        if (running.isEmpty()) {
            log.warn("Lost the RUNNING CAS after creating {} ({}); it is left to reconciliation", ref, key);
            return;
        }
        emit(SandboxEvent.Type.PROVISIONED, running.get(), running.get().slot(slot.name()).orElseThrow(), key);
    }

    /**
     * A provider's or server's message with the sandbox's raw id replaced by its hash, as {@link ProviderSandboxRef}
     * prints it: a recorded failure reaches owner-facing views and the model, and the id plus network reach is root in
     * the sandbox (WS §13.3; management design §5.4).
     */
    static String redact(String message, ProviderSandboxRef ref) {
        if (message == null || ref == null || ref.sandboxId().isEmpty()) {
            return message;
        }
        return message.replace(ref.sandboxId(), "#" + SandboxLabels.h(ref.sandboxId()));
    }

    private Optional<SandboxSlot> sameClaim(SandboxWorkspace current, SandboxSlot claimed) {
        return current.slot(claimed.name()).filter(s -> s.state() == SlotState.PROVISIONING
                && s.generation() == claimed.generation() && s.provisioning().equals(claimed.provisioning()));
    }

    private static SlotFailure.Kind kindOf(SandboxProviderException e) {
        return e.kind() == SandboxProviderException.Kind.PERMANENT
                ? SlotFailure.Kind.PERMANENT
                : SlotFailure.Kind.TRANSIENT;
    }

    /**
     * CAS the claimed generation to FAILED and build the error for the caller.
     *
     * @param created
     *            the sandbox this claim created, destroyed once the FAILED CAS is won; {@code null} for none, or for
     *            one that may not be ours
     */
    private SandboxUnavailableException fail(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile,
            SlotFailure.Kind kind, String step, String reason, ProviderSandboxRef created) {
        final Instant now = clock.instant();
        final int attempts = slot.failure().filter(f -> f.kind() == kind).map(f -> f.attempts() + 1).orElse(1);
        final SlotFailure failure = SlotFailure.builder().at(now).kind(kind).step(step).reason(reason)
                .attempts(attempts).profileHash(profile.contentHash()).build();
        final Optional<SandboxWorkspace> stored = mutate(workspace.id(),
                current -> sameClaim(current, slot).map(s -> current.withSlot(s.toBuilder().state(SlotState.FAILED)
                        .provisioning(null).failure(failure).lastActiveAt(now).build())).orElse(null));
        log.warn("Provisioning {}/{} generation {} failed ({} {}): {}", workspace.id(), slot.name(), slot.generation(),
                kind, step, reason);
        if (stored.isPresent() && created != null) {
            destroyQuietly(created);
        }
        return stored.map(w -> failed(w.slot(slot.name()).orElseThrow()))
                .orElseGet(() -> new SandboxUnavailableException("the sandbox could not be provisioned: " + reason));
    }

    /**
     * Seeds the generation (§6.9) and records {@code seeded=true}, or FAILED(permanent) on a failed check. The
     * provider's own checks ({@link SandboxProvider#verify}: egress enforcement, vault bindings) run first, then the
     * seed script (§11.3).
     */
    private void seed(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile,
            SandboxConnection connection, String root) {
        final ProviderSandboxRef ref = slot.providerRef().orElseThrow();
        Optional<SandboxSeeder.Failure> failure;
        try {
            failure = provider.verify(ref, profile.requiredCapabilities()).stream().findFirst()
                    .map(f -> new SandboxSeeder.Failure(f.step(), f.reason()));
            if (failure.isEmpty()) {
                failure = seeder.seed(connection, profile, root);
            }
        } catch (SandboxNotFoundException e) {
            activity(workspace, slot, profile).markLost();
            throw new SandboxUnavailableException(LOST_MESSAGE, e);
        } catch (RuntimeException e) {
            throw new SandboxUnavailableException("the sandbox could not be prepared: " + redact(e.getMessage(), ref),
                    e);
        }
        if (failure.isPresent()) {
            final Instant now = clock.instant();
            final SlotFailure recorded = SlotFailure.builder().at(now).kind(SlotFailure.Kind.PERMANENT)
                    .step(failure.get().step()).reason(redact(failure.get().reason(), ref))
                    .profileHash(profile.contentHash()).build();
            final Optional<SandboxWorkspace> stored = mutate(workspace.id(),
                    current -> sameGeneration(current, slot)
                            .map(s -> current.withSlot(
                                    s.toBuilder().state(SlotState.FAILED).failure(recorded).lastActiveAt(now).build()))
                            .orElse(null));
            if (stored.isPresent()) {
                // Our own, verified sandbox, and the record no longer points anyone at it.
                destroyQuietly(ref);
            }
            throw failed(slot.toBuilder().state(SlotState.FAILED).failure(recorded).build());
        }
        mutate(workspace.id(), current -> sameGeneration(current, slot).filter(s -> !s.seeded())
                .map(s -> current.withSlot(s.toBuilder().seeded(true).build())).orElse(null));
    }

    private Optional<SandboxSlot> sameGeneration(SandboxWorkspace current, SandboxSlot slot) {
        return current.slot(slot.name()).filter(s -> s.state() == SlotState.RUNNING
                && s.generation() == slot.generation() && s.providerRef().equals(slot.providerRef()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // close and reopen (§10.5)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Closes a workspace for good: its sandboxes are destroyed and the record stays as a tombstone for
     * {@code closedRetention}, answering "workspace closed" until {@link #reopen} (§10.5). Idempotent, so closing an
     * id that has no record is success — which also means a foreign caller learns whether an id exists ("not
     * permitted" or nothing), the one thing §15 lets the owner check reveal. Workspace ids are not guessable.
     *
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking; must have the manage right ({@link #checkManage})
     * @throws SandboxUnavailableException
     *             when the caller may not close it
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             when the principal gate refuses the caller (§8.3) — like {@code SandboxUnavailableException}, an
     *             {@code ExecutionEnvironmentUnavailableException}
     */
    public void close(SandboxWorkspaceId id, Principal caller) {
        close(id, Acting.as(callers.callerOf(Optional.ofNullable(caller))));
    }

    private void close(SandboxWorkspaceId id, Acting acting) {
        final Optional<SandboxWorkspace> found = store.find(id);
        if (found.isEmpty()) {
            return;
        }
        acting.checkManage(this, found.get());
        closeProcedure(id, CloseCause.EXPLICIT, current -> true, acting.actor());
    }

    /**
     * Reopens a CLOSED workspace: OPEN, a new incarnation, the owner unchanged; the slots stay TERMINATED so the next
     * use provisions a new generation (§10.5). An OPEN workspace is left as is.
     *
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking; must have the manage right ({@link #checkManage})
     * @throws SandboxUnavailableException
     *             when the caller may not reopen it, it does not exist, or it is still closing — also when it became
     *             CLOSING after this call first read it
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             when the principal gate refuses the caller (§8.3) — like {@code SandboxUnavailableException}, an
     *             {@code ExecutionEnvironmentUnavailableException}
     */
    public void reopen(SandboxWorkspaceId id, Principal caller) {
        reopen(id, Acting.as(callers.callerOf(Optional.ofNullable(caller))));
    }

    private void reopen(SandboxWorkspaceId id, Acting acting) {
        final SandboxWorkspace found = store.find(id)
                .orElseThrow(() -> new SandboxUnavailableException("no such sandbox workspace"));
        acting.checkManage(this, found);
        // Decided on the freshly read record inside the CAS: a close that started since the first read is refused,
        // not silently ignored.
        mutate(id, current -> {
            if (current.state() == WorkspaceState.CLOSING) {
                throw new SandboxUnavailableException(
                        "the sandbox workspace is still closing; reopen it once it is closed");
            }
            return current.state() == WorkspaceState.CLOSED ? reopened(current, clock.instant()) : null;
        }).ifPresent(reopened -> {
            if (!reopened.incarnation().equals(found.incarnation())) {
                emit(SandboxEvent.Type.WORKSPACE_REOPENED, reopened, null,
                        acting.isAdministrator() ? "reopened by an administrator" : "reopened by the application",
                        acting.actor(), null);
            }
        });
    }

    /**
     * The close procedure (§10.5, without the volume steps) behind {@link #close} and the janitor's closes, which are
     * internal paths with no caller (§8.3). Every step is idempotent, so the janitor can resume a close stuck in
     * CLOSING from the top.
     *
     * @param guard
     *            re-checked on the freshly read record, whatever its state, before the state-switch CAS; a refusal
     *            leaves the record alone. The janitor passes its idle condition, or for a resumed close "still CLOSING
     *            with the scanned incarnation", so it never acts on a workspace that changed after its scan.
     * @return whether the workspace reached CLOSED
     */
    boolean closeProcedure(SandboxWorkspaceId id, CloseCause cause, Predicate<SandboxWorkspace> guard) {
        return closeProcedure(id, cause, guard, null);
    }

    /**
     * @param actor
     *            who asked for the close, recorded on its events; {@code null} for the janitor's
     */
    private boolean closeProcedure(SandboxWorkspaceId id, CloseCause cause, Predicate<SandboxWorkspace> guard,
            String actor) {
        final boolean[] tombstoned = new boolean[1];
        final Optional<SandboxWorkspace> closing = mutate(id, current -> {
            tombstoned[0] = false;
            if (!guard.test(current)) {
                return null;
            }
            switch (current.state()) {
                case OPEN :
                    return current.toBuilder().state(WorkspaceState.CLOSING).stateSince(clock.instant())
                            .closeCause(cause).build();
                case CLOSING :
                    // An explicit close overtaking an idle one turns it into a tombstone (§10.5).
                    return cause == CloseCause.EXPLICIT && current.closeCause().orElse(null) == CloseCause.IDLE
                            ? current.toBuilder().closeCause(CloseCause.EXPLICIT).build()
                            : current;
                case CLOSED :
                    // An idle-closed record never blocks connect; an explicit close turns it into a tombstone that
                    // does. Its sandboxes are already gone, so only the cause and the retention start change. Decided
                    // in this one CAS: if an idle auto-reopen wins the race, the retry reads OPEN and closes that.
                    if (cause == CloseCause.EXPLICIT && current.closeCause().orElse(null) == CloseCause.IDLE) {
                        tombstoned[0] = true;
                        return current.toBuilder().closeCause(CloseCause.EXPLICIT).stateSince(clock.instant()).build();
                    }
                    return null;
                default :
                    return null;
            }
        });
        if (closing.isEmpty() || tombstoned[0]) {
            return false;
        }
        // Everything below acts only while the record is still this close's: CLOSING, same incarnation. A slow closer
        // (a janitor resuming a close another node finished) must not touch a workspace that was reopened since.
        final String incarnation = closing.get().incarnation();
        final Predicate<SandboxWorkspace> stillClosing = current -> current.state() == WorkspaceState.CLOSING
                && current.incarnation().equals(incarnation);
        for (String slotName : List.copyOf(closing.get().slots().keySet())) {
            terminateSlot(id, slotName, "workspace close", stillClosing, slot -> true, actor);
        }
        // A close resumed after a crash finds slots already TERMINATED whose destroy never ran: destroy is
        // idempotent, so every recorded sandbox is destroyed again rather than trusting that it was.
        store.find(id).filter(stillClosing).ifPresent(current -> current.slots().values()
                .forEach(slot -> slot.providerRef().ifPresent(this::destroyQuietly)));
        awaitSandboxesGone(id, stillClosing);
        final Optional<SandboxWorkspace> closed = mutate(id,
                current -> stillClosing.test(current)
                        ? current.toBuilder().state(WorkspaceState.CLOSED).stateSince(clock.instant()).build()
                        : null);
        closed.ifPresent(w -> emit(SandboxEvent.Type.WORKSPACE_CLOSED, w, null,
                w.closeCause().orElse(cause).name().toLowerCase(Locale.ROOT) + " close", actor, null));
        return closed.isPresent();
    }

    /**
     * CAS one slot to TERMINATED, then destroy its sandbox (§10.5 step 2, §10.4 item 1).
     *
     * @param guard
     *            re-checked on the freshly read slot before the CAS (the janitor's idle condition)
     * @return whether this call terminated it
     */
    boolean terminateSlot(SandboxWorkspaceId id, String slotName, String cause, Predicate<SandboxSlot> guard) {
        return terminateSlot(id, slotName, cause, workspace -> true, guard, null);
    }

    private boolean terminateSlot(SandboxWorkspaceId id, String slotName, String cause,
            Predicate<SandboxWorkspace> workspaceGuard, Predicate<SandboxSlot> guard, String actor) {
        final SandboxSlot[] before = new SandboxSlot[1];
        final Optional<SandboxWorkspace> terminated = mutate(id, current -> !workspaceGuard.test(current)
                ? null
                : current.slot(slotName).filter(s -> s.state() != SlotState.TERMINATED && guard.test(s)).map(s -> {
                    before[0] = s;
                    return current.withSlot(s.terminated(clock.instant()));
                }).orElse(null));
        if (terminated.isEmpty()) {
            return false;
        }
        before[0].providerRef().ifPresent(this::destroyQuietly);
        emit(SandboxEvent.Type.TERMINATED, terminated.get(), before[0], cause, actor, null);
        return true;
    }

    /**
     * Reclaims a PROVISIONING slot whose claim nobody finished or took over (§10.4 item 1 as amended in implementation
     * step 3): its claimer died, or its {@code create} or RUNNING CAS failed in a way that left the claim behind. Such
     * a slot would otherwise block the idle close and hold an admission slot until someone connects to that session.
     * The claim must be {@link #provisioningAbandoned abandoned} — twice {@code provisionTimeout}, longer than any
     * claimer still working and than the connect-path takeover — and still the same on the freshly read record. The
     * slot becomes TERMINATED, and a sandbox the claimer may have created under its key is destroyed.
     *
     * @return whether this call reclaimed it
     */
    boolean reclaimProvisioning(SandboxWorkspaceId id, SandboxSlot slot) {
        final ProvisioningClaim claim = slot.provisioning().orElse(null);
        if (slot.state() != SlotState.PROVISIONING || claim == null) {
            return false;
        }
        final SandboxSlot[] before = new SandboxSlot[1];
        final Optional<SandboxWorkspace> reclaimed = mutate(id,
                current -> current.state() != WorkspaceState.OPEN
                        ? null
                        : current.slot(slot.name())
                                .filter(s -> s.state() == SlotState.PROVISIONING && s.generation() == slot.generation()
                                        && s.provisioning().equals(Optional.of(claim))
                                        && provisioningAbandoned(claim, clock.instant()))
                                .map(s -> {
                                    before[0] = s;
                                    return current.withSlot(s.terminated(clock.instant()));
                                }).orElse(null));
        if (reclaimed.isEmpty()) {
            return false;
        }
        final String key = SandboxLabels.key(settings.deployment(), id.value(), reclaimed.get().incarnation(),
                slot.name(), slot.generation());
        final Map<String, String> selector = new LinkedHashMap<>(
                SandboxLabels.workspaceSelector(settings.deployment(), id.value()));
        selector.put(SandboxLabels.SANDBOX_KEY, SandboxLabels.h(key));
        try {
            provider.list(selector).forEach(sandbox -> destroyQuietly(sandbox.ref()));
        } catch (RuntimeException e) {
            log.warn("Listing the sandbox of abandoned claim {} failed; it is left to provider expiry: {}", key,
                    e.getMessage());
        }
        emit(SandboxEvent.Type.TERMINATED, reclaimed.get(), before[0],
                "provisioning claim of " + claim.nodeId() + " abandoned since " + claim.since());
        return true;
    }

    /** Whether a provisioning claim is old enough for the janitor to reclaim: twice {@code provisionTimeout}. */
    boolean provisioningAbandoned(ProvisioningClaim claim, Instant now) {
        return !now.isBefore(claim.since().plus(settings.provisionTimeout().multipliedBy(2)));
    }

    /**
     * Waits for the workspace's sandboxes to disappear (§10.5 step 3), destroying what is still listed while the
     * record is this close's: a sandbox whose creator lost its RUNNING CAS to this close is recorded nowhere, and
     * nothing else would destroy it before provider expiry. Each destroy re-reads the record first.
     */
    private void awaitSandboxesGone(SandboxWorkspaceId id, Predicate<SandboxWorkspace> stillClosing) {
        final Map<String, String> selector = SandboxLabels.workspaceSelector(settings.deployment(), id.value());
        final Instant deadline = clock.instant().plus(settings.closeWait());
        final long realDeadline = System.nanoTime() + settings.closeWait().toNanos();
        Duration delay = FIRST_POLL;
        while (true) {
            try {
                final List<ProviderSandbox> listed = provider.list(selector);
                if (listed.isEmpty()) {
                    return;
                }
                for (ProviderSandbox sandbox : listed) {
                    if (store.find(id).filter(stillClosing).isEmpty()) {
                        return;
                    }
                    destroyQuietly(sandbox.ref());
                }
            } catch (RuntimeException e) {
                log.warn("Listing the sandboxes of closing workspace {} failed: {}", id, e.getMessage());
            }
            if (!clock.instant().isBefore(deadline) || System.nanoTime() - realDeadline >= 0) {
                log.warn("Sandboxes of workspace {} were still listed after close-wait ({}); they are left to provider "
                        + "expiry", id, settings.closeWait());
                return;
            }
            sleep(delay);
            delay = delay.multipliedBy(2).compareTo(MAX_POLL) > 0 ? MAX_POLL : delay.multipliedBy(2);
        }
    }

    private SandboxWorkspace reopened(SandboxWorkspace workspace, Instant now) {
        final Map<String, SandboxSlot> slots = new LinkedHashMap<>();
        workspace.slots().forEach(
                (name, slot) -> slots.put(name, slot.state() == SlotState.TERMINATED ? slot : slot.terminated(now)));
        return workspace.toBuilder().state(WorkspaceState.OPEN).incarnation(newIncarnation()).stateSince(now)
                .closeCause(null).slots(slots).build();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // managed workspaces and session options (docs/design/workspace-sandbox-management.md §6.1, §6.2, §6.6)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Creates a managed workspace and provisions its {@code primary} slot before returning (management design §6.1):
     * the principal gate on {@code owner}, the request's checks, {@code max-managed-per-owner}, then a record under a
     * new {@code wsm:} id and the {@link #connect} state machine from step 2 on. Repeating a call creates a second
     * workspace.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxWorkspaces.create}.
     *
     * @param request
     *            name, profile and access
     * @param owner
     *            the principal that will own it
     * @param actor
     *            who asks, recorded in the event
     * @return the workspace, {@code primary} RUNNING
     * @throws IllegalArgumentException
     *             for a profile that is not configured
     * @throws at.aimon.sandbox.SandboxConfigurationException
     *             for {@code access: tenant} without {@code allow-shared-workspaces} in a {@code principal}
     *             deployment
     * @throws WorkspaceProvisioningException
     *             when the record was created but provisioning failed; it carries the id to retry or purge
     * @throws SandboxUnavailableException
     *             when the owner already holds {@code max-managed-per-owner} managed workspaces; nothing is written
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             when the principal gate refuses the owner
     */
    public WorkspaceView createManaged(CreateWorkspace request, Principal owner, Principal actor) {
        Objects.requireNonNull(request, "request must not be null");
        final WorkspaceOwner who = callers.callerOf(Optional.ofNullable(owner));
        final String profile = request.profile().orElse(profiles.defaultProfileName());
        if (profiles.find(profile).isEmpty()) {
            throw new IllegalArgumentException("sandbox profile '" + profile + "' is not configured");
        }
        if (request.access().orElse(null) == SandboxSettings.WorkspaceAccess.TENANT
                && settings.workspaceAccess() == SandboxSettings.WorkspaceAccess.PRINCIPAL
                && !settings.allowSharedWorkspaces()) {
            throw new SandboxConfigurationException(List.of("a workspace shared with the tenant (access: tenant) needs "
                    + "allow-shared-workspaces: everyone attached to it shares one trust domain"));
        }
        final int limit = settings.maxManagedPerOwner();
        if (limit != SandboxSettings.UNLIMITED && countManaged(who) >= limit) {
            throw new SandboxUnavailableException("this owner already has " + limit + " managed sandbox workspaces "
                    + "(max-managed-per-owner); purge one before creating another");
        }
        final Instant now = clock.instant();
        final SandboxWorkspace created = store.createIfAbsent(SandboxWorkspace.builder().id(ManagedIds.generate())
                .owner(who).origin(WorkspaceOrigin.MANAGED).name(request.name()).profile(profile)
                .access(request.access().orElse(null)).state(WorkspaceState.OPEN).incarnation(newIncarnation())
                .stateSince(now).quota(settings.quota()).createdAt(now).lastActivityAt(now).build());
        emit(SandboxEvent.Type.WORKSPACE_CREATED, created, null, "created", actorOf(actor, who), null);
        return provision(created.id(), who);
    }

    /**
     * Best effort, like admission: two concurrent creates may both pass. Each record is checked against the scan
     * again — a store written before the owner and origin filters existed returns every record.
     */
    private int countManaged(WorkspaceOwner owner) {
        int count = 0;
        WorkspaceScan scan = WorkspaceScan.builder().owner(owner).origin(WorkspaceOrigin.MANAGED).build();
        while (true) {
            final List<SandboxWorkspace> page = store.scan(scan);
            count += (int) page.stream().filter(scan::matches).count();
            if (page.size() < scan.limit()) {
                return count;
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }
    }

    /**
     * Provisions the workspace's {@code primary} slot — a retry after a failed {@link #createManaged}, respecting the
     * recorded failure's backoff, or the step after {@link #reopen}. Needs the use right.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking
     * @return the workspace, {@code primary} RUNNING
     * @throws WorkspaceProvisioningException
     *             when provisioning failed
     * @throws SandboxUnavailableException
     *             when the workspace does not exist or the caller may not use it
     */
    public WorkspaceView provision(SandboxWorkspaceId id, Principal caller) {
        return provision(id, callers.callerOf(Optional.ofNullable(caller)));
    }

    private WorkspaceView provision(SandboxWorkspaceId id, WorkspaceOwner who) {
        final SandboxWorkspace found = store.find(id).orElseThrow(() -> doesNotExist(id));
        checkUse(found, who);
        // The created-with profile is required only for a slot yet to exist: one a definition's sandbox.profile built
        // first is accepted as it is, as connect accepts it for a binding that names none.
        final String required = found.slot(SandboxBinding.PRIMARY).isEmpty() ? found.profile().orElse(null) : null;
        try {
            provisionSlot(id, SandboxBinding.PRIMARY, required, who);
        } catch (SandboxUnavailableException e) {
            throw new WorkspaceProvisioningException(id, e);
        }
        return view(store.find(id).orElseThrow(() -> doesNotExist(id)));
    }

    /**
     * The {@link #connect} loop for a slot, with no command to run: admission, quota, create, verify, seed. The record
     * must exist ({@code mustExist}); the profile, when given, is the required one.
     */
    private void provisionSlot(SandboxWorkspaceId id, String slot, String profile, WorkspaceOwner caller) {
        connect(new Target(id, caller, caller, slot, Optional.ofNullable(profile), SandboxBinding.DEFAULT_ROOT, true));
    }

    /**
     * Deletes a CLOSED managed workspace for good (management design §6.6): the manage right, then the record (a CAS on
     * the version read — a concurrent reopen makes it fail and nothing else happens), a {@code WORKSPACE_PURGED} event,
     * then every session's attachment to it. When that last step fails the janitor's sweep finishes it. An id with no
     * record is success, as for {@link #close}.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking; must have the manage right
     * @throws SandboxUnavailableException
     *             when the caller may not manage it, it is not managed, not CLOSED, or changed during the purge
     */
    public void purge(SandboxWorkspaceId id, Principal caller) {
        purge(id, Acting.as(callers.callerOf(Optional.ofNullable(caller))));
    }

    private void purge(SandboxWorkspaceId id, Acting acting) {
        final Optional<SandboxWorkspace> found = store.find(id);
        if (found.isEmpty()) {
            return;
        }
        final SandboxWorkspace workspace = found.get();
        acting.checkManage(this, workspace);
        if (workspace.origin() != WorkspaceOrigin.MANAGED) {
            // Deleting a derived tombstone would let the session's next turn silently re-create ws:{sid}.
            throw new SandboxUnavailableException("only a managed sandbox workspace can be purged; a derived one's "
                    + "closed record expires after closed-retention");
        }
        if (workspace.state() != WorkspaceState.CLOSED) {
            throw new SandboxUnavailableException(
                    "the sandbox workspace is " + workspace.state() + "; close it before purging it");
        }
        try {
            store.delete(id, workspace.version());
        } catch (StaleVersionException e) {
            throw new SandboxUnavailableException("the sandbox workspace changed while it was being purged (it may "
                    + "have been reopened); it was kept", e);
        }
        emit(SandboxEvent.Type.WORKSPACE_PURGED, workspace, null, "purged", acting.actor(), null);
        try {
            sessionOptions.deleteByWorkspace(id);
        } catch (RuntimeException e) {
            log.warn("Clearing the session options of purged workspace {} failed; the janitor's sweep finishes it: {}",
                    id, e.getMessage());
        }
    }

    /**
     * A workspace as {@code caller} may see it: empty when it does not exist or the caller may not use it — the two
     * answer alike (WS §15).
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking
     * @return its view
     */
    public Optional<WorkspaceView> describe(SandboxWorkspaceId id, Principal caller) {
        final WorkspaceOwner who = callers.callerOf(Optional.ofNullable(caller));
        return store.find(id).filter(workspace -> mayUse(workspace, who)).map(this::view);
    }

    /**
     * The workspaces {@code caller} owns — tenant and principal — filtered and paged by id.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param caller
     *            the principal asking
     * @param query
     *            filters and page
     * @return one page
     */
    public Page<WorkspaceView, SandboxWorkspaceId> listOwned(Principal caller, WorkspaceQuery query) {
        final WorkspaceOwner who = callers.callerOf(Optional.ofNullable(caller));
        return listing(WorkspaceScan.builder().owner(who).origin(query.origin().orElse(null)).states(query.states())
                .afterId(query.after().orElse(null)).limit(query.limit()).build(), query.namePrefix());
    }

    /**
     * One page of {@code scan}, by id. The name prefix is not a store filter: scan on until the page is full or the
     * store is exhausted. Every record is checked against the scan again, so a store that ignores a filter (one written
     * before the owner and origin filters existed) cannot show a workspace the listing excludes.
     */
    private Page<WorkspaceView, SandboxWorkspaceId> listing(WorkspaceScan first, Optional<String> namePrefix) {
        final List<WorkspaceView> items = new ArrayList<>();
        WorkspaceScan scan = first;
        while (true) {
            final List<SandboxWorkspace> page = store.scan(scan);
            for (SandboxWorkspace workspace : page) {
                if (scan.matches(workspace)
                        && namePrefix.map(prefix -> workspace.name().orElse("").startsWith(prefix)).orElse(true)) {
                    items.add(view(workspace));
                    if (items.size() == first.limit()) {
                        return Page.of(items, workspace.id());
                    }
                }
            }
            if (page.size() < scan.limit()) {
                return Page.of(items, null);
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }
    }

    /**
     * Sets a session's sandbox options (management design §6.2): only the session's owner — tenant and principal,
     * whatever any access mode says — and, for an attachment, to a managed workspace that is OPEN or idle-closed and
     * that both the caller and the session's owner may use. Each turn is still checked at {@code connect} against the
     * <i>executing</i> principal: in a multi-participant session a non-owner's turn into the attached workspace passes
     * only if that participant may use it. Touches no sandbox.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param session
     *            the session
     * @param update
     *            the attachment and rule; both replace what is there
     * @param caller
     *            the principal asking
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             without an owner source, for an unknown session, a caller that is not its owner, a
     *             malformed id, or a workspace the session's owner may not use
     * @throws SandboxUnavailableException
     *             when the workspace does not exist, is closing, or was closed explicitly
     */
    public void setSessionOptions(SessionId session, SessionSandboxOptions.Update update, Principal caller) {
        setSessionOptions(session, update, Acting.as(callers.callerOf(Optional.ofNullable(caller))));
    }

    private void setSessionOptions(SessionId session, SessionSandboxOptions.Update update, Acting acting) {
        Objects.requireNonNull(update, "update must not be null");
        if (update.workspace().isEmpty() && update.unattached().isEmpty()) {
            // Nothing to set is a clear: no record is kept that holds nothing.
            clearSessionOptions(session, acting);
            return;
        }
        final WorkspaceOwner sessionOwner = requireSessionOwner(session, acting);
        final WorkspaceOwner who = acting.recordedAs(sessionOwner.tenant());
        final Optional<SandboxWorkspaceId> target = update.workspace()
                .map(id -> ManagedIds.require(id.value(), "the session option's workspace"));
        if (target.isPresent()) {
            final SandboxWorkspace workspace = store.find(target.get()).orElseThrow(() -> doesNotExist(target.get()));
            if (workspace.state() == WorkspaceState.CLOSING || workspace.state() == WorkspaceState.CLOSED
                    && workspace.closeCause().orElse(CloseCause.EXPLICIT) != CloseCause.IDLE) {
                throw new SandboxUnavailableException("sandbox workspace " + workspace.id() + " is " + workspace.state()
                        + "; it has to be reopened before a session can be attached to it");
            }
            // The consistency check, on the administrator's path too: no session is attached where its owner's turns
            // would all be refused (§5.5).
            if (!acting.mayUse(this, workspace) || !mayUse(workspace, sessionOwner)) {
                throw new BindingRejectedException("the owner of session " + session.value()
                        + " is not permitted to use sandbox workspace " + workspace.id());
            }
        }
        writeOptions(session,
                current -> (current != null ? current.toBuilder() : SessionSandboxOptions.builder().session(session))
                        .setBy(who).workspace(target.orElse(null)).unattached(update.unattached().orElse(null))
                        .updatedAt(clock.instant()).build());
        emitSession(SandboxEvent.Type.SESSION_OPTIONS_SET, session, target.orElse(null), sessionOwner, acting.actor(),
                "set: workspace=" + target.map(SandboxWorkspaceId::value).orElse("none") + ", unattached="
                        + update.unattached().map(rule -> rule.name().toLowerCase(Locale.ROOT)).orElse("none"));
    }

    /**
     * Clears a session's attachment and rule, keeping its {@code lastWorkspaceId} so its next turn hears that it moved
     * (§6.4), and its unannounced reset; a record with nothing left is deleted. The same rights as
     * {@link #setSessionOptions}.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param session
     *            the session
     * @param caller
     *            the principal asking
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             as for {@link #setSessionOptions}
     */
    public void clearSessionOptions(SessionId session, Principal caller) {
        clearSessionOptions(session, Acting.as(callers.callerOf(Optional.ofNullable(caller))));
    }

    private void clearSessionOptions(SessionId session, Acting acting) {
        final WorkspaceOwner sessionOwner = requireSessionOwner(session, acting);
        final WorkspaceOwner who = acting.recordedAs(sessionOwner.tenant());
        for (int attempt = 0; attempt <= settings.casRetries(); attempt++) {
            final Optional<SessionSandboxOptions> current = sessionOptions.find(session);
            if (current.isEmpty()) {
                return;
            }
            final SessionSandboxOptions record = current.get();
            // Where the session last ran: recorded, else the workspace it is being detached from. Every tool result now
            // delivers the switch notice (core-033 design §6.4), but its record write is asynchronous and loses its CAS
            // to this clear when the two race: without the fallback the next turn would land elsewhere silently,
            // although the model was last told it runs here.
            final Optional<SandboxWorkspaceId> last = record.lastWorkspaceId().or(record::workspace);
            try {
                if (last.isEmpty() && record.unannouncedReset().isEmpty()) {
                    sessionOptions.delete(session, record.version());
                } else {
                    sessionOptions.put(
                            record.toBuilder().setBy(who).workspace(null).unattached(null)
                                    .lastWorkspaceId(last.orElse(null)).updatedAt(clock.instant()).build(),
                            OptionalLong.of(record.version()));
                }
                emitSession(SandboxEvent.Type.SESSION_OPTIONS_CLEARED, session, record.workspace().orElse(null),
                        sessionOwner, acting.actor(), "cleared");
                return;
            } catch (StaleVersionException e) {
                log.debug("CAS conflict on the options of session {} (attempt {}); re-reading", session.value(),
                        attempt + 1);
            }
        }
        throw optionsBusy();
    }

    /**
     * Deletes a session's options record outright, {@code lastWorkspaceId} and unannounced reset included: the
     * administrator's cleanup for a session deleted outside core's router, which fires no
     * {@code SessionLifecycleListener}. Such a session may be unknown to every owner source by now, and a single-tenant
     * host has none at all — yet a request attribute can still leave a {@code lastWorkspaceId} record behind there —
     * so the owner only names the event: unknown, the event names the record's {@code setBy}.
     */
    private void deleteSessionOptions(SessionId session, Acting acting) {
        Objects.requireNonNull(session, "session must not be null");
        final Optional<WorkspaceOwner> knownOwner = sessionOwners == null
                ? Optional.empty()
                : sessionOwners.ownerOf(session).map(callers::ownerOf);
        for (int attempt = 0; attempt <= settings.casRetries(); attempt++) {
            final Optional<SessionSandboxOptions> current = sessionOptions.find(session);
            if (current.isEmpty()) {
                return;
            }
            try {
                sessionOptions.delete(session, current.get().version());
                emitSession(SandboxEvent.Type.SESSION_OPTIONS_CLEARED, session, current.get().workspace().orElse(null),
                        knownOwner.orElse(current.get().setBy()), acting.actor(), "deleted");
                return;
            } catch (StaleVersionException e) {
                log.debug("CAS conflict on the options of session {} (attempt {}); re-reading", session.value(),
                        attempt + 1);
            }
        }
        throw optionsBusy();
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param session
     *            the session
     * @param caller
     *            the principal asking; the same rights as {@link #setSessionOptions}
     * @return the session's options
     */
    public Optional<SessionOptionsView> sessionOptions(SessionId session, Principal caller) {
        requireSessionOwner(session, Acting.as(callers.callerOf(Optional.ofNullable(caller))));
        return sessionOptions.find(session).map(SessionOptionsView::of);
    }

    /**
     * Keeps a reset of a session's sandbox on its options record until a drain delivers it
     * (docs/design/workspace-sandbox-unannounced-reset.md §2.1, §6.1). Called by the root environment whose
     * {@code connect} reset the slot, synchronously and before the reset notice is queued — so no drain can return the
     * notice before it is durable — on a path that is provisioning a sandbox anyway. A newer reset replaces an older
     * one: both say that the files from before are gone.
     *
     * <p>
     * Best effort and never throws: on a store error or {@code casRetries} lost CASes it logs a WARN and returns empty,
     * and only a reset that is also never drained is lost, as before. A session with no record gets one holding only
     * the reset, unless core's session store is wired and no longer knows the session. Like a {@code lastWorkspaceId}
     * record it grants nothing and lists as no attachment.
     *
     * <p>
     * A switch the same environment already delivered, whose asynchronous {@link #acknowledgeLater} may still be
     * queued, is written along with the reset, under the same strict version CAS: otherwise this write would move the
     * record past {@code switchVersion} first and that acknowledgement would lose its CAS, so the next turn would hear
     * the switch again (design §12).
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param session
     *            the session
     * @param workspace
     *            the workspace whose slot was reset
     * @param slot
     *            the slot
     * @param switchVersion
     *            the version that acknowledgement expects; empty when the session had no record
     * @param lastWorkspace
     *            the delivered switch's target, or empty when no switch acknowledgement may be pending
     * @param setBy
     *            the executing caller, recorded on a record this creates
     * @return the stored record — its version and its reset as the store keeps them — or empty when nothing was
     *         stored
     */
    public Optional<SessionSandboxOptions> markResetUnannounced(SessionId session, SandboxWorkspaceId workspace,
            String slot, OptionalLong switchVersion, Optional<SandboxWorkspaceId> lastWorkspace, WorkspaceOwner setBy) {
        final UnannouncedReset reset = UnannouncedReset.of(workspace, slot, clock.instant());
        try {
            for (int attempt = 0; attempt <= settings.casRetries(); attempt++) {
                final Optional<SessionSandboxOptions> current = sessionOptions.find(session);
                try {
                    if (current.isPresent()) {
                        final SessionSandboxOptions.Builder next = current.get().toBuilder().unannouncedReset(reset);
                        if (lastWorkspace.isPresent() && switchVersion.isPresent()
                                && current.get().version() == switchVersion.getAsLong()) {
                            next.lastWorkspaceId(lastWorkspace.get());
                        }
                        return Optional.of(sessionOptions.put(next.build(), OptionalLong.of(current.get().version())));
                    }
                    final Optional<SessionRecordStore> records = sessionRecords();
                    if (records.isPresent() && !records.get().exists(session)) {
                        log.debug("Session {} no longer exists; its reset is not kept", session.value());
                        return Optional.empty();
                    }
                    return Optional
                            .of(sessionOptions.put(
                                    SessionSandboxOptions.builder().session(session).setBy(setBy)
                                            .lastWorkspaceId(
                                                    switchVersion.isEmpty() ? lastWorkspace.orElse(null) : null)
                                            .unannouncedReset(reset).updatedAt(clock.instant()).build(),
                                    OptionalLong.empty()));
                } catch (StaleVersionException e) {
                    log.debug("CAS conflict on the options of session {} (attempt {}); re-reading", session.value(),
                            attempt + 1);
                }
            }
            log.warn("The options of session {} kept changing; its reset is told only if this execution's notices are "
                    + "drained", session.value());
        } catch (RuntimeException e) {
            log.warn("Keeping the reset of session {} failed; it is told only if this execution's notices are "
                    + "drained: {}", session.value(), e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Records on the background executor what a drain delivered, so that core's {@code drainNotices()}, which triggers
     * it, never waits on the store (core-033 design §6.4, docs/design/workspace-sandbox-unannounced-reset.md §6.2). A
     * refused submission is logged; the worst case is the switch notice, or a reset, told once more.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param session
     *            the session
     * @param expectedVersion
     *            the version of the options record the environment knows — the one the binding policy read, or its
     *            own mark's; empty when the session had no record
     * @param lastWorkspace
     *            where the session connected, when a switch was delivered (or recorded silently)
     * @param delivered
     *            the unannounced reset the drained notices covered
     * @param setBy
     *            the executing caller, recorded on a record this creates
     */
    public void acknowledgeLater(SessionId session, OptionalLong expectedVersion,
            Optional<SandboxWorkspaceId> lastWorkspace, Optional<UnannouncedReset> delivered, WorkspaceOwner setBy) {
        runInBackground("recording the delivered notices of session " + session.value(),
                () -> acknowledge(session, expectedVersion, lastWorkspace, delivered, setBy));
    }

    /**
     * Records what a drain delivered, best effort: a store error or {@code casRetries} lost CASes are logged.
     *
     * <ul>
     * <li>{@code lastWorkspace} is written only while the record is still at {@code expectedVersion} — a CAS on what
     * the binding read, so a re-attach since is never overwritten; the worst case is the switch notice repeating once
     * (§6.4). With no {@code expectedVersion} the session had no record and one is created holding only
     * {@code lastWorkspaceId} — unless core's session store is wired and no longer knows the session: a write queued
     * behind a deletion must not bring the deleted session's state back.</li>
     * <li>{@code delivered} is cleared only while the record still holds exactly that reset: a newer one, written since
     * by another execution of the session, is left for its own delivery. Nothing is created for it.</li>
     * </ul>
     *
     * A record left with nothing in it is deleted. A record created here grants nothing: {@code setBy} is the executing
     * caller, who need not be the session's owner, and every right over the session is checked against the owner
     * source. It has no {@code workspace}, so no listing shows it as an attachment.
     */
    void acknowledge(SessionId session, OptionalLong expectedVersion, Optional<SandboxWorkspaceId> lastWorkspace,
            Optional<UnannouncedReset> delivered, WorkspaceOwner setBy) {
        try {
            for (int attempt = 0; attempt <= settings.casRetries(); attempt++) {
                final Optional<SessionSandboxOptions> current = sessionOptions.find(session);
                try {
                    if (current.isEmpty()) {
                        if (lastWorkspace.isPresent() && expectedVersion.isEmpty()) {
                            final Optional<SessionRecordStore> records = sessionRecords();
                            if (records.isPresent() && !records.get().exists(session)) {
                                log.debug("Session {} no longer exists; its last workspace is not recorded",
                                        session.value());
                                return;
                            }
                            sessionOptions.put(
                                    SessionSandboxOptions.builder().session(session).setBy(setBy)
                                            .lastWorkspaceId(lastWorkspace.get()).updatedAt(clock.instant()).build(),
                                    OptionalLong.empty());
                        }
                        return;
                    }
                    final SessionSandboxOptions record = current.get();
                    final SessionSandboxOptions.Builder next = record.toBuilder();
                    boolean changed = false;
                    if (lastWorkspace.isPresent() && expectedVersion.isPresent()
                            && record.version() == expectedVersion.getAsLong()) {
                        next.lastWorkspaceId(lastWorkspace.get());
                        changed = true;
                    }
                    if (delivered.isPresent() && record.unannouncedReset().equals(delivered)) {
                        next.unannouncedReset(null);
                        changed = true;
                    }
                    if (!changed) {
                        return;
                    }
                    final SessionSandboxOptions updated = next.build();
                    if (updated.workspace().isEmpty() && updated.unattached().isEmpty()
                            && updated.lastWorkspaceId().isEmpty() && updated.unannouncedReset().isEmpty()) {
                        sessionOptions.delete(session, record.version());
                    } else {
                        sessionOptions.put(updated, OptionalLong.of(record.version()));
                    }
                    return;
                } catch (StaleVersionException e) {
                    // Re-read: the switch part then no longer matches its version and drops out, as it always did;
                    // the clear compares the value and still applies.
                    log.debug("CAS conflict on the options of session {} (attempt {}); re-reading", session.value(),
                            attempt + 1);
                }
            }
            log.debug("The options of session {} kept changing; a delivered notice may be told once more",
                    session.value());
        } catch (RuntimeException e) {
            log.debug("Recording the delivered notices of session {} failed; one may be told once more: {}",
                    session.value(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // session deletion (kangwoo/aimon-core#253, docs/design/workspace-sandbox-core-033.md §6.3)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Drops what this module keeps for a deleted session. Called by the session cleanup on the deleting node, under
     * the session's lease, so it does store reads and CAS writes only and no provider call:
     *
     * <ol>
     * <li>the session's options record is deleted — an unannounced reset goes with it: the session is gone;</li>
     * <li>its derived workspace {@code ws:{sessionId}} is marked {@link CloseCause#SESSION_DELETED} — OPEN becomes
     * CLOSING, a CLOSING or CLOSED record keeps its state — so that from now on {@code connect} refuses it and a new
     * session with the same id never reaches the deleted one's sandbox. A managed workspace the session was attached to
     * is shared and has its own owner: it is not touched.</li>
     * </ol>
     *
     * Terminating the sandboxes and deleting the record then run on the background executor
     * ({@link #finishSessionDeletion}); the janitor finishes it if that never happens. Never throws: a store failure is
     * logged, and the janitor's sweeps are behind every step.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     *
     * @param session
     *            the deleted session
     * @param owner
     *            the owner core recorded for it, named on the options event
     */
    public void sessionDeleted(SessionId session, Optional<Principal> owner) {
        deleteOptionsOfDeletedSession(session, owner);
        final SandboxWorkspaceId derived = SandboxWorkspaceId.of("ws:" + session.value());
        final Optional<SandboxWorkspace> marked;
        try {
            marked = mutate(derived, current -> {
                if (current.origin() != WorkspaceOrigin.DERIVED) {
                    return null;
                }
                if (current.closeCause().orElse(null) == CloseCause.SESSION_DELETED
                        && current.state() != WorkspaceState.OPEN) {
                    return current;
                }
                return switch (current.state()) {
                    case OPEN -> current.toBuilder().state(WorkspaceState.CLOSING).stateSince(clock.instant())
                            .closeCause(CloseCause.SESSION_DELETED).build();
                    // An idle close in flight keeps passing its own guard (state and incarnation) and ends CLOSED with
                    // this cause; an idle-closed record or a tombstone holds no sandbox and is only deleted.
                    case CLOSING, CLOSED -> current.toBuilder().closeCause(CloseCause.SESSION_DELETED).build();
                };
            });
        } catch (RuntimeException e) {
            log.warn("Marking workspace {} of deleted session {} failed; it ends by the idle policy: {}", derived,
                    session.value(), e.getMessage());
            return;
        }
        marked.ifPresent(workspace -> runInBackground("removing workspace " + derived + " of a deleted session",
                () -> finishSessionDeletion(derived, workspace.incarnation())));
    }

    private void deleteOptionsOfDeletedSession(SessionId session, Optional<Principal> owner) {
        try {
            for (int attempt = 0; attempt <= settings.casRetries(); attempt++) {
                final Optional<SessionSandboxOptions> current = sessionOptions.find(session);
                if (current.isEmpty()) {
                    return;
                }
                try {
                    sessionOptions.delete(session, current.get().version());
                } catch (StaleVersionException e) {
                    continue;
                }
                emitSession(SandboxEvent.Type.SESSION_OPTIONS_CLEARED, session, current.get().workspace().orElse(null),
                        ownerForEvent(owner, current.get()), null, "session deleted");
                return;
            }
            log.warn("The options of deleted session {} kept changing; the janitor's sweep deletes them",
                    session.value());
        } catch (RuntimeException e) {
            log.warn("Deleting the options of deleted session {} failed; the janitor's sweep deletes them: {}",
                    session.value(), e.getMessage());
        }
    }

    private WorkspaceOwner ownerForEvent(Optional<Principal> owner, SessionSandboxOptions record) {
        try {
            return owner.map(callers::ownerOf).orElse(record.setBy());
        } catch (RuntimeException e) {
            return record.setBy();
        }
    }

    /**
     * The deferred part of a session deletion, also the janitor's: closes the workspace — terminate, destroy, wait up
     * to {@code close-wait} — while it is still CLOSING with {@code incarnation}, then deletes the record once it is
     * {@code CLOSED(SESSION_DELETED)} with that incarnation, without {@code closed-retention}. Never throws.
     *
     * @param id
     *            the derived workspace
     * @param incarnation
     *            the incarnation that was marked; a record reopened since is left alone
     */
    void finishSessionDeletion(SandboxWorkspaceId id, String incarnation) {
        final Predicate<SandboxWorkspace> marked = current -> current.incarnation().equals(incarnation)
                && current.closeCause().orElse(null) == CloseCause.SESSION_DELETED;
        try {
            closeProcedure(id, CloseCause.SESSION_DELETED,
                    current -> current.state() == WorkspaceState.CLOSING && marked.test(current));
            final Optional<SandboxWorkspace> closed = store.find(id)
                    .filter(current -> current.state() == WorkspaceState.CLOSED && marked.test(current));
            if (closed.isPresent()) {
                store.delete(id, closed.get().version());
                emit(SandboxEvent.Type.WORKSPACE_PURGED, closed.get(), null, "session deleted");
            }
        } catch (StaleVersionException e) {
            // The janitor and this task race for the delete; whoever lost has nothing left to do.
            log.debug("Workspace {} of a deleted session changed while it was being removed: {}", id, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Removing workspace {} of a deleted session did not finish; the janitor does: {}", id,
                    e.getMessage());
        }
    }

    /** Hands one-shot work to the background executor; a refusal is logged, never thrown. */
    private void runInBackground(String what, Runnable task) {
        try {
            background.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("The background executor refused {}; it is left to the janitor or a repeated notice: {}", what,
                    e.getMessage());
        }
    }

    /**
     * The session's owner, which the caller must be — the right over a session is its owner's (§6.2) — unless an
     * administrator acts (§5.5): the lookup is still required and must know the session.
     */
    private WorkspaceOwner requireSessionOwner(SessionId session, Acting acting) {
        Objects.requireNonNull(session, "session must not be null");
        if (sessionOwners == null) {
            throw new BindingRejectedException("session options need a session owner lookup (SessionOwnerLookup)");
        }
        final WorkspaceOwner owner = sessionOwners.ownerOf(session).map(callers::ownerOf)
                .orElseThrow(() -> new BindingRejectedException("unknown session " + session.value()));
        if (!acting.isAdministrator() && !owner.equals(acting.recordedAs(owner.tenant()))) {
            throw new BindingRejectedException(
                    "only the owner of session " + session.value() + " may change its sandbox options");
        }
        return owner;
    }

    /** Read → apply → CAS on the session's options, re-read and re-applied on conflict. */
    private SessionSandboxOptions writeOptions(SessionId session, UnaryOperator<SessionSandboxOptions> change) {
        for (int attempt = 0; attempt <= settings.casRetries(); attempt++) {
            final Optional<SessionSandboxOptions> current = sessionOptions.find(session);
            try {
                return sessionOptions.put(change.apply(current.orElse(null)),
                        current.map(c -> OptionalLong.of(c.version())).orElse(OptionalLong.empty()));
            } catch (StaleVersionException e) {
                log.debug("CAS conflict on the options of session {} (attempt {}); re-reading", session.value(),
                        attempt + 1);
            }
        }
        throw optionsBusy();
    }

    private SandboxUnavailableException optionsBusy() {
        return new SandboxUnavailableException("the session's sandbox options are busy (" + settings.casRetries()
                + " concurrent updates in a row); retry the call");
    }

    private static SandboxUnavailableException doesNotExist(SandboxWorkspaceId id) {
        return new SandboxUnavailableException("sandbox workspace " + id + " does not exist");
    }

    /** The actor of an event: the resolved principal, or its {@code TYPE:id} when no tenant is known for it. */
    private String actorOf(Principal actor, WorkspaceOwner fallback) {
        if (actor == null) {
            return Objects.requireNonNull(fallback, "an event without an actor principal needs a fallback").toString();
        }
        try {
            return callers.ownerOf(actor).toString();
        } catch (RuntimeException e) {
            return actor.getType().name() + ":" + actor.getId();
        }
    }

    private WorkspaceView view(SandboxWorkspace workspace) {
        return WorkspaceView.of(workspace, settings.workspaceAccess());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // administration (docs/design/workspace-sandbox-management.md §5.5, §6.7, §6.8) — no owner check on the actor
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Who a management call acts as: a principal holding the owner's rights, or an administrator, who passes no owner
     * or tenant check and is only recorded (§5.5).
     */
    private static final class Acting {
        private final WorkspaceOwner who;
        private final Principal administrator;
        /** The administrator as the tenant resolver sees it; {@code null} when it knows no tenant for it. */
        private final WorkspaceOwner resolved;
        private final String actor;

        private Acting(WorkspaceOwner who, Principal administrator, WorkspaceOwner resolved, String actor) {
            this.who = who;
            this.administrator = administrator;
            this.resolved = resolved;
            this.actor = actor;
        }

        static Acting as(WorkspaceOwner who) {
            return new Acting(who, null, null, who.toString());
        }

        static Acting administrator(Principal actor, WorkspaceOwner resolved) {
            return new Acting(null, actor, resolved,
                    resolved != null ? resolved.toString() : actor.getType().name() + ":" + actor.getId());
        }

        boolean isAdministrator() {
            return who == null;
        }

        /** @return the event's actor */
        String actor() {
            return actor;
        }

        /**
         * @param fallbackTenant
         *            the tenant an administrator is recorded in when the resolver knows none for it
         * @return who to record as having set a session's options
         */
        WorkspaceOwner recordedAs(TenantId fallbackTenant) {
            if (who != null) {
                return who;
            }
            return resolved != null ? resolved : WorkspaceOwner.of(fallbackTenant, administrator);
        }

        void checkManage(SandboxWorkspaceManager manager, SandboxWorkspace workspace) {
            if (who != null) {
                manager.checkManage(workspace, who);
            }
        }

        boolean mayUse(SandboxWorkspaceManager manager, SandboxWorkspace workspace) {
            return who == null || manager.mayUse(workspace, who);
        }
    }

    private Acting administrator(Principal actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        WorkspaceOwner resolved;
        try {
            resolved = callers.ownerOf(actor);
        } catch (RuntimeException e) {
            resolved = null;
        }
        return Acting.administrator(actor, resolved);
    }

    /**
     * Every workspace that matches, paged by id; each scanned record is checked against the query again.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param query
     *            filters and page
     * @return one page
     */
    public Page<WorkspaceView, SandboxWorkspaceId> adminWorkspaces(AdminWorkspaceQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return listing(WorkspaceScan.builder().states(query.states()).origin(query.origin().orElse(null))
                .tenant(query.tenant().orElse(null)).owner(query.owner().orElse(null))
                .afterId(query.after().orElse(null)).limit(query.limit()).build(), query.namePrefix());
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param id
     *            the workspace
     * @return its view, whoever owns it
     */
    public Optional<WorkspaceView> adminDescribe(SandboxWorkspaceId id) {
        return store.find(id).map(this::view);
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param id
     *            the workspace
     * @param after
     *            the page starts after this session
     * @param limit
     *            the page size
     * @return the sessions attached to it, by session id
     */
    public Page<SessionOptionsView, SessionId> adminSessions(SandboxWorkspaceId id, Optional<SessionId> after,
            int limit) {
        final Page<SessionSandboxOptions, SessionId> page = sessionOptions.byWorkspace(id, after, limit);
        return Page.of(page.items().stream().map(SessionOptionsView::of).toList(), page.next().orElse(null));
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param session
     *            the session
     * @param update
     *            the attachment and rule
     * @param actor
     *            the operator, recorded
     */
    public void adminSetSessionOptions(SessionId session, SessionSandboxOptions.Update update, Principal actor) {
        setSessionOptions(session, update, administrator(actor));
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param session
     *            the session
     * @param actor
     *            the operator, recorded
     */
    public void adminClearSessionOptions(SessionId session, Principal actor) {
        clearSessionOptions(session, administrator(actor));
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param session
     *            the session; it need not exist any more
     * @param actor
     *            the operator, recorded
     */
    public void adminDeleteSessionOptions(SessionId session, Principal actor) {
        deleteSessionOptions(session, administrator(actor));
    }

    /**
     * Terminates one slot (§6.7): CAS to TERMINATED, then destroy, with cause "terminated by administrator". The
     * workspace stays OPEN and the next use provisions a new generation; a command running in it fails as "lost". A
     * slot that is absent or already TERMINATED is left alone.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param id
     *            the workspace
     * @param slot
     *            the slot
     * @param actor
     *            the operator, recorded
     * @return whether this call terminated it
     * @throws SandboxUnavailableException
     *             when the workspace does not exist
     */
    public boolean adminTerminateSlot(SandboxWorkspaceId id, String slot, Principal actor) {
        final Acting acting = administrator(actor);
        final SandboxWorkspace found = store.find(id).orElseThrow(() -> doesNotExist(id));
        final SandboxSlot current = found.slot(slot).orElseThrow(
                () -> new SandboxUnavailableException("sandbox workspace " + id + " has no slot '" + slot + "'"));
        if (current.state() == SlotState.PROVISIONING) {
            throw provisioningRefused(id, slot);
        }
        // Re-checked inside the CAS: a slot that started provisioning since is refused the same way, not terminated
        // under its claimer — whose create would then leave an orphan and provision again at once.
        final boolean[] provisioning = new boolean[1];
        final boolean terminated = terminateSlot(id, slot, "terminated by administrator", workspace -> true, s -> {
            provisioning[0] = s.state() == SlotState.PROVISIONING;
            return !provisioning[0];
        }, acting.actor());
        if (provisioning[0]) {
            throw provisioningRefused(id, slot);
        }
        return terminated;
    }

    private static SandboxUnavailableException provisioningRefused(SandboxWorkspaceId id, String slot) {
        return new SandboxUnavailableException("slot '" + slot + "' of sandbox workspace " + id + " is provisioning; "
                + "terminate it once that has settled (the janitor reclaims an abandoned claim)");
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param id
     *            the workspace
     * @param actor
     *            the operator, recorded
     */
    public void adminClose(SandboxWorkspaceId id, Principal actor) {
        close(id, administrator(actor));
    }

    /**
     * Reopens and provisions, as the owner-facing path does, with no owner check.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param id
     *            the workspace
     * @param actor
     *            the operator, recorded
     * @return the workspace, {@code primary} RUNNING
     */
    public WorkspaceView adminReopen(SandboxWorkspaceId id, Principal actor) {
        final Acting acting = administrator(actor);
        final SandboxWorkspace before = store.find(id).orElseThrow(() -> doesNotExist(id));
        // The owner through the principal gate first, as create on behalf of does: the deployment may refuse a
        // principal it once accepted (require-principal turned on, an allowed system principal removed).
        callers.callerOf(Optional.ofNullable(principalOf(before.owner())));
        reopen(id, acting);
        final SandboxWorkspace found = store.find(id).orElseThrow(() -> doesNotExist(id));
        // As its owner: the use check inside connect then always passes, as an operator's call must.
        return provision(id, found.owner());
    }

    /** @return the principal an owner was recorded from; {@code null} for an {@code anonymous} owner */
    private static Principal principalOf(WorkspaceOwner owner) {
        if (WorkspaceOwner.ANONYMOUS.equals(owner.principal())) {
            return null;
        }
        final int colon = owner.principal().indexOf(':');
        final String id = owner.principal().substring(colon + 1);
        return Principal.builder().type(Principal.Type.valueOf(owner.principal().substring(0, colon))).id(id)
                .displayName(id).build();
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @param id
     *            the workspace
     * @param actor
     *            the operator, recorded
     */
    public void adminPurge(SandboxWorkspaceId id, Principal actor) {
        purge(id, administrator(actor));
    }

    /**
     * Every sandbox the provider runs for this deployment, with the reconciler's verdict (§6.8): the store is scanned
     * first, to the end, then the provider listed — the reconciler's order (WS §10.4). Informational: nothing is
     * destroyed. O(records + sandboxes), uncached.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release. Use {@code SandboxAdministration}.
     *
     * @return one view per listed sandbox
     * @throws at.aimon.sandbox.provider.SandboxProviderException
     *             when the provider cannot list: no partial view that hides sandboxes
     */
    public List<SandboxView> adminSandboxes() {
        final Map<String, SandboxWorkspace> byHash = new LinkedHashMap<>();
        WorkspaceScan scan = WorkspaceScan.builder().build();
        while (true) {
            final List<SandboxWorkspace> page = store.scan(scan);
            page.forEach(record -> byHash.put(SandboxLabels.h(record.id().value()), record));
            if (page.size() < scan.limit()) {
                break;
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }
        final List<ProviderSandbox> listed = provider
                .list(Map.of(SandboxLabels.MANAGED, "true", SandboxLabels.DEPLOYMENT, settings.deployment()));
        final Instant now = clock.instant();
        final List<SandboxView> views = new ArrayList<>();
        for (ProviderSandbox sandbox : listed) {
            final SandboxWorkspace record = byHash.get(sandbox.labels().get(SandboxLabels.WORKSPACE));
            final SandboxReconciler.Verdict verdict = SandboxReconciler.classify(record, sandbox);
            // What the janitor waits for: the grace of an ORPHAN or a DUPLICATE, and of an IN_FLIGHT sandbox ahead of
            // its record's generation, which it treats as ORPHAN once the grace is over (§6.8).
            final boolean graced = verdict.afterGrace() || verdict == SandboxReconciler.Verdict.IN_FLIGHT
                    && SandboxReconciler.inFlightByGeneration(record, sandbox);
            final SandboxView.Builder view = SandboxView.builder().refHash(SandboxLabels.h(sandbox.ref().sandboxId()))
                    .providerState(sandbox.state()).expiresAt(sandbox.expiresAt().orElse(null))
                    .createdAt(sandbox.createdAt().orElse(null)).verdict(SandboxVerdict.valueOf(verdict.name()));
            if (record != null) {
                final long generation = SandboxReconciler.parseGeneration(sandbox);
                view.workspaceId(record.id()).slot(sandbox.labels().get(SandboxLabels.SLOT))
                        .generation(generation < 0 ? null : generation);
            }
            if (graced) {
                // Only the provider's createdAt: the reconciler's firstSeen is node-local and not visible here.
                sandbox.createdAt().ifPresent(created -> view
                        .pastGrace(!Duration.between(created, now).minus(settings.orphanGrace()).isNegative()));
            }
            views.add(view.build());
        }
        return views;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // reconciliation (§10.4 item 2) — every write guarded on generation, providerRef and state
    // ---------------------------------------------------------------------------------------------------------------

    private static Optional<SandboxSlot> sameSandbox(SandboxWorkspace current, SandboxSlot slot) {
        return current.slot(slot.name()).filter(s -> (s.state() == SlotState.RUNNING || s.state() == SlotState.PAUSED)
                && s.generation() == slot.generation() && s.providerRef().equals(slot.providerRef()));
    }

    /**
     * Records that reconciliation found the slot's sandbox missing, unless it already was.
     *
     * @return whether this call recorded it
     */
    boolean markMissing(SandboxWorkspaceId id, SandboxSlot slot, Instant now) {
        return mutate(id, current -> sameSandbox(current, slot).filter(s -> s.missingSince().isEmpty())
                .map(s -> current.withSlot(s.toBuilder().missingSince(now).build())).orElse(null)).isPresent();
    }

    /**
     * Clears a {@code missingSince} after the sandbox was seen again.
     *
     * @return whether this call cleared it
     */
    boolean clearMissing(SandboxWorkspaceId id, SandboxSlot slot) {
        return mutate(id, current -> sameSandbox(current, slot).filter(s -> s.missingSince().isPresent())
                .map(s -> current.withSlot(s.toBuilder().missingSince(null).build())).orElse(null)).isPresent();
    }

    /**
     * Declares the slot's sandbox LOST once it has been missing for {@code lost-confirm-after} (§10.4): TERMINATED with
     * {@code lostAt}, the connection evicted, a {@code LOST} event. The next call provisions a new generation and tells
     * the model {@code /workspace} was reset.
     *
     * @return whether this call declared it lost
     */
    boolean confirmLost(SandboxWorkspaceId id, SandboxSlot slot, Instant now) {
        // The missingSince the CAS acted on, which may be newer than the caller's snapshot.
        final AtomicReference<Instant> missingSince = new AtomicReference<>();
        final Optional<SandboxWorkspace> lost = mutate(id,
                current -> sameSandbox(current, slot)
                        .filter(s -> s.missingSince()
                                .map(since -> !now.isBefore(since.plus(settings.lostConfirmAfter()))).orElse(false))
                        .map(s -> {
                            missingSince.set(s.missingSince().orElseThrow());
                            return current
                                    .withSlot(s.terminated(now).toBuilder().missingSince(null).lostAt(now).build());
                        }).orElse(null));
        if (lost.isEmpty()) {
            return false;
        }
        slot.providerRef().ifPresent(connections::evict);
        final SandboxWorkspace stored = lost.get();
        emit(SandboxEvent.Type.LOST, stored, stored.slot(slot.name()).orElseThrow(),
                "reconciliation found the sandbox missing since " + missingSince.get());
        return true;
    }

    SandboxProvider provider() {
        return provider;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The use right (§8.3; management design §6.5): the tenant always, the principal too unless the workspace's access
     * — its own, else {@code workspace-access} — is {@code tenant}. Checked by {@code connect}, {@code provision},
     * {@code describe} and for the target of session options. The message says nothing about the workspace beyond "not
     * permitted" (§15).
     *
     * @throws SandboxUnavailableException
     *             when {@code caller} may not use it
     */
    void checkUse(SandboxWorkspace workspace, WorkspaceOwner caller) {
        if (!mayUse(workspace, caller)) {
            throw notPermitted();
        }
    }

    /**
     * @return whether {@code caller} has the use right on {@code workspace}
     */
    boolean mayUse(SandboxWorkspace workspace, WorkspaceOwner caller) {
        final WorkspaceOwner owner = workspace.owner();
        return owner.tenant().equals(caller.tenant())
                && (workspace.access().orElse(settings.workspaceAccess()) == SandboxSettings.WorkspaceAccess.TENANT
                        || owner.principal().equals(caller.principal()));
    }

    /**
     * The manage right (management design §6.5) of {@code close}, {@code reopen} and {@code purge}: on a
     * {@code MANAGED} record the owner's tenant and principal, whatever its access mode — sharing a workspace lets
     * others use it, not end it; on a {@code DERIVED} record the use right, as before.
     *
     * @throws SandboxUnavailableException
     *             when {@code caller} may not manage it
     */
    void checkManage(SandboxWorkspace workspace, WorkspaceOwner caller) {
        if (workspace.origin() == WorkspaceOrigin.DERIVED) {
            checkUse(workspace, caller);
        } else if (!workspace.owner().equals(caller)) {
            throw new SandboxUnavailableException("this principal is not permitted to manage the sandbox workspace");
        }
    }

    private static SandboxUnavailableException notPermitted() {
        return new SandboxUnavailableException("this principal is not permitted to use the sandbox workspace");
    }

    /**
     * Read → decide → CAS, retried on conflict. {@code change} returns the record to store, the same instance to keep
     * it unchanged, or {@code null} to stop.
     *
     * @return the stored (or unchanged) record, or empty when the record is absent or {@code change} stopped
     */
    Optional<SandboxWorkspace> mutate(SandboxWorkspaceId id, UnaryOperator<SandboxWorkspace> change) {
        return mutate(id, change, settings.casRetries());
    }

    private Optional<SandboxWorkspace> mutate(SandboxWorkspaceId id, UnaryOperator<SandboxWorkspace> change,
            int retries) {
        for (int attempt = 0; attempt <= retries; attempt++) {
            final Optional<SandboxWorkspace> current = store.find(id);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            final SandboxWorkspace next = change.apply(current.get());
            if (next == null) {
                return Optional.empty();
            }
            if (next == current.get()) {
                return current;
            }
            try {
                return Optional.of(store.update(id, current.get().version(), next));
            } catch (StaleVersionException e) {
                log.debug("CAS conflict on {} (attempt {}); re-reading", id, attempt + 1);
                if (attempt >= CONFLICTS_BEFORE_BACKOFF) {
                    sleep(Duration.ofMillis(1 + RANDOM.nextInt(5)));
                }
            }
        }
        throw busy(id);
    }

    private SandboxUnavailableException busy(SandboxWorkspaceId id) {
        return new SandboxUnavailableException("the sandbox workspace is busy (" + settings.casRetries()
                + " concurrent updates in a row); retry the call");
    }

    /**
     * Destroys, then evicts: a connection a concurrent call cached between an eviction and the destroy would outlive
     * the sandbox, and nothing would evict it again. After the destroy, a new {@code connect} answers not found.
     */
    void destroyQuietly(ProviderSandboxRef ref) {
        try {
            provider.destroy(ref);
        } catch (RuntimeException e) {
            log.warn("Destroying {} failed; it is left to provider expiry: {}", ref, e.getMessage());
        } finally {
            connections.evict(ref);
        }
    }

    void emit(SandboxEvent.Type type, SandboxWorkspace workspace, SandboxSlot slot, String cause) {
        emit(type, workspace, slot, cause, null, null);
    }

    private void emit(SandboxEvent.Type type, SandboxWorkspace workspace, SandboxSlot slot, String cause, String actor,
            SessionId session) {
        final SandboxEvent.Builder event = SandboxEvent.builder().type(type).workspaceId(workspace.id())
                .owner(workspace.owner()).cause(cause).actor(actor).session(session).at(clock.instant());
        if (slot != null) {
            event.slot(slot);
        }
        publish(type, event);
    }

    private void emitSession(SandboxEvent.Type type, SessionId session, SandboxWorkspaceId workspace,
            WorkspaceOwner sessionOwner, String actor, String cause) {
        publish(type, SandboxEvent.builder().type(type).session(session).workspaceId(workspace).owner(sessionOwner)
                .actor(actor).cause(cause).at(clock.instant()));
    }

    private void publish(SandboxEvent.Type type, SandboxEvent.Builder event) {
        try {
            events.onEvent(event.build());
        } catch (RuntimeException | LinkageError e) {
            // A LinkageError too: a listener built against another version must not fail what it hears of.
            log.warn("SandboxEventListener failed on {}: {}", type, e.getMessage(), e);
        }
    }

    private static String newIncarnation() {
        final char[] chars = new char[8];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = INCARNATION_CHARS[RANDOM.nextInt(INCARNATION_CHARS.length)];
        }
        return new String(chars);
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxUnavailableException("interrupted while waiting for the sandbox", e);
        }
    }

    SandboxSettings settings() {
        return settings;
    }

    SandboxProfileRegistry profiles() {
        return profiles;
    }

    SandboxWorkspaceStore store() {
        return store;
    }

    /** @return core's session store, when the assembly wires one */
    Optional<SessionRecordStore> sessionRecords() {
        return sessionRecords == null ? Optional.empty() : Optional.ofNullable(sessionRecords.get());
    }

    SessionOptionsStore sessionOptionsStore() {
        return sessionOptions;
    }

    Clock clock() {
        return clock;
    }

    SandboxConnectionCache connections() {
        return connections;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // activity (§5.3, §10.3)
    // ---------------------------------------------------------------------------------------------------------------

    private SlotActivity activity(SandboxWorkspace workspace, SandboxSlot slot, SandboxProfile profile) {
        return new BoundActivity(workspace.id(), workspace.owner(), slot, profile);
    }

    /** {@link SlotActivity} bound to one generation and sandbox. */
    private final class BoundActivity implements SlotActivity {
        private final SandboxWorkspaceId id;
        private final WorkspaceOwner owner;
        private final SandboxSlot slot;
        private final SandboxProfile profile;

        private BoundActivity(SandboxWorkspaceId id, WorkspaceOwner owner, SandboxSlot slot, SandboxProfile profile) {
            this.id = id;
            this.owner = owner;
            this.slot = slot;
            this.profile = profile;
        }

        @Override
        public void record(boolean force) {
            if (!touch(force)) {
                throw new SandboxUnavailableException(LOST_MESSAGE);
            }
        }

        /** @return false when the sandbox is gone (the slot was marked lost) */
        boolean touch(boolean force) {
            final Instant now = clock.instant();
            if (!force) {
                final Optional<SandboxSlot> current = store.find(id).flatMap(w -> w.slot(slot.name()));
                if (current.isEmpty() || Duration.between(current.get().lastActivityAt(), now)
                        .compareTo(settings.activityWriteInterval()) < 0) {
                    return true;
                }
            }
            final Optional<SandboxWorkspace> written = mutate(id,
                    current -> sameGeneration(current, slot)
                            .map(s -> current.withSlot(s.withLastActivityAt(now)).withLastActivityAt(now)).orElse(null),
                    ACTIVITY_RETRIES);
            if (written.isEmpty()) {
                return true;
            }
            try {
                provider.extendExpiry(slot.providerRef().orElseThrow(),
                        now.plus(profile.terminateAfter().orElseThrow()));
            } catch (SandboxNotFoundException e) {
                markLost();
                return false;
            } catch (RuntimeException e) {
                log.warn("Extending the expiry of {} failed: {}", slot.providerRef().orElse(null), e.getMessage());
            }
            return true;
        }

        @Override
        public Heartbeat startHeartbeat(boolean background, Runnable onTick, Runnable onLost) {
            return new ActivityHeartbeat(this::touch, scheduler, clock, settings.activityWriteInterval(),
                    background ? profile.backgroundHeartbeatLimit() : null, onTick, onLost);
        }

        @Override
        public void markLost() {
            final Instant now = clock.instant();
            final Optional<SandboxWorkspace> lost = mutate(id, current -> current.slot(slot.name()).filter(
                    s -> s.generation() == slot.generation() && s.providerRef().equals(slot.providerRef()) && s.live())
                    .map(s -> current.withSlot(s.terminated(now).toBuilder().lostAt(now).build())).orElse(null));
            slot.providerRef().ifPresent(connections::evict);
            lost.ifPresent(w -> emit(SandboxEvent.Type.LOST, w, slot,
                    "the provider no longer has " + slot.providerRef().map(Object::toString).orElse("the sandbox")));
            if (lost.isEmpty()) {
                log.debug("{} of {} was already replaced when it was found lost", slot, owner);
            }
        }
    }

    /**
     * Builder for {@link SandboxWorkspaceManager}; {@code WorkspaceSandbox} is the usual way to get one.
     *
     * <p>
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
     * any release.
     */
    public static final class Builder {
        private SandboxSettings settings;
        private SandboxProfileRegistry profiles;
        private SandboxProvider provider;
        private SandboxWorkspaceStore store;
        private SessionOptionsStore sessionOptions = new InMemorySessionOptionsStore();
        private SandboxConnectionCache connections;
        private SandboxAdmission admission;
        private SandboxEventListener events = SandboxEventListener.NOOP;
        private CallerResolver callers;
        private SessionOwnerLookup sessionOwners;
        private Supplier<? extends SessionRecordStore> sessionRecords;
        private Clock clock = Clock.systemUTC();
        private SandboxScheduler scheduler;
        private Executor background = Runnable::run;

        private Builder() {
        }

        public Builder settings(SandboxSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder profiles(SandboxProfileRegistry profiles) {
            this.profiles = profiles;
            return this;
        }

        public Builder provider(SandboxProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder store(SandboxWorkspaceStore store) {
            this.store = store;
            return this;
        }

        /** Default: a new {@link InMemorySessionOptionsStore}. */
        public Builder sessionOptions(SessionOptionsStore sessionOptions) {
            this.sessionOptions = sessionOptions;
            return this;
        }

        public Builder connections(SandboxConnectionCache connections) {
            this.connections = connections;
            return this;
        }

        public Builder admission(SandboxAdmission admission) {
            this.admission = admission;
            return this;
        }

        public Builder events(SandboxEventListener events) {
            this.events = events;
            return this;
        }

        public Builder callers(CallerResolver callers) {
            this.callers = callers;
            return this;
        }

        /** Default: none — session options are then refused (management design §6.2). */
        public Builder sessionOwners(SessionOwnerLookup sessionOwners) {
            this.sessionOwners = sessionOwners;
            return this;
        }

        /**
         * Default: none — the janitor does not sweep the options of deleted sessions, and a {@code lastWorkspaceId}
         * record is created without checking that its session still exists.
         */
        public Builder sessionRecords(Supplier<? extends SessionRecordStore> sessionRecords) {
            this.sessionRecords = sessionRecords;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder scheduler(SandboxScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Runs one-shot work: the deferred part of a session deletion and the {@code lastWorkspaceId} write after a
         * drain. Default: inline, on the calling thread — {@code WorkspaceSandbox} supplies a pool of its own.
         */
        public Builder background(Executor background) {
            this.background = background;
            return this;
        }

        public SandboxWorkspaceManager build() {
            return new SandboxWorkspaceManager(this);
        }
    }
}
