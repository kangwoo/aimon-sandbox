package at.aimon.sandbox.provider;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One file or directory as {@link SandboxFiles#stat} and {@link SandboxFiles#list} report it. {@link #modifiedAt()}
 * has at least millisecond resolution, or {@link #etag()} is present — a content change must change one of them
 * (docs/design/workspace-sandbox.md §6.1, core {@code FileMetadata}'s change-detection contract).
 */
public final class FileStat {

    private final String path;
    private final long size;
    private final Instant modifiedAt;
    private final boolean directory;
    private final String etag;

    private FileStat(String path, long size, Instant modifiedAt, boolean directory, String etag) {
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.size = size;
        this.modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt must not be null");
        this.directory = directory;
        this.etag = etag;
    }

    /**
     * @param path
     *            the absolute path inside the sandbox
     * @param size
     *            the size in bytes (0 for a directory)
     * @param modifiedAt
     *            the modification time
     * @param directory
     *            whether it is a directory
     * @param etag
     *            a content hash, or {@code null}
     * @return the stat
     */
    public static FileStat of(String path, long size, Instant modifiedAt, boolean directory, String etag) {
        return new FileStat(path, size, modifiedAt, directory, etag);
    }

    /** @return the absolute path inside the sandbox */
    public String path() {
        return path;
    }

    /** @return the size in bytes */
    public long size() {
        return size;
    }

    /** @return the modification time */
    public Instant modifiedAt() {
        return modifiedAt;
    }

    /** @return whether it is a directory */
    public boolean directory() {
        return directory;
    }

    /** @return a content hash, when the provider gives one */
    public Optional<String> etag() {
        return Optional.ofNullable(etag);
    }

    @Override
    public String toString() {
        return path + (directory ? "/" : "") + " (" + size + "B, " + modifiedAt + ", etag=" + etag + ')';
    }
}
