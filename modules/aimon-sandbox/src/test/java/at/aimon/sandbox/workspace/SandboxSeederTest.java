package at.aimon.sandbox.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/** {@link SandboxSeeder}: which outcomes are permanent failures and which are transient. */
class SandboxSeederTest {

    private final SandboxSeeder seeder = new SandboxSeeder(Duration.ofSeconds(30));
    private final SandboxProfile profile = SandboxTestProfiles.local("standard").build();

    /** A connection whose one command ends as given. */
    private static SandboxConnection answering(int exitCode, String stdout, String stderr) {
        return new SandboxConnection() {
            @Override
            public RunningCommand run(ExecSpec spec, OutputSink sink) {
                return new RunningCommand() {
                    @Override
                    public ExecOutcome await(Duration timeout) {
                        return ExecOutcome.builder().exitCode(exitCode).stdout(stdout.getBytes(StandardCharsets.UTF_8))
                                .stderr(stderr.getBytes(StandardCharsets.UTF_8)).build();
                    }

                    @Override
                    public void kill() {
                        // already ended
                    }
                };
            }

            @Override
            public SandboxFiles files() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // nothing held
            }
        };
    }

    @Test
    void seedLockContentionIsTransientNotAPermanentFailure() {
        // Another seed of the same sandbox is still running: the next call retries, the profile is not at fault.
        assertThat(seeder.script(profile, "/workspace/repo"))
                .contains("flock -w 30 8 || { echo 'another seed of this sandbox did not finish' >&2; exit 75; }")
                .doesNotContain("fail seed-lock");

        assertThatThrownBy(() -> seeder.seed(answering(75, "", "another seed of this sandbox did not finish"), profile,
                "/workspace/repo"))
                .isInstanceOfSatisfying(SandboxProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT))
                .hasMessageContaining("another seed");
    }

    @Test
    void aFailedCheckIsAPermanentFailureWithItsStep() {
        final Optional<SandboxSeeder.Failure> failure = seeder.seed(
                answering(SandboxSeeder.FAIL_EXIT, "AIMON_SEED_FAIL root the sandbox runs as root\n", ""), profile,
                "/workspace/repo");

        assertThat(failure).hasValueSatisfying(f -> {
            assertThat(f.step()).isEqualTo("root");
            assertThat(f.reason()).isEqualTo("the sandbox runs as root");
        });
    }

    /** A host change to a fresh sandbox's {@code /workspace} before it is seeded. */
    private interface Prepare {
        void apply(Path workspace) throws Exception;
    }

    /** Seeds a fresh local sandbox after {@code prepare} changed its host {@code /workspace}. */
    private Optional<SandboxSeeder.Failure> seedLocal(Prepare prepare) throws Exception {
        try (LocalProcessSandboxProvider local = LocalProcessSandboxProvider.builder().build()) {
            final ProviderSandboxRef ref = local.create(
                    CreateSpec.builder().key("seed").image("local").expiresAt(Instant.now().plusSeconds(3600)).build());
            final Path workspace = local.hostRoot(ref).resolve("workspace");
            prepare.apply(workspace);
            try (SandboxConnection connection = local.connect(ref)) {
                return seeder.seed(connection, profile, "/workspace/repo");
            } finally {
                if (Files.exists(workspace)) {
                    Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            }
        }
    }

    @Test
    @DisplayName("§13.3: a /workspace that is not writable is a permanent failure, not lock contention")
    void aWorkspaceThatIsNotWritableIsAPermanentFailure() throws Exception {
        final Optional<SandboxSeeder.Failure> failure = seedLocal(
                workspace -> Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("r-xr-xr-x")));

        assertThat(failure).hasValueSatisfying(f -> {
            assertThat(f.step()).isEqualTo("workspace");
            assertThat(f.reason()).isEqualTo("/workspace is missing or not writable");
        });
    }

    @Test
    @DisplayName("§13.3: a missing /workspace reaches the seed's own check and is a permanent failure")
    void aMissingWorkspaceIsAPermanentFailureNotAProviderError() throws Exception {
        // The local provider refuses a missing working directory, as a real exec server may: the seed must not run
        // in /workspace, or this would be a transient provider error.
        final Optional<SandboxSeeder.Failure> failure = seedLocal(Files::delete);

        assertThat(failure).hasValueSatisfying(f -> {
            assertThat(f.step()).isEqualTo("workspace");
            assertThat(f.reason()).isEqualTo("/workspace is missing or not writable");
        });
    }

    @Test
    @DisplayName("§13.3: a seed lock that cannot be opened is a permanent failure, not lock contention")
    void aSeedLockThatCannotBeOpenedIsAPermanentFailure() throws Exception {
        final Optional<SandboxSeeder.Failure> failure = seedLocal(
                workspace -> Files.createDirectory(workspace.resolve(".aimon-seed.lock")));

        assertThat(failure).hasValueSatisfying(f -> {
            assertThat(f.step()).isEqualTo("workspace");
            assertThat(f.reason()).isEqualTo("cannot open /workspace/.aimon-seed.lock");
        });
    }

    @Test
    void theWorkspaceIsCheckedBeforeTheLockIsTaken() {
        final String script = seeder.script(profile, "/workspace/repo");

        assertThat(script).contains(
                "[ -d /workspace ] && [ -w /workspace ] || fail workspace '/workspace is missing or not writable'\n")
                .contains("exec 8>/workspace/.aimon-seed.lock || fail workspace");
        assertThat(script.indexOf("[ -w /workspace ]")).isLessThan(script.indexOf("exec 8>"));
    }
}
