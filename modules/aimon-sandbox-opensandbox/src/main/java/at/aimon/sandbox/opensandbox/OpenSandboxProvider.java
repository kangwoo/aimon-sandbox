package at.aimon.sandbox.opensandbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ProviderCapabilities;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ProviderSandboxState;
import at.aimon.sandbox.provider.ResourceSpec;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.provider.SharedVolumes;
import at.aimon.sandbox.provider.VerificationFailure;
import at.aimon.sandbox.provider.VolumeMount;

/**
 * The production {@link SandboxProvider}: OpenSandbox over its REST API, called directly with the JDK
 * {@code HttpClient} and Jackson (docs/design/workspace-sandbox.md §6, docs/design/opensandbox-spike.md §8).
 *
 * <p>
 * It enforces what the server does not (spike §4): {@code create} always sends {@code timeout} (without one a sandbox
 * lives forever); {@link #extendExpiry} reads the current expiry, ignores a move backwards, clamps to
 * {@code max-expiry} and refuses a paused sandbox; {@link #destroy} treats 404 as success; errors are classified by
 * HTTP status. {@code create} is best-effort idempotent on the {@code sandbox-key} label (the server has no idempotency
 * key): a sandbox already carrying it is returned — the oldest when a takeover race made several, the rest are left to
 * reconciliation as duplicates. A sandbox that does not become {@code Running} in time is <b>not</b> destroyed: another
 * node may already have adopted it through that same lookup.
 *
 * <p>
 * Sandbox ids are never logged at INFO: on OpenSandbox, knowing one is enough to run commands in it through the
 * server's proxy route (spike §4-5). {@link #close()} releases the HTTP client's threads and the command readers; it
 * never destroys sandboxes.
 */
public final class OpenSandboxProvider implements SandboxProvider {

    /** The provider name in every {@link ProviderSandboxRef}. */
    public static final String NAME = "opensandbox";

    /**
     * A provider-owned label: the hash of the credential binding names the vault was given, so {@link #verify} can
     * check the vault on any node without the profile.
     */
    static final String CREDENTIALS_LABEL = "aimon.at/credentials";

    private static final Logger log = LoggerFactory.getLogger(OpenSandboxProvider.class);
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RUNNING_POLL = Duration.ofMillis(250);

    private final OpenSandboxProviderConfig config;
    private final Clock clock;
    private final Transport transport;
    private final Retry retry;
    private final LifecycleClient lifecycle;
    private final ExecutorService readers;
    private final ProviderCapabilities capabilities;
    private final Set<ResourceSpec> warnedResources = ConcurrentHashMap.newKeySet();

    /**
     * @param config
     *            the provider configuration
     */
    public OpenSandboxProvider(OpenSandboxProviderConfig config) {
        this(config, Clock.systemUTC());
    }

    OpenSandboxProvider(OpenSandboxProviderConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.transport = new Transport(config.requestTimeout(), config.maxConcurrentCalls());
        this.retry = new Retry(config.retryMaxAttempts(), config.retryBackoff());
        this.lifecycle = new LifecycleClient(transport, config.endpoint(), config.apiKey(), retry,
                config.useServerProxy());
        final AtomicInteger threads = new AtomicInteger();
        // One thread per running command's stream; a command's length is bounded by its own timeout.
        this.readers = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    final Thread thread = new Thread(runnable, "opensandbox-exec-" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.capabilities = ProviderCapabilities.builder().advertised(config.capabilities())
                .maxExpiry(config.maxExpiry()).runtimeClass(config.runtimeClass().map(c -> c.name()).orElse(null))
                .credentialScopes(config.credentialScopes()).controlPlaneEndpoints(config.controlPlaneProbes()).build();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public ProviderSandboxRef create(CreateSpec spec) {
        refuseUnservable(spec);
        // One budget for the POST and the waits after it (§6.1): the POST alone may block ~60 s on Kubernetes.
        final long deadline = System.nanoTime() + config.createTimeout().toNanos();
        final Optional<ProviderSandbox> existing = existing(spec);
        final String id;
        if (existing.isPresent()) {
            id = existing.get().ref().sandboxId();
        } else {
            final JsonNode created = lifecycle.create(body(spec), remaining(deadline));
            id = created.path("id").asText("");
            if (id.isEmpty()) {
                throw new SandboxProviderException("OpenSandbox created a sandbox without an id");
            }
        }
        final ProviderSandboxRef ref = ProviderSandboxRef.of(NAME, id);
        awaitRunning(ref, deadline);
        if (spec.egress().isPresent()) {
            awaitEgressSidecar(ref, deadline);
        }
        awaitExecd(ref, deadline);
        if (!spec.credentials().isEmpty()) {
            final VaultClient vault = vault(ref);
            // verify() demands exactly these bindings, so a vault holding more or fewer (an earlier create of the
            // same key with other credentials) is replaced, not topped up.
            final Optional<TreeSet<String>> held = vault.bindingNames();
            final boolean exact = held.isPresent() && held.get().equals(new TreeSet<>(spec.credentials()));
            if (!exact) {
                if (held.isPresent()) {
                    vault.delete();
                }
                vault.create(spec.credentials(), config.credentials());
            }
        }
        return ref;
    }

    /** Defence in depth: startup validation refuses these already. */
    private void refuseUnservable(CreateSpec spec) {
        final Optional<String> served = config.runtimeClass().map(OpenSandboxProviderConfig.RuntimeClass::name);
        if (spec.runtimeClass().isPresent() && served.isPresent() && !served.equals(spec.runtimeClass())) {
            throw new SandboxProviderException(
                    "runtime class " + spec.runtimeClass().get() + " was requested, but "
                            + "the server runs every sandbox with " + served.get(),
                    SandboxProviderException.Kind.PERMANENT, null);
        }
        if (spec.egress().isPresent() && !capabilities.supports(Capability.EGRESS_POLICY)) {
            throw new SandboxProviderException("an egress policy was requested, but the server does not enforce "
                    + "one (egress-enforcement is not dns+nft)", SandboxProviderException.Kind.PERMANENT, null);
        }
        if (!spec.credentials().isEmpty() && !capabilities.supports(Capability.CREDENTIAL_INJECTION)) {
            throw new SandboxProviderException("credentials were requested, but no credential vault is configured",
                    SandboxProviderException.Kind.PERMANENT, null);
        }
        if ((spec.resources().disk().isPresent() || spec.resources().pids().isPresent())
                && warnedResources.add(spec.resources())) {
            log.warn("OpenSandbox has no per-sandbox disk or pids limit: {} is enforced only by the server's own "
                    + "configuration (Docker pids_limit)", spec.resources());
        }
    }

    /** The sandbox already carrying this spec's key, when a create ran before (§6.3); FAILED and gone ones excluded. */
    private Optional<ProviderSandbox> existing(CreateSpec spec) {
        final Map<String, String> selector = new LinkedHashMap<>();
        for (String label : List.of(SandboxLabels.MANAGED, SandboxLabels.DEPLOYMENT, SandboxLabels.SANDBOX_KEY)) {
            final String value = spec.labels().get(label);
            if (value != null) {
                selector.put(label, value);
            }
        }
        if (!selector.containsKey(SandboxLabels.SANDBOX_KEY)) {
            selector.put(SandboxLabels.SANDBOX_KEY, SandboxLabels.h(spec.key()));
        }
        // verify() compares the vault with the credentials label the sandbox was created with, so a sandbox created
        // for other credentials is not this spec's: a new one is created and reconciliation removes the duplicate.
        final String credentials = spec.credentials().isEmpty() ? null : credentialsHash(spec.credentials());
        return list(selector).stream()
                .filter(sandbox -> sandbox.state() != ProviderSandboxState.TERMINATED
                        && sandbox.state() != ProviderSandboxState.FAILED)
                .filter(sandbox -> Objects.equals(sandbox.labels().get(CREDENTIALS_LABEL), credentials))
                .min(Comparator.comparing((ProviderSandbox sandbox) -> sandbox.createdAt().orElse(Instant.MAX))
                        .thenComparing(sandbox -> sandbox.ref().sandboxId()));
    }

    private ObjectNode body(CreateSpec spec) {
        final ObjectNode body = transport.json.createObjectNode();
        body.putObject("image").put("uri", spec.image());
        final ArrayNode entrypoint = body.putArray("entrypoint");
        config.entrypoint().forEach(entrypoint::add);
        body.put("timeout", timeoutSeconds(spec.expiresAt()));
        final ObjectNode resources = body.putObject("resourceLimits");
        resources.put("cpu", spec.resources().cpu().orElse(config.defaultCpu()));
        resources.put("memory", spec.resources().memory().orElse(config.defaultMemory()));
        if (!spec.environment().isEmpty()) {
            final ObjectNode env = body.putObject("env");
            spec.environment().forEach(env::put);
        }
        final ObjectNode metadata = body.putObject("metadata");
        spec.labels().forEach(metadata::put);
        if (!spec.credentials().isEmpty()) {
            metadata.put(CREDENTIALS_LABEL, credentialsHash(spec.credentials()));
        }
        spec.egress().ifPresent(allowed -> {
            // An empty list is sent too: it is an explicit deny-all, and omitting the policy would allow everything.
            final ObjectNode policy = body.putObject("networkPolicy").put("defaultAction", "deny");
            final ArrayNode egress = policy.putArray("egress");
            allowed.forEach(target -> egress.addObject().put("action", "allow").put("target", target));
        });
        if (!spec.credentials().isEmpty()) {
            body.putObject("credentialProxy").put("enabled", true);
        }
        if (!spec.volumes().isEmpty()) {
            final ArrayNode volumes = body.putArray("volumes");
            for (VolumeMount mount : spec.volumes()) {
                volumes.addObject().put("name", mount.volume().name()).put("mountPath", mount.mountPath())
                        .put("readOnly", mount.readOnly()).putObject("pvc").put("claimName", mount.volume().name())
                        .put("createIfNotExists", true);
            }
        }
        return body;
    }

    /** Relative seconds, at least the server's minimum of 60 and at most {@code max-expiry}. */
    long timeoutSeconds(Instant expiresAt) {
        final Duration until = Duration.between(clock.instant(), expiresAt);
        final long seconds = (until.toMillis() + 999) / 1000;
        return Math.min(config.maxExpiry().toSeconds(), Math.max(MIN_TIMEOUT.toSeconds(), seconds));
    }

    /** What is left of the create budget, at least a second so a late POST still gets a chance to answer. */
    private static Duration remaining(long deadline) {
        final long nanos = deadline - System.nanoTime();
        return nanos < Duration.ofSeconds(1).toNanos() ? Duration.ofSeconds(1) : Duration.ofNanos(nanos);
    }

    static String credentialsHash(List<String> names) {
        return SandboxLabels.h(String.join(",", new TreeSet<>(names)));
    }

    /**
     * Docker answers the create while the sandbox is {@code Pending}; Kubernetes once it is {@code Running}. A
     * {@code Failed} sandbox or one still pending at {@code create-timeout} is a transient failure, and is left where
     * it is: reconciliation reclaims it as a failed leftover after re-reading the record.
     */
    private void awaitRunning(ProviderSandboxRef ref, long deadline) {
        while (true) {
            final Optional<ProviderSandbox> status = status(ref);
            if (status.isEmpty()) {
                throw new SandboxProviderException("the created sandbox disappeared before it was running");
            }
            final ProviderSandboxState state = status.get().state();
            if (state == ProviderSandboxState.RUNNING) {
                return;
            }
            if (state == ProviderSandboxState.FAILED || state == ProviderSandboxState.TERMINATED) {
                throw new SandboxProviderException("the created sandbox is " + state + " instead of running");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new SandboxProviderException("the created sandbox was not running within "
                        + config.createTimeout().toSeconds() + "s (still " + state + ")");
            }
            sleep(RUNNING_POLL);
        }
    }

    /**
     * The egress sidecar answers a moment after the sandbox is {@code Running} (HTTP 500 from the server's proxy until
     * then, measured in implementation step 4); the vault and {@link #verify} need it, so {@code create} waits for it.
     */
    private void awaitEgressSidecar(ProviderSandboxRef ref, long deadline) {
        while (true) {
            try {
                lifecycle.networkPolicy(ref.sandboxId());
                return;
            } catch (HttpErrors.NotFound e) {
                throw new SandboxProviderException("the created sandbox disappeared before its egress sidecar was up");
            } catch (SandboxProviderException e) {
                if (e.kind() != SandboxProviderException.Kind.TRANSIENT || System.nanoTime() - deadline >= 0) {
                    throw e;
                }
            }
            sleep(RUNNING_POLL);
        }
    }

    /**
     * {@code Running} is not execd listening: with egress, execd's bootstrap waits for the sidecar's CA before it
     * starts
     * execd, so the first command could meet a 502 from the server's proxy (seen on Kubernetes, about one create in
     * five). {@code create} waits until execd answers {@code /ping}, within the same budget.
     */
    private void awaitExecd(ProviderSandboxRef ref, long deadline) {
        final ExecdClient execd = new ExecdClient(transport, lifecycle, ref, ExecdClient.EXECD_PORT);
        while (true) {
            try {
                HttpErrors.check(execd.send(ep -> ep.request("/ping").GET(), "ping execd"), "ping execd");
                return;
            } catch (HttpErrors.NotFound e) {
                if (lifecycle.get(ref.sandboxId()).isEmpty()) {
                    throw new SandboxProviderException("the created sandbox disappeared before execd was up");
                }
            } catch (SandboxProviderException e) {
                if (e.kind() != SandboxProviderException.Kind.TRANSIENT) {
                    throw e;
                }
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new SandboxProviderException(
                        "execd did not answer within " + config.createTimeout().toSeconds() + "s of the create");
            }
            sleep(RUNNING_POLL);
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxProviderException("interrupted while waiting for the sandbox");
        }
    }

    @Override
    public Optional<ProviderSandbox> status(ProviderSandboxRef ref) {
        if (!NAME.equals(ref.provider())) {
            return Optional.empty();
        }
        return lifecycle.get(ref.sandboxId()).map(OpenSandboxProvider::toSandbox);
    }

    static ProviderSandbox toSandbox(JsonNode node) {
        final Map<String, String> labels = new LinkedHashMap<>();
        node.path("metadata").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
        return ProviderSandbox.of(ProviderSandboxRef.of(NAME, node.path("id").asText()),
                state(node.path("status").path("state").asText("")), labels, instant(node.path("expiresAt")),
                instant(node.path("createdAt")));
    }

    /** OpenSandbox's eight states onto the SPI's five (docs/design/opensandbox-spike.md §2). */
    static ProviderSandboxState state(String state) {
        switch (state) {
            case "Running" :
                return ProviderSandboxState.RUNNING;
            case "Pausing" :
            case "Paused" :
                return ProviderSandboxState.PAUSED;
            case "Stopping" :
            case "Terminated" :
                return ProviderSandboxState.TERMINATED;
            case "Failed" :
                return ProviderSandboxState.FAILED;
            case "Pending" :
            case "Resuming" :
            default :
                return ProviderSandboxState.PENDING;
        }
    }

    private static Instant instant(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || value.asText().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(value.asText()).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** {@code PAUSE_RESUME} is not advertised before implementation step 7. */
    @Override
    public void pause(ProviderSandboxRef ref) {
        throw new UnsupportedOperationException("PAUSE_RESUME is not advertised by the OpenSandbox provider yet");
    }

    /** {@code PAUSE_RESUME} is not advertised before implementation step 7. */
    @Override
    public void resume(ProviderSandboxRef ref) {
        throw new UnsupportedOperationException("PAUSE_RESUME is not advertised by the OpenSandbox provider yet");
    }

    /**
     * Forward only and at most {@code max-expiry} from now — the server enforces neither on renew (spike §4-1). Two
     * nodes extending at once may race (read, then renew); both write about {@code now + terminateAfter}. A paused
     * sandbox is refused: on Kubernetes renewing one breaks it (spike §4-2). The target is rounded up to a whole
     * microsecond: the server keeps microseconds and drops the rest, so a nanosecond clock (Linux) would otherwise get
     * an expiry just before the one asked for.
     */
    @Override
    public void extendExpiry(ProviderSandboxRef ref, Instant until) {
        final ProviderSandbox current = status(ref).orElseThrow(() -> new SandboxNotFoundException(ref));
        if (current.state() == ProviderSandboxState.PAUSED) {
            throw new SandboxProviderException("cannot extend the expiry of a paused sandbox",
                    SandboxProviderException.Kind.PERMANENT, null);
        }
        final Instant now = clock.instant();
        final Instant cap = now.plus(config.maxExpiry()).truncatedTo(ChronoUnit.MICROS);
        final Instant wanted = ceilToMicros(until);
        final Instant target = wanted.isAfter(cap) ? cap : wanted;
        if (!target.isAfter(now) || current.expiresAt().map(expiry -> !target.isAfter(expiry)).orElse(false)) {
            return;
        }
        try {
            lifecycle.renew(ref.sandboxId(), target);
        } catch (HttpErrors.NotFound e) {
            throw new SandboxNotFoundException(ref);
        }
    }

    static Instant ceilToMicros(Instant instant) {
        final Instant truncated = instant.truncatedTo(ChronoUnit.MICROS);
        return truncated.equals(instant) ? instant : truncated.plus(1, ChronoUnit.MICROS);
    }

    @Override
    public void destroy(ProviderSandboxRef ref) {
        if (NAME.equals(ref.provider())) {
            lifecycle.delete(ref.sandboxId());
        }
    }

    @Override
    public List<ProviderSandbox> list(Map<String, String> labels) {
        final List<ProviderSandbox> sandboxes = new ArrayList<>();
        for (JsonNode node : lifecycle.list(labels)) {
            final ProviderSandbox sandbox = toSandbox(node);
            // The server filters; this guards against a server that ignores the filter.
            if (sandbox.labels().entrySet().containsAll(labels.entrySet())) {
                sandboxes.add(sandbox);
            }
        }
        return sandboxes;
    }

    @Override
    public Optional<SharedVolumes> sharedVolumes() {
        return config.volumeReclaimer().map(reclaimer -> reclaimer);
    }

    @Override
    public SandboxConnection connect(ProviderSandboxRef ref) {
        if (!NAME.equals(ref.provider())) {
            throw new SandboxNotFoundException(ref);
        }
        final ExecdClient execd = new ExecdClient(transport, lifecycle, ref, ExecdClient.EXECD_PORT);
        execd.resolve();
        return new OpenSandboxConnection(execd, retry, readers);
    }

    private VaultClient vault(ProviderSandboxRef ref) {
        return new VaultClient(new ExecdClient(transport, lifecycle, ref, ExecdClient.EGRESS_PORT));
    }

    /**
     * The seed step's provider checks (§11.3): with {@code EGRESS_POLICY} required, the sidecar must enforce
     * {@code dns+nft} with a deny default — the declaration is checked, not trusted (spike §4-6); with
     * {@code CREDENTIAL_INJECTION} required, the vault must hold exactly the bindings the sandbox was created with.
     */
    @Override
    public List<VerificationFailure> verify(ProviderSandboxRef ref, Set<Capability> required) {
        final List<VerificationFailure> failures = new ArrayList<>();
        if (required.contains(Capability.EGRESS_POLICY)) {
            final JsonNode policy;
            try {
                policy = lifecycle.networkPolicy(ref.sandboxId());
            } catch (HttpErrors.NotFound e) {
                throw new SandboxNotFoundException(ref);
            }
            final String mode = policy.path("enforcementMode").asText("");
            final String defaultAction = policy.path("policy").path("defaultAction").asText("");
            if (!"dns+nft".equals(mode)) {
                failures.add(VerificationFailure.of("egress",
                        "the server enforces egress with '" + mode + "', not dns+nft as egress-enforcement declares"));
            } else if (!"deny".equals(defaultAction)) {
                failures.add(VerificationFailure.of("egress",
                        "the sandbox's egress default is '" + defaultAction + "', not deny"));
            }
        }
        if (required.contains(Capability.CREDENTIAL_INJECTION)) {
            final ProviderSandbox sandbox = status(ref).orElseThrow(() -> new SandboxNotFoundException(ref));
            final String expected = sandbox.labels().get(CREDENTIALS_LABEL);
            final Optional<TreeSet<String>> held = vault(ref).bindingNames();
            if (held.isEmpty() || expected == null || !expected.equals(credentialsHash(new ArrayList<>(held.get())))) {
                failures.add(VerificationFailure.of("credentials",
                        "the credential vault holds " + held.map(Object::toString).orElse("nothing")
                                + ", not the bindings the sandbox was " + "created with"));
            }
        }
        return failures;
    }

    /** Stops the command readers. Never destroys sandboxes. */
    @Override
    public void close() {
        readers.shutdownNow();
    }
}
