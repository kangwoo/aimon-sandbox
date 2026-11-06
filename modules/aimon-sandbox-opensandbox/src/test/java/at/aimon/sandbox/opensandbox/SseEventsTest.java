package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SseEventsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<String> texts(String stream, int max) throws IOException {
        final List<String> texts = new ArrayList<>();
        SseEvents.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), JSON, event -> {
            texts.add(event.path("text").asText());
            return true;
        }, max);
        return texts;
    }

    @Test
    @DisplayName("raw JSON and data: lines, separated by \\n, \\r\\n or \\r; noise and blank lines skipped")
    void readsEventsAsTheLineReaderDid() throws IOException {
        final String stream = "{\"text\":\"a\"}\n\n" + "data: {\"text\":\"b\"}\r\n\r\n" + "not json\n"
                + "{\"text\":\"c\"}\r" + "{broken\n" + "{\"text\":\"é\"}";

        assertThat(texts(stream, 1024)).containsExactly("a", "b", "c", "é");
    }

    /** Serves {@code bytes} at most {@code step} at a time, so every boundary falls inside a line, a CRLF or a char. */
    private static InputStream trickle(byte[] bytes, int step) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, step));
            }
        };
    }

    @Test
    @DisplayName("reads split anywhere — inside a line, between \\r and \\n, inside a multibyte char — read the same")
    void readsTheSameWhateverTheReadBoundaries() throws IOException {
        final String stream = "{\"text\":\"é한😀\"}\r\n\r\n" + "data: {\"text\":\"b\"}\r" + "{\"text\":\"c\"}\n";
        final byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);

        for (int step = 1; step <= 7; step++) {
            final List<String> texts = new ArrayList<>();
            SseEvents.read(trickle(bytes, step), JSON, event -> texts.add(event.path("text").asText()), 1024);

            assertThat(texts).as("step %d", step).containsExactly("é한😀", "b", "c");
        }
    }

    @Test
    @DisplayName("the limit holds for a line spread over several reads, multibyte content counted in bytes")
    void theLimitCountsBytesAcrossReads() throws IOException {
        // 20 000 bytes of 3-byte chars plus the JSON around them: more than two 8 KiB reads.
        final String line = "{\"text\":\"" + "한".repeat(20_000 / 3) + "\"}";
        final int size = line.getBytes(StandardCharsets.UTF_8).length;
        final byte[] twice = (line + "\n" + line + "\n").getBytes(StandardCharsets.UTF_8);
        final List<String> texts = new ArrayList<>();

        SseEvents.read(trickle(twice, 5000), JSON, event -> texts.add(event.path("text").asText()), size);
        assertThat(texts).hasSize(2).allSatisfy(text -> assertThat(text).hasSize(20_000 / 3));

        assertThatThrownBy(() -> SseEvents.read(trickle(twice, 5000), JSON, event -> true, size - 1))
                .isInstanceOf(SseEvents.LineTooLongException.class);
    }

    @Test
    void aTerminalEventStopsTheReading() throws IOException {
        final List<String> seen = new ArrayList<>();
        final boolean stopped = SseEvents.read(
                new ByteArrayInputStream("{\"t\":1}\n{\"t\":2}\n{\"t\":3}\n".getBytes(StandardCharsets.UTF_8)), JSON,
                (JsonNode event) -> {
                    seen.add(event.path("t").asText());
                    return !"2".equals(event.path("t").asText());
                });

        assertThat(stopped).isTrue();
        assertThat(seen).containsExactly("1", "2");
    }

    @Test
    void aLineAtTheLimitIsRead() throws IOException {
        final String line = "{\"text\":\"" + "x".repeat(100 - 11) + "\"}";
        assertThat(line).hasSize(100);

        assertThat(texts(line + "\n" + line, 100)).hasSize(2);
    }

    @Test
    void aLineOverTheLimitStopsTheReading() {
        final String line = "{\"text\":\"" + "x".repeat(100 - 10) + "\"}";

        assertThatThrownBy(() -> texts("{\"text\":\"ok\"}\n" + line + "\n", 100))
                .isInstanceOf(SseEvents.LineTooLongException.class).hasMessageContaining("100 bytes");
    }

    @Test
    @DisplayName("a line that never ends is not read past the limit (plus one read's chunk)")
    void anEndlessLineIsNotMaterialized() {
        final long[] served = new long[1];
        final InputStream endless = new InputStream() {
            @Override
            public int read() {
                served[0]++;
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                java.util.Arrays.fill(b, off, off + len, (byte) 'x');
                served[0] += len;
                return len;
            }
        };

        assertThatThrownBy(() -> SseEvents.read(endless, JSON, event -> true))
                .isInstanceOf(SseEvents.LineTooLongException.class);
        assertThat(served[0]).isLessThanOrEqualTo(SseEvents.MAX_LINE_BYTES + 8192L);
    }
}
