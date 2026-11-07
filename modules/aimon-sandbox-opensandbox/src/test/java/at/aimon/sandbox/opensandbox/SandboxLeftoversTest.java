package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** The pure parts of {@link SandboxLeftovers}: what the docker and k8s tiers' leftover checks read and report. */
class SandboxLeftoversTest {

    private static final String T0 = "Sat Nov  7 05:25:18 2026";
    private static final String T1 = "Sat Nov  7 05:25:21 2026";
    private static final String PS = String.join("\n",
            "    1     0     1 Ss   " + T0 + " /bin/sh /opt/opensandbox/bootstrap.sh tail -f /dev/null",
            "   40     1    40 S    " + T1
                    + " python3 -c import os, time; os.fork() and time.sleep(600) aimon-zombie-parent",
            "   41    40    40 Z    " + T1 + " [python3] <defunct>",
            "   57     1    50 Z    " + T0 + " [python3] <defunct>", "   60     1    60 S    " + T1 + " sleep 615555",
            "   61     1    61 S    " + T1 + " python3 -I /workspace/repo/.aimon-ptc/r/aimon_ptc.py",
            "   70    12    70 R    " + T1 + " ps -eo pid=,ppid=,pgid=,stat=,lstart=,args=", "not a process line",
            "   71    12    71 R    no start time");

    @Test
    void psLinesAreParsedAndAnythingElseSkipped() {
        final List<SandboxLeftovers.Proc> procs = SandboxLeftovers.parse(PS);

        assertThat(procs).hasSize(7);
        assertThat(procs.get(0)).isEqualTo(new SandboxLeftovers.Proc(1, 0, 1, "Ss", "Sat Nov 7 05:25:18 2026",
                "/bin/sh /opt/opensandbox/bootstrap.sh tail -f /dev/null"));
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

        final String message = SandboxLeftovers.describeNew(procs, keys(procs, 57, 61),
                SandboxLeftovers.leftOver(null));

        assertThat(message).startsWith("new since this run").contains(
                "41 40 40 Z [python3] <defunct> [parent: python3 -c import os, time; os.fork() and time.sleep(600) "
                        + "aimon-zombie-parent]")
                .contains("present before this run:\n  57 1 50 Z [python3] <defunct> [parent: /bin/sh");
        assertThat(SandboxLeftovers.describeNew(procs, keys(procs, 41, 57, 61), SandboxLeftovers.leftOver(null)))
                .as("nothing new").isEmpty();
    }

    @Test
    void aReusedPidIsANewProcess() {
        final List<SandboxLeftovers.Proc> procs = SandboxLeftovers.parse(PS);
        // 61 exited after the baseline was taken, and a new leftover took its pid three seconds later
        final Set<SandboxLeftovers.Key> baseline = Set.of(new SandboxLeftovers.Key(41, T1.replace("  ", " ")),
                new SandboxLeftovers.Key(57, "Sat Nov 7 05:25:18 2026"),
                new SandboxLeftovers.Key(61, "Sat Nov 7 05:25:18 2026"));

        assertThat(SandboxLeftovers.describeNew(procs, baseline, SandboxLeftovers.leftOver(null)))
                .startsWith("new since this run (pid ppid pgid stat args):\n  61 1 61 S python3 -I ");
    }

    @Test
    void theProbeReadsBackTheTopology() {
        final SandboxLeftovers.WatchdogProbe probe = SandboxLeftovers.parseProbe(
                String.join("\n", "self=61 pgid=61", "parent=60 pgid=12 args=python3 -I /run/aimon_ptc.py --key x",
                        "watchdog=63 ppid=1 pgid=61 args=python3 -I /run/aimon_ptc.py --key x", ""));

        assertThat(probe.runtimeLeadsItsOwnGroup()).isTrue();
        assertThat(probe.parentArgs()).isEqualTo("python3 -I /run/aimon_ptc.py --key x");
        assertThat(probe.watchdogs())
                .extracting(SandboxLeftovers.Proc::pid, SandboxLeftovers.Proc::ppid, SandboxLeftovers.Proc::args)
                .containsExactly(tuple(63, 1, "python3 -I /run/aimon_ptc.py --key x"));
        assertThat(
                SandboxLeftovers.parseProbe("self=61 pgid=12\nparent=60 pgid=12 args=sh\n").runtimeLeadsItsOwnGroup())
                .as("still in the shell's group").isFalse();
        assertThatThrownBy(() -> SandboxLeftovers.parseProbe("self=61 pgid=61\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAForkOfTheRuntimeOutsideItsAndTheAnchorsChildrenIsAWatchdog() {
        final SandboxLeftovers.WatchdogProbe probe = SandboxLeftovers
                .parseProbe(String.join("\n", "self=61 pgid=61", "parent=60 pgid=12 args=python3 -I /run/aimon_ptc.py",
                        "watchdog=64 ppid=60 pgid=61 args=python3 -I /run/aimon_ptc.py",
                        "watchdog=65 ppid=1 pgid=61 args=sleep 30", "watchdog=66 ppid=1 pgid=61 args=", ""));

        assertThat(probe.candidates()).hasSize(3);
        assertThat(probe.watchdogs()).as("the anchor's child, an unrelated grandchild, an unnamed process").isEmpty();
    }

    @Test
    void theProbeProgramWritesWhereItIsTold() {
        assertThat(SandboxLeftovers.watchdogProbeProgram("/tmp/aimon-probe-x"))
                .contains("with open('/tmp/aimon-probe-x', 'w')").contains("if _g == _me and _p != _me:")
                .contains("args=%s");
    }

    private static Set<SandboxLeftovers.Key> keys(List<SandboxLeftovers.Proc> procs, Integer... pids) {
        final Set<Integer> wanted = Set.of(pids);
        return procs.stream().filter(p -> wanted.contains(p.pid())).map(SandboxLeftovers.Proc::key)
                .collect(Collectors.toUnmodifiableSet());
    }
}
