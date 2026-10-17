package at.aimon.sandbox;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.Page;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SessionOptionsView;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.WorkspaceQuery;
import at.aimon.sandbox.workspace.WorkspaceView;

/**
 * The owner-facing management of workspaces (docs/design/workspace-sandbox-management.md §5.1): create a sandbox
 * before any agent runs, attach sessions to it, and close, reopen or purge it. Get it from
 * {@link WorkspaceSandbox#workspaces()}; every call takes the calling principal, which passes the same principal gate
 * as an execution.
 *
 * <pre>{@code
 * WorkspaceView ws = sandbox.workspaces().create(CreateWorkspace.named("ticket-4711"), alice); // primary RUNNING
 * sandbox.workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(ws.id()), alice);
 * // every root turn of that session — and its forks — now runs in ws
 * }</pre>
 *
 * <p>
 * <b>Rights.</b> Using a workspace — being attached to it, describing it, provisioning it — follows its access mode
 * (its own, else {@code workspace-access}). Managing it — close, reopen, purge — is its owner principal's alone.
 * Redirecting a session is a right over the session: only its owner sets its options, which needs a
 * {@code SessionOwnerLookup}.
 *
 * <p>
 * <b>A shared workspace is one trust domain.</b> Every session attached to it can read and change every file —
 * including the other sessions' shell state under {@code /workspace/.aimon-shell/}, which each command sources — and
 * uses the same profile credentials. Files do not outlive the sandbox yet: a slot ends after its profile's
 * {@code terminate-after}, and the next use starts from an empty {@code /workspace}.
 */
public final class SandboxWorkspaces {

    private final SandboxWorkspaceManager manager;

    SandboxWorkspaces(SandboxWorkspaceManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    /**
     * Creates a managed workspace under a new {@code wsm:} id and provisions its {@code primary} slot before returning;
     * the wait is bounded by {@code provision-timeout}. Repeating a call creates a second workspace — a caller that
     * lost
     * the response finds it by {@link #list} (name, creation time).
     *
     * @param request
     *            name, profile and access
     * @param caller
     *            the owner
     * @return the workspace, {@code primary} RUNNING
     * @throws IllegalArgumentException
     *             for a profile that is not configured
     * @throws SandboxConfigurationException
     *             for {@code access: tenant} without {@code allow-shared-workspaces} in a {@code principal}
     *             deployment
     * @throws at.aimon.sandbox.workspace.WorkspaceProvisioningException
     *             when the workspace was created but provisioning failed: retry with {@link #provision} or
     *             {@link #purge} it
     * @throws at.aimon.sandbox.workspace.SandboxUnavailableException
     *             over {@code max-managed-per-owner}; nothing is written
     */
    public WorkspaceView create(CreateWorkspace request, Principal caller) {
        return manager.createManaged(request, caller, caller);
    }

    /**
     * Provisions the workspace's {@code primary} slot: the retry after a failed {@link #create}, respecting the
     * recorded failure's backoff. Needs the use right.
     *
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking
     * @return the workspace, {@code primary} RUNNING
     */
    public WorkspaceView provision(SandboxWorkspaceId id, Principal caller) {
        return manager.provision(id, caller);
    }

    /**
     * @param id
     *            the workspace
     * @param caller
     *            the principal asking
     * @return the workspace; empty when it does not exist or the caller may not use it — the two answer alike
     */
    public Optional<WorkspaceView> describe(SandboxWorkspaceId id, Principal caller) {
        return manager.describe(id, caller);
    }

    /**
     * @param caller
     *            the principal asking
     * @param query
     *            filters and page
     * @return the caller's own workspaces, ordered by id
     */
    public Page<WorkspaceView, SandboxWorkspaceId> list(Principal caller, WorkspaceQuery query) {
        return manager.listOwned(caller, Objects.requireNonNull(query, "query must not be null"));
    }

    /**
     * Attaches a session to a managed workspace, sets its own unattached rule, or both; both replace what was set
     * before. Only the session's owner may; the workspace must be OPEN or idle-closed, and the session's owner must be
     * able to use it. Each turn is still checked at {@code connect} against the principal actually executing it. A
     * turn already running keeps its binding until it ends; nothing is stopped in the workspace the session leaves.
     *
     * @param session
     *            the session
     * @param update
     *            the attachment and rule
     * @param caller
     *            the session's owner
     * @throws at.aimon.sandbox.binding.BindingRejectedException
     *             without a {@code SessionOwnerLookup}, for an unknown session, a caller that is not its owner, a
     *             malformed id, or a workspace the session's owner may not use; nothing is written
     * @throws at.aimon.sandbox.workspace.SandboxUnavailableException
     *             when the workspace does not exist, is closing or was closed explicitly
     */
    public void setSessionOptions(SessionId session, SessionSandboxOptions.Update update, Principal caller) {
        manager.setSessionOptions(session, update, caller);
    }

    /**
     * Clears a session's attachment and rule: its next turn falls back to the agent default or the unattached rule,
     * and hears that it moved. Hosts call this — or {@code SessionOptionsStore.delete} — when they delete a session.
     *
     * @param session
     *            the session
     * @param caller
     *            the session's owner
     */
    public void clearSessionOptions(SessionId session, Principal caller) {
        manager.clearSessionOptions(session, caller);
    }

    /**
     * @param session
     *            the session
     * @param caller
     *            the session's owner
     * @return its options
     */
    public Optional<SessionOptionsView> sessionOptions(SessionId session, Principal caller) {
        return manager.sessionOptions(session, caller);
    }

    /**
     * Closes the workspace: its sandboxes are destroyed and attached sessions get "workspace closed" until
     * {@link #reopen}. Needs the manage right.
     *
     * @param id
     *            the workspace
     * @param caller
     *            its owner
     */
    public void close(SandboxWorkspaceId id, Principal caller) {
        manager.close(id, caller);
    }

    /**
     * Reopens a CLOSED workspace and provisions it: a managed workspace reopened is meant to be used. Needs the manage
     * right.
     *
     * @param id
     *            the workspace
     * @param caller
     *            its owner
     * @return the workspace, {@code primary} RUNNING
     */
    public WorkspaceView reopen(SandboxWorkspaceId id, Principal caller) {
        manager.reopen(id, caller);
        return manager.provision(id, caller);
    }

    /**
     * Deletes a CLOSED managed workspace for good, then every session's attachment to it; its id is never used
     * again. Needs the manage right. A workspace still OPEN is refused: close it first.
     *
     * @param id
     *            the workspace
     * @param caller
     *            its owner
     */
    public void purge(SandboxWorkspaceId id, Principal caller) {
        manager.purge(id, caller);
    }
}
