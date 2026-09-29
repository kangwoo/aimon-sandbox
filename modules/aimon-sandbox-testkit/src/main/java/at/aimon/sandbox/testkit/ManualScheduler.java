package at.aimon.sandbox.testkit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.workspace.SandboxScheduler;

/**
 * A {@link SandboxScheduler} driven by a {@link ManualClock}: nothing runs until {@link #runDue()} (or
 * {@link #advance(Duration)}) is called, and then every task whose time has come runs once, on the calling thread.
 */
public final class ManualScheduler implements SandboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(ManualScheduler.class);

    private final ManualClock clock;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();

    /**
     * @param clock
     *            the clock task times are read from
     */
    public ManualScheduler(ManualClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Cancellable scheduleAtFixedRate(Runnable task, Duration period) {
        final Task scheduled = new Task(task, period, clock.instant().plus(period));
        tasks.add(scheduled);
        return () -> tasks.remove(scheduled);
    }

    /**
     * Runs every task due at the clock's current instant, once each.
     *
     * @return how many ran
     */
    public int runDue() {
        final Instant now = clock.instant();
        final List<Task> due = new ArrayList<>();
        for (Task task : tasks) {
            if (!task.next.isAfter(now)) {
                due.add(task);
            }
        }
        for (Task task : due) {
            task.next = now.plus(task.period);
            try {
                task.runnable.run();
            } catch (RuntimeException e) {
                log.warn("Scheduled task failed", e);
            }
        }
        return due.size();
    }

    /**
     * Moves the clock forward and runs what is due.
     *
     * @param duration
     *            how far
     * @return how many tasks ran
     */
    public int advance(Duration duration) {
        clock.advance(duration);
        return runDue();
    }

    /** @return how many tasks are scheduled */
    public int scheduled() {
        return tasks.size();
    }

    @Override
    public void close() {
        tasks.clear();
    }

    private static final class Task {
        private final Runnable runnable;
        private final Duration period;
        private volatile Instant next;

        private Task(Runnable runnable, Duration period, Instant next) {
            this.runnable = runnable;
            this.period = period;
            this.next = next;
        }
    }
}
