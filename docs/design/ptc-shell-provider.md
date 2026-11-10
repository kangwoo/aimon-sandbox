# Design — PTC shell engine, provider half (D2: S3–S6, S8 provider side)

Base: `main` @ `ff49985`. Core: `at.aimon.core:*:0.3.4-SNAPSHOT` as published 2026-11-06 (core `main` @ `2feb5d83`,
PRs #330/#333/#334/#335). Spec: core `docs/design/tool/programmatic-tool-calling-shell.md` (below **shell doc**) §3–§5,
§10, §13, §14; this repo's `docs/design/ptc-shell-prerequisites.md` (below **memo**) §6–§10.

---

## 0. Facts established before designing (verified, not assumed)

These were checked against the real snapshot and the real code, and several of them change what the memo said.

| # | Fact | How verified | Consequence |
|---|---|---|---|
| F1 | The current tree **compiles** (`compileTestJava`, all modules) against the new snapshot. | Clean copy of `HEAD` in a scratch dir, `./gradlew compileTestJava` → exit 0; the resolved jar contains `RuntimeFileSystem`/`ShellKillCause`. | No compile-level core-compat work. |
| F2 | `checkAll` against the new snapshot: **780 tests, 1 failure**: `OrcaRuntimeSandboxE2ETest.aSkillHooksDirectoryIsAPrivateRunDirectory` (line 389) — `NullPointerException: Origin cannot be null` from `PreToolContext.Builder.build()`. Core #330 made `origin(...)` required on the hook-context builders. | Same scratch copy, `./gradlew checkAll --continue`. | One-line test fix. Core-compat is tiny → **one PR**, no split. |
| F3 | `aimon-code-engine-testkit:0.3.4-SNAPSHOT` **is published** to Central snapshots (2026-11-06 09:38). `ShellCodeExecutionEngine.create()` is public. | `maven-metadata.xml` on the snapshot repo; core source. | The engine contract suite can be run here with no core change (see §5.7 for the one obstacle on the testkit tier and how it is avoided). |
| F4 | Core ships **no published `RuntimeFileSystem` contract suite**. `LocalRuntimeFileSystemTest` is a core unit test. The memo §8 ("run core's RuntimeFileSystem contract suite") assumed one. | Core tree search. | This repo writes its own contract (§5.6). Record the gap in the memo. |
| F5 | The engine puts its directory at **`descriptor().workingDirectory() + "/.aimon-ptc"`** (`ShellCodeExecutionEngine.ptcRoot`). Our descriptor's working directory is `binding.root()`, whose default is **`/workspace/repo`** (`SandboxBinding.DEFAULT_ROOT`). So replies land in `/workspace/repo/.aimon-ptc/…`, **not** `/workspace/.aimon-ptc/` as the memo §8 assumed — and S9's rule `PathRule.deny("workspace/.aimon-ptc")` does not cover it. | Core source + `SandboxBinding`. | S9 must be extended to the binding root (§5.3). Worth an open question to core (Q-C1). |
| F6 | The engine **never calls `RuntimeFileSystem.read`**. It calls `createDirectories`, `writeNew` (replies, probe file) and `delete` (probe file, run directory). The probe checks visibility with the shell (`test -f`). `ShellProgramRun` distinguishes `java.nio.file.FileAlreadyExistsException` from other `IOException`s. | Core source (`ShellCodeExecutionEngine`, `ShellProgramRun`). | `read` must honour the interface contract but is not on any hot path; exception *types* on `writeNew` matter. |
| F7 | The engine classifies `PROTOCOL_VIOLATION` from `result.killCause() == LINE_LIMIT_EXCEEDED` **only on a returned `ShellCommandResult`**. A `ShellExecutionException` becomes `SCRIPT_ERROR` ("the shell failed"). Today `SandboxShell` throws `wrapperFailed(...)` ("exit 137") when a stream run has no trailer. | Core `classify(...)`; `SandboxShell.classifyStream`. | S8 is not only an `ExecOutcome` field: `SandboxShell` must *return* a result for that case (§5.4). |
| F8 | Core's own hook runner uses `hook(true).isolated(isolated)` (`ShellActionRunner`). `SandboxShell.plan` picks `Mode.ISOLATED` whenever `isIsolated()`, hook or not. | Core + this repo. | "Isolated" alone does not identify the engine; S8's result shape must exclude hooks explicitly (§5.4). |
| F9 | The `at.aimon.sandbox.provider` package imports **no** core type today. | `grep`. | S8's provider-side field gets its own enum rather than core's `ShellKillCause` (§2, alt. A4). |
| F10 | The local testkit provider virtualises `/workspace` only in command text, environment values, output and files-API paths (`LocalConnection`). Core's Python runtime reads the reply directory path from a **run-directory file** (`reply_dir`, content `"/workspace/repo/.aimon-ptc/<hex>"`) and opens it verbatim. On the host that path does not exist. | `LocalConnection`, `aimon_ptc.py`, `ShellCodeExecutionEngine.execute`. | The engine contract suite cannot run unmodified over `LocalProcessSandboxProvider` (§5.7 for the workaround and the record). |
| F11 | Profiles are built in Java (`SandboxProfile.builder()`); this repo has no property binding. `contentHash()` is used only to decide whether a permanently FAILED slot is retried. | `SandboxProfile`, `SandboxWorkspaceManager:461`. | S3's declaration is a builder method; adding it to the hash only affects FAILED slots. |
| F12 | The uploaded run-files path keeps file bytes out of every argv: the wrapper does `IFS= read -r b <&4 && printf '%s' "$b" \| /usr/bin/base64 -d` (`printf` is a builtin). The inline path puts the base64 into the `bash -c` script, i.e. execd's command argv → `/proc/<pid>/cmdline`. | `ShellWrapper` run-files stage. | S4 is a pure planning change: secret ⇒ upload (§5.2). |
| F13 | Core's runtime imports at top level: `ast asyncio base64 hashlib hmac inspect json keyword os shutil signal stat sys tempfile threading time traceback`; `ctypes` only inside a guarded `try`. Debian's `python3-minimal` lacks several of these. | `aimon_ptc.py`. | The seed check imports exactly that list (§5.1). The docker image installs `python3`, not `python3-minimal`. |

---

## 1. Problem (restated)

aimon-core 0.3.4-SNAPSHOT ships the core half of `ExecuteCode`'s Python **shell engine**: it runs `python3` inside a
sandboxed execution environment over an isolated streamed shell call, places runtime/code/HMAC key through a
`RunDirectory` (the key marked *secret*), and answers the runtime's tool calls with HMAC'd reply files written through a
new framework-only `RuntimeFileSystem`. It offers itself only on an environment whose descriptor declares
`isolation() = SANDBOXED` and the `python3` runtime, that hands out a `runtimeFileSystem()`, and whose shell declares
`STREAMING`/`RUN_DIRECTORY`/`ISOLATED_EXECUTION`; it treats a shell-ended command
(`ShellCommandResult.killCause() == LINE_LIMIT_EXCEEDED`) as `PROTOCOL_VIOLATION`. aimon-sandbox must supply the
provider half so the engine actually becomes available on a workspace sandbox and behaves safely there: declare `python3`
as an optional, seed-verified part of the image contract (S3); never inline a secret run file into a process argument
(S4); implement `RuntimeFileSystem` over the provider SPI with atomic create-only writes and no-follow bounded reads
(S5); declare `SANDBOXED` and the profile's runtimes on the descriptor (S6); and carry "the provider ended this command
because a line exceeded its cap" from the provider through `ExecOutcome` onto `ShellCommandResult.killCause()` (S8). It
must also absorb core's breaking changes since the previous snapshot, run core's engine contract suite on this repo's
providers as far as possible without core changes, and update docs/CHANGELOG.

---

## 2. Approach and rejected alternatives

**Chosen approach, in one line per item:**

- **S3** — `SandboxProfile.runtimes()` (a set; only `python3` accepted), in the profile hash when non-empty; the seed
  script appends a `runtime-python3` check (presence, ≥ 3.8, `-I` imports of the runtime's stdlib modules) as a
  permanent failure; image contract text in `workspace-sandbox.md` §13.3.
- **S4** — in `SandboxShell.plan`, a `RunDirectory` with any secret path is uploaded whole (`filesUploaded = true`
  from the start); nothing else changes.
- **S5** — new package-private `SandboxRuntimeFileSystem` in `at.aimon.sandbox.environment`, provider-neutral over
  `SandboxConnection` (`files()` + `run(...)`), lazily connected like `SandboxFileSystem`, confined to `/workspace/**`,
  mapping failures onto the `java.nio.file` exceptions the interface names. `read` is one in-sandbox helper exec
  (`[ -L ]`/`[ -f ]` refusal, `head -c`, base64 out, `transientOutput(true)`).
- **S6** — `SandboxExecutionEnvironmentProvider.descriptor(...)` adds `isolation(SANDBOXED)` and
  `runtimes(profile.runtimes())`; `SandboxExecutionEnvironment.runtimeFileSystem()` returns it always.
- **S8** — new provider-SPI enum `ExecKillCause { LINE_LIMIT_EXCEEDED }` and `ExecOutcome.killCause()`; the
  OpenSandbox provider sets it when `SseEvents` throws `LineTooLongException`; `SandboxShell` maps it to
  `ShellKillCause` and, **for an isolated non-hook stream run**, returns `ShellCommandResult(...).withKillCause(...)`
  instead of throwing; other stream runs keep throwing, with the cause named in the message.
- **S9 follow-up** (forced by F5) — the deny rule also covers `<binding root>/.aimon-ptc`.
- **Engine contract suite** — run on the docker tier unmodified (real execd, real `/workspace`, image with
  `python3`); run on the testkit tier through a *test-only* environment decorator that rewrites the one
  run-directory file whose content is an absolute `/workspace` path (F10).
- **Core compat** — fix the one test (`origin(ToolCallOrigin…)`), update the version-catalog comment and CHANGELOG
  header. One PR.

**Rejected alternatives:**

| # | Alternative | Why rejected |
|---|---|---|
| A1 | **S4: upload only the secret files**, inline the rest. | Uploads are already two files (manifest + blob) regardless of count; splitting adds a third upload and another wrapper stage for no gain. The memo §7 reached the same conclusion. |
| A2 | **S4: keep inlining but pass the secret via environment variable / stdin.** | Environment values are inlined into the script too (`ShellWrapper.inlineSize`), so they reach argv; the stream path's stdin is also inlined below the budget. Upload is the one existing path with no argv exposure (F12). |
| A3 | **S5 `read`: `SandboxFiles.stat` + `SandboxFiles.read`.** | execd's `/files/download` follows links (`os.Open`) and `/files/info` is not known to be `lstat`; the files API cannot express "don't follow". Also `FileStat` has no link flag. |
| A3' | **S5 `read`: `python3`/`dd` with `O_NOFOLLOW`.** | `python3` is optional in the image contract and `dd` has no portable `nofollow`; the runtime FS must work on every image (the probe runs before we know python exists). |
| A3'' | **S5 `read`: add a `SandboxFiles.readNoFollow` SPI method.** | Every provider would implement it with the same in-sandbox helper; the provider SPI grows for an operation no caller in core uses (F6). Revisit if a provider can do it natively. |
| A4 | **S8: put core's `ShellKillCause` directly on `ExecOutcome`.** | The provider SPI package is core-free today (F9) and also serves non-shell execs (seed, content search). A local enum keeps provider implementers off core's shell vocabulary; the mapping is one exhaustive switch. |
| A5 | **S8: return a result-with-killCause for every stream run** (hooks, monitors too). | A hook that floods a line would come back as exit 137 instead of an exception. Core's hook runner treats a non-0/non-2 exit differently from a failed execution (fail-open/closed handling), so a flooded guard hook could let a tool through. Scope it to the run that needs it (isolated, not hook). Listed as Q4 for the general case. |
| A6 | **S8: keep throwing, put the cause in the exception.** | Core classifies only from a returned result (F7); an exception becomes `SCRIPT_ERROR`, which is the bug S8 exists to fix. |
| A7 | **S5 confinement to `<root>/.aimon-ptc/**`.** | Couples this repo to core's package-private constant `PTC_DIRECTORY`; a core rename would silently break the engine here. `/workspace/**` is the mount contract and the only tree the testkit translates. |
| A8 | **S3: accept any runtime name** and pass it through to the descriptor. | The descriptor would promise a capability the seed cannot verify. Only `python3` has a check; anything else is a configuration error (`SandboxConfigurationException`) until a check exists. |
| A9 | **S3/S6: derive runtimes from the seed result** (what the image actually has). | The descriptor must be known at offer time without provisioning (shell doc §4); seed results don't exist then. Declared value + seed verification + core's probe as the second line. |
| A10 | **Testkit engine suite via a `python3` shim in `LocalProcessSandboxProvider`** that rewrites `$AIMON_RUN_DIR/reply_dir`. | Puts knowledge of core's runtime internals into the *published* testkit and changes behaviour for every testkit user. The test-only decorator (§5.7) keeps that coupling inside one test class, and it fails loudly if core renames the file. |
| A11 | **Testkit engine suite: skip, docker tier only.** | Shell doc §14 asks for both tiers; the decorator gets the testkit tier for a few dozen lines of test code. Docker remains the authoritative tier. |
| A12 | **Split core-compat into its own PR first.** | It is one test line (F2); the task says split only if large. |
| A13 | **Seed sweeps `.aimon-ptc`** (memo Q3). | Not in this task's scope; with shared volumes the seed of one slot could delete another slot's live run. Core's probe already sweeps per environment object. Stays open (Q3). |

---

## 3. Concrete changes by file

Paths relative to `modules/` unless noted.

### 3.1 Build / core compat

- `gradle/libs.versions.toml` (repo root): add
  `aimon-code-engine-testkit = { module = "at.aimon.core:aimon-code-engine-testkit", version.ref = "aimon-core" }`.
  Extend the `0.3.4-SNAPSHOT` comment with the PTC D1 additions this repo now implements (`RuntimeFileSystem`,
  `EnvironmentDescriptor.isolation()/runtimes()`, `RunDirectory` secret mark, `ShellKillCause`) and the testkit entry.
  Junit versions match core's (`6.1.3` both) — no platform conflict.
- `aimon-sandbox/build.gradle.kts` and `aimon-sandbox-opensandbox/build.gradle.kts`:
  `testImplementation(libs.aimon.code.engine.testkit)`.
- `aimon-sandbox/src/test/.../environment/OrcaRuntimeSandboxE2ETest.java:389`: add `.origin(...)` to the
  `PreToolContext` builder (use the `ToolCallOrigin` factory for a direct main-agent call; the implementer picks the
  exact factory from core 0.3.4's `ToolCallOrigin`). After this, run the whole `checkAll` again — F2 found only this one.

### 3.2 S3 — profile declaration and seed check

- `aimon-sandbox/src/main/java/at/aimon/sandbox/profile/SandboxProfile.java`
  - field `Set<String> runtimes` (unmodifiable, sorted), `public Set<String> runtimes()`, `Builder.runtimes(Set<String>)`
    (null → empty).
  - Validation at `build()`: every name ∈ `{EnvironmentDescriptor.RUNTIME_PYTHON3}`; otherwise
    `IllegalArgumentException` naming the supported set (consistent with the builder's other checks — implementer
    follows whatever the builder already does for invalid values, e.g. `insecureAllow ⊄ WAIVABLE`).
  - `contentHash()`: append `"runtimes=" + runtimes` **only when non-empty**, so every existing profile keeps its hash
    (F11: a hash change only re-tries FAILED slots, but there is no reason to cause that wave).
  - `toString()` includes runtimes.
- `aimon-sandbox/src/main/java/at/aimon/sandbox/workspace/SandboxSeeder.java`
  - after the OS/platform checks, before `rm -rf /workspace/.tmp-*`: when `profile.runtimes()` contains `python3`,
    append the check (still under the seed lock; it reads nothing in `/workspace`):

    ```sh
    command -v python3 >/dev/null 2>&1 || fail runtime-python3 'the profile declares python3 but the image lacks it'
    python3 -I -c 'import sys
    if sys.version_info < (3, 8): sys.exit(3)
    import ast, asyncio, base64, hashlib, hmac, inspect, json, keyword, os, shutil, signal, stat, tempfile, threading, time, traceback' >/dev/null 2>&1; r=$?
    [ "$r" = 3 ] && fail runtime-python3 "python3 is older than 3.8 ($(python3 -I -V 2>&1))"
    [ "$r" = 0 ] || fail runtime-python3 'python3 -I cannot import the standard library modules the ExecuteCode runtime needs'
    ```

    (No `TMPDIR` writability check — see Q6.)

    Version check first, imports second (a 3.6 may lack nothing yet fail differently). The module list is a constant
    in `SandboxSeeder` with a comment pointing at `aimon_ptc.py` (F13) — it must follow core.
  - Extract the fragment into `static String runtimeChecks(SandboxProfile)` so a test can run it alone (§6).
  - Class javadoc: add the bullet.
- Startup validation: none needed beyond the builder (the provider advertises nothing about runtimes).

### 3.3 S4 — secret run files are never inlined

- `aimon-sandbox/src/main/java/at/aimon/sandbox/environment/SandboxShell.java`, `plan(...)`:

  ```java
  final boolean secret = runDirectory.isPresent() && !runDirectory.get().getSecretPaths().isEmpty();
  ...
  filesUploaded = secret || inlineBytes > ShellWrapper.INLINE_RUN_FILES_LIMIT;
  ```

  The later "still over budget → upload" fallback is unchanged. Javadoc of `plan` and of the class paragraph at line ~91
  ("inline in the script when they fit") gain "…and never when a file is marked secret".
- No `ShellWrapper` change: the uploaded path already deletes `"$r.manifest" "$r.blob"` right after copying and the
  private `.blob` after unpacking (memo §2.4), and keeps bytes out of argv (F12).
- `docs`: memo §7 becomes "implemented".

### 3.4 S5 — `SandboxRuntimeFileSystem`

New file `aimon-sandbox/src/main/java/at/aimon/sandbox/environment/SandboxRuntimeFileSystem.java`
(package-private, final, thread-safe, no mutable state):

```java
final class SandboxRuntimeFileSystem implements RuntimeFileSystem {
    SandboxRuntimeFileSystem(SandboxBinding binding, SandboxWorkspaceManager manager, PendingNotices pending)
    public void writeNew(String path, byte[] bytes) throws IOException
    public byte[] read(String path, int maxBytes) throws IOException
    public void delete(String path) throws IOException
    public void createDirectories(String path) throws IOException
}
```

- **Connect per call** like `SandboxFileSystem.connect()`: `pending.connect(manager, binding)`,
  `slot.activity().record(false)` — but **inside** the method's exception mapping: a connect that throws
  `SandboxUnavailableException` (lost sandbox, slot FAILED, e.g. on the new `runtime-python3` seed step) or any other
  `RuntimeException` becomes `IOException(message, cause)`. The interface is `IOException`-only, and core relies on
  it: `ShellCodeExecutionEngine.execute`'s `finally { deleteQuietly(files, runDirectory) }` catches only
  `IOException`, so a runtime exception there would replace the engine's classified outcome. Construction does nothing remote (offer time must not provision — shell doc §4;
  core's `OfferFacts` only asks `runtimeFileSystem().isPresent()`).
- **Path check first** (no provider call on refusal) → `IOException("… is not served by the sandbox runtime file
  system")`: non-null, absolute, no NUL, starts with `/workspace/` (exactly `/workspace` refused for
  `writeNew`/`delete`), no empty, `.` or `..` segment, no trailing `/` except as normalised away. No normalisation —
  refuse rather than reinterpret.
- **`writeNew`** → `connection.files().write(path, new ByteArrayInputStream(bytes), bytes.length, CREATE_NEW)`.
  Both providers already do "temp name in the target directory + link(2)" (`OpenSandboxFiles.write`,
  `LocalSandboxFiles.write`), which is atomic and refuses an existing file or link (dangling included).
  Exception mapping:
  - core `FileAlreadyExistsException` → `java.nio.file.FileAlreadyExistsException(path)`.
  - core `VirtualFileSystemException` (both providers throw this for a **directory** at the target: "Is a
    directory") → `stat(path)`; present → `java.nio.file.FileAlreadyExistsException`, else `IOException(cause)`.
    The interface says *anything* existing is `FileAlreadyExistsException`.
  - `SandboxNotFoundException` / `SandboxProviderException` / `SandboxUnavailableException` → `IOException` (after the
    same "mark lost" side effect `ProviderCalls.guarded` performs — reuse it, then catch its
    `SandboxUnavailableException`).
  - Missing parent: both providers create parents. The interface says "the parent directory must exist" as a
    precondition; creating it is lenient and harmless. Not pinned by the contract test.
- **`read(path, maxBytes)`** — `maxBytes < 0` → `IllegalArgumentException`; `maxBytes` above
  `MAX_READ = 16 MiB` → `IOException` (refused, not clamped — clamping would break "all of it when smaller"; keeps
  the capture cap representable). `0` → the helper still runs (to give `NoSuchFile`/link errors) and returns empty.
  One exec through `connection.run(...)`:

  ```sh
  set -o pipefail
  set -- '<path>' '<maxBytes>'
  [ -L "$1" ] && exit 40          # a link: refused, not followed
  [ -e "$1" ] || exit 44          # nothing there
  [ -f "$1" ] || exit 41          # not a regular file
  head -c "$2" -- "$1" | /usr/bin/base64 || exit 42   # pipefail: an unreadable file (EACCES, EIO) is exit 42, not ""
  ```

  `/usr/bin/base64` by absolute path: the seed requires it to be GNU, which wraps at 76 columns, so no output line
  approaches the S2 256 KiB cap (a non-wrapping `base64` earlier on `PATH` would emit one huge line for files over
  ~192 KiB). On the testkit tier the helper runs on the host bash, where `/usr/bin/base64` exists on Linux and macOS.

  `ExecSpec`: working directory `/`, timeout 2 min (same as `OpenSandboxFiles.HELPER_TIMEOUT`), `transientOutput(true)`
  (its stdout is file content — S1 applies), `maxCaptureBytes = 4·⌈maxBytes/3⌉ + ⌈that/57⌉ + 1 KiB`
  (base64 + GNU's line breaks + slack). Exit → `40,41,42`: `IOException`; `44`: `NoSuchFileException`; truncated
  capture: `IOException` (cannot happen with the computed cap; defensive). Decode with `Base64.getMimeDecoder()`
  (tolerates GNU's 76-column wrapping and macOS's unwrapped output — the testkit runs the helper on the host). Output
  is ASCII, so the provider's line normalisation (`ExecOutcome` doc) cannot corrupt it. Quoting via the existing
  single-quote helper (`ShellWrapper.quote`).
  - **Known limit, documented in javadoc:** the `-L` check and the `head` open are two steps (TOCTOU). A same-uid
    process that swaps the path between them makes the host read *what the sandbox user can read anyway* — the helper
    runs as that user inside the sandbox — so the residual is confidentiality within the sandbox, not escape. Core does
    not call `read` today (F6).
- **`delete(path)`** → one in-sandbox helper, not the files API: `rm -rf -- "$1"` then `[ -e "$1" ] || [ -L "$1" ]`
  → exit 43 if anything remains. `rm -rf` never follows a final-component link (removes the link), handles a
  **dangling** link, a file, a tree and nothing alike, and the post-check gives "something remains → `IOException`".
  Chosen over `stat` + `files().delete(path, true)` because whether `/files/info` (and the testkit's `stat`) follows
  the final link is unverified — if it does, a dangling link reads as absent and stays, silently breaking the
  contract (this replaces the former O5 fallback with the primary path). Cost: one exec per delete; core deletes once
  per probe and once per run. Not `transientOutput` (no output).
- **`createDirectories(path)`** → `files().createDirectories(path)`; core `FileAlreadyExistsException` (something not
  a directory in the way — `OpenSandboxFiles:277`) → `IOException` (plain, not the nio subtype — the interface says
  `IOException`); others as above.
- Spool hygiene: `writeNew` runs `OpenSandboxFiles`'s `ln` helper, which on execd `v1.1.0` leaves an (empty) spool pair
  in `/tmp` per reply, as every model `Write` already does. Not made transient (would add one HTTP call per reply to an
  already 2-call path, shell doc §9). Recorded in the memo as an accepted cost.

- `SandboxExecutionEnvironment.java`: construct it next to `fileSystem`; override
  `public Optional<RuntimeFileSystem> runtimeFileSystem() { return Optional.of(runtimeFileSystem); }` with javadoc
  "framework-only, outside the path rules, never handed to a model tool; confined to `/workspace`".

### 3.5 S9 follow-up — deny `.aimon-ptc` under the binding root

- `SandboxFileSystem.java`: `RULES` stays (the `/workspace` level), and the view adds
  `PathRule.deny(rootRelative + "/.aimon-ptc")` when the binding root is not `/workspace`. Build the list once per
  `SandboxFileSystem` instance (`rules(binding)`), not per call. Comment cites F5 (core places it under the working
  directory).
- Grep still sees it (memo Q4, unchanged, now also for `/workspace/repo/.aimon-ptc`).

### 3.6 S6 — descriptor

- `SandboxExecutionEnvironmentProvider.descriptor(binding, profile)`:
  `.isolation(EnvironmentIsolation.SANDBOXED).runtimes(profile.runtimes())`. Javadoc and the class doc paragraph about
  "declared platform, OS and shell" extend to isolation and runtimes. Unconditional `SANDBOXED`: this environment's
  shell and files are always inside a sandbox.
- The unavailable path is core's (`EnvironmentDescriptor.unavailable(cause)` / `UnavailableExecutionEnvironment`) and
  declares no isolation — nothing to do; a test pins it.
- `ShellFeature` declarations (`STREAMING`, `RUN_DIRECTORY`, `ISOLATED_EXECUTION`) already exist.

### 3.7 S8 — kill cause from provider to shell result

- New `aimon-sandbox/src/main/java/at/aimon/sandbox/provider/ExecKillCause.java`:

  ```java
  /** Why a provider ended a command itself (not a timeout, not a kill() call). */
  public enum ExecKillCause {
      /** One line of output exceeded the provider's line limit, so the provider ended the command before holding it.
       *  Both streams are reported truncated. */
      LINE_LIMIT_EXCEEDED
  }
  ```

- `ExecOutcome`: field + `public Optional<ExecKillCause> killCause()`, `Builder.killCause(ExecKillCause)` (default
  none), in `toString`. Class doc: a provider that sets it also sets both truncation flags; `timedOut` and `killCause`
  are never both set (whichever ended the command first). Additive — every existing provider and test compiles.
- `aimon-sandbox-opensandbox/.../OpenSandboxRunningCommand.java`: in the `LineTooLongException` branch record
  `killCause = LINE_LIMIT_EXCEEDED` (a field the outcome builder reads), next to the two `truncate()` calls. Not set if
  the line arrives after the command already ended (exit known) — then nothing was killed.
- `SandboxShell`:
  - `classifyStream(...)`: after `completed(...)` and the tamper check, **before** the clock-based timeout heuristic
    (`run.commandTimeout && exit == 137 && duration ≥ timeout` would otherwise claim a line kill that landed after the
    deadline):

    ```java
    if (outcome.killCause().isPresent()) {
        final ShellKillCause cause = toShellKillCause(outcome.killCause().get());   // exhaustive switch
        if (run.options.isIsolated() && !run.options.isHook()) {
            return new ShellCommandResult(outcome.exitCode(), stdout(outcome, run), stderrWithoutTrailer(outcome, run),
                    duration, true).withKillCause(cause);
        }
        throw wrapperFailed(outcome, run, "the provider ended the command: " + describe(cause));
    }
    ```

    (stdout/stderr capped exactly as the `completed` stream path caps them — reuse its helpers.)
  - `completed(...)` (trailer present) for a stream run: if `outcome.killCause()` is present, attach it too (same
    isolated-non-hook condition). Rationale: output after the cut is lost even though the wrapper finished, so the
    caller must not read the stream as complete. For other modes the trailer's result stands (unchanged behaviour).
  - Non-stream `classify(...)`: the exec's own output carries only the trailer, so a line kill there is not expected;
    if it happens, `wrapperFailed` names the cause (diagnostic only, no shape change).
  - `wrapperFailed` gets an overload with a reason prefix; message for hooks becomes e.g.
    `"the sandbox shell wrapper failed (exit 137): the provider ended the command: an output line exceeded the
    provider's line limit"`.
- `SandboxContentSearch` (`rg --json`): unchanged behaviour (it already fails and core falls back); optionally log the
  cause — implementer's call, not required.

### 3.8 Image contract & docker tier image

- `aimon-sandbox-opensandbox/src/test/docker/Dockerfile`: add `python3` to the `apt-get install` line; comment that it is
  the optional `python3` item of §13.3 so the tier can run the shell engine.
- The K8s tier's default image (`ghcr.io/kangwoo/aimon-sandbox-runtime:1`, `OpenSandboxK8sIT`) is not changed and its
  profile declares no runtimes; the memo says so explicitly, so nobody assumes the shell engine is exercised there.
- `docs/design/workspace-sandbox.md` §13.3: add the optional `python3` item (memo §6.1 text, updated with F13's module
  list and the `TMPDIR`/`/tmp` writability sentence; "execd runs without `TMPDIR`" sentence kept), §13.1 profile table
  gets `runtimes`, §11.3 seed list gets `runtime-python3`.
- `docs/guide/user-guide.md`: profile field table row `runtimes` (`[]`; only `python3`; enables core's Python
  `ExecuteCode` engine when the app enables programmatic tools; seed-verified, permanent failure otherwise) and a short
  "Python `ExecuteCode`" note (agent needs unconstrained `Bash`; same-uid limits — link core shell doc §12).

### 3.9 Docs and CHANGELOG

- `docs/design/ptc-shell-prerequisites.md`: status line → S1–S9 implemented (S8 provider side); §6–§9 rewritten from
  "direction" to "implemented" with the decisions above; new short subsections for F5 (root ≠ `/workspace`, S9
  extension), F4 (no core RuntimeFileSystem contract suite — our own), F10 (testkit tier needs the decorator), and §14
  status (engine suite on both tiers, cmdline check). Q2 closed (answered by core's `ShellKillCause` + this design);
  Q1, Q3, Q4 stay; new questions from §8 here added.
- `CHANGELOG.md` `[Unreleased]`: header "Requires aimon-core 0.3.4" (currently says 0.3.3 — note snapshot until
  released); new "Added: the sandbox half of core's Python `ExecuteCode` shell engine (D2)" with S3/S4/S5/S6/S8/S9
  bullets and **behaviour changes**: every run with a secret file now uploads (two extra uploads); an isolated
  non-hook streamed call whose line exceeds 256 KiB now returns a result with exit 137 and `killCause` instead of
  throwing; descriptor now declares `SANDBOXED`; new public SPI `ExecKillCause`/`ExecOutcome.killCause()`; new profile
  field.
- `modules/aimon-sandbox/README.md`, `modules/aimon-sandbox-testkit/README.md`: one line each if they list
  environment capabilities / contracts (testkit: the new `RuntimeFileSystemContract`).

### 3.10 Testkit (published) — new contract

- New `aimon-sandbox-testkit/src/main/java/at/aimon/sandbox/testkit/RuntimeFileSystemContract.java` (abstract, JUnit,
  like `SandboxProviderContract`): subclasses supply `ExecutionEnvironment environment()` (a sandbox environment whose
  `runtimeFileSystem()` is present) and run its tests (§6.2). Published because the docker tier and any third-party
  provider run it.

---

## 4. Data and interface shapes that change

| Type | Change | Compat |
|---|---|---|
| `at.aimon.sandbox.provider.ExecKillCause` (public enum) | **new**: `LINE_LIMIT_EXCEEDED` | additive SPI |
| `at.aimon.sandbox.provider.ExecOutcome` | `Optional<ExecKillCause> killCause()`, `Builder.killCause(ExecKillCause)` | additive; default empty |
| `at.aimon.sandbox.profile.SandboxProfile` | `Set<String> runtimes()`, `Builder.runtimes(Set<String>)`; `contentHash()` includes runtimes when non-empty | additive; hashes of existing profiles unchanged |
| `SandboxExecutionEnvironment` | overrides `runtimeFileSystem()` → present | core default was empty |
| `SandboxRuntimeFileSystem` (package-private) | `read`/`delete` are in-sandbox helper execs; `writeNew`/`createDirectories` use the files API; every failure, connect included, is an `IOException` | new |
| `EnvironmentDescriptor` produced by this repo | `isolation = SANDBOXED`, `runtimes = profile.runtimes()` | consumers see new values |
| `SandboxShell` result for isolated non-hook stream runs ended by the provider's line cap | was `ShellExecutionException("…wrapper failed (exit 137)…")`; now `ShellCommandResult{exit=137, outputTruncated=true, killCause=LINE_LIMIT_EXCEEDED}` | **behaviour change** (CHANGELOG) |
| Hook/monitor stream runs ended by the line cap | still `ShellExecutionException`; message names the cause | message text only |
| `SandboxShell` run with any secret run file | always uploaded (manifest + blob) | behaviour change, invisible except latency and the "upload failed" error path |
| `SandboxFileSystem` path rules | + `deny(<root>/.aimon-ptc)` when root ≠ `/workspace` | model tools can no longer see `/workspace/repo/.aimon-ptc` |
| `RuntimeFileSystemContract` (testkit, public abstract) | **new** | additive |
| Seed failure step | **new** step name `runtime-python3` (permanent) | additive |

---

## 5. Details worth spelling out

### 5.1 Seed check and the probe (two lines of defence)

- Seed: once per sandbox creation, only when declared; FAILED/permanent (`SandboxSeeder.FAIL_EXIT`) like the other image
  checks — retrying the same image cannot help, and the profile hash changes when the operator fixes the declaration.
- Core probe: once per environment object, always (`python3` ≥ 3.8, reply-file visibility). It catches what the seed
  cannot: a slot provisioned **before** the profile declared `python3` (the descriptor reads the profile, the seed never
  ran the check) and an image whose `python3` was removed at runtime. Its failure makes the engine unavailable for that
  environment object only — acceptable, documented.

### 5.2 Secret marking and latency

Core marks `key` secret on **every** `ExecuteCode` run, so every run takes the upload path: two uploads (HTTP) before
the exec. That is added to shell doc §9's per-run cost, not per tool call. Accepted; mention in the memo. The window
between upload and the wrapper's `rm` is shell doc §15 Q2 (unchanged).

### 5.3 Where `.aimon-ptc` lives

With the default root `/workspace/repo`, the engine's directory is `/workspace/repo/.aimon-ptc`. Consequences handled:
the deny rule (§3.5); the runtime FS confinement (`/workspace/**`) covers it; the probe's `find -mmin` sweep is core's.
Consequence **not** handled here: it sits inside the model's working tree, so a `git status`/`git add -A` the model runs
*during* a run sees reply files (run directories are deleted at run end; the empty `.aimon-ptc/` itself is invisible to
git). This is a core placement decision → Q-C1. Not mitigated with `.git/info/exclude` at seed time: the seed does not
own the repository (git seeds arrive with step 5; today the model may `git init`/clone into the root after the seed
ran, so there is no `.git` to write into at seed time), and writing into the model's repository metadata is a
behaviour the model would not expect. Revisit with step 5's git seed.

### 5.4 S8 decision table (stream runs)

| Trailer? | `killCause` | Isolated ∧ ¬hook | Result |
|---|---|---|---|
| yes | none | any | trailer's result (unchanged) |
| yes | present | yes | trailer's result `.withKillCause(cause)` |
| yes | present | no | trailer's result (unchanged) |
| no | present | yes | `ShellCommandResult(137…, truncated).withKillCause(cause)` |
| no | present | no | `ShellExecutionException`, message names cause |
| no | none | any | existing order: tampered → timeout heuristic → `wrapperFailed` (unchanged) |

Timeout vs kill cause: the provider sets at most one; `killCause` is checked before the clock heuristic. Cancellation is
decided before classification (unchanged). `RUN_FILES_TAMPERED_EXIT` stays first (the wrapper exits before the command
starts, so it cannot coincide with a line kill).

### 5.5 Thread-safety and notices of the runtime FS

`ShellProgramRun` writes replies from executor threads concurrently. `SandboxRuntimeFileSystem` holds only final
references; per-call `pending.connect` is what `SandboxFileSystem` already does under concurrent tool calls. Notices a
connect produces (reset, re-creation) queue on the environment's `PendingNotices` and drain after `ExecuteCode`'s
result — the same as for the shell call that runs the program.

### 5.6 Failure modes

| Failure | Handling |
|---|---|
| Sandbox gone during `writeNew`/`delete`/… | `ProviderCalls.guarded` marks the slot lost; we rethrow as `IOException("…lost…")`. Engine: `writeNew` failure → host failure → `SCRIPT_ERROR`; the shell call itself fails on its own. |
| Connect fails (slot FAILED, provider down, lost) before the operation | `SandboxUnavailableException`/`RuntimeException` from `pending.connect` mapped to `IOException` (§3.4), so the engine's `deleteQuietly` in `finally` cannot turn a classified outcome into an exception. |
| Binding root outside `/workspace` | Static availability says available (descriptor SANDBOXED + python3), but every probe `writeNew` is refused by the confinement → probe unavailable "a file could not be written through the runtime file system". Harmless, documented in `SandboxRuntimeFileSystem` javadoc and the memo; roots outside `/workspace` are not a supported configuration of the image contract anyway. |
| Reply path pre-empted by a co-resident process (file, dir, link) | `java.nio.file.FileAlreadyExistsException` → core treats as protocol violation (its `ShellProgramRun` branch). Directory case mapped via `stat` (§3.4). |
| `writeNew` upload succeeds, `ln` helper fails for another reason | provider removes the temp file (both providers do); `IOException`. Nothing left at the path (interface contract). |
| `read` on a link / non-regular / missing | `IOException` / `IOException` / `NoSuchFileException`. TOCTOU documented (§3.4). |
| `read` helper times out or provider error | `IOException`. |
| Path outside `/workspace`, relative, `..`, NUL | `IOException` before any provider call. |
| Profile declares an unknown runtime | `IllegalArgumentException` at `SandboxProfile.build()` (startup). |
| Profile declares `python3`, image lacks it / too old / stripped stdlib | seed FAILED `runtime-python3` (permanent); first shell/file call reports it like other image-contract failures. |
| Profile declares `python3` after a slot was seeded | core probe → engine unavailable for that environment object (§5.1). |
| Upload of secret run files fails | `ShellExecutionException("could not pass the hook files into the sandbox…")` — the existing path, now also hit by small secret sets. Engine → `SCRIPT_ERROR`. |
| Line over 256 KiB in the engine's runtime | provider kills, `killCause` → result → engine `PROTOCOL_VIOLATION`, engine disabled for that environment object (core decision 7). |
| Line over 256 KiB in a hook | exception as before, message now says why. |
| Spool of runtime-FS `ln` helpers on execd v1.1.0 | empty files accumulate in `/tmp` like every `Write`; accepted (§3.4). |
| `checkAll` red from another core break not seen in F2 | Fix in the same PR; F2 says there is exactly one. |

### 5.7 Engine contract suite (shell doc §14)

**Docker tier** — `aimon-sandbox-opensandbox/src/test/java/at/aimon/sandbox/opensandbox/ShellEngineContractIT.java`,
`@Tag("docker")`, `extends AbstractCodeExecutionEngineContractTest`:

- `engine()` → `ShellCodeExecutionEngine.create()`; `programs()` → `new PythonPrograms()`.
- `toolContext()` → `ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, env).build()` where `env` is a
  main-turn environment from a **class-static** `WorkspaceSandbox` assembly over `OpenSandboxProvider`, profile as in
  `OpenSandboxWorkspaceIT.profile()` plus `.runtimes(Set.of("python3"))`, one session for the whole class (each test
  re-provisioning would cost ~13 sandbox creations). Closed in `@AfterAll`.
- This exercises, for real: seed `runtime-python3`, isolated streamed execution with a secret run directory
  (S4 upload path), `SandboxRuntimeFileSystem` over execd (`writeNew` = upload + `ln`), S1 spool removal, `delete` of
  the run directory. It does **not** exercise the engine's offer-time availability or its probe — the suite calls
  `execute` directly. Those are §6.10's tests, which sit in the same two test classes' tiers.

**Testkit tier** — `aimon-sandbox/src/test/java/at/aimon/sandbox/environment/ShellEngineContractTest.java`,
`extends AbstractCodeExecutionEngineContractTest`, over `SandboxHarness` (`LocalProcessSandboxProvider`), profile with
`runtimes(python3)`. `@BeforeAll assumeTrue(python3 ≥ 3.8 runnable with -I)` — check with a real `python3 -I -c` on
`LocalProcessSandboxProvider.BASE_PATH`, not `command -v` (the macOS `/usr/bin/python3` CLT stub). Obstacle F10 is
handled by a **test-only decorator**:

```java
/** The local provider virtualises /workspace in command text, environment values and files-API paths, but not in
 *  run-directory file bytes. Core's runtime reads its reply directory from one such file ("reply_dir", an absolute
 *  /workspace path) — so this environment rewrites that one file to the host path before the shell places it. */
final class HostPathEnvironment implements ExecutionEnvironment { // delegates everything
    public VirtualShell shell() { return new RewritingShell(delegate.shell(), workspaceHostDir); }
}
```

`RewritingShell.execute(command, options, listener)` rebuilds `options.getRunDirectory()` with `reply_dir`'s content
`/workspace…` → `{sandboxDir}/workspace…`, preserving `variable`, every other file and **every secret mark**, then
delegates. Digests are computed by `SandboxShell` from the bytes it receives, so integrity checks still run for real.
If core stops writing `reply_dir`, the rewrite finds nothing and the suite fails (no reply arrives) — loud, in this
repo's CI, which is what we want. The decorator needs `ExecutionOptions.toBuilder()` or an equivalent copy; if 0.3.4 has
none, rebuild via `ExecutionOptions.builder()` copying every getter (implementer checks — open item O1).

**What could not be done without core changes** (record in the memo): running the suite over
`LocalProcessSandboxProvider` *unmodified*. Missing: either the runtime taking its reply directory from an environment
variable or the command (both of which the testkit translates), or a relative reply path resolved against
`$AIMON_RUN_DIR`'s parent. Nothing else in the suite needed core changes.

**§14 cmdline rule** (key never on a command line) — docker IT
`OpenSandboxWorkspaceIT.aSecretRunFileIsOnNoCommandLine`: build a `RunDirectory` with
`secretFile("key", random 32 bytes)` and a command that waits on a marker file; while it runs, a second exec greps
`/proc/*/cmdline` (NUL-separated → `tr '\0' '\n'`) for the key's base64 **and** hex → zero hits; release the marker.
Control in the same test, as a **separate run with no secret file**: a `RunDirectory` holding only a non-secret file
with a distinctive marker of the same size, which **is** found in that run's cmdline (proves the grep sees the inline
path — mirrors the S1 IT's control). It must be separate: under S4 a control file placed in the same `RunDirectory` as
the key would be uploaded with it, and the control would fail. Uses
`isolated(true)` + listener, i.e. the engine's call shape.

---

## 6. Test strategy

`checkAll` (unit tier, testkit provider) unless marked **[docker]** (CI `integration`, real execd `v1.1.0`).

### 6.1 Core compat
- `OrcaRuntimeSandboxE2ETest` green with `origin(...)`; full `checkAll` green.

### 6.2 S5 — `RuntimeFileSystemContract` (testkit) run on both tiers
Subclasses: `SandboxRuntimeFileSystemContractTest` (aimon-sandbox, `SandboxHarness`) and
**[docker]** `OpenSandboxRuntimeFileSystemIT` (opensandbox). Cases:
1. `writeNew` creates the file with its whole content; the directory then lists exactly that file (no `.aimon-tmp-*`
   / `.aimon-write-*` left).
2. `writeNew` on an existing file / directory / symlink / **dangling** symlink → `java.nio.file.FileAlreadyExistsException`;
   content/target unchanged.
3. 8 concurrent `writeNew`s of different contents to one path → exactly one succeeds, others
   `FileAlreadyExistsException`, file holds the winner's bytes.
4. `read` binary round-trip (bytes `0x00`, `\r`, `\r\n`, invalid UTF-8, 0xFF) — catches line normalisation.
5. `read` caps at `maxBytes`; smaller file returns all; `maxBytes = 0` → empty.
6. `read` of a symlink to a regular file → `IOException` (not `NoSuchFileException`, not content); of a directory →
   `IOException`; of nothing → `NoSuchFileException`.
7. `delete` of a file, of a tree, of nothing (no error), of a symlink → link gone, target intact; of a **dangling**
   symlink → link gone; of a symlink to a directory → link gone, directory and its contents intact.
7b. `read` of an unreadable regular file (`chmod 000`) → `IOException`, not an empty array (skipped when the sandbox
   user is root — the docker tier runs as root).
7c. `read` with `maxBytes` over the implementation ceiling → `IOException` before any exec.
7d. A runtime-FS call on an environment whose sandbox cannot be connected (slot FAILED / provider refuses create, via
   `FaultInjectingSandboxProvider`) → `IOException`, never a `RuntimeException` (unit tier).
8. `createDirectories` nested; existing dir OK; a regular file in the way → `IOException`.
9. Refused paths (`relative`, `/workspace/../etc/x`, `/etc/passwd`, `/workspace/a\0b`, `/workspace/./x`) →
   `IOException`, and (unit tier) the provider recorded no call (`RecordingProvider`).
10. Visibility: a `writeNew` file is seen by the environment's shell with `test -f` (the probe's condition).
11. Calling `runtimeFileSystem()` and `descriptor()` provisions nothing (provider create count 0) — unit tier.

### 6.3 S4
- Unit (`RecordingProvider`/`SandboxShellCaptureTest` style): run with a 32-byte secret file + small normal file →
  `ExecSpec.command()` contains neither the secret's base64 nor its hex; uploads recorded = manifest + blob; same run
  without the secret mark → inline (control).
- Upload failure with a small secret set → `ShellExecutionException` "could not pass…" (`FailingFiles`).
- **[docker]** `aSecretRunFileIsOnNoCommandLine` (§5.7).
- Existing `SandboxShellRunDirectoryIT` stays green (secret-less runs unchanged).

### 6.4 S3
- `SandboxProfileTest`: default empty; `python3` accepted; unknown name refused; hash unchanged for a profile without
  runtimes (assert against a hash computed from the pre-change canonical string, or two profiles equal-but-for-runtimes
  differ only when declared); `toString`.
- `SandboxSeederTest`: script contains the fragment iff declared.
- `SeedVerificationTest`-style host execution of `runtimeChecks(profile)` (prefixed by the `fail()` definition) with a
  temp `PATH` holding fake `python3` scripts: absent → `runtime-python3 … lacks it`; exits 3 → "older than 3.8";
  exits 1 → "cannot import"; exits 0 → passes. (No real python needed.)
- **[docker]** the engine IT's provisioning passes the real check (image has `python3`). A negative docker case is not
  added (would need a second image) — O3.

### 6.5 S6
- `SandboxEnvironmentProviderTest`: descriptor has `isolation() == SANDBOXED`, `runtimes()` mirrors the profile (empty and
  `{python3}`); a fork's descriptor the same; an unavailable environment's descriptor has no isolation.
- The engine contract suites do **not** cover availability: `AbstractCodeExecutionEngineContractTest` only calls
  `staticAvailability(OfferFacts.none())` (non-null) and `execute(...)`; it never builds offer facts from the
  environment and never calls `probe`. Availability is pinned separately in §6.10.

### 6.6 S8
- `OpenSandboxProviderTest` (fake server): a > 256 KiB event line → `ExecOutcome.killCause() == LINE_LIMIT_EXCEEDED`,
  both truncated, kill sent (extend the existing S2 test); a normal command → empty; `timedOut` and `killCause` not
  both set on a timeout.
- `SandboxShell` unit tests with a scripted provider (`DelegatingProvider`/`TrailerKillTest` pattern) returning
  `ExecOutcome{137, killCause, truncated, no trailer}`:
  - isolated + listener, not hook → result, `exitCode 137`, `outputTruncated`, `killCause LINE_LIMIT_EXCEEDED`;
  - hook + isolated → `ShellExecutionException` whose message names the line limit;
  - listener (background/monitor) → exception;
  - isolated with a timeout already passed (`duration ≥ timeout`) → still the kill-cause result, not a timeout;
  - trailer present + `killCause` on an isolated run → trailer's exit code with `killCause` attached.
- **[docker]** `OpenSandboxWorkspaceIT`: keep the existing hook huge-line assertion (exception, < 20 s); add the
  isolated non-hook + listener variant → returned result with `killCause()` present, < 20 s.
- No engine-level "flooded channel → `PROTOCOL_VIOLATION`" IT: model code cannot reach the channel fd (the runtime
  moves it away from fd 1), so the path is pinned at the `SandboxShell` boundary above and by core's own
  `ShellEngineProtocolTest`.

### 6.7 S9 extension
- `SandboxFileSystemTest`: with the default root, `/workspace/repo/.aimon-ptc/x` is invisible/refused to read, write and
  list; with root `/workspace`, unchanged; the shell still reads/writes it.

### 6.8 Engine contract suites
- Testkit: `ShellEngineContractTest` (all inherited tests pass; skipped only when no usable python3).
- **[docker]** `ShellEngineContractIT`.
- Both tiers count toward JaCoCo as today (docker tier's `integrationTest.exec` is already collected).

### 6.10 The engine becomes available on a workspace sandbox (offer facts + probe)

The task's purpose is that core's engine actually becomes available here; the contract suites skip that (§6.5). New
test `ShellEngineAvailabilityTest` (testkit tier, `SandboxHarness`, same `python3` assumption as §5.7) and
**[docker]** `ShellEngineAvailabilityIT` (opensandbox, `python3` image), each with:

1. **Static availability from the real environment, through core's own extraction.** Build the facts the way the loop
   does: `CodeOfferKeys.putEnvironmentFacts(ToolOfferContext.builder()…, toolContext)` with a `ToolContext` carrying
   the environment, then `CodeOfferKeys.factsOf(offer)`. Copy those facts into
   `OfferFacts.builder().descriptor(f.getDescriptor().orElseThrow()).shellFeatures(f.getShellFeatures())
   .runtimeFileSystem(f.hasRuntimeFileSystem()).bashUnconstrained(true)` — the Bash fact comes from the agent's
   allow-list and hooks, which are not this repo's concern. Assert
   `ShellCodeExecutionEngine.create().staticAvailability(facts).isAvailable()`. Also assert the facts individually
   (isolation `SANDBOXED`, runtimes ∋ `python3`, features ⊇ `STREAMING`/`RUN_DIRECTORY`/`ISOLATED_EXECUTION`,
   runtime FS present), so a failure names the missing fact. Building the facts must provision nothing (provider
   create count 0 afterwards, testkit tier).
2. **The same for a forked environment** (`SandboxExecutionEnvironmentProvider` fork path): available. This catches a
   descriptor path that drops `runtimes`/`isolation` for forks.
3. **Probe passes:** `engine.probe(env).isAvailable()`, the real thing — `createDirectories` + `writeNew` of the probe
   file through `SandboxRuntimeFileSystem`, the isolated non-listener exec (`command -v python3 … test -f … find -mmin
   … -exec rm`) through `SandboxShell`, then `delete`. Afterwards, the probe file is gone (`test -e` in the shell).
4. **Negative:** the same with a profile without `runtimes` → `staticAvailability` unavailable, reason contains "does
   not declare the python3 runtime".
5. **Negative (testkit tier only):** an unavailable environment (sandbox refused by binding policy, as existing tests
   do) → unavailable with the "not sandboxed" reason (its descriptor declares no isolation).

The probe on the testkit tier needs no `reply_dir` decorator (its paths are in the command text, which the testkit
translates). `ToolOfferContext.builder()`'s minimal required inputs are an implementation detail (O6).

### 6.9 Gates
- `./gradlew checkAll` green locally and in CI; CI `integration` green before merge (it is the only place execd runs).

---

## 7. Implementation order (suggested, one PR)

1. Core compat test fix + catalog/testkit dependency → `checkAll` green on unchanged behaviour.
2. S8 (SPI + provider + `SandboxShell`) with unit tests.
3. S4 with unit tests.
4. S5 `SandboxRuntimeFileSystem` + testkit contract + unit-tier subclass; S9 extension.
5. S3 profile + seed + S6 descriptor.
6. Availability tests (§6.10), engine contract suites (testkit decorator, docker IT), Dockerfile `python3`, docker
   ITs for S4/S8/S5.
7. Docs (memo, `workspace-sandbox.md`, user guide, CHANGELOG).

---

## 8. Open questions (not silently assumed)

**For the orchestrator / user (decide before or during build):**

- **Q1 (memo Q1, unchanged)** — execd `TMPDIR`. Still assumed `/tmp`. Not touched here.
- **Q3 (memo Q3)** — seed-side sweep of `.aimon-ptc`: left out (A13). Confirm out of scope.
- **Q4 (memo Q4, widened)** — `Grep` bypasses path rules via `contentSearch()`, now for both `/workspace/.aimon-ptc` and
  `<root>/.aimon-ptc`. Left as is; confirm.
- **Q5** — S8's result shape for **hooks and monitors**: this design keeps them on exceptions (A5) for hook fail-safety.
  Core's contract text ("say so in `killCause()`") would also allow results there. Confirm the narrower scope.
- **Q6** — should the seed also verify that `TMPDIR`/`/tmp` is writable by the sandbox user (the runtime's
  `mkdtemp`)? The image-contract text requires it; the seed doesn't check `/tmp` writability today for anything.
  Proposed: document only. Confirm.
- **Q7** — testkit tier via the test-only `reply_dir` decorator (§5.7) vs. docker-only plus a recorded gap. Design
  chooses the decorator; reviewers may prefer not to depend on a core runtime file name even in tests.

**For aimon-core (do not change core here; record in the memo):**

- **Q-C1** — the engine places `.aimon-ptc/` under the descriptor's working directory, which for a sandbox binding is
  the model's repository root (`/workspace/repo`). A descriptor-declared runtime directory (or using the parent of the
  working directory) would keep reply files out of the model's working tree. Not blocking.
- **Q-C2** — no published `RuntimeFileSystem` contract suite (F4); this repo's `RuntimeFileSystemContract` could move to
  core's testkit.
- **Q-C3** — the runtime reads its reply directory from a run-directory file as an absolute path (F10); passing it by
  environment variable would let virtualising shells (this repo's testkit) run the suite unmodified.

**Implementation-level items the builder must confirm in code (not design decisions):**

- **O1** — closed: `ExecutionOptions.toBuilder()` exists in 0.3.4 (`ExecutionOptions.java:294`); the decorator uses it.
- **O6** — what `ToolOfferContext.builder()` requires before `putEnvironmentFacts` can run in a test (§6.10).
- **O2** — the exact `ToolCallOrigin` factory for the `OrcaRuntimeSandboxE2ETest` fix.
- **O3** — no negative docker seed test (second image without python3) — acceptable given the host-run fragment tests?
- **O4** — whether `SandboxProfile.Builder` signals invalid values with `IllegalArgumentException` or
  `SandboxConfigurationException`; follow the existing convention.
- **O5** — closed: `delete` is an `rm -rf --` helper with a post-check (§3.4), so the files API's link behaviour no
  longer matters; contract case 7 pins links, dangling links and links to directories on both tiers.

---

## 9. Implementation departures

Status: **IMPLEMENTED** on branch `herdr/ptc-shell-provider-d2`. §0–§8 above are the design as approved (review 2 passed
it with non-blocking items); this section says where the code differs and why. The memo
[`ptc-shell-prerequisites.md`](ptc-shell-prerequisites.md) now records S3–S6 and S8 as implemented (§6–§9, §11, §12),
and the architecture document [`workspace-sandbox.md`](workspace-sandbox.md) has absorbed the change (§6.1, §7, §11.1,
§11.3, §13.1, §13.3). Its §20 carries the open questions of §8 that outlive this change — Q1, Q3, Q4, Q5, Q6, Q-C1,
Q-C2, Q-C3 (with Q7) and the new Q-C4 below (since closed) — each pointing back here.

### Where the code differs from the design

- **S3: an unknown runtime is refused at startup, not by the builder (O4).** `SandboxProfile` has no validation of its
  own; `insecureAllow ⊄ WAIVABLE` is refused by `SandboxStartupValidator`. Runtimes follow it: the builder accepts any
  set, `SandboxProfile.SUPPORTED_RUNTIMES` = `{python3}`, and startup reports "runtimes may only name [python3], not
  [...]". `toString` names runtimes only when declared (review 2).
- **S5 `writeNew`: "something is there" is asked in the sandbox, not through `files().stat`.** §3.4 maps a provider's
  `VirtualFileSystemException` to `FileAlreadyExistsException` when `stat(path)` finds something. On the testkit tier
  a dangling link never reaches link(2): `LocalConnection.toHost` refuses to resolve it (`InvalidPathException`), and
  its `stat` answers empty for a dangling link anyway — contract case 2 failed. The fallback is now one helper exec,
  `[ -e "$1" ] || [ -L "$1" ]`, which also does not depend on whether the files API's stat follows links. It runs only on
  the refusal path.
- **S5 `read(path, 0)` reads one byte to `/dev/null` and returns empty.** `head -c 0` is an error on BSD `head`
  ("illegal byte count"), and the testkit runs the helper on the host. The zero-byte read keeps the link, existence,
  regular-file and readability checks.
- **S5 confinement: a trailing `/` and an empty segment are refused; a `null` path is an `IOException`.** §3.4 left the
  trailing slash ambiguous ("except as normalised away"); refusing keeps "refuse rather than reinterpret". Core strips
  trailing slashes before it builds paths, so it never sends one.
- **S8 `ExecOutcome`: the builder refuses `timedOut` together with `killCause`.** §3.7 states the invariant as
  documentation; the code enforces it (`IllegalArgumentException`) so a provider that breaks it fails loudly.
- **S8 OpenSandbox: which of a timeout and a line kill wins (review 2).** The cause is recorded only when no kill was
  requested yet (`killRequested`, under `killLock`) and the exit is not known; once recorded, every outcome reports
  `timedOut = false` — also when the kill's exit lands past the spec's timeout, or `await` runs out after the line
  kill. A long line arriving after a timeout's or caller's kill leaves the timeout reported. The latched pre-`init` path
  cannot produce an outcome at all: the reader stops at the long line, never sees `init`, and the status poll fails
  ("ended before the command started").
- **S8 `SandboxShell`: one `wrapperFailed`, not an overload.** It names the cause whenever the outcome carries one
  ("…failed (exit 137): the provider ended the command: an output line exceeded the provider's line limit; …"), which
  also covers the non-stream path. The result for an isolated non-hook run keeps the outcome's own exit code (137 in
  practice) rather than a literal 137. "Isolated non-hook" is `Mode.ISOLATED && !isHook()`, so the engine's probe shape
  (isolated, no listener) is covered as well as its run shape; both are stream runs.
- **Contract 7c is generic in the published contract.** `RuntimeFileSystemContract` cannot see the implementation's
  ceiling, so it asks for `Integer.MAX_VALUE`; the unit-tier subclass pins `MAX_READ + 1` and "no provider call". The
  contract also gained cases the design did not list: a negative `maxBytes` is `IllegalArgumentException`, more refused
  shapes (`/workspace//x`, `/workspace/x/`, `/workspacex/y`, the empty string), and — unit tier — a sandbox found gone
  mid-call is an `IOException` and the next call re-creates it.
- **Testkit-tier engine suite: provisioned per test, host path resolved per call (review 2).** `ShellEngineContractTest`
  provisions in `@BeforeEach` and gives `HostPathEnvironment` a function that resolves the sandbox's host root through
  the store's slot ref on every call. The `python3` assumption runs `python3 -I` through the sandbox's own `PATH`
  (`bash` in the environment), not `LocalProcessSandboxProvider.BASE_PATH`, which is package-private.
- **Docker tier: one sandbox per class, warmed in `@BeforeAll` (review 2).** `SharedSandbox` provisions and seeds the
  python profile once per class (`ShellEngineContractIT`, `ShellEngineAvailabilityIT`, `OpenSandboxRuntimeFileSystemIT`).
  `ShellEngineAvailabilityIT` has one test each for the facts, static availability and a real probe, and a negative
  seed test: an image without `python3` (the tier's image with every `python3*` on `PATH` removed; the build fails
  saying so if one is still found) under a profile declaring it fails provisioning permanently at
  `[step runtime-python3]`, and the probe reports the engine unavailable for a reason that names `python3`. The fork
  and the two negative cases of §6.10 are unit-tier only, since they do not depend on the provider.
- **cmdline IT: the patterns reach `grep` in a file.** With the key's base64 as a `grep` argument, `grep`'s own
  `/proc/<pid>/cmdline` would hold it and the check could never be zero; the patterns are written through the file tools
  and passed with `-f`.
- **Every inherited engine test runs on both tiers (Q-C4 below, closed).** `wallClock` and `bridgeLimitStopsAtOnce`
  were disabled on the testkit tier, and `ShellEngineContractIT` dropped the engine's replies to a stopped run, until
  core's fix (aimon-core#336) was published; both workarounds are gone. The testkit tier checks after every test that
  no `aimon_ptc.py` of the test's sandbox is left on the host; the docker tier has a test of its own that a timed-out
  run whose program started a child leaves no runtime, no child and no zombie in the sandbox.
- **Not done (optional in the design):** `SandboxContentSearch` does not log a kill cause.

### Q-C4 — core's runtime outlives the shell's kill (found in build) — **closed**

Core's `aimon_ptc.py` starts with `os.setpgid(0, 0)` (shell doc §6, step 1). This repository's kill — a timeout, a
cancellation, a line kill — reaches the exec's process group and no further (WS §9; execd offers nothing wider), so it
never reaches the runtime. What follows:

- A `TIMED_OUT` or `CANCELLED` run's runtime keeps running after the shell call returned. One waiting for a reply ends
  at its own wall clock (`_await_reply`'s deadline); one that never awaits (`while True`) does not end until the sandbox
  goes. On OpenSandbox the channel is an execd spool file, so the runtime never sees EPIPE either.
- Measured on the testkit tier, where the runtime is a host process: every run of the suite's `wallClock` left one
  `aimon_ptc.py` at ~100 % CPU after the test and its harness had closed (seven of them after seven runs, ages 2–11
  minutes when observed).
- The suite's `bridgeLimitStopsAtOnce` failed 1 run in 5 on its own and once under `checkAll`: after the bridge trips the
  stop, the runtime is not killed, keeps sending calls, and `ShellProgramRun` forwards them to the bridge until the
  shell call returns (6 calls recorded, the suite allows 5). The docker tier had the same race in principle; it is now
  closed there by dropping the replies to a stopped run (above).

The fix belongs in core: the runtime should stay in the exec's process group (and put the model's children in a group
of its own for `_end_process`), or notice by itself that its shell call ended (e.g. watch its parent). This repository
does not work around it (it would need a session-wide kill the image contract cannot promise). Recorded in WS §20 as
an item to close before the shell engine is enabled in production.

**Closed (aimon-core#336, backlog TC-19).** Core's runtime now forks first: the *anchor* — the process the shell
started — stays in the exec's process group and holds no secret; the runtime under it leads its own group and
double-forks a *watchdog* into that group, which kills the group when the anchor dies (the kernel closes the anchor's
pipe, whatever signal killed it). So the group kill this repository makes is enough, and nothing changed here but the
tests (above). What remains, checked here:

- **Zombie watchdogs.** The double-forked watchdog is reparented to the sandbox's PID 1 and becomes its zombie once it
  has killed its group, so PID 1 has to reap. On OpenSandbox PID 1 is the execd image's `bootstrap.sh` — read from the
  pinned server: docker makes it the container's entrypoint, and Kubernetes (template mode) the sandbox container's
  command. Its `/bin/sh` starts execd and the entrypoint (`tail -f /dev/null`) in the background and blocks in `wait`
  on the latter; a POSIX shell's `wait` reaps any child that exits meanwhile, adopted orphans included. With
  `runtime.execd_run_as_init = true` it execs `execd --init`, a reaper by design. The docker tier asserts it after
  every test of `ShellEngineContractIT` (no new `Z` process, runtime or child of the program), and the K8s tier now in
  template and pool mode (*K8s tier, measured* below). Pool mode (`extensions.poolRef`) runs `bootstrap.sh` as a task
  under the pool pod's task-executor, so its PID 1 is the `Pool`'s, not the server's: it reaps only if that pod's PID 1
  does, or if `execd_run_as_init` makes execd a subreaper (measured below). So `execd_run_as_init = true` is an operator
  requirement (Kubernetes guide §4.2), and `scripts/k8s-tier.sh` sets it. An image contract sentence is not needed: the
  PID 1 is the provider's (or the pool's), not the image's.
- **`shell()` and `supports` provision nothing** (core's javadoc, item 10 of its design). `SandboxExecutionEnvironment
  .shell()` returns the shell built with the environment, and `SandboxShell.supports` / `getWorkingDirectory` are
  static; `ShellEngineAvailabilityTest.askingTheShellProvisionsNothing` pins it (no provider call of any kind).

### K8s tier, measured (2026-11-07)

Run on a throwaway kind cluster (`aimon-sandbox-verify`, deleted afterwards) provisioned by `scripts/k8s-tier.sh up`, with
its own kubeconfig; design and departures: [`ptc-shell-verification.md`](ptc-shell-verification.md). kind v0.33.0,
node `kindest/node:v1.37.0` (containerd 2.3.4, arm64), OpenSandbox chart at `3738975`, server, controller, execd, egress
and task-executor `release-1.1.1-rc.1` (docker.io), the tier's contract image (`src/test/docker/Dockerfile`), the pool
of `src/test/k8s/pool.yaml` (hardened at container level like the template, `recycleStrategy: Delete`), and the server
with `execd_run_as_init = true`, which the script now sets by default.

**What was measured on which bytes.** The first run (PR #52) measured the sources before `spotlessApply`, an import
tidy and a javadoc fix — formatting only — on a server without `execd_run_as_init`. It was re-run on the code of the
follow-up change, which is what the table shows: the sources it ran are the ones committed (formatting applied before
the run; only documentation changed after it), and the configuration is the shipped one.

| Class | Mode | Tests | Passed | Failed | Skipped |
|---|---|---|---|---|---|
| `OpenSandboxK8sIT` | template | 8 | 8 | 0 | 0 (the two internet checks ran) |
| `OpenSandboxK8sPoolIT` | pool | 8 | 4 | 0 | 4 (need a network policy, a credential or the runtime class) |
| `ShellEngineContractK8sTemplateIT` | template | 15 | 15 | 0 | 0 |
| `ShellEngineContractK8sPoolIT` | pool | 15 | 15 | 0 | 0 |

That is the final, unfiltered `scripts/k8s-tier.sh test` (2 min 26 s, exit 0). An earlier run on the same cluster,
before the diagnostics below and before the PID 1 record also listed the execd processes, had the same counts. The 15
of each shell-engine class are core's 12 engine tests, the timed-out run with its watchdog evidence, the PID 1 record
and the orphaned-zombie test; each is followed by the leftover check against a snapshot taken before it, keyed on pid
and start time. A re-run on a fresh cluster after the start time became the kernel's (`/proc/<pid>/stat` starttime,
in clock ticks, instead of `ps`'s `lstart` to the second) and the pool's PID 1 check began matching task-executor by
its program's name had the same counts: 42 passed, 4 skipped, 0 failed.

**Re-run on one node and on three (2026-11-10).** The leftover snapshot now reads `/proc` alone (`cmdline` bracketed by
two `stat` reads), and the PID 1 check reads the NUL-separated `/proc/1/cmdline`. With those changes the tier ran
twice, each time on a fresh `aimon-sandbox-verify`, which was deleted afterwards. First on one node, then with
`AIMON_K8S_NODES=3`: a control plane, which kept its NoSchedule taint, and two workers. Both runs had the same counts:
42 passed, 4 skipped, 0 failed (`OpenSandboxK8sIT` 8/8, `OpenSandboxK8sPoolIT` 4 + 4 skipped, template 15/15, pool
15/15). On three nodes the server and the controller ran on `worker2`. The run's 16 pods in the sandbox namespace landed
as follows:

| Pods | `worker` | `worker2` |
|---|---|---|
| Template sandboxes | 5 | 5 |
| Pool pods | 4 | 2 |

So 9 sandboxes ran on a node other than the server's. PID 1's argv was the same as on one node in both modes.

- **PID 1, template mode:** `/opt/opensandbox/execd --init -- tail -f /dev/null` (asserted: `bootstrap.sh` or
  `execd --init`). Without `execd_run_as_init` (the first run) it was `/bin/sh /opt/opensandbox/bootstrap.sh tail -f
  /dev/null`, which reaps too.
- **PID 1, pool mode:** `/bin/sh -c /opt/opensandbox/task-executor -listen-addr=0.0.0.0:5758 -log-dir=/tmp` — the pool
  pod's command; Debian's `sh` does not exec a lone `-c` command, so it stays PID 1 and its `wait` reaps. Under it,
  `execd --init -- tail -f /dev/null` runs as a subreaper. The pool class asserts that PID 1 is not task-executor or
  that an `execd --init` runs.
- **Zombies:** none left in either mode, by the inherited tests or the dedicated ones. The timed-out run's probe saw the
  runtime lead its own group, the anchor in another, and a watchdog: an `aimon_ptc` process in the runtime's group whose
  parent is neither the runtime nor the anchor.
- **`OpenSandboxK8sIT` in pool mode** (`OpenSandboxK8sPoolIT`, new): the labels, the hardening of the pool's pods (not
  root, no-new-privileges, no capabilities, no service-account token), the seed of a profile without waivers and
  east-west isolation all pass on pool pods. The runtime-class, `dns+nft`, vault and git-under-injection checks skip by
  assumption, each saying why: the server refuses a network policy on a pool sandbox, so there is no egress sidecar, and
  a pool's pods get no runtime class. Two of its tests took about 60 s each, waiting for the pool (`poolMax: 3`,
  `recycleStrategy: Delete`) to replace used pods.
- **Diagnostic: task-executor as PID 1, with `execd_run_as_init = true`** (the pool's command without the shell):
  15/15 — PID 1 `/opt/opensandbox/task-executor …`, and `execd --init` (pid 27 under the task) reaps the task's orphans.
- **Diagnostic: the same, without `execd_run_as_init`** (`AIMON_K8S_EXECD_INIT=0 scripts/k8s-tier.sh reconfigure`):
  15/15 failed. The PID 1 test failed naming the cause ("PID 1 of a pool-mode sandbox is not task-executor, or execd runs
  as --init … PID 1 is /opt/opensandbox/task-executor …"); the others failed in the leftover check or, for the timed-out
  and orphaned-zombie tests, in their bodies, as on the first run: task-executor does not reap orphans.
- **So `execd_run_as_init = true` is required for pool mode**, and it is what the script and the guide ship. The
  pool's shell PID 1 is a second reaper, not the requirement: a base whose `sh` execs its command would lose it.
- **Cold start:** on the first run's fresh node, `OpenSandboxK8sIT` failed 2 of 8 with the server's own 504 ("Timeout
  waiting for sandbox … to be Running with IP. Elapsed: 60.0s") while the node pulled the egress image (2 min 27 s,
  131 MB); rerun warm: 8/8. Not a product failure; the guide's kind section mentions it. The re-run did not hit it.
- **Not run:** nothing that the tier has was skipped except the four pool-mode assumptions above. Gaps that stay: runc
  only (no gVisor or Kata). Multi-node is kind's: every node is a container on the same Docker host.
- **Negative control** (docker tier, `LeftoverCheckNegativeControlIT`): the leftover check fails on an escaped child of
  a timed-out run (a `start_new_session` child, which is what the pre-#336 runtime was) and on an unreaped zombie,
  naming the mark and the parent. That shows the detector turns red. It does not show a pre-#336 core failing: that
  artefact cannot be pinned, and core is not modified here.

### Review 2's non-blocking items

| Item | Done |
|---|---|
| `timedOut` and `killCause` both set on some OpenSandbox paths | resolved as above; `OpenSandboxProviderTest` pins the line kill past the timeout and the long line after a timeout kill |
| testkit decorator: where `{sandboxDir}` comes from | resolved per call through the slot ref, after provisioning in `@BeforeEach` |
| docker `ShellEngineContractIT`: warm the static environment | `SharedSandbox.started()` in `@BeforeAll` |
| trailer + kill cause on an isolated run is deliberate | said in `completedStream`'s javadoc; `SandboxShellKillCauseTest` pins it |
| `read` output and `LocalConnection`'s output translation | noted in `read`'s javadoc |
| `toString` includes runtimes only when non-empty | done |
| O4: follow where `insecureAllow ⊄ WAIVABLE` is refused | done (`SandboxStartupValidator`) |

### Open questions — answers and where they went

- **Q7** (testkit tier via the `reply_dir` decorator) — built as designed; it works (every inherited test passes, since
  Q-C4's close the two that were disabled too). It goes to WS §20 together with Q-C3, the core change that would make
  it unnecessary.
- **O1** closed in the design (`toBuilder()`); **O2** — `ToolCallOrigin.direct()`; **O3** — no negative docker seed
  test; the fragment is run on the host against fake and real `python3` (`SandboxSeederTest`), and the docker tier's
  seed runs the check against the real image; **O5** closed in the design; **O6** — `ToolOfferContext.builder()` needs
  nothing before `putEnvironmentFacts` (no registry: `factsOf` reads Bash as not eligible, which §6.10 overrides with
  `bashUnconstrained(true)` anyway).
- **Q1, Q3, Q4, Q5, Q6, Q-C1, Q-C2, Q-C3** — WS §20, each linking here. **Q-C4** went there too and is closed (above).
