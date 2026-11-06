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
