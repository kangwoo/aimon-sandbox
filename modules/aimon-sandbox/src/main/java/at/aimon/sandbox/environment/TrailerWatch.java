package at.aimon.sandbox.environment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import at.aimon.sandbox.provider.OutputSink;

/**
 * Watches a stream script's stderr for its trailer while the exec runs (docs/design/workspace-sandbox.md §9). A hook
 * that leaves {@code cmd &} behind keeps the exec's streams open, and a provider's {@code await} may return only when
 * every holder is gone: on seeing the trailer this runs an action once — {@code SandboxShell} kills the exec — and
 * keeps the parsed trailer, which survives a stderr larger than the provider's capture cap, whose tail (the trailer
 * included) the provider drops. Only the last {@link #TAIL} bytes are kept between chunks; STDOUT is ignored.
 */
final class TrailerWatch implements OutputSink {

    /** The trailer line is under 80 bytes; this much of the previous chunks is kept so a split one still matches. */
    static final int TAIL = 512;

    private final byte[] marker;
    private final Pattern trailer;
    private final Runnable onTrailer;
    private final AtomicBoolean seen = new AtomicBoolean();
    private byte[] tail = new byte[0];
    private volatile Trailer parsed;

    /**
     * @param nonce
     *            the run's trailer nonce
     * @param onTrailer
     *            runs once, on the provider's thread, when the trailer is complete; it must not block
     */
    TrailerWatch(String nonce, Runnable onTrailer) {
        this.marker = ("\n" + nonce + " ").getBytes(StandardCharsets.US_ASCII);
        this.trailer = Pattern.compile("\n" + Pattern.quote(nonce) + " exit=(-?\\d+) cwd=(\\d)\r?\n");
        this.onTrailer = Objects.requireNonNull(onTrailer);
    }

    /** The parsed trailer of a stream script's run. */
    static final class Trailer {
        private final int exitCode;
        private final String cwd;

        Trailer(int exitCode, String cwd) {
            this.exitCode = exitCode;
            this.cwd = cwd;
        }

        int exitCode() {
            return exitCode;
        }

        String cwd() {
            return cwd;
        }
    }

    @Override
    public void accept(Stream stream, byte[] bytes, int offset, int length) {
        if (stream != Stream.STDERR || seen.get()) {
            return;
        }
        final Trailer found;
        synchronized (this) {
            final byte[] window = Arrays.copyOf(tail, tail.length + length);
            System.arraycopy(bytes, offset, window, tail.length, length);
            found = find(window);
            tail = Arrays.copyOfRange(window, Math.max(0, window.length - TAIL), window.length);
        }
        if (found != null && seen.compareAndSet(false, true)) {
            parsed = found;
            onTrailer.run();
        }
    }

    private Trailer find(byte[] window) {
        if (indexOf(window, marker) < 0) {
            return null;
        }
        final Matcher m = trailer.matcher(new String(window, StandardCharsets.ISO_8859_1));
        if (!m.find()) {
            return null;
        }
        try {
            return new Trailer(Integer.parseInt(m.group(1)), m.group(2));
        } catch (NumberFormatException e) {
            // printf %d of the outer's $? never overflows; a line that does was not the wrapper's.
            return null;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }

    /** @return the trailer, once seen */
    Optional<Trailer> trailer() {
        return Optional.ofNullable(parsed);
    }
}
