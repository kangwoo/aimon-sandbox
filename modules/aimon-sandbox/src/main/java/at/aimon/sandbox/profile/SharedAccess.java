package at.aimon.sandbox.profile;

/**
 * How a profile's sandboxes mount the workspace's {@code /shared} volume
 * (docs/design/workspace-sandbox-filesystem.md §8.2).
 */
public enum SharedAccess {

    /** Read-write: every {@code rw} slot of a workspace is one trust domain. */
    RW,

    /** Read-only: fetch, never push. */
    RO,

    /** Not mounted (the default; the only value implementation step 3 accepts). */
    NONE
}
