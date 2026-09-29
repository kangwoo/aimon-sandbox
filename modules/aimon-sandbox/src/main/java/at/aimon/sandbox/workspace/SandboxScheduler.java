package at.aimon.sandbox.workspace;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the periodic work — activity heartbeats, the janitor loop. Injected so tests drive it by hand against a manual
 * clock instead of waiting real minutes.
 */
public interface SandboxScheduler extends AutoCloseable {

    /** A scheduled task. */
    interface Cancellable {
        /** Stops future runs; a run in progress completes. */
        void cancel();
    }

    /**
     * @param task
     *            the task; an exception it throws is logged and does not stop later runs
     * @param period
     *            the delay before the first run and between runs
     * @return a handle that cancels it
     */
    Cancellable scheduleAtFixedRate(Runnable task, Duration period);

    /** Stops every task. */
    @Override
    void close();

    /** @return a scheduler on two daemon threads */
    static SandboxScheduler daemon() {
        final AtomicInteger counter = new AtomicInteger();
        final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
            final Thread thread = new Thread(runnable, "aimon-sandbox-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        return new SandboxScheduler() {
            @Override
            public Cancellable scheduleAtFixedRate(Runnable task, Duration period) {
                final ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        org.slf4j.LoggerFactory.getLogger(SandboxScheduler.class).warn("Scheduled task failed", e);
                    }
                }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            }

            @Override
            public void close() {
                executor.shutdownNow();
            }
        };
    }
}
