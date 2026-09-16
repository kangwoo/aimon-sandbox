package at.aimon.sandbox.artifact;

/**
 * Shared security constraints for tar archive operations.
 *
 * <p>
 * Both {@link TarCreator} and {@link TarExtractor} enforce these limits to ensure consistent behavior in both
 * directions (VFS → tar and tar → VFS).
 */
public final class TarSecurityPolicy {

    /** Maximum number of files allowed in a single archive. */
    public static final int MAX_FILES = 1000;

    /** Maximum total bytes allowed across all files in an archive (100 MB). */
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    /** Maximum size of a single file in an archive (50 MB). */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    /** Tar block size. */
    static final int TAR_BLOCK_SIZE = 512;

    private TarSecurityPolicy() {
    }
}
