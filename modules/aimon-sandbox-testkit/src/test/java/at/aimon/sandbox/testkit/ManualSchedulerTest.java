package at.aimon.sandbox.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.workspace.SandboxScheduler;

class ManualSchedulerTest {

    @Test
    void runsTasksOnlyWhenTheirTimeHasCome() {
        final ManualClock clock = new ManualClock();
        final ManualScheduler scheduler = new ManualScheduler(clock);
        final AtomicInteger runs = new AtomicInteger();
        final SandboxScheduler.Cancellable task = scheduler.scheduleAtFixedRate(runs::incrementAndGet,
                Duration.ofSeconds(30));

        assertThat(scheduler.runDue()).isZero();
        assertThat(scheduler.advance(Duration.ofSeconds(29))).isZero();
        assertThat(scheduler.advance(Duration.ofSeconds(1))).isEqualTo(1);
        assertThat(scheduler.advance(Duration.ofMinutes(5))).as("once per call, not once per missed period")
                .isEqualTo(1);
        scheduler.scheduleAtFixedRate(() -> {
            throw new IllegalStateException("boom");
        }, Duration.ofSeconds(1));
        assertThat(scheduler.advance(Duration.ofSeconds(30))).isEqualTo(2);

        task.cancel();
        assertThat(scheduler.scheduled()).isEqualTo(1);
        scheduler.close();
        assertThat(scheduler.scheduled()).isZero();
        assertThat(runs).hasValue(3);
    }

    @Test
    void theDaemonSchedulerRunsAndCancels() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        try (SandboxScheduler scheduler = SandboxScheduler.daemon()) {
            final SandboxScheduler.Cancellable task = scheduler.scheduleAtFixedRate(runs::incrementAndGet,
                    Duration.ofMillis(20));
            Thread.sleep(200);
            task.cancel();
        }
        assertThat(runs.get()).isPositive();
    }

    @Test
    void theManualClockMovesOnlyWhenTold() {
        final ManualClock clock = new ManualClock();
        final var start = clock.instant();

        clock.advance(Duration.ofHours(1));

        assertThat(clock.instant()).isEqualTo(start.plus(Duration.ofHours(1)));
        assertThat(clock.withZone(java.time.ZoneId.of("Asia/Seoul"))).isSameAs(clock);
        assertThat(SandboxTestProfiles.hostPlatform()).isNotBlank();
    }
}
