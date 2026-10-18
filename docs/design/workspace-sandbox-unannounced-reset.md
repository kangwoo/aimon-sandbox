# Design — durable "unannounced reset" per session (kangwoo/aimon-sandbox#28)

> Status: **IMPLEMENTED**. The body below is the design as approved (r1; review 1 passed it with non-blocking items,
> answered in §12). Where the code departed from it, §12 says so and why. The architecture document
> [`workspace-sandbox.md`](workspace-sandbox.md) has absorbed the change (§5.1, §7, §10.1, §15, §16), its §20 carries the
> open questions of §11 that outlive it, and [`workspace-sandbox-management.md`](workspace-sandbox-management.md) §17
> says where its §5.2 and §6.4 no longer describe the code.
>
> Base: aimon-sandbox `847dad2` (branch `herdr/durable-unannounced-reset`). Core
> reference: aimon-core checkout at `/Users/kangwoo/Workspaces/github.com/kangwoo/aimon-core` (read-only), pinned as
> `0.3.3-SNAPSHOT`. **WS** is `docs/design/workspace-sandbox.md`, **C033** is
> `docs/design/workspace-sandbox-core-033.md`, **MG** is `docs/design/workspace-sandbox-management.md`, **C-NT** is
> core `docs/design/tool/execution-environment-notices.md`, and EE-106/107/108 are core
> `docs/backlog/execution-environment-open-items.md`.
>
> Grounding — every existing name below was read on the base commit: `PendingNotices` (`addAll(ConnectedSlot)`,
> `addAll(incarnation, generation, reset, notices)`, `drain()`, the `recorder` `Consumer<WorkspaceSwitch>`, the
> generation-notice bookkeeping), `SandboxExecutionEnvironment` (one `PendingNotices` per instance, recorder →
> `manager.recordLastWorkspaceLater`), the four connect sites `SandboxShell:173-174`, `SandboxFileSystem:66-67`,
> `SandboxStaging:95-96`, `SandboxContentSearch:67-68` (each `manager.connect(binding)` then `pending.addAll(slot)`),
> `SandboxExecutionEnvironmentProvider.bindFork` (`workspaceSwitch(null)`), `SandboxWorkspaceManager.connect` /
> `Connect` (`RESET_NOTICE` on an idle reopen, "the sandbox was recreated (generation N)…" on a TERMINATED slot, "the
> sandbox was lost and has been recreated…" on `SandboxNotFoundException`, the `reset` flag, `withNotices` appending
> notices to a `SandboxUnavailableException`), `reopened(...)` (new incarnation, every slot terminated),
> `ConnectedSlot` (`workspace()`, `slot()`, `incarnation()`, `generation()`, `reset()`), `recordLastWorkspaceLater` /
> `recordLastWorkspace` (strict CAS on the policy-read version; create-when-absent guarded by
> `sessionRecords().exists`), `clearSessionOptions` (deletes a record "with nothing left", `.or(record::workspace)`
> fallback), `deleteSessionOptions`, `sessionDeleted`, `SessionOptionsBindingPolicy.bind` (one `options.find(session)`
> per session bind) and `withSwitch` (`record.isEmpty() && !requested → no switch`), `WorkspaceSwitch`,
> `SessionSandboxOptions` (`workspace`, `unattached`, `lastWorkspaceId`, `setBy`, `updatedAt`, `version`),
> `SessionOptionsStore` (`find`/`put`/`delete`/`byWorkspace`/`workspaces`/`sessions`/`deleteByWorkspace`),
> `InMemorySessionOptionsStore`, testkit `SessionOptionsStoreContract`, `SandboxSessionCleanup`, `WorkspaceSandbox`
> (wraps every policy in `SessionOptionsBindingPolicy`, line 140), `SandboxUnavailableException` (public since 0.1.0),
> `SandboxHarness`, `OrcaRuntimeSandboxE2ETest` (skill hooks from YAML), `ManagedWorkspaceSandboxIT` (`@Tag("docker")`).
> Core: C-NT §3 (drained after PostTool hooks, on every model-facing result including tool errors, not on workflow
> script or routine steps), §4 ("must not block on remote I/O for long"; "a provider must not put the same fact in
> both"), §5, `SkillHookSetParser` (`onStop` shell actions exist).

---

## 1. The problem, restated

An environment-level reset notice — "/workspace was reset after an idle close", "the sandbox was recreated (generation
N)", "the sandbox was lost and has been recreated" — is produced by the `connect` that performs the reset, queued in
that execution environment's in-memory `PendingNotices`, and delivered only when core calls `drainNotices()` after a
model-facing tool call. A connect can also happen on a path core never drains: a Stop (`onStop`) hook's shell action,
a workflow-script tool call, a staging call after the last tool result. If the instance dies after such a connect,
the notice dies with it; the next turn's fresh instance connects to a slot that is already RUNNING, `connect` reports
nothing, and its per-instance generation tracking has no baseline. The model goes on believing its earlier files are
under `/workspace`. C033 §2.2 solved the same loss for the workspace-switch notice by recording `lastWorkspaceId` only
on drain, so an undelivered switch is recomputed and re-announced next turn; resets have no such durable residue. The
fix is to make an *undelivered* reset durable per session, in the shared store the module already has, re-announce it
on the session's next root execution, and clear it on the drain that actually delivers it — without double delivery on
the ordinary path, without sharing queues with forks, without hot-path I/O, and consistent with session deletion.

---

## 2. Approach, and what was rejected

### 2.1 Chosen approach

**An `unannouncedReset` field on the session's options record, set synchronously by the root environment whose
`connect` reset the sandbox, replayed at the first successful connect of the session's next root environment, and
cleared by an acknowledgement submitted from the drain that returned the notice.**

1. **Where it lives.** `SessionSandboxOptions` (one record per session, `SessionOptionsStore`, version CAS, shared by
   every node) gains `Optional<UnannouncedReset> unannouncedReset()` — `(workspace id, slot name, at)`. It sits next to
   `lastWorkspaceId`, the other "what has the model been told" fact. A session without a record gets a bookkeeping
   record, exactly as C033 §6.4 does for `lastWorkspaceId`.
2. **When it is set.** In the environment layer, *before* the notice is queued in memory: a root environment that has
   a session (new binding field, §5.2) and whose `connect` reported `reset()` writes the field synchronously (read +
   CAS, create when absent). The reset path is already provisioning a sandbox (seconds); one options read + write is
   noise there. Writing before queueing means no drain can ever return the notice before it is durable, so a set can
   never land after its own clear.
3. **When it is replayed.** `SessionOptionsBindingPolicy.bind` already reads the session's record once per root bind;
   it hands the field to the binding. At the environment's first successful connect, if the field names the
   workspace and slot just connected and this connect did not itself report a reset, `PendingNotices` queues one
   replay notice (§5.5) after the switch notice and before the connect's own notices. No extra I/O.
4. **When it is cleared.** Only by a drain that returned a notice covering it (the instance's own reset notice or the
   replay). `drain()` hands an *acknowledgement* to the manager's background executor (never blocking the drain, as
   today): one CAS write that clears the field **iff it still equals the value this instance knows** (a newer reset
   written since is left alone), records `lastWorkspaceId` if a switch was delivered (today's write, folded in), and
   deletes the record if nothing is left in it.
5. **Dedup with the in-memory queue.** On the ordinary path the same fact exists twice — in memory (delivered) and
   durable (cleared by the ack) — but only the in-memory one is ever shown in this instance; the durable one is shown
   only by a *later* instance, and only if no ack cleared it. The ack is submitted at the drain, mid-turn, and at least
   one LLM call follows every drain before the turn can end, so on the ordinary path it has landed long before the
   next turn's bind reads the record. Within one instance the replay is queued at most once (first-connect latch) and
   never alongside a fresh reset of the same slot.
6. **Forks** never read, set or clear it (the fork binding drops the field, as it drops the switch). Their queues stay
   per instance (EE-107); a live parent still hears a fork's re-creation through C033 §6.5's generation notice.
7. **Connect failures that reported a reset** (reopen succeeded, re-provisioning then failed) are routed through the
   same queue instead of the exception message (§6.3), so a hook's failed connect no longer swallows the reset either.

### 2.2 Rejected alternatives

| Alternative | Why rejected |
|---|---|
| **Core drains at turn end** (the issue's second option) | aimon-core's decision (EE-106/EE-107), out of scope by TASK.md. It would also not suffice: a turn-end drain has no model-facing result to put the notice on (the Stop hook runs after the last LLM call), so core would have to carry it into the next turn's context — the EE-106 "turn-start reminder" question, plus "is that a hook result?". A sandbox-side durable record works with core as it is and also covers workflow scripts and staging, which no turn-boundary drain sees. |
| **A flag on the workspace record's slot** (`SandboxSlot.unannouncedReset`), cleared on drain | Zero extra reads (connect reads the record anyway), but a slot is shared: every session attached to a `wsm:` workspace connects it. The first session to drain would clear the flag for all, and the session that caused the reset — the one that must hear it — might never. Tracking "which sessions heard" on the slot makes the workspace record grow with its sessions, which WS §5 / MG §5.2 forbid. A `SandboxSlot` field is also a schema change for host `SandboxWorkspaceStore`s, an SPI released in 0.1.0; `SessionOptionsStore` is `[Unreleased]`. |
| **Per-session "last seen (incarnation, generation)"**, recorded on every drain, compared at the next turn's first connect | The general fix: it would also close WS §20's "턴 사이의 재생성 알림" (re-creations by another session or an earlier turn's fork). But it writes on the ordinary path — an async write on the first drain of every session and of every new generation — and gives *every* session that touches a sandbox an options record, which on a host without the lifecycle listener and without `sessionRecords` (no janitor sweep) accumulates without bound. It also changes behaviour beyond #28 (announcing other sessions' re-creations in a shared workspace, a product decision). Kept as the documented way to close that WS §20 item; this design's field does not preclude it (Q2). |
| **Record durable state only on paths known not to be model-facing** (`ExecutionOptions.isHook()`) | The shell knows a hook, but a workflow script's tool call reaches the same `SandboxFileSystem`/`SandboxShell` methods as a model's, and staging cannot tell either. A partial fix that leaves two of the issue's three paths open. |
| **Set the durable state asynchronously** (background executor, like the switch write) | The set and the ack would race on a two-thread executor: the ack could run first and the set then leave a stale field that is replayed next turn — a double delivery on the ordinary path. Synchronous set before queueing removes the race by construction; its cost sits on the provisioning path only. |
| **Synchronous ack inside `drainNotices()`** (no window at all) | C-NT §4: the drain must not block on remote I/O. C033 moved the `lastWorkspaceId` write off the drain for the same reason. The async window is bounded by "the rest of the current turn" (§2.1 item 5). |
| **Node-local memory of undelivered notices** (keyed by session, survives the instance) | Not multi-node: the next turn may run on another node; lost on restart. The shared options store is the only per-session state every node sees. |
| **Forks also set the field** (so a fork's re-creation reaches the parent's next turn when the parent never connects again) | The parent instance, which read the record at its bind, cannot learn that a fork wrote the field. If the parent then connects the re-created slot (no generation notice: it had no baseline yet) and its model creates files in it, the field survives and the next turn is told "files from before are gone" — about files the model made after the reset. A false replay is worse than the pre-existing gap, which C033 §6.5 and WS §20 already record. Q3. |
| **Store the original notice text** in the record | Model-facing wording would be persisted in host databases and frozen there across rewording; the original texts also differ by cause. A fixed replay wording built in code from `(workspace, slot)` is enough: the model only needs "files from before are gone", and the original was never shown. |
| **Keep reset notices in the `SandboxUnavailableException` message and also write the field** on a failed connect | A model-facing failure would show the reset in the error now and replay it next turn (no drain ever "returns" the message text). Routing the notices through the queue (§6.3) gives one channel, one delivery, and also fixes the in-turn hook case. |

---

## 3. Concrete changes by file

### 3.1 `aimon-sandbox` — `at.aimon.sandbox.workspace`

- **New `UnannouncedReset`** (public, immutable value; §5.1). Equality on all three fields; `at` as the store
  returned it (stores may round an `Instant`, so the environment always keeps the *stored* value as its key).
- **`SessionSandboxOptions`**: field + accessor `unannouncedReset()`, builder method, `toBuilder`, `equals`,
  `toString`. Class Javadoc names it among the record's contents. `Update`'s Javadoc ("the record's
  `lastWorkspaceId` is kept") gains "and its unannounced reset".
- **`SessionOptionsStore`** Javadoc: `deleteByWorkspace` keeps "each record's other fields" — already true; state
  that `unannouncedReset` is one of them. No new method.
- **`InMemorySessionOptionsStore`**: persists the field (it copies the record; check that `put` keeps it).
- **`SandboxWorkspaceManager`**:
  - (a) **new** `Optional<SessionSandboxOptions> markResetUnannounced(SessionId, UnannouncedReset, WorkspaceOwner
    setBy)` — *Internal*, synchronous, never throws: re-read, then CAS `put` with the field replaced (create a
    bookkeeping record when absent — skipped when `sessionRecords` is wired and `!exists(session)`, as in
    `recordLastWorkspace`); retry on `StaleVersionException` up to `settings.casRetries()`; on failure WARN and return
    empty. Returns the stored record (its version and stored field are the environment's new baseline).
  - (b) `recordLastWorkspaceLater` / `recordLastWorkspace` are **replaced** by `acknowledgeLater(SessionId,
    Acknowledgement)` (Internal, submits to `background`) and package-private `acknowledge(...)` (§5.3, §6.2).
  - (c) `clearSessionOptions`: a record is "with nothing left" only when it has neither `lastWorkspaceId` (after the
    existing fallback) nor `unannouncedReset`; otherwise it is rewritten keeping the field.
  - (d) `setSessionOptions`: keeps the field (it builds from `toBuilder()` of the current record — verify, and say so
    in the Javadoc).
  - (e) `deleteSessionOptions` (operator) and `sessionDeleted` (listener): unchanged — they delete the record outright,
    field included. Javadoc: "an unannounced reset goes with it; the session is gone or being cleaned up".
  - (f) `Connect.withNotices`: unchanged message for non-environment callers, but the exception it builds is the new
    subclass of §3.1 (g) carrying the bare reason, the notices, the `reset` flag and the workspace/slot.
  - (g) **new** `SandboxResetUnavailableException extends SandboxUnavailableException` (name open to the
    implementer; Internal): `reason()`, `notices()`, `reset()`, `workspaceId()`, `slot()`. Existing `catch
    (SandboxUnavailableException)` sites keep working; the message is unchanged for them.
- **`SessionOptionsView`**: unchanged (Q5).

### 3.2 `aimon-sandbox` — `at.aimon.sandbox.binding`

- **New `SessionBookkeeping`** (public value, Internal; §5.2): `session()`, `optionsVersion()` (`OptionalLong`, empty
  when no record), `unannouncedReset()` (`Optional`).
- **`SandboxBinding`**: new optional field `sessionBookkeeping()` with builder method (Javadoc: set by
  `SessionOptionsBindingPolicy`; a custom policy leaves it alone), copied by `toBuilder`, included in `equals` /
  `toString` like `workspaceSwitch`.
- **`SessionOptionsBindingPolicy.bind`**: for every bind with a session, attaches `SessionBookkeeping(session,
  record.version, record.unannouncedReset)` — from the record it already read. No new read.
- **`SessionOptionsBindingPolicy.withSwitch`**: a record carrying *only* an unannounced reset (no `workspace`, no
  `unattached`, no `lastWorkspaceId`) is treated as absent. Otherwise the bookkeeping record this design creates for a
  derived-workspace session would turn the next bind into a silent switch, record `lastWorkspaceId`, and leave a
  permanent record behind where today there is none.

### 3.3 `aimon-sandbox` — `at.aimon.sandbox.environment`

- **`PendingNotices`** becomes the one place that connects-and-queues:
  - constructor takes `(WorkspaceSwitch, SessionBookkeeping, NoticeLedger)` instead of the `Consumer` recorder;
  - **new** `ConnectedSlot connect(SandboxWorkspaceManager manager, SandboxBinding binding)` — `manager.connect`, then
    on success: if `slot.reset()` and bookkeeping present → `ledger.markResetUnannounced(...)` **outside the lock**,
    then `addAll(slot)`; on `SandboxResetUnavailableException` with `reset()`: the same mark, queue its notices, and
    rethrow a plain `SandboxUnavailableException(reason, cause)` (§6.3);
  - `addAll` gains the first-connect replay and the `ackDue` bookkeeping (§6.1); `drain` submits one acknowledgement
    when a switch or an `ackDue` was delivered (§6.2);
  - existing generation-notice logic unchanged.
- **New package-private `NoticeLedger`** interface — `mark(UnannouncedReset) → Optional<SessionSandboxOptions>` and
  `acknowledge(Acknowledgement)` — with the production implementation a pair of lambdas over the manager built in
  `SandboxExecutionEnvironment`'s constructor (where the recorder lambda is today). Lets `PendingNoticesTest` drive it
  with a fake.
- **`SandboxShell:173`, `SandboxFileSystem:66`, `SandboxStaging:95`, `SandboxContentSearch:67`**: `manager.connect
  (binding); pending.addAll(slot)` → `pending.connect(manager, binding)`.
- **`SandboxExecutionEnvironment`**: builds `PendingNotices` with the binding's bookkeeping and the ledger; class
  Javadoc adds one sentence on the durable residue.
- **`SandboxExecutionEnvironmentProvider.bindFork`**: `.sessionBookkeeping(null)` next to `.workspaceSwitch(null)`,
  with the reason (§2.2 row "forks also set").

### 3.4 `aimon-sandbox-testkit`

- **`SessionOptionsStoreContract`**: round-trip of `unannouncedReset` (present, absent, replaced, cleared);
  `deleteByWorkspace` keeps it; `sessions(...)` returns records holding only it. Both in-memory contract tests pick it
  up automatically.

### 3.5 Docs

- **WS** (Korean): §7 (notices) — one paragraph: an undelivered reset is kept on the session's options record and told
  again on the next root execution; §10.5 or wherever session options' contents are listed — the new field; §20 —
  **new entry** for #28 marking the hook/workflow/staging loss as closed by this change, and amending the
  "턴 사이의 재생성 알림" entry: the session's *own* never-delivered reset is now covered; re-creations by other
  sessions or by a fork whose parent never connects again remain, with the "last seen generation" record as the way
  to close them.
- **MG** §5.2 (record fields) — the new field and its life cycle, if MG still describes the record (C033 §14 says MG
  §17 lists what it no longer describes; follow that).
- **New design record** `docs/design/workspace-sandbox-unannounced-reset.md` — this document, as approved, with an
  implementation-departures section added at the end (the C033 pattern).
- **CHANGELOG `[Unreleased]`** under "Changed: environment notices reach every tool result": a bullet "a reset that a
  hook, a workflow step or a staging call caused is told on the session's next tool result, even a turn later"; under
  "Behaviour changes": "a custom `SessionOptionsStore` must persist `unannouncedReset`; run the updated contract" and
  the `recordLastWorkspaceLater` replacement is Internal (no entry needed).
- **README** (`modules/aimon-sandbox/README.md`): if it describes session options' fields or the notice paths, one
  sentence each.

---

## 4. What core gives, and what this design relies on

- `drainNotices()` is called after PostTool hooks on every model-facing result, tool errors included (C-NT §3, §5) —
  so "returned by a drain" ⇒ "on a result the model sees" (at-most-once, EE-107 (3)).
- Workflow-script tool calls, routine steps and staging are never drained (C-NT §5 "deliberate"); hooks' shell actions
  run in the same instance's shell (EE-9) and are never drained either.
- One turn of a session at a time (session lease / turn gate, used by C033 §6.3 too): the set of turn T happens before
  turn T+1's bind reads the record.
- The session options store is shared and per-key linearizable (it already has to be, for CAS).

**No core change is required.** A core "environment discarded" signal, or EE-106's turn-start drain, would let the
sandbox skip the durable write on the ordinary path; neither is needed for correctness. Whether to file it in
`core-gaps.md` is Q6.

---

## 5. Data and interface shapes

### 5.1 `UnannouncedReset` (workspace package)

```java
public final class UnannouncedReset {
    SandboxWorkspaceId workspace();   // the workspace whose slot was reset
    String slot();                    // "primary" today
    Instant at();                     // when the reset was observed; part of the identity
    static UnannouncedReset of(SandboxWorkspaceId workspace, String slot, Instant at);
}
```

Persisted by host stores as three columns (or a small embedded value). One per session: a newer reset replaces an
older one — both say the same thing, "files from before are gone".

### 5.2 `SessionBookkeeping` (binding package), on `SandboxBinding`

```java
public final class SessionBookkeeping {
    SessionId session();
    OptionalLong optionsVersion();                 // version the policy read; empty: no record
    Optional<UnannouncedReset> unannouncedReset(); // what the record held at bind
}
// SandboxBinding
Optional<SessionBookkeeping> sessionBookkeeping(); // root binds with a session only; never a fork, never a routine
```

`WorkspaceSwitch.optionsVersion()` stays as it is (it is public and documented); `PendingNotices` keeps its own
*known version*, initialised from the bookkeeping and advanced by every successful mark, and the acknowledgement uses
that — not the switch's — so a mark earlier in the same instance does not make the switch write lose its CAS.

### 5.3 `Acknowledgement` (environment → manager)

```java
record Acknowledgement(SessionId session,
                       OptionalLong expectedVersion,                 // the instance's known version
                       Optional<SandboxWorkspaceId> lastWorkspace,   // a delivered (or silent) switch's target
                       Optional<UnannouncedReset> delivered,         // the reset the drained notices covered
                       WorkspaceOwner setBy) {}
```

`acknowledge` (on the background executor, best effort, never throws):

```
loop up to casRetries:
  current = find(session)
  if absent:
      if lastWorkspace present && expectedVersion empty && (no sessionRecords || exists(session)):
          create { lastWorkspaceId }                       -- today's recordLastWorkspace create path
      return                                               -- a delivered reset with no record: nothing to clear
  next = current
  if lastWorkspace present && current.version == expectedVersion: next.lastWorkspaceId = lastWorkspace
  if delivered present && current.unannouncedReset == delivered:  next.unannouncedReset = null
  if next == current: return
  if next has no workspace, unattached, lastWorkspaceId, unannouncedReset: delete(session, current.version)
  else put(next, current.version)
  on StaleVersionException: retry (the switch part then only applies if the version still matches — as today)
```

The `lastWorkspaceId` part keeps today's strictness (C033 §6.4, P13: a lost CAS repeats the switch once); the clear
part is a compare-and-clear on the field's value and survives unrelated version bumps.

### 5.4 SPI impact

- `SessionOptionsStore` (host SPI, `[Unreleased]`): implementations must persist one more value. No new method.
- `SandboxWorkspaceStore`, `SandboxProvider`, `SandboxScheduler`: untouched.
- `SandboxBinding` / `SessionOptionsBindingPolicy` (public): one additive optional field.

### 5.5 Model-visible shapes

- **Replay notice** (new, `PendingNotices` or a manager constant like `RESET_NOTICE`):
  *"/workspace was reset after this session last saw it, during a step that reported nothing (a hook, a workflow
  step or a staging call): files and shell state (cwd, exported variables) from before are gone"*. Exact wording is
  the implementer's, under three rules: it says files and shell state are gone; it names no workspace name (ids
  only, like `WorkspaceSwitch.notice()`); it is distinct from `RESET_NOTICE` so `LinkedHashSet` collapse never hides
  a fresh reset behind a replay.
- **A failed connect after a reset** (§6.3): the tool error reads the bare reason; the reset notices arrive as
  `[environment]` lines on the same result (core prepends them). Before: one error text "reason (also: notices)".
- Everything else unchanged.

---

## 6. Mechanisms

### 6.1 Set and replay (in `PendingNotices`)

State added per instance: `knownVersion` (`OptionalLong`), `durable` (the `UnannouncedReset` this instance believes
the record holds, from the bookkeeping or its own mark), `ackDue` (the `UnannouncedReset` that queued, undrained
notices cover), `replayed` (latch).

```
connect(manager, binding):
  try slot = manager.connect(binding)
  catch SandboxResetUnavailableException e with e.reset():
      markIfRoot(e.workspaceId, e.slot)
      queue(e.notices)               -- under lock; sets ackDue as below
      throw new SandboxUnavailableException(e.reason(), e)
  if slot.reset(): markIfRoot(slot.workspace().id(), slot.slot().name())   -- sync, outside the lock
  addAll(slot)                                                             -- as today, plus:

markIfRoot(ws, slot):                -- no bookkeeping (fork, routine, hand-wired policy) → no-op
  stored = ledger.mark(UnannouncedReset.of(ws, slot, clock.instant()))
  synchronized: if stored present: knownVersion = stored.version; durable = stored.unannouncedReset
                pendingMark = durable if stored present
                           else (durable if durable names (ws, slot) else null)   -- mark failed: the older one
                                                                                  -- is covered by this fresh reset

addAll (under lock), first successful connect only (!connected), after the switch notice:
  if !reset && durable present && durable names (slot.workspace, slot.slot):
      pending.add(REPLAY_NOTICE); ackDue = durable
whenever this call reset:      ackDue = pendingMark (may replace an older ackDue: the newer value covers both)
```

The mark happens before `addAll`, so the reset notice is never drainable before it is durable. A replay is queued at
most once per instance, at its first *successful* connect (a failed first connect leaves the field for a later one).
A replay is never queued for another workspace or slot: the field stays until the session connects that slot again,
when "files from before are gone" is still true (or a newer reset replaces it).

### 6.2 Acknowledgement (in `drain`)

```
drain (under lock): out = pending; clear
  switchDue = !recorded && switch != null && out contains switch.notice()  (as today)
  resetDue  = ackDue; ackDue = null
  if switchDue || resetDue != null:
      ledger.acknowledge(Acknowledgement(session, knownVersion, switchDue ? to : empty, resetDue, caller))
```

The silent switch recorded at the first connect goes through the same `acknowledge` (with `delivered` empty). With
no bookkeeping (fork, routine), the switch path stays as today for the root-without-bookkeeping case (cannot occur
through `WorkspaceSandbox`, which always wraps the policy) and nothing else is sent.

### 6.3 Connect failures after a reset

`Connect.withNotices` exists because a reset followed by a failed re-provisioning leaves the slot FAILED, and the next
call has nothing to tell (`SandboxWorkspaceManager` comment, WS §15). Today the notices ride the exception message;
on a hook or staging connect that message never reaches the model. With the subclass of §3.1 (g), `PendingNotices`
marks the reset durable, queues the notices, and rethrows with the bare reason: a model-facing call gets "reason" plus
the `[environment]` lines on the same result (delivered once, acked); a hook's call gets the bare reason, and the
notices wait for the next drain or the next turn. Callers outside the environment (`provisionSlot`, admin paths)
still catch a `SandboxUnavailableException` whose message carries the notices, unchanged.

### 6.4 Session deletion and the janitor

- `SandboxSessionCleanup` → `sessionDeleted` deletes the options record, field and all. Nothing is left to announce:
  the session is gone.
- A mark racing a deletion cannot happen inside the lease (turns and their hooks run under the session's turn gate,
  which the listener holds). A mark that would *create* a record is skipped when `sessionRecords` is wired and the
  session no longer exists — the same guard `recordLastWorkspace` has (C033 §14).
- An acknowledgement queued behind a deletion finds no record and creates nothing for a reset (`delivered` only
  clears); a pending switch create keeps today's `exists` guard.
- The janitor's sweep of deleted sessions (`sweepSessionOptions`) deletes records of gone sessions whatever they
  hold, so a bookkeeping record holding only an unannounced reset is swept like a `lastWorkspaceId` one. Without
  `sessionRecords` there is no sweep — as today; on the ordinary path the ack deletes the bookkeeping record it no
  longer needs, so only sessions whose last reset was never delivered *and* that never ran again leave a residue.
- `SandboxAdministration.deleteSessionOptions` drops the field: an operator's explicit cleanup.

### 6.5 Multi-node

Everything durable is in `SessionOptionsStore`, shared; the in-memory queue is per instance and never leaves its node.
Turn T on node A marks; turn T+1 on node B reads the field at bind and replays. The ack runs on node A's background
executor and is a CAS against the shared store, so B's own mark/ack interleave safely (compare-and-clear on value).

### 6.6 Hot-path cost

| Path | Added cost |
|---|---|
| `resolve` / bind | none (the record is already read; one small object on the binding) |
| connect without reset | one branch |
| connect that reset (root with session) | one options `find` + one CAS `put` (+ one `exists` when creating with `sessionRecords`), synchronous, on a path that is provisioning a sandbox |
| first connect replaying | none (queued from the binding) |
| drain | none on the calling thread; one background task only when a switch or a reset/replay was delivered — rare |
| record size | three small columns |

---

## 7. Failure modes

| Case | Result |
|---|---|
| Issue scenario: idle past `closeAfter`, turn T's only sandbox use is an `onStop` hook that reopens with a reset | Mark written; notice queued, never drained; instance dies. Turn T+1's bind reads the field; first connect (same workspace, slot RUNNING, no reset) queues the replay; the first model-facing tool result carries it once; the ack clears it |
| Ordinary path: reset drained in the same turn | Model hears the original notice once; ack clears the field before the turn can end; next turn reads no field |
| Ack delayed past the next turn's bind (background executor saturated, store down) | Replay once on the next turn — failure path only, like the switch's "repeats once" (C033 §7) |
| Mark fails (store error, CAS exhausted) | WARN; in-memory delivery as today; only the never-drained case is lost (the status quo). An older field for the same slot is acked by this turn's delivery |
| Ack fails / loses every CAS | DEBUG; the field stays; one replay next turn |
| Two resets in one instance before a drain | The second mark replaces the field; the drain delivers both notices and acks the newer value, which clears it |
| A drain delivered the first reset, a second reset follows | First ack's compare fails against the newer value — left; second drain acks it |
| Next turn's first connect resets again | No replay (the fresh notice says it); the new mark replaces the field |
| Next turn is hook-only again | Replay queued in memory, never drained; field untouched; told on the next drained turn |
| Next turn lands in another workspace (switch) | No replay now; switch notice as today; field kept until the session connects that slot again |
| Workspace purged meanwhile (`deleteByWorkspace`) | Field kept; harmless: it is only replayed on a connect of that id and slot, when it is still true |
| Fork resets the slot | In-memory only (fork's model hears it); a live parent hears the generation notice (C033 §6.5); a parent that never connects again before its instance dies → not told (pre-existing WS §20 gap, Q3) |
| Routine execution | No session → no field; a routine run starts with no memory of earlier files, so nothing is owed |
| Parallel calls of one instance, one resets | One mark (only one connect resets); notices collapse as today; replay latch under the lock |
| Connect fails after a reset (§6.3) | Durable mark + queued notices; model-facing: delivered on that error result; hook: next drain or next turn |
| Session deleted | Field deleted with the record (listener, admin delete, janitor sweep) |
| A Java hook calls `drainNotices()` (EE-108) | That notice is lost **and** acked, as for the switch today; documented, not prevented |
| Result drained then discarded (EE-107 (3)) | Lost and acked — core's at-most-once, accepted as in C033 §6.4 |
| Custom `SessionOptionsStore` drops the new field | Behaves as before #28 (no replay); the updated contract catches it |

---

## 8. Compatibility

| Host / caller | After this change |
|---|---|
| Custom `SessionOptionsStore` | must persist `unannouncedReset`; contract test updated. `[Unreleased]` SPI, so no released host breaks |
| Custom `SandboxBindingPolicy` | unaffected; `WorkspaceSandbox` wraps it and adds the bookkeeping |
| Code catching `SandboxUnavailableException` from `connect` | unchanged type and message outside the environment; inside, the tool error loses the "(also: …)" suffix, which arrives as `[environment]` lines instead |
| `recordLastWorkspaceLater` callers | Internal API, replaced by `acknowledgeLater` |
| Admin views (`sessionOptions`, `SandboxAdministration.sessions`) | a record holding only the field has no `workspace`, so it is never listed as an attachment (as C033's bookkeeping records) |

---

## 9. Test strategy

Unit tier (`./gradlew checkAll`):

- **`PendingNoticesTest`** (fake `NoticeLedger`): mark happens before the notice is drainable; drain acks the stored
  value; replay queued once at first successful connect when the field names the slot; no replay when the connect
  itself reset (and the newer value is acked); no replay for another workspace; failed mark → older field acked; two
  resets → one ack of the newer value; switch + reset → one acknowledgement with the instance's known version (after
  a mark, not the switch's stale one); no bookkeeping → no ledger calls; parallel `connect` + `drain` on two threads
  → each notice once, replay once.
- **`ManagedSessionsIT`** (in-memory, `QueueingExecutor` where deferral matters): `markResetUnannounced` creates a
  bookkeeping record, respects the `exists` guard, retries a CAS; `acknowledge` clears only an equal value, deletes a
  record left empty, keeps the switch strictness; `clearSessionOptions` and `setSessionOptions` keep the field;
  `withSwitch` ignores a reset-only record (no silent switch, no `lastWorkspaceId` written).
- **Acceptance scenario** (`SandboxEnvironmentProviderTest` or `ManagedSessionsIT`, `SandboxHarness` with
  `ManualClock`/`ManualScheduler`): turn 1 writes a file via `bash`; advance past `closeAfter` and run the idle close;
  turn 2's environment runs one `bash(..., ExecutionOptions.hook)` and is dropped undrained; turn 3's environment's
  first file call → `drainNotices()` contains the replay exactly once; a second drain and turn 4's drain contain
  nothing. Variants: staging (`stage`) instead of the hook; the hook's re-provisioning fails (fault-injecting
  provider) → turn 3 hears the reset once.
- **No double delivery**: turn 2 reset drained by a model-facing call; run the background executor; turn 3 drains
  nothing. And with the executor *not* run before turn 3: exactly one replay (the documented failure window).
- **Forks**: a fork's reset never writes the field (`DelegatingOptionsStore` records no put); the fork binding has no
  bookkeeping.
- **Multi-node** (`MultiNodeScenariosTest`): node A's hook-only turn marks; node B's next turn replays once; node A's
  late ack after B's own newer mark leaves B's value.
- **Deletion** (`SandboxSessionCleanupTest`): listener removes a record holding only the field; a queued ack after
  the deletion creates nothing; a mark-create after deletion with `sessionRecords` is skipped.
- **Store contract** (`SessionOptionsStoreContract` via both in-memory contract tests).
- **E2E** (`OrcaRuntimeSandboxE2ETest`, real core loop): a skill with an `onStop` shell hook; turn 1 a `Bash` tool
  call; idle close; turn 2 a text-only answer whose `onStop` hook runs a command (reopen + reset); turn 3 a `Read` —
  its tool result content starts with the `[environment]` replay line, once. If the scripted runtime cannot fire
  `onStop` for a text-only turn, fall back to the provider-level scenario above and say so in the departures section.

Docker tier (`./gradlew integrationTest`): **`ManagedWorkspaceSandboxIT`** — the same three-turn scenario against a
real OpenSandbox server with a short `closeAfter` (the hook connect is a `bash` with `ExecutionOptions.hook`), checking
the replay once and that turn 3's file listing no longer has turn 1's file. Practical because the class already drives
idle closes and session turns; if the idle close is too slow to drive there, use an explicit close + reopen and note it.

---

## 10. Commit sequence (messages reference `#28`; last one "Closes #28")

1. `feat(workspace): UnannouncedReset on session options` — value, record field, in-memory store, contract.
2. `feat: keep a reset the model never heard on the session` — bookkeeping on the binding, `PendingNotices.connect`,
   mark/replay/acknowledge, manager methods, `withSwitch` tweak, connect-failure routing, tests.
3. `test: hook-only turn after an idle close hears the reset next turn` — E2E and Docker tier.
4. `docs: durable unannounced reset (#28)` — design record, WS §7/§20, MG, CHANGELOG, README. Closes #28.

---

## 11. Open questions (not silently assumed)

- **Q1 — Connect-failure routing (§6.3).** Designed in, because the same loss happens when a hook's reopen is followed
  by a failed re-provisioning. It changes the shape of one model-facing error (suffix → `[environment]` lines). Keep
  it in this change, or split it out and accept that a failed hook connect still loses the reset?
- **Q2 — "Last seen generation" per session.** Rejected here (§2.2) for its ordinary-path writes and record growth.
  Should WS §20's between-turns item now be scoped as "re-creations by others only", or is the general fix wanted
  soon enough that this field should be designed as its first half (e.g. also storing the generation)?
- **Q3 — Forks.** Designed as "forks never touch the field" (§2.2 explains the false-replay hazard). Acceptable that a
  fork's re-creation still goes unheard when the parent never connects again in that turn?
- **Q4 — Replay wording.** One fixed text (§5.5) rather than the original cause ("idle", "re-created", "lost"). Is
  the cause worth persisting (one more column)?
- **Q5 — Visibility.** Should `SessionOptionsView` / the admin views show a pending unannounced reset (useful when a
  user reports "the model lost its files"), or stay silent as designed?
- **Q6 — Core gap.** No core change is needed. Should the dev phase still record in `$RUN_DIR/core-gaps.md` that an
  "environment discarded with undrained notices" signal (or EE-106's turn-start drain) would let the sandbox drop the
  ordinary-path set + clear writes? Designed as: not filed, mentioned only in the design record.
- **Q7 — Residue without a sweep.** On a host with neither the lifecycle listener nor `sessionRecords`, a deleted
  session whose last reset was never delivered leaves a small record forever (as `lastWorkspaceId` records already
  do). Acceptable, or should the field carry its own expiry (e.g. dropped after `closed-retention`)?
- **Q8 — E2E feasibility.** Whether `OrcaRuntimeSandboxE2ETest`'s scripted runtime fires `onStop` shell hooks for a
  text-only turn was not verified in this phase; §9 gives the fallback.

---

## 12. Implementation departures

Where the code and documents of this change differ from §1–§11, and why. Of the open questions of §11, Q2 and Q3 (other
re-creations between turns, forks), Q5 (visibility) and Q7 (residue without a sweep) reach beyond this change and are
in WS §20, each pointing back here. Q1, Q4, Q6 and Q8 are answered below.

### Review 1 — what was done with its items

| Item | Done |
|---|---|
| A dropped acknowledgement replays a reset the model *was* told: a false notice, unlike a repeated switch (§7 rows "ack delayed", "ack fails") | Named and narrowed. The replay names the time of the reset — "/workspace was reset at 2026-10-18T04:00:00Z and this session may not have been told: files and shell state (cwd, exported variables) from before then are gone" — so a model that made files after it can tell they are not meant. The window stays bounded by one turn: the replay is itself acknowledged. WS §20's #28 entry states the trade-off. `UnannouncedResetIT.aDelayedAcknowledgementRepeatsTheResetOnce` pins it |
| Parallel calls: call B queues the replay while call A, which reset the slot, is still in its synchronous mark — two notices for one fact | A reset reaching `PendingNotices` removes a replay still queued, the way a reset already removes a queued generation notice, and no replay is queued after it. `PendingNoticesTest.aResetNoticeReplacesAQueuedReplay`, `parallelConnectsWithOneResetTellItOnce` (eight threads, one resetting: the drain is exactly the reset notice) |
| §6.1's failed-connect path must set neither `connected`, the generation baseline nor the replay latch | It sets none of them (`PendingNotices.failed`). Building it showed a related gap the review did not name — see "replay latch" below |
| `SandboxBinding` has no `equals` | None was added; `toString` is unchanged too (it never showed the switch either). `sessionBookkeeping()` is an accessor and a builder method only |
| There is no `sweepSessionOptions` | §6.4's sweep is `SandboxJanitor.sweepDeletedSessions`, the `options.sessions(...)` loop. It deletes a record holding only an unannounced reset like any other |
| WS §20 should also list resets no session caused (an administrator's `reopen`, `provisionSlot` after a terminate) | Listed in WS §20's "턴 사이의 재생성 알림" entry, with other sessions' and forks' re-creations |
| A parent whose turn's only model-facing work runs in a fork leaves its replay undrained, and the next turn's replay then also covers the fork's later files | The parent never connected, so nothing was queued; the field waits for its next connect. The replay's time is what keeps that notice true: the fork's files are from after it |
| Q8: whether the scripted runtime fires `onStop` for a text-only turn | It does: `OrcaRuntimeSandboxE2ETest.aStopHooksResetReachesTheNextTurnOnce` runs the issue's scenario through `OrcaAgentExecutor` with a skill's `onStop` shell hook |

### Where the code differs from the design

- **Replay latch (found while building).** §6.1 queues the replay at the first *successful* connect when that connect
  did not reset. After a failed connect that reset the slot (§6.3), the next connect usually succeeds without a reset
  (it re-provisions a FAILED slot, which is no reset), and §6.1 as written would queue a replay of the reset this
  instance had just queued itself — twice in one drain, or once more after it. `PendingNotices` keeps a separate
  `replayable` value (the reset the binding read and this instance has not told): the replay consumes it, and so does
  any reset notice of the instance's own, successful or failed. `PendingNoticesTest.aFailedConnectAfterAResetIsKeptAndQueued`.
- **The replay wording** carries the reset's time (above), cut to seconds. It still names no workspace and stays
  distinct from `RESET_NOTICE`. Q4: one fixed text; the cause is not persisted.
- **No `Acknowledgement` type (§5.3).** `SandboxWorkspaceManager.acknowledgeLater(session, expectedVersion,
  lastWorkspace, delivered, setBy)` takes the five values as parameters, like `recordLastWorkspaceLater` did; the
  package-private `acknowledge` does the work as §5.3's pseudo-code says, with `casRetries` re-reads.
- **`markResetUnannounced(session, workspace, slot, setBy)`** stamps `at` from the manager's clock, so `NoticeLedger`
  takes `(session, workspace, slot)` and `PendingNotices` needs no clock. On an existing record it leaves `updatedAt`
  alone, as `recordLastWorkspace` did; a record it creates gets the current time.
- **`PendingNotices` entry points.** `connect(manager, binding)` is the one call the four sites make (§3.3); it hands
  what `connect` returned or threw to package-private `connected(...)` and `failed(...)`, which take plain values so
  `PendingNoticesTest` can drive them with a fake ledger (`ConnectedSlot` and the new exception have
  package-private constructors). The old `addAll` overloads are gone.
- **`SandboxResetUnavailableException`** is `final` with a package-private constructor. It is built whenever a failed
  `connect` gathered notices — in practice only reset notices are gathered before a failure — and `PendingNotices`
  marks only when `reset()` is true. The rethrown `SandboxUnavailableException` keeps it as the cause.
- **`SessionBookkeeping.of(session, record)`** builds the value from the record the policy read; `UnannouncedReset`
  has `isOf(workspace, slot)`.
- **`withSwitch`** treats a reset-only record as absent only for an unrequested bind. Row 0 is announced as before
  (from-absent wording); the acknowledgement uses the environment's known version, not the switch's.
- **Log levels.** A failed or exhausted mark logs a WARN (the only loss left is a reset that is also never drained); a
  failed acknowledgement logs at DEBUG, like the `lastWorkspaceId` write it replaces (the worst case is one repeat).

### Tests placed or shaped differently from §9

- **`UnannouncedResetIT`** (new, `at.aimon.sandbox`, unit tier) holds the acceptance scenario and its variants rather
  than `SandboxEnvironmentProviderTest` / `ManagedSessionsIT`: the hook-only turn, a staging call, a hook whose
  re-provisioning fails, the ordinary path, a delayed acknowledgement, a fork, set/clear keeping the field, the
  acknowledgement's compare-and-clear against a stale switch version, and a mark retrying a lost CAS. The
  `withSwitch` case is asserted inside the acceptance test (the next turn's binding has no switch, and the record is
  gone after the drain) and in `SessionOptionsBindingPolicyTest`.
- **Deletion** cases are in `SandboxSessionCleanupTest` as planned; the "mark after deletion" case calls
  `markResetUnannounced` directly, because a turn of a session core no longer has is refused at bind once
  `sessionRecords` is wired.
- **Multi-node** cases are in `MultiNodeScenariosTest`, with two harnesses sharing the workspace store, the options
  store and the provider.
- **`SandboxWorkspaceManagerTest`**'s two "notice rides on the error" tests now check the two halves: a direct
  `connect` still carries the notices in the message (and as `SandboxResetUnavailableException` parts), and an
  environment's call reports the bare reason with the notice drained.
- **Docker tier** — `ManagedWorkspaceSandboxIT.aHookOnlyTurnsResetIsToldOnTheNextTurn` runs the three-turn scenario on
  a real OpenSandbox server; the janitor's idle close works there with the manual clock, so no explicit close was
  needed.

### Open questions — answers and where they went

- **Q1** — kept in this change: a hook's failed re-provisioning no longer swallows the reset.
- **Q2, Q3** — WS §20 "턴 사이의 재생성 알림" now says that the session's own undelivered resets are covered and what
  remains (other sessions, forks whose parent never connects again, administrator paths), with the "last seen
  generation" record as the way to close it.
- **Q5, Q7** — new WS §20 entries.
- **Q6** — no core change is needed and none was filed. A core signal for "environment discarded with undrained
  notices", or EE-106's turn-start drain, would let the sandbox skip the set-and-clear writes on the ordinary path;
  it is an optimisation, not a fix.
