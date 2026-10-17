package at.aimon.sandbox.workspace;

/** How a workspace record came to exist (docs/design/workspace-sandbox-management.md §3.1, §6.6). */
public enum WorkspaceOrigin {

    /**
     * Created by {@code connect} under an id a binding policy derived ({@code ws:{sessionId}},
     * {@code ws:{executionId}});
     * an expired tombstone is deleted after {@code closedRetention}.
     */
    DERIVED,

    /**
     * Created explicitly ({@code SandboxWorkspaces.create}) under a generated {@code wsm:} id that is never reused; the
     * janitor never deletes its record — only {@code purge} does.
     */
    MANAGED
}
