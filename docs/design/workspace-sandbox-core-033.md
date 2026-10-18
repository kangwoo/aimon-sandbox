# Design — adopt aimon-core 0.3.3's three seams (request attributes, session owner + deletion event, environment notices)

> Status: **IMPLEMENTED**. The body below is the design as approved (r2, after review 1 — §13 maps its findings; review
> 2 passed it with non-blocking items). Where the code departed from it, §14 says so and why. The architecture document
> [`workspace-sandbox.md`](workspace-sandbox.md) has absorbed the changes (§7, §8.1–§8.3, §10.5, §13.2, §16), its §20
> carries the open questions of §12 that outlive this change, and the management design
> [`workspace-sandbox-management.md`](workspace-sandbox-management.md) §17 lists what it no longer describes.
>
> Scope: TASK.md items 1–7. Base: aimon-sandbox `ce2f496` (branch `herdr/adopt-core-033-seams`). Core reference:
> aimon-core main @ `c2eca4fd` (read-only), published as `at.aimon.core:*:0.3.3-SNAPSHOT` (20261017.114222).
> Below, **WS** is `docs/design/workspace-sandbox.md`, **MG** is `docs/design/workspace-sandbox-management.md`
> (its §15/§16 departure ids `P*`/`A*` are cited as they are), and the three core records are
> **C-EE102** (`docs/design/tool/execution-environment-ee102-request-attributes.md`), **C-SO**
> (`docs/design/session/session-owner-deletion-event.md`, backlog SO-1..SO-6) and **C-NT**
> (`docs/design/tool/execution-environment-notices.md`, backlog EE-106..EE-108).
>
> Like the step-3/4, core-031 and management designs, this document is in English. Edits to WS stay in Korean,
> the language of that file.
>
> Grounding: every existing name below was read on the base commit: `SessionOptionsBindingPolicy` (rows 1–3,
> `effectiveRule`, `withSwitch`), `DefaultSandboxBindingPolicy` (`sessionOwner`), `SessionOwnerLookup`,
> `BindingContext.from` (copies `definitionAttributes()` only), `WorkspaceSwitch` (`announced`/`silent`, `long
> optionsVersion`), `SandboxExecutionEnvironmentProvider` (`bindRoot`, `bindFork` — a fork never calls
> `policy.bind`), `SandboxExecutionEnvironment` (one `PendingNotices` per instance, built in its constructor),
> `PendingNotices` (`addAll` on connect, `drain` records the switch), `SandboxShell` (`pending.drain()` into
> `ShellCommandResult`/exceptions, `options.isHook() ? List.of() : …`, `KILLED_NOTICE`, `cwdNotice`),
> `SandboxWorkspaceManager` (`setSessionOptions`, `clearSessionOptions` with `lastWorkspaceId().or(record::workspace)`,
> `deleteSessionOptions`, `recordLastWorkspace`, `requireSessionOwner`, `connect` notices: `RESET_NOTICE`,
> "recreated (generation N)", "lost and has been recreated", "provisioned in Ns"), `SessionOptionsStore`
> (`find`/`put`/`delete`/`byWorkspace`/`workspaces`/`deleteByWorkspace` — no scan of all sessions),
> `SandboxJanitor.sweepSessionOptions`, `CloseCause` (`EXPLICIT`, `IDLE` — public since 0.1.0),
> `WorkspaceSandbox.Builder.sessionOwnerLookup`, `SandboxStartupValidator` ("require-principal needs a
> SessionOwnerLookup"), `build.gradle.kts` (no snapshot repository on `main`), `gradle/libs.versions.toml`
> (`aimon-core = "0.3.2"`), `scripts/release.sh` (refuses a SNAPSHOT pin), CHANGELOG (`v0.1.0` is the only tag;
> session options, `SandboxWorkspaces`, `SandboxAdministration` are all `[Unreleased]`). Core: `EnvironmentRequest
> .requestAttributes()`, `EnvironmentRequestAttributes.PREFIX`, `RoutineExecutionAttributes`,
> `SessionRecordStore.load(id) → Optional<SessionRecordView>`, `SessionRecordView.getOwner()`,
> `SessionLifecycleListener.onDeleted` / `whenDeleted`, `SessionDeletedEvent`, `ExecutionEnvironment.drainNotices()`,
> `EnvironmentNotices.PREFIX`.

---

## 1. The problem, restated

aimon-sandbox's managed workspaces (#25) were built on core 0.3.2 and worked around three things core lacked, each
filed upstream and now in aimon-core 0.3.3-SNAPSHOT. (#252) A provider could not see per-run caller input, so "run
this turn in workspace X" and "refuse if nothing is attached" became a per-session options store the host writes
before a turn, and routines got nothing at all; core now forwards `environment.*` execution attributes, prefix
stripped and String-only, to `EnvironmentRequest.requestAttributes()` for main turns (from `SubmitOptions`) and
routines (from a host `RoutineExecutionAttributes`), never for forks. (#253) Core recorded no session owner and
announced no deletion, so the host had to hand-write a `SessionOwnerLookup` and remember to call
`deleteSessionOptions` on every delete; core now records a first-wins, unenforced owner
(`SessionRecordView.getOwner()`) and fires `SessionLifecycleListener.onDeleted` once, on the deleting node. (#254)
File tools had no notice channel, so environment-level facts (reset, re-creation, workspace move) waited in
`PendingNotices` for the next `Bash` result, and `lastWorkspaceId` bookkeeping was delayed until such a result
carried the switch notice; core now drains `ExecutionEnvironment.drainNotices()` after every model-facing tool call.
This change pins the snapshot and moves the sandbox onto the three seams — per-execution workspace and rule through
request attributes with the existing rights rules, core's owner as the default owner source, a shipped deletion
listener, and every environment-level notice through `drainNotices()` — while keeping hosts that wired the 0.3.2-era
workarounds working.

---

## 2. Approach, and what was rejected

### 2.1 Chosen approach (one line per seam)

1. **Pin** `0.3.3-SNAPSHOT` exactly as 5545684 did (filtered Central-snapshot block + catalog comment).
2. **#252 — request attributes become row 0 of the existing decorator.** `SessionOptionsBindingPolicy` resolves, in
   order: request attribute → session option → agent default → effective unattached rule. A request attribute
   redirects **only its own execution** into a `wsm:` workspace the **executing principal may use** (the use right,
   checked by `connect` as for rows 1–2); it never writes a session option. `sandbox.unattached` from the request
   joins `strictest(...)` and can only tighten. **The session options store is kept** as the session-level default
   (§2.3).
3. **#253 owner — core's recorded owner becomes a built-in `SessionOwnerLookup` source**, wired with one builder call
   (`WorkspaceSandbox.Builder.sessionRecords(store)`); the SPI stays, and a host lookup becomes the fallback for
   sessions provisioned without an owner.
4. **#253 deletion — a shipped `SessionLifecycleListener`** (`WorkspaceSandbox.sessionLifecycleListener()`) deletes
   the session's options record and, **while the lease is held**, marks its derived `ws:{sessionId}` record with the
   new close cause `SESSION_DELETED` (one CAS: OPEN/CLOSING → CLOSING, CLOSED → CLOSED). The slow part — terminating
   sandboxes and deleting the record — runs afterwards on a manager-owned background executor, and the janitor
   finishes it if that never happens. A janitor sweep reconciles lost events.
5. **#254 — `SandboxExecutionEnvironment.drainNotices()`** returns the instance's `PendingNotices`; `SandboxShell`
   stops draining it into results, which keep only per-command notices. The switch is recorded when it is drained
   (core: drained = delivered), on the same background executor so the drain never waits on store I/O; the
   `Bash`-only and hook special cases go. The `clearSessionOptions` fallback stays, as a guard for a lost record
   write (§6.4).

### 2.2 Rejected alternatives

| Alternative | Why rejected |
|---|---|
| **Remove the session options store** now that request attributes exist (it is `[Unreleased]`, so removal costs no released host) | It holds three things request attributes cannot: a set-once default for hosts that do not want to stamp every submit; `lastWorkspaceId`, which the switch notice needs whatever chose the workspace (§6.4); and the admin view of "sessions attached to workspace X" (`byWorkspace`, `SandboxAdministration.sessions`). Removing it would push all three back onto hosts. |
| **Deprecate the store** in favour of request attributes | Nothing about it is wrong or superseded; deprecation would signal a removal nobody plans. The two coexist with a stated precedence (§5.1). |
| **Request attribute writes a session option** ("sticky" per-turn choice) | Turns a host-supplied per-run value into persistent state that redirects other principals' later turns — exactly the right the owner-only rule of MG §6.2 guards. A request attribute affects its own execution only. |
| **Require the session owner for a request-attribute redirect** (the MG §6.2 rule) | That rule exists because a session option redirects *everyone's* future turns of the session. A request attribute redirects only the turn of the principal it runs as, into a workspace that principal may already use; the use right (owner of the workspace, or a tenant-mate on an `access: tenant` record) is the right that matters, and TASK.md names it. Requiring session ownership would also make the option useless for routines (no session) and for multi-participant sessions. |
| **Honour every `sandbox.*` key from the request** (`slot`, `profile` too) | `sandbox.profile` is how low-trust definitions pin their trust domain (WS §8.3); letting a run override it moves a privilege decision from the definition author to every submit. Only `sandbox.workspace` and `sandbox.unattached` are request keys; any other `sandbox.*` key is refused loudly (core's own reasoning: a silently dropped placement input is the worse failure). |
| **Replace `SessionOwnerLookup` with a direct `SessionRecordStore` dependency** | The SPI shipped in 0.1.0; hosts with an identity provider keep using it, and sessions provisioned before the host set `SubmitRequest.owner` have no recorded owner. A built-in source plus fallback keeps both. |
| **A node-local owner cache** to avoid a full record load per bind | Owner is immutable only for one record lifetime. After a delete and re-creation of the same id (SO-3: peer nodes are not told), a stale cached owner would be written into the new session's `ws:{sid}` record at `createIfAbsent` and stick for that record's lifetime — a sticky denial. Correctness first; the cost is filed as a core gap (SO-5) and is Q3. |
| **Record `lastWorkspaceId` at the first successful connect** (drop record-on-drain entirely) | A connect can happen on a path whose queue is never drained — a turn-start or Stop hook's shell action, a workflow script's tool call, a staging call — and the instance dies with the notice still queued (EE-106). Recording at connect would then lose the announcement for good; recording at drain re-announces on the next turn. Core's contract makes the drain the delivery point, so it is also the honest acknowledgement point. |
| **Share one notice queue between a parent and its forks**, or seed a fork's queue from the parent's | Core forbids sharing (EE-107). Seeding is unnecessary: a fork's model never saw the old files. The opposite direction — a fork discovers a re-creation the parent must hear — is solved by generation tracking per instance (§6.5), not by sharing. |
| **Keep draining environment notices into `ShellCommandResult` as well** | C-NT §4: "A provider must not put the same fact in both." With one queue drained once it would not duplicate, but which result carried a fact would depend on a race between the `Bash` result and core's drain; and a background command's result (read by `BashOutput`, maybe a turn later) would swallow a reset that belongs to now. |
| **Listener runs the whole close synchronously** | The listener runs under the session's lease with the turn gate held (C-SO §6: "keep it short"). `close` waits up to `close-wait` on provider calls. Only the one CAS that makes the record unusable runs under the lease; the rest is deferred (§6.3). |
| **Listener only enqueues the close** (draft r1) | The lease is the guarantee core provides against "the new session X sees the deleted session X's state". With the whole close deferred, a re-created session can bind the still-OPEN `ws:{sid}`, connect into the deleted session's sandbox and see its files, and then have that sandbox terminated under its running turn by the queued close (review 1, B1). |
| **Close with `EXPLICIT` and delete the record afterwards** (draft r1, no new cause) | If the deferred part never runs (node crash), the record ends as a `CLOSED(explicit)` tombstone that blocks a re-created session for `closed-retention` (7 d), and nothing can tell it apart from an application's close. The reason has to be persisted for the janitor to finish the job, so `CloseCause` gains `SESSION_DELETED`. The cost — a new constant in a public enum — is listed in §8. |
| **`SandboxScheduler` gains a one-shot `execute(Runnable)`** | `SandboxScheduler` is a public SPI since 0.1.0 that hosts implement (`WorkspaceSandbox.Builder.scheduler`); an abstract method breaks them, and a default would have to run inline (the blocking this avoids) or abuse `scheduleAtFixedRate`. Its default pool is the two heartbeat threads; a close holding one for `close-wait` delays heartbeats and invites an idle close of a busy workspace (review 1, B2). A separate executor owns one-shot work (§3.5). |

### 2.3 Fate of the per-session options store (TASK item 2)

**Kept, as the session-level default.** Weighed against: the module is 0.x; `SessionOptionsStore`,
`SandboxWorkspaces` and `SandboxAdministration` exist only under `[Unreleased]` (tag `v0.1.0` predates them), so any
choice is free of a released-compatibility cost — the decision is about value, not about compatibility. Value is §2.2
row 1. What changes:

- it is no longer the only way to express a per-execution option, and it is not required for routines;
- the host no longer has to delete it by hand (§6.3) — the owner-facing `SandboxWorkspaces.deleteSessionOptions`
  (added in this `[Unreleased]` cycle only for that purpose, MG P7) is **removed**; the operator's
  `SandboxAdministration.deleteSessionOptions` (A10) stays for hosts that delete sessions outside the router;
- it gains one SPI method for the janitor sweep (§5.4).

---

## 3. Concrete changes by file

### 3.1 Build (TASK item 1)

| File | Change |
|---|---|
| `build.gradle.kts` | Re-add the `centralSnapshots` block of 5545684 verbatim (filtered to `at.aimon.core`, `snapshotsOnly()`, the three-line comment). |
| `gradle/libs.versions.toml` | `aimon-core = "0.3.3-SNAPSHOT"`; a new paragraph after the 0.3.2 one: pinned for `EnvironmentRequest#requestAttributes` (#252), the session owner and `SessionLifecycleListener` (#253) and `ExecutionEnvironment.drainNotices()` (#254), resolved from Central's snapshot repository through the filtered block; a release blocker (`scripts/release.sh`); **raising it to the released 0.3.3 and deleting the repository block is a separate act, done together**. |
| `scripts/release.sh` | none (already refuses a SNAPSHOT pin). |
| tests | whatever core 0.3.3 breaks at compile time is fixed in this commit, as 5545684 did for `UserLocale`. Known risk: core reserved `environment.*` — any test that sets a non-String `environment.*` execution attribute now gets an unavailable environment (none found by reading; the build will tell). |

### 3.2 `aimon-sandbox` — `at.aimon.sandbox.binding`

| File | Change |
|---|---|
| `BindingContext` | new `Map<String, String> requestAttributes()` copied from `request.requestAttributes()` (prefix already stripped by core). Javadoc: host-supplied placement input; forks never have it. |
| `SandboxRequestAttributes` (new, public final, private ctor) | Host-facing keys and helpers: `WORKSPACE = "environment.sandbox.workspace"`, `UNATTACHED = "environment.sandbox.unattached"` (built from core's `EnvironmentRequestAttributes.PREFIX` + `SlotChoice` keys), `static Map<String, Object> workspace(SandboxWorkspaceId)`, `static Map<String, Object> unattached(UnattachedRule)` — for `SubmitOptions.executionAttribute(...)` and a `RoutineExecutionAttributes` resolver. Javadoc carries the trust rule (never from model output). |
| `SlotChoice` | Javadoc only: `WORKSPACE_ATTRIBUTE` / `UNATTACHED_ATTRIBUTE` are read from the definition **and**, without the `environment.` prefix, from the request. |
| `SessionOptionsBindingPolicy` | Row 0 (§6.1): request `sandbox.workspace` → `managed(context, id)` (unchanged helper: caller as owner, `mustExist`, definition slot/profile). Request `sandbox.unattached` joins `effectiveRule(agent, session, request)`. Any other request key under `sandbox.` → `BindingRejectedException`. Switch logic extended to a session **without** an options record when row 0 bound it (§6.4). Class Javadoc: the four-row order and the precedence table of §5.1. Name kept (it is public but unreleased; a rename buys nothing). |
| `WorkspaceSwitch` | `optionsVersion` becomes `OptionalLong` (absent: no record yet — the recorder creates one); `notice()` words the from-absent case as "…; files and shell state from any earlier workspace of this session are not here". |
| `SessionOwnerLookup` | Stays a `@FunctionalInterface`. New `static SessionOwnerLookup recorded(SessionRecordStore records)` and `recorded(Supplier<? extends SessionRecordStore>)` (late-bound: the stack builds its in-memory store after the sandbox) → `records.load(id).flatMap(SessionRecordView::getOwner)`; new `default SessionOwnerLookup orElse(SessionOwnerLookup fallback)`. Javadoc: core records the owner only when the host sets `SubmitRequest.owner(...)` on a submit that provisions the record (C-SO rule 5.2); a full record load per call (SO-5). |
| `DefaultSandboxBindingPolicy` | no code change; Javadoc says where the lookup now comes from. |

### 3.3 `aimon-sandbox` — `at.aimon.sandbox.environment`

| File | Change |
|---|---|
| `SandboxExecutionEnvironment` | `@Override drainNotices()` → `pending.drain()`. The recorder passed to `PendingNotices` hands `manager.recordLastWorkspace(...)` to the manager's background executor (§3.5) — the drain never blocks on store I/O (C-NT §4 "must not block on remote I/O"); a rejected submission (executor closed or full) is logged and dropped, the worst case being one repeated notice. Class Javadoc: one queue per instance, never shared with a fork (EE-107). |
| `PendingNotices` | Javadoc rewritten: drained by core after every model-facing tool call. Keeps: switch notice queued at the first successful connect; silent switch recorded at that connect; announced switch recorded by the `drain` that returns it. **New**: per-instance generation tracking (§6.5) — `addAll(ConnectedSlot)` instead of `addAll(List<String>)`, reading the slot's `(incarnation, generation)` and its `reset()` flag rather than matching notice strings. |
| `SandboxShell` | Every `pending.drain()` / `drain(run)` / `options.isHook() ? List.of() : pending.drain()` site is removed: results and exceptions carry **only per-command notices** (`KILLED_NOTICE`, `cwdNotice`). The "shell busy", "sandbox lost during command" and cancelled-before-start exceptions carry `List.of()`. Hooks are no longer special-cased for notices (core drains on the tool the hook ran for, after PostTool hooks). |
| `SandboxFileSystem`, `SandboxStaging`, `SandboxContentSearch` | `pending.addAll(slot)` (signature change only). Comments that say "reported with the next `Bash` result" go. |
| `SandboxExecutionEnvironmentProvider` | `bindFork` unchanged in code (it never builds a `BindingContext`, so a fork cannot read request attributes); Javadoc states it, citing #252. |

### 3.4 `aimon-sandbox` — `at.aimon.sandbox.workspace`

| File | Change |
|---|---|
| `SandboxWorkspaceManager` | (a) `clearSessionOptions`: unchanged in code — the `.or(record::workspace)` fallback stays; its comment now names the case it still covers: the drained switch whose asynchronous record write lost its CAS to this clear (§6.4). (b) `recordLastWorkspace(session, OptionalLong expected, workspace, setBy)`: absent `expected` creates the record (`put(..., OptionalLong.empty())`, `setBy` = the binding's caller); a present one CASes as today; still best effort. (c) new internal `sessionDeleted(SessionId)`: the synchronous part of §6.3 (options delete, `SESSION_DELETED` mark) and the submission of the deferred part to the background executor; new internal `finishSessionDeletion(id, incarnation)` used by that task and by the janitor. (d) owner-facing `deleteSessionOptions(SessionId, Principal)` removed; the private `Acting` variant stays for the admin path; the Javadoc that pointed hosts at it (manager l. 1225, `SessionOptionsStore`) now names the listener, and for sessions deleted outside the router the admin `deleteSessionOptions` or the janitor sweep. (e) `connect`'s `ConnectedSlot` exposes the workspace incarnation and slot generation it connected, and a `reset()` flag set when this connect reported an idle reopen, a re-creation or a lost sandbox (§6.5). (f) `connect` treats `CLOSING`/`CLOSED` with cause `SESSION_DELETED` as not usable ("this session's sandbox workspace is being removed; retry shortly") — never an idle-style auto-reopen. (g) takes the background executor from the builder. |
| `CloseCause` | new constant `SESSION_DELETED` — "its session was deleted; the record is removed as soon as its sandboxes are gone, without `closed-retention`". |
| `SessionOptionsStore` | new `Page<SessionSandboxOptions, SessionId> sessions(Optional<SessionId> after, int limit)` — every record, ordered by session id (janitor sweep, §6.3). Class Javadoc: "Hosts delete options with their sessions" becomes "register `WorkspaceSandbox.sessionLifecycleListener()`". |
| `InMemorySessionOptionsStore` | implements `sessions`. |
| `SandboxJanitor` | new sweep step `sweepDeletedSessions()` — only when a `SessionRecordStore` is wired (§6.3). `deleteTombstone`: a `CLOSED(SESSION_DELETED)` record is deleted on the first pass (no `closed-retention`); the existing resume of a stuck `CLOSING` already finishes an interrupted `SESSION_DELETED` close, after `close-resume-after`. |
| `SandboxEvent` | none (reuses `SESSION_OPTIONS_CLEARED`, `WORKSPACE_CLOSED`, `WORKSPACE_PURGED` with cause "session deleted", no actor). |

### 3.5 `aimon-sandbox` — root package

| File | Change |
|---|---|
| `WorkspaceSandbox.Builder` | new `sessionRecords(SessionRecordStore)` and `sessionRecords(Supplier<? extends SessionRecordStore>)`. The effective lookup is: records only → `recorded(records)`; records + `sessionOwnerLookup` → `recorded(records).orElse(lookup)`; lookup only → lookup (0.1.0 behaviour); neither → none (caller owns, single tenant). Passed to the manager, the default policy and the janitor (records). |
| `WorkspaceSandbox.Builder` — background executor | new optional `backgroundExecutor(Executor)` for one-shot work: the deferred part of a session deletion and the `lastWorkspaceId` write after a drain. Default: an executor this assembly owns — two daemon threads named `aimon-sandbox-background-N`, a bounded queue (1 000 tasks; a rejected task is logged at WARN and dropped — both kinds of task are best effort with a janitor or a repeated notice behind them), shut down by `WorkspaceSandbox.close()` after the janitor. It is **separate from the heartbeat and janitor schedulers**, so a deletion's close (up to `close-wait`) never delays a heartbeat. A supplied executor is borrowed, never shut down, like a supplied scheduler. `SandboxScheduler` is not changed (§2.2). Tests pass a queueing executor they drain by hand. |
| `WorkspaceSandbox` | new `SessionLifecycleListener sessionLifecycleListener()` — one instance per assembly. Class Javadoc example gains `.sessionRecords(...)` and the listener registration (`SessionSpec.Builder.lifecycleListener(sandbox.sessionLifecycleListener())`, or a Spring bean). |
| `SandboxSessionCleanup` (new, public final, `implements SessionLifecycleListener`; constructed only by `WorkspaceSandbox`) | `onDeleted(event)` → `manager.sessionDeleted(...)`; catches `RuntimeException` and logs WARN (core catches too, but it must not depend on that). |
| `SandboxStartupValidator` | `Wiring.sessionOwnerLookupSupplied` = lookup **or** records supplied; message "require-principal needs a SessionOwnerLookup or sessionRecords(...)". |
| `SandboxWorkspaces` | `deleteSessionOptions` removed (§2.3); Javadoc of the session-option methods names both owner sources. |
| `SandboxAdministration` | Javadoc of `deleteSessionOptions`: for sessions deleted outside the router (no event). |

### 3.6 `aimon-sandbox-testkit`

`SessionOptionsStoreContract` gains `sessionsPagesEveryRecordInOrder` (records with and without `workspace`, more
than one page, a record deleted between pages).

### 3.7 Docs (TASK item 6)

- **New** `docs/design/workspace-sandbox-core-033.md` — this design as approved, with a later "implementation
  departures" section, like `workspace-sandbox-core-031.md`.
- **MG**: a new §17 "Departures after aimon-core 0.3.3" (table: §1 "execution option" now per request; §6.2 owner
  source and "hosts delete options" → listener; §6.3 row 0; §6.4 notice channel, P16 gone and P7's fallback kept for a lost record write;
  §12 Q1 resolved). Status line points at the core-033 design. The approved body is not edited (house habit).
- **WS** (Korean): §7 table row `ShellCommandResult.notices()` split into `drainNotices()` (environment facts) and
  `ShellCommandResult.notices()` (per-command facts); §7 core-version paragraph (0.3.3, PR #257/#258/#259); §8.3
  owner paragraph (core-recorded owner, fallback, `require-principal` rule of §5.2 below) and the "세션 옵션은 …
  `SessionOwnerLookup`" sentence; §8.1/§8.2 one line on request attributes; §13.2 validator line (lookup or records);
  §16 test rows if any cite the `Bash`-only rule.
- **README**: "Wiring" gains `sessionRecords(...)`, the listener registration and per-request attributes
  (`SandboxRequestAttributes`, a `RoutineExecutionAttributes` example); "Managing workspaces" loses "call
  `deleteSessionOptions`" (line 103–105) and states the precedence table; line 137 (operator needs the lookup) names
  both sources.
- **CHANGELOG `[Unreleased]`**: "Requires aimon-core **0.3.3**" (and that the pin is a snapshot until then);
  `### Added: per-execution workspace and rule (kangwoo/aimon-core#252)`; `### Added: session owner from core and a
  deletion listener (kangwoo/aimon-core#253)`; `### Changed: environment notices reach every tool result
  (kangwoo/aimon-core#254)`; edits to the existing session-options bullets (no manual delete, `deleteSessionOptions`
  owner-facing method gone, "with its next command's result — never with a hook's" replaced); a short
  "Behaviour changes for 0.1.0 hosts" list (§8), including the new `CloseCause.SESSION_DELETED` constant and the
  `backgroundExecutor(...)` builder option.

---

## 4. What core gives, and what this design relies on (checked in core)

| Core fact | Where | Used for |
|---|---|---|
| `requestAttributes()` never null, unmodifiable, `environment.` stripped, String-only; a bad value makes the environment unavailable **before** the provider is called | C-EE102 §2.1, §5 | row 0 needs no type parsing beyond `UnattachedRule.parse` |
| Main turns: from `SubmitOptions` execution attributes; routines: from `RoutineExecutionAttributes` (once per fire, after the execution id is minted); forks, workflow steps, workflow runner fallback, CLI probe: always empty | C-EE102 §2.2–2.3 | routines get an `ExecutionId`, so row 0 binds them with `exec:{id}` |
| Main-turn requests carry no `executionId` (EE-105) | backlog | row 0 on a main turn keys the shell by session, as rows 1–2 |
| Owner: first-wins, set only by `provision` on a submit that carries `SubmitRequest.owner`; never cleared; not enforced; `Principal.equals` is type + id | C-SO §2.1, §4.3, §5 | owner lookup semantics |
| Reading the owner means `SessionRecordStore.load(id)` — the whole record, transcript included (SO-5) | backlog SO-5 | cost note, core gap |
| `onDeleted` fires once per call that deletes the record, on the deleting node, under the lease, before the lease returns; at-most-once (crash loses it); a delete of a never-existing id fires with an empty owner; raw `SessionRecordStore.delete` / `clear()` fire nothing | C-SO §2.2, §5.4, §6 | listener must be idempotent, short, never call the router; janitor reconciles |
| `drainNotices()` drained once after every model-facing tool call (`SingleToolInvoker`, `LlmSkillExecutor` fallback), after PostTool hooks, on every exit path; `[environment] ` prefix; null/blank skipped; a throw → WARN, result unchanged; drained ⇒ delivered (at-most-once) | C-NT §2.1, §4, §5 | §6.4–6.5 |
| Not drained by: routine steps, workflow-script tool calls, `RecentFilesRestoreHook`, hook shell actions — notices stay queued | C-NT §5 | why record-on-drain, not on connect |

---

## 5. Data and interface shapes

### 5.1 Precedence (TASK item 2)

**Which workspace** a root execution binds to — the first row that applies wins:

| Row | Source | Who may set it | Applies to | Binding |
|---|---|---|---|---|
| 0 | request attribute `environment.sandbox.workspace` | the host, per submit / per routine fire | this execution only (main turn or routine; never a fork) | `wsm:` only; caller = owner; `mustExist`; definition's slot/profile |
| 1 | session option `workspace` | the session's owner (or an operator) | every root turn of the session | as today |
| 2 | agent definition `sandbox.workspace` | the definition's author | every root execution of that agent | as today |
| 3 | effective rule `PER_SESSION` → delegate policy (`ws:{sessionId}` / `ws:{executionId}`); `REJECT` → refused | — | — | as today |

**Which unattached rule** (consulted only when rows 0–2 do not apply):

```
rule = strictest(setting `unattached`, agent `sandbox.unattached`, session option `unattached`,
                 request `environment.sandbox.unattached`)
```

The deployment setting is the floor; each later layer can only tighten. A request `per-session` therefore cannot lift
a session's or an agent's `reject`, and a request `reject` refuses this execution when nothing else attaches it (the
"refuse if nothing is attached" of MG §1). There is no deployment-level default *workspace*; the deployment setting
takes part only in the rule.

Forks are not rows: they inherit the parent's binding (WS §8.2), including a parent bound by row 0.

### 5.2 Owner sources and `require-principal` (TASK item 3)

```
owner(session) = recorded(records).orElse(hostLookup)      // whichever of the two is wired, in that order
```

| Wiring | Owner answered | Owner unknown (`Optional.empty()` from every wired source) |
|---|---|---|
| neither (single-tenant, 0.1.0 default) | — | the executing principal owns `ws:{sid}` (unchanged); session options refused ("need a session owner lookup") |
| records and/or host lookup | that owner | **the bind is rejected** ("the owner of session X is not known — set `SubmitRequest.owner(...)` on the submit that creates the session, or answer it from your `SessionOwnerLookup`"), as a lookup's empty answer is today; session options refused ("unknown session") |

`require-principal: true` still demands an owner source at startup — now satisfied by `sessionRecords(...)` alone.
**With `require-principal` on, a session provisioned without an owner gets no sandbox** (every root bind of it is
rejected) unless the host lookup answers for it: there is deliberately no fallback to the executing principal, because
that is the "first caller owns" weakness `require-principal` exists to exclude (WS §8.3). Hosts with such sessions keep
their `SessionOwnerLookup` as the fallback until those sessions are gone. With `require-principal` off the same table
holds (unchanged from 0.1.0: a wired source that does not know the session rejects).

Core does not enforce the owner (SO-1): the sandbox's owner-only right over session options remains enforced in
`requireSessionOwner`, now against core's owner.

### 5.3 New and changed signatures

```java
// at.aimon.sandbox.binding
public final class SandboxRequestAttributes {
    public static final String WORKSPACE = EnvironmentRequestAttributes.PREFIX + SlotChoice.WORKSPACE_ATTRIBUTE;
    public static final String UNATTACHED = EnvironmentRequestAttributes.PREFIX + SlotChoice.UNATTACHED_ATTRIBUTE;
    public static Map<String, Object> workspace(SandboxWorkspaceId id);   // {WORKSPACE: id.value()}
    public static Map<String, Object> unattached(UnattachedRule rule);    // {UNATTACHED: "reject" | "per-session"}
}

@FunctionalInterface
public interface SessionOwnerLookup {
    Optional<Principal> ownerOf(SessionId sessionId);
    static SessionOwnerLookup recorded(SessionRecordStore records);
    static SessionOwnerLookup recorded(Supplier<? extends SessionRecordStore> records);
    default SessionOwnerLookup orElse(SessionOwnerLookup fallback);
}

// BindingContext
public Map<String, String> requestAttributes();

// WorkspaceSwitch
public OptionalLong optionsVersion();     // was long

// at.aimon.sandbox
WorkspaceSandbox.Builder sessionRecords(SessionRecordStore records);
WorkspaceSandbox.Builder sessionRecords(Supplier<? extends SessionRecordStore> records);
WorkspaceSandbox.Builder backgroundExecutor(Executor executor);          // optional; default owned, 2 daemon threads
SessionLifecycleListener WorkspaceSandbox.sessionLifecycleListener();

// at.aimon.sandbox.workspace
public enum CloseCause { EXPLICIT, IDLE, SESSION_DELETED }
```

Host usage (illustrative):

```java
live.submit(prompt, SubmitOptions.builder()
        .executionAttributes(SandboxRequestAttributes.workspace(SandboxWorkspaceId.of("wsm:…")))
        .build());

schedulingSpec = schedulingSpec.withRoutineExecutionAttributes((task, executionId) ->
        myTaskPlacement(task).map(SandboxRequestAttributes::workspace).orElse(Map.of()));
```

### 5.4 SPI addition

```java
// SessionOptionsStore — every record, ordered by session id; for the janitor's deleted-session sweep
Page<SessionSandboxOptions, SessionId> sessions(Optional<SessionId> after, int limit);
```

`[Unreleased]` SPI, so not a break for any released store; the testkit contract covers it.

### 5.5 Model-visible shapes

- Environment notices: one `[environment] <text>` line each at the head of **every** tool result (core renders).
  Texts unchanged (`RESET_NOTICE`, "the sandbox was recreated (generation N)…", "…lost and has been recreated…",
  "sandbox provisioned in Ns", the switch notice) plus the new generation notice of §6.5.
- `Bash` / `BashOutput` per-command lines unchanged (`KILLED_NOTICE`, cwd fallback). On a `Bash` result core puts the
  drained lines first, then these, then output.

---

## 6. Mechanisms

### 6.1 Row 0 — binding from request attributes

```
bind(context):                                        -- root requests only; forks never reach bind()
  req = context.requestAttributes() restricted to keys starting "sandbox."
  for key in req: key ∉ {sandbox.workspace, sandbox.unattached}
                  -> BindingRejectedException("request attribute environment.<key> is not supported")
  record = session.flatMap(options::find)             -- unchanged: one options read per session bind
  if req has sandbox.workspace:
       id = ManagedIds.require(value, "the request's environment.sandbox.workspace")
       binding = managed(context, id)                 -- caller is owner and caller; mustExist; slot/profile from definition
  else if record.workspace present:  rows 1, 2, 3 as today, with
       effectiveRule(agentRule, record.unattached, parse(req sandbox.unattached))
  return withSwitch(binding, record, session, row0 = req has workspace)
```

- **Rights.** The executing principal passes the principal gate (`callers.callerOf`) at bind, and `connect` runs the
  use check (`checkUse`) against it before anything is provisioned — the workspace's owner, or a principal of the
  same tenant on a record whose access is `tenant`. That is the whole right: a request attribute redirects only the
  execution it is attached to. A malformed id, a derived `ws:` id, an unknown `sandbox.*` key, or an unparsable rule
  → `BindingRejectedException` → core installs an unavailable environment; the turn runs, and its file and shell
  tools fail with the reason (C-EE102 §5, WS §12.1).
- **Routines.** No session: no options read, no switch, shell key `exec:{executionId}`. Core builds a routine's
  request with `.principal(task.getOwner())` (`RoutineExecutor`), normally the user who created the task; that
  principal passes the gate like any caller (a SYSTEM/SERVICE owner only via `allowed-system-principals` under
  `require-principal`) and must be able to use the workspace (owned by it, or `access: tenant` in its tenant) — the
  same as MG §6.3's agent-default rule. README examples and tests use a task owner, not `Principal.system()`.
- **Trust.** Request attributes are host-supplied by core's contract (C-EE102 §2.4). The sandbox does not try to
  detect model-derived values; README and `SandboxRequestAttributes` Javadoc say a host must never fill them from model
  output. The rights check above applies whatever the host says.
- **Forks.** `bindFork` never builds a `BindingContext`; even a hand-built fork request carrying request attributes
  binds to the parent's workspace (test, §9). Core never sends them to forks anyway.

### 6.2 Owner lookup

`recorded(records)` is `id -> records.load(id).flatMap(SessionRecordView::getOwner)`; a `RuntimeException` from the
store propagates (bind: → unavailable environment, as a failing host lookup today; options calls: → the caller sees
it). Every place that asked the host lookup now asks the composed one: `DefaultSandboxBindingPolicy.sessionOwner`
(once per main-turn root bind), `requireSessionOwner` (options calls, admin paths). The record exists by the time a
main turn binds (the router provisions on claim), so an owner set on the first submit is visible on that first turn.

Consequence worth stating: **with only `sessionRecords(...)` wired, options cannot be set for a session before its
first submit** (the record — and so the owner — does not exist yet). Hosts that attach before the first turn either
pass the workspace as a request attribute on the first submit, pre-provision the record with
`SessionRecordStore.provision(id, agentRef, owner)`, or keep a host lookup as fallback (Q8).

### 6.3 Deletion listener (TASK item 4)

```
SandboxSessionCleanup.onDeleted(event):                 -- deleting node, under the session's lease; must be short
  manager.sessionDeleted(event.sessionId())

manager.sessionDeleted(sid):                            -- no rights check: a system action on a deleted session
  -- synchronous, under the lease: store reads and CAS writes only, no provider call
  1. options: find → delete(version) with the CAS retry loop; emit SESSION_OPTIONS_CLEARED(cause "session deleted")
     when a record was there. A store error is logged at WARN; the janitor sweep finishes it.
  2. derived workspace ws = store.find("ws:" + sid), origin DERIVED (a managed workspace the session was attached to
     is never touched: it is shared and has its own owner). CAS loop on the read version (casRetries):
       OPEN                 -> CLOSING(stateSince = now, cause SESSION_DELETED)       -- same incarnation
       CLOSING (any cause)  -> CLOSING, cause SESSION_DELETED                         -- an idle close in flight
       CLOSED  (any cause)  -> CLOSED,  cause SESSION_DELETED                         -- idle-closed or tombstone
       absent               -> nothing
     emit WORKSPACE_CLOSED(cause "session deleted") on the OPEN transition. A store error or exhausted CAS is logged
     at WARN (the derived workspace then ends by the idle policy, as for a lost event).
  -- deferred: submitted to the background executor with the incarnation read in step 2
  3. finishSessionDeletion(id, incarnation):
       closeProcedure(id, SESSION_DELETED, guard: CLOSING && same incarnation)   -- terminate, destroy, wait
       then, if the record is CLOSED(SESSION_DELETED) with that incarnation: store.delete(id, version);
       emit WORKSPACE_PURGED(cause "session deleted")
  never throws
```

- **Why the mark is synchronous.** The listener runs under the session's lease, and core gives that lease as the
  guarantee that the cleanup of session X cannot touch a *new* session X. Step 2 is the one write that matters for
  that guarantee: once the lease is returned and a re-created session binds `ws:{sid}`, `connect` sees CLOSING or
  CLOSED with cause `SESSION_DELETED` and answers "being removed; retry shortly" — it never connects into the deleted
  session's sandbox, and the deferred step 3 can never terminate a sandbox under a new session's turn, because no new
  turn ever got one from that incarnation. The new session gets a fresh `ws:{sid}` once step 3 (or the janitor) has
  deleted the record. Step 2 costs one read and one CAS write against the workspace store, the same order of cost as
  step 1; the provider calls (destroy, wait up to `close-wait`) are all in step 3.
- **Why close the derived workspace at all.** It is per-session state (keyed `ws:{sessionId}`) whose sandboxes cost
  money until `close-after`; and its record carries the deleted session's owner, so a *new* session reusing the id
  would be refused ("not permitted") by its tombstone or by an idle-closed record's auto-reopen for up to
  `closed-retention` (WS §10.5's reason for a tombstone — a later turn of the *same* session — does not exist once the
  session is deleted). That is why CLOSED records, idle or explicit, are marked too (review 1, non-blocking 1); they
  hold no sandboxes, so step 3 only deletes them. Deleting the record is the derived-record counterpart of `purge`.
- **Convergence without step 3.** If the background task never runs (node crash, executor full, assembly closed),
  the persisted cause carries the intent: the janitor's existing resume of a stuck CLOSING (after
  `close-resume-after`) finishes the close with the cause kept, and `deleteTombstone` deletes a
  `CLOSED(SESSION_DELETED)` record on its first pass, without `closed-retention`. Until then a re-created session with
  the same id is refused with "being removed" — bounded by `close-resume-after` plus one janitor period.
- **Idempotent.** Unknown session (core fires with an empty owner when a never-existing id is deleted) → nothing to
  do. A second firing finds the options gone and the record already marked (the CAS rewrites the same cause or finds
  nothing); a second deferred task finds nothing to do.
- **Other nodes (SO-3).** Only the deleting node is told. Everything this module keys by session lives in the shared
  stores (options record, workspace record) or inside the sandbox, so one firing is enough. Node-local state keyed by
  session is transient: shell locks in `SandboxConnectionCache` (released when commands end) and exec-shell
  bookkeeping (swept by `sweepExecShells`). No owner cache exists (§2.2). Nothing on a peer node needs the event.
- **Lost events** (crash between delete and listener, a delete through raw `SessionRecordStore.delete`, a host that
  never registered the listener): the janitor's new `sweepDeletedSessions()` pages `SessionOptionsStore.sessions` and
  deletes a record when `records.exists(sid)` is false **and** `updatedAt` is older than `closed-retention` (the grace
  protects a record written before the session's first submit — possible with a host-lookup fallback). It runs only
  when a `SessionRecordStore` is wired. A derived workspace of a lost event ends by the idle policy (`close-after`) and
  `closed-retention`, as before (it is not marked `SESSION_DELETED`: without the event, nothing proves the session is
  gone rather than not yet provisioned).
- **Shell state of a session inside a managed workspace** (`/workspace/.aimon-shell/session-{sid}`) is not removed:
  it would need a connect (provisioning) from a listener that must stay short. A later session reusing the id in the
  same workspace would source that cwd/exports — recorded as a known limitation (README), not fixed.

### 6.4 Workspace switch and `lastWorkspaceId` (TASK item 5)

What the workaround was (MG §6.4, P4, P7, P16): the switch notice could only ride a `Bash` result, so recording
`lastWorkspaceId` was **delayed until a `Bash` result carried it**; a hook's result took none; and because a
file-only turn never recorded it, `clearSessionOptions` fell back to the attached workspace as `lastWorkspaceId`.

After this change:

```
policy:   withSwitch(binding, record?, session, row0)
          from = record.lastWorkspaceId
          from == to                                   -> no switch
          record absent && !row0                       -> no switch (never redirected; as today)
          row0 && from absent                          -> announced(from = none, to, version?)   -- record or not
          record present, no lastWorkspaceId, no workspace -> silent(to)   (rule-only record, rows 1–3; as today)
          otherwise                                    -> announced(from, to, version)

environment (per instance):
  first successful connect: queue the announced switch notice ahead of connect's notices; record a silent switch
  drainNotices(): out = queue; clear; if out contains the switch notice and not yet recorded:
                  submit recordLastWorkspace(session, version?, to, caller) to the background executor
                                                                   -- async, best effort, CAS
```

- **Removed**: the `Bash`-only delivery, the hook exclusion (`options.isHook() ? List.of() : …`), and the doc text
  "a turn of file tools only leaves it for the next `Bash`". The first tool call of any kind after the connect delivers
  and records it.
- **Kept**: record-on-drain (§2.2 explains why not record-on-connect), the CAS on the version the policy read
  (a lost CAS repeats the notice once, P13), forks never carry a switch, and the `clearSessionOptions`
  `.or(record::workspace)` fallback. That fallback no longer exists for file-only turns, but it still covers a switch
  that was drained and whose asynchronous record write then lost its CAS to the owner's clear: without it the record
  would be deleted without `lastWorkspaceId`, and the next turn would land in `ws:{sid}` silently although the model
  was last told it runs in `wsm:A` (review 1). It costs nothing.
- **New**: whenever row 0 bound the execution and the session has no `lastWorkspaceId`, the switch is announced
  (from-absent wording) — with or without a record. A rule-only record therefore does not silence a request-attribute
  redirect. With no record the recorder **creates** one holding only `lastWorkspaceId`. So "turn 1 in `ws:{sid}`,
  turn 2 with a request attribute in `wsm:A`, turn 3 without" announces on turns 2 and 3. A brand-new session pinned
  by request attribute hears the from-absent wording once (Q4).
- **Bookkeeping records.** A record the recorder creates has `setBy` = the executing caller, who need not be the
  session's owner. It grants nothing: every right over the session is checked against the owner source
  (`requireSessionOwner`), never against `setBy`. It has no `workspace`, so `byWorkspace` (which indexes only records
  with a `workspace`, MG §5.2) and therefore `SandboxAdministration.sessions` / `WorkspaceDetail` never list it as an
  attachment; `sessionOptions(...)` views show it with `workspace` and `unattached` empty. The listener and the
  sweep delete it with the session.
- **At-most-once** (EE-107 (3)): a result drained and then discarded (the turn cancelled between the tool returning
  and the result being recorded) loses the notice and still moves `lastWorkspaceId`. Accepted, as core recommends: the
  window is the gap between a tool's return and its transcript write.
- **Turn without tool calls** (EE-106): nothing is drained, nothing recorded; the next turn re-announces. The model of
  a no-tool turn touched no file, so it missed nothing it acted on.

### 6.5 Notice queue scope and forks (EE-107)

- **One queue per `SandboxExecutionEnvironment` instance** — already true: `resolve` builds a new environment, and a
  new `PendingNotices`, for every root and every fork request; nothing is shared or seeded.
- **A fact a fork discovers that its parent must hear.** Today (and after the move) a fork whose first connect
  re-creates the sandbox gets the "recreated" notice in *its* queue; the parent's next connect finds a RUNNING slot of
  a newer generation and says nothing, so the parent's model edits a tree it believes still holds its files —
  EE-107 (1). Fix, per instance: `PendingNotices` remembers the `(incarnation, generation)` of the slot it last
  connected; on every successful connect, if the instance had connected before, the pair differs, and the
  `ConnectedSlot.reset()` flag is false (this connect did not itself report an idle reopen, re-creation or loss — a
  flag, not a match on notice strings, so rewording a notice cannot double-announce), it queues *"the sandbox was
  re-created since this execution last used it (generation N); files under /workspace from before are gone"*. The
  comparison and update are one synchronized step, so parallel calls of one instance announce once. A first connect
  has no baseline and says nothing.
- **What it does not cover** (pre-existing, out of scope): detection is within one instance. A re-creation caused
  *between turns* by someone else — another session's turn in a shared `wsm:` workspace, or a fork of an earlier
  turn — is never announced to this session: its next turn's instance has no baseline, and `connect` reports only a
  re-creation it performs itself. Closing that needs a per-session record of the last seen generation (it would fit
  next to `lastWorkspaceId`); recorded as a WS §20 open question, not built here.
- **User-typed `/skill`** (EE-107 (2)): its tool calls drain the main turn's instance into the skill's scratch loop,
  and the main agent never hears. The sandbox cannot tell a scratch loop from the main loop; filed as a core gap (§10).
- **Per-command notices stay on the command** (`ShellCommandResult`, `ShellExecutionException`,
  `ShellTimeoutException`, `ShellCancelledException`, and a background command's final result read by `BashOutput`):
  `KILLED_NOTICE` and the cwd fallback. Everything `connect` reports, the switch, and the new generation notice go to
  `drainNotices()`. A `connect` failure keeps appending its notices to the `SandboxUnavailableException` message
  (`withNotices`) — that error *is* the tool result, so they are delivered with it.

---

## 7. Failure modes

| Case | Result |
|---|---|
| Request names a malformed, derived (`ws:`), or missing `wsm:` workspace | malformed/derived: `BindingRejectedException` at bind → unavailable environment, tools fail with the reason; missing: `mustExist` → "sandbox workspace … does not exist" at the first tool call; nothing is created |
| Request names a workspace the executing principal may not use | `connect`'s use check: "not permitted" at the first tool call; nothing provisioned |
| Request carries `environment.sandbox.slot` / `.profile` / any other `sandbox.*` key | `BindingRejectedException` naming the key |
| Request `sandbox.unattached` unparsable | `BindingRejectedException` naming the request attribute |
| Request `per-session` while the session or agent says `reject`, nothing attached | refused (strictest) |
| Non-String `environment.*` value | core makes the environment unavailable before the provider is called (C-EE102 §5); the sandbox never sees it |
| Routine resolver throws / returns null | core: unavailable environment for that fire (C-EE102 §5) |
| Options store fails during a session bind | as today: `SandboxUnavailableException`, no fallback — even when row 0 would have applied (the switch needs the record; failing is simpler and safe). Routines do not read it |
| Owner source store fails (`load` throws) | bind fails → unavailable environment; options call fails with the exception |
| Owner unknown with a source wired | bind rejected with the "set `SubmitRequest.owner`" message; options calls "unknown session" |
| `require-principal` without lookup and without records | startup refused (validator) |
| Listener: options store error | WARN; janitor sweep deletes it after `closed-retention` |
| Listener: the synchronous mark fails (store error, CAS exhausted) | WARN; the record is untouched and ends by the idle policy and `closed-retention` (as for a lost event). A re-created session with the same id meanwhile binds the old record — the residual case, needing a store failure exactly at delete time |
| Listener: deferred task fails, is rejected, or the node dies | the record is already `SESSION_DELETED`: the janitor resumes the CLOSING close after `close-resume-after` and deletes the CLOSED record on its first pass |
| Session id reused right after delete | `connect` sees `SESSION_DELETED` and answers "being removed; retry shortly" — never the deleted session's sandbox — until step 3 or the janitor deletes the record; then a fresh `ws:{sid}` |
| Background executor queue full | the task is dropped with a WARN: a deletion is finished by the janitor, a `lastWorkspaceId` write repeats one notice |
| Listener invoked for a never-existing id | nothing found, nothing done |
| `drainNotices()` races a connect on another thread of the same instance | `PendingNotices` is synchronized; each notice is returned once; one that arrives after the drain goes with the next tool result |
| Record write after drain fails / loses CAS | logged at DEBUG; the switch notice repeats once next turn (as today) |
| Hook command's shell triggers a re-creation | queued; drained onto the tool result the hook ran for (core drains after PostTool hooks) |
| A Java hook calls `drainNotices()` (contract violation, EE-108) | that notice is lost and an announced switch is recorded; documented, not prevented |
| Many notices | uncapped, as core and `Bash` (C-NT Q5) |

---

## 8. Compatibility (existing hosts)

| Host on 0.1.0 / current `[Unreleased]` | After this change |
|---|---|
| Wires `sessionOwnerLookup(...)` | unchanged; may add `sessionRecords(...)` and keep the lookup as fallback |
| Calls `SandboxWorkspaces.deleteSessionOptions` on delete | **compile error** — the method was `[Unreleased]` only; replace with the listener; for sessions deleted outside the router, `SandboxAdministration.deleteSessionOptions` or the janitor sweep (CHANGELOG migration note) |
| Switches exhaustively over `CloseCause` (event listener, custom store mapping) | new constant `SESSION_DELETED` (public enum since 0.1.0): add a branch; a store that persists the enum by name needs nothing |
| Supplies its own `SandboxScheduler` | unchanged — the interface is not touched; one-shot work goes to the new background executor, which it may also supply (`backgroundExecutor(...)`) |
| Calls `SandboxAdministration.deleteSessionOptions` | unchanged |
| Implements `SessionOptionsStore` | must implement `sessions(...)` (`[Unreleased]` SPI); run the updated contract |
| Implements `SandboxBindingPolicy` | additive: `BindingContext.requestAttributes()`; the decorator handles row 0 before the custom policy |
| Reads `ShellCommandResult.notices()` programmatically | environment notices no longer appear there (they reach the model through core); per-command ones still do |
| Sets non-String `environment.*` execution attributes for its own purposes | core 0.3.3's reservation makes those turns' environments unavailable — core's change, repeated in our CHANGELOG |
| Requires core | **0.3.3** (snapshot until released) |

---

## 9. Test strategy

Default tier (`./gradlew checkAll`), JUnit 5 + AssertJ, existing harnesses (`SandboxHarness`, `ManualScheduler`,
`ManualClock`, `RecordingProvider`, `LocalProcessSandboxProvider`), core's `InMemorySessionRecordStore`.

**#252 (acceptance: main turn, routine, fork, rights refusal)**
- `SessionOptionsBindingPolicyTest`: row 0 on a main turn → `wsm:A`, `mustExist`, `session:{sid}`; row 0 over a
  session option and over an agent default; routine context (no session, `executionId`) → `wsm:A`, `exec:{id}`;
  request `reject` refuses an unattached session and an unattached routine; request `per-session` cannot loosen a
  session `reject` nor an agent `reject`; malformed / `ws:` id / `sandbox.profile` key / bad rule → rejected; keys
  outside `sandbox.` ignored.
- `SandboxEnvironmentProviderTest`: a fork request carrying request attributes for `wsm:B` under a parent bound to
  `wsm:A` (by row 0) binds `wsm:A`; `BindingContext` exposes the request map.
- Rights: `ManagedRightsMatrixTest` gains the request-attribute column — owner, tenant-mate on `access: tenant`,
  tenant-mate on `principal`, other tenant, anonymous; the refusal is "not permitted" at `connect` and creates
  nothing; a SYSTEM routine with and without `allowed-system-principals`.
- `OrcaRuntimeSandboxE2ETest`: an `OrcaAgentExecutionRequest` with `executionAttributes(SandboxRequestAttributes
  .workspace(wsm))` runs `Bash` in the pre-created workspace; a `Task` fork of that turn runs in the same workspace.
  Routine path: if core's `RoutineExecutor` can be driven with a `RoutineExecutionAttributes` from this test source
  set cheaply, one case through it; otherwise the provider-level test with a hand-built routine `EnvironmentRequest`
  (`executionId` + `requestAttributes`) stands in, and the dev phase says which was done.

**#253 owner (acceptance: lookup from core)**
- `SessionOwnerLookupTest`: `recorded(store)` answers the owner `provision(id, agent, owner)` stored; empty for an
  owner-less record and a missing one; `orElse` consults the fallback only on empty; the `Supplier` form resolves late.
- `DefaultSandboxBindingPolicyTest` / `WorkspaceSandboxTest`: `sessionRecords(store)` alone makes `ws:{sid}` owned by
  the recorded owner while another principal binds first (WS §8.3's guarantee); owner-less session rejected with the
  `SubmitRequest.owner` hint; with fallback lookup it binds.
- `SandboxStartupValidatorTest`: `require-principal` passes with records only; refuses with neither.
- `SandboxWorkspacesTest`: set options as the recorded owner passes; as another principal refused.

**#253 deletion (acceptance: listener cleans state)**
- `SandboxSessionCleanupTest` (a queueing background executor the test drains by hand): when `onDeleted` returns,
  the options record is gone, `SESSION_OPTIONS_CLEARED("session deleted")` was emitted, `ws:{sid}` is already
  `CLOSING(SESSION_DELETED)` and **no provider call has been made on the calling thread**; after draining the
  executor, its sandbox is destroyed, its record deleted and `WORKSPACE_PURGED` emitted. An idle-closed and an
  explicitly closed `ws:{sid}` are marked and deleted (no sandbox calls). An attached managed workspace stays OPEN
  with its sandbox; unknown id → no-op; a throwing options store or workspace store → no exception, WARN; second
  firing → no-op.
- **Re-creation race** (review 1, B1): delete `S` with `ws:S` OPEN and RUNNING; *before* draining the executor,
  a turn of a re-created `S` connects → "being removed", no connection to the old sandbox, nothing provisioned;
  drain → record deleted; the next turn of `S` provisions a fresh `ws:S` (new incarnation) owned by the new owner.
  The same with the executor's task dropped: the janitor's resume (`ManualClock` past `close-resume-after`) and
  `deleteTombstone` reach the same end state.
- `SandboxJanitorTest`: `CLOSED(SESSION_DELETED)` deleted on the first pass, younger than `closed-retention`;
  `CLOSED(EXPLICIT)` still kept for `closed-retention`.
- `WorkspaceSandboxTest`: the default background executor is owned and shut down by `close()`; a supplied one is
  not; a full queue drops with a WARN.
- `SandboxJanitorTest`: `sweepDeletedSessions` deletes a record whose session does not exist and is older than
  `closed-retention`; keeps a younger one and one whose session exists; does nothing without records wired.
- `SessionOptionsStoreContract.sessionsPagesEveryRecordInOrder` (in-memory store runs it).

**#254 (acceptance: notice reaches a Read-only turn exactly once)**
- `OrcaRuntimeSandboxE2ETest` (scripted LLM, real core loop): an idle-closed workspace; turn of `Read`, `Read` → the
  first tool_result starts with `[environment] /workspace was reset…`, the second carries none. Same with an attached
  session that moved: the switch notice on the first `Read` only, and `lastWorkspaceId` recorded (after the scheduler
  runs) so the next turn hears nothing. A `Bash` result shows the drained line once and no copy in the command's own
  lines.
- `PendingNoticesTest` (new, unit): announced switch queued at first connect only; recorded only by the drain that
  returns it; silent switch recorded at connect; generation notice: baseline set by the first connect, emitted on a
  changed pair without a connect reset notice, not emitted when connect carried one, once under two concurrent
  connects.
- `SandboxShellIT` / `SandboxShellCaptureTest`: results and exceptions carry only `KILLED_NOTICE` / cwd notices; a
  hook command's connect notice is left in the queue for `drainNotices()`.
- Fork scope: a fork environment's re-creation does not appear in the parent's `drainNotices()` immediately; the
  parent's next connect queues the generation notice.
- `SandboxWorkspaceManagerTest`: a switch drained, then `clearSessionOptions` before the queued record write runs →
  the record keeps the detached workspace as `lastWorkspaceId` (fallback kept) and the next turn announces the move
  back; `recordLastWorkspace` with an absent version creates a record; a row-0 redirect on a rule-only record is
  announced; a bookkeeping record is not listed by `byWorkspace` / `SandboxAdministration.sessions`.

**Docker tier** (`integrationTest`, run if Docker is available; the dev phase says if it was not):
`ManagedWorkspaceSandboxIT` gains one request-attribute turn and one listener-driven close of `ws:{sid}` against a
real server. **E2E/k8s**: unchanged.

`ReleaseGateMatchesCiGateTest` unchanged; `scripts/release.sh` refusing the SNAPSHOT pin is expected.

---

## 10. Core gaps to file in `$RUN_DIR/core-gaps.md` (dev phase, with evidence)

1. **No narrow owner read (SO-5).** `SessionOwnerLookup.recorded` costs a full `SessionRecordStore.load` (transcript
   included) on every main-turn root bind. Evidence: `SessionRecordStore` has `load`/`exists` only; the sandbox calls
   it from `DefaultSandboxBindingPolicy.sessionOwner` per turn.
2. **User-typed `/skill` drains the main turn's notices into a scratch loop (EE-107 (2)).** The provider cannot
   distinguish the loops; only core can route or re-queue.
3. **Hooks can drain (EE-108)** — affects the sandbox because a drain records `lastWorkspaceId`.
4. *(Not a gap, state it)*: SO-3's peer-node fan-out is not needed by this module (§6.3).

Only items the implementation actually hits with evidence go into the file.

---

## 11. Commit sequence (on the run branch; messages reference `kangwoo/aimon-core#25x`)

1. `build: pin aimon-core 0.3.3-SNAPSHOT for #252/#253/#254` — repository block, catalog comment, compile fixes.
2. `feat(binding): per-execution workspace and rule from request attributes (kangwoo/aimon-core#252)` —
   `BindingContext`, `SandboxRequestAttributes`, row 0, switch from row 0, `WorkspaceSwitch.optionsVersion`,
   `recordLastWorkspace` create path, tests.
3. `feat: session owner from core and a deletion listener (kangwoo/aimon-core#253)` — `SessionOwnerLookup.recorded`
   / `orElse`, `Builder.sessionRecords`, validator, background executor, `CloseCause.SESSION_DELETED` (connect and
   janitor rules), `SandboxSessionCleanup`, `sessionDeleted` / `finishSessionDeletion`,
   `SessionOptionsStore.sessions` + contract, janitor sweep, removal of owner-facing `deleteSessionOptions`, tests.
4. `feat(environment): environment notices through drainNotices (kangwoo/aimon-core#254)` — environment override,
   `SandboxShell` cleanup, `PendingNotices` generation tracking, `clearSessionOptions` fallback removal, tests.
5. `docs: core 0.3.3 seams — design, departures, README, CHANGELOG`.

---

## 12. Open questions (not silently assumed)

- **Q1 — Closing `ws:{sessionId}` on deletion.** Designed as on (§6.3). A host that implements "clear conversation"
  as delete-and-recreate under the same id would lose its sandbox each time. Is that acceptable, or should the
  listener be options-only by default with the close opt-in (`sessionLifecycleListener(boolean closeDerived)`)?
- **Q2 — Removing the owner-facing `deleteSessionOptions`.** Designed as removed (it is `[Unreleased]`, and its only
  purpose is now served by the listener). Keep it, deprecated, if anyone already built against the unreleased API?
- **Q3 — Owner read cost.** One full record load per main-turn bind when `sessionRecords` is wired. Accept until core
  offers a narrow read (designed), or add a bounded node-local cache despite the id-reuse hazard of §2.2?
  **Resolved (2026-10-18):** core added `SessionRecordStore#loadOwner` (kangwoo/aimon-core#262, SO-5), and
  `SessionOwnerLookup.recorded` now calls it — the owner alone, no transcript. No cache was added.
- **Q4 — First-turn wording for request-attribute sessions.** A session pinned by request attribute from its first
  turn hears "now runs in sandbox workspace X; files and shell state from any earlier workspace of this session are
  not here" once. Alternative: probe whether the derived workspace exists and stay silent when it does not (one more
  store read on that path only).
- **Q5 — Sweep grace.** Reusing `closed-retention` (default 7 d) as the grace for options whose session record is
  gone, rather than a new setting. Acceptable?
- **Q6 — Generation notice (§6.5).** It fixes EE-107 (1), a gap that predates this change. Keep it in this change
  (designed, commit 4) or split it out?
- **Q7 — `require-principal` and owner-less sessions.** Designed as "rejected unless the host lookup answers" (§5.2),
  with no fallback to the executing principal. Confirm that strictness is wanted even for sessions created before
  the host started setting `SubmitRequest.owner`.
- **Q8 — Options before the first submit.** With records only, `setSessionOptions` refuses a not-yet-provisioned
  session (§6.2). Is documenting the three workarounds enough?
- **Q9 — Row 0 when the options store is down.** Designed to fail the session bind (one rule for every session bind).
  Should a request attribute instead bind without switch bookkeeping when the store is unreadable?

---

## 13. Review 1 — resolution

| Finding | Resolution |
|---|---|
| **B1** derived close deferred past the lease; a re-created session connects into the deleted session's sandbox, then loses it under its turn | The state change that matters is now synchronous under the lease: one CAS marks `ws:{sid}` `CLOSING`/`CLOSED` with the new persisted cause `SESSION_DELETED`; `connect` refuses such a record ("being removed"); only terminate/destroy/wait and the record delete are deferred, guarded on the captured incarnation. The persisted cause lets the janitor finish an interrupted deletion without `closed-retention`. Race test added (§2.1 item 4, §2.2, §3.4, §6.3, §7, §8, §9) |
| **B2** `SandboxScheduler.execute` does not exist; SPI change unlisted; heartbeat threads would be held | `SandboxScheduler` untouched. A new manager-owned background executor (`WorkspaceSandbox.Builder.backgroundExecutor`, default two daemon threads `aimon-sandbox-background-N`, bounded queue of 1 000, drop with WARN, shut down by `close()`, a supplied one borrowed) runs both one-shot jobs, apart from heartbeats. Tests use a queueing executor; `ManualScheduler` needs no change (§2.2, §3.3, §3.5, §5.3, §7, §8, §9) |
| n1 idle-closed `ws:{sid}` skipped, keeps the old owner for 7 d | CLOSED records (idle or explicit) are marked `SESSION_DELETED` too and deleted by step 3 / the janitor (§6.3) |
| n2 `clearSessionOptions` fallback also covers a lost switch write | Kept, comment updated; test added (§3.4, §6.4, §9) |
| n3 row 0 silent on a rule-only record | Row 0 with no `lastWorkspaceId` always announces (§6.4) |
| n4 routine principal is the task owner, not `system()` | Corrected; README/tests use a task owner (§6.1) |
| n5 generation notice is per-instance only | Stated as a pre-existing, out-of-scope gap with where a fix would live (§6.5) |
| n6 notice-string matching | `ConnectedSlot.reset()` flag (§3.3, §3.4, §6.5) |
| n7 bookkeeping records with a non-owner `setBy` | Stated: grants nothing, not listed as an attachment; test added (§6.4, §9) |
| n8 point hosts deleting outside the router somewhere | Manager/store Javadoc and the CHANGELOG migration note name the admin path and the sweep (§3.4, §8) |

---

## 14. Implementation departures

Where the code and documents of this change differ from §1–§13, and why. The open questions of §12 that reach beyond
this change are in WS §20, each pointing back here: Q1 (closing `ws:{sid}` on deletion, with the cost below added), Q3
(owner read cost), Q5 (sweep grace), Q7 and Q8 (owner-less sessions under `require-principal`; options before the first
submit), plus two limitations §6.3 and §6.5 state (shell state left in a managed workspace; re-creations between turns).
Q2, Q4, Q6 and Q9 were built as designed and concern only this change. Review 2's non-blocking items are answered in
the first table.

### Review 2 — what was done with its items

| Item | Done |
|---|---|
| §11 commit 4 says the `clearSessionOptions` fallback is removed | The body (§3.4 (a), §6.4, §9) won: the fallback is kept and its comment names the case it now covers. `ManagedSessionsIT.aClearBeforeTheRecordWriteStillAnnouncesTheMove` pins it |
| The asynchronous create of a `lastWorkspaceId` record can bring a deleted session's state back | `recordLastWorkspace` with no expected version skips when core's session store is wired and no longer has the session. The residual is the window between that `exists` check and the `put`; without `sessionRecords` there is no check, and the janitor has no sweep either. `SandboxSessionCleanupTest.aQueuedRecordWriteAfterTheDeletionCreatesNothing` |
| `connect` ran `checkUse` before looking at the state, so a re-created session with another owner heard "not permitted" | `connect` checks for a non-OPEN record with cause `SESSION_DELETED` first and answers "this session's sandbox workspace is being removed; retry shortly" whoever asks. It reveals nothing a `ws:{sid}` id did not already: the id is the session's. The race test re-creates the session for another owner |
| `WORKSPACE_CLOSED` emitted twice for a deleted OPEN workspace | The mark emits nothing; `closeProcedure` emits `WORKSPACE_CLOSED` once, when the record reaches CLOSED. The record's deletion emits `WORKSPACE_PURGED` (cause "session deleted") — from the deferred step or from the janitor |
| The generation notice could be told twice when a parallel call's reset notice arrives second | `PendingNotices` remembers which slot a still-queued generation notice is for; a reset notice for the same `(incarnation, generation)` removes it. A generation older than the one last seen (a call that read the slot before it was re-created) changes nothing. If the drain ran in between, the model hears both — once each |
| Shell state of the deleted session in a managed workspace | Not removed, as designed; README and WS §20 state it |
| How `close()` stops the owned executor | `shutdown`, then up to `WorkspaceSandbox.BACKGROUND_CLOSE_WAIT` (5 s), then `shutdownNow`. A deletion cut short this way keeps its `SESSION_DELETED` mark and the janitor finishes it; an interrupted `awaitSandboxesGone` gives up as it does for any interrupt |
| §9 "after the scheduler runs" | The tests drain a `QueueingExecutor` (the background executor), not the scheduler |
| Q1 should name the failed first turn | WS §20's entry and the README say that a session re-created under the same id gets "being removed" from its tools for up to `close-wait` — or, when the deferred close is dropped, `close-resume-after` plus one janitor period (§6.3; PR #27 review corrected the README) |

### Where the design was wrong or incomplete

- **The operator's `deleteSessionOptions` could not clean up a session deleted outside the router (§2.3, §8).** It ran
  `requireSessionOwner`, which with `sessionRecords(...)` alone answers "unknown session" for exactly the sessions it
  is kept for. It now needs no owner source at all — neither wired nor knowing the session (PR #27 review: row 0 leaves
  a `lastWorkspaceId` record on a single-tenant host too, where nothing else could remove it); the event then names
  the record's `setBy`. The owner-facing set, clear and view keep the full check.
- **`recordLastWorkspace` (§3.4 (b)) is two methods.** The environment calls the public, internal
  `recordLastWorkspaceLater(session, OptionalLong, workspace, setBy)`, which hands the write to the background
  executor; the write itself is package-private. A silent switch recorded at the first connect goes the same way.
- **`sessionDeleted` takes the event's owner (§3.4 (c))** — `sessionDeleted(SessionId, Optional<Principal>)` — so the
  `SESSION_OPTIONS_CLEARED` event names the owner core recorded; without one, the record's `setBy`.
- **The manager's internal builder defaults its background executor to inline execution.** Only `WorkspaceSandbox`
  builds the pool of §3.5; a hand-wired manager (tests, custom assemblies, already "Internal" and unvalidated) runs
  the work on the calling thread. `SandboxHarness` passes inline by default too, so a drain's write has happened when
  the drain returns, and tests that need the deferral pass a `QueueingExecutor`.
- **A second firing of the listener resubmits the deferred step** when the record is still marked: the step finds
  nothing to do, or finishes what a dropped first task left. §6.3 called the second firing a no-op; it is one in effect.
- **The janitor's sweep of deleted sessions emits no event** (it logs), like the existing sweep of purged
  attachments. The listener's deletion emits `SESSION_OPTIONS_CLEARED` as §3.4 said.
- **`WorkspaceSwitch.notice()`'s from-absent wording** applies to every announced switch without a `from`, which also
  covers a first attachment through session options (rows 1–2), not only row 0. Its text there changed from "…from the
  previous workspace…" to "…from any earlier workspace of this session…".

### Tests placed or shaped differently from §9

- **The routine path** is the provider-level request (`SandboxHarness.routine(executionId, principal,
  requestAttributes)`), not core's `RoutineExecutor`: driving a routine fire from this source set needs core's
  scheduling wiring, and `EnvironmentRequestAttributes.extract` — the one rule both executors share — is exercised by
  `SessionOptionsBindingPolicyTest.theHostHelpersSpellCoresKeys`. The main-turn path does go through the real loop
  (`OrcaRuntimeSandboxE2ETest.aRequestAttributeTurnAndItsForkRunInTheNamedWorkspace`).
- **Deletion, janitor and executor cases live in `SandboxSessionCleanupTest` and `WorkspaceSandboxTest`** rather than
  `SandboxJanitorTest`; the generation and fork-queue cases in `SandboxEnvironmentProviderTest`; the
  `SandboxWorkspaceManagerTest` cases of §9 in `ManagedSessionsIT` (fallback after a lost write, row-0 bookkeeping record,
  announcement both ways) and `SandboxSessionCleanupTest`. "A full queue drops with a WARN" is a refusing executor, not
  a real full pool.
- **`SandboxShellIT` / `SandboxShellCaptureTest` were not extended**: the existing `KILLED_NOTICE` and cwd cases already
  assert the per-command notices, and the hook case is `ManagedSessionsIT.aHookDoesNotConsumeTheSwitchNotice`. Tests that
  read a reset or a re-creation off a `Bash` result now read `drainNotices()`.
- **Owner-from-core** cases are in `WorkspaceSandboxTest` (assembly wiring) and `SessionOwnerLookupTest`, not
  `DefaultSandboxBindingPolicyTest` and `SandboxWorkspacesTest`.

### Smaller points

- **Commits differ from §11.** The work landed as three commits — the pin (`build:`), one `feat:` for all three seams
  and one `docs:` — instead of §11's five, followed by separate fix commits from the PR #27 reviews.
- `SandboxRequestAttributes.unattached(rule)` writes `per-session` / `reject`, the spelling `UnattachedRule.parse` and
  definition files use.
- The Docker tier ran (Docker 29.2.1): `ManagedWorkspaceSandboxIT` gained a request-attribute turn and a
  listener-driven close of `ws:{sid}` against a real server.
