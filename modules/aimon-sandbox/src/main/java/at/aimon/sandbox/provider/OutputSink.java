package at.aimon.sandbox.provider;

/**
 * Receives a running command's output as it arrives (docs/design/workspace-sandbox-provider.md §2.2); the shell's
 * streaming runs pass it on line by line (docs/design/workspace-sandbox-shell.md §5).
 * Called from provider threads; implementations must not block.
 */
public interface OutputSink {

    /** A sink that drops everything. */
    OutputSink DISCARD = (stream, bytes, offset, length) -> {
    };

    /** Which stream a chunk came from. */
    enum Stream {
        /** Standard output. */
        STDOUT,
        /** Standard error. */
        STDERR
    }

    /**
     * @param stream
     *            the stream
     * @param bytes
     *            the buffer (only valid during the call)
     * @param offset
     *            the chunk's offset
     * @param length
     *            the chunk's length
     */
    void accept(Stream stream, byte[] bytes, int offset, int length);
}
