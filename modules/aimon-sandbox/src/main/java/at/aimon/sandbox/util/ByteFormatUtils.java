package at.aimon.sandbox.util;

/**
 * Utility for formatting byte sizes into human-readable strings.
 */
public final class ByteFormatUtils {

    private ByteFormatUtils() {
        // utility class
    }

    /**
     * Formats a byte count into a human-readable string (e.g. "1.5 KB", "3.2 MB").
     *
     * @param bytes
     *            the number of bytes (must be non-negative)
     * @return a human-readable string representation
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format("%.1f MB", mb);
    }
}
