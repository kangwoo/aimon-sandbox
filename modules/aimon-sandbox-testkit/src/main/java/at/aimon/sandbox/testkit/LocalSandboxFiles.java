package at.aimon.sandbox.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.WriteMode;

/**
 * The files API over a sandbox directory. {@link #stat} gives an etag (the content's SHA-256), so the change-detection
 * contract holds whatever the host filesystem's timestamp resolution.
 */
final class LocalSandboxFiles implements SandboxFiles {

    private final LocalConnection connection;

    LocalSandboxFiles(LocalConnection connection) {
        this.connection = connection;
    }

    @Override
    public InputStream read(String path, long offset, long length) {
        final Path host = existing(path);
        if (Files.isDirectory(host)) {
            throw new VirtualFileSystemException("Is a directory: " + path);
        }
        InputStream in = null;
        try {
            in = Files.newInputStream(host);
            in.skipNBytes(Math.min(offset, Files.size(host)));
            return length < 0 ? in : new BoundedInputStream(in, length);
        } catch (IOException e) {
            closeQuietly(in);
            throw new VirtualFileSystemException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String path, InputStream content, long length, WriteMode mode) {
        final Path host = connection.toHost(path);
        if (Files.isDirectory(host)) {
            throw new VirtualFileSystemException("Is a directory: " + path);
        }
        try {
            Files.createDirectories(host.getParent());
            final Path tmp = Files.createTempFile(host.getParent(), ".aimon-write-", ".tmp");
            try {
                try (InputStream in = length < 0 ? content : new BoundedInputStream(content, length)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (mode == WriteMode.CREATE_NEW) {
                    // link(2) refuses an existing target atomically; rename(2) would replace it, whatever
                    // ATOMIC_MOVE suggests.
                    Files.createLink(host, tmp);
                } else {
                    try {
                        Files.move(tmp, host, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, host, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new FileAlreadyExistsException(path);
        } catch (IOException e) {
            throw new VirtualFileSystemException("cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<FileStat> stat(String path) {
        final Path host = connection.toHost(path);
        if (!Files.exists(host, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            final BasicFileAttributes attributes = Files.readAttributes(host, BasicFileAttributes.class);
            final boolean directory = attributes.isDirectory();
            return Optional.of(FileStat.of(path, directory ? 0 : attributes.size(),
                    attributes.lastModifiedTime().toInstant(), directory, directory ? null : sha256(host)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<FileStat> list(String directory, boolean recursive, int limit) {
        final Path host = existing(directory);
        if (!Files.isDirectory(host)) {
            throw new VirtualFileSystemException("Not a directory: " + directory);
        }
        final List<FileStat> out = new ArrayList<>();
        try (Stream<Path> entries = recursive ? Files.walk(host) : Files.list(host)) {
            entries.filter(p -> !p.equals(host)).sorted().limit(limit).forEach(p -> {
                try {
                    final BasicFileAttributes attributes = Files.readAttributes(p, BasicFileAttributes.class);
                    out.add(FileStat.of(connection.toSandbox(p), attributes.isDirectory() ? 0 : attributes.size(),
                            attributes.lastModifiedTime().toInstant(), attributes.isDirectory(), null));
                } catch (IOException e) {
                    // Vanished while listing: skip it.
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new VirtualFileSystemException("cannot list " + directory + ": " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public void createDirectories(String path) {
        final Path host = connection.toHost(path);
        if (Files.exists(host) && !Files.isDirectory(host)) {
            throw new FileAlreadyExistsException(path);
        }
        try {
            Files.createDirectories(host);
        } catch (IOException e) {
            throw new VirtualFileSystemException("cannot create " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String path, boolean recursive) {
        final Path host = existing(path);
        if (recursive) {
            LocalProcessSandboxProvider.deleteTree(host);
            if (Files.exists(host, LinkOption.NOFOLLOW_LINKS)) {
                throw new VirtualFileSystemException("could not delete all of " + path);
            }
            return;
        }
        try {
            Files.delete(host);
        } catch (DirectoryNotEmptyException e) {
            throw new VirtualFileSystemException("Directory is not empty: " + path);
        } catch (IOException e) {
            throw new VirtualFileSystemException("cannot delete " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void move(String from, String to, boolean overwrite) {
        final Path source = existing(from);
        final Path target = connection.toHost(to);
        try {
            Files.createDirectories(target.getParent());
            if (overwrite) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                moveWithoutReplacing(source, target);
            }
        } catch (java.nio.file.FileAlreadyExistsException | DirectoryNotEmptyException e) {
            throw new FileAlreadyExistsException(to);
        } catch (IOException e) {
            throw new VirtualFileSystemException("cannot move " + from + " to " + to + ": " + e.getMessage(), e);
        }
    }

    /**
     * rename(2) replaces an existing file, and an empty directory, so it cannot refuse a target on its own. A symbolic
     * link is re-created at the target (symlink(2) refuses an existing target) and then removed — link(2) would follow
     * it on macOS and turn it into a hard link to what it points at; a file is linked (link(2) refuses an existing
     * target) and then unlinked; a directory first reserves the target as an empty
     * directory (mkdir(2) refuses an existing one) and is then renamed over that reservation, which fails rather than
     * replaces if anything was put into it meanwhile.
     */
    private static void moveWithoutReplacing(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) {
            Files.createSymbolicLink(target, Files.readSymbolicLink(source));
            Files.delete(source);
            return;
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createLink(target, source);
            Files.delete(source);
            return;
        }
        Files.createDirectory(target);
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // Someone else's content is in the reservation now: it stays theirs.
            }
            throw e;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // already failing
            }
        }
    }

    private Path existing(String path) {
        final Path host = connection.toHost(path);
        if (!Files.exists(host, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException(path);
        }
        return host;
    }

    private static String sha256(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file),
                MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStreamSink.INSTANCE);
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Discards everything. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
            // discarded
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discarded
        }
    }

    /** At most {@code limit} bytes of the delegate. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            final int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            final int n = delegate.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
