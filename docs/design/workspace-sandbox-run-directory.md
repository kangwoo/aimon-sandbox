# Design — aimon-sandbox#33: `RUN_DIRECTORY` and hook/isolated run files out of agent reach

Base: sandbox `main` `553be78` (branch `herdr/sandbox-run-directory`), core `origin/main` `63e3b9ec` (EE-109 merged;
`RunDirectory`, `ShellFeature.RUN_DIRECTORY`, `ExecutionOptions.getRunDirectory()`, `RunDirectoryIntegrityException`
read from there). Line numbers are at `553be78`. Paths are under `modules/aimon-sandbox/src/main/java/at/aimon/sandbox/`
unless stated otherwise.

Target level: **narrowing, not a privilege boundary.** Same uid. The uid boundary is aimon-sandbox#34 / core EE-113 and
is out of scope.

---

## 1. Problem

Core (EE-109) now gives a hook its files as bytes in `ExecutionOptions.getRunDirectory()` and runs `hooks.json` handlers
that name `AIMON_HOOK_DIR` only on a shell that declares `ShellFeature.RUN_DIRECTORY`. `SandboxShell` does not declare
it, so on the sandbox those handlers are `RUN_DIRECTORY_UNSUPPORTED` (a guard blocks), and skill hooks fall back to an
agent-writable staged copy that the interpreter reopens by path after verification. Separately, an isolated or hook
command's text, stdin, stdout, stderr, cwd flag and timeout marker all live in run files under
`/workspace/.aimon-shell/<h>/run-*`, which the agent's shell (same uid) can read and rewrite before or during the run,
forging a policy hook's verdict. The sandbox must (a) implement the `RUN_DIRECTORY` contract — fresh private directory,
exact bytes, digest check against digests that travel in the exec argument, variable export, removal on every outcome —
and (b) for `HOOK` and `ISOLATED` modes only, move every per-run artefact off persistent agent-writable files: the
command and small stdin in the exec argument, output on the exec's own streams, the timeout decided by the JVM's clock.
Foreground and background (the model's own commands) keep today's run files.

---

## 2. Approach

### 2.1 Chosen

One mechanism carries everything that must reach a hook run intact: **the exec argument** (the single `bash -c`
script). What fits goes inline. What does not is uploaded through the files API (agent-writable) and then copied by the
wrapper into a **private directory** and checked against SHA-256 digests that the JVM computed and embedded in the
argument. Output never touches a file: the inner bash writes to the exec's own stdout/stderr, the outer prints the
trailer last on stderr, and the JVM caps and truncates.

Concretely:

1. **Private directory per run** `P = /workspace/.aimon-shell/.run.<32 hex>` (Q5 decided: under `.aimon-shell`; §2.3).
   Its parent, the shared `/workspace/.aimon-shell`, is re-created with `mkdir -p` first (a model command may have
   removed it); `P` itself is created with `mkdir -m 700` (no `-p`: fails if it exists). Created for every `HOOK`/`ISOLATED` run, and for a
   `FOREGROUND`/`BACKGROUND` run only when it carries a run directory. Layout:
   - `P/d/` — the run directory proper, exported in `RunDirectory.getVariable()`; holds **exactly** `getPaths()`.
   - `P/.stdin` — an uploaded stdin payload after its digest check (hook modes only).
   - `P/r.cwd` — the inner bash's cwd-fallback flag (hook modes only; the inner's run prefix is `P/r`).
2. **Placement** in the outer's prologue, before the watchdog starts (placement does not count against the timeout,
   like the lock wait): `umask 077` for placement only (restored before the inner starts), `set -C` (noclobber, so two paths the filesystem merges fail — contract rule 2),
   inline files decoded with `/usr/bin/base64 -d`, uploaded files copied with `cat >`, upload removed, then
   `sha256sum -c --strict` on a manifest fed by the `printf` builtin (no here-document temp file), plus an entry count
   check. A digest mismatch or wrong count → exit 73; any other failure (I/O, a missing binary) → exit 70.
3. **Hook/isolated command** travels as the inner bash's sixth positional argument (`$6`), never as `$r.cmd`. Over
   `INLINE_COMMAND_LIMIT` it is refused before connecting (Q7 decided: refuse).
4. **Hook/isolated stdin** inline as base64 piped into the inner bash when the assembled script stays under
   `ARGUMENT_BUDGET`; otherwise uploaded to `{runPrefix}.in`, copied to `P/.stdin`, digest-checked, redirected.
5. **Hook/isolated output** on the exec streams; trailer `\n{nonce} exit=N cwd=C` on stderr; capture cap applied in the
   JVM. A trailer watcher (`OutputSink`) detects the trailer while the exec streams, and the exec is then killed so
   a backgrounded descendant does not hold it open (Q8 — needs maintainer confirmation, §8). The watcher also keeps the
   parsed trailer, so a stderr larger than the provider's capture backstop does not lose it.
6. **Timeout** for hook/isolated runs is decided by the JVM: no trailer, exit 137 and elapsed ≥ the command timeout.
   The watchdog writes no `.timedout` in these modes.
7. **Cleanup**: the wrapper removes `P` (epilogue and `EXIT` trap) and the uploads; the JVM's `cleanUp` removes `P`
   recursively plus `{runPrefix}.files` and `{runPrefix}.in` whenever the run ended without a trailer (watchdog
   `kill -KILL 0` skips the trap).

### 2.2 Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Private directory name derived from `runPrefix`'s suffix (the task text, §1.4) | The upload path `{runPrefix}.files/…` appears in the agent-visible `.aimon-shell/<h>/` **before** the exec starts, so the agent learns the suffix and can pre-create or plant `.run.<suffix>` before the wrapper's `mkdir`, i.e. reach the private directory *before* the run instead of only while it runs. (This does not stop an agent from failing a hook run: deleting the upload between the files-API write and the exec, or killing the outer, still gives exit 70 / a signal → `EXECUTION_FAILED`, which a `failOpen` guard passes. That is R1-class interference and stays accepted; D1 does not claim to close it.) An independent random name generated in the JVM is equally known to `cleanUp` and is first visible only in the exec's argv, microseconds before the `mkdir`. **Deviation D1.** |
| Export `P` itself as the variable | Contract rule 2 says "exactly `getPaths()`"; `.stdin`/`r.cwd` beside the files would add entries, and a hook file named `.stdin` would collide. A sub-directory `P/d` keeps the exported directory exact. **D2.** |
| `PRIVATE_BASE = ${TMPDIR:-/tmp}` (Q5 alternative) | (1) The JVM must delete the directory after a watchdog kill through the files API, and cannot know a shell-expanded `$TMPDIR`. (2) In the testkit `/tmp` in a command is the **host's** `/tmp` while the files API maps `/tmp` to `{dir}/tmp` (`LocalConnection` translates only `/workspace` and `/usr/bin/sha256sum`), so JVM cleanup would miss it and tests would write to the host. (3) Not in the image contract; breaks on a read-only root. Reachability by the same uid is identical either way. |
| Inline stdin as quoted text (`printf '%s' 'PAYLOAD'`, the task's §2.2) plus a NUL refusal | The quoted text reaches the inner as UTF-8 (the script's encoding), while today's upload sends `stdin.getBytes(options.getCharset())`; any non-UTF-8 charset would change the bytes. base64 of the exact bytes keeps today's semantics for every charset and carries a NUL, so the NUL refusal (which foreground does not have either) is unnecessary. Cost: 4/3 size, inside the same budget. **D4**, Q-A. |
| Kill the exec "after `completed` returns" (the task's §2.4 wording) | `completed` runs after `RunningCommand.await` returns, and on OpenSandbox `await` returns only when the SSE stream ends, i.e. when every holder of the exec's stdout/stderr is gone (docs/design/workspace-sandbox-step3.md:85). A hook's `cmd &` would hold it until the backstop, so the kill would never come in time. The trailer must be detected while streaming: an `OutputSink`. **D5.** |
| Keep waiting for the exec to end (no kill; Q8 alternative) | Every hook that backgrounds anything becomes a backstop-length call (timeout + 5 s), on the tool call's critical path. |
| Redirect the inner's output through `head -c` in the sandbox (keep the in-sandbox cap) | `inner | head -c` SIGPIPEs the hook when it writes past the cap (behaviour change), needs `PIPESTATUS`, and the stderr side needs process substitution; the byte counts would still have to travel in the trailer. The JVM already bounds what it receives (`captureBackstop`). |
| Upload the over-64 KiB hook command and digest-check it (Q7 alternative) | `hooks.json` commands are short; refusing keeps the script simpler. Reversible later without SPI change. |
| A `.timedout` marker inside `P` instead of the clock | `P` is reachable by the same uid while the run lives; a forged marker would turn a normal failure into a timeout. The clock cannot be forged. |
| Testkit `Fault.rewrite(UnaryOperator<byte[]>)` for the tamper tests | Faults in `FaultInjectingSandboxProvider` match by operation and call number, not path (`Rule(Operation, int call, Fault, int times)`); the upload's call number is fragile and a path predicate is new published API. A test-local provider decorator is smaller and private. (Either is allowed by the task; this is a choice, not a deviation.) |
| Lower an existing inline cap to make room | Not needed: decide inline/upload against the **assembled** script length (§3.3). |

### 2.3 Q5 decided: `/workspace/.aimon-shell/.run.<hex>`

- In the image contract and created by the seed (`SandboxSeeder.java:157`); writable on a read-only root.
- File tools see `.aimon-shell` read-only (`SandboxFileSystem.RULES`): they cannot write `P`; they can **read** it while
  a hook runs (contract: "outside the paths the model's file tools are given *where the environment has such a
  place*" — under one uid with only `/workspace` guaranteed, it has none). See Q-D.
- Distinct prefix `.run.`: never a shell-key directory (`ShellKey.directoryName()` is `SandboxLabels.h(value)`, no
  leading dot), never matched by `run-*`, and the exec-shell sweep (`SandboxConnectionCache.execShellFinished`) only
  removes exec-key directories. The watcher test (§7, #11) tells `.run.*` from `<h>/run-*`.
- The design reviewer's watcher objection (a #33-style poller of `.aimon-shell` finds `.run.*`) holds equally for
  `/tmp` with `inotifywait`; it is R1 (interference with a running hook), accepted.

---

## 3. Changes by file

### 3.1 `gradle/libs.versions.toml`

Keep `aimon-core = "0.3.3-SNAPSHOT"`; extend the comment block with "and `ShellFeature.RUN_DIRECTORY`
(kangwoo/aimon-core#275)". Run `./gradlew --refresh-dependencies` once. No shims of core types.

### 3.2 `environment/ShellWrapper.java`

New constants:

```java
static final int RUN_FILES_TAMPERED_EXIT = 73;          // placed bytes ≠ embedded digest (or an extra entry)
static final int INLINE_RUN_FILES_LIMIT = 16 * 1024;    // quoted base64 of all files + the manifest
static final int ARGUMENT_BUDGET = 120 * 1024;          // the whole script, UTF-8; MAX_ARG_STRLEN is 128 KiB
static final String PRIVATE_PREFIX = SHELL_ROOT + "/.run.";
```

`Invocation` gains (all package-private, fluent like the rest):

```java
Invocation privateDirectory(String p)                    // P; null for FG/BG without a run directory
Invocation runDirectory(String variable, List<RunFile> files, boolean uploaded)
Invocation stdinInline(String base64)                    // hook modes; exclusive with stdinUploaded
Invocation stdinUploaded(String sha256)                  // hook modes: {runPrefix}.in → P/.stdin, checked
static final class RunFile { final String relPath; final String base64OrNull; final String sha256; }
```

(`RunFile` is a small final class, not a record, per the repo's convention for value types; the task calls it
`Entry`.) `stdinPath` stays for FG/BG.

Script changes:

- **`INNER`**: read `$1..$5` as today; then
  ```sh
  if [ "$__aimon_mode" = iso ] || [ "$__aimon_mode" = hook ]; then __aimon_cmd=$6; shift 6
  else shift 5; IFS= read -r -d '' __aimon_cmd < "$__aimon_run.cmd"; fi
  ```
  State read stays `[ "$__aimon_mode" != iso ]` (so `hook` reads it, as `bg` did); the EXIT save stays `fg`-only. The
  doc comment lists the new mode word and `$6`.
- **`hook(in)`** uses mode word `hook` (was `bg`). `isolated(in)` stays `iso`.
- **`prologue`** splits by mode:
  - FG/BG: unchanged (`mkdir -p "$d"`, `printf … > "$r.cmd"` unless uploaded), then the run-directory section if any.
  - HOOK/ISO: `d=`, `r=`, `p=` assignments; **no** `mkdir -p "$d"` (nothing of these modes goes under `<h>/`), **no**
    `$r.cmd`; then the private-directory section (always) and the run-directory / uploaded-stdin sections.
- **Private-directory section** (new `appendPrivate`, every mode that has a `P`), illustrative:
  ```sh
  p='/workspace/.aimon-shell/.run.<hex>'
  mkdir -p -- /workspace/.aimon-shell || exit 70     # the shared root: a model command may have removed it
  m=$(umask); umask 077                              # placement only; restored before the inner starts
  mkdir -m 700 -- "$p" || exit 70                    # exclusive: no -p on P itself
  trap 'rm -rf -- "$p"' EXIT
  ```
  The shell root is re-created with a plain `mkdir -p` (no `-m`: it is shared with every shell's state directory, and
  today FG/BG/hook runs re-create it the same way through `mkdir -p "$d"`, `ShellWrapper.java:394`). Without it, a
  model command that removed `/workspace/.aimon-shell` (an anticipated case, `workspace-sandbox.md:1861`) would make
  every inline hook/isolated run fail at `mkdir -m 700` — and with a non-`failOpen` `preTool` guard on `Bash` no
  foreground command could ever run to bring it back. The exclusive create of `P` is unchanged.
- **Run-directory section** (new `appendRunFiles`):
  ```sh
  mkdir -m 700 -- "$p/d" || exit 70
  set -C
  # inline, per file (rel quoted with quote(); dirname of a top-level file is ".")
  mkdir -p -- "$p/d/lib" && printf '%s' 'QkFTRTY0' | /usr/bin/base64 -d > "$p/d/lib/b" \
    || { printf 'cannot place %s\n' 'lib/b' >&2; exit 70; }
  # uploaded, per file
  mkdir -p -- "$p/d/lib" && cat -- "$r.files/lib/b" > "$p/d/lib/b" || { …; exit 70; }
  rm -rf -- "$r.files"                         # uploaded only
  set +C
  # only when there is at least one file (GNU sha256sum -c fails on an empty manifest)
  ( cd -- "$p/d" || exit 70
    printf '%s' '<sha256>  lib/b
  …
  ' | PATH=/usr/bin:/bin /usr/bin/sha256sum -c --strict >/dev/null 2>&1
    s=$?; [ "$s" = 0 ] && exit 0; [ "$s" = 1 ] && exit 73; exit 70 ) || exit $?
  n=$(cd -- "$p/d" && find . ! -type d | wc -l) || exit 70
  [ "$n" -eq N ] || exit 73
  v='AIMON_HOOK_DIR'
  ```
  The manifest is fed by the `printf` builtin through a pipe, not a here-document: bash writes a here-document to a
  temp file in `$TMPDIR` (always in 3.2, and in 5.x past the pipe buffer), which would fail on a read-only root
  without a writable `/tmp` — and the temp file would be one more same-uid window. Only `sha256sum`'s "checksum did not
  match / file missing" status (1, for GNU and for the testkit's `shasum` fallback) becomes exit 73; any other failure
  (`cd`, `find`, a missing binary → 127) is exit 70, so an environment fault is never reported as tampering — the
  task's rule that the integrity exception is for a digest mismatch only. Likewise the count check: `find` failing is
  70, a wrong count 73. `cat >` instead of `cp` so noclobber applies; `--quiet` dropped (output is discarded anyway,
  and the testkit's `shasum` fallback is not guaranteed to know it). The manifest lines are `hex + two spaces +
  relPath`; paths have no newline (refused, §3.3) and no backslash (confined rule), so GNU's escaped-name form never
  applies; the manifest is quoted with `ShellWrapper.quote` and counted in the budget like the files.
- **Uploaded stdin section** (hook modes):
  ```sh
  cat -- "$r.in" > "$p/.stdin" || exit 70; rm -f -- "$r.in"
  ( cd -- "$p" || exit 70
    printf '%s' '<sha256>  .stdin
  ' | PATH=/usr/bin:/bin /usr/bin/sha256sum -c --strict >/dev/null 2>&1
    s=$?; [ "$s" = 0 ] && exit 0; [ "$s" = 1 ] && exit 73; exit 70 ) || exit $?
  ```
- **Restore the umask** after placement and before the watchdog/inner: `umask "$m"`. The hook command runs with the
  exec's own umask, as today; only the placed files and `P` are 0600/0700.
- **`appendWatchdog`** gains a flag: FG/BG write `: > "$r.timedout"` as today; HOOK/ISO do not.
- **`appendInner`**:
  - HOOK/ISO: `"$p/r"` instead of `"$r"` as `$2`; the quoted command as `$6` after root and wd; per-command variables;
    then `"$v=$p/d"` **last** (wins over the same name in `getEnvironment()`, because the inner `export`s `$@` in
    order). No `>"$r.out"`/`2>"$r.err"`; `2>&1` when `redirectErrorStream`. stdin: `printf '%s' 'B64' | /usr/bin/base64
    -d | /bin/bash …` (inline), `< "$p/.stdin"` (uploaded) or `</dev/null`.
  - FG/BG with a run directory: append `"$v=$p/d"` last; everything else unchanged.
- **`epilogue`**:
  - FG/BG: unchanged, plus `rm -rf -- "$p"` before the trailer when `p` is set.
  - HOOK/ISO (new `hookEpilogue`):
    ```sh
    c=0; [ -e "$p/r.cwd" ] && read -r c < "$p/r.cwd"
    rm -rf -- "$p"
    printf '\n%s exit=%d cwd=%d\n' 'AIMON-<nonce>' "$rc" "$c" >&2
    ```
    No `OUTPUT_LOST` check, `head -c`, `wc -c`, `rm -f` of run files.
- New `static long scriptBytes(String script)` (UTF-8 length) for the budget decision, and
  `static String sha256Hex(byte[])` (or put it in `SandboxShell`; `MessageDigest` SHA-256, `HexFormat` lowercase).
- Class doc: the two script families (run-file and stream), the private directory, `base64` added to the required
  binaries ("needs `flock`, `head`, …"), bash 3.2 compatibility kept (no `exec 3> >(…)`, no `mapfile`).

### 3.3 `environment/SandboxShell.java`

**`supports`** (`:609-611`): add `|| feature == ShellFeature.RUN_DIRECTORY`.

**`execute`** (`:145-172`), after the existing checks, all before `pending.connect` (nothing provisioned on refusal):

- If `hookMode = options.isHook() || options.isIsolated()` and `!ShellWrapper.embeddable(text)` →
  `ShellExecutionException("a hook command over 64 KiB is not supported")`.
- If `options.getRunDirectory()` is present (`run`):
  - `ShellWrapper.invalidNames(List.of(run.getVariable()))` empty, else `ShellExecutionException("invalid run
    directory variable name: …")`;
  - every path `SandboxStaging.confined(relPath)` (made package-private, `SandboxStaging.java:137`), else
    `ShellExecutionException("a hook file path is not a relative path inside the run directory: …")`;
  - no path contains `\n` → `ShellExecutionException("a hook file name contains a newline, which the sandbox cannot
    place")`; also refuse `\r` the same way (a normalizing provider never sees it, but `sha256sum` line parsing would);
  - `run.getTotalBytes() <= MAX_RUN_DIRECTORY_BYTES` (new constant, **8 MiB**: core caps at 4 MiB, slack for a core
    raise), else `ShellExecutionException` (never the integrity exception).
- **Plan the script before connecting** (new private static `plan(...)` → the filled `Invocation` plus the list of
  uploads to do): generate `runPrefix`, `nonce` and `P` here (they depend only on the binding), read each file's
  bytes once with `run.getContent(...)`, compute digests in the JVM, then:
  1. build with run files inline if their quoted base64 + manifest ≤ `INLINE_RUN_FILES_LIMIT`, else uploaded;
  2. hook modes: add stdin inline (base64) and keep it if `scriptBytes ≤ ARGUMENT_BUDGET`; otherwise stdin uploaded;
  3. if the script (stdin uploaded) is still over budget and run files were inline → switch them to uploaded, rebuild;
  4. still over → `ShellExecutionException("the hook's command, environment and files are too large to pass to the
     sandbox (… bytes, at most …)")`.
  FG/BG keep their stdin upload and their command upload; the budget check applies to them only when they carry a run
  directory (otherwise their caps already sum under the limit, as today).
- `run(...)` takes the plan; after connect it performs the uploads (`upload(files, runPrefix + ".files/" + rel, bytes,
  "a hook file")`, `upload(files, runPrefix + ".in", bytes, "stdin")`) — an upload failure is
  `ShellExecutionException`/`SandboxUnavailableException` as today, never the integrity exception.

**`Mode`** gains `boolean streams()` (true for `HOOK`, `ISOLATED`). **`Run`** gains `mode`, `privateDirectory`
(nullable), `uploadedRunFiles`, `uploadedStdin`; `watched` unchanged (`mode != BACKGROUND`, `:330`).

**`await`** (`:335-404`): for `streams()` runs, pass a `TrailerWatch` sink instead of `OutputSink.DISCARD` (`:365`).
`TrailerWatch` (new package-private class in `environment/`):

- keeps a bounded tail (e.g. 512 bytes) of STDERR chunks and a running count of STDERR bytes; on seeing
  `\n{nonce} exit=<int> cwd=<d>\n` complete, records the **parsed trailer** (exit code, cwd flag) and the STDERR byte
  offset at which its marker began, sets `seen` once and runs the kill action. It sees every chunk the provider
  forwards, whatever the provider's capture cap (both providers hand the sink every chunk and only *keep* the first
  `maxCaptureBytes`: `LocalRunningCommand.Capture.run`, `OpenSandboxRunningCommand`), so the trailer survives a
  stderr larger than `captureBackstop(max)` — see `completed` below;
- the kill action must not block the provider thread (`OutputSink` contract; OpenSandbox's kill is an HTTP `DELETE`),
  so it starts a daemon thread `sandbox-hook-trailer-kill` that waits a short **drain grace** (`TRAILER_KILL_GRACE`,
  500 ms, cut short when `await` returns first) and then calls `killQuietly(running.get())`. The grace exists because
  execd reads stdout and stderr separately and emits one event per line, so the trailer line can arrive before the
  inner's last stdout lines (the hook's JSON verdict) have been forwarded; the inner wrote them before the outer
  printed the trailer, so they are in execd's pipe already and a few hundred ms is ample (the same
  `AtomicReference` for `running`
  `AtomicReference`; if `running` is not yet set, the existing "made up for below" check after `run(...)` also looks at
  `trailerSeen`);
- STDOUT chunks are ignored by the watch.

The kill is unconditional once the trailer is seen (both providers' `kill()` is idempotent; on a finished exec it is a
no-op or a swallowed error) so the "descendants die with the hook" rule holds on the local provider too, whose
`await` returns at the leader's exit and would otherwise leave them running. See Q-C for the per-hook `DELETE` cost.

**`classify`** (`:406-429`) for `streams()` runs, in this order:

1. `completed(...)` — a trailer wins over `outcome.timedOut()` and over the kill's exit code (as the cancel path
   already does at `:391-398`);
2. no trailer and `exitCode == RUN_FILES_TAMPERED_EXIT` → `throw new RunDirectoryIntegrityException("a hook file or
   the hook's input placed for the run does not match its digest")` (no content in the message);
3. `outcome.timedOut()`, or (`run.timeout` set, `exitCode == 137`, elapsed ≥ `run.timeout`) → `timeout(...)` built from
   `outcome.stdout()/stderr()` (JVM-capped), never from run files. Elapsed is the JVM's own `duration`, measured from
   before `connection.run`, so it is never shorter than the watchdog's sleep: no tolerance needed (**D6**);
4. otherwise the generic wrapper failure (unchanged; its message carries stderr, e.g. "cannot place lib/b").

FG/BG keep today's order and `.timedout` check.

**`completed`** (`:434-451`) for `streams()` runs:

- second pattern `HOOK_TRAILER = "exit=(-?\\d+) cwd=(\\d)\\r?\\n"` matched at the marker (not end-anchored: a
  descendant may write to stderr after the trailer before the kill lands; bytes after the trailer line are dropped);
- **where the trailer comes from**: the marker in `outcome.stderr()` when present (as today). When it is absent and
  `outcome.stderrTruncated()` — the hook wrote more than `captureBackstop(max)` (3 × max + 1 KiB) to stderr, so the
  provider kept only the head and the trailer at the end was dropped — the trailer is the one `TrailerWatch` parsed
  from the stream. Today `head -c` in the sandbox caps stderr before the trailer is printed, so this case could not
  arise; with stderr on the exec stream it can (a linter, `set -x`, a guard that logs its agent-controlled stdin to
  stderr before `exit 2`), and without the fallback it would read as "the wrapper failed" → core `EXECUTION_FAILED`,
  letting a `failOpen` guard that meant to block pass. Without either (no marker, no watched trailer) the run has no
  trailer, as before;
- stdout = `outcome.stdout()` truncated to `run.max` bytes; stderr = bytes before the marker (or, in the watched case,
  the captured head) truncated to `run.max`; `outputTruncated = stdout.length > max || stderr kept > max ||
  outcome.stdoutTruncated() || outcome.stderrTruncated()` (byte lengths as received, i.e. after any §6.1
  normalization; see failure modes). Truncation cuts bytes, then decodes with `run.charset` (a cut multi-byte char
  becomes U+FFFD, as `head -c` + decode does today). The same rule applies to stdout past the backstop: the provider
  keeps its head, which is ≥ `max`, so the JVM's cut is unaffected.

**`timeout` / `cancelled`** (`:471-493`): for `streams()` runs never call `readRunFile`; use the outcome's streams
(JVM-capped) or empty when there is no outcome (interrupted wait).

**`cleanUp`** (`:566-576`) takes the `Run`:

- FG/BG: today's suffixes; plus, when present, `{runPrefix}.files` and `P` (`files.delete(path, true)`).
- HOOK/ISO: `P` (recursive), and `{runPrefix}.files` (recursive) / `{runPrefix}.in` only when uploaded. No stat of
  `.out/.err/.cmd/.cwd/.timedout` (they are never created).
- Each delete `stat`-guarded and best-effort; a failure is logged at WARN for `P` (contract rule 5: "logged, not the
  command's failure"), DEBUG for the rest as today.
- `wrapperCleanedUp` stays "a trailer was read". A trailer forged by the hook itself before the real one would skip
  JVM cleanup of `P` after the kill: litter, not a tamper vector (each run creates a fresh `P`).

Class doc (`:52-95`): drop "(Its run files still live in the agent-writable shell directory: #33.)"; describe
`RUN_DIRECTORY`, the stream mode, and the kill on trailer.

### 3.4 `environment/SandboxStaging.java`

- `confined` (`:137`) `private static` → package-private `static`.
- Class doc (around `:47`): "Verified on every call — core's `ExecutionEnvironment.stage()` contract since EE-109;
  never trusted because an earlier call checked it."
- No functional change.

### 3.5 `workspace/SandboxSeeder.java` (+ docs; addendum)

- Image-contract loop (`:131-132`): `for t in git rg flock sha256sum base64`, message "it must provide git, rg, flock,
  sha256sum and base64". Class doc (`:26`) likewise. `SandboxSeederTest` assertion updated.
- The test Docker image already installs `coreutils` (`modules/aimon-sandbox-opensandbox/src/test/docker/Dockerfile:6`),
  which has `base64`; update its header comment.
- Not added: a sweep of leftover `.run.*` at seed (Q-B).

### 3.6 Testkit (`aimon-sandbox-testkit`)

No published change is required. `/usr/bin/base64` exists on macOS and Linux and `-d` decodes on both (macOS ≥ 10.x
accepts `-d` as `-D`). If CI shows a host without it, add a shim + rewrite exactly like `sha256sum`
(`LocalConnection.SHA256SUM`, `LocalProcessSandboxProvider.installShims`) — recorded as a contingency, not planned.

### 3.7 Docs (`docs/design/workspace-sandbox.md`, ko canonical; no `.en.md` exists in the repo — checked)

- §9: hook/isolated rows — no run files under `.aimon-shell/<h>/`; command as argument; stdin inline/upload+digest;
  output on the exec streams with the JVM cap; timeout by clock; the private directory; the kill on trailer (Q8
  behaviour change: a hook's `cmd &` is killed when the hook ends); `RUN_DIRECTORY` placement and the exit codes 70/73.
- §11.1 layout: `/workspace/.aimon-shell/.run.*` (per hook run, removed after).
- §12.1, the one bullet at `:1563-1570` (residual risk + operator guidance; one edit, not two): run files and `AIMON_HOOK_DIR` are no longer agent-writable between runs; the
  running hook (its `P`, `/proc/<pid>/fd`, the trailer nonce in argv) still is — R1.
- §13.3 image contract (`:1752-1754`) and the seed pseudo-code (`:1398`, `:1418`): add `base64`.
- §15/§16 (`:1861`): the "`.aimon-shell` removed, output lost" row applies to foreground/background only; new rows:
  integrity mismatch → `RunDirectoryIntegrityException` (core: `HOOK_FILES_TAMPERED`, blocks whatever `failOpen`),
  oversized hook command / files / script → refused before connecting, collision → not run.
- §6.1 normalization note: the hook-mode cap is applied in the JVM on received bytes.
  The guide sentence in the same bullet ("run 파일(`.aimon-shell`, #33)과 … `AIMON_HOOK_DIR`(코어 F12)은 여전히 에이전트가
  쓸 수 있으므로 …") is rewritten in that edit: files are now protected before the run, not while it runs.
- `CHANGELOG.md` `[Unreleased]`: Added `RUN_DIRECTORY` (`hooks.json` + `AIMON_HOOK_DIR` now runs on the sandbox; skill
  hooks get `AIMON_SKILL_DIR` under `.aimon-shell/.run.*`); Changed: hook/isolated I/O off run files; Changed
  (behaviour): a hook's background descendants are killed when it ends; image contract adds `base64`.

### 3.8 Run records (`build/`)

- `build/deviations.md`: D1–D15 (§9 below).
- `build/core-followup.md`: core-side items found here (§10, F1–F3).

### 3.9 Issues (task §5)

Widen #33's title/body (already widened per the comment; retitle) and file the uid-boundary issue with the task's
text — at the end of the build run, by the orchestrator, not by code.

---

## 4. Data and interface shapes that change

| Shape | Before | After |
|---|---|---|
| `SandboxShell.supports` | PIPE, REDIRECTION, CANCELLATION, ISOLATED_EXECUTION | + `RUN_DIRECTORY` |
| Exceptions from `execute` | `ShellExecutionException` family | + `RunDirectoryIntegrityException` (exit 73, no trailer) |
| `ShellWrapper` constants | 70, 71, 75; 64 KiB / 32 KiB caps | + `RUN_FILES_TAMPERED_EXIT=73`, `INLINE_RUN_FILES_LIMIT=16 KiB`, `ARGUMENT_BUDGET=120 KiB`, `PRIVATE_PREFIX` |
| `SandboxShell` constants | — | + `MAX_RUN_DIRECTORY_BYTES = 8 MiB` |
| `INNER` positionals | `$1 dir $2 runprefix $3 fg|bg|iso $4 root $5 wd`, then `K=V…` | `hook` mode word added; for `iso`/`hook` `$6` = command, `$2` = `P/r`; `VAR=P/d` last |
| Hook/iso trailer | `\n{nonce} exit=N out=B err=B cwd=C` | `\n{nonce} exit=N cwd=C` |
| Files in sandbox, hook/iso | `.aimon-shell/<h>/run-*.{cmd,in,out,err,cwd,timedout}` | none under `<h>/` unless uploads (`run-*.files/`, `run-*.in`, removed by the wrapper right after copying); `.aimon-shell/.run.<hex>/{d/…,.stdin,r.cwd}` during the run |
| Files in sandbox, FG/BG | unchanged | + `.aimon-shell/.run.<hex>/d/…` only with a run directory |
| `SandboxStaging.confined` | `private static` | package-private `static` |
| Seed image contract | `git rg flock sha256sum` | + `base64` |
| `ExecSpec`/provider SPI | — | unchanged (`OutputSink` already exists; now used) |

No public API of this library changes.

---

## 5. Failure modes

| Failure | Where detected | Result | Core's mapping |
|---|---|---|---|
| Invalid variable / unconfined path / newline in a path / total > 8 MiB / hook command > 64 KiB / script over budget | `execute`, before connect | `ShellExecutionException`; nothing provisioned | `EXECUTION_FAILED` (`failOpen` passes) |
| Upload fails (disk full, provider error) | `upload` | `ShellExecutionException` / `SandboxUnavailableException` (as today) | `EXECUTION_FAILED` / unavailable |
| `P` exists (`mkdir` fails), `mkdir`/decode/copy fails, two paths the FS merges (noclobber), `a` and `a/b` both present | wrapper, exit 70 + stderr line | generic wrapper failure with the message; command not run; JVM `cleanUp` | `EXECUTION_FAILED` |
| Placed bytes ≠ digest, extra/missing entry, uploaded stdin ≠ digest | wrapper, exit 73 | `RunDirectoryIntegrityException`; `P` removed by trap + JVM | `HOOK_FILES_TAMPERED` (blocks whatever `failOpen`) |
| Hook timeout | watchdog kills group (137), no trailer, elapsed ≥ timeout | `ShellTimeoutException` with streamed partial output; JVM removes `P` | `TIMEOUT` |
| No timeout, backstop | provider `timedOut()` | `ShellTimeoutException` | `TIMEOUT` |
| Cancel / interrupt | as today | `ShellCancelledException` with outcome streams (or empty); `P` removed by JVM | `CANCELLED` |
| Background descendant holds the exec streams | `TrailerWatch` sees trailer | exec killed; result from trailer | — (Q8 behaviour change) |
| Descendant writes to stderr after the trailer | `completed` | bytes after the trailer line dropped | — |
| Output over `max` | JVM | truncated, `outputTruncated()` | — |
| stderr over `captureBackstop(max)` (provider dropped the tail, trailer included) | `TrailerWatch` parsed the trailer from the stream | result from the watched trailer; stderr = captured head cut to `max`, `outputTruncated()` | normal verdict (exit 2 still blocks) |
| `/workspace/.aimon-shell` removed by a model command | `mkdir -p` of the shell root in the prologue | re-created; the hook runs normally | — |
| Upload deleted/unreadable before the wrapper copies it | wrapper, exit 70 | generic failure, command not run | `EXECUTION_FAILED` (R1-class; not reported as tampering per the task's rule) |
| A late stdout line still in execd when the trailer arrives | kill after `TRAILER_KILL_GRACE` (500 ms, cut short by `await` returning) | full stdout in practice; a line still unforwarded after the grace would be lost (accepted, tested in docker IT) | — |
| Normalizing provider grows output (U+FFFD ×3, added final `\n`) | JVM compares received bytes | may report `truncated` for output that was ≤ max raw, and cut at a normalized offset (Q-E) | — |
| Removal of `P` fails | `cleanUp` | WARN log only | — |
| JVM dies after a watchdog kill | — | `.run.<hex>` litter until the sandbox goes (Q-B); never reused | — |
| Forged trailer by the hook itself or a same-uid process (nonce in `/proc/<pid>/cmdline`) | — | accepted residual R1 | — |
| Same-uid process writes into `P` while the hook runs | — | accepted residual R1 | — |
| Empty run directory | wrapper | `P/d` created and exported, verification skipped (empty manifest would fail GNU `sha256sum -c`), count check `-eq 0` | — |

The integrity exception is thrown only for exit 73 with no trailer. The command cannot produce that combination by
itself (its `exit 73` ends only the inner; the outer still prints the trailer); a same-uid process killing the outer
produces a signal code, not 73.

---

## 6. Behaviour kept

- FG/BG: run files, `head -c` cap in the sandbox, `.timedout`, `OUTPUT_LOST`, trailer with `out=`/`err=`, stdin upload,
  command upload over 64 KiB. Existing `ShellWrapperTest`, `SandboxShellIT`, `SandboxShellCaptureTest` pass unchanged
  except where they assert the hook script shape (mode word `bg` → `hook`).
- Locking, heartbeat (FG only), cancellation checkpoints, notices.
- Skill hooks via `stage()` fallback still work (core picks `RUN_DIRECTORY` now, so they move to `P/d`).

---

## 7. Test strategy

Unit tests run in `checkAll` (`test` task). `SandboxShellIT`/`SandboxStagingIT`/`OrcaRuntimeSandboxE2ETest` use
`LocalProcessSandboxProvider` and are **not** docker-tagged, so they run in `checkAll` too. Docker tests
(`@Tag("docker")`, `OpenSandboxWorkspaceIT` in `aimon-sandbox-opensandbox`) run via `integrationTest` when Docker is
available.

**`ShellWrapperTest`** (script shape, no execution):

- hook/iso scripts: no `"$r.out"`, `"$r.err"`, `"$r.cmd"`, `"$r.timedout"`, no `mkdir -p "$d"`; command as the
  argument after wd; mode word `hook`; `"$v=$p/d"` is the last argument; trailer without `out=`/`err=`.
- run directory: `mkdir -m 700 -- "$p"`, `set -C`, the shell-root `mkdir -p` before `mkdir -m 700`, `umask "$m"` restored before the inner, the manifest fed by `printf` (no `<<`), status 1 → `exit 73` and other statuses → `exit 70`; inline vs
  uploaded variants; no `sha256sum` line for an empty run directory.
- FG with a run directory: run files kept, `rm -rf -- "$p"` before the trailer.

**`TrailerWatchTest`** (new unit): trailer split across chunks at every offset; wrong nonce ignored; a trailer line
without its newline not yet matched; STDOUT ignored; the action runs once.

**`SandboxShell` planning unit** (via package-private `plan` or through `ShellWrapper.scriptBytes`): inline/upload
boundary at exactly `INLINE_RUN_FILES_LIMIT` and one byte over; stdin moves to upload when the script would exceed
`ARGUMENT_BUDGET`; over budget after both uploads → refused.

**`SandboxShellIT`** (local provider) — numbers follow the task's §4:

1. `supports(RUN_DIRECTORY)`.
2. Inline: files `a`, `lib/b`; `cat "$AIMON_HOOK_DIR/a"; cat "$AIMON_HOOK_DIR/lib/b"; echo "$AIMON_HOOK_DIR"` → both
   contents; path absolute, starts with `/workspace/.aimon-shell/.run.`, ends `/d`; after the call
   `files.stat(P)` empty.
3. Upload: 40 KiB file → same; `{runPrefix}.files` gone (list `.aimon-shell/<h>/` for `run-*`).
4. Tamper: test-local `TamperingProvider` (decorates a `SandboxProvider`; its connection's `files().write` delegates,
   then for paths ending `.files/a` or `.in` writes different bytes) → `RunDirectoryIntegrityException`; a `touch`
   marker absent; no `.run.*` left.
5. `AIMON_HOOK_DIR=/planted` in `getEnvironment()` → not `/planted`.
6. `P` removed after success, `exit 3`, timeout (`sleep 30`, 1 s — pins JVM-side `cleanUp`), cancel.
7. `Run.sh` + `run.sh`: probe case sensitivity of the sandbox dir first; case-sensitive → both verify; case-insensitive
   (macOS APFS dev hosts) → `ShellExecutionException`, not the integrity exception, command not run (pins contract
   rule 2). CI (GitHub Actions, Linux ext4) exercises the "both verify" branch; macOS dev hosts the refusal branch.
   The refusal branch is also pinned on Linux by #22 (a file and a directory of the same name), which collides on any
   filesystem.
8. `스크립트.sh` through both transports.
9. Boundary: exactly `INLINE_RUN_FILES_LIMIT` inline (no `.files` upload observed via a spying provider), one over
   uploads.
10. Newline in a path → `ShellExecutionException`, provider never connected (spy / fault provider counts `connect`).
11. During isolated and hook runs, `ls -A /workspace/.aimon-shell/*/ 2>/dev/null | grep run- || true` → empty; the same
    probe in a foreground command lists its own `run-*` (the probe works).
12. Background writer loop forging `run-*.out` started by a model-facing background command cannot change an isolated
    command's stdout/exit code.
13a. **stderr past the backstop:** a hook writes `captureBackstop(max) + 64 KiB` to stderr then `exit 2` (small
   `maxCaptureBytes`, e.g. 4 KiB, so the test is cheap) → a normal result with exit 2, `outputTruncated()`, stderr cut
   to `max`; not "the sandbox shell wrapper failed". Same for stdout past the backstop.
13b. **removed shell root:** a foreground `rm -rf /workspace/.aimon-shell`, then an isolated command and a hook command
   with an inline run directory run normally (and a foreground one after them still works).
13. Output over `maxCaptureBytes` truncated + `outputTruncated()`; under it exact; with and without
    `redirectErrorStream`.
14. `(sleep 2; touch /workspace/late) & echo done` in a hook → returns `done` well under 2 s; after 3 s `late` does not
    exist (pins the kill, which the local provider would otherwise not exercise).
15. Isolated timeout → `ShellTimeoutException` whose stdout has the `echo partial` printed before the sleep.
16. stdin ≥ 1 MiB → upload path, `wc -c` exact; tampered upload → `RunDirectoryIntegrityException`.
17. **Changed (D4):** a NUL in stdin arrives intact (`od -c | grep '\\0'`), inline and uploaded; a non-UTF-8 charset
    (ISO-8859-1 `é`) arrives as that charset's byte.
18. Hook command > 64 KiB → refused before connecting.
19. `iso` with the root removed → runs in `/workspace`, cwd notice from `cwd=2`, no `.cwd` file under `<h>/`.
20. Foreground/background unchanged (existing tests).
21. Empty `RunDirectory` → variable set, directory exists and is empty during the run, gone after.
22. A file and a directory of the same name (`lib`, `lib/x`) → not run, `ShellExecutionException`.

**`SandboxStagingIT`**: `aCopyRewrittenBetweenTwoStagesIsCopiedAgain` (task §3).

**`OrcaRuntimeSandboxE2ETest`**: `hooks.json` `preTool` guard `bash "$AIMON_HOOK_DIR/guard.sh"` exiting 2 **blocks**
(was `RUN_DIRECTORY_UNSUPPORTED`); a skill hook printing `$AIMON_SKILL_DIR` shows `/workspace/.aimon-shell/.run.…/d`,
not `.aimon-staged`.

**`OpenSandboxWorkspaceIT`** (docker): inline and upload round trip; integrity on a tampered upload (same decorator
idea over the OpenSandbox provider, if the IT's wiring allows; otherwise skip and note); `sleep 30 & echo done` hook
returns promptly (this is where execd's stream-holding behaviour is real); a hook that prints ~256 KiB of stdout in
many lines and exits at once arrives whole (pins the drain grace before the kill); isolated output exact through line
normalization; image contract passes with `base64`.

**`SandboxSeederTest`**: the contract loop names `base64`.

`./gradlew checkAll`, then `./gradlew integrationTest` if `docker info` succeeds.

---

## 8. Q8 — kill on trailer (needs the maintainer)

Implemented as the design recommends (kill), because the alternative turns every backgrounding hook into a
backstop-length call. It is a sandbox-side behaviour change: a `postTool` hook's `notify-send &` (or any `&` job left in
the exec's process group) is killed when the hook's trailer arrives. A job that left the group (`setsid`, `set -m`)
survives, as today. Documented in §9 and the CHANGELOG. If the maintainer rejects it, the fallback is: no
`TrailerWatch` kill, and document that a backgrounded hook job extends the call to the backstop.

---

## 9. Deviations from the task text (to `build/deviations.md`)

- **D1** Private directory name is an independent JVM random (`.run.<32 hex>`), not `runPrefix`'s suffix — uploads
  leak the suffix before the exec.
- **D2** The exported directory is `P/d`, not `P`; `P` also holds `.stdin` and `r.cwd`.
- **D3** `P` exists for every hook/isolated run (hosts stdin upload and the cwd flag — the task's §2.6 "temp file inside
  `$p`" option, made unconditional).
- **D4** Inline stdin is base64 (`/usr/bin/base64 -d`), not quoted text; no NUL refusal; test 17 changed.
- **D5** The kill on trailer comes from a streaming `OutputSink` on a daemon thread, not "after `completed`".
- **D6** Clock timeout = no trailer ∧ exit 137 ∧ JVM elapsed ≥ timeout, no tolerance.
- **D7** Empty run directory skips `sha256sum -c`.
- **D8** Added an entry-count check after the digest check (exit 73).
- **D9** Placement under `set -C` with `cat >` (not `cp`), to make merged paths an error (contract rule 2).
- **D10** Hook trailer matched at the marker, not end-anchored; stderr after it dropped; trailer beats `timedOut()`.
- **D11** `sha256sum -c --strict` without `--quiet`; `\r` in a path refused alongside `\n`.
- **D12** Manifests fed by the `printf` builtin through a pipe, not a here-document (no `$TMPDIR` temp file); only
  `sha256sum` status 1 maps to 73, other statuses to 70.
- **D13** `umask 077` scoped to placement and restored before the inner bash.
- **D14** Hook/isolated prologues still re-create the shell root (`mkdir -p -- /workspace/.aimon-shell`) though they no
  longer create `$d`.
- **D15** `TrailerWatch` keeps the parsed trailer; `completed` falls back to it when the provider truncated stderr. The
  kill waits a 500 ms drain grace.

---

## 10. Core follow-ups (to `build/core-followup.md`; core is read-only here)

- **F1** `RunDirectory.Builder.file` accepts both `a` and `a/b`, and file names with `\n`/`\r`; core could refuse them
  up front so every shell does not have to.
- **F2** `HOOK_FILES_TAMPERED`'s text says "the hook's files changed"; on the sandbox the same exception also covers a
  tampered uploaded stdin payload. Either widen the wording or add a cause.
- **F3** The 4 MiB cap (`HookRunFiles.MAX_BYTES`) is package-private; a public constant (or a shell-side
  `maxRunDirectoryBytes` hint) would let the sandbox's ceiling follow it instead of a guessed 8 MiB.

---

## 11. Open questions

- **Q8** Kill a hook's background descendants on trailer? (§8) Implemented as "kill" pending confirmation.
- **Q-A** Accept D4 (base64 stdin, NUL carried) instead of the task's quoted-text + NUL refusal?
- **Q-B** Leftover `.aimon-shell/.run.*` after a JVM crash following a watchdog kill: sweep them (seed? janitor? only
  entries older than the longest hook backstop, since another node may be running a hook in the same sandbox), or
  leave them? Proposed: leave; they are never trusted by a later run.
- **Q-C** The unconditional kill usually costs one `DELETE /command` per hook run on OpenSandbox: its `kill()` sends
  none once the terminal event has arrived, but the trailer precedes that event and the grace is short, so on most
  runs the `DELETE` still goes out. Acceptable, or kill only when `await` has not returned within the grace (which
  leaves local-provider descendants alive, making the behaviour provider-dependent)?
- **Q-D** File tools can *read* `.aimon-shell/.run.*` while a hook runs (the rule is read-only, not deny). Hook bytes
  are not secret under EE-109's scope; should a deny rule be added anyway?
- **Q-E** Under a line-normalizing provider the JVM cap compares received (normalized) bytes, so output of exactly
  `max` raw bytes can be reported truncated (added final `\n`, U+FFFD growth). Carrying exact raw counts would need an
  in-sandbox counter on the streams (rejected above). Accept the imprecision for hook modes?
- **Q-F** `MAX_RUN_DIRECTORY_BYTES` = 8 MiB (slack) vs exactly core's 4 MiB?
- **Q-G** Should a `FOREGROUND`/`BACKGROUND` run with a run directory (core does not send one today) also be covered by
  an IT, or only by the script-shape unit test?

---

## 12. Implementation departures

Status: **IMPLEMENTED** on branch `herdr/sandbox-run-directory`. §1–§11 above are the design as approved (review 2
passed it with non-blocking items); this section says where the code differs and why. The architecture document
[`workspace-sandbox.md`](workspace-sandbox.md) has absorbed the change (§6.1, §9, §11.1, §12.1, §13.3, §15, §16), and its
§20 carries the open questions of §11 that outlive it — Q8, Q-B, Q-C, Q-D, Q-E and the uid boundary (#34) — each
pointing back here. Q-A, Q-F and Q-G are answered below.

### Where the code differs from the design

- **`sha256sum -c --strict -`, with an explicit `-`.** §3.2 reads the manifest from stdin with `sha256sum -c --strict`.
  The testkit's `sha256sum` shim runs the host's binary, and on a macOS host that is the BSD `/sbin/sha256sum`, which
  prints its usage and exits 1 when `-c` has no file operand — status 1, which the script maps to exit 73, so every hook
  with a run directory read as tampered on a developer's machine. `-` names stdin for GNU and BSD alike; GNU's
  behaviour is unchanged. `ShellWrapper.appendCheck` and its test pin the form.
- **The variable is written `'NAME'"=$p/d"`, not through `v='NAME'` and `"$v=$p/d"`.** The same argument, one shell
  variable fewer. It is still the inner bash's last argument.
- **`TrailerWatch` keeps the parsed trailer, not the STDERR offset of its marker.** Nothing needed the offset.
  `completedStream` uses the trailer in the captured stderr when it is there and complete, and the watched one
  otherwise — whenever the captured stderr lacks a complete trailer, not only when `stderrTruncated()` says the
  provider cut it. In the watched case the stderr is the captured head cut to `max`, as §3.3 says. Either source
  suffices; with neither the run has no trailer (§3.3's own wording).
- **The trailer kill when the trailer beats `run(...)`.** §3.3 had the post-`run(...)` check also look at a "trailer
  seen" flag. The kill thread instead waits the grace and, if the command is still unknown then, waits for the `await`
  to return before killing; `await` cannot return before `run(...)` has. One mechanism instead of two.
- **A model's command with a run directory maps exit 73 to the integrity exception too.** §3.3 described the mapping in
  the stream classification; §5's failure table applies it to every run with a run directory. `classify` (foreground,
  background) throws `RunDirectoryIntegrityException` for exit 73 without a trailer when the run has a private
  directory, after the `.timedout` and lock-busy checks.
- **A model's command with a run directory runs in a subshell.** The variable is a per-command variable, and the inner
  bash runs a command that has any in a subshell, so that command's own `cd` and `export` are not saved — exactly as for
  any `ExecutionOptions.environment`. Core sends a run directory only with hook commands (§10's Q-G), so no caller sees
  it; the `Q-G` test pins only placement and cleanup.
- **Refusals carry fixed text.** "a hook command over 64 KiB is not supported" (the size is `INLINE_COMMAND_LIMIT`);
  `\r` and `\n` share the newline message; a NUL in a path is refused with the confinement message (core refuses it
  first).
- **The architecture document's capture note was stale.** WS §9 still said the provider-side cap is `max + 1 KiB`; it
  has been `3 × max + 1 KiB` since step 4 (`captureBackstop`). Corrected in the same edit as the hook-mode note.

### Tests placed or shaped differently from §7

- The `SandboxShellIT` cases of §7 are in a class of their own, `SandboxShellRunDirectoryIT` (`SandboxShellIT` is 1300
  lines); its display names carry §7's numbers. Two cases were added: a script over `ARGUMENT_BUDGET` with stdin and
  every file uploaded (a long manifest) is refused before connecting; and the Q-G foreground case.
- §7 #3 and #8 share a test (the upload transport with a non-ASCII name); #8's inline half has its own.
- §7's tamper tests use `TamperingFiles`, a files-API decorator next to `FailingFiles`, installed through a
  `RecordingProvider` in a harness of their own (the files wrapper applies to connections opened after it is set).
- No separate "planning unit" test: `SandboxShell.plan` is private, and the boundary (#9), the stdin-to-upload switch
  (#16, the script is inspected through `RecordingProvider`) and the refusal (above) are pinned end to end.
- §7 #12's writer is started on an executor thread: a background command's `execute` holds its caller until it ends,
  as core's manager runs it.
- §7 #13b: the foreground `rm -rf /workspace/.aimon-shell` is itself reported as "output lost" (WS §15) — the test
  expects that, then the runs after it.
- `OrcaRuntimeSandboxE2ETest`'s skill-hook case drives core's `DefaultShellActionExecutor` with a scanned
  `StagedResource` directly instead of through `OrcaAgentExecutor`: a skill hook gets its resource from core's
  skill-scoped hook registry, which this repository's E2E wiring does not build. The `hooks.json` case runs through
  `OrcaAgentExecutor` as §7 says.
- `OpenSandboxWorkspaceIT` (docker) has no tamper case: its wiring talks to `OpenSandboxProvider` directly and has no
  provider decorator; the local-provider tamper tests cover the script, which is the same. Its other §7 rows run in one
  test against one sandbox (`seq 1 40000` for the drain grace, ~230 KiB in 40 000 lines).

### Review 2's non-blocking items

| Item | Done |
|---|---|
| `find` failing gave `n=0` and exit 73 | `l=$(cd -- "$p/d" && find . ! -type d) \|\| exit 70`, then the count; `find` failing is 70 |
| `sha256sum` status 1 also covers "could not be read" | `appendCheck`'s comment says "a digest that does not match, or a listed file that is gone"; inside a fresh 0700 directory that needs same-uid interference, so 73 stands |
| `base64: write error` on a hook that never reads its stdin | the decoder's stderr goes to `/dev/null` |
| Q-C's cost wording | WS §20's entry says the `DELETE` falls on hooks whose exec outlives the grace, i.e. ones that leave a descendant holding the streams |
| D6 classes an OOM kill or a same-uid `kill -9` after the timeout as a timeout | accepted, harmless (`TIMEOUT` instead of `EXECUTION_FAILED`); recorded here |
| The seed's `command -v base64` passes an image with only `/bin/base64` | not changed: the seed follows the `sha256sum` precedent (§3.5). Left as a sandbox-side follow-up — an `[ -x /usr/bin/… ]` check would cover `sha256sum` and `base64` together |

### Open questions — answers and where they went

- **Q-A** (base64 stdin, NUL carried) — built as designed (D4); `SandboxShellRunDirectoryIT` #16/17 pin a NUL and an
  ISO-8859-1 byte inline and uploaded. Local to this change.
- **Q-F** (8 MiB vs 4 MiB) — 8 MiB (`SandboxShell.MAX_RUN_DIRECTORY_BYTES`); following core's constant needs core to
  publish it (F3).
- **Q-G** — one IT and the script-shape test; see "Where the code differs" for the subshell consequence.
- **Q8, Q-B, Q-C, Q-D, Q-E** and the uid boundary — WS §20, each linking here.
