package at.aimon.sandbox.provider;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The files API of one sandbox (docs/design/workspace-sandbox.md §6.1). Every path is an <b>absolute</b> path inside
 * the sandbox. Failures are core VFS exceptions — {@code FileNotFoundException}, {@code FileAlreadyExistsException},
 * {@code VirtualFileSystemException} — so the file tools report them as they report any filesystem's; a missing
 * sandbox is {@link SandboxNotFoundException}.
 */
public interface SandboxFiles {

    /**
     * @param path
     *            the file
     * @param offset
     *            the first byte to read
     * @param length
     *            how many bytes to read; negative reads to the end
     * @return the bytes; the caller closes the stream
     */
    InputStream read(String path, long offset, long length);

    /**
     * Writes a file, creating missing parent directories.
     *
     * @param path
     *            the file
     * @param content
     *            the bytes
     * @param length
     *            how many bytes {@code content} holds, or {@code -1} when unknown
     * @param mode
     *            what to do when the file exists
     */
    void write(String path, InputStream content, long length, WriteMode mode);

    /**
     * @param path
     *            the file or directory
     * @return its stat, or empty when it does not exist
     */
    Optional<FileStat> stat(String path);

    /**
     * @param directory
     *            the directory
     * @param recursive
     *            whether to descend
     * @param limit
     *            the most entries to return
     * @return its entries, directories included, with absolute paths
     */
    List<FileStat> list(String directory, boolean recursive, int limit);

    /**
     * Creates a directory and its missing parents; an existing directory is success.
     *
     * @param path
     *            the directory
     */
    void createDirectories(String path);

    /**
     * @param path
     *            the file or directory
     * @param recursive
     *            whether a populated directory may be removed
     */
    void delete(String path, boolean recursive);

    /**
     * @param from
     *            the source
     * @param to
     *            the destination
     * @param overwrite
     *            whether an existing destination is replaced
     */
    void move(String from, String to, boolean overwrite);
}
