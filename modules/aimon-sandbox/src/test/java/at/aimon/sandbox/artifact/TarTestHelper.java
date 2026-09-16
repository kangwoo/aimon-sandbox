package at.aimon.sandbox.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared helper for creating minimal valid tar archives in tests.
 */
public final class TarTestHelper {

    private TarTestHelper() {
    }

    /**
     * Creates a minimal valid tar archive containing a single regular file.
     *
     * @param name
     *            the file name inside the archive
     * @param content
     *            the file content
     * @return the tar archive as a byte array
     */
    public static byte[] createTarWithFile(String name, String content) throws IOException {
        return createTarWithType(name, content, '0');
    }

    /**
     * Creates a minimal valid tar archive with a specified type flag.
     *
     * @param name
     *            the entry name
     * @param content
     *            the entry content
     * @param typeFlag
     *            the tar type flag ('0' = regular file, '2' = symlink, '5' = directory)
     * @return the tar archive as a byte array
     */
    public static byte[] createTarWithType(String name, String content, char typeFlag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);

        // Header block (512 bytes)
        byte[] header = new byte[512];

        // Name (offset 0, 100 bytes)
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));

        // Mode (offset 100, 8 bytes) - "0000644\0"
        System.arraycopy("0000644\0".getBytes(), 0, header, 100, 8);

        // UID (offset 108, 8 bytes) - "0000000\0"
        System.arraycopy("0000000\0".getBytes(), 0, header, 108, 8);

        // GID (offset 116, 8 bytes) - "0000000\0"
        System.arraycopy("0000000\0".getBytes(), 0, header, 116, 8);

        // Size (offset 124, 12 bytes) - octal
        String sizeOctal = String.format("%011o\0", contentBytes.length);
        System.arraycopy(sizeOctal.getBytes(), 0, header, 124, 12);

        // Mtime (offset 136, 12 bytes) - "00000000000\0"
        System.arraycopy("00000000000\0".getBytes(), 0, header, 136, 12);

        // Type flag (offset 156)
        header[156] = (byte) typeFlag;

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
        System.arraycopy(checksumStr.getBytes(), 0, header, 148, 8);

        baos.write(header);

        // Content blocks
        baos.write(contentBytes);

        // Padding to 512-byte boundary
        int remainder = contentBytes.length % 512;
        if (remainder > 0) {
            baos.write(new byte[512 - remainder]);
        }

        // End-of-archive marker (two 512-byte zero blocks)
        baos.write(new byte[1024]);

        return baos.toByteArray();
    }
}
