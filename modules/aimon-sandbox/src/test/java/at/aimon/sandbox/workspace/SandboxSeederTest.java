package at.aimon.sandbox.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.HostPort;
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
    @DisplayName("§13.3: the image contract names base64, which a hook's inline run files are decoded with")
    void theImageContractNamesBase64() {
        assertThat(seeder.script(profile, "/workspace/repo"))
                .contains("for t in git rg flock sha256sum base64; do command -v \"$t\"")
                .contains("it must provide git, rg, flock, sha256sum and base64");
    }

    @Test
    @DisplayName("§13.3: the image contract wants sha256sum and base64 executable in /usr/bin, where hooks call them")
    void theImageContractChecksTheAbsolutePaths() {
        assertThat(seeder.script(profile, "/workspace/repo"))
                .contains("for t in /usr/bin/sha256sum /usr/bin/base64; do [ -x \"$t\" ]")
                .contains("sha256sum and base64 must be in /usr/bin");
    }

    @Test
    @DisplayName("§13.3: a sha256sum that refuses -c --strict - (BusyBox) fails the image contract; GNU's passes")
    void aSha256sumWithoutStrictFailsTheImageContract(@TempDir Path dir) throws Exception {
        final String script = seeder.script(profile, "/workspace/repo");
        final String failLine = script.lines().filter(l -> l.startsWith("fail() ")).findFirst().orElseThrow();
        final String probe = script.lines().filter(l -> l.contains("-c --strict -")).findFirst().orElseThrow();
        // BusyBox's sha256sum knows -c but not --strict: it prints usage and exits 1.
        final Path busybox = executable(dir.resolve("busybox-sha256sum"),
                "#!/bin/sh\ncase \" $* \" in *' --strict '*) echo 'unrecognized option' >&2; exit 1;; esac\nexit 0\n");
        final String hostSha = Stream.of("/usr/bin/sha256sum", "/sbin/sha256sum", "/usr/local/bin/sha256sum")
                .filter(p -> Files.isExecutable(Path.of(p))).findFirst().orElse(null);
        final Path gnu = executable(dir.resolve("host-sha256sum"),
                hostSha != null
                        ? "#!/bin/sh\nexec " + hostSha + " \"$@\"\n"
                        : "#!/bin/sh\nexec /usr/bin/shasum -a 256 \"$@\"\n");

        assertThat(runProbe(failLine, probe.replace("| /usr/bin/sha256sum -c", "| " + busybox + " -c"))).contains(
                SandboxSeeder.FAIL_EXIT + " AIMON_SEED_FAIL image-contract /usr/bin/sha256sum does not accept");
        assertThat(runProbe(failLine, probe.replace("| /usr/bin/sha256sum -c", "| " + gnu + " -c"))).isEqualTo("0 ");
    }

    private static Path executable(Path path, String content) throws Exception {
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }

    /** Runs the seed's {@code fail} and one line of it: the exit code, a space, then what it printed. */
    private static String runProbe(String failLine, String line) throws Exception {
        final Process process = new ProcessBuilder("/bin/bash", "-c", failLine + "\n" + line).redirectErrorStream(true)
                .start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return process.waitFor() + " " + output;
    }

    @Test
    void theWorkspaceIsCheckedBeforeTheLockIsTaken() {
        final String script = seeder.script(profile, "/workspace/repo");

        assertThat(script).contains(
                "[ -d /workspace ] && [ -w /workspace ] || fail workspace '/workspace is missing or not writable'\n")
                .contains("exec 8>/workspace/.aimon-seed.lock || fail workspace");
        assertThat(script.indexOf("[ -w /workspace ]")).isLessThan(script.indexOf("exec 8>"));
    }

    private static final SandboxProfile ISOLATED = SandboxTestProfiles.local("isolated")
            .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT)).build();

    @Test
    @DisplayName("§11.3: the control-plane probe runs only when NETWORK_ISOLATION is required, with quoted targets")
    void theNetworkProbeRunsOnlyWhenIsolationIsRequired() {
        final SandboxSeeder probing = new SandboxSeeder(Duration.ofSeconds(30),
                List.of(HostPort.of("opensandbox-server.opensandbox-system.svc", 80), HostPort.of("it's", 443)));

        assertThat(probing.script(ISOLATED, "/workspace/repo"))
                .contains("probe_host='opensandbox-server.opensandbox-system.svc'; probe_port=80\n")
                .contains("probe_host='it'\\''s'; probe_port=443\n").contains(
                        "fail network-isolation \"the sandbox reaches the control plane at $probe_host:$probe_port\"");
        assertThat(probing.script(profile, "/workspace/repo")).as("waived").doesNotContain("probe");
        assertThat(seeder.script(ISOLATED, "/workspace/repo")).as("no targets").doesNotContain("probe");
    }

    private Optional<SandboxSeeder.Failure> seedIsolated(HostPort target) throws Exception {
        final SandboxSeeder probing = new SandboxSeeder(Duration.ofSeconds(30), List.of(target));
        try (LocalProcessSandboxProvider local = LocalProcessSandboxProvider.builder().build()) {
            final ProviderSandboxRef ref = local.create(
                    CreateSpec.builder().key("seed").image("local").expiresAt(Instant.now().plusSeconds(3600)).build());
            try (SandboxConnection connection = local.connect(ref)) {
                return probing.seed(connection, ISOLATED, "/workspace/repo");
            }
        }
    }

    @Test
    @DisplayName("§11.3: a control plane the sandbox can connect to fails the slot; an unreachable one passes")
    void aReachableControlPlaneIsAPermanentFailure() throws Exception {
        final int closedPort;
        try (ServerSocket listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            closedPort = listening.getLocalPort();
            final Optional<SandboxSeeder.Failure> reached = seedIsolated(HostPort.of("127.0.0.1", closedPort));

            assertThat(reached).hasValueSatisfying(f -> {
                assertThat(f.step()).isEqualTo("network-isolation");
                assertThat(f.reason()).isEqualTo("the sandbox reaches the control plane at 127.0.0.1:" + closedPort);
            });
        }
        assertThat(seedIsolated(HostPort.of("127.0.0.1", closedPort))).isEmpty();
        assertThat(seedIsolated(HostPort.of("no-such-host.invalid", 80))).isEmpty();
    }
}
