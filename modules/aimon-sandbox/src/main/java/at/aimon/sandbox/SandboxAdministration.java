package at.aimon.sandbox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.workspace.AdminWorkspaceQuery;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.Page;
import at.aimon.sandbox.workspace.SandboxView;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SessionOptionsView;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.WorkspaceDetail;
import at.aimon.sandbox.workspace.WorkspaceView;

/**
 * The operator's view of every workspace and sandbox (docs/design/workspace-sandbox-management.md §5.5): list and
 * describe every workspace and the sessions attached to it, list what the provider actually runs with the janitor's
 * verdict, and create on behalf of, attach, terminate, close, reopen and purge.
 *
 * <p>
 * <b>It checks no owner or tenant on the actor.</b> It is not a security boundary inside the JVM — in-process code can
 * always reach the manager — but a separate API surface, built with {@link #of} rather than handed out by
 * {@link WorkspaceSandbox}, which the host exposes only behind its own operator authorization. Every mutating call
 * takes
 * the actor principal and records it on the event ({@code tenant/TYPE:id}, or {@code TYPE:id} when the tenant
 * resolver knows no tenant for it).
 *
 * <p>
 * No view carries a provider sandbox id, credential, seed parameter or endpoint; an operator who needs the raw id has
 * the provider's console.
 */
public final class SandboxAdministration {

    /** The first page of sessions {@link #workspace} returns. */
    public static final int DETAIL_SESSIONS = 100;

    private final SandboxWorkspaceManager manager;

    private SandboxAdministration(SandboxWorkspaceManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    /**
     * @param sandbox
     *            the assembly to administer
     * @return its administration
     */
    public static SandboxAdministration of(WorkspaceSandbox sandbox) {
        return new SandboxAdministration(Objects.requireNonNull(sandbox, "sandbox must not be null").manager());
    }

    /**
     * @param query
     *            filters (state, origin, name prefix, tenant, owner) and page
     * @return every matching workspace, ordered by id
     */
    public Page<WorkspaceView, SandboxWorkspaceId> workspaces(AdminWorkspaceQuery query) {
        return manager.adminWorkspaces(query);
    }

    /**
     * @param id
     *            the workspace
     * @return the workspace and the first {@value #DETAIL_SESSIONS} sessions attached to it; empty when it does not
     *         exist. The record and the sessions are two reads: a purge in between can show the sessions of a
     *         workspace that is already gone (the purge clears them right after)
     */
    public Optional<WorkspaceDetail> workspace(SandboxWorkspaceId id) {
        return manager.adminDescribe(id)
                .map(view -> WorkspaceDetail.of(view, manager.adminSessions(id, Optional.empty(), DETAIL_SESSIONS)));
    }

    /**
     * @param id
     *            the workspace
     * @param after
     *            the page starts after this session
     * @param limit
     *            the page size, at least 1
     * @return the sessions attached to it, ordered by session id
     */
    public Page<SessionOptionsView, SessionId> sessions(SandboxWorkspaceId id, Optional<SessionId> after, int limit) {
        return manager.adminSessions(id, after, limit);
    }

    /**
     * Every sandbox the provider runs for this deployment, matched to its record and given the reconciler's
     * classification before grace ({@code OK}, {@code IN_FLIGHT}, {@code ORPHAN}, {@code STALE},
     * {@code FAILED_LEFTOVER}, {@code DUPLICATE}, {@code SKIP}). Informational: only the janitor destroys. Reads the
     * whole store into memory and the whole listing on every call, uncached — an operator call, not something to
     * poll.
     *
     * @return one view per listed sandbox
     * @throws at.aimon.sandbox.provider.SandboxProviderException
     *             when the provider cannot list
     */
    public List<SandboxView> sandboxes() {
        return manager.adminSandboxes();
    }

    /**
     * Creates a managed workspace owned by {@code owner}, as {@link SandboxWorkspaces#create} would for it: the owner
     * passes the principal gate, and admission, quota and {@code max-managed-per-owner} apply to it. A {@code GROUP}
     * owner is accepted, but under {@code access: principal} no USER caller equals it — give such a workspace
     * {@code access: tenant}.
     *
     * @param request
     *            name, profile and access
     * @param owner
     *            who will own it
     * @param actor
     *            the operator, recorded
     * @return the workspace, {@code primary} RUNNING
     */
    public WorkspaceView create(CreateWorkspace request, Principal owner, Principal actor) {
        return manager.createManaged(request, Objects.requireNonNull(owner, "owner must not be null"),
                Objects.requireNonNull(actor, "actor must not be null"));
    }

    /**
     * Sets any session's options. The session must be known to the {@code SessionOwnerLookup}, and its owner must be
     * able to use the workspace — no session is attached where every turn of its owner would be refused.
     *
     * @param session
     *            the session
     * @param update
     *            the attachment and rule
     * @param actor
     *            the operator, recorded
     */
    public void setSessionOptions(SessionId session, SessionSandboxOptions.Update update, Principal actor) {
        manager.adminSetSessionOptions(session, update, actor);
    }

    /**
     * @param session
     *            the session, known to the {@code SessionOwnerLookup}
     * @param actor
     *            the operator, recorded
     */
    public void clearSessionOptions(SessionId session, Principal actor) {
        manager.adminClearSessionOptions(session, actor);
    }

    /**
     * Deletes any session's options record outright — the cleanup for a session deleted outside core's session router
     * (a raw {@code SessionRecordStore.delete}, a host that never registered
     * {@code WorkspaceSandbox.sessionLifecycleListener()}), which no listener hears of. No owner source is needed: the
     * session may be unknown to every one by now, and a single-tenant host has none. With {@code sessionRecords(...)}
     * wired, the janitor's sweep also deletes such a record once {@code closed-retention} has passed.
     *
     * @param session
     *            the session
     * @param actor
     *            the operator, recorded
     */
    public void deleteSessionOptions(SessionId session, Principal actor) {
        manager.adminDeleteSessionOptions(session, actor);
    }

    /**
     * Terminates one slot's sandbox. The workspace stays OPEN and its next use provisions a new generation with an
     * empty {@code /workspace}; a command running in it fails as "lost". Terminating a FAILED slot clears its
     * recorded failure and backoff, so the next use retries at once. A slot already TERMINATED is left alone; a
     * PROVISIONING one is refused — terminated under its claimer, it would leave an orphan and provision again.
     *
     * @param id
     *            the workspace
     * @param slot
     *            the slot
     * @param actor
     *            the operator, recorded
     * @throws at.aimon.sandbox.workspace.SandboxUnavailableException
     *             when the workspace or the slot does not exist, or the slot is provisioning
     */
    public void terminateSlot(SandboxWorkspaceId id, String slot, Principal actor) {
        manager.adminTerminateSlot(id, slot, actor);
    }

    /**
     * @param id
     *            the workspace
     * @param actor
     *            the operator, recorded
     */
    public void close(SandboxWorkspaceId id, Principal actor) {
        manager.adminClose(id, actor);
    }

    /**
     * Reopens a CLOSED workspace and provisions it.
     *
     * @param id
     *            the workspace
     * @param actor
     *            the operator, recorded
     * @return the workspace, {@code primary} RUNNING
     */
    public WorkspaceView reopen(SandboxWorkspaceId id, Principal actor) {
        return manager.adminReopen(id, actor);
    }

    /**
     * Deletes a CLOSED managed workspace for good, then every session's attachment to it.
     *
     * @param id
     *            the workspace
     * @param actor
     *            the operator, recorded
     */
    public void purge(SandboxWorkspaceId id, Principal actor) {
        manager.adminPurge(id, actor);
    }
}
