package at.aimon.sandbox.provider;

/**
 * Receives a running command's output as it arrives (docs/design/workspace-sandbox.md §6.2). Nothing streams it to
 * the model yet — {@code SandboxShell} collects the result — but the SPI does not have to change when something does.
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
