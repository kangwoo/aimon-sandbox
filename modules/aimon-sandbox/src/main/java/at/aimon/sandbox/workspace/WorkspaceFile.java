package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One file or directory under {@code /workspace} as a file explorer sees it (docs/design/workspace-sandbox-files.md
 * §5.2). {@link #path()} is relative to {@code /workspace} — {@code ""} is {@code /workspace} itself. No view carries a
 * provider sandbox id.
 */
public final class WorkspaceFile {

    private final String path;
    private final boolean directory;
    private final long size;
    private final Instant modifiedAt;
    private final String etag;
    private final boolean readOnly;

    private WorkspaceFile(String path, boolean directory, long size, Instant modifiedAt, String etag,
            boolean readOnly) {
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.directory = directory;
        this.size = size;
        this.modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt must not be null");
        this.etag = etag;
        this.readOnly = readOnly;
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
     * release.
     *
     * @param path
     *            relative to {@code /workspace}
     * @param directory
     *            whether it is a directory
     * @param size
     *            the size in bytes (0 for a directory)
     * @param modifiedAt
     *            the modification time
     * @param etag
     *            a content hash, or {@code null}
     * @param readOnly
     *            whether the explorer refuses to change it
     * @return the view
     */
    public static WorkspaceFile of(String path, boolean directory, long size, Instant modifiedAt, String etag,
            boolean readOnly) {
        return new WorkspaceFile(path, directory, directory ? 0 : size, modifiedAt, etag, readOnly);
    }

    /** @return the path relative to {@code /workspace}; {@code ""} for {@code /workspace} itself */
    public String path() {
        return path;
    }

    /** @return the last path segment; {@code ""} for {@code /workspace} itself */
    public String name() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** @return the absolute path inside the sandbox */
    public String sandboxPath() {
        return path.isEmpty() ? "/workspace" : "/workspace/" + path;
    }

    /** @return whether it is a directory */
    public boolean directory() {
        return directory;
    }

    /** @return the size in bytes; 0 for a directory */
    public long size() {
        return size;
    }

    /** @return the modification time */
    public Instant modifiedAt() {
        return modifiedAt;
    }

    /** @return a content hash, when the provider gives one */
    public Optional<String> etag() {
        return Optional.ofNullable(etag);
    }

    /**
     * @return whether the explorer refuses to write, move or delete it: {@code /workspace} itself and the sandbox's own
     *         {@code .aimon-*} entries (shell state, staged skills, the seed lock)
     */
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorkspaceFile other && path.equals(other.path) && directory == other.directory
                && size == other.size && modifiedAt.equals(other.modifiedAt) && Objects.equals(etag, other.etag)
                && readOnly == other.readOnly;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, directory, size, modifiedAt, etag, readOnly);
    }

    @Override
    public String toString() {
        return (path.isEmpty() ? "." : path) + (directory ? "/" : "") + " (" + size + "B, " + modifiedAt
                + (readOnly ? ", read-only" : "") + ')';
    }
}
