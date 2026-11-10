package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * The pure parts of the docker tier's flake diagnostics: which server log makes {@link OpenSandboxEgressIT} retry a
 * create and how that retry runs, on a simulated server log, and the {@code oom_kill} count
 * {@link OpenSandboxWorkspaceIT}'s out-of-memory test compares.
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
    }
}
