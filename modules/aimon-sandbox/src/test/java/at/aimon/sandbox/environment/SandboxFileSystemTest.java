package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.SandboxHarness;

/** {@link SandboxFileSystem}: root-relative paths, the read-only areas (§16 row 17), and the VFS contract. */
class SandboxFileSystemTest {

    private final SandboxHarness harness = SandboxHarness.standard();
    private SessionId session;
    private ExecutionEnvironment env;
    private VirtualFileSystem fs;

    @BeforeEach
    void setUp() {
        session = SessionId.generate();
        env = harness.mainTurn(session, ALICE);
        fs = env.fileSystem();
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    @DisplayName("§16: Write into .aimon-staged / .aimon-shell is Access denied; Bash can write there")
    void stagingAndShellAreasAreReadOnlyToFileToolsButShellCanWrite() throws Exception {
        assertThatThrownBy(() -> fs.write("/workspace/.aimon-staged/skill/key/run.sh", "echo planted"))
                .isInstanceOf(FileAccessDeniedException.class).hasMessageContaining("Access denied");
        assertThatThrownBy(() -> fs.write("/workspace/.aimon-shell/x/state", "export A=1"))
                .isInstanceOf(FileAccessDeniedException.class).hasMessageContaining("Access denied");
        assertThatThrownBy(() -> fs.write("../.aimon-staged/sneaky", "x"))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.deleteRecursive("/workspace")).isInstanceOf(FileAccessDeniedException.class);

        assertThat(bash(env,
                "mkdir -p /workspace/.aimon-staged/manual && echo ok > "
                        + "/workspace/.aimon-staged/manual/f && cat /workspace/.aimon-staged/manual/f")
                .stdout()).isEqualTo("ok\n");
        assertThat(fs.exists("/workspace/.aimon-staged/manual/f")).isTrue();
        try (InputStream in = fs.read("/workspace/.aimon-staged/manual/f")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("ok\n");
        }
    }

    @Test
    void relativePathsResolveAgainstTheRootNotTheShellCwd() throws Exception {
        bash(env, "mkdir -p /workspace/repo/src && cd src");

        fs.write("Foo.java", "root");

        assertThat(harness.hostFile(session, "/workspace/repo/Foo.java")).isEqualTo("root");
        assertThat(fs.getWorkingDirectory()).isEqualTo("/workspace/repo");
        assertThat(env.shell().getWorkingDirectory()).isEqualTo("/workspace/repo");
    }

    @Test
    void listingsAreRelativeUnderTheRootAndAbsoluteOutsideIt() {
        fs.write("dir/a.txt", "a");
        fs.write("dir/sub/b.txt", "b");
        fs.write("/workspace/outside.txt", "o");

        assertThat(fs.list("dir")).containsExactlyInAnyOrder("dir/a.txt", "dir/sub");
        assertThat(fs.listRecursive("dir")).containsExactlyInAnyOrder("dir/a.txt", "dir/sub/b.txt");
        assertThat(fs.list("/workspace")).contains("/workspace/outside.txt", "/workspace/.aimon-staged");
        assertThat(fs.search("dir", "*.txt", 10)).containsExactlyInAnyOrder("dir/a.txt", "dir/sub/b.txt");
    }

    @Test
    void metadataCarriesAnEtagThatChangesWithContent() {
        fs.write("m.txt", "aaaa");
        final FileMetadata before = fs.getMetadata("m.txt");
        fs.write("m.txt", "bbbb");
        final FileMetadata after = fs.getMetadata("m.txt");

        assertThat(before.getPath()).isEqualTo("m.txt");
        assertThat(before.getSize()).isEqualTo(4);
        assertThat(before.getEtag()).isPresent();
        assertThat(after.getEtag()).isNotEqualTo(before.getEtag());
        assertThat(fs.getMetadata("/workspace/repo").isDirectory()).isTrue();
    }

    @Test
    void vfsContractEdges() throws Exception {
        fs.createDirectory("empty");
        assertThat(fs.isDirectory("empty")).isTrue();
        fs.delete("empty");
        assertThat(fs.exists("empty")).isFalse();

        fs.write("full/x.txt", "x");
        assertThatThrownBy(() -> fs.delete("full")).isInstanceOf(VirtualFileSystemException.class);
        assertThatThrownBy(() -> fs.list("missing")).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> fs.list("full/x.txt")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.read("missing.txt")).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> fs.createDirectory("full/x.txt")).isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> fs.write("full", "x")).isInstanceOf(InvalidPathException.class);

        fs.copy("full/x.txt", "copy.txt", false);
        assertThatThrownBy(() -> fs.copy("full/x.txt", "copy.txt", false))
                .isInstanceOf(FileAlreadyExistsException.class);
        fs.move("copy.txt", "moved.txt", false);
        assertThat(fs.exists("copy.txt")).isFalse();
        try (OutputStream out = fs.openOutputStream("streamed.txt")) {
            out.write("streamed".getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = fs.openInputStream("streamed.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("streamed");
        }
        fs.deleteRecursive("full");
        assertThat(fs.exists("full")).isFalse();
        assertThatThrownBy(() -> fs.deleteRecursive("full")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void theEnvironmentIsNotDurableAndHasNoIsolation() {
        assertThat(env.durable()).isFalse();
        assertThat(env.isolate("branch_1")).isEmpty();
        assertThat(env.contentSearch()).isPresent();
    }
}
