package at.aimon.sandbox.provider;

/** The state a provider reports for one sandbox. */
public enum ProviderSandboxState {

    /** Being created. */
    PENDING,

    /** Running and able to execute commands. */
    RUNNING,

    /** Paused ({@link Capability#PAUSE_RESUME}). */
    PAUSED,

    /** Stopped or being deleted. */
    TERMINATED,

    /** The provider gave up on it. */
    FAILED
}
