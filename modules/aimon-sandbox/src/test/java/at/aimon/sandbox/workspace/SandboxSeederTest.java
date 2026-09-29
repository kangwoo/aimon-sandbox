package at.aimon.sandbox.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxProviderException;
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
}
