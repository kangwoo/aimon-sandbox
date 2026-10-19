# Workspace files design — a file explorer's backend for a workspace's running sandbox

> Status: **DRAFT r1** — implemented with this document; not yet reviewed.
>
> Scope: a host-facing API to list, stat, read, search, write, create, move and delete files under `/workspace` of a
> workspace's running `primary` sandbox, for a person using a file explorer, not for an agent. It adds no provider
> SPI: `SandboxFiles` (WS §6.1) already has everything. The REST endpoints and the web UI belong to the host
> (aimon-ops) and are not in this repository.
>
> Authority: [`workspace-sandbox.md`](workspace-sandbox.md) (**WS**) is the architecture;
> [`workspace-sandbox-management.md`](workspace-sandbox-management.md) (**WM**) is the pattern this follows — an
> owner-facing surface on `SandboxWorkspaces` and an operator surface on `SandboxAdministration`.

---

## 1. Problem

An application that runs agents in sandboxes has to show people what an agent made: open `/workspace/repo`, preview
a log, download a build artefact, drop in an input file, delete a mess. Today the only ways in are an agent's own
file tools, which exist only inside an execution and speak to the model, or the provider's console, which needs the
raw sandbox id (root in the sandbox, WS §13.3). aimon-ops already has an explorer for its local VFS
(`VfsExplorerPort`: list, file info, preview, download, search, upload, mkdir, rename, move, delete); it needs the same
operations for a sandbox workspace, behind the same rights the workspace already has.

## 2. Boundaries

- **What runs, nothing more.** The explorer reaches a sandbox only while it is RUNNING and seeded. Files do not
  outlive a sandbox yet (WS §18 step 5), so a workspace whose sandbox is not up has nothing to show.
- **`/workspace` only.** The rest of the image (`/usr`, `/etc`, `/tmp`) is not the agent's work and is not shown.
- **`primary` only.** Multiple slots come with step 5; the API takes no slot until then.
- **No usage summary.** Sizing a remote tree walks it file by file (the same reason `SandboxFileSystem` does not
  offer one).
- **No model notice.** A person's change is not announced to the model (§6.4).

## 3. Approach, and what was rejected

### 3.1 Chosen

A root-package class `SandboxWorkspaceFiles` — one per surface, both from the same code:

| Surface | Obtained from | Check | Actor on events |
|---|---|---|---|
| owner-facing | `sandbox.workspaces().files()` | the use right (WM §6.5) | the caller as the tenant resolver sees it |
| operator | `SandboxAdministration.of(sandbox).files()` | none | the operator principal, required |

Each call opens the running slot through a new manager entry point (`runningFiles` / `adminRunningFiles`) that never
provisions (§6.1), applies the path rules (§6.3) and calls `SandboxFiles` directly.

### 3.2 Rejected

- **Reuse `SandboxWorkspaceManager.connect` with a synthetic binding.** `connect` creates a missing `ws:` record,
  provisions, reopens an idle-closed workspace (with a reset the model must hear about) and waits on another node's
  provisioning. Every one of those is wrong for someone looking: opening an explorer on a session's workspace would
  start a sandbox and put "/workspace was reset" into the next tool result of a session that did nothing.
- **Hand out a core `VirtualFileSystem`.** Its `list` returns names only, so a directory view costs one `stat` per
  entry over the provider's network; it has no ranged read, no create-new write and no recursive-delete flag that
  says so. The explorer needs `FileStat`s in one call.
- **Provision on demand (an option).** Considered and declined with the user: an empty `/workspace` from a fresh
  sandbox is not what anyone opening an explorer wants to see, and it spends a sandbox. `SandboxWorkspaces.provision`
  is already there for a host that wants a "start" button.

## 4. Concrete changes by file

`aimon-sandbox`, root package:

- `SandboxWorkspaceFiles` (new) — the API, path rules, read-only rule, search matcher.
- `SandboxWorkspaces.files()`, `SandboxAdministration.files()` (new).

`aimon-sandbox`, `at.aimon.sandbox.workspace`:

- `SandboxWorkspaceManager.runningFiles(id, caller)`, `adminRunningFiles(id, actor)` (new, internal).
- `RunningSlotFiles` (new, internal) — the slot's `SandboxFiles`, its `SlotActivity` and the event hook; maps provider
  failures.
- `WorkspaceFile`, `WorkspaceFileListing` (new views), `WorkspaceFilesUnavailableException` (new, with `Reason`).
- `SandboxEvent.Type.FILES_CHANGED` (new).

Providers and the testkit are unchanged: `OpenSandboxFiles` and `LocalSandboxFiles` already pass the provider
contract for every call used here.

## 5. Interface shapes

### 5.1 `SandboxWorkspaceFiles`

Every method takes the workspace id first and the calling principal last, like `SandboxWorkspaces`.

```java
Optional<WorkspaceFile> stat(SandboxWorkspaceId id, String path, Principal caller);
WorkspaceFileListing list(SandboxWorkspaceId id, String directory, int limit, Principal caller);   // limit 1..10 000
InputStream read(SandboxWorkspaceId id, String path, long offset, long length, Principal caller);  // length < 0: to EOF
List<WorkspaceFile> search(SandboxWorkspaceId id, String directory, String pattern, int maxResults, Principal caller);

WorkspaceFile write(SandboxWorkspaceId id, String path, InputStream content, long length, boolean overwrite,
        Principal caller);
WorkspaceFile createDirectory(SandboxWorkspaceId id, String path, Principal caller);
WorkspaceFile move(SandboxWorkspaceId id, String from, String to, boolean overwrite, Principal caller);
void delete(SandboxWorkspaceId id, String path, boolean recursive, Principal caller);
```

How aimon-ops' `VfsExplorerPort` maps onto it:

| `VfsExplorerPort` | here |
|---|---|
| `listEntries` | `list(...).entries()` (directories first, then by name — the port's order) |
| `getFileInfo` | `stat` (empty → the port's not-found) |
| `readFile` | `read(id, path, 0, -1, caller)` |
| `readTextContent(maxBytes)` | `read(id, path, 0, maxBytes, caller)`; truncated when `stat.size() > maxBytes` |
| `writeFile` | `write(..., overwrite = true, ...)` |
| `createDirectory` | `createDirectory` |
| `rename(path, newName)` | `move(path, parent + "/" + newName, false, ...)` |
| `move(source, destinationDir)` | `move(source, destinationDir + "/" + name, false, ...)` |
| `delete` (recursive) | `delete(..., recursive = true, ...)` |
| `search` | `search(id, "", pattern, maxResults, caller)` |
| `getUsageSummary` | not offered (§2) |
| "VFS not initialized" | `WorkspaceFilesUnavailableException` with `NOT_RUNNING` / `CLOSED` |

The port's "writes auto-create the VFS" does not carry over: a write to a workspace with no running sandbox fails
`NOT_RUNNING` like a read.

### 5.2 Views

`WorkspaceFile`: `path()` relative to `/workspace` (`""` is `/workspace`), `name()`, `sandboxPath()` (absolute),
`directory()`, `size()` (0 for a directory), `modifiedAt()`, `etag()`, `readOnly()` (§6.3). `WorkspaceFileListing`:
the listed `directory()`, `entries()`, `truncated()`. Neither carries a provider sandbox id.

### 5.3 `WorkspaceFilesUnavailableException`

A `SandboxUnavailableException` with a `Reason` a UI switches on:

| Reason | When |
|---|---|
| `NOT_FOUND` | no such workspace, or the caller may not use it — answered alike (WM §6.5) |
| `NOT_RUNNING` | OPEN, but `primary` is absent, PROVISIONING, TERMINATED, FAILED, not seeded, or found gone |
| `CLOSING` | the workspace is closing |
| `CLOSED` | closed, idle or explicit; the files are gone |
| `UNREACHABLE` | the provider fails, or the slot's profile is no longer configured |

File-level failures stay core VFS exceptions, as from the agent's file tools: `FileNotFoundException`,
`FileAlreadyExistsException`, `InvalidPathException`, `FileAccessDeniedException`, `VirtualFileSystemException`.

## 6. Mechanisms

### 6.1 Reaching a running sandbox without provisioning

`runningFiles` reads the record once and decides, in order:

1. absent, or the caller lacks the use right → `NOT_FOUND`. The operator variant skips the right;
2. `CLOSING` → `CLOSING`; any other non-OPEN state → `CLOSED`. An idle-closed workspace is **not** reopened;
3. no `primary`, not RUNNING, or not seeded → `NOT_RUNNING`. A PROVISIONING slot is not waited on;
4. the slot's profile is gone → `UNREACHABLE`;
5. `SandboxConnectionCache.get(ref)`: `SandboxNotFoundException` marks the slot lost (generation-guarded, exactly as
   a file tool does) and answers `NOT_RUNNING`; any other provider failure is `UNREACHABLE`.

It writes nothing in steps 1–4. Step 5's "mark lost" records a fact the explorer found, not one it caused: the next
agent turn hears "the sandbox was recreated" as it would have anyway. The returned `RunningSlotFiles` is bound to that
generation; every provider call through it gets the same mapping.

### 6.2 Reads

- `list` stats the directory (missing → `FileNotFoundException`, a file → `InvalidPathException`), asks the provider
  for `limit + 1` entries without recursion, and reports `truncated` when it got more than `limit`. It sorts what it
  keeps; which entries made the cut is the provider's order.
- `read` stats first (missing, directory) and returns the provider's ranged stream.
- `search` walks the tree under `directory` with one recursive listing of at most 100 000 entries — more fails, as
  `ProviderFileSystem` does, rather than answer from part of the tree — and matches each entry's **name**. A pattern
  with a glob character (`* ? [ {`) is a glob, sanitised by core's `SearchPatterns` so it reads as core's `Glob`
  would; any other pattern is a case-insensitive substring, which is what a search box means. Directories match too.

Reads record no activity: an explorer left open does not keep a sandbox from its idle close.

### 6.3 Paths and the read-only rule

A caller path is relative to `/workspace`. It is normalised by core's `VfsPaths.normalizeRelative` (`.`, `..`,
repeated and trailing slashes, `\` as `/`); a path that climbs out, a NUL byte, or a leading `/` is
`InvalidPathException`. An absolute path is refused rather than guessed at, so `/workspace/repo` and `repo` cannot be
confused. Provider answers are mapped back; one outside `/workspace` fails rather than show.

`/workspace` itself and every top-level `.aimon-*` entry — `.aimon-shell` (each session's cwd and exports, which every
command sources), `.aimon-staged` (staged skills) and `.aimon-seed.lock` — are `readOnly()`: listed and readable, and
any write, mkdir, delete or move from or into them is `FileAccessDeniedException`. This is the agent file tools' rule
(`SandboxFileSystem.RULES`) widened to the whole prefix, because a person's mistaken upload into `.aimon-shell` would
run in every later command. It is a guard against mistakes, not a security boundary: the agent shares the sandbox
and can make `repo/x` a symlink to `.aimon-shell`, which the provider follows like any path (WS §11.1).

### 6.4 Changes

`write`, `createDirectory`, `move` and `delete` check their preconditions with a `stat` (exists, directory, empty,
`move` not into itself), then

1. record activity through the slot's `SlotActivity` (throttled by `activity-write-interval`; pushes `lastActivityAt`
   and the provider expiry, like an agent's file call). A sandbox found gone here is `NOT_RUNNING`;
2. make the change;
3. publish `SandboxEvent.Type.FILES_CHANGED` with the workspace, slot, generation, the actor and a cause naming the
   operation and path(s), e.g. `wrote repo/in/data.csv (4 bytes)`, `moved repo/a to repo/b`. A no-op
   (`createDirectory` of an existing directory) publishes nothing.

The checks and the change are not atomic: an agent running at the same time can change the same path in between, and
the provider's own answer then decides (`CREATE_NEW` still refuses an existing file). The model is not told that a
person changed something. Core's `Edit` and `Write` refuse to overwrite a file whose `modifiedAt`/`etag` changed since
the model read it ("File changed since it was read; Read it again", core `FileStamps`), so the usual collision
surfaces as a stale-read error for the model, not a silent overwrite. Hosts that want
the model to know can tell it in the next prompt.

`write` takes `length` as the provider does (`-1` when unknown); the host caps upload size before calling.

### 6.5 Rights

The owner-facing surface resolves the caller as `SandboxWorkspaces` does (`CallerResolver.callerOf`, so
`require-principal` applies) and checks the **use** right: the tenant always, the principal too unless the workspace's
access is `tenant`. Changing files is using, not managing — whoever may run a session in a workspace may already
change every file in it from the shell (WM §6.5, "one trust domain"). Session-derived `ws:{sessionId}` workspaces are
owned by the session's owner, so the same rule covers "browse my session's sandbox". The operator surface checks
nothing, requires the actor and records it, like every other `SandboxAdministration` write.

## 7. Failure modes

| Situation | Result |
|---|---|
| sandbox expired at the provider between record read and call | slot marked lost, `NOT_RUNNING`; the next agent turn recreates it |
| provider down or slow | `UNREACHABLE`; nothing written |
| idle close runs while a person browses | next call `CLOSED`; nothing reopened |
| person writes while the agent edits the same file | the person's write lands; the agent's next `Edit`/`Write` is refused as stale |
| directory with more than `limit` entries | first `limit` in provider order, `truncated = true` |
| search over a tree larger than 100 000 entries | `VirtualFileSystemException`: narrow the directory |

## 8. Departures from WS

None. WS §6.1's files API and §8.3's use right are applied as they are. `FILES_CHANGED` is a new event type, which
WS §14 leaves open.

## 9. Test strategy

`SandboxWorkspaceFilesTest` (default tier, local-process provider through `SandboxHarness`):

- reads: one-level listing order, reserved entries flagged, truncation at the limit, ranged read, stat, search
  (substring, glob, directory match, max results);
- changes: write (create-new, overwrite, onto a directory), mkdir (idempotent, onto a file), move (rename, overwrite
  refused, into itself, missing source), delete (non-empty refused, recursive), the `FILES_CHANGED` causes and actor;
- read-only rule and path rules (`..`, absolute, NUL, normalisation);
- activity: reads leave `lastActivityAt`, a change moves it;
- no provisioning: a terminated slot stays terminated with no new sandbox, a closed workspace stays closed, a session's
  workspace is not created by looking and is browsable after its first command;
- provider faults: not-found marks lost, a transient failure is `UNREACHABLE` and changes nothing;
- rights: another principal and a missing id answer alike, tenant access, the operator surface.

No docker-tier test is added: every provider call used is covered by `SandboxProviderContract` against OpenSandbox.

## 10. Open questions

- **Downloads of whole directories** (zip/tar). Possible as a `tar` exec through the connection, but that runs a
  command in the agent's sandbox on a person's behalf; left out until a host asks.
- **Telling the model.** A host-chosen notice on the session's next tool result (`PendingNotices`) would need a
  session to attach it to; a workspace can have many or none.
- **Slots and `/shared`.** With step 5 the API grows a slot parameter and a second root.
