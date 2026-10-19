package at.aimon.sandbox.workspace;

import java.util.List;
import java.util.Objects;

/**
 * One directory's entries (docs/design/workspace-sandbox-files.md §5.2): directories first, then by name.
 * {@link #truncated()} says the directory holds more than the limit asked for; which entries made the cut is the
 * provider's order, not the sort.
 */
public final class WorkspaceFileListing {

    private final WorkspaceFile directory;
    private final List<WorkspaceFile> entries;
    private final boolean truncated;

    private WorkspaceFileListing(WorkspaceFile directory, List<WorkspaceFile> entries, boolean truncated) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.entries = List.copyOf(entries);
        this.truncated = truncated;
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
     * release.
     *
     * @param directory
     *            the listed directory
     * @param entries
     *            its entries, sorted
     * @param truncated
     *            whether there are more
     * @return the listing
     */
    public static WorkspaceFileListing of(WorkspaceFile directory, List<WorkspaceFile> entries, boolean truncated) {
        return new WorkspaceFileListing(directory, entries, truncated);
    }

    /** @return the listed directory */
    public WorkspaceFile directory() {
        return directory;
    }

    /** @return its entries: directories first, then by name */
    public List<WorkspaceFile> entries() {
        return entries;
    }

    /** @return whether the directory holds more entries than were returned */
    public boolean truncated() {
        return truncated;
    }
}
