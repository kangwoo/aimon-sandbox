# Design — bring aimon-sandbox up to aimon-core `0.3.1-SNAPSHOT` @ `61604b4` (EE-1 sandbox part, EE-59)

> Status: **IMPLEMENTED**. The body below is the design as approved; where the code departed from it, §10 says so
> and why. The architecture document [`workspace-sandbox.md`](workspace-sandbox.md) has absorbed the changes (§5.3,
> §7, §9, §12.1, §13, §15, §16), and its §20 carries the open questions of §9 that outlive this change.

> Scope: TASK.md items 1–8. Base: aimon-sandbox `bb6c877`. Core reference: aimon-core `61604b4` (read-only).
> Below, **WS** is `docs/design/workspace-sandbox.md`, **spike** is `docs/design/opensandbox-spike.md`,
> **core §13** is aimon-core `docs/design/tool/execution-environment.md` §13.
> This document is written in English like the step-3/step-4 implementation designs; edits to WS are written in
> Korean, the language of that file. The repository has no `*.en.md` translation convention (checked `docs/`).

---

## 1. The problem

aimon-core main gained five PRs (#204–#208) that the next core release (0.3.1) ships together. aimon-sandbox pins
that core as a SNAPSHOT and must follow before the release. One thing is broken outright: a test still uses the
deleted `Environment` type (EE-14), so the test source set does not compile. Three things compile but leave the
sandbox behind the new contract (core §13, three new rows): the sandbox shell does not declare
`ShellFeature.CANCELLATION`, so `KillShell` answers with an error for every sandbox background command; the sandbox
environment states no `backgroundCommandTimeout()`, so a background command gets core's default of 24 hours; and the
provider's position on `bindRuntime`/`RuntimeBinding` is undocumented and untested. Around those sit verification
chores: core's backlog items EE-1 and EE-59 describe this repository **by inference** (their author had no
checkout), so each claim has to be checked against the source here, the behaviour changes of EE-51/EE-70 have to be
documented for sandbox users, WS §7 has to mirror core §13, and the PR has to tell the core maintainer exactly which
inferences held.

## 2. What the source says (verified before designing)

Every item below was checked in this worktree or in the core checkout. They are the premises of §3.

| # | Fact | Where |
|---|------|-------|
| F1 | The only compile error is `Environment` in `OrcaRuntimeSandboxE2ETest` (import line 22, `.environment(Environment.createDefault())` line 108). Nothing in `main` uses it. | grep over `modules/` |
| F2 | **Remote kill already exists and is a mandatory part of the provider SPI.** `RunningCommand.kill()` "ends this call's process group — SIGTERM, then SIGKILL after a short grace". OpenSandbox: `DELETE /command?id=` (SIGTERM to the group, SIGKILL 3 s later); a kill before the `init` event is latched and sent when the id arrives; sent once. Local testkit provider: `kill -TERM -- -pgid`, then `-KILL` after 1.5 s. The contract suite pins it (`SandboxProviderContract.killEndsOnlyItsOwnProcessGroup`). | `provider/RunningCommand.java`, `OpenSandboxRunningCommand.java:272-297`, `LocalRunningCommand.java`, spike §3 |
| F3 | `SandboxShell.await` already kills on two paths — thread interrupt and "sandbox lost" (heartbeat) — with the same `AtomicReference<RunningCommand>` + latch pattern a cancellation listener needs. | `SandboxShell.java:265-299` |
| F4 | A killed wrapper prints no trailer: `kill()` signals the whole exec process group, the outer bash has no TERM trap and dies, and the inner bash's TERM trap exits without saving state. The command's output so far is in the run files `run-{id}.out/.err`, which the timeout path already reads back through the files API (`readPartial`). | `ShellWrapper.java`, `SandboxShell.timeout()` |
| F5 | The kill reaches the **process group**, not the session or cgroup: a job the command moved out of the group (`setsid`, `set -m`) survives until the sandbox goes. execd offers nothing wider. Already documented for the timeout kill. | spike §3, WS §9, `ShellWrapper` javadoc |
| F6 | A background command (`ExecutionOptions.isBackground()`) takes no shell lock, has no in-sandbox watchdog, and is bounded only by `options.getTimeout()` (+5 s slack) enforced by the provider's `await`; with a `null` timeout the backstop is one day. | `SandboxShell.run`, `ShellWrapper.background` |
| F7 | **Keep-awake is already capped.** A background command's activity heartbeat stops after the profile's `backgroundHeartbeatLimit` (default 1 h); after that the idle policy pauses/terminates the slot under the command. | `ActivityHeartbeat`, WS §5.3 |
| F8 | **The provider holds nothing per `AgentRuntime`.** Workspaces are keyed by session or execution id (`ws:{sessionId}` / `ws:{executionId}`); connections by `ProviderSandboxRef`; shell locks and `exec:` shell directories by ref + shell key. `AgentRuntimeId` is only copied into `BindingContext` for custom policies to read. WS §3.2 already states "`AgentRuntime.close()` does not touch sandboxes". | `DefaultSandboxBindingPolicy`, `SandboxConnectionCache`, `BindingContext` |
| F9 | `ExecutionEnvironmentSpec` (and `factory`) appears nowhere — code, tests, docs, README. This repository depends on `aimon-core` only, not `aimon-bootstrap`. | grep |
| F10 | No use or implementation of `BackgroundBashStore`/`Record`/`Manager` (one prose mention in WS §20), `SkillHookActivator`, `SkillForkExecutor`, `HookRegistryAccess`, `SubagentExecutor`, `ShellHookOutcome`, `HookHotReloadBootstrap`, `HookRegistryReloader`. No tool here spawns subagents (orchestrator tools are step 5 and do not exist yet). `OrcaSandboxToolProvider` does not exist — it went with the old design. | grep |
| F11 | No `switch` over `ShellFeature`: `supports` is an `==` chain. | `SandboxShell.java:440` |
| F12 | `WorkspaceSandbox.skillHookSetParser()` wires core's `NoOpShellActionExecutor`, and WS §12.1 justifies it with "core's `DefaultShellActionExecutor` runs on the host". **That is no longer true** of this core: since EE-12 (closed 2026-10-03) `DefaultShellActionExecutor` holds no shell and runs the action in the firing execution's `environment.shell()`. WS §20's open question "run skill hooks in the sandbox — needs core's hook executor to take the environment" is answered on the core side. | `WorkspaceSandbox.java:116-128`, core backlog EE-12 |
| F13 | `SandboxShell` already honours `ExecutionOptions.environment` and `stdin` (what a skill shell hook sends), with tests. | `SandboxShellIT` (`stdinIsFedFromAFile`, per-command environment tests) |
| F14 | `SandboxShellIT` and its siblings carry no `@Tag("docker")`: they run in `test` (so in `checkAll`) against the testkit's `LocalProcessSandboxProvider`, with real processes. | `aimon.java-conventions.gradle.kts`, the IT classes |
| F15 | Core's caller side: `BackgroundBashManager.start` creates a `ShellCancellationSource` only when `shell.supports(CANCELLATION)`; `BackgroundBashTask` settles as `KILLED` exactly when the cause is `ShellCancelledException`; `KillShell` waits up to 5 s for the task to settle; `BashTool` takes the environment's ceiling when it is positive (floor 1 s) and tells the model "The environment stops it after …". Only background `Bash` carries a signal today (foreground still relies on thread interrupt — core EE-54). | core `tools/bash/*` |

## 3. Approach

Six decisions, D1–D6. Each lists what was rejected and why.

### D1 — Compile fix (EE-14)

`OrcaRuntimeSandboxE2ETest`: import `at.aimon.core.base.UserLocale`; `.environment(Environment.createDefault())`
becomes `.userLocale(UserLocale.createDefault())` (confirm the factory method name against the core class while
editing — the task names the type, getter and builder method, not the factory). Nothing else.

### D2 — Cancellation: `SandboxShell` declares `CANCELLATION` and implements it on the existing kill path

**Chosen.** `supports(ShellFeature.CANCELLATION)` returns `true`, unconditionally — killing is mandatory in the
provider SPI (F2), so there is no provider for which the declaration would be false. `execute` honours
`options.getCancellation()` for **both** foreground and background commands (the contract is general; core's only
caller today is background `Bash`, F15).

Mechanics, all inside `SandboxShell`:

1. **Tripped before the call → nothing happens remotely.** First statement after argument validation, before
   `manager.connect`: if `cancellation.isCancelled()`, throw `ShellCancelledException` (no output). No provisioning,
   no activity record, no lock. This is the "command is not started at all" clause.
2. **Tripped while waiting** (provisioning inside `connect`, or the node-local shell lock, up to `shellLockWait`):
   neither wait is aborted — provisioning is shared with other executions and must not be torn down by one
   command's cancel — but the signal is re-checked **after the lock is held** and again in `await` immediately
   before `connection.run`. In both places the command is not started and `ShellCancelledException` is thrown; the
   existing `finally` removes any uploaded `.cmd`/`.in` run file.
3. **Tripped while running.** In `await`, next to the existing `lost` latch, add a `cancelled` `AtomicBoolean` and a
   listener registered with `cancellation.onCancel(...)` *before* `connection.run`:

   ```java
   // illustrative
   final ShellCancellation.Registration registration = cancellation.onCancel(() -> {
       cancelled.set(true);                       // flag first, then kill (LocalShell's order)
       final RunningCommand command = running.get();
       if (command != null) { killQuietly(command); }
   });
   try {
       if (cancelled.get()) { throw cancelledBeforeStart(); }   // listener ran at registration
       running.set(slot.connection().run(spec, OutputSink.DISCARD));
       if (lost.get() || cancelled.get()) { running.get().kill(); }   // tripped between check and set
       outcome = running.get().await(spec.timeout());
   } finally { registration.remove(); heartbeat.close(); }
   ```

   The listener is thread-safe (atomics + a `kill()` both providers already make idempotent and thread-safe),
   idempotent, and wrapped so it cannot throw. It runs on the cancelling thread: for OpenSandbox that is one
   `DELETE` bounded by `request-timeout`; for the local provider one `/bin/kill`. `cancel()` therefore returns when
   the stop has been *requested*, which is what the contract allows a remote shell.
4. **Deciding the outcome — by what the wrapper printed, not by the flag alone.** After `await` returns:
   - the outcome has a **trailer** → the command ended on its own before the kill landed; return the normal
     `ShellCommandResult` ("a signal tripped after the command ended changes nothing");
   - **no trailer and `cancelled` is set** → throw `ShellCancelledException` carrying the partial stdout/stderr read
     from the run files (the helper the timeout path uses), `outputTruncated=false` as the timeout path reports it,
     and notices = pending notices + the existing `KILLED_NOTICE`. This check comes **before** the `timedOut` and
     `lost` branches: a command the caller asked to stop is "killed", whatever else also happened in that instant;
   - otherwise the existing classification (timeout, lock busy, wrapper failure).

   Classifying by trailer closes the race `LocalShell` leaves open (flag set between the command's natural end and
   `registration.remove()`), at no cost: `classify` already looks for the trailer first.
5. State and cleanup are what a timeout kill already gives: the inner bash's TERM trap skips the state save, so the
   shell keeps the state from before the command; the run files are removed by the existing `finally`; the shell
   lock is released by the `Lease`; the heartbeat stops in `finally`.

**What "everything the command started" means here** (F5): the exec's process group — the wrapper, the inner bash,
the command and every descendant that stayed in the group. A job the command put in its own group or session
survives until the sandbox goes. This is the same reach as the timeout kill and as core's own `LocalShell` gap
(core EE-55), and it is stated in the javadoc and WS §9 rather than hidden. It is not a reason to withhold the
declaration: not declaring leaves *every* command unstoppable.

| Rejected | Why |
|----------|-----|
| Do not declare `CANCELLATION`; document that `KillShell` errors | Only justified if no remote kill existed. It exists, is mandatory in the SPI and contract-tested (F2). |
| Declare it per provider through a new `Capability.CANCEL` | `RunningCommand.kill()` is not optional, so the capability would be `true` for every conforming provider — a knob with one position, plus a startup-validator row and a contract-suite branch to maintain. |
| Add a new SPI method (`SandboxConnection.cancel(commandId)`) | `RunningCommand.kill()` is already exactly that, including the before-`init` latch. No SPI change means no change for third-party providers. |
| Treat the cancel as a thread interrupt of the executing thread | The shell does not own that thread; and interrupt already has its own message/semantics ("interrupted and killed"). Core rejected the same alternative for the same reasons (EE-13 design §3.1). |
| Decide "cancelled" from the flag alone (as `LocalShell` does) | Misreports a command that finished normally in the same instant as `KILLED`, and throws away its real exit code and output. The trailer is authoritative and already parsed. |
| Kill wider than the group (a second exec that walks `/proc` for the run's descendants, or cgroup kill) | execd has no cgroup/session kill (spike §3); a `/proc` walk is racy, needs a marker in every descendant's environment, and costs a second exec on the cancelling thread. The timeout kill has the same reach today; widening both belongs to one change with its own spike, not to this migration. Recorded as a known limit (§6). |
| Abort provisioning / the lock wait when the signal trips | Provisioning is shared by every execution bound to the slot; the lock wait is ≤ `shellLockWait` (10 s). Re-checking after each is enough to guarantee "not started". |
| Cancel only background commands | The SPI contract does not distinguish; the code path is shared; and core EE-54 (carry the signal on foreground calls) then needs nothing from this repository. |

### D3 — Background ceiling: a per-profile `backgroundCommandTimeout`, defaulting to `backgroundHeartbeatLimit`

**Chosen.** `SandboxProfile` gains an optional `backgroundCommandTimeout` (settings key
`background-command-timeout`). `SandboxProfile.backgroundCommandTimeout()` returns the configured value, or
`backgroundHeartbeatLimit()` when unset (default 1 h). `SandboxExecutionEnvironment.backgroundCommandTimeout()`
returns `Optional.of(...)` of the profile the provider already resolves for the descriptor (`declaredProfile`), so
it costs no extra store read and a fork states the ceiling of the profile its descriptor declares.

**Why this value.** F7 is the basis: once a background command's heartbeat stops at `backgroundHeartbeatLimit`, the
command no longer keeps its slot awake, and from then on its fate is an accident of other activity — with none, the
slot is paused under it after `pauseAfter` (the command freezes mid-run) and destroyed after `terminateAfter` (the
task ends as "sandbox lost"); with some, it runs on unobserved for up to a day while a core worker thread waits on
it. Ending the command at the moment it stops being allowed to hold the slot gives it one deterministic, explained
end instead: core tells the model at start "The environment stops it after 1 hour if it is still running", the task
settles as a timeout with its partial output, and the model can `KillShell` earlier. One knob by default; an
operator who wants a dev server to outlive the keep-awake window sets `background-command-timeout` higher
explicitly and accepts that it then lives only while something else keeps the slot awake.

It is per profile because the idle policy it derives from (`pauseAfter`, `terminateAfter`,
`backgroundHeartbeatLimit`) is per profile (WS §10.2, §13.1).

Validation (`SandboxStartupValidator`, same block as `background-heartbeat-limit`): an explicit
`background-command-timeout` must be positive. No upper bound and no relation to `terminate-after` is enforced —
a value above `backgroundHeartbeatLimit` is legal and documented (previous paragraph). The field joins
`SandboxProfile.contentHash()`'s canonical form like every other field (its only effect there is that a
permanently-failed slot is retried once after the profile changes).

Enforcement needs no new code: core passes the ceiling as `options.getTimeout()`, and F6 already turns that into the
provider backstop (`timeout + 5 s`) and a `ShellTimeoutException` with partial output.

| Rejected | Why |
|----------|-----|
| Return empty (keep 24 h) | TASK item 3 and core §13 ask for a value where a running command interacts with slot lifetime. 24 h is also longer than any slot can live abandoned (`backgroundHeartbeatLimit + terminateAfter`), so it is never the real bound — the "sandbox lost" failure is. |
| Default `backgroundHeartbeatLimit + terminateAfter` (the longest an abandoned command can survive) | In that window the command may be frozen by `pauseAfter` and then lost with the sandbox; the ceiling would fire at the same moment the provider expiry destroys the sandbox — a race whose loser decides whether the model sees "timed out" or "sandbox lost". It bounds nothing the idle policy does not already bound. |
| Reuse `backgroundHeartbeatLimit` with no new field | Couples two questions an operator may answer differently ("how long may it hold the slot" vs "how long may it run"). A defaulted separate field gives the coupling by default and the split when wanted. |
| A global `SandboxSettings` value | The idle policy is per profile; a `review` profile (terminate after 30 min) and a `standard` one need different ceilings. |
| Put the ceiling in the `EnvironmentDescriptor` notes | The descriptor is rendered into the prompt and its equality protects the prompt cache; core decided the same (EE-13 design §3.2) and already tells the model in the start response. |

### D4 — Runtime binding: inherit `RuntimeBinding.NONE`; pin the contract with tests and say why in the javadoc

**Chosen.** `SandboxExecutionEnvironmentProvider` does **not** override `bindRuntime`. F8: it holds nothing keyed
by runtime, so there is nothing a binding's `close()` could release — sandboxes live as long as their workspace
(close, idle policy, janitor, provider expiry), connections as long as the sandbox generation or the assembly.
The three clauses of the core §13 row are then true by construction:

- "a closed handle does not stop a running command" — the handle is `NONE`;
- "what another binding of the same id uses is not released" — nothing is released;
- "`resolve` answers for an id nobody bound" — `resolve` never consults bindings.

The provider's class javadoc and WS §7 state this explicitly, including the trap for future changes: anything
later keyed by `AgentRuntimeId` must come with a real `bindRuntime` whose `close()` leaves running commands alone.
Teardown order follows: core's `BACKGROUND_COMMANDS` phase signals commands through shells whose connections belong
to `WorkspaceSandbox`, which the *host* closes — so the host must close the core stack (or its runtimes) **before**
`WorkspaceSandbox.close()`. That sentence goes into the `WorkspaceSandbox` javadoc and the README (D5).

| Rejected | Why |
|----------|-----|
| Override `bindRuntime` to return `NONE` explicitly | Code that restates the inherited default. The decision is carried by javadoc and tests, which is where a reader looks. |
| Track bindings and evict the runtime's cached connections / `exec:` shell directories on close | Connections are per sandbox and shared by every runtime bound to that workspace; evicting one would cut another runtime's — or the same runtime's still-running — command off its stream, which is precisely what the contract forbids. `exec:` directories are already swept by `execShellIdle`. |
| Make `SandboxExecutionEnvironmentProvider` `AutoCloseable` so a stack can own it via `provider(Supplier)` | What needs closing is the whole `WorkspaceSandbox` (janitor, schedulers, connections, optionally the provider); closing a part of it from the stack would leave the rest to the host anyway. `shared(...)` states the ownership truthfully (D5). |

### D5 — `ExecutionEnvironmentSpec.factory`: nothing to migrate; document the supported wiring

F9: no use exists, so nothing breaks and nothing moves. Because the README currently shows no wiring at all, add a
short "Wiring" section (also satisfies TASK item 6's "guide/README" for the behaviour note, D6):

- direct: `runtimeBuilder.executionEnvironmentProvider(sandbox.environmentProvider())` (as the `WorkspaceSandbox`
  javadoc already shows);
- through aimon-bootstrap: `ExecutionEnvironmentSpec.shared(sandbox.environmentProvider())` — `shared`, not
  `provider(Supplier)`, because the host owns `WorkspaceSandbox`; `factory` no longer exists in core 0.3.1;
- close order: the core stack first, then `WorkspaceSandbox` (D4).

### D6 — Skill hooks: document the behaviour change, and correct the now-false rationale for refusing shell hooks

TASK item 6 asks for one sentence: when the sandbox is unavailable (`ExecutionEnvironmentUnavailableException` from
`shell()`/`execute` — here `BindingRejectedException`/`SandboxUnavailableException` paths), a tool call guarded by a
skill's `preTool` shell hook is **blocked** with the reason, and a skill fork with an `onStart` shell guard **does
not start**; hooks that only observe should set `failOpen: true`. `hooks.json` hooks run on the host shell and are
unaffected by sandbox availability.

That sentence only describes a deployment in which skill shell hooks *run in the sandbox* — which F12 shows is now
what core does by default, while this repository's assembly helper still refuses such skills at parse time and WS
justifies the refusal with a statement that stopped being true. Leaving that paragraph untouched next to the new
sentence would make the document contradict itself. So, in this PR:

- **WS §12.1** (the "스킬 선언 훅의 셸 액션은 샌드박스 모드에서 거부한다" paragraph) is rewritten to the current facts:
  with core ≥ 0.3.1 a skill-declared shell hook runs in the execution's sandbox shell (never on the host);
  `WorkspaceSandbox.skillHookSetParser()` / `markdownSkillParser()` remain as an **opt-in stricter policy** ("no
  skill-declared shell code at all"); the fail-closed behaviour and the `failOpen` recommendation follow.
- **WS §20** "스킬 선언 훅을 샌드박스에서 돌리기" is struck through as closed on the core side (EE-12), pointing at
  §12.1 and at open question Q2 below for what is left.
- **`WorkspaceSandbox` javadoc** (class and the two static methods): "Skills **must** be parsed with …" becomes
  "may be parsed with … to refuse skill-declared shell hooks altogether"; the reason given is no longer "instead of
  running its hook on the host".
- **README "Wiring"** carries the behaviour sentence and the `failOpen` recommendation.
- **No behaviour change in code**: the helper methods and `SkillHookRejectionTest` stay as they are. Whether to
  deprecate/remove the helpers, and whether this repository should own an end-to-end test of a skill shell hook
  running inside a sandbox, is Q2 — it is a product decision the task statement does not make.

| Rejected | Why |
|----------|-----|
| Add only the `failOpen` sentence, leave §12.1/§20/javadoc alone | The repository would then say both "shell hooks are refused in sandbox mode because they run on the host" and "shell hook guards block when the sandbox is unavailable". One of them is false against this core. |
| Remove `skillHookSetParser()`/`markdownSkillParser()` and the rejection test now | Deleting public assembly API and flipping the documented default is beyond "follow the core"; a host may want the stricter policy. Left to Q2. |

## 4. Changes by file

### Code — `modules/aimon-sandbox`

| File | Change |
|------|--------|
| `src/test/.../environment/OrcaRuntimeSandboxE2ETest.java` | D1. |
| `src/main/.../environment/SandboxShell.java` | D2: `supports` adds `CANCELLATION`; pre-connect check; post-lock check; `await` gains the `cancelled` latch, the listener registration/removal, the before-start check and the trailer-first cancelled classification; a small `cancelled(files, run)` helper beside `timeout(files, run)` (shared partial-read). Class javadoc: one paragraph on cancellation, its three check points and its reach (process group). |
| `src/main/.../environment/ShellWrapper.java` | Javadoc only: the "timeout kill is best-effort" paragraph now says "timeout **or cancellation** kill". No script change. |
| `src/main/.../profile/SandboxProfile.java` | D3: nullable field + builder method `backgroundCommandTimeout(Duration)`, accessor returning the effective value, `contentHash()` canonical form, `toBuilder` if present. |
| `src/main/.../SandboxStartupValidator.java` | D3: explicit `background-command-timeout` must be positive (violation text in the style of the `background-heartbeat-limit` line). To let the validator see "explicit", the profile exposes the raw optional (package-visible or `Optional<Duration> configuredBackgroundCommandTimeout()` — implementer's choice, keep it out of the public surface if the existing validator pattern allows). |
| `src/main/.../environment/SandboxExecutionEnvironment.java` | D3: constructor takes the ceiling; overrides `backgroundCommandTimeout()`. |
| `src/main/.../environment/SandboxExecutionEnvironmentProvider.java` | D3: passes `profile.backgroundCommandTimeout()` into the environment. D4: class javadoc paragraph on `bindRuntime` (inherits `NONE`, why, and the rule for future per-runtime state). |
| `src/main/.../WorkspaceSandbox.java` | D4/D6: javadoc only (close order; shell-hook parser is optional). |

No change in `aimon-sandbox-opensandbox` main code or in the provider SPI. `aimon-sandbox-testkit`: no main change
expected (`SandboxTestProfiles` only if a test needs a profile with a short ceiling).

### Documentation

| File | Change |
|------|--------|
| `docs/design/workspace-sandbox.md` §7 | Table gains three rows matching core §13: **취소** (`SandboxShell` declares `CANCELLATION`; signal → `RunningCommand.kill()` on the exec's process group → `ShellCancelledException` with partial output; already-tripped → not started, nothing provisioned; reach = process group, §9), **백그라운드 상한** (`backgroundCommandTimeout()` = profile `backgroundCommandTimeout`, default `backgroundHeartbeatLimit`; rationale → §5.3), **런타임 바인딩** (nothing per runtime; inherits `NONE`; resolve answers unbound ids; close order). Also the intro sentence: core PRs #204–#208 are now the baseline; the `BackgroundBashStore` row of core §13 is "not implemented here". |
| WS §5.3 | After the `backgroundHeartbeatLimit` paragraph: the command itself now ends at `backgroundCommandTimeout` (default the same value), replacing "명령은 계속 돌지만". |
| WS §9 | The kill bullet: add the cancellation signal as a third trigger next to deadline and interrupt; background bullet: mention the ceiling and `KillShell`. |
| WS §12.1, §20 | D6. §20 also strikes "백그라운드 명령을 끝내는 도구" (closed: core `KillShell` + this change) — keeping the residual limit (process group) as a pointer to §9. |
| WS §13.1 / §13.2 | Profile table row and the YAML example key `background-command-timeout`. |
| WS §15 / §16 | §15 error table: sandbox unavailable ⇒ guarded tool calls blocked / `onStart`-guarded forks not started (D6). §16 scenario table: new rows for cancel (running, before start, after end), ceiling, binding. |
| `README.md` | New "Wiring" section (D5, D6). Status paragraph: core baseline is `0.3.1-SNAPSHOT` @ `61604b4`. The SNAPSHOT-pin text stays. |
| `CHANGELOG.md` `[Unreleased]` | New subsection "Changed — follows aimon-core 0.3.1 (EE-59)": `SandboxShell` supports cancellation (`KillShell` now works for sandbox background commands; reach = process group); profiles gain `background-command-timeout` (default = `background-heartbeat-limit`, i.e. **background commands now end after 1 h by default instead of running up to 24 h**); `bindRuntime` not overridden and why; behaviour note on fail-closed shell guards; shell-hook refusal is now optional; test moved to `UserLocale`. |
| `gradle/libs.versions.toml` | Untouched (TASK item 7). |
| `docs/design/workspace-sandbox-step3.md` / `-step4.md` | Untouched — they are records of what those steps did. |

## 5. Data and interface shapes

```java
// SandboxProfile (public, immutable class + builder — existing style)
public Duration backgroundCommandTimeout();                 // configured value, else backgroundHeartbeatLimit()
public Builder backgroundCommandTimeout(Duration value);    // null = follow backgroundHeartbeatLimit

// SandboxExecutionEnvironment
@Override public Optional<Duration> backgroundCommandTimeout();   // always present

// SandboxShell
supports(CANCELLATION) == true
execute(...) throws ShellCancelledException                 // subtype of ShellExecutionException, core type
```

- Settings key: `aimon.sandbox.profiles.<name>.background-command-timeout` (duration; optional).
- `SandboxProfile.contentHash()` canonical form gains `backgroundCommandTimeout=<effective value>`.
- No change to: the provider SPI (`SandboxProvider`, `SandboxConnection`, `RunningCommand`, `ExecSpec`,
  `ExecOutcome`), the workspace record/store schema, `SandboxBinding`, the wrapper script, events.
- `ShellCancelledException` contents: message `"the command was cancelled and killed"` (running) or
  `"the command was cancelled before it started"`; stdout/stderr = run-file contents up to `maxCaptureBytes`
  (empty before start); notices = pending notices (+ `KILLED_NOTICE` when it was running).

## 6. Failure modes

| Situation | Handling |
|-----------|----------|
| Signal already tripped at `execute` | `ShellCancelledException` before `connect`: nothing provisioned, no activity recorded. |
| Tripped during provisioning or the lock wait | Honoured when that wait returns; command not started; lock lease released; uploaded run files removed. Provisioning itself completes (shared). |
| Tripped between the pre-run check and `running.set` | Listener sees no command; the post-`run` re-check kills it (same latch the `lost` path uses). OpenSandbox additionally latches a kill issued before `init`. |
| Tripped as the command ends by itself | Trailer present → normal result; the kill is a no-op (`DELETE` answers 404, already tolerated; local `kill` of a dead group is ignored). Core settles the task as completed, not `KILLED`. |
| Cancel and timeout in the same instant | No trailer + flag ⇒ `ShellCancelledException` (checked before `timedOut`). |
| Cancel while the sandbox is lost | Flag wins: `ShellCancelledException` with whatever partial output can still be read (the read helper already swallows failures → empty). `markLost` bookkeeping from the heartbeat is unaffected. |
| The kill request itself fails (execd unreachable, 5xx) | `kill()` logs and returns (existing). The listener does not throw. `await` keeps waiting; the command ends at its timeout/backstop and is then reported by the existing paths; `KillShell` answers "stop requested, still shutting down" after 5 s (core). |
| `await` throws a provider exception after a cancel | Propagates as today (`SandboxUnavailableException` / lost message); the task ends `FAILED`, not `KILLED`. Not converted: `ShellCancelledException` would claim a stop that was not observed. |
| A descendant left the process group (`setsid`, `set -m`) | Survives the cancel, as it survives a timeout kill today, until the sandbox goes. Documented (WS §9, javadoc, CHANGELOG). Core's contract wording "everything the command started" is met to the extent execd allows; recorded for the maintainer in the PR (§9 below). |
| A descendant ignores SIGTERM | SIGKILL follows (3 s execd, 1.5 s local). `execute` returns when the exec stream ends, i.e. when the wrapper is gone; the straggler writes to an unlinked run file. |
| Listener and heartbeat-lost callback both kill | `kill()` is idempotent in both providers. |
| Wrapping shells | None in this repository derive `ExecutionOptions` (checked: `SandboxShell` is the only `VirtualShell`). Nothing to forward. |
| Ceiling configured ≤ 0 | Startup violation. (Core would also ignore it with a WARN.) |
| Ceiling above `backgroundHeartbeatLimit` | Legal; the command lives past its keep-awake window only while other activity holds the slot, and otherwise ends as "sandbox lost" — documented in §5.3/§13.1. |
| Ceiling reached | Existing background timeout path: provider kills at `timeout + 5 s`, `ShellTimeoutException` with partial output; core reports `FAILED` (timeout). |
| Runtime evicted while its background command runs | Binding is `NONE`; the command, its connection and its heartbeat continue. Stack shutdown later cancels it through the still-open connection — provided the host closes `WorkspaceSandbox` after the stack (documented). |
| Host closes `WorkspaceSandbox` first | Connections close; a later cancel's `DELETE` fails and is logged; commands end with the sandbox (idle policy/expiry). Documented as the wrong order, not defended in code. |
| `SkillHookRejectionTest` against the new core | Expected to pass unchanged (`NoOpShellActionExecutor.isShellSupported()` is still `false`); if core's message text changed, adjust the assertion strings only. Run it first after D1. |

## 7. Test strategy

All new unit-tier tests run in `./gradlew checkAll` (F14: local-process provider, real processes). Each is checked
once by reverting its production change and watching it fail (TASK acceptance); the mutation to use is listed.

**`SandboxShellIT` (cancellation, D2)**

| Test | Asserts | Fails when |
|------|---------|-----------|
| `supportsCancellation` | `supports(CANCELLATION)` | `supports` reverted |
| `cancellingARunningBackgroundCommandKillsItAndThrowsCancelled` | `sleep 60` in background, `cancel()` after it is observably running (marker file via the env's file system) → the future fails with `ShellCancelledException` well inside the timeout; stdout carries what was printed before; a later probe (`kill -0 $pid` of a pid the command wrote, or `pgrep` of a unique marker argument) shows the process **and a child it started in the group** are gone | listener/classification removed → the command runs to its end |
| `cancellingARunningForegroundCommandKeepsThePreviousState` | foreground `cd /tmp; export X=1; sleep 60` cancelled → `ShellCancelledException` with `KILLED_NOTICE`; the next command sees the earlier cwd/no `X`; the shell lock is free (next command does not wait `shellLockWait`) | same |
| `aSignalTrippedBeforeExecuteStartsNothing` | pre-cancelled token → `ShellCancelledException`; `RecordingProvider` shows **no** create/connect/run call (nothing provisioned); a marker the command would create does not exist | pre-connect check removed |
| `aSignalTrippedWhileWaitingForTheLockStartsNothing` | hold the shell with one foreground command, start a second with a token, cancel, release → second throws `ShellCancelledException`, its marker is absent | post-lock check removed |
| `aSignalTrippedAfterTheCommandEndedChangesNothing` | run to completion, then `cancel()` → result was returned normally, no exception, no extra provider call | (guards the listener removal: assert via a `DelegatingProvider` that `kill` is not called after completion) |
| `cancelRacingCompletionReturnsTheResultWhenTheTrailerArrived` | deterministic through `DelegatingProvider`: a `RunningCommand` whose `await` trips the signal and then returns an outcome **with** a valid trailer → normal `ShellCommandResult` | flag-only classification |

**`SandboxEnvironmentProviderTest` / `SandboxProfileTest` / `SandboxStartupValidatorTest` (ceiling, D3)**

- environment of the default profile returns `Optional.of(backgroundHeartbeatLimit)`; with
  `backgroundCommandTimeout(20m)` returns 20 m; a fork returns its declared profile's value. *(revert: remove the
  override → empty)*
- profile: default follows `backgroundHeartbeatLimit` (also after changing only that limit); `contentHash` differs
  when the ceiling differs.
- validator: zero/negative explicit ceiling is a violation; unset is not.
- `ActivityHeartbeatIT` or `SandboxShellIT`: a background command given the environment's (short, test-profile)
  ceiling as its timeout ends with `ShellTimeoutException` and its process is gone — characterises F6, the
  enforcement D3 relies on.

**`OrcaRuntimeSandboxE2ETest` (core's tools over the sandbox, D2+D3 end to end, unit tier)**

- register `BashTool` + core's `KillShell`/`BashOutput` (via the same registration core uses) over the sandbox
  provider; scripted LLM: `Bash(run_in_background=true, "sleep 60")` → the start text contains
  `KillShell(taskId=` and "The environment stops it after"; `KillShell(taskId)` → success ("stopped");
  `BashOutput` → `Status: Killed`. *(revert `supports` → KillShell returns the "cannot stop" error and the start
  text says so.)* If wiring the three tools into this hand-built runtime proves disproportionate, the fallback is a
  direct `BackgroundBashManager.start(...)`/`kill(...)` test over `environment.shell()`; the design prefers the
  tool-level test because it is what a user sees.

**Binding (D4) — `SandboxEnvironmentProviderTest` + `SandboxShellIT`**

- `bindRuntime(id)` returns a binding whose `close()` is idempotent; `resolve` works for an id never bound and
  after its binding closed.
- two runtime ids A and B, each with a running background command in its own session's workspace: close A's
  binding (twice) → both commands still running, both complete with their output; B's shell runs a new command.
- same id bound twice: closing the first leaves the second's running command alone.
- These are **characterisation tests**: no production change makes them pass, so "revert and watch it fail" does
  not apply. They are verified the other way round — temporarily add a `bindRuntime` whose `close()` calls
  `connections.close()` (or kills running commands) and confirm they fail — and the PR says so.

**Docker tier (`./gradlew integrationTest`, `@Tag("docker")`)** — `OpenSandboxWorkspaceIT`: one test that cancels a
running background command through `SandboxShell` against a real OpenSandbox server and asserts
`ShellCancelledException` plus the process being gone (`pgrep` through a second command). Run if a Docker daemon is
available; otherwise the PR states it was not run. No new `OpenSandboxProviderTest` case is needed: the fake-server
tests already pin `DELETE /command` (sent once, latched before `init`).

**Gate.** `./gradlew checkAll` green; Spotless/Checkstyle clean; the JaCoCo floors (`aimon-sandbox` 90) hold — the
new branches in `SandboxShell` are all covered by the tests above.

## 8. What the PR and HANDOFF must tell the core maintainer

Decisions for a human, at the top of the PR description: Q1 and Q2 below.

**Can core backlog EE-1 (sandbox part) and EE-59 be closed?**

- **EE-1, sandbox part — yes, after this PR.** What was inferred vs. found:
  - "`OrcaSandboxToolProvider` reads `getFileSystem()`": **stale** — the class was deleted with the
    identifier-based sandbox; nothing here reads `OrcaToolProviderContext`.
  - "`Environment.createDefault()` in one test": **correct** (F1); fixed.
  - Addenda (1)–(4) (subagent-spawning tools, `SkillHookActivator`/`SkillForkExecutor`, `ShellActionExecutor`
    implementations, direct `BackgroundBashManager`/`Store` use): **none apply** (F10). The only touch point is the
    *use* of core's own `NoOpShellActionExecutor`, which still compiles and behaves.
  - EE-70/EE-71 addendum: no `HookHotReloadBootstrap`/`HookRegistryReloader` call here; forks spawned by external
    tools: none here.
  - The aimon-browser half of EE-1 is out of scope and stays open.
- **EE-59 — yes, after this PR**, with these corrections to its inferences:
  - "left alone, a running command keeps the slot awake for a day": **wrong for this repository** — keep-awake was
    already capped at `backgroundHeartbeatLimit` (1 h) since step 3 (F7). What was actually missing was an end for
    the *command* and for core's task record; the ceiling now provides it.
  - "implement remote kill": the kill already existed in the provider SPI (F2); the work was honouring the signal
    in the shell and classifying the outcome.
  - "implement `bindRuntime` if it holds per-runtime resources": it holds none (F8); `NONE` is inherited, with
    tests pinning the contract.
  - "`factory` in docs/examples": not used anywhere (F9) — core's Q1 (remove `factory`) costs this repository
    nothing.
  - "`ShellFeature` switch without default": none (F11).
  - `BackgroundBashStore` owner fields: not implemented here (F10).
  - Behaviour notes (EE-51/EE-70): documented — and they surfaced that this repository's "shell hooks are refused
    in sandbox mode" rationale predates EE-12 (F12, Q2).
  - One thing for core to know: cancellation reaches the exec's **process group**; "everything the command
    started" excludes jobs moved out of the group, exactly as with `LocalShell` (core EE-55).

## 9. Open questions (not assumed)

- **Q1 — Default ceiling.** The design makes background commands end at `backgroundHeartbeatLimit` (1 h) by
  default, where today they may run up to 24 h as long as other activity keeps the slot awake. That is an
  observable change the task statement does not decide ("기본값과 근거를 설계에"). If the maintainer prefers no
  default tightening, the alternative is: field unset ⇒ `backgroundHeartbeatLimit + terminateAfter` (never fires
  before the idle policy would have taken the sandbox anyway). Changing it is one line in `SandboxProfile` plus the
  doc sentences; the tests take the default from the profile, not from a literal.
- **Q2 — Skill-declared shell hooks in sandbox mode.** Core now runs them in the sandbox shell (F12). This PR only
  corrects the documentation and keeps `WorkspaceSandbox.skillHookSetParser()`/`markdownSkillParser()` as an
  optional stricter policy. Undecided: (a) deprecate or remove those helpers and `SkillHookRejectionTest`;
  (b) whether this repository should own an end-to-end test that a `preTool` shell guard runs inside the sandbox
  and blocks when the sandbox is unavailable — the claim in the new README/WS text is taken from core's contract
  and tests (`DefaultShellActionExecutorTest`), **not** verified here; (c) a hook runs as a foreground command of
  the session's shell key, so it takes the shell lock — parallel tool calls with shell guards can meet
  "shell is busy" after `shellLockWait`, which a guard reads as a block. (c) may deserve its own shell key for
  hooks; that needs a core-side way to tell the shell a call is a hook.
- **Q3 — `UserLocale` factory name.** TASK gives the type, the getter and the builder method; the design assumes a
  `createDefault()`-style factory mirrors the old one. To be confirmed against `at.aimon.core.base.UserLocale`
  while editing (a compile error would show it immediately).
- **Q4 — Cancel reach beyond the process group.** Left as a documented limit (D2). Whether to pursue a wider kill
  (upstream execd issue for session/cgroup kill, alongside the existing spike issue drafts) is not decided here;
  suggested as a WS §20 entry rather than work in this PR.
- **Q5 — Docker tier.** Whether a Docker daemon is available to the build agent is unknown at design time; the PR
  must state plainly whether `integrationTest` ran.
- **Q6 — The principal-less fork fallback.** Core main now forwards the principal from
  `SubagentBackedSkillForkExecutor` (seen in the checkout), which is the trigger WS §20 names for revisiting the
  fallback in `SandboxExecutionEnvironmentProvider.bindFork`. Not part of EE-1/EE-59 and not touched here; noted so
  the maintainer can decide whether to fold it into this release.

## 10. Implementation departures

Where the code and documents of this change differ from §1–§9, and why. The open questions of §9 that reach beyond
this change are in WS §20, each pointing back here: Q1 (the default ceiling), Q2 (skill shell hooks in the sandbox),
Q4 (a kill wider than the process group) and Q6 (the principal-less fork fallback, an existing §20 entry). Q3 held:
`UserLocale.createDefault()` exists. Q5: a Docker daemon was available, and the Docker tier ran.

### Where the design was wrong or contradicted itself

- **The trailer was not looked at first (§3 D2, step 4).** `classify` tested `outcome.timedOut()` before the
  trailer, and `await` reported a lost sandbox before `classify` ran at all. The trailer parse is now a method of
  its own, `SandboxShell.completed`, and the cancelled branch sits in `await` ahead of both. A command that was not
  cancelled is classified in the order it always was: lost, timed out, trailer.
- **A cancel whose kill request fails (§6).** One row said such a command is "reported by the existing paths", while
  D2 and the row above it said that no trailer plus a tripped signal means cancelled, checked before the timeout.
  The general rule won: the command ends at its timeout and `execute` throws `ShellCancelledException`, so core
  settles the task as `KILLED`, late. `SandboxShellIT.aCancelWhoseKillFailedIsStillCancelledWhenTheTimeoutEndsTheCommand`
  pins it.
- **Core §13 has no `BackgroundBashStore` row (§4, Documentation).** WS §7 says in one sentence under its table that
  this module uses core's background task list as it is and implements neither the manager nor the store.

### Additions

- **Partial output falls back to the exec stream.** §5 reads a cancelled command's output from its run files. When
  the kill lands in the wrapper's last lines — after it printed the output and removed the files, before the trailer
  — the files are gone and the output is on the stream; `SandboxShell.cancelled` then uses the stream.
- **A test for a command that ignores SIGTERM** (`cancellingACommandThatIgnoresSigtermStillEndsIt`): the SIGKILL
  that follows ends it, and `execute` still reports cancelled. §6 described the behaviour; §7 had no test for it.
- **The cancellation listener is registered before the heartbeat starts**, not beside it as in the sketch of D2. A
  signal that trips between the lock and the exec then starts no heartbeat and records no activity.
- **The CHANGELOG says that every profile's content hash changes** with this version. §3 D3 adds the ceiling to
  `contentHash()` and that is what the code does; a slot that failed permanently is retried once after the upgrade.
- **Documents beyond the list in §4**, so that WS does not contradict itself: WS §21 (the shell-hook rule rewritten,
  and two rules added — the close order and `bindRuntime`), WS §4.1 (the sentence on the skill-hook parser), and WS
  §15 rows for cancellation and for the ceiling next to the fail-closed row. WS §12.1 and the README mark the
  fail-closed behaviour as core's contract, not something a test here exercises, and WS §12.1 and §20 add to Q2(c)
  that a hook's `cd` and `export` are saved into the model's shell state.

### Tests placed or shaped differently from §7

- **The tool-level test is `CoreToolsSandboxIT.killShellStopsASandboxBackgroundCommand`**, not a case in
  `OrcaRuntimeSandboxE2ETest`. It calls core's `BashTool`, `KillShellTool` and `BashOutputTool` over a sandbox
  environment directly, as that class already does for core's other tools. A scripted LLM would have had to parse
  the task id out of one tool result to make the next call. The assertions §7 asked for are kept: the start text
  names `KillShell(taskId=` and "The environment stops it after 1 hour", `KillShell` reports the command stopped,
  and `BashOutput` reports `Status: Killed`.
- **`aSignalTrippedWhileWaitingForTheLockStartsNothing` asserts that no run file was uploaded**, not only that the
  command's marker is absent. Without the check after the lock, the check before the exec still stops the command,
  so the marker cannot tell the two apart; the upload of the command's stdin can.
- **The ceiling's enforcement is characterised in `SandboxShellIT`** (`aBackgroundCommandEndsAtTheCeilingItIsGiven`),
  one of the two classes §7 allowed.
- **The binding tests are characterisation tests, as §7 said.** They were verified the other way round: with a
  `bindRuntime` whose `close()` closes the connection cache, and with one whose `close()` closes the workspaces, they
  fail. The local provider's connections hold nothing, so the shell test counts closed connections and kills through
  the recording provider instead of waiting for a command to be cut off.

### Smaller points

- `SandboxProfile.configuredBackgroundCommandTimeout()` is public: the startup validator is in another package.
- F10 and §8 say `OrcaSandboxToolProvider` "was deleted with the old design". More precisely, it is not implemented
  yet: WS §8.5 and §18-5 still name it as the planned provider of the orchestrator tools. There is nothing to migrate
  today, and it will be written against the current SPI.
- **Left as it was:** an interrupt that arrives after a cancel is still reported as "interrupted and killed", not as
  cancelled. The design does not cover the combination. Core's `BackgroundBashManager.close()` cancels first and
  interrupts five seconds later, so a command whose kill request failed can settle as failed there rather than killed.
