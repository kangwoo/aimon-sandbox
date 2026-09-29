package at.aimon.sandbox.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that moves only when told to — idle, heartbeat and backoff rules are tested without waiting hours. */
public final class ManualClock extends Clock {

    private final AtomicReference<Instant> now;

    /** Starts at {@code 2026-01-01T00:00:00Z}. */
    public ManualClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * @param start
     *            the initial instant
     */
    public ManualClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    /**
     * @param duration
     *            how far to move forward
     * @return the new instant
     */
    public Instant advance(Duration duration) {
        return now.updateAndGet(current -> current.plus(duration));
    }

    /**
     * @param instant
     *            the new instant
     */
    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
