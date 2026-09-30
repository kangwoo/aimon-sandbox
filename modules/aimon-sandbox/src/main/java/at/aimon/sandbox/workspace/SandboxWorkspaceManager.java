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
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.base.Principal;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.binding.CallerResolver;
import at.aimon.sandbox.binding.SandboxBinding;
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

    /** The notice a caller gets when its workspace came back empty after an idle close (§10.5, §15). */
    public static final String RESET_NOTICE = "/workspace was reset: this workspace was closed after being idle, and "
            + "its files and shell state are gone";

    /** The error of a command whose sandbox disappeared under it (§15). */
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
    private final SandboxConnectionCache connections;
    private final SandboxAdmission admission;
    private final SandboxEventListener events;
    private final CallerResolver callers;
    private final Clock clock;
    private final SandboxScheduler scheduler;
    private final SandboxSeeder seeder;

    private SandboxWorkspaceManager(Builder builder) {
        this.settings = Objects.requireNonNull(builder.settings, "settings must not be null");
        this.profiles = Objects.requireNonNull(builder.profiles, "profiles must not be null");
        this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
        this.store = Objects.requireNonNull(builder.store, "store must not be null");
        this.connections = Objects.requireNonNull(builder.connections, "connections must not be null");
        this.admission = Objects.requireNonNull(builder.admission, "admission must not be null");
        this.events = Objects.requireNonNull(builder.events, "events must not be null");
        this.callers = Objects.requireNonNull(builder.callers, "callers must not be null");
        this.clock = Objects.requireNonNull(builder.clock, "clock must not be null");
        this.scheduler = Objects.requireNonNull(builder.scheduler, "scheduler must not be null");
        this.seeder = new SandboxSeeder(settings.provisionTimeout(), provider.capabilities().controlPlaneEndpoints());
    }

    /**
     * For tests and custom assemblies. <b>Runs no startup validation</b>: build through {@code WorkspaceSandbox}, which
     * refuses the settings this version cannot honour (§13.2).
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
     * @param binding
     *            the execution's binding
     * @return the connected slot
     * @throws SandboxUnavailableException
     *             with a model-facing reason when the slot cannot be used — also when the store or the admission
     *             fails, and carrying the notices this call gathered (a reset the model must hear of)
     */
    public ConnectedSlot connect(SandboxBinding binding) {
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

    /** One {@link #connect} call's state across its loop iterations. */
    private final class Connect {
        private final SandboxBinding binding;
        private final Instant started;
        private final long startedNanos = System.nanoTime();
        private final List<String> notices = new ArrayList<>();
        private int conflicts;
        private boolean provisioned;
        private int provisionings;
        private Duration pollDelay = FIRST_POLL;

        private Connect(SandboxBinding binding) {
            this.binding = binding;
            this.started = clock.instant();
        }

        /**
         * The error with this call's notices appended. A reset notice (idle reopen, a lost or terminated sandbox) is
         * otherwise lost when the recreate that follows it fails: the next call finds the slot FAILED, not
         * TERMINATED, and has nothing to tell (§15).
         */
        private SandboxUnavailableException withNotices(SandboxUnavailableException e) {
            if (notices.isEmpty()) {
                return e;
            }
            return new SandboxUnavailableException(e.getMessage() + " (also: " + String.join("; ", notices) + ")", e);
        }

        /** @return the connected slot, or {@code null} to go round again */
        private ConnectedSlot step() {
            SandboxWorkspace workspace = loadOrCreate(binding);
            checkOwner(workspace, binding.caller());
            if (workspace.state() == WorkspaceState.CLOSED
                    && workspace.closeCause().orElse(CloseCause.EXPLICIT) == CloseCause.IDLE) {
                workspace = store.update(workspace.id(), workspace.version(), reopened(workspace, clock.instant()));
                notices.add(RESET_NOTICE);
                emit(SandboxEvent.Type.WORKSPACE_REOPENED, workspace, null, "reopened after an idle close");
            } else if (workspace.state() == WorkspaceState.CLOSING) {
                throw new SandboxUnavailableException("the sandbox workspace is being closed; retry shortly");
            } else if (workspace.state() != WorkspaceState.OPEN) {
                throw new SandboxUnavailableException("the sandbox workspace is closed; the application has to reopen "
                        + "it before it can be used again");
            }
            final SandboxSlot slot = workspace.slot(binding.slot()).orElse(null);
            final SandboxProfile profile = profileFor(binding, slot);
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
            return new ConnectedSlot(workspace, slot, profile, connection, notices, activity(workspace, slot, profile));
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

    private SandboxWorkspace loadOrCreate(SandboxBinding binding) {
        final Optional<SandboxWorkspace> found = store.find(binding.workspaceId());
        if (found.isPresent()) {
            return found.get();
        }
        final Instant now = clock.instant();
        return store.createIfAbsent(SandboxWorkspace.builder().id(binding.workspaceId()).owner(binding.owner())
                .state(WorkspaceState.OPEN).incarnation(newIncarnation()).stateSince(now).quota(settings.quota())
                .createdAt(now).lastActivityAt(now).build());
    }

    private SandboxProfile profileFor(SandboxBinding binding, SandboxSlot slot) {
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
        final String name = binding.requiredProfile().orElse(profiles.defaultProfileName());
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
            throw fail(workspace, slot, profile, kindOf(e), "create", e.getMessage(), ref);
        } catch (RuntimeException e) {
            throw fail(workspace, slot, profile, SlotFailure.Kind.TRANSIENT, "create", String.valueOf(e), ref);
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
            throw new SandboxUnavailableException("the sandbox could not be prepared: " + e.getMessage(), e);
        }
        if (failure.isPresent()) {
            final Instant now = clock.instant();
            final SlotFailure recorded = SlotFailure.builder().at(now).kind(SlotFailure.Kind.PERMANENT)
                    .step(failure.get().step()).reason(failure.get().reason()).profileHash(profile.contentHash())
                    .build();
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
     *            the principal asking; must pass the owner check
     * @throws SandboxUnavailableException
     *             when the caller may not close it
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             when the principal gate refuses the caller (§8.3) — like {@code SandboxUnavailableException}, an
     *             {@code ExecutionEnvironmentUnavailableException}
     */
    public void close(SandboxWorkspaceId id, Principal caller) {
        final WorkspaceOwner who = callers.callerOf(Optional.ofNullable(caller));
        final Optional<SandboxWorkspace> found = store.find(id);
        if (found.isEmpty()) {
            return;
        }
        checkOwner(found.get(), who);
        closeProcedure(id, CloseCause.EXPLICIT, current -> true);
    }

    /**
     * Reopens a CLOSED workspace: OPEN, a new incarnation, the owner unchanged; the slots stay TERMINATED so the next
     * use provisions a new generation (§10.5). An OPEN workspace is left as is.
     *
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking; must pass the owner check
     * @throws SandboxUnavailableException
     *             when the caller may not reopen it, it does not exist, or it is still closing — also when it became
     *             CLOSING after this call first read it
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             when the principal gate refuses the caller (§8.3) — like {@code SandboxUnavailableException}, an
     *             {@code ExecutionEnvironmentUnavailableException}
     */
    public void reopen(SandboxWorkspaceId id, Principal caller) {
        final WorkspaceOwner who = callers.callerOf(Optional.ofNullable(caller));
        final SandboxWorkspace found = store.find(id)
                .orElseThrow(() -> new SandboxUnavailableException("no such sandbox workspace"));
        checkOwner(found, who);
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
                emit(SandboxEvent.Type.WORKSPACE_REOPENED, reopened, null, "reopened by the application");
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
            terminateSlot(id, slotName, "workspace close", stillClosing, slot -> true);
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
                w.closeCause().orElse(cause).name().toLowerCase(Locale.ROOT) + " close"));
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
        return terminateSlot(id, slotName, cause, workspace -> true, guard);
    }

    private boolean terminateSlot(SandboxWorkspaceId id, String slotName, String cause,
            Predicate<SandboxWorkspace> workspaceGuard, Predicate<SandboxSlot> guard) {
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
        emit(SandboxEvent.Type.TERMINATED, terminated.get(), before[0], cause);
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
        final Optional<SandboxWorkspace> lost = mutate(id, current -> sameSandbox(current, slot)
                .filter(s -> s.missingSince().map(since -> !now.isBefore(since.plus(settings.lostConfirmAfter())))
                        .orElse(false))
                .map(s -> current.withSlot(s.terminated(now).toBuilder().missingSince(null).lostAt(now).build()))
                .orElse(null));
        slot.providerRef().ifPresent(connections::evict);
        lost.ifPresent(w -> emit(SandboxEvent.Type.LOST, w, slot,
                "reconciliation found the sandbox missing since " + slot.missingSince().orElse(null)));
        return lost.isPresent();
    }

    SandboxProvider provider() {
        return provider;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The owner check (§8.3): the tenant always, the principal too under {@code workspace-access: principal}. The
     * message says nothing about the workspace beyond "not permitted" (§15).
     */
    private void checkOwner(SandboxWorkspace workspace, WorkspaceOwner caller) {
        final WorkspaceOwner owner = workspace.owner();
        final boolean permitted = owner.tenant().equals(caller.tenant())
                && (settings.workspaceAccess() == SandboxSettings.WorkspaceAccess.TENANT
                        || owner.principal().equals(caller.principal()));
        if (!permitted) {
            throw new SandboxUnavailableException("this principal is not permitted to use the sandbox workspace");
        }
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
        final SandboxEvent.Builder event = SandboxEvent.builder().type(type).workspaceId(workspace.id())
                .owner(workspace.owner()).cause(cause).at(clock.instant());
        if (slot != null) {
            event.slot(slot);
        }
        try {
            events.onEvent(event.build());
        } catch (RuntimeException e) {
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

    /** Builder for {@link SandboxWorkspaceManager}; {@code WorkspaceSandbox} is the usual way to get one. */
    public static final class Builder {
        private SandboxSettings settings;
        private SandboxProfileRegistry profiles;
        private SandboxProvider provider;
        private SandboxWorkspaceStore store;
        private SandboxConnectionCache connections;
        private SandboxAdmission admission;
        private SandboxEventListener events = SandboxEventListener.NOOP;
        private CallerResolver callers;
        private Clock clock = Clock.systemUTC();
        private SandboxScheduler scheduler;

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

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder scheduler(SandboxScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public SandboxWorkspaceManager build() {
            return new SandboxWorkspaceManager(this);
        }
    }
}
