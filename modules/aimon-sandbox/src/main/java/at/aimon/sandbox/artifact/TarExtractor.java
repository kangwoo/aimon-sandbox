package at.aimon.sandbox.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.artifact.ArtifactFileNames;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Extracts tar archives into a {@link VirtualFileSystem} with security constraints.
 *
 * <p>
 * Security policies:
 * <ul>
 * <li>Path traversal prevention (rejects entries with ".." or absolute paths)
 * <li>File type restriction (regular files and directories only, symlinks ignored)
 * <li>Size limits (max files, max total bytes, max per-file bytes)
 * </ul>
 */
public class TarExtractor {

    private static final Logger log = LoggerFactory.getLogger(TarExtractor.class);

    // Tar header field offsets
    private static final int NAME_OFFSET = 0;
    private static final int NAME_LENGTH = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int SIZE_LENGTH = 12;
    private static final int TYPE_OFFSET = 156;

    /**
     * Extracts a tar stream into the virtual file system under the given base path.
     *
     * @param fileSystem
     *            the virtual file system to write files into
     * @param basePath
     *            the base path prefix for all extracted files (e.g. "/sandbox-artifacts/id/runId")
     * @param tarStream
     *            the tar input stream
     * @return list of extracted file artifacts
     * @throws IOException
     *             if extraction fails or security constraints are violated
     */
    public List<FileArtifact> extract(VirtualFileSystem fileSystem, String basePath, InputStream tarStream)
            throws IOException {
        List<FileArtifact> entries = new ArrayList<>();
        long totalBytes = 0;
        byte[] header = new byte[TarSecurityPolicy.TAR_BLOCK_SIZE];

        while (true) {
            int bytesRead = readFully(tarStream, header);
            if (bytesRead < TarSecurityPolicy.TAR_BLOCK_SIZE || isEndOfArchive(header)) {
                break;
            }

            String name = parseString(header, NAME_OFFSET, NAME_LENGTH);
            if (name.isEmpty()) {
                break;
            }

            long size = parseOctal(header, SIZE_OFFSET, SIZE_LENGTH);
            char type = (char) header[TYPE_OFFSET];

            // Calculate blocks to skip/read
            long blocks = (size + TarSecurityPolicy.TAR_BLOCK_SIZE - 1) / TarSecurityPolicy.TAR_BLOCK_SIZE;
            long paddedSize = blocks * TarSecurityPolicy.TAR_BLOCK_SIZE;

            // Security: path traversal check
            if (!isPathSafe(name)) {
                log.warn("Skipping unsafe path: {}", name);
                skipBytes(tarStream, paddedSize);
                continue;
            }

            // Directories: skip (VFS.write creates parent directories automatically)
            if (type == '5') {
                skipBytes(tarStream, paddedSize);
                continue;
            }

            // Only process regular files ('0' or '\0')
            if (type != '0' && type != '\0') {
                log.debug("Skipping non-regular file type '{}': {}", type, name);
                skipBytes(tarStream, paddedSize);
                continue;
            }

            // Security: file count limit
            if (entries.size() >= TarSecurityPolicy.MAX_FILES) {
                log.warn("Max file count reached ({}), skipping remaining entries", TarSecurityPolicy.MAX_FILES);
                skipBytes(tarStream, paddedSize);
                continue;
            }

            // Security: per-file size limit
            if (size > TarSecurityPolicy.MAX_FILE_BYTES) {
                log.warn("File exceeds max size ({} > {}): {}", size, TarSecurityPolicy.MAX_FILE_BYTES, name);
                skipBytes(tarStream, paddedSize);
                continue;
            }

            // Security: total size limit
            if (totalBytes + size > TarSecurityPolicy.MAX_TOTAL_BYTES) {
                log.warn("Total size would exceed limit ({} + {} > {}), skipping: {}", totalBytes, size,
                        TarSecurityPolicy.MAX_TOTAL_BYTES, name);
                skipBytes(tarStream, paddedSize);
                continue;
            }

            // Build VFS path and validate it stays within basePath
            String vfsPath = basePath + "/" + name;
            if (!isWithinBase(vfsPath, basePath)) {
                log.warn("Path escapes base directory: {}", name);
                skipBytes(tarStream, paddedSize);
                continue;
            }

            // Write file to VFS using BoundedInputStream to read exactly 'size' bytes
            BoundedInputStream bounded = new BoundedInputStream(tarStream, size);
            fileSystem.write(vfsPath, bounded, size);
            bounded.drain(); // ensure tar stream position advances past file content

            // Skip padding after file content
            long padding = paddedSize - size;
            if (padding > 0) {
                skipBytes(tarStream, padding);
            }

            totalBytes += size;
            entries.add(FileArtifact.builder().path(vfsPath).size(size)
                    .fileName(ArtifactFileNames.extractFileName(name)).build());
        }

        return entries;
    }

    private boolean isPathSafe(String name) {
        if (name.contains("..")) {
            return false;
        }
        if (name.startsWith("/")) {
            return false;
        }
        // Windows drive letters
        if (name.length() >= 2 && name.charAt(1) == ':') {
            return false;
        }
        return true;
    }

    private boolean isWithinBase(String path, String basePath) {
        // Normalize by removing trailing slashes for consistent prefix comparison
        String normalizedBase = normalizePath(basePath);
        String normalizedPath = normalizePath(path);
        return normalizedPath.startsWith(normalizedBase + "/") || normalizedPath.equals(normalizedBase);
    }

    private String normalizePath(String path) {
        // Remove trailing slashes
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private boolean isEndOfArchive(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private String parseString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset).trim();
    }

    private long parseOctal(byte[] header, int offset, int length) {
        String octal = parseString(header, offset, length).trim();
        if (octal.isEmpty()) {
            return 0;
        }
        return Long.parseLong(octal, 8);
    }

    private int readFully(InputStream in, byte[] buffer) throws IOException {
        int totalRead = 0;
        while (totalRead < buffer.length) {
            int read = in.read(buffer, totalRead, buffer.length - totalRead);
            if (read < 0) {
                break;
            }
            totalRead += read;
        }
        return totalRead;
    }

    private void skipBytes(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read < 0) {
                break;
            }
            remaining -= read;
        }
    }

    /**
     * An InputStream wrapper that reads exactly {@code limit} bytes from the underlying stream. Does not close the
     * underlying stream when closed.
     */
    private static class BoundedInputStream extends InputStream {

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
            int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int read = delegate.read(buf, off, toRead);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        /**
         * Consumes any remaining bytes that were not read by the VFS write operation.
         */
        void drain() throws IOException {
            byte[] buf = new byte[8192];
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = delegate.read(buf, 0, toRead);
                if (read < 0) {
                    break;
                }
                remaining -= read;
            }
        }

        @Override
        public void close() {
            // Do not close the underlying tar stream
        }
    }
}
