package at.aimon.sandbox.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellExecutionException;

/**
 * The contract of a workspace sandbox environment's {@link RuntimeFileSystem} — core's framework-only file access, on
 * which core's {@code ExecuteCode} shell engine writes its reply files (docs/design/ptc-shell-provider.md §6.2). Core
 * publishes no contract suite for it, so this is the one a sandbox environment over any provider must pass: atomic
 * create-only writes that refuse anything existing, reads that never follow a link and are bounded, deletes that remove
 * a link rather than its target, and confinement to {@code /workspace}.
 *
 * <p>
 * Extend it and implement {@link #environment()}: a sandbox environment whose {@code runtimeFileSystem()} is present.
 * It may be shared by every test; each test works in a directory of its own under {@code /workspace} and prepares
 * links and modes through the environment's shell, whose output is compared newline-terminated (a provider may deliver
 * it line-normalized). A test that needs a sandbox user other than root is skipped when the shell runs as root.
 */
public abstract class RuntimeFileSystemContract {

    private ExecutionEnvironment environment;
    private RuntimeFileSystem files;
    private String dir;

    /**
     * @return the sandbox environment under test; called before every test
     */
    protected abstract ExecutionEnvironment environment();

    @BeforeEach
    void prepare() throws Exception {
        environment = environment();
        files = environment.runtimeFileSystem().orElseThrow();
        dir = "/workspace/rfs-contract-" + UUID.randomUUID();
        files.createDirectories(dir);
    }

    /** Runs a script in the environment's shell and returns its stdout; a non-zero exit fails the test. */
    protected final String sh(String script) throws ShellExecutionException {
        final ShellCommandResult result = environment.shell().execute(() -> script,
                ExecutionOptions.builder().isolated(true).timeout(Duration.ofSeconds(30)).build());
        assertThat(result.exitCode()).as("exit of %s (stderr %s)", script, result.stderr()).isZero();
        return result.stdout();
    }

    /** @return this test's own directory, created through the runtime file system */
    protected final String dir() {
        return dir;
    }

    /** @return the runtime file system under test */
    protected final RuntimeFileSystem files() {
        return files;
    }

    private static String q(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    @Test
    @DisplayName("1: writeNew creates the whole file and leaves nothing else in the directory")
    void writeNewCreatesTheWholeFile() throws Exception {
        final byte[] content = "a reply\n".getBytes(StandardCharsets.UTF_8);

        files.writeNew(dir + "/1", content);

        assertThat(sh("cat " + q(dir + "/1"))).isEqualTo("a reply\n");
        assertThat(sh("ls -A " + q(dir))).isEqualTo("1\n");
    }

    @Test
    @DisplayName("2: writeNew refuses an existing file, directory, link and dangling link, and changes nothing")
    void writeNewRefusesAnythingExisting() throws Exception {
        sh("cd " + q(dir) + " && printf before > file && mkdir directory && printf target > target"
                + " && ln -s target link && ln -s missing dangling");
        final byte[] forged = "forged".getBytes(StandardCharsets.UTF_8);

        for (String name : List.of("file", "directory", "link", "dangling")) {
            assertThatThrownBy(() -> files.writeNew(dir + "/" + name, forged)).as(name)
                    .isInstanceOf(FileAlreadyExistsException.class);
        }

        assertThat(sh("cd " + q(dir) + " && cat file target && echo && readlink link && readlink dangling"
                + " && [ -d directory ] && [ -z \"$(ls -A directory)\" ] && [ ! -e missing ] && echo ok"))
                .isEqualTo("beforetarget\ntarget\nmissing\nok\n");
    }

    @Test
    @DisplayName("3: of concurrent writeNews to one path exactly one succeeds, and the file holds its bytes")
    void concurrentWriteNewsHaveOneWinner() throws Exception {
        final String path = dir + "/race";
        final int writers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final String content = "writer-" + i;
                final Callable<String> write = () -> {
                    start.await();
                    try {
                        files.writeNew(path, content.getBytes(StandardCharsets.UTF_8));
                        return content;
                    } catch (FileAlreadyExistsException e) {
                        return null;
                    }
                };
                results.add(pool.submit(write));
            }
            start.countDown();
            final List<String> winners = new ArrayList<>();
            for (Future<String> result : results) {
                final String winner = result.get();
                if (winner != null) {
                    winners.add(winner);
                }
            }

            assertThat(winners).hasSize(1);
            assertThat(new String(files.read(path, 64), StandardCharsets.UTF_8)).isEqualTo(winners.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("4: bytes round-trip exactly: NUL, CR, CRLF, invalid UTF-8, no final newline")
    void bytesRoundTrip() throws Exception {
        final byte[] content = {'a', 0, 'b', '\r', 'c', '\r', '\n', (byte) 0xFF, (byte) 0xC3, 'd', (byte) 0x80};

        files.writeNew(dir + "/binary", content);

        assertThat(files.read(dir + "/binary", 1024)).containsExactly(content);
        assertThat(sh("wc -c < " + q(dir + "/binary")).strip()).isEqualTo(String.valueOf(content.length));
    }

    @Test
    @DisplayName("5: read stops at maxBytes, returns all of a smaller file, and nothing for 0")
    void readIsBounded() throws Exception {
        final byte[] content = new byte[100_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        files.writeNew(dir + "/big", content);

        final byte[] head = files.read(dir + "/big", 1000);
        assertThat(head).hasSize(1000);
        assertThat(head).containsExactly(java.util.Arrays.copyOf(content, 1000));
        assertThat(files.read(dir + "/big", 200_000)).containsExactly(content);
        assertThat(files.read(dir + "/big", 0)).isEmpty();
    }

    @Test
    @DisplayName("6: read refuses a link and a directory, and reports nothing there as NoSuchFileException")
    void readRefusesLinksAndNonFiles() throws Exception {
        sh("cd " + q(dir) + " && printf secret > target && ln -s target link && mkdir directory");

        assertThatThrownBy(() -> files.read(dir + "/link", 64)).isInstanceOf(IOException.class)
                .isNotInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> files.read(dir + "/directory", 64)).isInstanceOf(IOException.class)
                .isNotInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> files.read(dir + "/missing", 64)).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> files.read(dir + "/link", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("7: delete removes a file, a tree, a link (not its target), a dangling link; nothing is no error")
    void deleteRemovesWithoutFollowingLinks() throws Exception {
        sh("cd " + q(dir) + " && printf x > file && mkdir -p tree/a/b && printf y > tree/a/b/c && printf t > target"
                + " && ln -s target link && ln -s missing dangling && mkdir kept && printf k > kept/inside"
                + " && ln -s kept dirlink");

        for (String name : List.of("file", "tree", "link", "dangling", "dirlink", "nothing")) {
            files.delete(dir + "/" + name);
        }

        assertThat(sh("cd " + q(dir) + " && ls -A | sort")).isEqualTo("kept\ntarget\n");
        // Ends with a newline: a provider may deliver output line-normalized (ExecOutcome).
        assertThat(sh("cat " + q(dir + "/target") + " " + q(dir + "/kept/inside") + "; echo")).isEqualTo("tk\n");
    }

    @Test
    @DisplayName("7b: read of an unreadable regular file is an IOException, not an empty array")
    void readOfAnUnreadableFileFails() throws Exception {
        assumeFalse("0".equals(sh("id -u").strip()), "the sandbox user is root, which reads any file");
        sh("printf secret > " + q(dir + "/locked") + " && chmod 000 " + q(dir + "/locked"));
        try {
            assertThatThrownBy(() -> files.read(dir + "/locked", 64)).isInstanceOf(IOException.class)
                    .isNotInstanceOf(NoSuchFileException.class);
        } finally {
            sh("chmod 600 " + q(dir + "/locked"));
        }
    }

    @Test
    @DisplayName("7c: read refuses a maxBytes above the implementation's ceiling")
    void readRefusesAHugeMaxBytes() throws Exception {
        files.writeNew(dir + "/small", new byte[]{1});

        assertThatThrownBy(() -> files.read(dir + "/small", Integer.MAX_VALUE)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("8: createDirectories makes parents, accepts an existing directory, refuses a file in the way")
    void createDirectoriesMakesParents() throws Exception {
        files.createDirectories(dir + "/a/b/c");
        files.createDirectories(dir + "/a/b/c");
        files.writeNew(dir + "/a/b/c/file", new byte[]{1});

        assertThat(sh("[ -d " + q(dir + "/a/b/c") + " ] && echo ok")).isEqualTo("ok\n");
        assertThatThrownBy(() -> files.createDirectories(dir + "/a/b/c/file")).isInstanceOf(IOException.class)
                .isNotInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> files.createDirectories(dir + "/a/b/c/file/below")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("9: paths outside /workspace, relative, with . or .. segments or a NUL are refused")
    void refusesPathsItDoesNotServe() {
        for (String path : refusedPaths()) {
            assertThatThrownBy(() -> files.writeNew(path, new byte[]{1})).as("writeNew %s", path)
                    .isInstanceOf(IOException.class).isNotInstanceOf(FileAlreadyExistsException.class);
            assertThatThrownBy(() -> files.read(path, 1)).as("read %s", path).isInstanceOf(IOException.class)
                    .isNotInstanceOf(NoSuchFileException.class);
            assertThatThrownBy(() -> files.delete(path)).as("delete %s", path).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> files.createDirectories(path)).as("createDirectories %s", path)
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> files.delete("/workspace")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> files.writeNew("/workspace", new byte[]{1})).isInstanceOf(IOException.class);
    }

    /** @return paths every operation refuses */
    protected static List<String> refusedPaths() {
        return List.of("relative/x", "/workspace/../etc/x", "/etc/passwd", "/workspace/a\0b", "/workspace/./x",
                "/workspace//x", "/workspace/x/", "/workspacex/y", "");
    }

    @Test
    @DisplayName("10: a file writeNew created is visible to the environment's shell (the engine's probe)")
    void writtenFilesAreVisibleToTheShell() throws Exception {
        files.writeNew(dir + "/probe", new byte[]{'1'});

        assertThat(sh("test -f " + q(dir + "/probe") + " && echo seen")).isEqualTo("seen\n");
        files.delete(dir + "/probe");
        assertThat(sh("test -e " + q(dir + "/probe") + " || echo gone")).isEqualTo("gone\n");
    }
}
