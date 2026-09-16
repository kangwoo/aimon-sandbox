package at.aimon.sandbox.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.filesystem.VirtualFileSystem;

@ExtendWith(MockitoExtension.class)
class TarExtractorTest {

    private static final String BASE_PATH = "/test";

    @Mock
    private VirtualFileSystem fileSystem;

    private TarExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TarExtractor();
    }

    @Test
    void extract_EmptyArchive_ReturnsEmptyList() throws IOException {
        byte[] emptyTar = new byte[1024]; // Two empty blocks
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(emptyTar));
        assertThat(entries).isEmpty();
    }

    @Test
    void extract_SingleFile_ExtractsCorrectly() throws IOException {
        byte[] tar = TarTestHelper.createTarWithFile("hello.txt", "Hello, World!");
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(tar));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getPath()).isEqualTo("/test/hello.txt");
        assertThat(entries.get(0).getSize()).isEqualTo(13);
        assertThat(entries.get(0).getFileName()).isEqualTo("hello.txt");

        verify(fileSystem).write(eq("/test/hello.txt"), any(InputStream.class), eq(13L));
    }

    @Test
    void extract_PathTraversal_Skipped() throws IOException {
        byte[] tar = TarTestHelper.createTarWithFile("../escape.txt", "malicious");
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(tar));

        assertThat(entries).isEmpty();
        verify(fileSystem, never()).write(any(), any(InputStream.class), anyLong());
    }

    @Test
    void extract_AbsolutePath_Skipped() throws IOException {
        byte[] tar = TarTestHelper.createTarWithFile("/etc/passwd", "root:x:0:0");
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(tar));

        assertThat(entries).isEmpty();
        verify(fileSystem, never()).write(any(), any(InputStream.class), anyLong());
    }

    @Test
    void extract_NestedFile_CreatesCorrectPath() throws IOException {
        byte[] tar = TarTestHelper.createTarWithFile("subdir/nested.txt", "nested content");
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(tar));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getPath()).isEqualTo("/test/subdir/nested.txt");
        assertThat(entries.get(0).getFileName()).isEqualTo("nested.txt");

        verify(fileSystem).write(eq("/test/subdir/nested.txt"), any(InputStream.class), eq(14L));
    }

    @Test
    void extract_LargeFile_ReportsCorrectSize() throws IOException {
        // Create a 64KB file to verify streaming works for larger content
        int size = 64 * 1024;
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        String largeContent = sb.toString();

        byte[] tar = TarTestHelper.createTarWithFile("large.bin", largeContent);
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(tar));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSize()).isEqualTo(size);

        verify(fileSystem).write(eq("/test/large.bin"), any(InputStream.class), eq((long) size));
    }

    @Test
    void extract_Symlink_SkippedSilently() throws IOException {
        byte[] tar = TarTestHelper.createTarWithType("link.txt", "target", '2');
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(tar));

        assertThat(entries).isEmpty();
        verify(fileSystem, never()).write(any(), any(InputStream.class), anyLong());
    }

    @Test
    void extract_Directory_SkippedSilently() throws IOException {
        byte[] tar = TarTestHelper.createTarWithType("mydir/", "", '5');
        List<FileArtifact> entries = extractor.extract(fileSystem, BASE_PATH, new ByteArrayInputStream(tar));

        assertThat(entries).isEmpty();
        verify(fileSystem, never()).write(any(), any(InputStream.class), anyLong());
    }
}
