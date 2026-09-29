package at.aimon.sandbox.provider;

import java.time.Duration;

/** A command started by {@link SandboxConnection#run}. */
public interface RunningCommand {

    /**
     * Waits for the command to end. When {@code timeout} passes first, the command's process group is killed as by
     * {@link #kill()} and the outcome reports {@link ExecOutcome#timedOut()}.
     *
     * @param timeout
     *            how long to wait
     * @return how the command ended
     * @throws InterruptedException
     *             if the waiting thread is interrupted; the command keeps running and the caller must {@link #kill()}
     *             it
     */
    ExecOutcome await(Duration timeout) throws InterruptedException;

    /**
     * Ends this call's process group — SIGTERM, then SIGKILL after a short grace — and nothing else. Idempotent.
     */
    void kill();
}
