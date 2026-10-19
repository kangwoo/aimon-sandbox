package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.RunningCommand;

/** The kill that follows a stream run's trailer (docs/design/workspace-sandbox.md §9). */
class TrailerKillTest {

    /** A command whose wait never ends by itself, as an exec a hook's {@code cmd &} holds open. */
    private static final class HeldOpen implements RunningCommand {
        private final CountDownLatch killed = new CountDownLatch(1);

        @Override
        public ExecOutcome await(Duration timeout) throws InterruptedException {
            killed.await();
            return ExecOutcome.builder().exitCode(137).build();
        }

        @Override
        public void kill() {
            killed.countDown();
        }
    }

    @Test
    void aTrailerBeforeRunReturnedKillsOnceTheCommandIsThereNotWhenItsWaitEnds() throws Exception {
        final AtomicReference<RunningCommand> running = new AtomicReference<>();
        final CountDownLatch awaited = new CountDownLatch(1);
        final HeldOpen command = new HeldOpen();

        SandboxShell.killAfterTrailer(running, awaited);
        // run(...) returns after the grace has passed; the wait (awaited) never ends without the kill.
        Thread.sleep(SandboxShell.TRAILER_KILL_GRACE.toMillis() + 200);
        running.set(command);

        assertThat(command.killed.await(2, TimeUnit.SECONDS)).as("killed without the wait having ended").isTrue();
        assertThat(awaited.getCount()).isOne();
    }

    @Test
    void aCommandAlreadyThereIsKilledAfterTheGrace() throws Exception {
        final HeldOpen command = new HeldOpen();
        final AtomicReference<RunningCommand> running = new AtomicReference<>(command);
        final long started = System.nanoTime();

        SandboxShell.killAfterTrailer(running, new CountDownLatch(1));

        assertThat(command.killed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(SandboxShell.TRAILER_KILL_GRACE.minusMillis(50));
    }
}
