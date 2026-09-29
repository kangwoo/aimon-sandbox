package at.aimon.sandbox.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.WriteMode;

/** What the local provider does beyond the contract: path translation, lazy expiry, host shims. */
class LocalProcessSandboxProviderTest {

    @TempDir
    Path base;

    private final ManualClock clock = new ManualClock();
    private LocalProcessSandboxProvider provider;

    @AfterEach
    void close() {
        if (provider != null) {
            provider.close();
        }
    }

    private LocalProcessSandboxProvider provider() {
        provider = LocalProcessSandboxProvider.builder().baseDirectory(base).clock(clock)
                .maxExpiry(Duration.ofHours(24)).build();
        return provider;
    }

    private ProviderSandboxRef create(LocalProcessSandboxProvider p) {
        return p.create(CreateSpec.builder().key("k").image("local").environment(Map.of("PROFILE_VAR", "p"))
                .expiresAt(clock.instant().plus(Duration.ofHours(1))).build());
    }

    private static String run(SandboxConnection connection, String command) throws InterruptedException {
        final ExecOutcome outcome = connection.run(ExecSpec.builder().command(command).build(), OutputSink.DISCARD)
                .await(Duration.ofSeconds(20));
        return new String(outcome.stdout(), StandardCharsets.UTF_8);
    }

    @Test
    void workspacePathsAreTranslatedBothWays() throws Exception {
        final LocalProcessSandboxProvider p = provider();
        final ProviderSandboxRef ref = create(p);
        final SandboxConnection connection = p.connect(ref);

        assertThat(run(connection,
                "echo hi > /workspace/f; cat /workspace/f; cd /workspace && pwd; " + "echo $PROFILE_VAR"))
                .isEqualTo("hi\n/workspace\np\n");
        assertThat(Files.readString(p.hostRoot(ref).resolve("workspace/f"))).isEqualTo("hi\n");
        assertThat(p.hostRoot(ref)).startsWith(p.baseDirectory());
        assertThat(run(connection, "printf x | /usr/bin/sha256sum"))
                .startsWith("2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881");
    }

    @Test
    void theFilesApiCannotEscapeTheSandbox() {
        final LocalProcessSandboxProvider p = provider();
        final SandboxConnection connection = p.connect(create(p));

        assertThatThrownBy(() -> connection.files().stat("/workspace/../../../etc/passwd"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> connection.files().stat("relative")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void aPlantedSymbolicLinkCannotLeadTheFilesApiOutOfTheSandbox() throws Exception {
        final LocalProcessSandboxProvider p = provider();
        final SandboxConnection connection = p.connect(create(p));
        run(connection, "ln -s /etc /workspace/escape");

        assertThatThrownBy(() -> connection.files().stat("/workspace/escape/passwd"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> connection.files().delete("/workspace/escape", true))
                .isInstanceOf(InvalidPathException.class);
    }

    @Test
    void theFilesApiReportsItsErrorsAsVfsExceptions() {
        final LocalProcessSandboxProvider p = provider();
        final SandboxConnection connection = p.connect(create(p));
        final SandboxFiles files = connection.files();
        files.write("/workspace/new.txt", new ByteArrayInputStream(new byte[]{1, 2}), 2, WriteMode.CREATE_NEW);
        files.write("/workspace/dir/a.txt", new ByteArrayInputStream(new byte[]{1}), 1, WriteMode.CREATE_OR_REPLACE);

        assertThat(files.stat("/workspace/new.txt")).hasValueSatisfying(stat -> assertThat(stat.size()).isEqualTo(2));
        assertThatThrownBy(
                () -> files.write("/workspace/new.txt", new ByteArrayInputStream(new byte[1]), 1, WriteMode.CREATE_NEW))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> files.write("/workspace/dir", new ByteArrayInputStream(new byte[1]), 1,
                WriteMode.CREATE_OR_REPLACE)).isInstanceOf(VirtualFileSystemException.class);
        assertThatThrownBy(() -> files.read("/workspace/dir", 0, -1)).isInstanceOf(VirtualFileSystemException.class);
        assertThatThrownBy(() -> files.list("/workspace/new.txt", false, 10))
                .isInstanceOf(VirtualFileSystemException.class);
        assertThatThrownBy(() -> files.delete("/workspace/dir", false)).isInstanceOf(VirtualFileSystemException.class);
        assertThatThrownBy(() -> files.move("/workspace/missing", "/workspace/x", true))
                .isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> files.createDirectories("/workspace/new.txt"))
                .isInstanceOf(FileAlreadyExistsException.class);
        files.move("/workspace/new.txt", "/workspace/dir/a.txt", true);
        assertThat(files.stat("/workspace/new.txt")).isEmpty();
        assertThat(files.stat("/workspace/dir/a.txt")).hasValueSatisfying(stat -> assertThat(stat.size()).isEqualTo(2));
    }

    @Test
    void expiryIsEnforcedLazilyAgainstTheClock() {
        final LocalProcessSandboxProvider p = provider();
        final ProviderSandboxRef ref = create(p);
        p.extendExpiry(ref, clock.instant().plus(Duration.ofHours(2)));
        assertThat(p.expiryOf(ref)).contains(clock.instant().plus(Duration.ofHours(2)));

        clock.advance(Duration.ofHours(2));

        assertThat(p.status(ref)).isEmpty();
        assertThatThrownBy(() -> p.connect(ref)).isInstanceOf(SandboxNotFoundException.class);
        assertThat(p.capabilities().maxExpiry()).contains(Duration.ofHours(24));
    }

    @Test
    void theShimsCoverFlockAndRipgrepOnHostsWithoutThem() throws Exception {
        final LocalProcessSandboxProvider p = provider();
        final SandboxConnection connection = p.connect(create(p));

        assertThat(run(connection, "command -v flock rg sha256sum | wc -l | tr -d ' '")).isEqualTo("3\n");
        assertThat(run(connection, "exec 9>/workspace/l; flock -w 1 9 && echo locked")).isEqualTo("locked\n");
        if (!LocalProcessSandboxProvider.hostHasRipgrep()) {
            final ExecOutcome rg = connection.run(ExecSpec.builder().command("rg x .").build(), OutputSink.DISCARD)
                    .await(Duration.ofSeconds(10));
            assertThat(rg.exitCode()).isEqualTo(2);
            assertThat(new String(rg.stderr(), StandardCharsets.UTF_8)).contains("ripgrep is not installed");
        }
    }

    @Test
    void theFlockShimExcludesASecondHolder() throws Exception {
        final LocalProcessSandboxProvider p = provider();
        final ProviderSandboxRef ref = create(p);
        final SandboxConnection connection = p.connect(ref);
        final var holder = connection.run(ExecSpec.builder()
                .command("exec 9>/workspace/lock; flock -w 5 9; : > /workspace/held; sleep 2").build(),
                OutputSink.DISCARD);
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(p.hostRoot(ref).resolve("workspace/held")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(run(connection, "exec 9>/workspace/lock; flock -n 9 && echo got || echo busy")).isEqualTo("busy\n");
        holder.await(Duration.ofSeconds(10));
        assertThat(run(connection, "exec 9>/workspace/lock; flock -n 9 && echo got || echo busy")).isEqualTo("got\n");
    }

    @Test
    void closeKeepsAGivenBaseDirectory() {
        final LocalProcessSandboxProvider p = provider();
        create(p);

        p.close();
        provider = null;

        assertThat(base).exists();
    }
}
