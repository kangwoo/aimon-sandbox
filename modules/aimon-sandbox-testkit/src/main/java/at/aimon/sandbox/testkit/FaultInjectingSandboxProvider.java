package at.aimon.sandbox.testkit;

import java.io.InputStream;
import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderCapabilities;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.provider.SharedVolumes;
import at.aimon.sandbox.provider.WriteMode;

/**
 * Wraps any provider and injects faults per {@link Operation} and call number (docs/design/workspace-sandbox.md §16):
 * a delay, a transient or permanent failure, a timeout, "not found", a success whose response is lost, or a
 * simulated node crash. Counts every call, so a test can assert how many copies a staging made.
 */
public final class FaultInjectingSandboxProvider implements SandboxProvider {

    /** What can be intercepted. */
    // spotless:off
    public enum Operation {
        CREATE, STATUS, PAUSE, RESUME, EXTEND_EXPIRY, DESTROY, LIST, CONNECT,
        RUN,
        FILES_READ, FILES_WRITE, FILES_STAT, FILES_LIST, FILES_MKDIR, FILES_DELETE, FILES_MOVE
    }
    // spotless:on

    /**
     * Thrown by {@link Fault#crash()}: an {@link Error}, so no {@code catch (RuntimeException)} on the way swallows it
     * —
     * the call path stops where it is, as when the node dies.
     */
    public static final class SimulatedCrash extends Error {
        @Serial
        private static final long serialVersionUID = 1L;

        SimulatedCrash(Operation operation) {
            super("simulated node crash during " + operation);
        }
    }

    /** One fault. */
    public static final class Fault {
        private final Duration delay;
        private final SandboxProviderException.Kind failure;
        private final boolean timeout;
        private final boolean notFound;
        private final boolean loseResponse;
        private final boolean crash;

        private Fault(Duration delay, SandboxProviderException.Kind failure, boolean timeout, boolean notFound,
                boolean loseResponse, boolean crash) {
            this.delay = delay;
            this.failure = failure;
            this.timeout = timeout;
            this.notFound = notFound;
            this.loseResponse = loseResponse;
            this.crash = crash;
        }

        /** @return a fault that waits, then lets the call proceed */
        public static Fault delay(Duration delay) {
            return new Fault(Objects.requireNonNull(delay), null, false, false, false, false);
        }

        /** @return a fault that fails the call with that kind (a 5xx is {@code TRANSIENT}) */
        public static Fault fail(SandboxProviderException.Kind kind) {
            return new Fault(null, Objects.requireNonNull(kind), false, false, false, false);
        }

        /** @return a fault that fails the call as a timeout would (transient) */
        public static Fault timeout() {
            return new Fault(null, null, true, false, false, false);
        }

        /** @return a fault that answers {@link SandboxNotFoundException} */
        public static Fault notFound() {
            return new Fault(null, null, false, true, false, false);
        }

        /** @return a fault that performs the call, then fails as if the response was lost (transient) */
        public static Fault succeedButLoseResponse() {
            return new Fault(null, null, false, false, true, false);
        }

        /** @return a fault that throws {@link SimulatedCrash} before the call */
        public static Fault crash() {
            return new Fault(null, null, false, false, false, true);
        }
    }

    private static final class Rule {
        private final Operation operation;
        private final int call;
        private final Fault fault;
        private final AtomicInteger remaining;

        private Rule(Operation operation, int call, Fault fault, int times) {
            this.operation = operation;
            this.call = call;
            this.fault = fault;
            this.remaining = new AtomicInteger(times);
        }
    }

    private final SandboxProvider delegate;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final Map<Operation, AtomicInteger> calls = new EnumMap<>(Operation.class);

    /**
     * @param delegate
     *            the provider to wrap (borrowed)
     */
    public FaultInjectingSandboxProvider(SandboxProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        for (Operation operation : Operation.values()) {
            calls.put(operation, new AtomicInteger());
        }
    }

    /**
     * Injects a fault into every call of an operation.
     *
     * @param operation
     *            the operation
     * @param fault
     *            the fault
     * @return this
     */
    public FaultInjectingSandboxProvider inject(Operation operation, Fault fault) {
        rules.add(new Rule(operation, 0, fault, Integer.MAX_VALUE));
        return this;
    }

    /**
     * Injects a fault into the next call of an operation only.
     *
     * @param operation
     *            the operation
     * @param fault
     *            the fault
     * @return this
     */
    public FaultInjectingSandboxProvider injectOnce(Operation operation, Fault fault) {
        rules.add(new Rule(operation, 0, fault, 1));
        return this;
    }

    /**
     * Injects a fault into the n-th call of an operation (1-based, counted since this wrapper was made).
     *
     * @param operation
     *            the operation
     * @param callNumber
     *            the call number
     * @param fault
     *            the fault
     * @return this
     */
    public FaultInjectingSandboxProvider injectAt(Operation operation, int callNumber, Fault fault) {
        rules.add(new Rule(operation, callNumber, fault, 1));
        return this;
    }

    /** Removes every rule. Counters are kept. */
    public void clear() {
        rules.clear();
    }

    /**
     * @param operation
     *            the operation
     * @return how many times it was called
     */
    public int calls(Operation operation) {
        return calls.get(operation).get();
    }

    /** Resets every counter to zero. */
    public void resetCounts() {
        calls.values().forEach(counter -> counter.set(0));
    }

    private <T> T call(Operation operation, ProviderSandboxRef ref, Supplier<T> action) {
        final int number = calls.get(operation).incrementAndGet();
        Fault fault = null;
        for (Rule rule : rules) {
            if (rule.operation == operation && (rule.call == 0 || rule.call == number)
                    && rule.remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                fault = rule.fault;
                break;
            }
        }
        if (fault == null) {
            return action.get();
        }
        if (fault.crash) {
            throw new SimulatedCrash(operation);
        }
        if (fault.delay != null) {
            try {
                Thread.sleep(fault.delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SandboxProviderException("interrupted during an injected delay");
            }
            return action.get();
        }
        if (fault.failure != null) {
            throw new SandboxProviderException("injected " + fault.failure + " failure of " + operation, fault.failure,
                    null);
        }
        if (fault.timeout) {
            throw new SandboxProviderException("injected timeout of " + operation);
        }
        if (fault.notFound) {
            throw new SandboxNotFoundException(ref != null ? ref : ProviderSandboxRef.of("injected", "not-found"));
        }
        action.get();
        throw new SandboxProviderException("injected lost response of " + operation);
    }

    private void run(Operation operation, ProviderSandboxRef ref, Runnable action) {
        call(operation, ref, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ProviderSandboxRef create(CreateSpec spec) {
        return call(Operation.CREATE, null, () -> delegate.create(spec));
    }

    @Override
    public Optional<ProviderSandbox> status(ProviderSandboxRef ref) {
        return call(Operation.STATUS, ref, () -> delegate.status(ref));
    }

    @Override
    public void pause(ProviderSandboxRef ref) {
        run(Operation.PAUSE, ref, () -> delegate.pause(ref));
    }

    @Override
    public void resume(ProviderSandboxRef ref) {
        run(Operation.RESUME, ref, () -> delegate.resume(ref));
    }

    @Override
    public void extendExpiry(ProviderSandboxRef ref, Instant until) {
        run(Operation.EXTEND_EXPIRY, ref, () -> delegate.extendExpiry(ref, until));
    }

    @Override
    public void destroy(ProviderSandboxRef ref) {
        run(Operation.DESTROY, ref, () -> delegate.destroy(ref));
    }

    @Override
    public List<ProviderSandbox> list(Map<String, String> labels) {
        return call(Operation.LIST, null, () -> delegate.list(labels));
    }

    @Override
    public Optional<SharedVolumes> sharedVolumes() {
        return delegate.sharedVolumes();
    }

    @Override
    public SandboxConnection connect(ProviderSandboxRef ref) {
        final SandboxConnection connection = call(Operation.CONNECT, ref, () -> delegate.connect(ref));
        return new FaultConnection(ref, connection);
    }

    /** Does not close the wrapped provider (borrowed). */
    @Override
    public void close() {
        // borrowed
    }

    private final class FaultConnection implements SandboxConnection {
        private final ProviderSandboxRef ref;
        private final SandboxConnection connection;
        private final SandboxFiles files;

        private FaultConnection(ProviderSandboxRef ref, SandboxConnection connection) {
            this.ref = ref;
            this.connection = connection;
            this.files = new FaultFiles(ref, connection.files());
        }

        @Override
        public RunningCommand run(ExecSpec spec, OutputSink sink) {
            return call(Operation.RUN, ref, () -> connection.run(spec, sink));
        }

        @Override
        public SandboxFiles files() {
            return files;
        }

        @Override
        public void close() {
            connection.close();
        }
    }

    private final class FaultFiles implements SandboxFiles {
        private final ProviderSandboxRef ref;
        private final SandboxFiles files;

        private FaultFiles(ProviderSandboxRef ref, SandboxFiles files) {
            this.ref = ref;
            this.files = files;
        }

        @Override
        public InputStream read(String path, long offset, long length) {
            return call(Operation.FILES_READ, ref, () -> files.read(path, offset, length));
        }

        @Override
        public void write(String path, InputStream content, long length, WriteMode mode) {
            FaultInjectingSandboxProvider.this.run(Operation.FILES_WRITE, ref,
                    () -> files.write(path, content, length, mode));
        }

        @Override
        public Optional<FileStat> stat(String path) {
            return call(Operation.FILES_STAT, ref, () -> files.stat(path));
        }

        @Override
        public List<FileStat> list(String directory, boolean recursive, int limit) {
            return call(Operation.FILES_LIST, ref, () -> files.list(directory, recursive, limit));
        }

        @Override
        public void createDirectories(String path) {
            FaultInjectingSandboxProvider.this.run(Operation.FILES_MKDIR, ref, () -> files.createDirectories(path));
        }

        @Override
        public void delete(String path, boolean recursive) {
            FaultInjectingSandboxProvider.this.run(Operation.FILES_DELETE, ref, () -> files.delete(path, recursive));
        }

        @Override
        public void move(String from, String to, boolean overwrite) {
            FaultInjectingSandboxProvider.this.run(Operation.FILES_MOVE, ref, () -> files.move(from, to, overwrite));
        }
    }
}
