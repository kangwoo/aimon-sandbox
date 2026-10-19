package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.provider.OutputSink.Stream;

/** The stream run's trailer, watched while the exec streams (docs/design/workspace-sandbox.md §9). */
class TrailerWatchTest {

    private static final String NONCE = "AIMON-0123abcd";
    private static final byte[] STDERR = ("warning: something\n\n" + NONCE + " exit=2 cwd=1\n")
            .getBytes(StandardCharsets.US_ASCII);

    private final AtomicInteger fired = new AtomicInteger();
    private final TrailerWatch watch = new TrailerWatch(NONCE, fired::incrementAndGet);

    @Test
    void aTrailerSplitAtAnyOffsetIsParsedOnce() {
        for (int split = 0; split <= STDERR.length; split++) {
            final AtomicInteger runs = new AtomicInteger();
            final TrailerWatch split2 = new TrailerWatch(NONCE, runs::incrementAndGet);

            split2.accept(Stream.STDERR, STDERR, 0, split);
            split2.accept(Stream.STDERR, STDERR, split, STDERR.length - split);

            assertThat(runs).as("split at " + split).hasValue(1);
            assertThat(split2.trailer()).hasValueSatisfying(t -> {
                assertThat(t.exitCode()).isEqualTo(2);
                assertThat(t.cwd()).isEqualTo("1");
            });
        }
    }

    @Test
    void aTrailerBehindMoreThanTheTailIsStillFound() {
        final byte[] flood = "x".repeat(10 * TrailerWatch.TAIL).getBytes(StandardCharsets.US_ASCII);

        watch.accept(Stream.STDERR, flood, 0, flood.length);
        watch.accept(Stream.STDERR, STDERR, 0, STDERR.length);

        assertThat(fired).hasValue(1);
    }

    @Test
    void aLineWithoutItsNewlineIsNotYetATrailer() {
        final byte[] partial = ("\n" + NONCE + " exit=0 cwd=0").getBytes(StandardCharsets.US_ASCII);

        watch.accept(Stream.STDERR, partial, 0, partial.length);
        assertThat(fired).hasValue(0);
        watch.accept(Stream.STDERR, new byte[]{'\n'}, 0, 1);

        assertThat(fired).hasValue(1);
        assertThat(watch.trailer()).hasValueSatisfying(t -> assertThat(t.exitCode()).isZero());
    }

    @Test
    void anotherNonceOrStdoutIsIgnoredAndTheActionRunsOnce() {
        final byte[] other = "\nAIMON-ffff exit=0 cwd=0\n".getBytes(StandardCharsets.US_ASCII);

        watch.accept(Stream.STDERR, other, 0, other.length);
        watch.accept(Stream.STDOUT, STDERR, 0, STDERR.length);
        assertThat(fired).hasValue(0);
        assertThat(watch.trailer()).isEmpty();

        watch.accept(Stream.STDERR, STDERR, 0, STDERR.length);
        watch.accept(Stream.STDERR, STDERR, 0, STDERR.length);

        assertThat(fired).hasValue(1);
    }

    @Test
    void aNormalizedCarriageReturnStillMatches() {
        final byte[] crlf = ("\n" + NONCE + " exit=-1 cwd=2\r\n").getBytes(StandardCharsets.US_ASCII);

        watch.accept(Stream.STDERR, crlf, 0, crlf.length);

        assertThat(watch.trailer()).hasValueSatisfying(t -> {
            assertThat(t.exitCode()).isEqualTo(-1);
            assertThat(t.cwd()).isEqualTo("2");
        });
    }
}
