package at.aimon.sandbox.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** {@link SandboxScheduler#daemon}: a failing run never cancels the runs after it. */
class SandboxSchedulerTest {

    @Test
    void aTaskThatThrowsAnErrorStillRunsAgain() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch third = new CountDownLatch(3);
        try (SandboxScheduler scheduler = SandboxScheduler.daemon("scheduler-test", 1)) {
            scheduler.scheduleAtFixedRate(() -> {
                third.countDown();
                if (runs.incrementAndGet() == 1) {
                    // An Error (a failed assertion in a listener, an OutOfMemoryError that passed) — not a
                    // RuntimeException: a ScheduledExecutorService would cancel every later run.
                    throw new AssertionError("first run fails");
                }
            }, Duration.ofMillis(20));

            assertThat(third.await(5, TimeUnit.SECONDS)).as("runs after the failing one").isTrue();
        }
    }
}
