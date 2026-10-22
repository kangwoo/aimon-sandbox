package at.aimon.sandbox.environment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits one stream of a streamed run's output — stdout, or stderr — into lines for a
 * {@link at.aimon.core.shell.ShellLineListener} ({@link at.aimon.core.shell.ShellFeature#STREAMING}).
 *
 * <p>
 * A stream run's stdout is the command's own; its stderr carries the wrapper's trailer too ({@link TrailerWatch}),
 * which {@code SandboxShell} takes out of the lines this hands over. Lines are split on the {@code \n} byte and handed
 * over without their terminator
 * ({@code \n} or {@code \r\n}); a run of {@link #MAX_LINE_BYTES} bytes without one is handed over as a line of its own,
 * cut — in UTF-8 — at the start of the character it ends inside, so no character is split. {@link #finish()} hands
 * over an unterminated last line.
 *
 * <p>
 * Called from provider threads. {@code accept} and {@code finish} are synchronized, so the listener is called one line
 * at a time and in order, as its contract asks. A listener that throws loses its line; the run goes on.
 */
final class StreamLines {

    /** Longest run of output without a line terminator handed over as one line. */
    static final int MAX_LINE_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(StreamLines.class);

    private final Consumer<String> listener;
    private final Charset charset;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    /** The last byte ended a line forced out at the cap: a terminator right after it ends nothing more. */
    private boolean forced;
    /** {@link #finish()} ran: output a provider still delivers after the exec is over is dropped. */
    private boolean finished;

    StreamLines(Consumer<String> listener, Charset charset) {
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        this.charset = Objects.requireNonNull(charset, "charset must not be null");
    }

    /** Takes one chunk of stdout; complete lines go to the listener. */
    synchronized void accept(byte[] bytes, int offset, int length) {
        if (finished) {
            return;
        }
        for (int i = offset; i < offset + length; i++) {
            final byte b = bytes[i];
            if (forced && pending.size() == 0 && (b == '\n' || b == '\r')) {
                // The line this terminates was already handed over at the cap.
                forced = b == '\r';
                continue;
            }
            forced = false;
            if (b == '\n') {
                deliver(take(pending.size()));
            } else {
                pending.write(b);
                if (pending.size() >= MAX_LINE_BYTES) {
                    deliverOverlong();
                    forced = pending.size() == 0;
                }
            }
        }
    }

    /** Hands over what is left once the run has ended. */
    synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (pending.size() > 0) {
            deliver(take(pending.size()));
        }
    }

    private void deliverOverlong() {
        final byte[] bytes = pending.toByteArray();
        int cut = bytes.length;
        if (StandardCharsets.UTF_8.equals(charset)) {
            int lead = bytes.length - 1;
            int continuation = 0;
            while (lead >= 0 && continuation < 3 && (bytes[lead] & 0xC0) == 0x80) {
                lead--;
                continuation++;
            }
            if (lead > 0 && continuation < utf8Continuations(bytes[lead])) {
                cut = lead;
            }
        }
        deliver(take(cut));
    }

    /** Removes the first {@code count} pending bytes and returns them. */
    private byte[] take(int count) {
        final byte[] all = pending.toByteArray();
        pending.reset();
        pending.write(all, count, all.length - count);
        final byte[] taken = new byte[count];
        System.arraycopy(all, 0, taken, 0, count);
        return taken;
    }

    private static int utf8Continuations(byte lead) {
        if ((lead & 0xE0) == 0xC0) {
            return 1;
        }
        if ((lead & 0xF0) == 0xE0) {
            return 2;
        }
        return (lead & 0xF8) == 0xF0 ? 3 : 0;
    }

    private void deliver(byte[] raw) {
        String line = new String(raw, charset);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        try {
            listener.accept(line);
        } catch (RuntimeException e) {
            log.warn("Shell line listener failed; the line is dropped ({})", e.toString());
        }
    }
}
