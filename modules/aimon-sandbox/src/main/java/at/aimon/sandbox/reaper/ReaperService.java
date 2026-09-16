package at.aimon.sandbox.reaper;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.backend.SandboxBackend;

/**
 * TTL-based sandbox cleanup service.
 *
 * <p>
 * Runs periodically in a background thread to remove expired sandboxes. The lifecycle is managed externally by the
 * application — call {@link #start()} to begin periodic reaping and {@link #close()} to shut down.
 *
 * <p>
 * This service has application scope and outlives individual {@code AgentRuntime} instances.
 */
public class ReaperService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ReaperService.class);

    private final SandboxBackend backend;
    private final ScheduledExecutorService scheduler;
    private final long intervalMs;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public ReaperService(SandboxBackend backend, long intervalMs) {
        this.backend = Objects.requireNonNull(backend, "Backend cannot be null");
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sandbox-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts periodic reaping of expired sandboxes.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.warn("Sandbox reaper already started");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::reap, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Sandbox reaper started with interval {}ms", intervalMs);
    }

    private void reap() {
        try {
            int count = backend.reapExpired();
            if (count > 0) {
                log.info("Reaped {} expired sandboxes", count);
            }
        } catch (Exception e) {
            log.warn("Reaper tick failed, will retry next cycle", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Sandbox reaper stopped");
    }
}
