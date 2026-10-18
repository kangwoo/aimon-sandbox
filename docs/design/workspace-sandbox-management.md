# Workspace management design — create a sandbox first, attach sessions to it, administer all of them

> Status: **APPROVED r3** — revised after reviews 1 and 2 (§13 and §14 map every finding to its resolution). Once approved, the
> architecture document [`workspace-sandbox.md`](workspace-sandbox.md) (below: **WS**) absorbs the departures of §8 in
> the implementing branches, as it did for steps 3 and 4.
>
> Since aimon-core 0.3.3, three workarounds below are replaced by core seams; §17 lists what changed and points to
> [`workspace-sandbox-core-033.md`](workspace-sandbox-core-033.md). The body is left as approved.

> Scope: a management layer on top of the step-3/4 domain — explicit workspace creation with immediate provisioning,
> per-session sandbox options (attachment and the unattached rule), an agent-definition default workspace, and an
> administration API. It does **not** pull in anything of WS §18 items 5+ (multiple slots, shared volumes, seed,
> orchestrator tools, JDBC store, pause/resume, snapshots).
> Authority: WS is the architecture; [`workspace-sandbox-step3.md`](workspace-sandbox-step3.md) (**S3**) and
> [`workspace-sandbox-step4.md`](workspace-sandbox-step4.md) (**S4**) are the patterns this document follows.
>
> Grounding: every existing name below was checked against `main` at `d3e2769` (aimon-core 0.3.2) —
> `WorkspaceSandbox`, `SandboxWorkspaceManager` (`connect` → `loadOrCreate`, `close`, `reopen`, `closeProcedure`,
> package-private `terminateSlot`, private `checkOwner`), `SandboxJanitor` (idle close, `deleteTombstone` deleting every
> CLOSED record past `closedRetention`), `SandboxReconciler` (package-private static `classify` → `Verdict`
> `SKIP · OK · IN_FLIGHT · ORPHAN · STALE · FAILED_LEFTOVER · DUPLICATE`), `SandboxBindingPolicy`,
> `DefaultSandboxBindingPolicy`, `BindingContext`, `SandboxBinding` (`toBuilder`), `SlotChoice`, `CallerResolver`,
> `SessionOwnerLookup`, `SandboxExecutionEnvironmentProvider` (`bindRoot`, `bindFork` — both rebuild the binding via
> `toBuilder`), `SandboxExecutionEnvironment` / `PendingNotices` (created privately per environment),
> `SandboxWorkspaceStore` (+ `deletedVersionFloor` no-ABA rule), `WorkspaceScan` (`next()` rebuilds by field),
> `SandboxWorkspace`, `SandboxEvent`, `SandboxLabels`, `SandboxProvider.list`, `SandboxSettings`
> (`WorkspaceAccess` lives here), `SandboxStartupValidator`, `ShellWrapper` (sources the session's state file), and
> core's `EnvironmentRequest` (0.3.2: no per-request attributes).

---

## 1. Problem, restated

Today a sandbox exists only as a side effect: the first `Bash` or file tool call of a session runs
`SandboxWorkspaceManager.connect()`, which creates the record `ws:{sessionId}` and provisions `primary` (WS §10.1). An
application cannot

1. **create a sandbox before any agent runs** and know it is up;
2. **attach a session to that sandbox** — the only route is to inject a whole `SandboxBindingPolicy` and keep the
   session→workspace mapping, and every owner check, itself;
3. **give an agent a default sandbox** that its sessions use unless a session says otherwise;
4. **choose, per execution, what happens when nothing is attached** — today it always gets its own `ws:{sessionId}`;
5. **see and act on all sandboxes** — every workspace, the sandboxes the provider actually runs, the sessions attached
   to a workspace — and stop, close or purge any of them as an operator.

Requested decisions (from the user): attachment is **per session**, with an **agent-defined default**; creation
**provisions immediately**; the unattached rule is an **execution option**; and an **administrator API** covers
"every sandbox" and "per workspace" listings.

**How "execution option" is met.** core 0.3.2's `EnvironmentRequest` carries no per-request attributes, so nothing can
travel *inside* an execution request. The finest unit this module can key on without changing core is the **session**
(for main turns) — so the option is a per-session setting the application writes before (or between) runs, stored with
the session's attachment (§5.2). Routines have no session and an execution id that is not known before the run; they
get the agent default and the deployment floor only. A truly per-request option needs core to carry request attributes
(§12 Q1).

---

## 2. Boundaries

**In** (two PRs, §11): `SandboxWorkspaces` (owner-facing: create, describe, list own, set/clear session options,
close, reopen, purge); `SandboxAdministration` (operator: list and describe every workspace, list sessions of a
workspace, list provider sandboxes with the reconciler's verdict, create on behalf of, set/clear session options,
terminate a slot, close, reopen, purge); the `SessionOptionsStore` SPI with an in-memory implementation and a contract
suite; `SessionOptionsBindingPolicy` (session options → agent default → unattached rule); the `sandbox.workspace` and
`sandbox.unattached` definition attributes; the `unattached`, `allow-shared-workspaces` and `max-managed-per-owner`
settings; per-workspace access with use/manage separation; workspace-switch notices; event `actor`; WS updates (§8).

**Not in**: an HTTP/REST endpoint or Spring starter; multiple slots (WS §18-5); a workspace-scoped seed (WS §20);
**persistent files** — until shared volumes (step 5) a slot's `/workspace` disappears when its sandbox is terminated
(§6.6); asynchronous creation (§12 Q3); a JDBC store (step 6); per-execution options for routines (§12 Q1).

---

## 3. Approach, and what was rejected

### 3.1 Chosen approach

- **The manager stays the only writer of workspace records.** The two facades call new manager entry points; every
  rule of WS §10 — CAS, owner check before state, admission, quota — applies unchanged.
- **Managed ids are generated, never chosen.** `create` mints `wsm:{UUID}`; the caller supplies a free-text `name` for
  display and listing. An id is never reused, so nothing that refers to one — a session's options, an agent definition
  — can be redirected to a different workspace later, and no id can be squatted or probed across tenants.
- **A managed workspace's record is removed only by `purge`.** Idle close still applies (it destroys sandboxes, keeps
  the record CLOSED with cause `idle`, and the next use reopens it, WS §10.5) — but the janitor never deletes a
  `MANAGED` CLOSED record. Ending one for good is an explicit owner or admin `purge`.
- **Creation = record + the §10.1 state machine from step 2 on**, with a binding that may not create (`mustExist`), so
  there is exactly one provisioning path.
- **Session options are a separate store, keyed by session.** A workspace may have unbounded sessions; the workspace
  record is the hottest CAS document and must not grow with them.
- **Binding resolution is a decorator policy** around whatever `SandboxBindingPolicy` the host configured. "Only a
  policy makes bindings" (WS §8.1) still holds.
- **Use and manage are different rights.** Using a workspace (binding to it, being attached to it, describing it)
  follows its access mode; managing it (close, reopen, purge) is always the owner principal's (or an admin's).
- **Redirecting a session is a right over the session**, not over the workspace: only the session's owner (or an
  admin) sets its options.
- **Administration is a separately constructed object**, `SandboxAdministration.of(WorkspaceSandbox)`, never a getter
  on `WorkspaceSandbox` (which every integration holds). It is not a security boundary inside the JVM — in-process code
  can always reach the manager — it is a **separate API surface** the host exposes only behind its own operator
  authorization. Every mutating call takes the actor principal, recorded in the event.

### 3.2 Rejected alternatives

- **Caller-chosen ids** (draft r1) — global, guessable ids allow cross-tenant squatting, an existence oracle, and, after
  a tombstone expires, re-creating an id that agent definitions still name (review B2, M1).
- **Attachment as a field of the workspace record** — unbounded growth, CAS contention, no lookup by session.
- **The host keeps the mapping and injects a policy (status quo)** — every host re-implements the owner checks; the
  admin API could not list a workspace's sessions.
- **Per-request option through core** — needs a core change (§12 Q1).
- **`admin-principals` setting** — a principal id is not a role; the host's auth layer already decides.
- **A third classification for admin listings** (draft r1's TRACKED/STALE/UNTRACKED) — it disagreed with the
  reconciler's verdicts and called an in-flight create "STALE" (review M9). The admin view shows the reconciler's own
  `Verdict`.

---

## 4. Concrete changes by file

### 4.1 `aimon-sandbox` — `at.aimon.sandbox.workspace`

| File | Change |
|------|--------|
| `SandboxWorkspace` | new fields `origin` (`WorkspaceOrigin`, default `DERIVED`), `name` (`Optional<String>`, managed only), `profile` (`Optional<String>`, managed only — the profile `create` asked for, kept even when no slot exists yet), `access` (`Optional<SandboxSettings.WorkspaceAccess>`; absent = deployment setting) — the record already depends on the root package's settings types through the manager; no enum move |
| `WorkspaceOrigin` (new) | `DERIVED`, `MANAGED` |
| `SandboxWorkspaceManager` | new `createManaged(...)`, `provision(id, caller)`, `purge(id, caller)`; `checkOwner` split into `checkUse` (record access, else setting) and `checkManage` (owner principal); `close`/`reopen` use `checkManage`; `connect` uses `checkUse` and refuses to create when `binding.mustExist()`; `terminateSlot` gets an admin entry point; `view(...)` builders |
| `SandboxJanitor` | `deleteTombstone` skips `MANAGED` records; new sweep deleting session options whose workspace record is gone (after `purge`) |
| `SandboxReconciler` | `classify` and `Verdict` made reachable by the admin facade (package-private → public-for-library, Javadoc "internal") |
| `WorkspaceScan` | new filters `owner(WorkspaceOwner)`, `origin(WorkspaceOrigin)`; `next()` carries them |
| `InMemorySandboxWorkspaceStore` | honours the new filters |
| `SandboxEvent` | new types `WORKSPACE_CREATED`, `WORKSPACE_PURGED`, `SESSION_OPTIONS_SET`, `SESSION_OPTIONS_CLEARED`; new `Optional<SessionId> session()`; `workspaceId` and `owner` become optional **for the two session types only** (the builder still requires them for every other type); new `Optional<String> actor()` (`TYPE:id`, plus the actor's tenant when the resolver knows it). Admin terminate and close reuse `TERMINATED` / `WORKSPACE_CLOSED` with `actor` and `cause` |
| `SessionSandboxOptions` (new) | §5.2 |
| `SessionOptionsStore` (new SPI), `InMemorySessionOptionsStore` (new) | §5.2 |
| `WorkspaceView`, `SlotView`, `SessionOptionsView`, `SandboxView`, `WorkspaceDetail`, `Page<T>`, `WorkspaceQuery` (new) | §5.4 |
| `CreateWorkspace` (new) | §5.1 |
| `WorkspaceProvisioningException` (new) | `extends SandboxUnavailableException`, carries the new workspace's id |

### 4.2 `aimon-sandbox` — `at.aimon.sandbox.binding`

| File | Change |
|------|--------|
| `SandboxBinding` | new `mustExist` (default `false`) and `Optional<WorkspaceSwitch> workspaceSwitch` (§6.4); **`toBuilder()` copies both** |
| `SessionOptionsBindingPolicy` (new) | the decorator (§6.3) |
| `UnattachedRule` (new) | `PER_SESSION` (today), `REJECT`; `strictest(a, b)` |
| `SlotChoice` | constants `WORKSPACE_ATTRIBUTE = "sandbox.workspace"`, `UNATTACHED_ATTRIBUTE = "sandbox.unattached"` |
| `ManagedIds` (new) | the one home of the `wsm:` id format and its validation |

### 4.3 `aimon-sandbox` — `at.aimon.sandbox.environment`

| File | Change |
|------|--------|
| `SandboxExecutionEnvironmentProvider` | `bindRoot`/`bindFork` keep `mustExist` and `workspaceSwitch` (forks inherit `mustExist`, never a switch); Javadoc "at most one store read" becomes "one workspace-store read and, for a session, one options-store read" |
| `SandboxExecutionEnvironment` | on its first successful `connect`, when the binding has a `workspaceSwitch`, adds the switch notice to its `PendingNotices` and records the session's `lastWorkspaceId` (§6.4) |

### 4.4 `aimon-sandbox` — root package

| File | Change |
|------|--------|
| `SandboxWorkspaces` (new) | owner-facing facade (§5.1), `WorkspaceSandbox.workspaces()` |
| `SandboxAdministration` (new) | `SandboxAdministration.of(WorkspaceSandbox)` (§5.5) |
| `WorkspaceSandbox` | builder `sessionOptionsStore(...)` (default in-memory); wraps the configured policy in `SessionOptionsBindingPolicy`; package-private accessors the admin factory uses |
| `SandboxSettings` | `unattached` (`UnattachedRule`, default `PER_SESSION`), `allowSharedWorkspaces` (default `false`), `maxManagedPerOwner` (default 20, `UNLIMITED` allowed) |
| `SandboxStartupValidator` | `max-managed-per-owner` is ≥ 1 or `UNLIMITED` (as `max-running-per-tenant`); owner-facing session options are refused at call time, not at startup, without a `SessionOwnerLookup` (§6.2) |

### 4.5 `aimon-sandbox-testkit`

`SessionOptionsStoreContract` (no version reuse after delete, stable `byWorkspace` paging, idempotent
`deleteByWorkspace`). `SandboxWorkspaceStoreContract` gains `scanPagesKeepOwnerAndOriginFilters` (more than one page,
mixed owners).

### 4.6 Docs

WS §5.1, §8.1–§8.4, §10.1, §10.4, §10.5, §13.2, §14, §15, §16, §20, §21 per §8; README ("Managing workspaces", the
trust-domain warning); module README; CHANGELOG `[Unreleased]`.

---

## 5. Data and interface shapes

### 5.1 `SandboxWorkspaces` — owner-facing

```java
public final class SandboxWorkspaces {
    WorkspaceView create(CreateWorkspace request, Principal caller);      // provisions primary before returning
    WorkspaceView provision(SandboxWorkspaceId id, Principal caller);     // retry after a failed create (use right)
    Optional<WorkspaceView> describe(SandboxWorkspaceId id, Principal caller);   // use right; empty if not permitted
    Page<WorkspaceView> list(Principal caller, WorkspaceQuery query);     // caller-owned only
    void setSessionOptions(SessionId session, SessionSandboxOptions.Update update, Principal caller);
    void clearSessionOptions(SessionId session, Principal caller);
    Optional<SessionOptionsView> sessionOptions(SessionId session, Principal caller);
    void close(SandboxWorkspaceId id, Principal caller);                  // manage right
    WorkspaceView reopen(SandboxWorkspaceId id, Principal caller);        // manage right, then provision
    void purge(SandboxWorkspaceId id, Principal caller);                  // manage right; CLOSED only
}

public final class CreateWorkspace {           // builder
    String name();                             // required, 1..80 printable chars; not unique
    Optional<String> profile();                // absent -> settings.defaultProfile()
    Optional<WorkspaceAccess> access();        // absent -> deployment setting; TENANT needs allow-shared-workspaces
}
```

- `create` always mints a new id; repeating a call creates a second workspace. A caller that lost the response finds it
  by `list` (name, `createdAt`) — §12 Q3 covers an idempotency key.
- `describe` of a workspace the caller may not use answers empty, like a missing one (WS §15).
- `reopen` provisions: a managed workspace reopened is meant to be used.
- The user-supplied `name` never appears in a model-facing string (notices, tool errors) — only the id does.

### 5.2 Session options — record and SPI

```java
public final class SessionSandboxOptions {
    SessionId session();
    WorkspaceOwner setBy();
    Optional<SandboxWorkspaceId> workspace();        // the attachment
    Optional<UnattachedRule> unattached();           // the per-session rule
    Optional<SandboxWorkspaceId> lastWorkspaceId();  // where this session last connected (§6.4)
    Instant updatedAt();
    long version();
}

public interface SessionOptionsStore {
    Optional<SessionSandboxOptions> find(SessionId session);
    SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expectedVersion); // empty: must not exist
    void delete(SessionId session, long expectedVersion);
    Page<SessionSandboxOptions> byWorkspace(SandboxWorkspaceId id, Optional<SessionId> after, int limit);
    Page<SandboxWorkspaceId> workspaces(Optional<SandboxWorkspaceId> after, int limit);  // janitor sweep
    int deleteByWorkspace(SandboxWorkspaceId id);    // idempotent; clears `workspace`, keeps the record's other fields
}
```

CAS semantics match `SandboxWorkspaceStore`, **including its no-ABA rule**: a record created again after `delete` never
reuses an old version. Like `InMemorySandboxWorkspaceStore`, the in-memory store keeps **one global** version floor
rather than remembering every deleted key. `byWorkspace` indexes only records whose `workspace` is
present and is ordered by session id.

### 5.3 `SandboxBinding.mustExist`

A binding that names a workspace it did not derive — session options or an agent default — must not create it.
Otherwise `connect()` step 1 (`loadOrCreate`) would re-create a purged managed id with the **binding's** owner
(the caller). With `mustExist`, a missing record answers `SandboxUnavailableException("sandbox workspace <id> does not
exist")` and the store stays untouched. Because the environment provider rebuilds every binding through `toBuilder()`
(`bindRoot`, `bindFork`), `toBuilder()` copies `mustExist`, and forks inherit it.

### 5.4 Views

```
WorkspaceView(id, name?, owner, origin, access, state, closeCause?, createdAt, lastActivityAt, slots: List<SlotView>)
SlotView(name, profile, state, generation, seeded, failure?: (kind, step, message), lastActivityAt)
SessionOptionsView(session, setBy, workspace?, unattached?, updatedAt)
WorkspaceDetail(workspace: WorkspaceView, sessions: Page<SessionOptionsView>)
SandboxView(refHash, providerState, expiresAt?, createdAt?, workspaceId?, slot?, generation?, verdict)
WorkspaceQuery(states?, origin?, name? (prefix), after?, limit ≤ 500)            -- owner-facing
AdminWorkspaceQuery = WorkspaceQuery + (tenant?, owner?)
Page<T>(items, next?)
```

**No view carries a provider sandbox id.** WS §13.3: execd checks no token, so the id plus network reach is root in the
sandbox. Owner views show slot state and generation only; the admin `SandboxView` identifies a sandbox by
`refHash = SandboxLabels.h(ref.sandboxId())` and, when the labels resolve, by workspace + slot + generation. An operator who
needs the raw id has the provider console. Views carry no credentials, seed parameters or endpoints either.

### 5.5 `SandboxAdministration` — operator

```java
public final class SandboxAdministration {
    static SandboxAdministration of(WorkspaceSandbox sandbox);
    Page<WorkspaceView> workspaces(AdminWorkspaceQuery query);
    Optional<WorkspaceDetail> workspace(SandboxWorkspaceId id);
    Page<SessionOptionsView> sessions(SandboxWorkspaceId id, Optional<SessionId> after, int limit);
    List<SandboxView> sandboxes();                                       // §6.8
    WorkspaceView create(CreateWorkspace request, Principal owner, Principal actor);
    void setSessionOptions(SessionId session, SessionSandboxOptions.Update update, Principal actor);
    void clearSessionOptions(SessionId session, Principal actor);
    void terminateSlot(SandboxWorkspaceId id, String slot, Principal actor);
    void close(SandboxWorkspaceId id, Principal actor);
    WorkspaceView reopen(SandboxWorkspaceId id, Principal actor);
    void purge(SandboxWorkspaceId id, Principal actor);
}
```

No owner or tenant check on the actor. But:

- `create` runs `owner` through the principal gate (`CallerResolver`), so no workspace is owned by a principal the
  deployment refuses; admission, quota and `max-managed-per-owner` apply to the owner. A `GROUP` owner is accepted, but
  under `principal` access no USER caller equals it — such a workspace needs `access: tenant` (one sentence in the
  Javadoc).
- `setSessionOptions` with a workspace runs the **consistency check** of §6.2 step 4 (the session's owner must be able
  to use it); an admin cannot create a session that fails every turn.

---

## 6. Mechanisms

### 6.1 Create and provision

```
create(request, owner, actor):
1. who = callers.callerOf(owner)                                  -- principal gate
2. validate name; profile must be configured; access TENANT needs allow-shared-workspaces
   (or the deployment already being `tenant`); PRINCIPAL is always allowed (narrowing)
3. max-managed-per-owner: scan(owner=who, origin=MANAGED, states=all).count < limit  -- best effort, like admission
4. id = "wsm:" + UUID
   store.createIfAbsent(OPEN, owner=who, origin=MANAGED, name, access, quota, createdAt=lastActivityAt=now)
   emit WORKSPACE_CREATED(actor)
5. provision(id, who)
6. return view(id)

provision(id, caller):
   record must exist; checkUse(record, caller)
   manager-internal provisionSlot(id, primary, record.profile, caller): the Connect loop of WS §10.1 with
   mustExist=true and requiredProfile=record.profile -- admission, quota, create, verify, seed
```

A failure in step 5 keeps the record (slot FAILED, or absent when admission refused) and throws
`WorkspaceProvisioningException(id, reason)`. The caller retries with `provision(id)` (respecting the recorded
failure's backoff, WS §10.1) or `purge`s it. The wait is bounded by `provisionTimeout` (default 5 minutes). A record
refused by admission counts toward `max-managed-per-owner` until purged.

`Connect` never runs a command, so `provision` does not build a `SandboxBinding` (whose shell key is mandatory): the
manager gets an internal entry `provisionSlot(id, slot, profile, caller)` that drives the same `Connect` loop. The
record's `profile` is also the **required profile of rows 1–2 of §6.3** when the definition names none, so a lazy bind
after a refused first provisioning builds the profile that was asked for, not the default.

### 6.2 Session options

```
setSessionOptions(session, update, caller):          -- owner-facing
1. who = callerOf(caller)
2. no SessionOwnerLookup configured -> refuse ("session options need a session owner lookup")
3. sessionOwner = lookup.ownerOf(session) or refuse ("unknown session")
   who must equal sessionOwner by tenant AND principal, whatever any access mode says     -- right over the session
4. update.workspace present:
     ws = store.find(id) or unavailable; must be OPEN or CLOSED(idle)
     checkUse(ws, who) and checkUse(ws, sessionOwner)                                     -- consistency
5. put(options) with CAS (re-read and re-apply on conflict); emit SESSION_OPTIONS_SET(actor=who)
```

The admin path skips step 3's equality (an operator may act on any session) but keeps steps 2–4's lookup and
consistency check. `clearSessionOptions` deletes the record's `workspace` and `unattached` (keeping `lastWorkspaceId`
for §6.4). Neither call touches a sandbox: a workspace the session leaves keeps running until its own idle policy ends
it. A turn already running keeps its binding until it ends, and background commands it started keep running in the old
workspace.

**Which principal is checked.** Options are set by the session's **owner**. Each turn is still checked at `connect`
against the **executing** principal (WS §8.3) — in a multi-participant session a non-owner's turn into an attached
workspace passes only if that participant may use it. The Javadoc says so; a test covers it.

**Hosts delete options with their sessions** (`clearSessionOptions`, or `SessionOptionsStore.delete`) — otherwise a
deleted session's options stay until its workspace is purged.

### 6.3 Binding resolution (`SessionOptionsBindingPolicy`)

For a root request (forks are unchanged — they inherit the parent's binding, WS §8.2):

| Order | When | Workspace | owner / shellKey | `mustExist` |
|-------|------|-----------|------------------|-------------|
| 1 | session has options with a `workspace` | that one | caller; `session:{id}` | yes |
| 2 | agent definition has `sandbox.workspace` | that one (must be a `wsm:` id) | caller; `session:{id}` or `exec:{id}` | yes |
| 3 | effective rule `PER_SESSION` | delegate policy (default: `ws:{sessionId}` / `ws:{executionId}`) | delegate's | no |
| 3 | effective rule `REJECT` | — | `BindingRejectedException("this session is not attached to a sandbox workspace")` | — |

**The effective unattached rule** has three layers:

```
rule = strictest(settings.unattached, agent attribute sandbox.unattached, session option unattached)
```

Each layer can only tighten the one above it: the deployment setting is the floor; an agent definition (whose author
may be a tenant in some hosts) cannot switch the operator's `reject` off; and a session's owner, through the host's
API, chooses per session within what the operator and the agent allow. With the floor and the agent at `per-session`
(the defaults), the session option fully decides — that is the "execution option". "Session option" means the record's
`unattached` field; a record holding only `workspace` or `lastWorkspaceId` contributes nothing to the rule.

- `setSessionOptions`' `workspace` and `sandbox.workspace` are both validated by `ManagedIds` (`wsm:` ids only —
  derived ids are reused after tombstone expiry, so an option must never name one) ; a malformed value rejects the bind with a message
  naming the definition. The owner check still applies at `connect` (`checkUse`).
- `sandbox.slot` and `sandbox.profile` keep their meaning in rows 1–2; a profile that differs from the existing slot's
  is refused by `connect` as today (WS §8.3).
- A routine typically runs as `Principal.system()`: it passes the gate only via `allowed-system-principals`, and row 2
  then needs the managed workspace owned by that system principal or `access: tenant` with the principal's tenant. The
  Javadoc of `sandbox.workspace` says so; a test covers it.
- Every root request with a session now reads the options store once. A store failure fails the bind
  (`SandboxUnavailableException`) — no silent fallback to `PER_SESSION`, which would put an attached user in a different
  workspace.

### 6.4 Workspace-switch notice

The policy compares the workspace it binds (any row) with the session's `lastWorkspaceId`. When the session has an
options record and the two differ, the binding carries `WorkspaceSwitch(from?, to)`. The environment, after its first
successful `connect`, adds one notice — "this session now runs in sandbox workspace `<to>`; files and shell state from
the previous workspace are not here" — and CAS-writes `lastWorkspaceId = to` (a lost CAS only repeats the notice once).
This covers attach, re-attach, clear (falling back to `ws:{sid}` or the agent default) and agent-default changes with
one rule, and writes only when the workspace actually changed. A session with no options record has never been
redirected and gets no notice. Forks never carry a switch.

- When `from` is absent and the record has no `workspace` (it so far only set `unattached`), the switch is recorded
  silently, whatever row bound it — the delegate's derived id or an agent default: nothing moved.
- A hook command's result takes no pending notice: the switch waits for the model's own next command.
- `clearSessionOptions` keeps the record with `lastWorkspaceId` set to the workspace it detaches from when none was
  recorded yet (a turn of file tools only), so the move back is still announced.
- The `lastWorkspaceId` write happens **after the notice is drained** into a command result (`PendingNotices` drains on
  `Bash`), so a turn that only used file tools leaves it unwritten and the next `Bash` still announces the switch.
- The write is best-effort: a store error or a lost CAS is logged and never fails the tool call (worst case: the notice
  repeats once).

### 6.5 Use and manage rights

| Right | Rule | Entry points |
|-------|------|--------------|
| use | same tenant; and `record.access().orElse(settings.workspaceAccess())` is `TENANT`, or the principal equals the owner's | `connect`, `provision`, `describe`, being the target of session options |
| manage | `MANAGED` records: same tenant and principal equal to the owner's, whatever the access mode. `DERIVED` records: unchanged — the use rule (WS §8.3 behaviour, `OwnerCheckTest`) | `close`, `reopen`, `purge` |

`access: tenant` on a record is a **sharing** decision: in a `principal` deployment it requires
`allow-shared-workspaces: true`. (In a `tenant` deployment every workspace is already shared within the tenant; the
setting adds nothing there.) Narrowing to `principal` in a `tenant` deployment is always allowed. Existing
`DERIVED` records have no `access` and keep the deployment setting.

**A shared workspace is one trust domain** (WS §8.4). Every session in it can read and change every file — including
the other sessions' shell state under `/workspace/.aimon-shell/`, which `ShellWrapper` sources before each command — and
uses the same profile credentials. Sharing across principals (several users attached to one `access: tenant`
workspace, or an agent default used by many users) is therefore a decision the operator opts into
(`allow-shared-workspaces` in `principal` deployments; implicit in `tenant` deployments), and README, WS §8.4 and
WS §21 say it in these words.

**Writing `sandbox.workspace` is the right to redirect every session of that agent** (§6.3 row 2). Another principal's
session can only land there if the workspace is shared — which, in a `principal` deployment, the owner had to opt into
under `allow-shared-workspaces`. Hosts where tenants author definitions should know this; WS §21 says it.

### 6.6 Lifecycle of managed workspaces

- **Files do not persist yet.** Slots follow the profile's `terminateAfter`: a created-but-unused sandbox ends after
  `terminateAfter` from creation, and the next use provisions a new generation — with an empty `/workspace` and the
  existing "recreated" notice. Persistent files come with shared volumes (WS §18-5). README states this.
- **Idle close applies** (`closeAfter`, WS §10.2): it destroys nothing more than idle termination already did, and the
  next use reopens the workspace (WS §10.5). **The janitor never deletes a `MANAGED` CLOSED record.** Only `purge`
  removes it.
- **`purge(id)`**: `MANAGED` records only (a `DERIVED` tombstone keeps `closedRetention`, or the session's next turn
  would silently re-create `ws:{sid}` — WS §10.5); manage right (owner) or admin; the record must be CLOSED (close
  first). It deletes the record
  (`store.delete` with the read version; a concurrent reopen makes it fail and nothing else happens), emits
  `WORKSPACE_PURGED`, then `deleteByWorkspace(id)`. If that second step fails, the janitor's sweep finishes it: it pages
  `SessionOptionsStore.workspaces()` and clears `workspace` on options whose record no longer exists. Ids are never
  reused, so the two writes cannot hit a different workspace in between.

### 6.7 Admin terminate

`terminateSlot(id, slot, actor)` runs the manager's `terminateSlot` (CAS to TERMINATED, then destroy) with cause
`"terminated by administrator"`; the existing `TERMINATED` event carries `actor`. The workspace stays OPEN; the next use
provisions a new generation (WS §5.2). A running command fails with "sandbox lost during command" (WS §15).

### 6.8 Listing every sandbox

`sandboxes()` reads the store first (`scan`, all states, paged to the end), then
`provider.list({managed: true, deployment: settings.deployment()})` — the reconciler's order (WS §10.4). Each provider
sandbox is matched to its record by the `aimon.at/workspace` label (`h(id)`) and given
`SandboxReconciler.classify(record, sandbox)`'s `Verdict` (no record → `ORPHAN`). For what the janitor destroys only
after `orphan-grace` — `ORPHAN`, `DUPLICATE`, and an `IN_FLIGHT` sandbox ahead of its record's generation — the view adds
`Optional<Boolean> pastGrace` — present only when the provider reports `createdAt` (the reconciler's own `firstSeen` is
node-local and not visible to the facade). The listing is informational; only the
janitor destroys. A `provider.list` failure fails the call (no partial view that hides sandboxes). Cost is
O(records + sandboxes) per call, uncached — an operator call, not a hot path.

---

## 7. Failure modes

| Case | Result |
|------|--------|
| `create` with a bad name, unknown profile, or `TENANT` access without `allow-shared-workspaces` | `IllegalArgumentException` / `SandboxConfigurationException` before any write |
| `create` over `max-managed-per-owner` | `SandboxUnavailableException` naming the limit; nothing written |
| admission or quota refuses the first provisioning | `WorkspaceProvisioningException(id)`; record kept, slot absent |
| provisioning fails | `WorkspaceProvisioningException(id)`; slot FAILED with the recorded failure; `provision(id)` retries |
| the host's HTTP call times out during `create` | provisioning continues; the caller finds the workspace by `list` |
| an event listener throws during `create` | swallowed and logged, as every `emit` (WS §14) |
| `setSessionOptions` without a `SessionOwnerLookup`, for an unknown session, or by a non-owner | `BindingRejectedException`; nothing written |
| `setSessionOptions` to a CLOSING / explicitly CLOSED / missing workspace | `SandboxUnavailableException` |
| `setSessionOptions` to a workspace the session's owner may not use (admin path too) | `BindingRejectedException` |
| attached session after an explicit close | tool error "workspace closed" until reopen |
| attached session after purge | `mustExist` → "does not exist" until the sweep clears the option; then the unattached rule |
| `sandbox.workspace` names a purged or malformed id | every root request of that agent: "does not exist" / "invalid" |
| options store unavailable | root bind of a session fails with `SandboxUnavailableException` |
| two `setSessionOptions` race | CAS — the loser re-reads and re-applies; last writer wins; both emit |
| `purge` of an OPEN workspace | refused ("close it first") |
| `purge` racing a `reopen` | the delete's CAS fails; the workspace stays; `purge` reports it |
| `provider.list` fails in `sandboxes()` | the call fails with the provider error |
| `create` of a profile whose `terminateAfter` is short | allowed; README and `WorkspaceView` (slot state) make the termination visible |

---

## 8. Departures from WS (folded in the implementing branches)

| # | WS | Departure |
|---|----|-----------|
| D1 | §5.1 "workspace id is deterministic, so no mapping store is needed" | true for derived workspaces; managed ones are reached through session options or a definition attribute |
| D2 | §8.2 default policy is the only resolution | `SessionOptionsBindingPolicy` resolves first; a routine may bind a managed workspace instead of `ws:{executionId}` |
| D3 | §8.3 one deployment-wide `workspace-access`, one owner check | per-workspace access; use vs manage rights |
| D4 | §8.4 "same sandbox = same trust domain" | unchanged, now stated for cross-principal sharing, which needs `allow-shared-workspaces` |
| D5 | §10.1 step 1 always `createIfAbsent` | not for `mustExist` bindings |
| D6 | §10.4 tombstones expire after `closedRetention` | not for `MANAGED` records; `purge` instead |
| D7 | §10.1 "세션을 여는 시점에 만들지 않는다" | still true for sessions; managed creation provisions eagerly by request |
| D8 | §7 / provider Javadoc "at most one store read" | plus one options-store read per session turn |

---

## 9. Test strategy

### 9.1 Default tier

- **Binding**: `SessionOptionsBindingPolicyTest` — the §6.3 order; the three-layer rule (floor × agent × session,
  including "agent cannot loosen the floor" and "session cannot loosen the agent"); `REJECT`; malformed
  and `ws:` defaults; forks untouched. `bindRootKeepsMustExist`, `forkOfAttachedSessionKeepsMustExist`,
  `connectWithMustExistOnMissingRecordCreatesNothing` (store stays empty).
- **Create**: provisions and returns RUNNING; two creates → two ids; `max-managed-per-owner`; admission refusal keeps
  the record and counts; failure → `provision` retries; `TENANT` without opt-in refused.
- **Rights matrix**: {`setSessionOptions`, `clearSessionOptions`, `sessionOptions`, `describe`, `close`, `reopen`,
  `purge`} × {owner, tenant-mate, other tenant, anonymous} × deployment {`principal`, `tenant`} × record access {absent,
  `principal`, `tenant`}. Empty `access` falls back to the setting (existing `DERIVED` records). Non-owner participant's
  turn after an attach. SYSTEM routine with an agent default.
- **Notices**: attach → one notice; repeated turns → none; clear → notice on fallback; no options → none.
- **Lifecycle**: janitor never deletes a `MANAGED` CLOSED record; idle-closed managed workspace reopens on use; purge
  deletes record then options; sweep clears options of a purged id after a simulated `deleteByWorkspace` failure.
- **Views**: a serialized `WorkspaceView` / `SandboxView` contains no provider id.
- **Admin**: listing filters and paging; `sandboxes()` verdicts with a PROVISIONING generation (`IN_FLIGHT`), an
  incarnation mismatch (`ORPHAN`), an older generation (`STALE`) and a label mismatch (`SKIP`), checked against
  `SandboxReconcilerTest` fixtures; terminate; events carry `actor`.
- **Stores**: `InMemorySessionOptionsStoreTest extends SessionOptionsStoreContract`; the scan-filter paging contract.
- **Races** (`MultiNodeScenariosTest` style): `setSessionOptions` vs `close`; `purge` vs `reopen`; two
  `setSessionOptions`; notice flip vs re-attach.

### 9.2 Docker tier

`ManagedWorkspaceSandboxIT`: create → RUNNING and listed with verdict `OK`; two sessions of one owner attached — shared
files, separate cwd; admin terminate → next command re-provisions; close → "workspace closed"; purge → "does not
exist". One documented-behaviour test: a session can read another session's shell state file in a shared workspace
(§6.5).

### 9.3 End-to-end

`OrcaRuntimeSandboxE2ETest`: an agent with `sandbox.workspace` runs `Bash` in the pre-created sandbox; session options
of another workspace override it; with `sandbox.unattached: reject` an unattached session's tool call reports the
rejection; a session option `per-session` lets it run where neither the floor nor the agent tightens, and cannot lift
an agent's `reject`.

---

## 10. Commit sequence

**PR 1 — create, session options, agent default**
1. `SessionOptionsStore` + in-memory + contract; record fields `origin`/`name`/`access`; scan filters and `next()`.
2. `mustExist` (+ `toBuilder`), use/manage rights, janitor rules (managed tombstones kept, sweep).
3. Manager `createManaged`/`provision`/`purge`/views; `SandboxWorkspaces`; settings.
4. `SessionOptionsBindingPolicy`, attributes, unattached layers, switch notice; wiring.
5. Docker IT, E2E; WS, READMEs, CHANGELOG for PR 1.

**PR 2 — administration**
6. `SandboxAdministration` (lists, details, sessions, create on behalf, options, terminate, close, reopen, purge),
   events with `actor`.
7. `sandboxes()` on the reconciler's `classify`.
8. Tests, WS/README/CHANGELOG for PR 2.

---

## 11. What the user gets

- `sandbox.workspaces().create(name, profile)` returns a RUNNING sandbox and its id.
- `setSessionOptions(session, workspace=id)` puts that session's turns (and its forks) into it.
- An agent definition's `attributes: { sandbox: { workspace: wsm:…, unattached: reject } }` gives every session of that
  agent a default and a stricter rule.
- `SandboxAdministration.of(sandbox)` lists every workspace, the sessions of each, every provider sandbox with what the
  janitor thinks of it, and can terminate, close, reopen and purge.

---

## 12. Open questions

- **Q1 — per-request option.** A per-execution workspace or rule (including routines) needs core to carry request
  attributes in `EnvironmentRequest` — proposed as kangwoo/aimon-core#252 (forward `SubmitOptions`' execution
  attributes). Two more core proposals came out of this work: a session owner and a deletion event
  (kangwoo/aimon-core#253 — would replace `SessionOwnerLookup` and the host's `deleteSessionOptions` call) and a notice
  channel for every tool result (kangwoo/aimon-core#254 — would let the switch notice of §6.4 reach a file-tools-only
  turn).
- **Q2 — persistence.** Users who create a workspace first will expect files to outlive idle termination. That is
  shared volumes (WS §18-5); until then README warns.
- **Q3 — asynchronous / idempotent create.** Synchronous create blocks up to `provisionTimeout`; an HTTP host may want
  `create` to return PROVISIONING, and an idempotency key so a lost response does not create a second workspace.
- **Q4 — multi-node.** The in-memory options store has the workspace store's single-node limit; step 6's JDBC store
  covers both.

---

## 13. Review 1 — resolution

| Finding | Resolution |
|---------|------------|
| B1 provider id in views | removed from every view; admin shows `refHash` (§5.4) |
| B2 id reuse hijacks agent defaults | ids generated, never reused; managed tombstones kept until `purge` (§3.1, §6.6) |
| B3 tenant-mate redirects a session | only the session owner (by principal) or an admin sets options; no lookup → refused (§6.2) |
| M1 global chosen ids | generated `wsm:{UUID}` + `name` (§5.1) |
| M2 trust domain | stated; cross-principal sharing needs `allow-shared-workspaces`; documented-behaviour test (§6.5, §9.2) |
| M3 tenant access grants manage | use vs manage rights (§6.5) |
| M4 "execution option" | per-session option in the options store, three-layer rule; routines/per-request → Q1 (§1, §6.3) |
| M5 unbounded growth | `max-managed-per-owner`; hosts clear options with sessions; unknown sessions refused (§6.1, §6.2) |
| M6 non-atomic deletes | ids never reused; purge order record → options; janitor sweep (§6.6) |
| M7 `mustExist` lost in `toBuilder` | copied; tests named (§5.3, §9.1) |
| M8 notice channel, detach silent | `WorkspaceSwitch` on the binding, `lastWorkspaceId` rule; classes listed (§4.3, §6.4) |
| M9 parallel classification | reconciler's `Verdict` (§6.8) |
| M10 definition loosens operator rule | setting is a floor; definitions only tighten (§6.3) |
| m1 D2 misstated | restated (§8 D2) |
| m2 extra store read | departure D8; Javadoc updated (§4.3) |
| m3 idempotent create ignores differences | moot — create always mints (§5.1); Q3 |
| m4 admin attach unchecked | consistency check in the admin path (§5.5) |
| m5 admin getter is ambient | `SandboxAdministration.of(...)`; not a JVM security boundary, stated (§3.1); actor tenant in events |
| m6 `WorkspaceScan.next()` | carries new filters; contract test (§4.1, §4.5) |
| m7 no file persistence | stated; Q2 (§6.6) |
| m8 SYSTEM routines | documented + test (§6.3, §9.1) |
| m9 owner vs executing principal | stated + test (§6.2) |
| m10 version reuse | no-ABA rule in the SPI and contract (§5.2) |
| m11 failure table gaps | rows added (§7) |
| N1 id validation home | `ManagedIds` (§4.2) |
| N2 resurrected owner wording | fixed (§5.3) |
| N3 `WorkspaceAccess` package | kept in `SandboxSettings`; dependency already exists (§4.1) |
| N4 event types | reuse `TERMINATED`/`WORKSPACE_CLOSED` + `actor` (§4.1) |
| N5 undefined types, internal record exposed | defined; `SessionOptionsView` (§5.4) |
| N6 GROUP owner | Javadoc sentence (§5.5) |
| N7 scope | two PRs (§10) |

---

## 14. Review 2 — resolution

| Finding | Resolution |
|---------|------------|
| N1 lost profile | `profile` persisted on `MANAGED` records; required profile of `provision` and rows 1–2 (§4.1, §6.1) |
| N2 manage split breaks DERIVED | manage rule applies to `MANAGED` only (§6.5) |
| N3 agent default redirects | stated as the definition author's right; cross-principal landing needs sharing opt-in (§6.5) |
| N4 session loosens agent | `strictest(floor, agent, session)` — every layer only tightens (§6.3) |
| N5 binding without shell key | manager-internal `provisionSlot` (§6.1) |
| N6 options on derived ids | `wsm:` only (§6.3) |
| N7 notice gaps | silent first record, write after drain, best-effort (§6.4) |
| N8 session events | optional workspace/owner for session types, `session()` (§4.1) |
| N9 purge DERIVED | `MANAGED` only (§6.6) |
| N10 validator | replaced with the `max-managed-per-owner` check (§4.4) |
| N11 name in model strings | never (§5.1) |
| M2, M5, M9, m10, B1 partials | wording; validator; `Optional pastGrace`; global version floor; `sandboxId()` |

---

## 15. Implementation departures (PR 1)

Where PR 1 (commits 1–5 of §10) builds something other than, or more precisely than, the text above. Each is the
smallest change that keeps the design's intent.

| # | Design | Built | Why |
|---|--------|-------|-----|
| P1 | `Page<T>(items, next?)` (§5.4) | `Page<T, K>`: `next()` is typed by the listing's key (`SessionId` for `byWorkspace`, `SandboxWorkspaceId` for `workspaces` and `list`) | the `after` parameters are typed (§5.2); an untyped cursor would need parsing back into one |
| P2 | `SessionSandboxOptions.Update` unspecified | replaces both fields (an absent one is cleared) and keeps `lastWorkspaceId`; factories `attach(id)`, `rule(rule)`, `of(id, rule)` | one meaning for "set", no partial-patch ambiguity |
| P3 | the record's `profile` is the required profile "of rows 1–2 of §6.3" (§6.1) | applied by the manager when it provisions a new `primary` slot and the binding requires none — for every binding to the record. The descriptor declares it too while no slot exists, and `provision(id)` requires it only for a slot yet to exist (a slot a definition's `sandbox.profile` built first is accepted) | the policy would need a second workspace-store read per bind to learn it; the manager already holds the record. Same result for rows 1–2 |
| P4 | the policy compares and "records it silently" (§6.4) | the policy never writes: a record that never attached the session (no `lastWorkspaceId`, no `workspace`) carries a *silent* `WorkspaceSwitch` whatever row bound it, which the environment records at its first successful connect; announced switches are recorded once their notice is drained (as designed). A switch carries the options version it was read at, so the write is a CAS on it | one writer of `lastWorkspaceId`, one best-effort path |
| P5 | §9.1 "session can loosen the agent within the floor"; §9.3 "a session option `per-session` lets it run when the floor allows" (both texts now corrected) | tests assert §6.3 / §14 N4 instead: `strictest(floor, agent, session)` — a session cannot loosen an agent's `reject`. The E2E shows a session `reject` refusing, `per-session` running where neither floor nor agent tightens, and an agent `reject` holding against a session `per-session` | §9.1/§9.3 wording predates review 2's N4; §6.3 is the rule |
| P6 | `ManagedWorkspaceSandboxIT` (§9.2): "listed with verdict `OK`", "admin terminate → next command re-provisions" | listed through the owner's `list`; the verdict and admin terminate move to PR 2's IT (done there) | `sandboxes()` and `SandboxAdministration.terminateSlot` are PR 2 (§10) |
| P7 | `clearSessionOptions` deletes `workspace` and `unattached` (§6.2) | as designed; `lastWorkspaceId` falls back to the workspace being detached from, and only a record with nothing left (a rule-only one) is deleted. An empty `Update` is a clear. A new `deleteSessionOptions(session, caller)` removes the record outright — what a host calls when it deletes the session | a record with no field left only costs a read per turn; a detached session must still hear it moved; hosts need a way to remove the rest |
| P8 | `purge` of a missing id unspecified | success, like `close` (WS §15) | idempotent retry after a lost response |
| P9 | new manager entry points (§4.1) | the manager also hosts `setSessionOptions` / `clearSessionOptions` / `sessionOptions` / `describe` / `listOwned` and returns views; it takes the `SessionOwnerLookup` (`Builder.sessionOwners`) and the `SessionOptionsStore` | the use check (`checkUse`) and the gate live there; the facade stays a thin layer PR 2 can share |
| P10 | `sessionOptions(session, caller)` right unspecified | the right over the session (its owner) — same as set/clear | otherwise a tenant-mate learns where another's session runs |
| P11 | `SESSION_OPTIONS_*` events "may lack" workspace and owner (§4.1) | `owner()` is always set (the session's owner) and keeps its 0.1.0 signature; `workspaceId()` keeps its signature too and throws `IllegalStateException` on a session event naming no workspace; the new `workspace()` is the `Optional` one. `emit` also swallows a `LinkageError` from a listener | changing return types broke binary compatibility with 0.1.0 listeners (review M1) |
| P12 | commit order (§10) | `UnattachedRule` lands in commit 1 (the options record holds one), `ManagedIds` in commit 3 (`setSessionOptions` validates with it), `WorkspaceSandbox.Builder.sessionOptionsStore` in commit 2 (the janitor sweep reads it) | each type arrives with its first user |
| P14 | `listOwned` / `countManaged` trust the store's scan | both re-check every record with `WorkspaceScan.matches` | a store written for 0.1.0 ignores the new owner and origin filters (review M2) |
| P15 | recorded failure reasons (§5.4: no view carries a sandbox id) | a provider's or server's message is recorded with the created sandbox's raw id replaced by its hash | server error texts may quote the id (review m7) |
| P16 | a hook's command result | takes no pending notice (a reset or the switch waits for the model's next command) | whether core shows a hook's output to the model depends on the hook (review m2) |
| P17 | `WorkspaceSandbox` getter for the options store (PR 1 r1) | removed; the store is reached through `workspaces()` (the builder still takes one) | a public store bypasses the right over a session (review m11) |
| P13 | §9.1 race "notice flip vs re-attach" | a turn bound before a re-attach announces the target it was bound to and loses the `lastWorkspaceId` CAS; the next turn announces the new target once | the running turn keeps its binding (§6.2) |

---

## 16. Implementation departures (PR 2)

Where PR 2 (commits 6–8 of §10) builds something other than, or more precisely than, the text above, following what
PR 1 built (§15).

| # | Design | Built | Why |
|---|--------|-------|-----|
| A1 | `AdminWorkspaceQuery = WorkspaceQuery + (tenant?, owner?)` (§5.4) | a class of its own with all seven fields; same `limit ≤ 500` | composing two builders buys nothing; the store scan takes them flat |
| A2 | `WorkspaceDetail(workspace, sessions)` | `sessions` is the first page, `SandboxAdministration.DETAIL_SESSIONS` (100); `sessions(id, after, limit)` pages on | the page size has to be chosen somewhere |
| A3 | the administrator's `setSessionOptions` (§6.2) | `setBy` records the actor as resolved by the tenant resolver, and — when it knows no tenant for the actor — the actor in the session owner's tenant; the event's `actor` is the same resolved form, else the bare `TYPE:id` | `setBy` is a `WorkspaceOwner`, which needs a tenant |
| A4 | admin `reopen` | runs the record's owner through today's principal gate first (refused: nothing changes), reopens, then provisions as the owner — the use check inside `connect` passes, and admission and quota apply to the owner | the operator has no use right of its own to check; provisioning is charged to whom the workspace belongs to, and a principal the deployment now refuses gets no sandbox, as for create on behalf of |
| A5 | `actor` on events "of every mutating call" of the administration (§3.1, §5.5) | also on an owner's own close, reopen and purge (the owner) and on the `TERMINATED` events a close causes; the janitor's carry none | one code path serves both callers; the audit log can tell every requested transition from the system's own |
| A6 | `terminateSlot` (§6.7) | a missing workspace answers "does not exist", an absent slot "has no slot", a PROVISIONING slot is refused (also when it starts provisioning before the CAS); an already TERMINATED slot is a no-op. Terminating a FAILED slot clears its failure and backoff | a terminated claim would leave its claimer's sandbox an orphan and provision again at once; a typo must not succeed |
| A7 | `classify` and `Verdict` "reachable by the admin facade" (§4.1) | `SandboxReconciler` stays package-private; the manager maps its `Verdict` to a new public `SandboxVerdict` for `SandboxView` | the admin API must not expose an internal type a later verdict (DRIFT, step 7) would break |
| A8 | `SandboxView.verdict`; `pastGrace` for `ORPHAN` and `STALE` (§6.8) | `classify`'s verdict before grace: an `IN_FLIGHT` sandbox ahead of its record, which the janitor escalates to `ORPHAN` after `orphan-grace`, still reads `IN_FLIGHT`. `pastGrace` is given where the janitor waits for the grace — `ORPHAN`, `DUPLICATE` and that `IN_FLIGHT` — not for `STALE`, which goes without grace (§6.8 corrected) | the facade cannot see the reconciler's node-local `firstSeen`; the pure verdict is the reproducible one |
| A10 | the administration's session writes | also `deleteSessionOptions(session, actor)`, the operator's cleanup for a host that deleted sessions without removing their options (P7) | otherwise only `clear` remains, which keeps a `lastWorkspaceId` record |
| A9 | admin entry points | live on the manager as `admin*` (Internal), following P9; the facade is thin | the use and manage checks, the CAS loops and `emit` are there |

---

## 17. Departures after aimon-core 0.3.3

aimon-core 0.3.3 shipped the three things this design worked around (kangwoo/aimon-core#252, #253, #254). The change
that adopts them is [`workspace-sandbox-core-033.md`](workspace-sandbox-core-033.md) (**C33**); the sections above keep
their approved text, and this table says where they no longer describe the code.

| Where | Was | Now | C33 |
|---|---|---|---|
| §1, §12 Q1 — "execution option" | per session only: the host writes session options before a turn, routines get nothing | a turn or a routine run can name its workspace and tighten its unattached rule through request attributes (`environment.sandbox.workspace`, `environment.sandbox.unattached`); the session option stays as the session-level default. §12 Q1 is resolved | §2.3, §5.1, §6.1 |
| §6.3 — resolution order | rows 1–3 | row 0, the request attribute, comes first and binds only its own execution; the use right is the whole right it needs. The rule is `strictest(setting, agent, session, request)` | §5.1, §6.1 |
| §6.2, §4.4 — owner source | a host `SessionOwnerLookup`, required for session options | the owner core records (`WorkspaceSandbox.Builder.sessionRecords`), with the host lookup as the fallback for sessions recorded without one | §5.2, §6.2 |
| §6.2, P7 — "hosts delete options with their sessions" | the host calls `SandboxWorkspaces.deleteSessionOptions` on every delete | `WorkspaceSandbox.sessionLifecycleListener()` does, and also closes and deletes `ws:{sessionId}`; the owner-facing `deleteSessionOptions` is removed. A10's operator `deleteSessionOptions` stays for sessions deleted outside the router, and a janitor sweep removes what a lost event leaves | §2.3, §6.3 |
| §6.4, P4 — notice channel | the switch notice rides the next `Bash` result; recorded once a command result carried it | every environment-level notice is delivered by core's `drainNotices()` on the next tool result of any kind, and the switch is recorded when it is drained (asynchronously). A row-0 redirect is announced too, and recorded in a record the recorder creates if needed | §6.4 |
| P16 — hooks take no notice | a hook's command result takes none | gone: shell results carry only per-command notices, and core drains on the tool the hook ran for | §6.5 |
| P7 — `clearSessionOptions` fallback to the attached workspace | needed because a file-only turn never recorded `lastWorkspaceId` | kept, for a different case: a drained switch whose asynchronous record write loses its CAS to the clear | §6.4 |

Later, kangwoo/aimon-sandbox#28 ([`workspace-sandbox-unannounced-reset.md`](workspace-sandbox-unannounced-reset.md),
**UR**) added one field to the record of §5.2:

| Where | Was | Now | UR |
|---|---|---|---|
| §5.2 — `SessionSandboxOptions` | `workspace`, `unattached`, `lastWorkspaceId` | also `unannouncedReset()`: a reset of the session's sandbox that no drain has delivered yet, written by the session's root environment before it queues the reset notice and cleared by the drain that delivers it. `clearSessionOptions` keeps it, and a record holding only it is no attachment and no move (§6.4's switch ignores it). The listener, the operator's delete and the janitor's sweep remove it with the record. A store must persist it | §2.1, §3, §6 |
| §6.4 — the `lastWorkspaceId` write | its own asynchronous write after the drain | folded into one asynchronous acknowledgement per drain with the reset's clear, a CAS on the version the environment knows (the policy's read, or its own reset write's) | §5.3, §6.2 |

