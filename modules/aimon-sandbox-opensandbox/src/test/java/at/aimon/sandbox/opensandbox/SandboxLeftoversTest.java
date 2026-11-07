package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The pure parts of {@link SandboxLeftovers}: what the docker and k8s tiers' leftover checks read and report. */
class SandboxLeftoversTest {

    private static final String PS = String.join("\n",
            "    1     0     1 Ss   /bin/sh /opt/opensandbox/bootstrap.sh tail -f /dev/null",
            "   40     1    40 S    python3 -c import os, time; os.fork() and time.sleep(600) aimon-zombie-parent",
            "   41    40    40 Z    [python3] <defunct>", "   57     1    50 Z    [python3] <defunct>",
            "   60     1    60 S    sleep 615555",
            "   61     1    61 S    python3 -I /workspace/repo/.aimon-ptc/r/aimon_ptc.py",
            "   70    12    70 R    ps -eo pid=,ppid=,pgid=,stat=,args=", "not a process line");

    @Test
    void psLinesAreParsedAndAnythingElseSkipped() {
        final List<SandboxLeftovers.Proc> procs = SandboxLeftovers.parse(PS);

        assertThat(procs).hasSize(7);
        assertThat(procs.get(0)).isEqualTo(
                new SandboxLeftovers.Proc(1, 0, 1, "Ss", "/bin/sh /opt/opensandbox/bootstrap.sh tail -f /dev/null"));
    }

    @Test
    void zombiesRuntimesAndTheMarkCountButNotTheCommandItself() {
        final List<SandboxLeftovers.Proc> procs = SandboxLeftovers.parse(PS);

        assertThat(procs.stream().filter(SandboxLeftovers.leftOver("615555")).map(SandboxLeftovers.Proc::pid))
                .containsExactly(41, 57, 60, 61);
        assertThat(procs.stream().filter(SandboxLeftovers.leftOver(null)).map(SandboxLeftovers.Proc::pid))
                .containsExactly(41, 57, 61);
    }

    @Test
    void onlyWhatIsNewFailsAndAZombieNamesItsParent() {
        final List<SandboxLeftovers.Proc> procs = SandboxLeftovers.parse(PS);

        final String message = SandboxLeftovers.describeNew(procs, Set.of(57, 61), SandboxLeftovers.leftOver(null));

        assertThat(message).startsWith("new since this run").contains(
                "41 40 40 Z [python3] <defunct> [parent: python3 -c import os, time; os.fork() and time.sleep(600) "
                        + "aimon-zombie-parent]")
                .contains("present before this run:\n  57 1 50 Z [python3] <defunct> [parent: /bin/sh");
        assertThat(SandboxLeftovers.describeNew(procs, Set.of(41, 57, 61), SandboxLeftovers.leftOver(null)))
                .as("nothing new").isEmpty();
    }

    @Test
    void theProbeReadsBackTheTopology() {
        final SandboxLeftovers.WatchdogProbe probe = SandboxLeftovers.parseProbe(String.join("\n", "self=61 pgid=61",
                "parent=60 pgid=12 args=python3 -I /run/aimon_ptc.py --key x", "watchdog=63 ppid=1 pgid=61", ""));

        assertThat(probe.runtimeLeadsItsOwnGroup()).isTrue();
        assertThat(probe.parentArgs()).isEqualTo("python3 -I /run/aimon_ptc.py --key x");
        assertThat(probe.watchdogs()).extracting(SandboxLeftovers.Proc::pid, SandboxLeftovers.Proc::ppid)
                .containsExactly(tuple(63, 1));
        assertThat(
                SandboxLeftovers.parseProbe("self=61 pgid=12\nparent=60 pgid=12 args=sh\n").runtimeLeadsItsOwnGroup())
                .as("still in the shell's group").isFalse();
        assertThatThrownBy(() -> SandboxLeftovers.parseProbe("self=61 pgid=61\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theProbeProgramWritesWhereItIsTold() {
        assertThat(SandboxLeftovers.watchdogProbeProgram("/tmp/aimon-probe-x"))
                .contains("with open('/tmp/aimon-probe-x', 'w')").contains("if _g == _me and _p != _me:");
    }
}
