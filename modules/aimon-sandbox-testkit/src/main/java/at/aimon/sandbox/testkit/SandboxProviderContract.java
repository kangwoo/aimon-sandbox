package at.aimon.sandbox.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.SharedVolumes;
import at.aimon.sandbox.provider.VolumeInUseException;
import at.aimon.sandbox.provider.VolumeMount;
import at.aimon.sandbox.provider.VolumeRef;
import at.aimon.sandbox.provider.WriteMode;

/**
 * The contract every {@link SandboxProvider} must pass (docs/design/workspace-sandbox.md §16): idempotent create,
 * idempotent destroy, separate stdout/stderr with the exit code, cwd and env, per-stream truncation, a
 * {@link RunningCommand#kill()} that ends only its own process group, the files API with its change-detection
 * contract, complete {@code list(labels)} answers, labels on {@code status}, {@link SandboxNotFoundException} for an
 * absent sandbox, volume deletion when {@link Capability#SHARED_VOLUME} is advertised, and nothing unadvertised.
 *
 * <p>
 * Extend it, implement {@link #createProvider()}, and every test here runs against a fresh provider. Shell state is
 * not part of the SPI and is not tested here.
 */
public abstract class SandboxProviderContract {

    /** The deployment label every contract sandbox carries. */
    protected static final String DEPLOYMENT = "contract";

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private SandboxProvider provider;
    private final List<ProviderSandboxRef> created = new ArrayList<>();

    /** @return a fresh provider for one test; closed after it */
    protected abstract SandboxProvider createProvider();

    /** @return the image contract sandboxes are created from */
    protected String image() {
        return "local";
    }

    /** @return how far ahead new sandboxes expire */
    protected Duration expiry() {
        return Duration.ofHours(1);
    }

    @BeforeEach
    void startProvider() {
        provider = createProvider();
    }

    @AfterEach
    void stopProvider() {
        for (ProviderSandboxRef ref : created) {
            try {
                provider.destroy(ref);
            } catch (RuntimeException e) {
                // already gone
            }
        }
        provider.close();
    }

    /** @return the provider under test */
    protected final SandboxProvider provider() {
        return provider;
    }

    /**
     * @param workspace
     *            the workspace id to label with
     * @param generation
     *            the generation to label with
     * @return a create spec for this contract's deployment
     */
    protected CreateSpec.Builder spec(String workspace, long generation) {
        return CreateSpec.builder().key(SandboxLabels.key(DEPLOYMENT, workspace, "inc00001", "primary", generation))
                .image(image())
                .labels(SandboxLabels.labels(DEPLOYMENT, workspace, "inc00001", "primary", generation, "tenant"))
                .expiresAt(Instant.now().plus(expiry()));
    }

    /**
     * @param spec
     *            what to create
     * @return the created sandbox, destroyed after the test
     */
    protected ProviderSandboxRef create(CreateSpec spec) {
        final ProviderSandboxRef ref = provider.create(spec);
        created.add(ref);
        return ref;
    }

    private ProviderSandboxRef newSandbox() {
        return create(spec("ws:" + UUID.randomUUID(), 1).build());
    }

    private ExecOutcome exec(SandboxConnection connection, String command) throws InterruptedException {
        return connection.run(ExecSpec.builder().command(command).timeout(COMMAND_TIMEOUT).build(), OutputSink.DISCARD)
                .await(COMMAND_TIMEOUT);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void write(SandboxConnection connection, String path, String content) {
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        connection.files().write(path, new ByteArrayInputStream(bytes), bytes.length, WriteMode.CREATE_OR_REPLACE);
    }

    private static String read(SandboxConnection connection, String path) throws IOException {
        try (InputStream in = connection.files().read(path, 0, -1)) {
            return text(in.readAllBytes());
        }
    }

    @Test
    void sameKeyCreatedTwiceInSequenceGivesOneSandbox() {
        final CreateSpec spec = spec("ws:" + UUID.randomUUID(), 1).build();
        final ProviderSandboxRef first = create(spec);
        final ProviderSandboxRef second = create(spec);

        assertThat(second).isEqualTo(first);
        assertThat(provider.list(Map.of(SandboxLabels.SANDBOX_KEY, spec.labels().get(SandboxLabels.SANDBOX_KEY))))
                .hasSize(1);
    }

    @Test
    void destroyIsIdempotent() {
        final ProviderSandboxRef ref = newSandbox();

        provider.destroy(ref);
        provider.destroy(ref);

        assertThat(provider.status(ref)).isEmpty();
    }

    @Test
    void statusReturnsTheLabels() {
        final CreateSpec spec = spec("ws:" + UUID.randomUUID(), 3).build();
        final ProviderSandboxRef ref = create(spec);

        final Optional<ProviderSandbox> status = provider.status(ref);

        assertThat(status).isPresent();
        assertThat(status.get().labels()).containsAllEntriesOf(spec.labels());
    }

    @Test
    void listReturnsEveryMatchingSandbox() {
        final String workspace = "ws:" + UUID.randomUUID();
        for (int generation = 1; generation <= 30; generation++) {
            create(spec(workspace, generation).build());
        }
        final ProviderSandboxRef other = newSandbox();

        final List<ProviderSandbox> listed = provider.list(SandboxLabels.workspaceSelector(DEPLOYMENT, workspace));

        assertThat(listed).hasSize(30).noneMatch(sandbox -> sandbox.ref().equals(other));
    }

    @Test
    void execSeparatesStdoutAndStderrAndReportsTheExitCode() throws InterruptedException {
        final SandboxConnection connection = provider.connect(newSandbox());

        final ExecOutcome outcome = exec(connection, "echo out; echo err >&2; exit 3");

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(text(outcome.stdout())).isEqualTo("out\n");
        assertThat(text(outcome.stderr())).isEqualTo("err\n");
        assertThat(outcome.timedOut()).isFalse();
    }

    @Test
    void execHonoursWorkingDirectoryAndEnvironment() throws InterruptedException {
        final SandboxConnection connection = provider.connect(newSandbox());
        connection.files().createDirectories("/workspace/sub");

        final ExecOutcome outcome = connection.run(
                ExecSpec.builder().command("pwd; echo \"$CONTRACT_VAR\"").workingDirectory("/workspace/sub")
                        .environment(Map.of("CONTRACT_VAR", "value")).timeout(COMMAND_TIMEOUT).build(),
                OutputSink.DISCARD).await(COMMAND_TIMEOUT);

        assertThat(text(outcome.stdout())).isEqualTo("/workspace/sub\nvalue\n");
    }

    @Test
    void execTruncatesStdoutAndStderrSeparately() throws InterruptedException {
        final SandboxConnection connection = provider.connect(newSandbox());

        final ExecOutcome outcome = connection
                .run(ExecSpec.builder().command("printf 'aaaaaaaaaaaaaaaaaaaa'; printf 'bbbbb' >&2").maxCaptureBytes(10)
                        .timeout(COMMAND_TIMEOUT).build(), OutputSink.DISCARD)
                .await(COMMAND_TIMEOUT);

        assertThat(text(outcome.stdout())).isEqualTo("aaaaaaaaaa");
        assertThat(outcome.stdoutTruncated()).isTrue();
        assertThat(text(outcome.stderr())).isEqualTo("bbbbb");
        assertThat(outcome.stderrTruncated()).isFalse();
    }

    @Test
    void awaitPastItsTimeoutKillsTheCommand() throws InterruptedException {
        final SandboxConnection connection = provider.connect(newSandbox());
        final RunningCommand command = connection
                .run(ExecSpec.builder().command("sleep 20").timeout(COMMAND_TIMEOUT).build(), OutputSink.DISCARD);

        final long started = System.nanoTime();
        final ExecOutcome outcome = command.await(Duration.ofMillis(300));

        assertThat(outcome.timedOut()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void killEndsOnlyItsOwnProcessGroup() throws Exception {
        final SandboxConnection connection = provider.connect(newSandbox());
        final RunningCommand victim = connection.run(
                ExecSpec.builder().command("sleep 20 & sleep 20; wait").timeout(COMMAND_TIMEOUT).build(),
                OutputSink.DISCARD);
        final RunningCommand bystander = connection.run(ExecSpec.builder()
                .command("sleep 1; echo alive > /workspace/bystander").timeout(COMMAND_TIMEOUT).build(),
                OutputSink.DISCARD);

        victim.kill();

        final long started = System.nanoTime();
        final ExecOutcome killed = victim.await(Duration.ofSeconds(15));
        assertThat(killed.exitCode()).isNotZero();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        final ExecOutcome survived = bystander.await(Duration.ofSeconds(15));
        assertThat(survived.exitCode()).isZero();
        assertThat(read(connection, "/workspace/bystander")).isEqualTo("alive\n");
    }

    @Test
    void filesReadWriteStatListMoveAndDelete() throws IOException {
        final SandboxConnection connection = provider.connect(newSandbox());

        write(connection, "/workspace/dir/a.txt", "hello");
        assertThat(read(connection, "/workspace/dir/a.txt")).isEqualTo("hello");
        try (InputStream in = connection.files().read("/workspace/dir/a.txt", 1, 3)) {
            assertThat(text(in.readAllBytes())).isEqualTo("ell");
        }
        final FileStat stat = connection.files().stat("/workspace/dir/a.txt").orElseThrow();
        assertThat(stat.size()).isEqualTo(5);
        assertThat(stat.directory()).isFalse();
        assertThat(connection.files().stat("/workspace/dir").orElseThrow().directory()).isTrue();
        assertThat(connection.files().stat("/workspace/missing")).isEmpty();

        connection.files().createDirectories("/workspace/dir/sub");
        write(connection, "/workspace/dir/sub/b.txt", "b");
        assertThat(connection.files().list("/workspace/dir", false, 100)).extracting(FileStat::path)
                .containsExactlyInAnyOrder("/workspace/dir/a.txt", "/workspace/dir/sub");
        assertThat(connection.files().list("/workspace/dir", true, 100)).extracting(FileStat::path)
                .contains("/workspace/dir/sub/b.txt");

        connection.files().move("/workspace/dir/a.txt", "/workspace/moved.txt", false);
        assertThat(connection.files().stat("/workspace/dir/a.txt")).isEmpty();
        assertThat(read(connection, "/workspace/moved.txt")).isEqualTo("hello");
        write(connection, "/workspace/other.txt", "other");
        assertThatThrownBy(() -> connection.files().move("/workspace/moved.txt", "/workspace/other.txt", false))
                .isInstanceOf(FileAlreadyExistsException.class);

        connection.files().delete("/workspace/dir", true);
        assertThat(connection.files().stat("/workspace/dir")).isEmpty();
        assertThatThrownBy(() -> connection.files().read("/workspace/dir/sub/b.txt", 0, -1))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void writeNewRefusesAnExistingFile() {
        final SandboxConnection connection = provider.connect(newSandbox());
        write(connection, "/workspace/a.txt", "a");

        assertThatThrownBy(() -> connection.files().write("/workspace/a.txt", new ByteArrayInputStream(new byte[1]), 1,
                WriteMode.CREATE_NEW)).isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void statDetectsASameSizeRewriteWithinOneSecond() {
        final SandboxConnection connection = provider.connect(newSandbox());
        write(connection, "/workspace/c.txt", "aaaa");
        final FileStat before = connection.files().stat("/workspace/c.txt").orElseThrow();

        write(connection, "/workspace/c.txt", "bbbb");
        final FileStat after = connection.files().stat("/workspace/c.txt").orElseThrow();

        final boolean etagChanged = before.etag().isPresent() && after.etag().isPresent()
                && !before.etag().equals(after.etag());
        assertThat(etagChanged || !before.modifiedAt().equals(after.modifiedAt()))
                .as("a content change must change the etag or the modification time").isTrue();
    }

    @Test
    void absentSandboxAnswersNotFound() {
        final ProviderSandboxRef ref = newSandbox();
        provider.destroy(ref);

        assertThatThrownBy(() -> provider.extendExpiry(ref, Instant.now().plus(expiry())))
                .isInstanceOf(SandboxNotFoundException.class);
        assertThatThrownBy(() -> provider.connect(ref)).isInstanceOf(SandboxNotFoundException.class);
        if (provider.capabilities().supports(Capability.PAUSE_RESUME)) {
            assertThatThrownBy(() -> provider.resume(ref)).isInstanceOf(SandboxNotFoundException.class);
        }
    }

    @Test
    void extendExpiryMovesForwardOnly() {
        assumeTrue(provider.capabilities().supports(Capability.EXPIRY));
        final ProviderSandboxRef ref = newSandbox();
        final Instant later = Instant.now().plus(expiry()).plus(Duration.ofMinutes(10));

        provider.extendExpiry(ref, later);
        provider.extendExpiry(ref, Instant.now().plus(Duration.ofMinutes(1)));

        provider.status(ref).flatMap(ProviderSandbox::expiresAt)
                .ifPresent(expiresAt -> assertThat(expiresAt).isAfterOrEqualTo(later));
    }

    @Test
    void unadvertisedCapabilitiesAreNotOffered() {
        if (!provider.capabilities().supports(Capability.PAUSE_RESUME)) {
            final ProviderSandboxRef ref = newSandbox();
            assertThatThrownBy(() -> provider.pause(ref)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.resume(ref)).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(provider.sharedVolumes().isPresent())
                .isEqualTo(provider.capabilities().supports(Capability.SHARED_VOLUME));
    }

    @Test
    void sharedVolumeDeletionIsIdempotentAndRefusedWhileMounted() {
        assumeTrue(provider.capabilities().supports(Capability.SHARED_VOLUME));
        final SharedVolumes volumes = provider.sharedVolumes().orElseThrow();
        final VolumeRef volume = VolumeRef.of("aimon-contract-" + UUID.randomUUID().toString().substring(0, 8));
        final ProviderSandboxRef ref = create(
                spec("ws:" + UUID.randomUUID(), 1).volumes(List.of(VolumeMount.of(volume, "/shared", false))).build());

        assertThatThrownBy(() -> volumes.delete(volume)).isInstanceOf(VolumeInUseException.class);
        provider.destroy(ref);
        volumes.delete(volume);
        volumes.delete(volume);
    }

    @Test
    void labelsOfOtherDeploymentsAreNotListed() {
        final String workspace = "ws:" + UUID.randomUUID();
        create(spec(workspace, 1).build());
        final Map<String, String> otherDeployment = new HashMap<>(
                SandboxLabels.workspaceSelector(DEPLOYMENT, workspace));
        otherDeployment.put(SandboxLabels.DEPLOYMENT, "another-deployment");

        assertThat(provider.list(otherDeployment)).isEmpty();
    }
}
