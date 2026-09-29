package at.aimon.sandbox.workspace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The heartbeat of one running command (docs/design/workspace-sandbox.md §5.3): every {@code activityWriteInterval}
 * it records activity and pushes the provider expiry, so a twenty-minute build keeps its sandbox without tool calls.
 * It stops with the command, or with the node — a counter in the record would not. A background command's heartbeat
 * also stops after {@code backgroundHeartbeatLimit}; the command keeps running but no longer keeps the sandbox awake.
 */
final class ActivityHeartbeat implements Heartbeat {

    private static final Logger log = LoggerFactory.getLogger(ActivityHeartbeat.class);

    private final Predicate<Boolean> touch;
    private final Clock clock;
    private final Instant started;
    private final Duration limit;
    private final Runnable onTick;
    private final Runnable onLost;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final SandboxScheduler.Cancellable task;

    /**
     * @param touch
     *            records activity; answers false when the sandbox is gone
     * @param limit
     *            how long a background command's heartbeat lasts, or {@code null} for a foreground command
     */
    ActivityHeartbeat(Predicate<Boolean> touch, SandboxScheduler scheduler, Clock clock, Duration interval,
            Duration limit, Runnable onTick, Runnable onLost) {
        this.touch = touch;
        this.clock = clock;
        this.started = clock.instant();
        this.limit = limit;
        this.onTick = onTick;
        this.onLost = onLost;
        this.task = scheduler.scheduleAtFixedRate(this::tick, interval);
    }

    private void tick() {
        if (stopped.get()) {
            return;
        }
        if (limit != null && !clock.instant().isBefore(started.plus(limit))) {
            log.debug("Background heartbeat reached its limit of {}; the command no longer keeps the sandbox awake",
                    limit);
            close();
            return;
        }
        final boolean alive;
        try {
            alive = touch.test(Boolean.TRUE);
        } catch (RuntimeException e) {
            log.warn("Activity heartbeat failed; retrying on the next tick: {}", e.getMessage());
            return;
        }
        if (!alive) {
            close();
            onLost.run();
            return;
        }
        if (onTick != null) {
            try {
                onTick.run();
            } catch (RuntimeException e) {
                log.debug("Heartbeat tick callback failed: {}", e.getMessage());
            }
        }
    }

    /** @return whether the heartbeat stopped (closed, lost, or past its background limit) */
    boolean stopped() {
        return stopped.get();
    }

    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            task.cancel();
        }
    }
}
