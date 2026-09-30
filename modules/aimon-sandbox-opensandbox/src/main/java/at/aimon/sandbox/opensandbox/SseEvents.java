package at.aimon.sandbox.opensandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * execd's {@code /command} stream: JSON objects separated by blank lines, each optionally prefixed {@code data:} as
 * in SSE (docs/design/opensandbox-spike.md §3, B §d). Tolerant: a line that is not JSON is skipped.
 */
final class SseEvents {

    private SseEvents() {
    }

    /**
     * Reads events until the stream ends or {@code handler} returns false.
     *
     * @return whether the handler asked to stop (a terminal event), as opposed to the stream ending
     * @throws IOException
     *             when the stream breaks
     */
    static boolean read(InputStream in, ObjectMapper json, Predicate<JsonNode> handler) throws IOException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String data = line.strip();
            if (data.startsWith("data:")) {
                data = data.substring(5).strip();
            }
            if (data.isEmpty() || data.charAt(0) != '{') {
                continue;
            }
            final JsonNode event;
            try {
                event = json.readTree(data);
            } catch (IOException e) {
                continue;
            }
            if (!handler.test(event)) {
                return true;
            }
        }
        return false;
    }
}
