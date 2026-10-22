package at.aimon.sandbox.environment;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Takes a stream run's trailer out of its stderr lines before they reach a listener: the wrapper prints
 * {@code "\n<nonce> exit=… cwd=…\n"} after the command, so the trailer line and the blank line right before it are the
 * run's, not the command's.
 *
 * <p>
 * A blank line is held until the next line says whose it was: the trailer drops it, anything else releases it first.
 * {@link #finish()} releases one still held when no trailer came (a timeout, a cancel, a lost exec) — then it was the
 * command's. Called under the lock of the {@link StreamLines} that feeds it.
 */
final class StderrTrailerFilter implements Consumer<String> {

    private final String trailerPrefix;
    private final Consumer<String> downstream;
    private boolean heldBlank;
    private boolean trailerSeen;

    /**
     * @param nonce
     *            the run's trailer nonce
     * @param downstream
     *            receives the command's stderr lines
     */
    StderrTrailerFilter(String nonce, Consumer<String> downstream) {
        this.trailerPrefix = Objects.requireNonNull(nonce, "nonce must not be null") + " ";
        this.downstream = Objects.requireNonNull(downstream, "downstream must not be null");
    }

    @Override
    public void accept(String line) {
        if (line.startsWith(trailerPrefix)) {
            heldBlank = false;
            trailerSeen = true;
            return;
        }
        if (heldBlank) {
            heldBlank = false;
            downstream.accept("");
        }
        if (line.isEmpty()) {
            heldBlank = true;
            return;
        }
        downstream.accept(line);
    }

    /** Releases a blank line still held when the run ended without its trailer. */
    void finish() {
        if (heldBlank && !trailerSeen) {
            downstream.accept("");
        }
        heldBlank = false;
    }
}
