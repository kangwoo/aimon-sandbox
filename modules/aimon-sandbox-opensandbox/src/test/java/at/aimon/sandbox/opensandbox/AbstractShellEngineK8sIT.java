package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;

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
     * @param args
     *            PID 1's command line, its arguments separated by spaces
     */
    protected abstract void checkPid1(String args);

    /** PID 1's command line, {@code ps}'s view of it and the pod's name go to stdout, which the JUnit XML keeps. */
    @Test
    @DisplayName("PID 1 of the sandbox is recorded, and is what this mode expects")
    void pid1IsRecorded() throws Exception {
        final ShellCommandResult pid1 = sandbox()
                .bash("tr '\\0' ' ' </proc/1/cmdline; echo; ps -o pid,ppid,stat,args -p 1; echo \"pod: $(hostname)\"");
        assertThat(pid1.exitCode()).as("reading PID 1: %s", pid1.stderr()).isZero();
        System.out.println("[" + getClass().getSimpleName() + "] PID 1:\n" + pid1.stdout());
        checkPid1(pid1.stdout().lines().findFirst().orElse("").strip());
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
