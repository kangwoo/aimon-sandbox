package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.shell.ShellCommandResult;

/**
 * The shell-engine checks of {@link AbstractShellEngineSandboxIT} on the k8s tier (docs/design/workspace-sandbox.md
 * §16), plus what they rest on there: which process is the sandbox's PID 1, and whether it reaps an orphaned zombie.
 * Both modes extend it; they differ in how the sandbox is created, and so in what PID 1 is.
 */
abstract class AbstractShellEngineK8sIT extends AbstractShellEngineSandboxIT {

    /**
     * @param argv
     *            PID 1's arguments, as its NUL-separated {@code /proc/1/cmdline} has them (one may contain a space)
     * @param procs
     *            every process of the sandbox, for a check that rests on more than PID 1 (a subreaper under it)
     */
    protected abstract void checkPid1(List<String> argv, List<SandboxLeftovers.Proc> procs);

    /**
     * PID 1's command line, {@code ps}'s view of it, the pod's name and the execd processes (one is the subreaper when
     * execd runs as {@code --init} under another PID 1) go to stdout, which the JUnit XML keeps.
     */
    @Test
    @DisplayName("PID 1 of the sandbox is recorded, and is what this mode expects")
    void pid1IsRecorded() throws Exception {
        final ShellCommandResult pid1 = sandbox()
                .bash("tr '\\0' ' ' </proc/1/cmdline; echo; ps -o pid,ppid,stat,args -p 1; echo \"pod: $(hostname)\"");
        assertThat(pid1.exitCode()).as("reading PID 1: %s", pid1.stderr()).isZero();
        final ShellCommandResult cmdline = sandbox().bash("od -An -v -tx1 </proc/1/cmdline");
        assertThat(cmdline.exitCode()).as("reading PID 1's cmdline: %s", cmdline.stderr()).isZero();
        final List<String> argv = argv(cmdline.stdout());
        final List<SandboxLeftovers.Proc> procs = SandboxLeftovers.snapshot(sandbox()::bash);
        System.out.println("[" + getClass().getSimpleName() + "] PID 1:\n" + pid1.stdout() + "argv: " + argv
                + "\nexecd (pid ppid args): " + procs.stream().filter(proc -> proc.args().contains("execd"))
                        .map(proc -> proc.pid() + " " + proc.ppid() + " " + proc.args()).toList());
        checkPid1(argv, procs);
    }

    /**
     * @param od
     *            {@code /proc/<pid>/cmdline} as {@code od -An -v -tx1} prints it: its bytes in hex, separated by spaces
     *            and newlines, so that no byte of it (a NUL, a newline, a space) is lost on the way out of the sandbox
     * @return its arguments: the bytes between NULs, as UTF-8; empty for an empty cmdline
     */
    static List<String> argv(String od) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String hex : od.strip().split("\\s+")) {
            if (!hex.isEmpty()) {
                bytes.write(Integer.parseInt(hex, 16));
            }
        }
        final List<String> argv = new ArrayList<>();
        final ByteArrayOutputStream arg = new ByteArrayOutputStream();
        for (byte b : bytes.toByteArray()) {
            if (b == 0) {
                argv.add(arg.toString(StandardCharsets.UTF_8));
                arg.reset();
            } else {
                arg.write(b);
            }
        }
        if (arg.size() > 0) {
            argv.add(arg.toString(StandardCharsets.UTF_8));
        }
        return List.copyOf(argv);
    }

    /**
     * A zombie whose parent dies is reparented to PID 1, or to the nearest subreaper, which must reap it. This tests
     * that directly, without the engine.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("an orphaned zombie is reaped once its parent is gone")
    void anOrphanedZombieIsReaped() throws Exception {
        final SandboxLeftovers.Zombie zombie = SandboxLeftovers.startUnreapedZombie(sandbox()::bash);

        assertThat(sandbox().bash("kill " + zombie.parent()).exitCode()).isZero();
        SandboxLeftovers.awaitGone(sandbox()::bash, Set.of(zombie.parent(), zombie.zombie()),
                "the zombie " + zombie.zombie() + " and its killed parent " + zombie.parent());
    }
}
