package at.aimon.sandbox.opensandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * execd's {@code /command} stream: JSON objects separated by blank lines, each optionally prefixed {@code data:} as
 * in SSE (docs/design/opensandbox-spike.md §3, B §d). Tolerant: a line that is not JSON is skipped.
 *
 * <p>
 * <b>Bounded.</b> execd puts no limit on an output line (it buffers a pending line without bound and sends it as one
 * event), so a command that prints one huge line would make a line reader hold all of it before the capture cap could
 * apply. A line is therefore never materialized past {@link #MAX_LINE_BYTES}: the reader stops with
 * {@link LineTooLongException} as soon as a line grows over the limit, having held at most that many bytes of it
 * (docs/design/ptc-shell-prerequisites.md S2).
 */
final class SseEvents {

    /**
     * The longest event line read, in bytes as received — JSON-escaped, so an output line of N bytes is at least N
     * here.
     */
    static final int MAX_LINE_BYTES = 256 * 1024;

    private static final int CHUNK = 8192;

    private SseEvents() {
    }

    /** An event line over the limit: the stream was not read past it. */
    static final class LineTooLongException extends IOException {
        private static final long serialVersionUID = 1L;

        LineTooLongException(int limit) {
            super("an event line of the command stream exceeds " + limit + " bytes");
        }
    }

    /**
     * Reads events until the stream ends or {@code handler} returns false.
     *
     * @return whether the handler asked to stop (a terminal event), as opposed to the stream ending
     * @throws LineTooLongException
     *             when a line exceeds {@link #MAX_LINE_BYTES}
     * @throws IOException
     *             when the stream breaks
     */
    static boolean read(InputStream in, ObjectMapper json, Predicate<JsonNode> handler) throws IOException {
        return read(in, json, handler, MAX_LINE_BYTES);
    }

    /** {@link #read(InputStream, ObjectMapper, Predicate)} with a given limit, for tests. */
    static boolean read(InputStream in, ObjectMapper json, Predicate<JsonNode> handler, int maxLineBytes)
            throws IOException {
        final byte[] chunk = new byte[CHUNK];
        final ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int n;
        while ((n = in.read(chunk)) != -1) {
            int start = 0;
            for (int i = 0; i < n; i++) {
                final byte b = chunk[i];
                // As BufferedReader.readLine did: \n, \r and \r\n all end a line; the empty line between is skipped.
                if (b != '\n' && b != '\r') {
                    continue;
                }
                append(line, chunk, start, i - start, maxLineBytes);
                start = i + 1;
                if (!dispatch(line, json, handler)) {
                    return true;
                }
            }
            append(line, chunk, start, n - start, maxLineBytes);
        }
        // A last line without a terminator counts, as it did for readLine.
        return line.size() > 0 && !dispatch(line, json, handler);
    }

    private static void append(ByteArrayOutputStream line, byte[] chunk, int offset, int length, int max)
            throws LineTooLongException {
        if (length > max - line.size()) {
            throw new LineTooLongException(max);
        }
        line.write(chunk, offset, length);
    }

    /** @return false when the handler asked to stop */
    private static boolean dispatch(ByteArrayOutputStream line, ObjectMapper json, Predicate<JsonNode> handler) {
        if (line.size() == 0) {
            return true;
        }
        String data = line.toString(StandardCharsets.UTF_8).strip();
        line.reset();
        if (data.startsWith("data:")) {
            data = data.substring(5).strip();
        }
        if (data.isEmpty() || data.charAt(0) != '{') {
            return true;
        }
        final JsonNode event;
        try {
            event = json.readTree(data);
        } catch (IOException e) {
            return true;
        }
        return handler.test(event);
    }
}
