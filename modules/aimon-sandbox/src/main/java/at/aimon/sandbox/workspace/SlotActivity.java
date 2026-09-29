package at.aimon.sandbox.workspace;

/**
 * Activity recording for one slot generation — the public seam between the environment layer and the manager
 * (docs/design/workspace-sandbox.md §5.3, §10.3). Only reachable through a {@link ConnectedSlot}, i.e. after
 * {@code connect}'s owner check, and bound to the generation and sandbox it was handed out for: once the slot moves on,
 * its writes are no-ops.
 */
public interface SlotActivity {

    /**
     * Records activity: when {@code force} or when {@code activityWriteInterval} has passed since the recorded value,
     * CAS {@code lastActivityAt = max(old, now)} on the slot and the workspace and push the provider expiry to
     * {@code now + terminateAfter}. A CAS conflict is retried, never dropped.
     *
     * @param force
     *            whether to write regardless of the throttle
     * @throws SandboxUnavailableException
     *             when the sandbox turned out to be gone (the slot is then marked lost)
     */
    void record(boolean force);

    /**
     * Starts the heartbeat of one running command: every {@code activityWriteInterval}, {@link #record(boolean)
     * record(true)} and {@code onTick}. A background command's heartbeat stops after the profile's
     * {@code backgroundHeartbeatLimit}.
     *
     * @param background
     *            whether the command runs in the background
     * @param onTick
     *            run after each successful write (a foreground command's lock heartbeat file)
     * @param onLost
     *            run once when the sandbox is found gone
     * @return the heartbeat
     */
    Heartbeat startHeartbeat(boolean background, Runnable onTick, Runnable onLost);

    /** Marks the slot's sandbox lost: CAS TERMINATED with {@code lostAt}, guarded by generation and sandbox. */
    void markLost();
}
