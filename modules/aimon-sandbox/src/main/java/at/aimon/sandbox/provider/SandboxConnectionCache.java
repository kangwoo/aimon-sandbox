package at.aimon.sandbox.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Node-local state that must never reach the workspace record (docs/design/workspace-sandbox.md §3.2): connections per
 * sandbox, the per-{@code (sandbox, shellKey)} lock that orders one node's commands on a shell (§9), the
 * per-{@code (sandbox, staging target)} lock that serialises {@code stage()} of one resource, and the {@code exec:}
 * shell directories to sweep after {@code execShellIdle}.
 *
 * <p>
 * A lock entry exists only while some thread holds or waits on it — it is removed the moment the last user leaves —
 * so an entry is never replaced under a running holder and the map never grows with dead sandboxes.
 * <p>
 * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in
 * any release.
 */
public final class SandboxConnectionCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SandboxConnectionCache.class);

    private final SandboxProvider provider;
    private final Clock clock;
    private final ConcurrentMap<ProviderSandboxRef, SandboxConnection> connections = new ConcurrentHashMap<>();
    private final Map<LockKey, NodeLock> locks = new HashMap<>();
    private final Map<LockKey, ExecShell> execShells = new HashMap<>();

    /**
     * @param provider
     *            the provider connections come from (borrowed)
     * @param clock
     *            the clock the exec-shell sweep reads
     */
    public SandboxConnectionCache(SandboxProvider provider, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param ref
     *            the sandbox
     * @return a cached connection, opened on first use
     * @throws SandboxNotFoundException
     *             when the sandbox is gone
     */
    public SandboxConnection get(ProviderSandboxRef ref) {
        final SandboxConnection cached = connections.get(ref);
        if (cached != null) {
            return cached;
        }
        // Connected outside the map: computeIfAbsent would hold the map bin's lock through a remote call and stall
        // every other sandbox hashed to that bin. Two racing callers may both connect; the loser closes its own.
        final SandboxConnection fresh = provider.connect(ref);
        final SandboxConnection raced = connections.putIfAbsent(ref, fresh);
        if (raced != null) {
            closeQuietly(fresh);
            return raced;
        }
        return fresh;
    }

    /**
     * Drops and closes the connection to a sandbox that was terminated, lost or replaced.
     *
     * @param ref
     *            the sandbox
     */
    public void evict(ProviderSandboxRef ref) {
        final SandboxConnection connection = connections.remove(ref);
        if (connection != null) {
            closeQuietly(connection);
        }
        synchronized (execShells) {
            execShells.keySet().removeIf(key -> key.ref.equals(ref));
        }
    }

    /**
     * Takes the node-local lock of one shell, waiting at most {@code wait}.
     *
     * @param ref
     *            the sandbox
     * @param shellKey
     *            the shell
     * @param wait
     *            how long to wait
     * @return the lease; {@link Lease#acquired()} is {@code false} when the lock did not come free in time (closing
     *         that lease is a no-op)
     * @throws InterruptedException
     *             if interrupted while waiting
     */
    public Lease lockShell(ProviderSandboxRef ref, String shellKey, Duration wait) throws InterruptedException {
        final LockKey key = new LockKey(ref, "shell:" + shellKey);
        final NodeLock lock = enter(key);
        boolean acquired = false;
        try {
            acquired = lock.lock.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            if (!acquired) {
                leave(key, lock);
            }
        }
        return new Lease(this, key, lock);
    }

    /**
     * Takes the node-local lock of one staging target, waiting as long as it takes (as core's {@code LocalStaging}
     * does with {@code synchronized}).
     *
     * @param ref
     *            the sandbox
     * @param target
     *            the staging target directory
     * @return the held lock
     * @throws InterruptedException
     *             if interrupted while waiting
     */
    public Lease lockStaging(ProviderSandboxRef ref, String target) throws InterruptedException {
        final LockKey key = new LockKey(ref, "stage:" + target);
        final NodeLock lock = enter(key);
        boolean acquired = false;
        try {
            lock.lock.lockInterruptibly();
            acquired = true;
        } finally {
            if (!acquired) {
                leave(key, lock);
            }
        }
        return new Lease(this, key, lock);
    }

    private NodeLock enter(LockKey key) {
        synchronized (locks) {
            final NodeLock lock = locks.computeIfAbsent(key, k -> new NodeLock());
            lock.users++;
            return lock;
        }
    }

    private void leave(LockKey key, NodeLock lock) {
        synchronized (locks) {
            lock.users--;
            if (lock.users == 0) {
                locks.remove(key, lock);
            }
        }
    }

    /** @return how many lock entries exist (for tests: entries must not outlive their users) */
    int lockEntries() {
        synchronized (locks) {
            return locks.size();
        }
    }

    /**
     * Marks an {@code exec:} shell directory as in use by a command. Waits while the sweep is deleting that directory,
     * so a command never starts in a directory that is being removed under it.
     *
     * @param ref
     *            the sandbox
     * @param directory
     *            the shell's state directory
     */
    public void execShellStarted(ProviderSandboxRef ref, String directory) {
        final LockKey key = new LockKey(ref, directory);
        boolean interrupted = false;
        synchronized (execShells) {
            ExecShell shell = execShells.get(key);
            while (shell != null && shell.sweeping) {
                try {
                    execShells.wait();
                } catch (InterruptedException e) {
                    // The sweep's own exec is bounded; finish waiting and keep the interrupt for the caller.
                    interrupted = true;
                }
                shell = execShells.get(key);
            }
            execShells.computeIfAbsent(key, k -> new ExecShell()).running++;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Marks the end of a command on an {@code exec:} shell directory; the idle time counts from here.
     *
     * @param ref
     *            the sandbox
     * @param directory
     *            the shell's state directory
     */
    public void execShellFinished(ProviderSandboxRef ref, String directory) {
        synchronized (execShells) {
            // Absent when the sandbox was evicted meanwhile: re-adding it would make the sweep connect to a sandbox
            // that is gone, on every pass.
            final ExecShell shell = execShells.get(new LockKey(ref, directory));
            if (shell != null) {
                shell.running = Math.max(0, shell.running - 1);
                shell.finishedAt = clock.instant();
            }
        }
    }

    /**
     * Deletes the {@code exec:} shell directories no command has used for {@code idle} (§9). A directory whose lock is
     * held in the sandbox is kept; one whose sandbox is gone is forgotten.
     *
     * @param idle
     *            how long a directory must have been unused
     * @return how many directories were deleted
     */
    public int sweepExecShells(Duration idle) {
        final Instant cutoff = clock.instant().minus(idle);
        final List<LockKey> due = new ArrayList<>();
        synchronized (execShells) {
            execShells.forEach((key, shell) -> {
                if (shell.running == 0 && shell.finishedAt != null && !shell.finishedAt.isAfter(cutoff)) {
                    due.add(key);
                }
            });
        }
        int deleted = 0;
        for (LockKey key : due) {
            final ExecShell shell;
            synchronized (execShells) {
                // Re-checked now: a command may have started since the snapshot. From here until the delete ends, a
                // command starting on this directory waits (execShellStarted).
                shell = execShells.get(key);
                if (shell == null || shell.running != 0 || shell.finishedAt == null
                        || shell.finishedAt.isAfter(cutoff)) {
                    continue;
                }
                shell.sweeping = true;
            }
            try {
                final String d = key.name.replace("'", "'\\''");
                final RunningCommand command = get(key.ref).run(
                        ExecSpec.builder()
                                .command("d='" + d
                                        + "'; [ -d \"$d\" ] || exit 0; exec 9>\"$d/lock\" && flock -n 9 && rm -rf "
                                        + "\"$d\"")
                                .timeout(Duration.ofSeconds(30)).maxCaptureBytes(4096).build(),
                        OutputSink.DISCARD);
                final ExecOutcome outcome;
                try {
                    outcome = command.await(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    // RunningCommand.await: the command keeps running unless the caller kills it.
                    command.kill();
                    throw e;
                }
                if (outcome.exitCode() == 0) {
                    deleted++;
                    forget(key);
                }
            } catch (SandboxNotFoundException e) {
                forget(key);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return deleted;
            } catch (RuntimeException e) {
                log.debug("Could not sweep exec shell {} of {}: {}", key.name, key.ref, e.getMessage());
            } finally {
                synchronized (execShells) {
                    shell.sweeping = false;
                    execShells.notifyAll();
                }
            }
        }
        return deleted;
    }

    private void forget(LockKey key) {
        synchronized (execShells) {
            final ExecShell shell = execShells.get(key);
            if (shell != null && shell.running == 0) {
                execShells.remove(key);
            }
        }
    }

    /** Closes every cached connection. Sandboxes are not touched. */
    @Override
    public void close() {
        for (ProviderSandboxRef ref : List.copyOf(connections.keySet())) {
            evict(ref);
        }
    }

    private static void closeQuietly(SandboxConnection connection) {
        try {
            connection.close();
        } catch (RuntimeException e) {
            log.debug("Closing a sandbox connection failed: {}", e.getMessage());
        }
    }

    /** A held node-local lock; closing it releases the lock. */
    public static final class Lease implements AutoCloseable {
        private final SandboxConnectionCache cache;
        private final LockKey key;
        private final NodeLock lock;
        private boolean released;

        private Lease(SandboxConnectionCache cache, LockKey key, NodeLock lock) {
            this.cache = cache;
            this.key = key;
            this.lock = lock;
            this.released = !lock.lock.isHeldByCurrentThread();
        }

        /** @return whether the lock was acquired */
        public boolean acquired() {
            return !released;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                lock.lock.unlock();
                cache.leave(key, lock);
            }
        }
    }

    private static final class NodeLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private static final class ExecShell {
        private int running;
        private Instant finishedAt;
        private boolean sweeping;
    }

    private static final class LockKey {
        private final ProviderSandboxRef ref;
        private final String name;

        private LockKey(ProviderSandboxRef ref, String name) {
            this.ref = ref;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LockKey that && ref.equals(that.ref) && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ref, name);
        }
    }
}
