package at.aimon.sandbox.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ProviderCapabilities;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ProviderSandboxState;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.SharedVolumes;

/**
 * A {@link SandboxProvider} made of temporary directories and local processes — <b>for tests only. It isolates
 * nothing; never use it in production.</b> It exists so the manager, the binding, the environment provider and core's
 * tools can be tested together without Docker (docs/design/workspace-sandbox.md §16), and it passes
 * {@code SandboxProviderContract}.
 *
 * <p>
 * Each sandbox is a directory {@code {base}/{id}}; a sandbox path {@code /p} is the host path {@code {base}/{id}/p},
 * so {@code /workspace} is {@code {base}/{id}/workspace}. Commands run as the JVM's user in a process group of their
 * own ({@code perl setpgrp}), with {@code /workspace} (and {@code /usr/bin/sha256sum}) rewritten to host paths
 * <i>textually</i> in the command, the working directory and the files API, and host paths rewritten back in the
 * output. The limit that follows: a path assembled at runtime ({@code cd /work"space"}) is not translated and reaches
 * the host. Other absolute paths ({@code /tmp}) are the host's.
 *
 * <p>
 * A shim directory first on every command's {@code PATH} holds a perl {@code flock} (only {@code -w N FD} and
 * {@code -n FD}) when the host has none, an {@code rg} stub that fails loudly when the host has no ripgrep
 * ({@link #hostHasRipgrep()} tells tests whether real searches can run), and {@code sha256sum}.
 *
 * <p>
 * Advertises {@link Capability#EXEC}, {@link Capability#FILES} and {@link Capability#EXPIRY} only. Expiry is enforced
 * lazily against the injected clock: an expired sandbox is destroyed on the next {@code status}, {@code connect} or
 * {@code list}. Profiles used with it waive {@code HARDENED_SECURITY_CONTEXT} and {@code NETWORK_ISOLATION}
 * ({@link SandboxTestProfiles}).
 */
public final class LocalProcessSandboxProvider implements SandboxProvider {

    /** The provider name in every {@link ProviderSandboxRef} this provider returns. */
    public static final String NAME = "local";

    static final String BASE_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin";

    private static final Logger log = LoggerFactory.getLogger(LocalProcessSandboxProvider.class);

    private final Path base;
    private final boolean ownBase;
    private final Path shims;
    private final Clock clock;
    private final Duration maxExpiry;
    private final Map<String, LocalSandbox> sandboxes = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    private LocalProcessSandboxProvider(Builder builder) {
        try {
            final Path requested = builder.baseDirectory != null
                    ? Files.createDirectories(builder.baseDirectory)
                    : Files.createTempDirectory("aimon-sandbox-");
            // Real path: on macOS the temp dir is under /var, a link to /private/var, and a command's `pwd` prints
            // the real one — the reverse translation of output must match what the process sees.
            this.base = requested.toRealPath();
            this.shims = Files.createDirectories(base.resolve(".shims"));
            installShims(shims);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare the local sandbox base directory", e);
        }
        this.ownBase = builder.baseDirectory == null;
        this.clock = Objects.requireNonNull(builder.clock, "clock must not be null");
        this.maxExpiry = builder.maxExpiry;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether the host has a real ripgrep on the provider's {@code PATH} — looked up the way the seed does, with
     * {@code /bin/bash -c 'command -v rg'} (a login shell's function or alias does not count). Tests that need real
     * {@code rg --json} output gate on it with {@code assumeTrue}.
     *
     * @return whether {@code rg} exists on the host
     */
    public static boolean hostHasRipgrep() {
        return hostHas("rg");
    }

    static boolean hostHas(String tool) {
        try {
            final ProcessBuilder probe = new ProcessBuilder("/bin/bash", "-c", "command -v " + tool)
                    .redirectErrorStream(true);
            probe.environment().put("PATH", BASE_PATH);
            final Process process = probe.start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void installShims(Path shims) throws IOException {
        if (!hostHas("flock")) {
            copyResource("flock", shims.resolve("flock"));
        }
        if (!hostHas("rg")) {
            copyResource("rg", shims.resolve("rg"));
        }
        final String sha = Stream
                .of("/usr/bin/sha256sum", "/sbin/sha256sum", "/usr/local/bin/sha256sum", "/opt/homebrew/bin/sha256sum")
                .filter(p -> Files.isExecutable(Path.of(p))).findFirst().orElse(null);
        writeExecutable(shims.resolve("sha256sum"),
                sha != null
                        ? "#!/bin/sh\nexec " + sha + " \"$@\"\n"
                        : "#!/bin/sh\nexec /usr/bin/shasum -a 256 \"$@\"\n");
    }

    private static void copyResource(String name, Path target) throws IOException {
        try (InputStream in = LocalProcessSandboxProvider.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing testkit resource " + name);
            }
            writeExecutable(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void writeExecutable(Path target, String content) throws IOException {
        Files.writeString(target, content);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** @return the directory every sandbox lives under */
    public Path baseDirectory() {
        return base;
    }

    /**
     * @param ref
     *            a sandbox of this provider
     * @return the host directory that is its {@code /}
     */
    public Path hostRoot(ProviderSandboxRef ref) {
        return require(ref).dir;
    }

    /**
     * @param ref
     *            a sandbox of this provider
     * @return its current expiry
     */
    public Optional<Instant> expiryOf(ProviderSandboxRef ref) {
        return Optional.ofNullable(sandboxes.get(ref.sandboxId())).map(sandbox -> sandbox.expiresAt);
    }

    /** @return how many sandboxes exist */
    public int sandboxCount() {
        expireDue();
        return sandboxes.size();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.of(EnumSet.of(Capability.EXEC, Capability.FILES, Capability.EXPIRY), maxExpiry);
    }

    @Override
    public synchronized ProviderSandboxRef create(CreateSpec spec) {
        expireDue();
        final String key = spec.labels().get(SandboxLabels.SANDBOX_KEY);
        if (key != null) {
            for (LocalSandbox existing : sandboxes.values()) {
                if (key.equals(existing.labels.get(SandboxLabels.SANDBOX_KEY))) {
                    return existing.ref;
                }
            }
        }
        final String id = "local-" + counter.incrementAndGet();
        final Path dir = base.resolve(id);
        try {
            Files.createDirectories(dir.resolve("workspace"));
            Files.createDirectories(dir.resolve("tmp"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create sandbox directory " + dir, e);
        }
        final LocalSandbox sandbox = new LocalSandbox(ProviderSandboxRef.of(NAME, id), dir, spec.labels(),
                spec.expiresAt(), spec.environment(), clock.instant());
        sandboxes.put(id, sandbox);
        return sandbox.ref;
    }

    @Override
    public Optional<ProviderSandbox> status(ProviderSandboxRef ref) {
        expireDue();
        return Optional.ofNullable(sandboxes.get(ref.sandboxId())).map(LocalSandbox::describe);
    }

    /** Not advertised. */
    @Override
    public void pause(ProviderSandboxRef ref) {
        throw new UnsupportedOperationException("LocalProcessSandboxProvider does not advertise PAUSE_RESUME");
    }

    /** Not advertised. */
    @Override
    public void resume(ProviderSandboxRef ref) {
        throw new UnsupportedOperationException("LocalProcessSandboxProvider does not advertise PAUSE_RESUME");
    }

    @Override
    public void extendExpiry(ProviderSandboxRef ref, Instant until) {
        expireDue();
        final LocalSandbox sandbox = require(ref);
        synchronized (sandbox) {
            if (until.isAfter(sandbox.expiresAt)) {
                sandbox.expiresAt = until;
            }
        }
    }

    @Override
    public void destroy(ProviderSandboxRef ref) {
        final LocalSandbox sandbox = sandboxes.remove(ref.sandboxId());
        if (sandbox != null) {
            sandbox.destroyed = true;
            sandbox.killAll();
            deleteTree(sandbox.dir);
        }
    }

    @Override
    public List<ProviderSandbox> list(Map<String, String> labels) {
        expireDue();
        final List<ProviderSandbox> matching = new ArrayList<>();
        for (LocalSandbox sandbox : sandboxes.values()) {
            if (sandbox.labels.entrySet().containsAll(labels.entrySet())) {
                matching.add(sandbox.describe());
            }
        }
        matching.sort(Comparator.comparing(s -> s.ref().sandboxId()));
        return matching;
    }

    /** @return empty: {@link Capability#SHARED_VOLUME} is not advertised */
    @Override
    public Optional<SharedVolumes> sharedVolumes() {
        return Optional.empty();
    }

    @Override
    public SandboxConnection connect(ProviderSandboxRef ref) {
        expireDue();
        return new LocalConnection(require(ref), shims);
    }

    private LocalSandbox require(ProviderSandboxRef ref) {
        final LocalSandbox sandbox = NAME.equals(ref.provider()) ? sandboxes.get(ref.sandboxId()) : null;
        if (sandbox == null) {
            throw new SandboxNotFoundException(ref);
        }
        return sandbox;
    }

    private void expireDue() {
        final Instant now = clock.instant();
        for (LocalSandbox sandbox : List.copyOf(sandboxes.values())) {
            if (!now.isBefore(sandbox.expiresAt)) {
                log.debug("Local sandbox {} expired at {}", sandbox.ref, sandbox.expiresAt);
                destroy(sandbox.ref);
            }
        }
    }

    /**
     * Kills every command still running, and what they left running in their process groups. Sandbox directories are
     * kept unless the provider created its own base directory, which is then deleted — a test's sandboxes do not
     * outlive it.
     */
    @Override
    public void close() {
        for (LocalSandbox sandbox : sandboxes.values()) {
            sandbox.killAll();
        }
        if (ownBase) {
            sandboxes.clear();
            deleteTree(base);
        }
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.debug("Could not delete {}: {}", root, e.getMessage());
        }
    }

    /**
     * One sandbox: a directory, its labels, its expiry, the commands running in it and every process group a command
     * started — a command's {@code sleep 600 &} stays in its group after the command ends, and a real sandbox would
     * take it along when destroyed.
     */
    static final class LocalSandbox {
        final ProviderSandboxRef ref;
        final Path dir;
        final Map<String, String> labels;
        final Map<String, String> environment;
        final Set<LocalRunningCommand> running = ConcurrentHashMap.newKeySet();
        /** Process group id → its leader's start time, which tells a reused pid from the group's own leader. */
        final Map<Long, Optional<Instant>> processGroups = new ConcurrentHashMap<>();
        final Instant createdAt;
        volatile Instant expiresAt;
        volatile boolean destroyed;

        LocalSandbox(ProviderSandboxRef ref, Path dir, Map<String, String> labels, Instant expiresAt,
                Map<String, String> environment, Instant createdAt) {
            this.ref = ref;
            this.createdAt = createdAt;
            this.dir = dir;
            this.labels = Map.copyOf(labels);
            this.expiresAt = expiresAt;
            this.environment = Map.copyOf(environment);
        }

        ProviderSandbox describe() {
            return ProviderSandbox.of(ref, ProviderSandboxState.RUNNING, labels, expiresAt, createdAt);
        }

        /**
         * @throws SandboxNotFoundException
         *             once this sandbox was destroyed, as a real provider answers
         */
        void requireLive() {
            if (destroyed) {
                throw new SandboxNotFoundException(ref);
            }
        }

        void killAll() {
            for (LocalRunningCommand command : List.copyOf(running)) {
                command.kill();
            }
            processGroups.forEach((pgid, started) -> {
                // A live process with the group's id that started later is a reused pid, not this group's leader.
                final boolean reused = ProcessHandle.of(pgid).flatMap(handle -> handle.info().startInstant())
                        .map(start -> started.map(start::isAfter).orElse(true)).orElse(false);
                if (!reused) {
                    killGroup(pgid);
                }
            });
        }

        private static void killGroup(long pgid) {
            try {
                final Process kill = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + pgid).start();
                kill.getInputStream().readAllBytes();
                kill.getErrorStream().readAllBytes();
                kill.waitFor(5, TimeUnit.SECONDS);
            } catch (IOException e) {
                log.debug("Could not kill process group {}: {}", pgid, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Builder for {@link LocalProcessSandboxProvider}. */
    public static final class Builder {
        private Path baseDirectory;
        private Clock clock = Clock.systemUTC();
        private Duration maxExpiry;

        private Builder() {
        }

        /** Default: a fresh temp directory, deleted by {@link LocalProcessSandboxProvider#close()}. */
        public Builder baseDirectory(Path baseDirectory) {
            this.baseDirectory = baseDirectory;
            return this;
        }

        /** The clock expiry is enforced against. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** The longest expiry to advertise, or {@code null} for none. */
        public Builder maxExpiry(Duration maxExpiry) {
            this.maxExpiry = maxExpiry;
            return this;
        }

        public LocalProcessSandboxProvider build() {
            return new LocalProcessSandboxProvider(this);
        }
    }
}
