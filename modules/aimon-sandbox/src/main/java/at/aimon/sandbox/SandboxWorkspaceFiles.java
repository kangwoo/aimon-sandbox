package at.aimon.sandbox;

import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.SearchPatterns;
import at.aimon.core.filesystem.VfsPaths;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.WriteMode;
import at.aimon.sandbox.workspace.RunningSlotFiles;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.WorkspaceFile;
import at.aimon.sandbox.workspace.WorkspaceFileListing;
import at.aimon.sandbox.workspace.WorkspaceFilesUnavailableException;

/**
 * The files of a workspace's running sandbox, for a person — the backend of a file explorer
 * (docs/design/workspace-sandbox-files.md). Get the owner-facing one from {@link SandboxWorkspaces#files()} and the
 * operator's from {@link SandboxAdministration#files()}.
 *
 * <pre>{@code
 * SandboxWorkspaceFiles files = sandbox.workspaces().files();
 * WorkspaceFileListing top = files.list(ws.id(), "", 500, alice);
 * try (InputStream in = files.read(ws.id(), "repo/README.md", 0, 64 * 1024, alice)) { ... }
 * files.write(ws.id(), "repo/input.csv", upload, size, false, alice);
 * }</pre>
 *
 * <ul>
 * <li><b>Paths</b> are relative to {@code /workspace} — {@code ""} is {@code /workspace} itself — and may not escape it
 * ({@code InvalidPathException}); an absolute path is refused rather than guessed at. What an agent's file tools call
 * {@code repo/x} (relative to the binding's root) is {@code repo/x} here too with the default root.</li>
 * <li><b>Nothing is provisioned.</b> Every call reaches the {@code primary} sandbox only when it is RUNNING and seeded;
 * otherwise it throws {@link WorkspaceFilesUnavailableException} with the reason ({@code NOT_RUNNING},
 * {@code CLOSED}, …). Looking never starts a sandbox, reopens an idle-closed workspace or resets anything.</li>
 * <li><b>Rights.</b> The owner-facing instance needs the use right — the same as attaching a session; a workspace the
 * caller may not use answers as one that does not exist. The operator's checks nothing and records the actor.</li>
 * <li><b>Changes</b> count as activity (they push the idle close out; reads do not) and publish
 * {@code FILES_CHANGED} with the actor. {@code /workspace} itself and the sandbox's own {@code .aimon-*} entries are
 * read-only ({@code FileAccessDeniedException}); they can be listed and read. Nothing tells the model that a person
 * changed a file, but core's {@code Edit} and {@code Write} refuse to overwrite a file changed since it was read.</li>
 * <li><b>Failures</b> are core VFS exceptions — {@code FileNotFoundException}, {@code FileAlreadyExistsException},
 * {@code InvalidPathException}, {@code FileAccessDeniedException}, {@code VirtualFileSystemException} — or
 * {@link WorkspaceFilesUnavailableException}.</li>
 * <li><b>Not a security boundary inside the sandbox.</b> A workspace is one trust domain: the agent can replace a
 * directory with a symlink, and the provider follows it like any path. The read-only rule guards against mistakes.</li>
 * </ul>
 */
public final class SandboxWorkspaceFiles {

    /** Where every path is relative to. */
    public static final String ROOT = "/workspace";

    /** The most entries one {@link #list} returns. */
    public static final int MAX_LIST = 10_000;

    /** The most matches one {@link #search} returns. */
    public static final int MAX_SEARCH = 1_000;

    /** The most entries a {@link #search} walks before it fails rather than answer from part of the tree. */
    static final int SEARCH_SCAN_LIMIT = 100_000;

    private static final BackendType SANDBOX = BackendType.of("SANDBOX");
    private static final String RESERVED_PREFIX = ".aimon-";
    private static final Comparator<WorkspaceFile> DIRECTORIES_FIRST = Comparator
            .comparing((WorkspaceFile file) -> !file.directory()).thenComparing(WorkspaceFile::name);

    /** How a call reaches the running sandbox: with the use check, or as an operator. */
    @FunctionalInterface
    interface Opener {
        RunningSlotFiles open(SandboxWorkspaceId id, Principal caller);
    }

    private final Opener opener;

    private SandboxWorkspaceFiles(Opener opener) {
        this.opener = opener;
    }

    static SandboxWorkspaceFiles owner(SandboxWorkspaceManager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        return new SandboxWorkspaceFiles(manager::runningFiles);
    }

    static SandboxWorkspaceFiles administrator(SandboxWorkspaceManager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        return new SandboxWorkspaceFiles(
                (id, actor) -> manager.adminRunningFiles(id, Objects.requireNonNull(actor, "actor must not be null")));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // reads
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * @param id
     *            the workspace
     * @param path
     *            the file or directory
     * @param caller
     *            the principal asking
     * @return it, or empty when it does not exist
     */
    public Optional<WorkspaceFile> stat(SandboxWorkspaceId id, String path, Principal caller) {
        final String relative = relative(path);
        final RunningSlotFiles files = opener.open(id, caller);
        return files.call(f -> f.stat(absolute(relative))).map(this::view);
    }

    /**
     * One directory's entries, not descending.
     *
     * @param id
     *            the workspace
     * @param directory
     *            the directory; {@code ""} for {@code /workspace}
     * @param limit
     *            the most entries, 1 to {@value #MAX_LIST}
     * @param caller
     *            the principal asking
     * @return its entries, directories first, then by name
     * @throws FileNotFoundException
     *             when it does not exist
     * @throws InvalidPathException
     *             when it is not a directory
     */
    public WorkspaceFileListing list(SandboxWorkspaceId id, String directory, int limit, Principal caller) {
        if (limit < 1 || limit > MAX_LIST) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIST);
        }
        final String relative = relative(directory);
        final RunningSlotFiles files = opener.open(id, caller);
        final WorkspaceFile dir = existingDirectory(files, relative, directory);
        // One more than the limit: exactly the limit is complete, more is not.
        final List<FileStat> raw = files.call(f -> f.list(absolute(relative), false, limit + 1));
        final List<WorkspaceFile> entries = new ArrayList<>();
        for (FileStat stat : raw.subList(0, Math.min(limit, raw.size()))) {
            entries.add(view(stat));
        }
        entries.sort(DIRECTORIES_FIRST);
        return WorkspaceFileListing.of(dir, entries, raw.size() > limit);
    }

    /**
     * @param id
     *            the workspace
     * @param path
     *            the file
     * @param offset
     *            the first byte, at least 0
     * @param length
     *            how many bytes; negative reads to the end. A preview asks for its cap and compares
     *            {@link WorkspaceFile#size()}
     * @param caller
     *            the principal asking
     * @return the bytes; the caller closes the stream
     * @throws FileNotFoundException
     *             when it does not exist
     * @throws InvalidPathException
     *             when it is a directory
     */
    public InputStream read(SandboxWorkspaceId id, String path, long offset, long length, Principal caller) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        final String relative = relative(path);
        final RunningSlotFiles files = opener.open(id, caller);
        final FileStat stat = files.call(f -> f.stat(absolute(relative)))
                .orElseThrow(() -> new FileNotFoundException(path, SANDBOX));
        if (stat.directory()) {
            throw new InvalidPathException(path, "is a directory");
        }
        return files.call(f -> f.read(absolute(relative), offset, length));
    }

    /**
     * Files and directories under {@code directory}, at any depth, whose name matches {@code pattern}. A pattern with
     * a glob character ({@code *}, {@code ?}, {@code [} or <code>{</code>) is a glob over the name, as core's
     * {@code Glob} reads it; any other pattern matches names that contain it, ignoring case.
     *
     * @param id
     *            the workspace
     *
     * @param directory
     *            where to look; {@code ""} for {@code /workspace}
     * @param pattern
     *            the name pattern
     * @param maxResults
     *            the most matches, 1 to {@value #MAX_SEARCH}
     * @param caller
     *            the principal asking
     * @return the matches, in the provider's walk order
     * @throws VirtualFileSystemException
     *             when the tree holds more than {@value #SEARCH_SCAN_LIMIT} entries: search a narrower directory
     */
    public List<WorkspaceFile> search(SandboxWorkspaceId id, String directory, String pattern, int maxResults,
            Principal caller) {
        if (maxResults < 1 || maxResults > MAX_SEARCH) {
            throw new IllegalArgumentException("maxResults must be between 1 and " + MAX_SEARCH);
        }
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        final NameMatcher matcher = NameMatcher.of(pattern);
        final String relative = relative(directory);
        final RunningSlotFiles files = opener.open(id, caller);
        existingDirectory(files, relative, directory);
        final List<FileStat> tree = files.call(f -> f.list(absolute(relative), true, SEARCH_SCAN_LIMIT + 1));
        if (tree.size() > SEARCH_SCAN_LIMIT) {
            throw new VirtualFileSystemException("More than " + SEARCH_SCAN_LIMIT + " entries under "
                    + (relative.isEmpty() ? ROOT : relative) + "; search a narrower directory");
        }
        final List<WorkspaceFile> found = new ArrayList<>();
        for (FileStat stat : tree) {
            final WorkspaceFile file = view(stat);
            if (matcher.matches(file.name())) {
                found.add(file);
                if (found.size() >= maxResults) {
                    break;
                }
            }
        }
        return found;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // changes
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Writes a file, creating missing parent directories.
     *
     * @param id
     *            the workspace
     * @param path
     *            the file
     * @param content
     *            the bytes; read to its end, not closed
     * @param length
     *            how many bytes {@code content} holds, or {@code -1} when unknown. A host caps uploads itself
     * @param overwrite
     *            whether an existing file is replaced
     * @param caller
     *            the principal asking
     * @return the written file
     * @throws FileAlreadyExistsException
     *             when it exists and {@code overwrite} is false
     * @throws InvalidPathException
     *             when a directory is at the path
     */
    public WorkspaceFile write(SandboxWorkspaceId id, String path, InputStream content, long length, boolean overwrite,
            Principal caller) {
        Objects.requireNonNull(content, "content must not be null");
        final String relative = writable(path);
        final RunningSlotFiles files = opener.open(id, caller);
        final Optional<FileStat> existing = files.call(f -> f.stat(absolute(relative)));
        if (existing.isPresent() && existing.get().directory()) {
            throw new InvalidPathException(path, "a directory exists at this path");
        }
        if (existing.isPresent() && !overwrite) {
            throw new FileAlreadyExistsException(path, SANDBOX);
        }
        files.beforeChange();
        files.call(f -> {
            f.write(absolute(relative), content, length,
                    overwrite ? WriteMode.CREATE_OR_REPLACE : WriteMode.CREATE_NEW);
            return null;
        });
        final WorkspaceFile written = statAfter(files, relative, path);
        files.changed((existing.isPresent() ? "replaced " : "wrote ") + relative + " (" + written.size() + " bytes)");
        return written;
    }

    /**
     * Creates a directory and its missing parents; an existing directory is success and no change.
     *
     * @param id
     *            the workspace
     * @param path
     *            the directory
     * @param caller
     *            the principal asking
     * @return the directory
     * @throws FileAlreadyExistsException
     *             when a file is at the path
     */
    public WorkspaceFile createDirectory(SandboxWorkspaceId id, String path, Principal caller) {
        final String relative = writable(path);
        final RunningSlotFiles files = opener.open(id, caller);
        final Optional<FileStat> existing = files.call(f -> f.stat(absolute(relative)));
        if (existing.isPresent()) {
            if (!existing.get().directory()) {
                throw new FileAlreadyExistsException(path, SANDBOX);
            }
            return view(existing.get());
        }
        files.beforeChange();
        files.call(f -> {
            f.createDirectories(absolute(relative));
            return null;
        });
        files.changed("created directory " + relative);
        return statAfter(files, relative, path);
    }

    /**
     * Moves or renames a file or directory; a rename is a move within its directory. Missing parents of {@code to}
     * are created.
     *
     * @param id
     *            the workspace
     * @param from
     *            the source
     * @param to
     *            the destination path, not the directory to move into
     * @param overwrite
     *            whether an existing destination is replaced
     * @param caller
     *            the principal asking
     * @return the moved entry at {@code to}
     * @throws FileNotFoundException
     *             when {@code from} does not exist
     * @throws FileAlreadyExistsException
     *             when {@code to} exists and {@code overwrite} is false
     * @throws InvalidPathException
     *             when {@code to} is {@code from} or inside it
     */
    public WorkspaceFile move(SandboxWorkspaceId id, String from, String to, boolean overwrite, Principal caller) {
        final String source = writable(from);
        final String target = writable(to);
        if (target.equals(source) || target.startsWith(source + "/")) {
            throw new InvalidPathException(to, "cannot move " + from + " onto itself or into itself");
        }
        final RunningSlotFiles files = opener.open(id, caller);
        if (files.call(f -> f.stat(absolute(source))).isEmpty()) {
            throw new FileNotFoundException(from, SANDBOX);
        }
        if (!overwrite && files.call(f -> f.stat(absolute(target))).isPresent()) {
            throw new FileAlreadyExistsException(to, SANDBOX);
        }
        files.beforeChange();
        files.call(f -> {
            f.move(absolute(source), absolute(target), overwrite);
            return null;
        });
        files.changed("moved " + source + " to " + target);
        return statAfter(files, target, to);
    }

    /**
     * @param id
     *            the workspace
     * @param path
     *            the file or directory
     * @param recursive
     *            whether a populated directory may be removed
     * @param caller
     *            the principal asking
     * @throws FileNotFoundException
     *             when it does not exist
     * @throws VirtualFileSystemException
     *             for a populated directory without {@code recursive}
     */
    public void delete(SandboxWorkspaceId id, String path, boolean recursive, Principal caller) {
        final String relative = writable(path);
        final RunningSlotFiles files = opener.open(id, caller);
        final FileStat stat = files.call(f -> f.stat(absolute(relative)))
                .orElseThrow(() -> new FileNotFoundException(path, SANDBOX));
        if (stat.directory() && !recursive && !files.call(f -> f.list(absolute(relative), false, 1)).isEmpty()) {
            throw new VirtualFileSystemException("Directory is not empty: " + path);
        }
        files.beforeChange();
        files.call(f -> {
            f.delete(absolute(relative), recursive);
            return null;
        });
        files.changed("deleted " + (stat.directory() ? "directory " : "") + relative);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // paths
    // ----------------------------------------------------------------------------------------------------------------

    /** A caller path, normalised and relative to {@link #ROOT}; {@code ""} for the root. */
    static String relative(String path) {
        if (path == null) {
            throw new InvalidPathException("null", "path must not be null");
        }
        if (path.indexOf('\0') >= 0) {
            throw new InvalidPathException(path, "path contains a NUL byte");
        }
        final String slashed = path.replace('\\', '/');
        if (slashed.startsWith("/")) {
            throw new InvalidPathException(path, "paths are relative to " + ROOT);
        }
        final String normalized = VfsPaths.normalizeRelative(slashed);
        if (normalized == null) {
            throw new InvalidPathException(path, "path escapes " + ROOT);
        }
        return normalized;
    }

    /** A caller path that may be changed: not the root, not one of the sandbox's own entries. */
    static String writable(String path) {
        final String relative = relative(path);
        if (readOnly(relative)) {
            throw new FileAccessDeniedException(path,
                    relative.isEmpty()
                            ? ROOT + " itself cannot be changed"
                            : "the sandbox's own " + RESERVED_PREFIX + "* entries are read-only");
        }
        return relative;
    }

    static boolean readOnly(String relative) {
        return relative.isEmpty() || relative.startsWith(RESERVED_PREFIX);
    }

    private static String absolute(String relative) {
        return relative.isEmpty() ? ROOT : ROOT + "/" + relative;
    }

    /** A provider path as a caller path. Providers answer only under what they were asked about. */
    private static String present(String absolute) {
        if (absolute.equals(ROOT) || absolute.equals(ROOT + "/")) {
            return "";
        }
        if (!absolute.startsWith(ROOT + "/")) {
            throw new VirtualFileSystemException("the provider answered with a path outside " + ROOT + ": " + absolute);
        }
        return absolute.substring(ROOT.length() + 1);
    }

    private WorkspaceFile view(FileStat stat) {
        final String path = present(stat.path());
        return WorkspaceFile.of(path, stat.directory(), stat.size(), stat.modifiedAt(), stat.etag().orElse(null),
                readOnly(path));
    }

    private WorkspaceFile existingDirectory(RunningSlotFiles files, String relative, String asked) {
        final FileStat stat = files.call(f -> f.stat(absolute(relative)))
                .orElseThrow(() -> new FileNotFoundException(relative.isEmpty() ? ROOT : asked, SANDBOX));
        if (!stat.directory()) {
            throw new InvalidPathException(asked, "is not a directory");
        }
        return view(stat);
    }

    private WorkspaceFile statAfter(RunningSlotFiles files, String relative, String asked) {
        return files.call(f -> f.stat(absolute(relative))).map(this::view)
                .orElseThrow(() -> new FileNotFoundException(asked, SANDBOX));
    }

    /** A search pattern: a glob over the name, or a case-insensitive substring. */
    private static final class NameMatcher {
        private final PathMatcher glob;
        private final String substring;

        private NameMatcher(PathMatcher glob, String substring) {
            this.glob = glob;
            this.substring = substring;
        }

        static NameMatcher of(String pattern) {
            if (pattern.chars().anyMatch(c -> c == '*' || c == '?' || c == '[' || c == '{')) {
                // Core's own sanitising, so this search and core's Glob never read a pattern differently.
                return new NameMatcher(
                        FileSystems.getDefault().getPathMatcher("glob:" + SearchPatterns.sanitize(pattern)), null);
            }
            return new NameMatcher(null, pattern.toLowerCase(Locale.ROOT));
        }

        boolean matches(String name) {
            return glob != null ? glob.matches(Path.of(name)) : name.toLowerCase(Locale.ROOT).contains(substring);
        }
    }
}
