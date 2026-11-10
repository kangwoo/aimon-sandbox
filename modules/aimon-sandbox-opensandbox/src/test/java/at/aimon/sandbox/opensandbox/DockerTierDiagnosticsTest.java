package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * The pure parts of the docker tier's flake diagnostics: which server log or error body makes
 * {@link OpenSandboxEgressIT} and {@link OpenSandboxProviderContractTest} retry a create and how that retry runs, on a
 * simulated server log, and what {@link OpenSandboxWorkspaceIT}'s out-of-memory test reads of its cgroup — its
 * version, the swap it allows and the {@code oom_kill} count — and of Docker: which container is the sandbox's.
 */
class DockerTierDiagnosticsTest {

    private static final String CONFLICT = "WARNING sandbox=abc | action=start egress sidecar | error=500 Server Error "
            + "(\"driver failed programming external connectivity on endpoint sidecar-abc: Bind for 0.0.0.0:51234 "
            + "failed: port is already allocated\")\n";
    private static final String OTHER = "WARNING sandbox=abc | action=start egress sidecar | error=... OCI runtime "
            + "create failed: unable to apply sysctl\n";

    /** A server log that grows by one entry per create, as {@link #creates} says. */
    private static final class FakeLog implements OpenSandboxTestServer.Log {
        private final StringBuilder log = new StringBuilder("INFO server started\n");

        @Override
        public int mark() {
            return log.length();
        }

        @Override
        public String since(int mark) {
            return log.substring(mark);
        }

        /**
         * @return a create that, on its n-th call, logs {@code entries[n]} and throws an HTTP 500 when the entry is
         *         not {@code null}, and returns {@code "ref-n"} when it is
         */
        Supplier<String> creates(List<Integer> calls, String... entries) {
            return () -> {
                final int call = calls.size();
                calls.add(call);
                if (entries[call] == null) {
                    return "ref-" + call;
                }
                log.append(entries[call]);
                throw HttpErrors.failure(500, "create sandbox", "Internal Server Error");
            };
        }
    }

    @Test
    void onlyAHostPortConflictInTheServersLogIsRetried() {
        assertThat(OpenSandboxTestServer.isPortConflict("WARNING sandbox=abc | action=start egress sidecar | "
                + "duration=41.20 | error=500 Server Error for http+docker://localhost/v1.47/containers/x/start: "
                + "Internal Server Error (\"driver failed programming external connectivity on endpoint "
                + "sidecar-abc: Bind for 0.0.0.0:51234 failed: port is already allocated\")")).isTrue();
        assertThat(OpenSandboxTestServer
                .isPortConflict("error=... listen tcp4 0.0.0.0:41000: bind: Address already in use")).isTrue();
        assertThat(OpenSandboxTestServer.isPortConflict("WARNING sandbox=abc | action=start egress sidecar | "
                + "error=... OCI runtime create failed: unable to apply sysctl")).isFalse();
        assertThat(OpenSandboxTestServer.isPortConflict("")).isFalse();
    }

    @Test
    void aPortConflictIsRetriedOnceAndTheRetrySaysSo() {
        final FakeLog log = new FakeLog();
        final List<Integer> calls = new ArrayList<>();
        final List<String> said = new ArrayList<>();

        assertThat(OpenSandboxTestServer.retryOnPortConflict(log, log.creates(calls, CONFLICT, null), said::add))
                .isEqualTo("ref-1");
        assertThat(calls).hasSize(2);
        assertThat(said).singleElement().asString().startsWith("create answered HTTP 500").contains("retrying once")
                .endsWith(CONFLICT).doesNotContain("server started");
    }

    @Test
    void aSecondConflictFailsWithTheServersLogOfIt() {
        final FakeLog log = new FakeLog();
        final List<Integer> calls = new ArrayList<>();

        assertThatThrownBy(() -> OpenSandboxTestServer.retryOnPortConflict(log,
                log.creates(calls, CONFLICT, CONFLICT.replace("abc", "def")), message -> {
                })).isInstanceOf(SandboxProviderException.class).hasMessageContaining("HTTP 500")
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement().asString().contains("(attempt 2)")
                        .contains("sidecar-def").doesNotContain("sidecar-abc"));
        assertThat(calls).hasSize(2);
    }

    @Test
    void anyOther500FailsAtOnceWithTheServersLogOfIt() {
        final FakeLog log = new FakeLog();
        final List<Integer> calls = new ArrayList<>();

        assertThatThrownBy(
                () -> OpenSandboxTestServer.retryOnPortConflict(log, log.creates(calls, OTHER, null), message -> {
                })).isInstanceOf(SandboxProviderException.class).satisfies(e -> assertThat(e.getSuppressed())
                        .singleElement().asString().contains("(attempt 1)").contains("unable to apply sysctl"));
        assertThat(calls).hasSize(1);
    }

    @Test
    void aFailureThatIsNotA500IsNotRetriedNorAnnotated() {
        final FakeLog log = new FakeLog();
        final List<Integer> calls = new ArrayList<>();
        final Supplier<String> create = () -> {
            calls.add(calls.size());
            throw HttpErrors.failure(422, "create sandbox", "bad image");
        };

        assertThatThrownBy(() -> OpenSandboxTestServer.retryOnPortConflict(log, create, message -> {
        })).isInstanceOf(SandboxProviderException.class).hasMessageContaining("HTTP 422").hasNoSuppressedExceptions();
        assertThat(calls).hasSize(1);
    }

    @Test
    void dockersFailedToBindWordingIsAPortConflictInTheLogOrInTheErrorBodyAlone() {
        assertThat(OpenSandboxTestServer.isPortConflict("Failed to create or start container: ... (\"failed to set up "
                + "container networking: driver failed programming external connectivity on endpoint sandbox-x (f4): "
                + "failed to bind host port 0.0.0.0:53758/tcp…")).as("PR #55's truncated body").isTrue();
        assertThat(OpenSandboxTestServer.isPortConflict(null)).isFalse();

        final FakeLog log = new FakeLog();
        final List<Integer> calls = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        final Supplier<String> create = () -> {
            calls.add(calls.size());
            if (calls.size() > 1) {
                return "ref-1";
            }
            throw HttpErrors.failure(500, "create sandbox", "{\"code\":\"DOCKER::SANDBOX_START_FAILED\",\"message\":"
                    + "\"... failed to bind host port 0.0.0.0:53758/tcp: address already in use\"}");
        };

        assertThat(OpenSandboxTestServer.retryOnPortConflict(log, create, said::add)).as("nothing in the server's log")
                .isEqualTo("ref-1");
        assertThat(calls).hasSize(2);
        assertThat(said).singleElement().asString().startsWith("create answered HTTP 500");
    }

    @Test
    void aSandboxsContainerIsNamedAfterItsId() {
        assertThat(OpenSandboxTestServer.containerName(ProviderSandboxRef.of(OpenSandboxProvider.NAME, "20a7f041")))
                .isEqualTo("sandbox-20a7f041");
    }

    @Test
    void aSandboxsContainerIsTheOneLabelledWithItsIdThatIsNoSidecar() {
        final ProviderSandboxRef ref = ProviderSandboxRef.of(OpenSandboxProvider.NAME, "20a7f041");
        final OpenSandboxTestServer.DockerContainer own = container("c1", "renamed-by-a-newer-server",
                Map.of(OpenSandboxTestServer.SANDBOX_ID_LABEL, "20a7f041"));
        final OpenSandboxTestServer.DockerContainer sidecar = container("c2", "sandbox-egress-20a7f041", Map.of(
                OpenSandboxTestServer.SANDBOX_ID_LABEL, "20a7f041", OpenSandboxTestServer.SIDECAR_LABEL, "20a7f041"));

        assertThat(OpenSandboxTestServer.sandboxContainer(ref, List.of(sidecar, own))).as("whatever its name")
                .isEqualTo("c1");
    }

    @Test
    void withoutASingleLabelledContainerTheServersNameDecides() {
        final ProviderSandboxRef ref = ProviderSandboxRef.of(OpenSandboxProvider.NAME, "20a7f041");
        final OpenSandboxTestServer.DockerContainer unlabelled = container("c1", "sandbox-20a7f041", Map.of());
        final OpenSandboxTestServer.DockerContainer twinA = container("c2", "sandbox-20a7f041",
                Map.of(OpenSandboxTestServer.SANDBOX_ID_LABEL, "20a7f041"));
        final OpenSandboxTestServer.DockerContainer twinB = container("c3", "helper-20a7f041",
                Map.of(OpenSandboxTestServer.SANDBOX_ID_LABEL, "20a7f041"));

        assertThat(OpenSandboxTestServer.sandboxContainer(ref, List.of(unlabelled))).as("an older server's labels")
                .isEqualTo("c1");
        assertThat(OpenSandboxTestServer.sandboxContainer(ref, List.of(twinA, twinB))).as("two labelled")
                .isEqualTo("c2");
    }

    @Test
    void noSingleContainerFailsNamingWhatWasFound() {
        final ProviderSandboxRef ref = ProviderSandboxRef.of(OpenSandboxProvider.NAME, "20a7f041");
        final OpenSandboxTestServer.DockerContainer sidecar = container("c2", "sandbox-egress-20a7f041", Map.of(
                OpenSandboxTestServer.SANDBOX_ID_LABEL, "20a7f041", OpenSandboxTestServer.SIDECAR_LABEL, "20a7f041"));
        final OpenSandboxTestServer.DockerContainer other = container("c4", "sandbox-ffff0000",
                Map.of(OpenSandboxTestServer.SANDBOX_ID_LABEL, "ffff0000"));

        assertThatThrownBy(() -> OpenSandboxTestServer.sandboxContainer(ref, List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no single Docker container of sandbox 20a7f041")
                .hasMessageContaining("0 labelled opensandbox.io/id=20a7f041 and 0 named sandbox-20a7f041");
        assertThatThrownBy(() -> OpenSandboxTestServer.sandboxContainer(ref, List.of(sidecar, other)))
                .as("only its sidecar and another sandbox's container").isInstanceOf(AssertionError.class)
                .hasMessageContaining("[sandbox-egress-20a7f041]").hasMessageContaining("[sandbox-ffff0000]");
    }

    @Test
    void theSwapACgroupAllowsIsReadFromItsMemoryFiles() {
        final String v2Docker = String.join("\n", "cgroup: v2", "memory.max: 268435456 ", "memory.swap.max: 268435456 ",
                "memory.current: 9129984 ", "");
        final String v2None = "cgroup: v2\nmemory.max: 268435456 \nmemory.swap.max: 0 \n";
        final String v2Unlimited = "cgroup: v2\nmemory.swap.max: max \n";
        final String v1None = "cgroup: v1\nmemory.limit_in_bytes: 268435456 \nmemory.memsw.limit_in_bytes: 268435456 \n";
        final String v1Docker = "cgroup: v1\nmemory.limit_in_bytes: 268435456 \nmemory.memsw.limit_in_bytes: 536870912 \n";

        assertThat(OpenSandboxWorkspaceIT.swapAllowance(v2Docker)).as("Docker's default, PR #55").isEqualTo(268435456);
        assertThat(OpenSandboxWorkspaceIT.swapAllowance(v2None)).isZero();
        assertThat(OpenSandboxWorkspaceIT.swapAllowance(v2Unlimited)).isEqualTo(Long.MAX_VALUE);
        assertThat(OpenSandboxWorkspaceIT.swapAllowance(v1None)).isZero();
        assertThat(OpenSandboxWorkspaceIT.swapAllowance(v1Docker)).isEqualTo(268435456);
        assertThat(OpenSandboxWorkspaceIT.swapAllowance("cgroup: v2\nmemory.swap.max: tr: No such file \n"))
                .as("no swap accounting").isEqualTo(-1);
        assertThat(OpenSandboxWorkspaceIT.swapAllowance("(unreadable: java.io.IOException)\n")).isEqualTo(-1);
    }

    @Test
    void theOomKillCountIsReadFromMemoryEvents() {
        final String memory = String.join("\n", "memory.max: 268435456 ", "memory.swap.max: 268435456 ",
                "memory.events: low 0 high 0 max 812 oom 3 oom_kill 2 oom_group_kill 0 ",
                "memory.swap.events: high 0 max 40 fail 0 ", "");

        assertThat(OpenSandboxWorkspaceIT.oomKills(memory)).isEqualTo(2);
        assertThat(OpenSandboxWorkspaceIT.oomKills("memory.events: tr: /sys/fs/cgroup/memory.events: No such file\n"))
                .isEqualTo(-1);
        assertThat(OpenSandboxWorkspaceIT.oomKills("(unreadable: java.io.IOException)\n")).isEqualTo(-1);
    }

    @Test
    void theOomKillCountIsReadFromMemoryOomControlOnCgroupV1() {
        final String memory = String.join("\n", "cgroup: v1", "memory.limit_in_bytes: 268435456 ",
                "memory.failcnt: 812 ", "memory.oom_control: oom_kill_disable 0 under_oom 0 oom_kill 3 ", "");

        assertThat(OpenSandboxWorkspaceIT.oomKills(memory)).isEqualTo(3);
        assertThat(OpenSandboxWorkspaceIT.oomKills("cgroup: v1\nmemory.oom_control: oom_kill_disable 0 under_oom 0 \n"))
                .as("a kernel before 4.13 has no count").isEqualTo(-1);
    }

    @Test
    void onlyACgroupV1WithoutTheCountSkipsTheOomKillCheck() {
        final String v1Without = "cgroup: v1\nmemory.oom_control: oom_kill_disable 0 under_oom 0 \n";
        final String v1With = "cgroup: v1\nmemory.oom_control: oom_kill_disable 0 under_oom 0 oom_kill 1 \n";
        final String v2With = "cgroup: v2\nmemory.events: low 0 high 0 max 812 oom 3 oom_kill 2 \n";
        final String v2Without = "cgroup: v2\nmemory.events: tr: /sys/fs/cgroup/memory.events: Permission denied \n";
        final String unreadable = "(unreadable: java.io.IOException)\n";

        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(v1Without, v1Without)).isTrue();
        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(v1With, v1Without)).as("v1, the count lost after").isTrue();
        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(v1With, v1With)).as("v1 with the count: checked").isFalse();
        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(v2With, v2With)).isFalse();
        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(v2Without, v2Without)).as("v2 stays strict").isFalse();
        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(v2With, v2Without)).as("v2 stays strict").isFalse();
        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(v1Without, unreadable)).as("no version read: strict")
                .isFalse();
        final String unknown = "cgroup: unknown (neither cgroup.controllers nor memory/memory.limit_in_bytes in "
                + "'/sys/fs/cgroup': cpu cpuacct )\n";
        assertThat(OpenSandboxWorkspaceIT.lacksOomKillCount(unknown, unknown)).as("an unknown version: strict")
                .isFalse();
    }

    @Test
    void theCgroupVersionIsReadFromWhatTheCgroupFileSystemHolds(@TempDir Path dir) throws Exception {
        final Path v2 = Files.createDirectories(dir.resolve("v2"));
        Files.writeString(v2.resolve("cgroup.controllers"), "cpu memory pids\n");
        Files.writeString(v2.resolve("memory.swap.max"), "0\n");
        final Path v1 = Files.createDirectories(dir.resolve("v1/memory"));
        Files.writeString(v1.resolve("memory.limit_in_bytes"), "268435456\n");
        Files.writeString(v1.resolve("memory.memsw.limit_in_bytes"), "268435456\n");
        // a v1 hierarchy without the memory controller: v1 by its layout, but there is no memory file to read
        Files.createDirectories(dir.resolve("unknown/cpu"));
        Files.createDirectories(dir.resolve("unknown/memory"));

        final String v2Memory = run(OpenSandboxWorkspaceIT.memoryCommand(v2.toString()));
        final String v1Memory = run(OpenSandboxWorkspaceIT.memoryCommand(dir.resolve("v1").toString()));
        final String unknown = run(OpenSandboxWorkspaceIT.memoryCommand(dir.resolve("unknown").toString()));

        assertThat(OpenSandboxWorkspaceIT.cgroupVersion(v2Memory)).as(v2Memory).isEqualTo("v2");
        assertThat(OpenSandboxWorkspaceIT.swapAllowance(v2Memory)).as(v2Memory).isZero();
        assertThat(OpenSandboxWorkspaceIT.cgroupVersion(v1Memory)).as(v1Memory).isEqualTo("v1");
        assertThat(OpenSandboxWorkspaceIT.swapAllowance(v1Memory)).as(v1Memory).isZero();
        assertThat(OpenSandboxWorkspaceIT.cgroupVersion(unknown)).as(unknown).isNull();
        assertThat(unknown).startsWith("cgroup: unknown (neither cgroup.controllers nor memory/memory.limit_in_bytes")
                .contains("cpu memory");
        assertThat(OpenSandboxWorkspaceIT.lacksSwapAccounting(unknown)).as("no version: not a host without it")
                .isFalse();
    }

    @Test
    void aHostWithoutSwapAccountingIsToldApartFromAnUnreadableCgroup() {
        final String v2Without = "cgroup: v2\nmemory.max: 268435456 \n"
                + "memory.swap.max: tr: /sys/fs/cgroup/memory.swap.max: No such file or directory \n";
        final String v1Without = "cgroup: v1\nmemory.limit_in_bytes: 268435456 \n"
                + "memory.memsw.limit_in_bytes: tr: memory.memsw.limit_in_bytes: No such file or directory \n";

        assertThat(OpenSandboxWorkspaceIT.lacksSwapAccounting(v2Without)).isTrue();
        assertThat(OpenSandboxWorkspaceIT.lacksSwapAccounting(v1Without)).isTrue();
        assertThat(OpenSandboxWorkspaceIT.lacksSwapAccounting("cgroup: v2\nmemory.swap.max: 0 \n")).isFalse();
        assertThat(OpenSandboxWorkspaceIT.lacksSwapAccounting("cgroup: v2\nmemory.swap.max: 268435456 \n"))
                .as("accounted, and not taken away: the test's assertion fails on it").isFalse();
        assertThat(OpenSandboxWorkspaceIT.lacksSwapAccounting("(unreadable: java.io.IOException)\n"))
                .as("execd did not answer").isFalse();
    }

    private static OpenSandboxTestServer.DockerContainer container(String id, String name, Map<String, String> labels) {
        return new OpenSandboxTestServer.DockerContainer(id, List.of(name), labels);
    }

    /** @return the stdout and stderr of {@code command} in this host's {@code bash}, which must exit 0 */
    private static String run(String command) throws Exception {
        final Process bash = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start();
        final String out = new String(bash.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(bash.waitFor()).as(out).isZero();
        return out;
    }
}
