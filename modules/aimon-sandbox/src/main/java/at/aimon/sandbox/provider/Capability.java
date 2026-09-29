package at.aimon.sandbox.provider;

/**
 * What a {@link SandboxProvider} can do (docs/design/workspace-sandbox.md §6.4). A provider advertises only what it
 * verified; a profile that needs an unadvertised capability is refused at startup rather than run without it.
 */
public enum Capability {

    /** Command execution: cwd, env, separate stdout/stderr, process-group kill. */
    EXEC,

    /** The files API ({@link SandboxFiles}). */
    FILES,

    /** Pause and resume a sandbox. */
    PAUSE_RESUME,

    /** A provider-side absolute expiry that can be pushed forward. */
    EXPIRY,

    /** RWX volumes shared by several sandboxes, per-sandbox read-only mounts, and volume deletion. */
    SHARED_VOLUME,

    /** Destination allow-lists with everything else denied. */
    EGRESS_POLICY,

    /** Secrets injected on egress without being exposed inside the sandbox. */
    CREDENTIAL_INJECTION,

    /** East-west and control-plane traffic blocked. */
    NETWORK_ISOLATION,

    /** The requested runtime class (gVisor, Kata) is actually used. */
    RUNTIME_CLASS,

    /** Non-root, no privilege escalation, dropped capabilities, no service-account token. */
    HARDENED_SECURITY_CONTEXT,

    /** Snapshots. */
    SNAPSHOT,

    /** Forks built on snapshots. */
    FORK
}
