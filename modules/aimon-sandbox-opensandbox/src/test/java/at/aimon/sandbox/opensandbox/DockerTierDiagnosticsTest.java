package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The pure parts of the docker tier's flake diagnostics: which server log makes {@link OpenSandboxEgressIT} retry a
 * create, and the {@code oom_kill} count {@link OpenSandboxWorkspaceIT}'s out-of-memory test compares.
 */
class DockerTierDiagnosticsTest {

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
    void theOomKillCountIsReadFromMemoryEvents() {
        final String memory = String.join("\n", "memory.max: 268435456 ", "memory.swap.max: 268435456 ",
                "memory.events: low 0 high 0 max 812 oom 3 oom_kill 2 oom_group_kill 0 ",
                "memory.swap.events: high 0 max 40 fail 0 ", "");

        assertThat(OpenSandboxWorkspaceIT.oomKills(memory)).isEqualTo(2);
        assertThat(OpenSandboxWorkspaceIT.oomKills("memory.events: tr: /sys/fs/cgroup/memory.events: No such file\n"))
                .isEqualTo(-1);
        assertThat(OpenSandboxWorkspaceIT.oomKills("(unreadable: java.io.IOException)\n")).isEqualTo(-1);
    }
}
