package at.aimon.sandbox.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;

@ExtendWith(MockitoExtension.class)
class TarCreatorTest {

    @Mock
    private VirtualFileSystem fileSystem;

    private TarCreator tarCreator;
    private TarExtractor tarExtractor;

    @BeforeEach
    void setUp() {
        tarCreator = new TarCreator();
        tarExtractor = new TarExtractor();
    }

    @Test
    void create_SingleFile_RoundtripWithExtractor() throws IOException {
        String content = "Hello, World!";
        byte[] contentBytes = content.getBytes();
        stubFile("/vfs/hello.txt", contentBytes);

        List<TarCreator.FileEntry> entries = List.of(new TarCreator.FileEntry("/vfs/hello.txt", "hello.txt"));

        byte[] tar = tarCreator.create(fileSystem, entries);

        // Roundtrip: extract back and verify
        VirtualFileSystem extractFs = new InMemoryVfs();
        List<FileArtifact> extracted = tarExtractor.extract(extractFs, "/out", new ByteArrayInputStream(tar));

        assertThat(extracted).hasSize(1);
        assertThat(extracted.get(0).getPath()).isEqualTo("/out/hello.txt");
        assertThat(extracted.get(0).getSize()).isEqualTo(contentBytes.length);
    }

    @Test
    void create_MultipleFiles_AllEntriesPresent() throws IOException {
        stubFile("/vfs/a.txt", "aaa".getBytes());
        stubFile("/vfs/b.txt", "bbb".getBytes());
        stubFile("/vfs/c.txt", "ccc".getBytes());

        List<TarCreator.FileEntry> entries = List.of(new TarCreator.FileEntry("/vfs/a.txt", "a.txt"),
                new TarCreator.FileEntry("/vfs/b.txt", "b.txt"), new TarCreator.FileEntry("/vfs/c.txt", "c.txt"));

        byte[] tar = tarCreator.create(fileSystem, entries);

        VirtualFileSystem extractFs = new InMemoryVfs();
        List<FileArtifact> extracted = tarExtractor.extract(extractFs, "/out", new ByteArrayInputStream(tar));

        assertThat(extracted).hasSize(3);
        assertThat(extracted).extracting(FileArtifact::getFileName).containsExactly("a.txt", "b.txt", "c.txt");
    }

    @Test
    void create_EmptyList_ProducesEndOfArchiveOnly() throws IOException {
        byte[] tar = tarCreator.create(fileSystem, List.of());

        // Should only contain end-of-archive marker (1024 bytes of zeros)
        assertThat(tar).hasSize(1024);

        VirtualFileSystem extractFs = new InMemoryVfs();
        List<FileArtifact> extracted = tarExtractor.extract(extractFs, "/out", new ByteArrayInputStream(tar));
        assertThat(extracted).isEmpty();
    }

    @Test
    void create_MaxFilesExceeded_ThrowsIOException() {
        List<TarCreator.FileEntry> entries = new ArrayList<>();
        for (int i = 0; i <= TarSecurityPolicy.MAX_FILES; i++) {
            entries.add(new TarCreator.FileEntry("/vfs/file" + i + ".txt", "file" + i + ".txt"));
        }

        assertThatThrownBy(() -> tarCreator.create(fileSystem, entries)).isInstanceOf(IOException.class)
                .hasMessageContaining("File count exceeds limit");
    }

    @Test
    void create_MaxFileBytesExceeded_ThrowsIOException() throws IOException {
        long oversize = TarSecurityPolicy.MAX_FILE_BYTES + 1;
        when(fileSystem.getMetadata("/vfs/big.bin")).thenReturn(createMetadata("/vfs/big.bin", oversize));

        List<TarCreator.FileEntry> entries = List.of(new TarCreator.FileEntry("/vfs/big.bin", "big.bin"));

        assertThatThrownBy(() -> tarCreator.create(fileSystem, entries)).isInstanceOf(IOException.class)
                .hasMessageContaining("File exceeds max size");
    }

    @Test
    void create_MaxTotalBytesExceeded_ThrowsIOException() throws IOException {
        // Three files that individually fit under per-file limit but together exceed total limit.
        // Third file triggers total size check before read(), so only stub metadata for it.
        long thirdPlus = TarSecurityPolicy.MAX_TOTAL_BYTES / 3 + 1;
        stubFile("/vfs/a.bin", new byte[(int) thirdPlus]);
        stubFile("/vfs/b.bin", new byte[(int) thirdPlus]);
        when(fileSystem.getMetadata("/vfs/c.bin")).thenReturn(createMetadata("/vfs/c.bin", thirdPlus));

        List<TarCreator.FileEntry> entries = List.of(new TarCreator.FileEntry("/vfs/a.bin", "a.bin"),
                new TarCreator.FileEntry("/vfs/b.bin", "b.bin"), new TarCreator.FileEntry("/vfs/c.bin", "c.bin"));

        assertThatThrownBy(() -> tarCreator.create(fileSystem, entries)).isInstanceOf(IOException.class)
                .hasMessageContaining("Total size would exceed limit");
    }

    @Test
    void create_PathTraversal_ThrowsIOException() {
        List<TarCreator.FileEntry> entries = List.of(new TarCreator.FileEntry("/vfs/file.txt", "../escape.txt"));

        assertThatThrownBy(() -> tarCreator.create(fileSystem, entries)).isInstanceOf(IOException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void create_AbsoluteArchiveName_ThrowsIOException() {
        List<TarCreator.FileEntry> entries = List.of(new TarCreator.FileEntry("/vfs/file.txt", "/etc/passwd"));

        assertThatThrownBy(() -> tarCreator.create(fileSystem, entries)).isInstanceOf(IOException.class)
                .hasMessageContaining("must be relative");
    }

    @Test
    void create_NestedArchiveName_Succeeds() throws IOException {
        String content = "nested content";
        stubFile("/vfs/data.txt", content.getBytes());

        List<TarCreator.FileEntry> entries = List
                .of(new TarCreator.FileEntry("/vfs/data.txt", "subdir/nested/data.txt"));

        byte[] tar = tarCreator.create(fileSystem, entries);

        VirtualFileSystem extractFs = new InMemoryVfs();
        List<FileArtifact> extracted = tarExtractor.extract(extractFs, "/out", new ByteArrayInputStream(tar));

        assertThat(extracted).hasSize(1);
        assertThat(extracted.get(0).getPath()).isEqualTo("/out/subdir/nested/data.txt");
    }

    @Test
    void create_StreamingVariant_RoundtripWithExtractor() throws IOException {
        String content = "Streaming test content";
        byte[] contentBytes = content.getBytes();
        stubFile("/vfs/stream.txt", contentBytes);

        List<TarCreator.FileEntry> entries = List.of(new TarCreator.FileEntry("/vfs/stream.txt", "stream.txt"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tarCreator.create(fileSystem, entries, out);
        byte[] tar = out.toByteArray();

        VirtualFileSystem extractFs = new InMemoryVfs();
        List<FileArtifact> extracted = tarExtractor.extract(extractFs, "/out", new ByteArrayInputStream(tar));

        assertThat(extracted).hasSize(1);
        assertThat(extracted.get(0).getPath()).isEqualTo("/out/stream.txt");
        assertThat(extracted.get(0).getSize()).isEqualTo(contentBytes.length);
    }

    @Test
    void create_StreamingVariant_NullOutputStream_ThrowsNPE() {
        assertThatThrownBy(() -> tarCreator.create(fileSystem, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_NullFileSystem_ThrowsNPE() {
        assertThatThrownBy(() -> tarCreator.create(null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_NullFiles_ThrowsNPE() {
        assertThatThrownBy(() -> tarCreator.create(fileSystem, null)).isInstanceOf(NullPointerException.class);
    }

    private void stubFile(String path, byte[] content) {
        when(fileSystem.getMetadata(path)).thenReturn(createMetadata(path, content.length));
        when(fileSystem.read(path)).thenReturn(new ByteArrayInputStream(content));
    }

    private FileMetadata createMetadata(String path, long size) {
        Instant now = Instant.now();
        return FileMetadata.builder().path(path).size(size).createdAt(now).modifiedAt(now).build();
    }

    /**
     * Simple in-memory VFS for roundtrip testing. Stores written files in memory for verification by TarExtractor.
     */
    private static class InMemoryVfs implements VirtualFileSystem {

        private final java.util.Map<String, byte[]> files = new java.util.HashMap<>();

        @Override
        public void write(String path, java.io.InputStream content, long contentLength) {
            try {
                files.put(path, content.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public java.io.InputStream read(String path) {
            byte[] data = files.get(path);
            if (data == null) {
                throw new at.aimon.core.filesystem.exception.FileNotFoundException("Not found: " + path);
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }

        @Override
        public boolean exists(String path) {
            return files.containsKey(path);
        }

        @Override
        public boolean isDirectory(String path) {
            return false;
        }

        @Override
        public FileMetadata getMetadata(String path) {
            byte[] data = files.get(path);
            if (data == null) {
                throw new at.aimon.core.filesystem.exception.FileNotFoundException("Not found: " + path);
            }
            Instant now = Instant.now();
            return FileMetadata.builder().path(path).size(data.length).createdAt(now).modifiedAt(now).build();
        }

        @Override
        public List<String> list(String directory) {
            return List.of();
        }

        @Override
        public List<String> listRecursive(String directory) {
            return List.of();
        }

        @Override
        public void copy(String sourcePath, String destinationPath, boolean overwrite) {
            // not needed
        }

        @Override
        public void move(String sourcePath, String destinationPath, boolean overwrite) {
            // not needed
        }

        @Override
        public java.io.OutputStream openOutputStream(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream openInputStream(String path) {
            return read(path);
        }

        @Override
        public String getWorkingDirectory() {
            return "/";
        }

        @Override
        public void initialize() {
            // no-op
        }

        @Override
        public at.aimon.core.filesystem.BackendStatus getStatus() {
            return null;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
