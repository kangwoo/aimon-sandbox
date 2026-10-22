package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StreamLines — chunks to lines")
class StreamLinesTest {

    private final List<String> lines = new ArrayList<>();
    private final StreamLines splitter = new StreamLines(lines::add, StandardCharsets.UTF_8);

    private void feed(String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        splitter.accept(bytes, 0, bytes.length);
    }

    @Test
    @DisplayName("lines split across chunks are joined, CRLF is stripped, and finish hands over the unterminated tail")
    void splitsAcrossChunks() {
        feed("al");
        feed("pha\r\nbe");
        feed("ta\ngam");
        assertThat(lines).containsExactly("alpha", "beta");

        splitter.finish();
        assertThat(lines).containsExactly("alpha", "beta", "gam");
    }

    @Test
    @DisplayName("an overlong run is cut between characters, never inside one")
    void overlongRunIsCutBetweenCharacters() {
        // 30,000 three-byte characters: past the 64 KiB cap, which is not a multiple of three.
        feed("가".repeat(30_000));
        splitter.finish();

        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(String.join("", lines)).doesNotContain("�").hasSize(30_000);
    }

    @Test
    @DisplayName("a line of exactly the cap, then its terminator, is one line — not a line and an empty one")
    void terminatorRightAfterTheCapEndsNothingMore() {
        feed("x".repeat(StreamLines.MAX_LINE_BYTES));
        feed("\r\nnext\n");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).hasSize(StreamLines.MAX_LINE_BYTES);
        assertThat(lines.get(1)).isEqualTo("next");
    }

    @Test
    @DisplayName("output delivered after finish is dropped, so a late tail is not split into two lines")
    void outputAfterFinishIsDropped() {
        feed("tail");
        splitter.finish();
        feed(" late\n");
        splitter.finish();

        assertThat(lines).containsExactly("tail");
    }

    @Test
    @DisplayName("a listener that throws loses its line, not the following ones")
    void throwingListenerLosesOneLine() {
        final List<String> kept = new ArrayList<>();
        final StreamLines failing = new StreamLines(line -> {
            if (line.equals("bad")) {
                throw new IllegalStateException("boom");
            }
            kept.add(line);
        }, StandardCharsets.UTF_8);
        final byte[] bytes = "bad\ngood\n".getBytes(StandardCharsets.UTF_8);

        failing.accept(bytes, 0, bytes.length);

        assertThat(kept).containsExactly("good");
    }
}
