package at.aimon.sandbox.environment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VfsPaths;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.WriteMode;

/**
 * The raw sandbox filesystem: a {@link VirtualFileSystem} over one connection's {@link SandboxFiles}, rooted at
 * {@code /} (docs/design/workspace-sandbox.md §6.6 of the step-3 design). Its working directory is {@code /} so that
 * core's path-rule wrapper, which resolves every path under the delegate's working directory, sees every absolute
 * sandbox path — {@code /workspace/.aimon-staged} and {@code /shared} included. Listings return paths relative to
 * {@code /}, as the VFS contract asks. A listing larger than {@value #LIST_LIMIT} entries fails rather than returning
 * a silently truncated tree, which Glob and Grep would present as complete.
 */
final class ProviderFileSystem implements VirtualFileSystem {

    static final BackendType SANDBOX = BackendType.of("SANDBOX");
    static final int LIST_LIMIT = 100_000;

    private final SandboxFiles files;
    private final int listLimit;

    ProviderFileSystem(SandboxFiles files) {
        this(files, LIST_LIMIT);
    }

    ProviderFileSystem(SandboxFiles files, int listLimit) {
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.listLimit = listLimit;
    }

    /** The absolute, normalised sandbox path of a caller path (relative paths are relative to {@code /}). */
    static String absolute(String path) {
        if (path == null) {
            throw new InvalidPathException("null", "path must not be null");
        }
        if (path.indexOf('\0') >= 0) {
            throw new InvalidPathException(path, "path contains a NUL byte");
        }
        final String normalized = VfsPaths.normalizeRelative(path.replace('\\', '/'));
        if (normalized == null) {
            throw new InvalidPathException(path, "path escapes the root");
        }
        return "/" + normalized;
    }

    private static String rootRelative(String absolute) {
        return absolute.startsWith("/") ? absolute.substring(1) : absolute;
    }

    private Optional<FileStat> stat(String path) {
        return files.stat(absolute(path));
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        final String target = absolute(path);
        final Optional<FileStat> existing = files.stat(target);
        if (existing.isPresent() && existing.get().directory()) {
            throw new InvalidPathException(path, "a directory exists at this path");
        }
        files.write(target, content, contentLength, WriteMode.CREATE_OR_REPLACE);
    }

    @Override
    public InputStream read(String path) {
        final String target = absolute(path);
        final FileStat stat = files.stat(target).orElseThrow(() -> new FileNotFoundException(path, SANDBOX));
        if (stat.directory()) {
            throw new InvalidPathException(path, "is a directory");
        }
        return files.read(target, 0, -1);
    }

    @Override
    public void delete(String path) {
        final String target = absolute(path);
        final FileStat stat = files.stat(target).orElseThrow(() -> new FileNotFoundException(path, SANDBOX));
        if (stat.directory() && !files.list(target, false, 1).isEmpty()) {
            throw new VirtualFileSystemException("Directory is not empty: " + path);
        }
        files.delete(target, false);
    }

    @Override
    public boolean exists(String path) {
        return stat(path).isPresent();
    }

    @Override
    public boolean isDirectory(String path) {
        return stat(path).map(FileStat::directory).orElse(false);
    }

    @Override
    public FileMetadata getMetadata(String path) {
        final FileStat stat = stat(path).orElseThrow(() -> new FileNotFoundException(path, SANDBOX));
        return FileMetadata.builder().path(rootRelative(stat.path())).size(stat.directory() ? 0 : stat.size())
                .createdAt(stat.modifiedAt()).modifiedAt(stat.modifiedAt()).directory(stat.directory())
                .etag(stat.etag().orElse(null)).build();
    }

    @Override
    public List<String> list(String directory) {
        return listing(directory, false).stream().map(stat -> rootRelative(stat.path())).toList();
    }

    @Override
    public List<String> listRecursive(String directory) {
        return listing(directory, true).stream().filter(stat -> !stat.directory())
                .map(stat -> rootRelative(stat.path())).toList();
    }

    private List<FileStat> listing(String directory, boolean recursive) {
        final String target = absolute(directory);
        final FileStat stat = files.stat(target).orElseThrow(() -> new FileNotFoundException(directory, SANDBOX));
        if (!stat.directory()) {
            throw new InvalidPathException(directory, "is not a directory");
        }
        // One more than the limit: exactly the limit is complete, more is not.
        final List<FileStat> entries = files.list(target, recursive, listLimit + 1);
        if (entries.size() > listLimit) {
            throw new VirtualFileSystemException(
                    "More than " + listLimit + " entries under " + directory + "; list or search a narrower directory");
        }
        return entries;
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        if (!overwrite && exists(destinationPath)) {
            throw new FileAlreadyExistsException(destinationPath, SANDBOX);
        }
        final byte[] bytes;
        try (InputStream in = read(sourcePath)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write(destinationPath, new ByteArrayInputStream(bytes), bytes.length);
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        if (!exists(sourcePath)) {
            throw new FileNotFoundException(sourcePath, SANDBOX);
        }
        if (!overwrite && exists(destinationPath)) {
            throw new FileAlreadyExistsException(destinationPath, SANDBOX);
        }
        files.move(absolute(sourcePath), absolute(destinationPath), overwrite);
    }

    @Override
    public OutputStream openOutputStream(String path) {
        final String target = absolute(path);
        return new ByteArrayOutputStream() {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    super.close();
                    ProviderFileSystem.this.write(target, new ByteArrayInputStream(toByteArray()), size());
                }
            }
        };
    }

    @Override
    public InputStream openInputStream(String path) {
        return read(path);
    }

    @Override
    public void createDirectory(String path) {
        final String target = absolute(path);
        final Optional<FileStat> existing = files.stat(target);
        if (existing.isPresent() && !existing.get().directory()) {
            throw new FileAlreadyExistsException(path, SANDBOX);
        }
        files.createDirectories(target);
    }

    @Override
    public void deleteRecursive(String path) {
        final String target = absolute(path);
        if ("/".equals(target)) {
            throw new VirtualFileSystemException("The root cannot be deleted");
        }
        if (files.stat(target).isEmpty()) {
            throw new FileNotFoundException(path, SANDBOX);
        }
        files.delete(target, true);
    }

    @Override
    public List<String> search(String directory, String pattern, int maxResults) {
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1");
        }
        final String glob = pattern.replaceAll("[/\\\\{}\\[\\]]", "").replaceAll("\\*+", "*");
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        final List<String> found = new ArrayList<>();
        for (FileStat stat : listing(directory, true)) {
            if (stat.directory()) {
                continue;
            }
            final String name = stat.path().substring(stat.path().lastIndexOf('/') + 1);
            if (matcher.matches(Path.of(name))) {
                found.add(rootRelative(stat.path()));
                if (found.size() >= maxResults) {
                    break;
                }
            }
        }
        return found;
    }

    @Override
    public String getWorkingDirectory() {
        return "/";
    }

    @Override
    public void initialize() {
        // The connection is live when this view is made.
    }

    @Override
    public BackendStatus getStatus() {
        return BackendStatus.connected(SANDBOX);
    }

    @Override
    public void close() {
        // A view over a cached connection: nothing to release.
    }
}
