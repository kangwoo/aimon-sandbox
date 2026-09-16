package at.aimon.sandbox.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Creates tar archives from {@link VirtualFileSystem} files.
 *
 * <p>
 * Symmetric counterpart of {@link TarExtractor}: while TarExtractor reads tar → VFS, TarCreator reads VFS → tar.
 *
 * <p>
 * Security policies (same limits as TarExtractor):
 * <ul>
 * <li>Max files per archive: 1000
 * <li>Max total bytes: 100 MB
 * <li>Max per-file bytes: 50 MB
 * <li>Path traversal prevention (rejects entries with ".." or absolute paths)
 * </ul>
 */
public class TarCreator {

    private static final Logger log = LoggerFactory.getLogger(TarCreator.class);

    /**
     * Creates a tar archive containing the specified files from the virtual file system.
     *
     * @param fileSystem
     *            the virtual file system to read files from (not null)
     * @param files
     *            the list of file entries to include (not null)
     * @return the tar archive as a byte array
     * @throws IOException
     *             if reading from VFS fails or security constraints are violated
     */
    public byte[] create(VirtualFileSystem fileSystem, List<FileEntry> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        create(fileSystem, files, out);
        return out.toByteArray();
    }

    /**
     * Creates a tar archive and writes it to the given output stream.
     *
     * <p>
     * This streaming variant avoids buffering the entire archive in memory, which is useful for large archives that are
     * sent directly to a network destination.
     *
     * @param fileSystem
     *            the virtual file system to read files from (not null)
     * @param files
     *            the list of file entries to include (not null)
     * @param out
     *            the output stream to write the tar archive to (not null, not closed by this method)
     * @throws IOException
     *             if reading from VFS fails, writing to the output stream fails, or security constraints are violated
     */
    public void create(VirtualFileSystem fileSystem, List<FileEntry> files, OutputStream out) throws IOException {
        Objects.requireNonNull(fileSystem, "FileSystem cannot be null");
        Objects.requireNonNull(files, "Files cannot be null");
        Objects.requireNonNull(out, "OutputStream cannot be null");

        if (files.size() > TarSecurityPolicy.MAX_FILES) {
            throw new IOException("File count exceeds limit: " + files.size() + " > " + TarSecurityPolicy.MAX_FILES);
        }

        long totalBytes = 0;

        for (FileEntry entry : files) {
            validateArchiveName(entry.getArchiveName());

            FileMetadata metadata = fileSystem.getMetadata(entry.getSourcePath());
            long size = metadata.getSize();

            if (size > TarSecurityPolicy.MAX_FILE_BYTES) {
                throw new IOException("File exceeds max size (" + size + " > " + TarSecurityPolicy.MAX_FILE_BYTES
                        + "): " + entry.getSourcePath());
            }

            if (totalBytes + size > TarSecurityPolicy.MAX_TOTAL_BYTES) {
                throw new IOException("Total size would exceed limit (" + totalBytes + " + " + size + " > "
                        + TarSecurityPolicy.MAX_TOTAL_BYTES + "): " + entry.getSourcePath());
            }

            // Write tar header
            byte[] header = createHeader(entry.getArchiveName(), size);
            out.write(header);

            // Write file content
            try (InputStream in = fileSystem.read(entry.getSourcePath())) {
                byte[] buf = new byte[8192];
                long remaining = size;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int read = in.read(buf, 0, toRead);
                    if (read < 0) {
                        break;
                    }
                    out.write(buf, 0, read);
                    remaining -= read;
                }
            }

            // Pad to 512-byte boundary
            int remainder = (int) (size % TarSecurityPolicy.TAR_BLOCK_SIZE);
            if (remainder > 0) {
                out.write(new byte[TarSecurityPolicy.TAR_BLOCK_SIZE - remainder]);
            }

            totalBytes += size;
            log.debug("Added to tar: {} ({} bytes)", entry.getArchiveName(), size);
        }

        // End-of-archive marker (two 512-byte zero blocks)
        out.write(new byte[TarSecurityPolicy.TAR_BLOCK_SIZE * 2]);
    }

    private void validateArchiveName(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Archive name cannot be null or empty");
        }
        if (name.contains("..")) {
            throw new IOException("Archive name contains path traversal: " + name);
        }
        if (name.startsWith("/")) {
            throw new IOException("Archive name must be relative: " + name);
        }
        // Windows drive letters
        if (name.length() >= 2 && name.charAt(1) == ':') {
            throw new IOException("Archive name contains drive letter: " + name);
        }
    }

    private byte[] createHeader(String name, long size) {
        byte[] header = new byte[TarSecurityPolicy.TAR_BLOCK_SIZE];

        // Name (offset 0, 100 bytes)
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));

        // Mode (offset 100, 8 bytes) - "0000644\0"
        System.arraycopy("0000644\0".getBytes(StandardCharsets.US_ASCII), 0, header, 100, 8);

        // UID (offset 108, 8 bytes) - "0000000\0"
        System.arraycopy("0000000\0".getBytes(StandardCharsets.US_ASCII), 0, header, 108, 8);

        // GID (offset 116, 8 bytes) - "0000000\0"
        System.arraycopy("0000000\0".getBytes(StandardCharsets.US_ASCII), 0, header, 116, 8);

        // Size (offset 124, 12 bytes) - octal
        String sizeOctal = String.format("%011o\0", size);
        System.arraycopy(sizeOctal.getBytes(StandardCharsets.US_ASCII), 0, header, 124, 12);

        // Mtime (offset 136, 12 bytes) - "00000000000\0"
        System.arraycopy("00000000000\0".getBytes(StandardCharsets.US_ASCII), 0, header, 136, 12);

        // Type flag (offset 156) - '0' for regular file
        header[156] = '0';

        // Checksum (offset 148, 8 bytes) - initially spaces for calculation
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }

        // Calculate checksum
        int checksum = 0;
        for (byte b : header) {
            checksum += (b & 0xFF);
        }
        String checksumStr = String.format("%06o\0 ", checksum);
        System.arraycopy(checksumStr.getBytes(StandardCharsets.US_ASCII), 0, header, 148, 8);

        return header;
    }

    /**
     * Represents a file entry to include in the tar archive.
     */
    public static final class FileEntry {

        private final String sourcePath;
        private final String archiveName;

        /**
         * Creates a new FileEntry.
         *
         * @param sourcePath
         *            the VFS path of the source file (not null)
         * @param archiveName
         *            the relative path inside the tar archive (not null)
         */
        public FileEntry(String sourcePath, String archiveName) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "Source path cannot be null");
            this.archiveName = Objects.requireNonNull(archiveName, "Archive name cannot be null");
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public String getArchiveName() {
            return archiveName;
        }
    }
}
