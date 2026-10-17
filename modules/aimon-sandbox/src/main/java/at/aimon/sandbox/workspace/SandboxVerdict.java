package at.aimon.sandbox.workspace;

/**
 * What the janitor's reconciliation makes of one sandbox the provider lists (docs/design/workspace-sandbox.md §10.4;
 * docs/design/workspace-sandbox-management.md §6.8), before any grace — the classification
 * {@code SandboxAdministration.sandboxes()} shows.
 */
public enum SandboxVerdict {

    /**
     * Its labels do not say what it is, or it failed the label comparison: not ours to judge; provider expiry ends it.
     */
    SKIP,

    /** The record's current sandbox. */
    OK,

    /**
     * Of a PROVISIONING generation, or newer than the record: someone is creating it. One ahead of its record's
     * generation is destroyed as an orphan once older than {@code orphan-grace}.
     */
    IN_FLIGHT,

    /**
     * No record, another incarnation, no such slot, or a TERMINATED generation: destroyed after {@code orphan-grace}.
     */
    ORPHAN,

    /** An older generation than the record's: destroyed on the janitor's next pass. */
    STALE,

    /** A failed generation the record points nobody at: destroyed on the janitor's next pass. */
    FAILED_LEFTOVER,

    /** The same generation as a live slot but not its recorded sandbox: destroyed after {@code orphan-grace}. */
    DUPLICATE
}
