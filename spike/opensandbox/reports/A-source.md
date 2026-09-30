# OpenSandbox spike — A: static source analysis

Source: OpenSandbox @ 3738975 (2026-09-29), cloned at `../OpenSandbox`. All paths are relative to that repo root (`srv/` = `server/opensandbox_server/`).
Tags: **[code]** means confirmed by code, **[doc]** means documented or specified only, **[unknown]** means it needs a live test. No live server was run for this half.

## Verdict summary

| Q | Verdict |
|---|---------|
| Q1 files | stat = path/type/size/mtime/ctime/owner/group/mode; **no hash/etag**; mtime serialized at ns (real FS resolution [unknown]); range read (first range only); write truncates in place (non-atomic) and creates parents; list returns stat per entry but has no limit or pagination; move never replaces (500 on existing dst, check not atomic); delete of a missing file succeeds; mkdir -p. No uid param: files are owned by execd's uid |
| Q2 pause | Docker = `docker pause` (freeze). K8s BatchSandbox = snapshot rootfs to image, delete pod, recreate on resume (processes lost; single replica only). Expiry keeps running while paused; renew allowed while paused |
| Q3 expiry | `timeout` is relative seconds, ≥60, optional (omit = never expires, even with a max set). renew takes an absolute `expiresAt`, and only "in future" is enforced, **so it can shorten** (spec's forward-only is not enforced). Max is checked only at create, not on renew, and **no API exposes it**. Renew on a no-expiry sandbox returns 409 |
| Q4 renew-on-access | Implemented, off by default at the server and opt-in per sandbox; counts only traffic through the server proxy or K8s ingress gateway, so direct endpoints (Docker default) don't count. Keep an explicit extendExpiry |
| Q6 labels | K8s label rules; `aimon.at/*` OK; `opensandbox.io/` is reserved; exact-match AND filter; page-number pagination (≤200 per page, no cursor); metadata can be patched; **no idempotency key** |
| Q7 volumes | per-mount readOnly; pvc {createIfNotExists, deleteOnSandboxTermination, storageClass, storage, accessModes (default RWO)}; Docker = named volume, size/class/mode ignored; no list/delete API; no labels |
| Q8 egress | {defaultAction, egress[]}; deny + [] blocks everything; mode is set server-wide, readable only after create via `enforcementMode` on GET networkpolicy; networkPolicy is rejected under gVisor and Docker host mode (the default). Vault: configured post-create on the sidecar API; scope = host/scheme/method/path(prefix); needs dns+nft |
| Q9 isolation | Not enforced on either runtime; Docker bridge publishes execd on 0.0.0.0 with no token; K8s controller creates no NetworkPolicy |
| Q10 secctx | Nothing per request (except a dangerous `bootstrap.execd.isolation` extension that the provider must never send); Docker defaults come from server config, no user set; K8s automountSAToken=false is enforced, but runAsNonRoot is never set; runtimeClass is server config only |
| Q11 snapshot | API exists (create 202/list/get/delete; restore = create with snapshotId). Docker = `docker commit` (rootfs only), K8s = SandboxSnapshot CR. Fork = snapshot + create |
| Q12 SDK | Direct REST (JDK HttpClient + Jackson). The Kotlin SDK `com.alibaba.opensandbox:sandbox:1.1.0` works, but its run() blocks and buffers without a cap, and kill needs a callback id |
| Q13 CI | `opensandbox/server` image + host docker socket (sibling containers, no DinD); TOML runtime.type=docker, execd_image, docker.host_ip; reuse the `scripts/java-e2e.sh` pattern |
| Q14 contradictions | `/v1`, `OPEN-SANDBOX-API-KEY`; create 202; 8 states incl. Pausing/Resuming/Stopping; 409 is not "duplicate"; execd reached via endpoint resolution (port 44772) + possible headers; mixed error shapes; execd has no token by default |


---

## Q1 Files API: stat metadata, read/write/list/move/delete/mkdir, ownership

**Verdict:** `stat` returns path/type/size/mtime/ctime/owner/group/mode. mtime is a Go `time.Time` serialized at nanosecond precision, but the actual resolution depends on the filesystem. There is no hash, etag or checksum anywhere. Byte-range read works. Write is a multipart upload that truncates the file in place (not atomic). Mode is set by an octal-looking decimal int. `list` = `/directories/list?depth=N`, which returns stat per entry including type and has **no limit/pagination**. `move` never overwrites (fails if dest exists, not atomically). Deleting a file is idempotent, but directories need a separate call. mkdir is `-p`. Files are owned by execd's uid unless `owner`/`group` names are given.

Paths below are relative to the OpenSandbox repo root. Labels: [code] = confirmed in source, [doc] = spec/docs only, [live] = unknown, needs a live test.

### stat — `GET /files/info?path=..&path=..`
- [code] Handler loops the paths, calls `GetFileInfo` → `os.Lstat` (does not follow symlinks), and returns `map[path]FileInfo`. If any path is missing, the whole request fails with 404 `FILE_NOT_FOUND` (components/execd/pkg/web/controller/filesystem.go:64-87, 48-62; components/execd/pkg/web/controller/utils.go:261-276).
- [code] `FileInfo` = `path, type, size, modified_at, created_at, owner, group, mode` (components/execd/pkg/web/model/filesystem.go:19-37). `type` is one of `file|directory|symlink|other` (utils.go:218-230). The spec matches (specs/execd-api.yaml:2090-2130).
- [code] `modified_at = fileInfo.ModTime()`, a Go `time.Time` (utils.go:251). Go marshals it as RFC3339Nano, so nanoseconds are kept when the filesystem has them (ext4/overlayfs: ns). The spec example only shows seconds (specs/execd-api.yaml:2112). [live] Confirm the JSON actually carries fractional seconds on the target image/storage (PVC/NFS may be coarser).
- [code] `mode` is the permission bits written as an octal string and then parsed as a **decimal int** (e.g. 0644 → `644`) (utils.go:245,256). The same encoding applies on input, parsed back with base 8 (utils.go:66-67).
- [code] `owner`/`group` are usernames resolved via `user.LookupId`. They fall back to the numeric uid/gid string (utils.go:235-243).
- [code] **No hash, etag or checksum** exists in the model or the handlers (model/filesystem.go:19-26). The download path calls `http.ServeContent` with modtime (filesystem_download.go:121), which yields a `Last-Modified` header only (HTTP-date, seconds). Go's ServeContent emits no ETag unless the handler sets one, and this handler does not.
- ⇒ The SPI must rely on mtime (ns, if the live test confirms it). Otherwise the provider has to compute a hash itself (e.g. `sha256sum` via `/command`) to satisfy the §6.1 "etag if seconds-resolution" rule.

### read — `GET /files/download?path=`
- [code] `Range: bytes=a-b` gives a 206 with `Content-Range`. Only the **first** range of a multi-range request is served (filesystem_download.go:98-117; ParseRange in utils.go:282-342). An invalid range returns 416 (filesystem_download.go:100-105).
- [code] Alternative line mode: `offset`/`limit` (1-based lines) returns text/plain and cannot be combined with Range (filesystem_download.go:57-82,126-181). Line mode strips `\r\n`, so it is lossy.
- [code] Missing file → 404 via `handleFileError` (filesystem_download.go:71-75).

### write — `POST /files/upload` (multipart: `metadata` JSON part + `file` part, repeatable)
- [code] `metadata` = `{path, owner, group, mode}` (model/filesystem.go:28-31; specs/execd-api.yaml:808-845).
- [code] Parent dirs are auto-created (`MkdirAllWithOwnership`, 0777 &^ umask) (filesystem_upload.go:145-163). The target is opened with `O_WRONLY|O_CREATE|O_TRUNC, 0777` and written **in place**. This is **not atomic**: there is no temp file and rename, so a concurrent reader can see a truncated file (filesystem_upload.go:176-192). After the write it fsyncs the file and the parent dir (194-210).
- [code] Mode/owner are applied afterwards with `ChmodFile`. `mode==0` means no chmod, so the file keeps 0777&^umask. `owner`/`group` are names, and chown needs privilege (filesystem_upload.go:219-233; utils.go:60-113).
- [code] Request size: there is no `MaxBytesReader`, and the router sets no `MaxMultipartMemory` (components/execd/pkg/web/router.go:42-140), so gin's default 32 MiB in-memory limit applies and anything larger spills to temp files. No explicit upload cap.

### list/search
- [code] `GET /directories/list?path=&depth=N` (default 1, 0 = empty) returns `[]FileInfo` **with `type`** per entry, in lexical order. It does not traverse symlinks and rejects a symlink as the root. **No limit or pagination** parameter (filesystem.go:218-332; spec specs/execd-api.yaml:948-1019). `depth` must be large enough to cover a whole tree ("recursive" = large depth). Entries come from `entry.Info()`, i.e. lstat semantics.
- [code] `GET /files/search?path=&pattern=` walks the tree recursively (`filepath.Walk`), **skips directories**, and matches the glob against the **base name only** (`info.Name()`), not the relative path. Returns `[]FileInfo`. No limit (filesystem.go:334-408). Note that `filepath.Walk` uses lstat, so symlinked dirs are not followed.

### move — `POST /files/mv` `[{src,dest}]`
- [code] Missing src → 404. It **auto-creates the dest parent dir** (0755). If dest exists, the call fails ("destination path already exists", surfaced as **500** because it is not ErrNotExist). Otherwise it calls `os.Rename` (utils.go:115-145; filesystem.go:139-162).
- ⇒ This is effectively **no-replace**, but checked with stat-then-rename (TOCTOU, not `RENAME_NOREPLACE`). There is **no overwrite option**. Items are processed in order and there is no rollback on partial failure. The spec says "Target directory must exist" (specs/execd-api.yaml:684), but the code creates it.

### delete
- [code] `DELETE /files?path=` removes only files: a missing file counts as success (idempotent), and a directory is an error (500) (utils.go:35-58; filesystem.go:89-107).
- [code] `DELETE /directories?path=` is `os.RemoveAll` (rm -rf, idempotent) (filesystem.go:189-216).

### mkdir -p — `POST /directories` `{path: {owner,group,mode}}`
- [code] `MkdirAll` plus chown on newly created components only. chmod is applied only if the leaf did not already exist and mode≠0. Existing dirs are OK (idempotent) (utils.go:147-216).

### Ownership / uid
- [code] The files API has **no uid parameter**. Files are created by the execd process, so they get **execd's uid/gid**, unless `owner`/`group` names are supplied in upload/mkdir metadata. That path chowns by name and needs CAP_CHOWN (i.e. root execd) (filesystem_upload.go:155,219-233; utils.go:79-113).
- [code] `/command` without `uid` runs as execd's uid (see Q5), so **files and commands share an owner by default**. This confirms the design choice in §6.1 and §13.3 to never pass `uid`.

### Other
- [code] Auth: header `X-EXECD-ACCESS-TOKEN` (components/execd/pkg/web/model/header.go:18; router.go:183-219).
- [code] `/files/replace` is a non-atomic read-modify-write (filesystem.go:410-484).
- [code] The isolated-session API (`/v1/isolated/session/{id}/files/*`) mirrors the same file ops inside a bwrap overlay (router.go:114-137). Not relevant to the SPI.

---

## Q5 execd: injection, `/command` semantics, kill, output, uid

**Verdict:** execd is **injected into an arbitrary image**. The image needs only `/bin/sh` (bash is optional) and a Linux platform. `command` runs as `bash --noprofile --norc -c <string>` (argv, not stdin), falling back to `sh -c`. There is no body-size limit. A missing cwd is a **400 error, not a fallback**. Output streams over SSE with **separate `stdout`/`stderr` events, split into lines**. Kill is `DELETE /command?id=<init id>`: SIGTERM to the **process group**, then SIGKILL after 3 s. `timeout` is a SIGKILL of the process group. There is **no output size limit**. A client disconnect does **not** kill a foreground command. execd runs as the **image's default USER**, and commands inherit that uid.

### Injection
- [code] **Docker:** the server creates the container (not started), then `put_archive`s the `execd` binary and `bootstrap.sh` (plus optional bwrap, session-gate and launcher) into `/opt/opensandbox`, extracted from the configured `execd_image` and cached in memory. It then sets `entrypoint=[/opt/opensandbox/bootstrap.sh]` with the user command as `Cmd`, and starts the container (server/opensandbox_server/services/docker/runtime.py:40-49,55-189,218-237,376-387; server/opensandbox_server/services/docker/container_ops.py:428-495).
- [code] **K8s (BatchSandbox and agent-sandbox providers):** an initContainer `execd-installer` (image `execd_image`) copies execd, bootstrap.sh and helpers into an `emptyDir` volume `opensandbox-bin` mounted at `/opt/opensandbox`. The main container runs `command=["/opt/opensandbox/bootstrap.sh"] + entrypoint` with `EXECD=/opt/opensandbox/execd` (server/opensandbox_server/services/k8s/provider_common.py:116-230; batchsandbox_provider.py:191-230; agent_sandbox_provider.py:311-347). The pool path instead uses `exec /opt/opensandbox/bootstrap.sh …` (batchsandbox_provider.py:519-545).
- [code] The execd binary is static (`CGO_ENABLED=0`) (components/execd/Dockerfile:24,49). `bootstrap.sh` is a POSIX sh script, so the image needs `/bin/sh`. It errors out if neither bash nor sh exists (components/execd/bootstrap.sh:510).
- [code] bootstrap starts execd in the background and then runs the user command. With `runtime.execd_run_as_init=true` (default **false**) it runs `exec execd --init -- cmd` (OSEP-0018, status `implementing`) (components/execd/bootstrap.sh:515-554; server/opensandbox_server/config.py:1133-1142; oseps/0018-execd-as-sandbox-init.md:7).
- [code] execd listens on port 44772 by default (components/execd/pkg/flag/parser.go:40,69).

### uid of execd and commands
- [code] Neither the Docker create path nor the K8s main container sets a user. The Docker `create_container` kwargs contain no `user` (container_ops.py:428-443), and bootstrap.sh never switches user; it only uses `sudo -n` for a few root-only setup steps (bootstrap.sh:228-236). ⇒ **execd runs as the image's `USER`** (root for most images). [live] K8s securityContext `runAsUser` belongs to another fork (Q10). If one is set, execd runs as that uid.
- [code] Without `uid`/`gid`, the command inherits execd's identity. A request uid equal to execd's own is a no-op. A different uid needs CAP_SETUID/SETGID, and fails with a hint when `docker.drop_capabilities` removed them (components/execd/pkg/runtime/command.go:104-180).
- [code] The optional `[hardening]` launcher (execd config, fail-open) defaults its policy to execd's own uid/gid, and drops identity only when execd is root (components/execd/pkg/runtime/hardening_linux.go:410-422,515-540).

### `/command` request (`POST /command`, JSON)
- [code] Fields: `command` XOR `argv`, plus `cwd, background, timeout(ms, ≥1), uid, gid(requires uid), envs` (components/execd/pkg/web/model/codeinterpreting.go:50-116; specs/execd-api.yaml:1927-1986).
- [code] **How the string is passed:** `exec.CommandContext(ctx, getShell(), "-c", code)`, where `getShell()` = `bash` if found on PATH, else `sh` (command.go:474-476, 80-89). The command string is an **argv element** (no stdin, no temp file). Note: `newShellCommand` does not add `--noprofile --norc`. Only `shellCommand()` does, and `/command` does not use it (command.go:94-102 vs 474-476). Foreground stdin is not set (exec default: /dev/null). Background stdin is explicitly /dev/null (command.go:410-415).
- [code] `argv` mode: native exec, no shell; PATH lookup against the child env (components/execd/pkg/runtime/command_argv.go:60-131).
- [code] **Body size:** `json.NewDecoder(req.Body)` with no limit (components/execd/pkg/web/controller/basic.go:65-68). Only the practical ARG_MAX (~128 KiB per argv string on Linux, `MAX_ARG_STRLEN`) applies to `command`. [live] Check proxies (server/ingress) for limits.
- [code] **cwd:** `$VAR`/`${VAR}`/`~` are expanded. Validation stats the dir; **nonexistent → 400 "working directory does not exist"**, not-a-dir → 400, and an undefined variable fails. Empty cwd = execd's working dir (codeinterpreting.go:115; components/execd/pkg/runtime/workingdir.go:30-49; command_argv.go:41-67).
- [code] **env:** layered as sandbox env < `EXECD_ENVS` file < request `envs`. `PWD` is derived from cwd (command_argv.go:50-58).
- [code] **timeout:** `context.WithTimeout(context.Background(), timeout)` (components/execd/pkg/runtime/ctrl.go:79-93). When the ctx ends, execd sends **SIGKILL to `-pgid`**, with no SIGTERM grace (command.go:304-321). No timeout → unbounded.
- [code] Every command gets `Setpgid: true`, i.e. its own process group (command.go:245-248, 402-405).

### Streaming and exit codes
- [code] The response is `text/event-stream`, but each event is written as **raw JSON followed by `\n\n` with no `data:` prefix** (components/execd/pkg/web/controller/sse.go:33-38,187-224). The Kotlin SDK accepts both framings (sdks/sandbox/kotlin/sandbox/src/main/kotlin/com/alibaba/opensandbox/sandbox/infrastructure/adapters/service/ExecdEventSupport.kt:32-59).
- [code] Event sequence: `init` (text = **command id**), then `stdout`/`stderr` events (separate types), `ping` every 3 s, and finally either `execution_complete` (exit 0, `execution_time` ms) **or** `error` with `{ename:"CommandExecError", evalue:"<exit code>", traceback}` for non-zero exit (sse.go:73-177; command.go:280-368). The exit code is therefore the **string `error.evalue`**. Termination by signal gives Go `ExitCode()` = **-1** [code, Go semantics]. Launch failure = `init` + `error` (command.go:265-278).
- [code] stdout and stderr go to **separate files** in `$TMPDIR/opensandbox-execd/<id>.stdout|.stderr`, tailed every 100 ms. Output is **split into lines**: `\n` and `\r` are both line breaks and are stripped, an empty line is sent as `"\n"`, and a trailing partial line is flushed at exit (components/execd/pkg/runtime/command_common.go:32-115,238-293). ⇒ **Byte-exact output is not preserved** (CR/LF and the trailing newline are lost). Relative stdout/stderr ordering is only per stream.
- [code] **No output size limit**: the tailer reads without bound and the pending-line buffer is unbounded (command_common.go:246-293). The provider must enforce `maxCaptureBytes` client-side, and must also kill or drain the command, because the output files keep growing on disk.
- [code] Status after completion: `GET /command/status/{id}` → `{running, exit_code, error, started_at, finished_at}`, retained ≥24 h (components/execd/pkg/web/controller/command.go:127-155; command_common.go:32-36,183-236). This makes it a reliable exit-code source after an SSE drop.
- [code] The handler sleeps `graceful-shutdown-timeout` (default 200 ms) after completion before closing the stream (command.go:114-120; components/execd/pkg/flag/parser.go:43).

### Kill
- [code] `DELETE /command?id=<id from init event>` → `Interrupt`, which for a running command runs `killPid`: **SIGTERM to `-pgid`**, polls up to **3 s**, then **SIGKILL to `-pgid`**. It returns 500 if the command is not running (components/execd/pkg/runtime/interrupt.go:29-122; controller/codeinterpreting.go:493-515). No cgroup or session is used; only the process group. Processes that `setsid`/`setpgid` away escape.
- [code] **Client disconnect does not kill a foreground command.** The command ctx derives from `context.Background()` (ctrl.go:79-91), not the HTTP request, and the SSE writer just drops events once the request ctx is done (sse.go:192-196). The command runs until exit or `timeout`. ⇒ The provider must always send an explicit `DELETE /command?id=` when a caller cancels, and it needs the `init` event's id for that.
- [code] Signals received by execd itself (non-init mode) are forwarded to the running foreground command's process group (command.go:41-70,322-329).

### Background mode
- [code] `background:true` returns right after start (`init` + `execution_complete`). stdout and stderr are **merged** into one `.output` file. Logs are polled via `GET /command/{id}/logs?cursor=` (plain text, `EXECD-COMMANDS-TAIL-CURSOR` header). A timeout still SIGKILLs the pgid (command.go:371-472; controller/command.go:157-174). Not suitable when stdout and stderr must stay separate.

---

Paths are relative to the OpenSandbox repo root (commit 3738975). Tags: [code] = confirmed by code, [doc] = documented only (spec, OSEP or docstring), [unknown] = needs a live test. `srv/` = `server/opensandbox_server/`.

## Q2 Pause/resume

**Verdict:** On Docker, pause is a real `docker pause` (cgroup freezer), so memory and processes survive. On K8s (BatchSandbox), pause commits the rootfs to an image and **deletes the pod**. Resume recreates the pod from that image, so processes, memory and non-PVC volumes are lost. On both runtimes the expiration clock **keeps running** while paused, and renew is **allowed** while paused.

Docker:
- [code] `pause_sandbox` requires `Running && !Paused`, otherwise 409 `SANDBOX_NOT_RUNNING` (srv/services/docker/docker_service.py:1142-1153). It then calls `container.pause()` (docker_service.py:1170) and also pauses the egress sidecar, rolling back on failure (docker_service.py:1191-1218).
- [code] `resume_sandbox` requires `Paused`, otherwise 409 "Sandbox is not in a paused state" (docker_service.py:1236-1244). It unpauses the sidecar, then the container (docker_service.py:1283-1288).
- [code] State mapping: Docker `Paused` becomes `"Paused"`. There is **no Pausing/Resuming** state on Docker, because the transition is synchronous (docker_service.py:530-542).
- [code] No `docker commit` is involved in pause. Commit exists only for snapshots (Q11).

K8s, BatchSandbox provider (the default workload):
- [code] The server only patches `spec.pause=true/false` (srv/services/k8s/batchsandbox_provider.py:694-738 and 740-788). Allowed phases: pause from `Succeed` (= Running), resume from `Paused`. `Pausing`/`Resuming`/`Pending`/`Failed` raise ValueError, which becomes **409** (srv/services/k8s/kubernetes_service.py:1470-1476).
- [code] Controller pause flow: ACK → stop tasks → create a child `SandboxSnapshot` named `<bs>-pause` (kubernetes/internal/controller/batchsandbox_pause_resume.go:232-290). `completePause` **deletes the pods** in non-pooled mode (batchsandbox_pause_resume.go:479-488) and sets phase `Paused` (batchsandbox_pause_resume.go:491-506).
- [code] Resume rewrites the template container images from the snapshot, and normal reconcile recreates the replica (batchsandbox_pause_resume.go:516-560). The internal snapshot is deleted after a successful resume (batchsandbox_pause_resume.go:122-138).
- [code] Pause supports only `replicas=1` (batchsandbox_pause_resume.go:239-254).
- [doc] OSEP-0008 is `status: implemented` (oseps/0008-pause-resume-rootfs-snapshot.md:7). Its non-goal is "Preserving in-memory process state, open sockets" (0008:92). PVs preserve explicit mounts (0008:67).
- [unknown] Whether the pod IP and endpoint change after resume. They very likely change, because it is a new pod.
- [code] K8s agent-sandbox provider: pause patches `spec.operatingMode=Suspended` (srv/services/k8s/agent_sandbox_provider.py:462-485). What the upstream controller does with that (scale to 0?) is [unknown].
- [code] Other workload providers raise `NotImplementedError`, which becomes **400** "Pause is not supported" (srv/services/k8s/workload_provider.py:228-248; kubernetes_service.py:1451-1458).

Expiry while paused:
- [code] Docker: expiry is an in-process `threading.Timer` keyed only on wall-clock `expires_at` (docker_service.py:281-306). `_expire_sandbox` kills and removes the container regardless of paused state (docker_service.py:391-400).
- [code] K8s: the reconciler deletes the BatchSandbox when `spec.expireTime < now` **before** any pause handling (kubernetes/internal/controller/batchsandbox_controller.go:127-142).
- [doc] "Running/Paused → Stopping (when kill is requested or TTL expires)" (specs/sandbox-lifecycle.yml:1529).
- [code] Renew while paused: neither `renew_expiration` checks state (Docker: docker_service.py:1328-1369; K8s: kubernetes_service.py:1552-1600), so renew is allowed.
- Exception: [code] auto-renew-on-access (Q4) only fires in state `running` (srv/integrations/renew_intent/controller.py:91-92).

## Q3 Expiry

**Verdict:** `timeout` is relative seconds, minimum 60, and optional. Omitting it gives a **non-expiring** sandbox, even when a max is configured. `renew-expiration` takes an **absolute** `expiresAt`. The server enforces only "in the future", **not** "later than the current value" (the spec claims forward-only). The `max_sandbox_timeout_seconds` cap is checked only at create, **not on renew**, and is **not exposed** by any API.

- [code] `timeout: Optional[int] ge=60`. "When omitted or null, the sandbox will not auto-terminate and must be deleted explicitly" (srv/api/schema.py:479-489).
- [code] `ensure_timeout_within_limit` returns immediately when `timeout is None`, so the max does not stop no-expiry sandboxes (srv/services/validators.py:168-181). Above the max, the request fails with 400 `INVALID_PARAMETER` (validators.py:186-197).
- [code] The check is called at create by Docker (docker_service.py:635-638) and K8s (kubernetes_service.py:1022-1025).
- [code] Null timeout on K8s: the label `opensandbox.io/manual-cleanup=true` is set and `expireTime`/`shutdownTime` is omitted (srv/services/k8s/create_helpers.py:63-69; batchsandbox_provider.py:299-302; agent_sandbox_provider.py:205-208).
  - [doc] The schema says K8s "may reject null timeout" (schema.py:486-487), but no rejection path was found in the K8s service. [unknown]
- [code] Renew body: `{"expiresAt": <RFC3339 absolute UTC>}` (schema.py:936-947). The response echoes `expiresAt` (schema.py:950-957).
- [code] Validation is only `normalized <= now → 400 INVALID_EXPIRATION` (validators.py:133-154).
  - Docker renew has **no comparison with the current expiry** (docker_service.py:1328-1369), and neither does K8s renew (kubernetes_service.py:1552-1600). So a client can **shorten** the expiry.
  - [doc] The spec says "Must be in the future and after the current expiresAt time" (specs/sandbox-lifecycle.yml:1947; also the docstring at srv/api/lifecycle.py:377). Code does not enforce this.
  - SPI implication: the provider must enforce forward-only itself (read `expiresAt` and compare).
- [code] Renew does not check `max_sandbox_timeout_seconds` on either runtime (same line ranges), so renew can extend without limit.
- [code] Renew on a sandbox without expiry fails with **409** `INVALID_EXPIRATION` "does not have automatic expiration enabled" (Docker: docker_service.py:1337-1344; K8s: kubernetes_service.py:1572-1580). A no-expiry sandbox can never gain one.
- [code] `max_sandbox_timeout_seconds: Optional[int] = None, ge=60` means no cap by default (srv/config.py:608-614). The example configs use 86400 (srv/examples/example.config.toml:22).
- [code] The only discovery endpoints are `/health` and `/version` (srv/main.py:293-311). No route returns config.
  - Consequence: `ProviderCapabilities.maxExpiry()` must come from **provider configuration**. It can also be probed by a create that returns 400, but that is not recommended.
- [code] Docker expiry timers are in-memory. On restart, they are rebuilt from the `opensandbox.io/expires-at` label plus a file-backed override (docker_service.py:319-334, 426-513, 1357-1362).

## Q4 OSEP-0009 renew-on-access

**Verdict:** It is implemented (OSEP status `implemented`), but it is **opt-in on the server** (`[renew_intent] enabled=false` by default) and per sandbox (`extensions["access.renew.extend.seconds"]`, 300–86400). "Access" means only HTTP/WS traffic through the **server proxy** (`/sandboxes/{id}/proxy/{port}/...`) or the **K8s ingress gateway** (via Redis). Direct endpoint calls (the Docker default) do not count. It is not a safe replacement for an explicit `extendExpiry`.

- [doc] OSEP-0009 `status: implemented` (oseps/0009-auto-renew-sandbox-on-ingress-access.md:7). "Docker direct access is explicitly out of scope" (0009:40, 67, 98).
- [code] Extension key `access.renew.extend.seconds` (srv/extensions/keys.py:17). It is validated as an integer in [300, 86400] (srv/extensions/validation.py:23-24, 28-60).
- [code] Master switch: `RenewIntentConfig.enabled` defaults to False; cooldown `min_interval_seconds` defaults to 60 (srv/config.py:245-262). Redis mode is needed for ingress (config.py:180-215).
- [code] Trigger points: the server proxy HTTP handler (srv/api/proxy.py:360) and the WebSocket handler (proxy.py:577) call `_schedule_proxy_renew` (proxy.py:226-231). The ingress gateway publishes the intent to Redis (components/ingress/pkg/proxy/proxy.go:145-148).
  - Header `OpenSandbox-Access-Renew: skip` opts out per request (proxy.py:67-68; components/ingress/pkg/proxy/header.go:33-40).
- [code] Renew logic (srv/integrations/renew_intent/controller.py:86-116):
  - skips unless the state is `running` and `expires_at` is not None
  - sets `new = max(now + extend, current)`, so it never shortens
  - calls the normal `renew_expiration`, so the max is not enforced
- [code] The endpoint route has `use_server_proxy: bool = Query(False)` (srv/api/lifecycle.py:531). The default returns the direct endpoint, which does **not** trigger renew.
- Assessment:
  - It can replace a heartbeat only if (a) the operator enables `[renew_intent]`, (b) the provider sets the extension, and (c) the provider routes all execd traffic through `use_server_proxy=true` or the ingress gateway.
  - It also stops renewing while the sandbox is paused, and it ties expiry to *any* traffic (including a stuck client).
  - Recommendation: keep an explicit `extendExpiry` in the SPI. Treat renew-on-access at most as an optional provider hint.

## Q6 Labels / metadata

**Verdict:** Metadata is validated with K8s label rules. Prefixed keys like `aimon.at/managed` are **allowed**; only the `opensandbox.io/` prefix is reserved. List filtering is `metadata=<url-encoded k=v&k2=v2>` with exact-match **AND** semantics. Pagination is **offset** (page/pageSize, max 200), with no cursor. Listing includes exited containers (Docker) but not deleted or expired ones. There is **no idempotency key or client id** on create.

- [code] Key rules (srv/services/validators.py:51-85):
  - An optional DNS-subdomain prefix of ≤253 chars (`[a-z0-9]([-a-z0-9]*[a-z0-9])?` labels joined by dots) plus `/`.
  - A name of ≤63 chars matching `[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?`.
  - So `aimon.at/sandbox-key` is valid (lowercase prefix).
- [code] Value rules: ≤63 chars, `^([A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?)?$`. The **empty value is allowed** (validators.py:54, 88-91). A base32 hash (A–Z, 2–7) passes; mixed case is allowed.
- [code] Reserved prefix `opensandbox.io/` is rejected with 400 `INVALID_METADATA_LABEL` (validators.py:103-113; srv/services/constants.py:19).
  - Called at create (Docker: docker_service.py:633; K8s: kubernetes_service.py:1020).
- [code] Metadata can be changed after create: `PATCH /sandboxes/{id}/metadata` with JSON Merge Patch, where null deletes. It has no optimistic lock (srv/api/lifecycle.py:218-242). Docker persists patches to a file store because container labels are immutable (docker_service.py:1371-1393).
- [code] List query (srv/api/lifecycle.py:123-178):
  - `state` repeated means OR.
  - `metadata` is parsed with `parse_qsl(strict_parsing=True)`; a malformed value gives 400 `INVALID_METADATA_FORMAT`.
  - `page ≥1` defaults to 1; `pageSize` is 1..200 and defaults to 20.
- [code] Matching is exact `metadata.get(k) == v` for every k, i.e. AND (srv/services/helpers.py:192-206). There is no existence-only or prefix query.
- [code] The server builds the full list, filters in memory, sorts by `created_at` desc, then slices by offset (Docker: docker_service.py:1016-1093; K8s: srv/services/k8s/list_helpers.py:29-56).
  - Response: `pagination{page,pageSize,totalItems,totalPages,hasNextPage}` (srv/api/schema.py:910-929).
  - Offset pages can skip or duplicate entries under concurrent create/delete. A provider `list()` should dedupe by id and tolerate misses.
- [code] Terminated entries:
  - Docker lists `all=True` containers with the `opensandbox.io/id` label (docker_service.py:1027-1031). Self-exited containers therefore appear as `Terminated`/`Failed` (docker_service.py:549-557) until they are deleted or expire.
  - Expired or deleted sandboxes are removed (docker_service.py:391-400, 1117-1125).
  - K8s lists existing workload CRs (kubernetes_service.py:1325-1338); expiry deletes the CR (batchsandbox_controller.go:127-137).
- [code] Delete of an absent sandbox returns **404**, not success (docker_service.py:1099-1105; lifecycle.py:245-254). The provider must map 404 to success for an idempotent `destroy`.
- [code] Idempotency: the id is always a server-generated `uuid4` (srv/services/sandbox_service.py:57-64; docker_service.py:607; kubernetes_service.py:1030). No idempotency or request key exists in the create schema (schema.py:452-575). `X-Request-ID` is tracing only (lifecycle.py:96).
  - The only idempotent create is the fast-sandbox (fsb) path via FastPath `request_id` (srv/services/fast_sandbox/fastpath_client.py:130), which is not exposed in the public API.
  - This confirms design §6.3: look up by label, then create (best effort).

## Q7 Volumes

**Verdict:** `volumes[]` entries have `name`, `mountPath`, `readOnly` (per mount), `subPath` and exactly one of `host`/`pvc`/`ossfs`. `pvc` has `claimName`, `createIfNotExists` (default **true**), `deleteOnSandboxTermination` (default false), `storageClass`, `storage` and `accessModes` (default `["ReadWriteOnce"]`). On Docker, `pvc` maps to a **named volume** (size, class and modes are ignored). There is **no volume list/delete API** and **no user labels** on volumes. On K8s, a PVC auto-created with `deleteOnSandboxTermination=true` is owned by that sandbox and **cannot be mounted by another sandbox (409)**.

- [code] Volume model (srv/api/schema.py:358-417):
  - `name` is a DNS label ≤63 (schema.py:368-373).
  - `mountPath` must match `^/`.
  - `readOnly` defaults to false (schema.py:392-396).
  - Exactly one backend (schema.py:406-416).
- [code] PVC model (schema.py:225-297):
  - `claimName` matches `^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`, ≤253.
  - `createIfNotExists` defaults to True (schema.py:248-255); `deleteOnSandboxTermination` defaults to False (schema.py:256-266).
  - `storageClass`, `storage` (pattern `\d+(Ki|Mi|Gi|…)`) and `accessModes` are "used only when auto-creating", "Ignored for Docker volumes" (schema.py:268-294).
- [code] K8s creation (srv/services/k8s/kubernetes_service.py:696-918):
  - The size default is `storage.volume_default_size` (config.py:807).
  - Access modes default to `["ReadWriteOnce"]` (kubernetes_service.py:~800, "access_modes = vol.pvc.access_modes or ["ReadWriteOnce"]").
  - Labels `opensandbox.io/volume-managed-by=server` and `opensandbox.io/id=<sandbox>` are applied **only if** `deleteOnSandboxTermination`, together with ownerReferences to the CR.
  - A 409 create race is tolerated by re-fetching and re-checking ownership.
- [code] `_reject_pvc_owned_by_other_sandbox` returns 409 when a PVC is labeled as managed by a different sandbox (kubernetes_service.py:920-950). For shared workspace volumes, use `deleteOnSandboxTermination=false`; the PVC is then unlabeled and user-managed, and the server never deletes it.
- [code] K8s read-only: if the same claim is mounted in several entries, the pod-level PVC source is `readOnly` only if **all** mounts are read-only. Each `volumeMount.readOnly` follows its own entry (srv/services/k8s/volume_helper.py:27-35, 56-109).
- [code] Docker (srv/services/docker/volumes.py):
  - `pvc` becomes `docker volume create name=<claimName> labels={opensandbox.io/volume-managed-by: server}` when missing and `createIfNotExists` (volumes.py:152-200). The docstring still says "must already exist" [stale doc].
  - Binds are `name:/path:ro|rw` per entry (volumes.py:332-372).
  - Removal happens only for volumes auto-created on this request with `deleteOnSandboxTermination` and the managed label (volumes.py:80-84, 427-454).
  - `host` binds are restricted to `storage.allowed_host_paths` (volumes.py:71-72; config.py:799).
- [code] Volume API: the full route list is lifecycle (srv/api/lifecycle.py), plus networkpolicy, pool, templates, devops, metrics and proxy. **No volume endpoints** exist (grep of `@router` in srv/api/*.py). `VolumeReclaimer` must therefore use the K8s or Docker API directly, as the design says.
- [code] Volume labels: the request has no label field (schema.py:225-297). The server applies only its own system label. This confirms design §6.3 (identify volumes by name prefix).
- [doc] OSEP-0003 (volumes) is `status: implementing` (oseps/0003-volume-and-volumebinding-support.md:7).
- [code] Volumes are rejected with pool mode and template mode (schema.py:586-600). Pool mode in K8s rejects `networkPolicy` (schema.py:526-533).

## Q11 Snapshot / fork

**Verdict:** A snapshot API exists: `POST /sandboxes/{id}/snapshots` (202), `GET /snapshots`, `GET/DELETE /snapshots/{id}`. Restore or fork means `create` with `snapshotId` instead of `image`. Docker implements it as `docker commit` (rootfs only, no volumes). K8s implements it as a BatchSandbox plus a `SandboxSnapshot` CR and the image-committer (rootfs pushed to a registry). Both are supported; fsb has its own runtime.

- [code] Routes: srv/api/lifecycle.py:399-512 (create 202 with a possible 501 "not implemented"; list, get, delete).
- [code] Restore: `CreateSandboxRequest.snapshot_id`; exactly one of image or snapshotId is allowed (srv/api/schema.py:465-469, 619-623). The entrypoint defaults to `tail -f /dev/null` for snapshots (schema.py:516-524).
- [code] Runtime factory: docker → `DockerSnapshotRuntime`; kubernetes → composite of `KubernetesSnapshotRuntime` and `FastSandboxSnapshotRuntime` (srv/services/snapshot_runtime_factory.py:140-190).
- [code] Docker: `container.commit(repository="opensandbox-snapshots", tag=<snapshot_id>)` runs synchronously in the request (srv/services/docker/snapshot_runtime.py:17-19, 38-42, 150-161).
  - [unknown] docker-py `commit` pauses the container during commit by default (upstream docker behaviour). Named volumes are not captured.
- [code] K8s: preflight requires a BatchSandbox and checks the source pod's RuntimeClass handler (srv/services/k8s/snapshot_runtime.py:93-140). The controller uses `SandboxSnapshot` (kubernetes/internal/controller/sandboxsnapshot_controller.go) and `cmd/image-committer`.
- [doc] OSEP-0015 "Spec-Driven Pod Snapshot" is `draft` (oseps/0015-pod-snapshot.md:7). Process and memory are not preserved (oseps/0008-pause-resume-rootfs-snapshot.md:92).
- Assessment: FORK = snapshot + create(snapshotId) is feasible on both runtimes. The fork does not carry PVC or named-volume content; the shared volume is mounted separately anyway. Registry config on K8s and the latency of commit/push are [unknown] and need a live test.

---

## Q8. Egress: networkPolicy, modes, credential vault

**Verdict:** `networkPolicy` is `{defaultAction: allow|deny, egress: [{action, target}]}`. `deny` plus an empty list really is deny-all. `dns+nft` is a server-wide setting and fails closed. You cannot learn the mode before create, but you can read it per sandbox afterwards (`enforcementMode` on `GET /sandboxes/{id}/networkpolicy`). Credential Vault is a sidecar API you call after create. Its scope can be narrowed by host (exact or `*.`), scheme, method and path (exact or prefix with `*`). It requires `dns+nft`, an egress auth token and `credentialProxy.enabled`.

Paths below are relative to the OpenSandbox repo root.

### Schema
- [code] Server model: `NetworkRule{action: str, target: str(min_length=1)}` and `NetworkPolicy{defaultAction: Optional[str], egress: list[NetworkRule]}` (`server/opensandbox_server/api/schema.py:89-121`). The server does not check `action` or `target` beyond `min_length`. The sidecar validates them (see below).
- [doc] The OpenAPI spec says `target` is an "FQDN or wildcard domain … IP/CIDR not yet supported in the egress MVP" (`specs/sandbox-lifecycle.yml:2062-2074`, `specs/egress-api.yaml:397-409`).
- [code] **This contradicts the spec:** the sidecar parses IP and CIDR targets (`components/egress/pkg/policy/policy.go:153-161`) and puts them into nft interval sets (`policy.go:192-215`, `components/egress/pkg/nftables/manager.go:232-250`). `server/configuration.md:238` [doc] agrees that CIDR/IP rules are enforced only under `dns+nft`.
- [code] Policy can be changed at runtime through the lifecycle API. `GET`, `PUT` (replace), `PATCH` (merge rules by target) and `DELETE` (rules by target) on `/sandboxes/{id}/networkpolicy` (`server/opensandbox_server/api/network_policy.py:28-55`; spec `specs/sandbox-lifecycle.yml:54-229`). For the Docker and K8s backends the server proxies these calls to the sidecar.
- [code] You cannot combine `networkPolicy` with `extensions.poolRef`. The request is rejected (`server/opensandbox_server/services/k8s/batchsandbox_provider.py:166-170`; spec `sandbox-lifecycle.yml:1730-1731`).

### What `defaultAction: deny` with an empty allow list does
- [code] The server starts a sidecar only when `request.network_policy` is truthy (`services/docker/docker_service.py:783`; `services/k8s/create_helpers.py:85,125`). Without a sidecar there is no enforcement at all, so omitting the policy means allow-all. This matches the design doc §6.4.
- [code] The server passes the policy as JSON in env `OPENSANDBOX_EGRESS_RULES`, serialized with `exclude_none` (`services/docker/networking.py:472-479`; `services/k8s/egress_helper.py:104-110`).
- [code] The sidecar treats empty, `null` or `{}` input, and a missing or blank `defaultAction`, as **deny** (`components/egress/pkg/policy/policy.go:64-80,129-141`; `pkg/policy/persist.go:34-40`).
  - So `{"defaultAction":"deny","egress":[]}` is deny-all.
  - Even `networkPolicy: {}` gives deny-all, because a pydantic object is truthy, the sidecar starts, and it receives `{"egress":[]}`.
  - The spec and schema descriptions saying "empty → allow-all at startup" (`schema.py:116`, `sandbox-lifecycle.yml:1849-1852,2026-2028`) describe omitting the field, not sending an empty object.
  - [unknown — live test] Confirm the `{}` case end-to-end.
- [code] In `dns+nft` mode the nft `output` chain is created with `policy drop` when `defaultAction` is deny (`pkg/nftables/manager.go:253-257`). It then accepts only:
  - established/related traffic, sidecar-marked packets, loopback, the DNS proxy on 127.0.0.1:15353, and `::1`;
  - the static allow set, and the dynamic allow set filled from allowed DNS answers;
  - and ends with an explicit `drop` (`manager.go:258-294`).
  - Nameserver IPs from `/etc/resolv.conf` and discovered upstreams are always allowed (`components/egress/nameserver.go:26-50,72-91`; `nft.go:55-57`).

### Modes
- [code] The server setting `[egress].mode` accepts `dns` (default) or `dns+nft` (`server/opensandbox_server/config.py:994-1006`). It is passed to the sidecar as `OPENSANDBOX_EGRESS_MODE` (`networking.py:477-480`; `egress_helper.py:112`). There is no per-request override: the create request has no mode field (`schema.py:459-565`).
- [code] What each mode enforces:
  - `dns` is DNS-proxy filtering only. The sidecar logs "nftables disabled (dns-only mode)" (`components/egress/nft.go:35-52`). A connection made directly to an IP is therefore not blocked [code, by absence of nft]. The docs say the same (`server/configuration.md:238`).
  - `dns+nft` exits the sidecar with a fatal error if the nft apply fails (`nft.go:57-66`). **It fails closed; it does not silently fall back to dns-only.**
- [code] You can read the mode after create: the sidecar returns `enforcementMode` in every policy response (`components/egress/policy_server.go:197,441`). The lifecycle `GET /sandboxes/{id}/networkpolicy` passes that through, as `PolicyStatusResponse.enforcementMode` (`specs/sandbox-lifecycle.yml:2001-2021`; Fsb omits it).
  - There is **no** server-level endpoint that reports the mode before a create.
  - Recommendation: keep the operator-declared `egress-enforcement` flag, and have the provider check `enforcementMode == "dns+nft"` on the first created sandbox (or the seed) and refuse if it differs.
- [code] Runtime incompatibility: when the effective runtime is `gvisor`, `networkPolicy` is **rejected with 400** because gVisor netstack has no iptables nat table (`services/validators.py:585-619`; called at `networking.py:190`, `batchsandbox_provider.py:307-310`, `agent_sandbox_provider.py:217-220`). **EGRESS_POLICY and RUNTIME_CLASS=gvisor cannot both be advertised.** Kata is allowed.
- [code] Docker: `networkPolicy` is rejected with 400 when `docker.network_mode` is `host` (the default) or a user-defined network. Only `bridge` works (`services/docker/networking.py:153-185`; `config.py:1200-1203`; `configuration.md:119,312`).

### Credential Vault
- [code] It is **not** part of the create request. `credentialProxy.enabled: true` on create only starts transparent MITM (`schema.py:124-138`; `docker_service.py:720-735,798-805`; `egress_helper.py:120-121`). The vault itself is configured afterwards on the **sidecar egress API** (port 18080), which you reach through endpoint resolution with the `OPENSANDBOX-EGRESS-AUTH` header (`specs/egress-api.yaml:5-27,157-310`). The routes are handled at `components/egress/policy_server.go:248-362`. The Kotlin SDK has a client for it (`sdks/sandbox/kotlin/sandbox/src/main/kotlin/com/alibaba/opensandbox/sandbox/Sandbox.kt`, references found by grep).
- [code] API surface:
  - `POST /credential-vault` creates it with `{credentials[], bindings[]}`.
  - `GET` returns sanitized state.
  - `PATCH` takes `{expectedRevision?, credentials:{add,replace,delete}, bindings:{add,replace,delete}}` and is an atomic, revisioned update (optimistic concurrency, 409 on conflict).
  - `DELETE` removes it.
  - List/get endpoints exist for credentials and bindings.
  - Credentials are `{name, source:{type:"inline", value (writeOnly)}}`. Inline is the only source type.
  - Refs: `specs/egress-api.yaml:157-310,410-535`.
- [code] Binding scope, `CredentialMatch` (`specs/egress-api.yaml:541-567`; normalization at `components/egress/pkg/credentialvault/vault.go:450-503`):
  - `hosts` is required. Each is an exact host or `*.suffix`; a wildcard cannot target an IP (`vault.go:930-950`).
  - `schemes` is `https|http`, default `[https]`. The port comes from the scheme (443/80); `ports` is deprecated.
  - `methods` defaults to `GET, POST, PUT, PATCH, DELETE`.
  - `paths` defaults to `["/*"]`. Each must start with `/`. A trailing `*` means prefix match; otherwise the match is exact (`components/egress/mitmscripts/system.py:794-797`). Path matching also defends against encoded-path tricks (`system.py:734-782,1167`).
  - Overlapping (ambiguous) bindings are rejected (`vault.go:1090-1170`).
  - **There is no body or query matching.** Scope is host + scheme + method + path prefix, which is enough for the design's host / path-prefix / method.
- [code] Auth types: `bearer`, `basic` (pre-encoded), `apiKey` (header name), `customHeaders`, `passthrough`. Each can also carry `substitutions` that replace a literal placeholder in path, query, header or body (`specs/egress-api.yaml:568-700`).
- [code] Prerequisites checked by the sidecar in `Store.Ready` (`vault.go:348-364`):
  - an egress auth token is set;
  - transparent mitmproxy is on;
  - insecure upstream TLS is off;
  - **mode is `dns+nft`**.
  - Otherwise it returns HTTP 412 Precondition Failed (`policy_server.go:310,336,362`).
- [code] Server-side prerequisites, checked at create: `credentialProxy.enabled` requires `[egress].mode = "dns+nft"` (400 otherwise), and `defaultAction` other than deny only produces a warning (`services/validators.py:553-585`).
- [code] Every binding host must be explicitly allowed by the egress policy. `defaultAction=allow` does not count as coverage (`vault.go:366-373,388-395,970-983`). [doc] OSEP-0012 R6 says the same (`oseps/0012-credential-vault.md:95,1062-1069`). OSEP status: implemented (`oseps/0012-credential-vault.md:7`).
- [code] The egress token is generated per sandbox by the server and exists only in the sidecar env. On Docker it is also stored in a container label (`docker_service.py:806-807`; `services/k8s/create_helpers.py:81-87`). The sidecar compares tokens in constant time (`policy_server.go:786-796`).
  - The sandbox process shares the sidecar's netns, so it can reach `127.0.0.1:18080`. Only the token protects `/policy` and `/credential-vault` from the workload.
  - [unknown — live test] Whether the workload can read the token, for example through the Docker label via a mounted socket; normally it cannot.

## Q9. Network isolation (east-west, and sandbox to server/host)

**Verdict:** OpenSandbox enforces **no** east-west or sandbox-to-server isolation of its own, in either runtime. On Docker the default is `network_mode=host`, where the sandbox shares the host network and can reach the server directly. In bridge mode, sandboxes share Docker's default bridge and host-published execd ports on 0.0.0.0, and **execd has no auth token set by the server**. The only lever is `networkPolicy` deny under `dns+nft`. That blocks *outbound* traffic from that sandbox to non-allowed IPs, but it does not block *inbound* traffic from other sandboxes. The K8s controller creates no NetworkPolicy for sandboxes. **Keep `NETWORK_ISOLATION` operator-declared, as the design doc says.**

### Docker
- [code] Default `docker.network_mode = "host"` (`server/opensandbox_server/config.py:1200-1203`; `server/configuration.md:119`). With host networking the sandbox uses the host's network stack. It can reach the lifecycle server on localhost and every host-local service. `networkPolicy` is then rejected entirely (`networking.py:162-170`).
- [code] Bridge mode:
  - The server never creates a network or sets `enable_icc` (grep of `server/opensandbox_server` finds no `networks.create` or `icc`). Sandboxes and egress sidecars go on Docker's default `bridge` (`networking.py:511`). User-defined networks are rejected when combined with `networkPolicy` (`networking.py:172-185`).
  - Docker's default bridge allows inter-container traffic unless the daemon runs with `icc=false`. That is host Docker config and outside OpenSandbox [doc: Docker default; unknown — live test].
- [code] Host-published ports: execd (44772), the HTTP port (8080) and egress (18080) are published on `docker.publish_host`, default `0.0.0.0` (`config.py:1266-1277`; `docker_service.py:808-813,860-866`; `configuration.md:131`). Any sandbox that can reach the host gateway IP can therefore reach other sandboxes' execd ports.
- [code] **execd authentication:** execd enforces `EXECD_ACCESS_TOKEN` only when that env var is set (`components/execd/pkg/flag/parser.go:31,42,63`; `components/execd/main.go:188`). The server never sets it (grep of `server/opensandbox_server` finds no `EXECD_ACCESS_TOKEN`). `secureAccess` is rejected on Docker (`services/docker/networking.py:193-205`).
  - **Result: a sandbox that can reach a neighbour's execd port can run commands in it.** [code, by composition; unknown — live test to confirm reachability]
- [code] With `networkPolicy` deny + `dns+nft`, the nft `output` chain drops the sandbox's outbound traffic to any IP not in an allow set (`pkg/nftables/manager.go:253-294`). That covers the bridge gateway, other sandboxes and the server, unless a resolv.conf nameserver or upstream IP happens to be one of them (`nameserver.go:26-50`).
  - `ct state established,related accept` (`manager.go:258`) plus an output-only hook means **inbound** connections from a neighbour without a policy still succeed. Isolation is one-directional unless every sandbox has a deny policy.
  - In `dns` mode, IP-literal connections are not filtered at all (`nft.go:49-52`).
- [code] Hardening knobs, server config only: `publish_host` (for example 127.0.0.1) keeps ports off public interfaces (`config.py:1266-1277`). `docker.sandbox_binds` and `sandbox_env` are fleet-wide (`config.py:1283-1300`). There is no per-request option.

### Kubernetes
- [code] The server and controller create **no** NetworkPolicy for sandbox pods. The only NetworkPolicy manifest protects the controller's metrics endpoint (`kubernetes/config/network-policy/allow-metrics-traffic.yaml:1-30`), and it is commented out of the default kustomization (`kubernetes/config/default/kustomization.yaml:30-34`). No `NetworkPolicy` appears in `manifests/charts` (grep).
- [doc] This is a deliberate choice. OSEP-0001 says egress "intentionally avoids depending on Kubernetes NetworkPolicy … All enforcement happens inside a sidecar" (`oseps/0001-fqdn-based-egress-control.md:71`), and it rejects an external NetworkPolicy controller (`oseps/0001-fqdn-based-egress-control.md:1242-1257`). OSEP-0014 expects operators to use per-namespace NetworkPolicy (`oseps/0014-multi-tenancy.md:46,63`).
- [code] execd has no access token in K8s either, unless `secureAccess` is used in ingress-gateway mode (spec `specs/sandbox-lifecycle.yml:1885-1895`; `services/k8s/endpoint_resolver.py:22-36`). Pod-to-pod reach is whatever the CNI and operator NetworkPolicies allow.
- Implication for the provider: advertise `NETWORK_ISOLATION` only when the operator declares it. The seed self-check (§11.3) should probe the server endpoint **and** a neighbour execd port or the host gateway.

## Q10. Security context

**Verdict:** Everything is set by server config (Docker) or by server code plus the operator template file (K8s). **Nothing is per request**, except `extensions["bootstrap.execd.isolation"]="enable"`, which *weakens* isolation (adds SYS_ADMIN and turns off seccomp/AppArmor). There is no per-request `runtimeClassName`, user or securityContext. The server never sets `runAsNonRoot` or `runAsUser`, so non-root depends on the image's `USER` (Docker) or on the operator's pod template (K8s). `automountServiceAccountToken: false` is forced by the server.

### Docker (server config `[docker]`, applied in `_base_host_config_kwargs`, `services/docker/container_ops.py:362-399`)
- [code] Defaults:
  - `drop_capabilities` = AUDIT_WRITE, MKNOD, NET_ADMIN, NET_RAW, SYS_ADMIN, SYS_MODULE, SYS_PTRACE, SYS_TIME, SYS_TTY_CONFIG (`config.py:1215-1230`).
  - `no_new_privileges=true` (`config.py:1237-1240`).
  - `apparmor_profile` and `seccomp_profile` are optional; Docker's defaults apply otherwise (`config.py:1231-1245`).
  - `pids_limit=4096` (`config.py:1278-1282`).
- [code] The OCI runtime comes from server `[secure_runtime] docker_runtime`, for example runsc or kata-runtime (`config.py:1146-1197`; `container_ops.py:394-398`). It is not chosen per request.
- [code] **Non-root / user:** `create_container` sets no `user` (`container_ops.py:429-441`) and the request has no user field (`api/schema.py:459-565`). The container runs as the image's default user, often root. The entrypoint is forced to the execd bootstrap (`container_ops.py:442-443`). How execd drops to a user, if it does, is a Q5 topic. [unknown — live test: `id -u` inside a default-image sandbox]
- [code] With `networkPolicy`, `NET_ADMIN` is also dropped from the main container and granted only to the sidecar (`docker_service.py:845-850`; `networking.py:511-513`).
- [code] Per-request weakening: `extensions["bootstrap.execd.isolation"] == "enable"` adds `SYS_ADMIN`, sets AppArmor and seccomp to `unconfined`, and clears Masked/ReadonlyPaths (`docker_service.py:902-921`; `container_ops.py:425-427`; key `extensions/keys.py:22`). **The provider must never send this extension.** Optionally, reject any profile that tries to set it.
- [code] Other create fields: `resourceLimits` (cpu/memory/gpu) are per request (`container_ops.py:385-393`). `resourceRequests` is ignored on Docker (`specs/sandbox-lifecycle.yml:1799`).

### Kubernetes (BatchSandbox and agent-sandbox providers)
- [code] `automountServiceAccountToken: False` is hard-coded in the runtime pod spec (`services/k8s/batchsandbox_provider.py:227-232`; `services/k8s/agent_sandbox_provider.py:345-350`). The runtime manifest is deep-merged over the operator template with runtime values winning (`services/k8s/template_manager.py:75-106`), so the template cannot turn it back on. A client request cannot either.
- [code] `runtimeClassName` comes from server `[secure_runtime] k8s_runtime_class` only (`batchsandbox_provider.py:116-119,257-258`; `agent_sandbox_provider.py:119,352-353`; `config.py:1163-1170`). There is no request field (`api/schema.py:459-565`; the lifecycle spec has no `runtimeClass`). The operator template can also set it: the validator reads the effective merged value (`batchsandbox_provider.py:306-310`).
  - **Implication:** `RUNTIME_CLASS` cannot be requested per profile. The provider can only advertise the one class the server is configured with, and has no API to discover which one that is. It must be operator-declared, or checked from inside the sandbox (seed). [unknown — live test]
- [code] Main-container `securityContext` from server code:
  - It only drops `NET_ADMIN` when `networkPolicy` is present (`services/k8s/egress_helper.py:66-82`; `services/k8s/provider_common.py:200-203`).
  - The isolation extension adds SYS_ADMIN and sets seccomp/AppArmor to Unconfined (`provider_common.py:205-215`).
  - Nothing else is set: no `runAsNonRoot`, `runAsUser`, `allowPrivilegeEscalation`, `readOnlyRootFilesystem` or capability drop. A grep of `server/opensandbox_server` finds none.
- [code] The operator's BatchSandbox template file (`batchsandbox_template_file`, `config.py`, KubernetesRuntimeConfig) can supply a container `securityContext`. It is merged into the runtime one, with runtime leaves winning on conflicts (`batchsandbox_provider.py:79-96,431-457,489-500`). Pod-level `spec.template.spec.securityContext` from the template passes through the deep merge untouched, because the runtime never sets it (`template_manager.py:92-106`). **Hardening is an operator/template concern, not a request option.**
- [code] The execd init container runs `privileged: True` when `egress.disable_ipv6` is on (the default `true`) and a policy exists (`services/k8s/egress_helper.py:46-63`; `config.py:823,1007-1014`; `provider_common.py:139-141`). The egress sidecar gets `NET_ADMIN` (`egress_helper.py:155-162`). Workload containers are not privileged by server code.
- [code] The controller's pause/resume and snapshot helper pods run as root (`RunAsUser 0`, `RunAsNonRoot false`, `AllowPrivilegeEscalation false`) (`kubernetes/internal/controller/batchsandbox_pause_resume.go:706-716`; `kubernetes/internal/controller/sandboxsnapshot_lifecycle.go:325-330`). Note the comment "sets pod-level runAsNonRoot, so override the inherited value" at `batchsandbox_pause_resume.go:712`: it implies templates with pod-level `runAsNonRoot` are an expected setup.
- Implication for `HARDENED_SECURITY_CONTEXT`:
  - The server **ignores nothing, because there is nothing to request.** Advertise the capability only when the operator declares their template or Docker config hardened. Keep the seed re-check (§11.3): uid != 0, `NoNewPrivs: 1` in `/proc/self/status`, CapEff, and no SA token at `/var/run/secrets/kubernetes.io/serviceaccount`.
  - Docker's defaults already cover no-new-privileges, cap-drop and the pids limit. Non-root depends on the image.

---

Paths are relative to the OpenSandbox repo root (commit 3738975). Labels: [code] = confirmed by code/tests, [doc] = spec/doc/comment only, [unknown] = needs a live test.

## Q12 SDK choice for a Java 21 provider

**Verdict: use direct REST (a thin hand-written client on JDK `HttpClient` + Jackson, covering about 15 endpoints, with DTOs optionally generated from `specs/*.yml`). Keep the Kotlin SDK only as a reference implementation and a test oracle.** The SDK works from Java and covers nearly every call the SPI needs. It still falls short in three places: (1) `run()` blocks the calling thread until the stream ends and buffers output with no byte limit; (2) killing a command needs the execution id that the SDK only exposes through a callback; (3) it brings in Kotlin stdlib, OkHttp 4 and the OpenTelemetry API, none of which aimon-sandbox (Jackson/SLF4J only) uses today.

Evidence:
- There is no Java SDK. `sdks/sandbox/` contains only `csharp go javascript kotlin python` [code: directory listing]. The Kotlin SDK is the JVM SDK. Its own Java E2E suite consumes it from plain Java 17 (`tests/java/build.gradle.kts:25-27,50-52`) [code].
- Maven coordinates: `com.alibaba.opensandbox:sandbox:1.1.0` (`sdks/sandbox/kotlin/gradle.properties:7-8`; `build.gradle.kts:188-190` sets `coordinates(group, project.name, version)`; `docs/releases/1.1.0.yaml:112` names `maven:com.alibaba.opensandbox:sandbox:1.1.0`) [code/doc]. The modules are `sandbox`, `sandbox-api` (generated), `sandbox-bom`, `sandbox-pool-redis` and `code-interpreter` (`settings.gradle.kts:23-27`) [code]. Whether 1.1.0 is actually on Maven Central is [unknown — live check].
- JVM target: `jvmToolchain(8)` with `-Xjvm-default=all` (`sdks/sandbox/kotlin/build.gradle.kts:111-116`), so it runs on Java 21 [code].
- Dependencies: `api(kotlin-stdlib)`, `api(slf4j-api)`, `implementation(okhttp 4.12.0, logging-interceptor, opentelemetry-api 1.51.0)` (`sandbox/build.gradle.kts:17-25`; versions in `gradle/libs.versions.toml:16-20`) [code]. kotlinx-serialization is shaded and relocated into the jar, and the POM dependency on it is stripped (`build.gradle.kts:101-126,130-145`) [code]. There is no Ktor (`useKtor=false`) and no coroutines (`coroutine=false`). The generated client is `kotlin` / `jvm-okhttp4` (`sandbox-api/build.gradle.kts:43-44,68-77`) [code]. The API is fully blocking and has no `suspend` functions (grep finds none) [code].
- The generated API covers `sandbox-lifecycle.yml`, `execd-api.yaml`, `egress-api.yaml` and `diagnostic-api.yml` (`sandbox-api/build.gradle.kts:88-132`) [code].
- API coverage against the SPI:
  - Labels/metadata filter: `SandboxFilter(states, metadata, pageSize, page)`, which is page-number pagination (`domain/models/sandboxes/SandboxModels.kt:64-68`). Used from Java in `tests/java/.../SandboxManagerE2ETest.java:158-171` [code].
  - Renew: the low-level `Sandboxes.renewSandboxExpiration(id, OffsetDateTime)` takes an absolute time (`domain/services/Sandboxes.kt:354-357`). `SandboxManager.renewSandbox(id, Duration)` converts it to `now()+duration` on the client side (`SandboxManager.kt:193-198`) [code].
  - Pause/resume/kill/get/list/endpoint: `Sandboxes.kt:257,265,297-325,337,344,364` [code].
  - Files: `readStream(path, range="bytes=a-b")` (`domain/services/Filesystem.kt:128-133`); `write(List<WriteEntry>)` with `mode/owner/group` (`FilesystemModels.kt:60-65`), which streams an `InputStream` with unknown length (`FilesystemAdapter.kt:196-205`); `readFileInfo(paths) -> EntryInfo` (`Filesystem.kt:283`); `listDirectory(path, depth)` (`Filesystem.kt:223-226`); `search`, `moveFiles`, `deleteFiles/Directories`, `createDirectories` (`Filesystem.kt:194-274`). `EntryInfo` = `path, mode, owner, group, size, modifiedAt: OffsetDateTime, createdAt, type` and has no hash or etag field (`FilesystemModels.kt:35-44`) [code]. Server-side mtime resolution is Q1.
  - Command: `RunCommandRequest(command | argv, background, workingDirectory, timeout, uid, gid, envs, handlers)` (`RunCommandRequest.kt:35-43`). stdout and stderr stay separate (`ExecutionModels.kt:68-69`, handlers `onStdout`/`onStderr` at `:194-205`). `interrupt(executionId)` maps to `DELETE /command` (`Commands.kt:87`, `CommandsAdapter.kt:182-189`, `specs/execd-api.yaml:456-461`). There is also `getCommandStatus`/`getBackgroundCommandLogs` (`Commands.kt:95-104`) [code].
- Mismatches with SPI §6.1 (`RunningCommand.await/kill`, `OutputSink`, `maxCaptureBytes`):
  - `Commands.run()` makes a synchronous OkHttp call and reads the SSE stream to the end before returning (`CommandsAdapter.kt:312-340`, `.execute()` at `:320`). Returning a `RunningCommand` handle would need a dedicated thread per command [code].
  - Output accumulates in `ExecutionLogs` with no size cap. The only option is to turn accumulation off entirely with `skipAccumulation` (`ExecutionModels.kt:226-229`) [code].
  - `kill()` needs the execution id, which only arrives in the `onInit` event (`ExecutionModels.kt:~208-211`), so the provider would have to capture it from a callback on the other thread [code].
  - Endpoint resolution is hidden behind `Sandbox.connect()`/`builder()`, which also run a readiness/health poll (`Sandbox.kt:584-606`, `skipHealthCheck` at `:325-330`). The `Sandboxes`/adapter classes are `internal` (`SandboxesAdapter.kt:88`, `AdapterFactory.kt:46`) and cannot be composed from Java [code].
- Why direct REST fits better:
  - The whole surface is small and documented: lifecycle (create, get, list, delete, pause, resume, renew-expiration, endpoints, metadata patch, networkpolicy) plus execd (`/command` SSE, `DELETE /command`, `/command/status/{id}`, `/files/*`, `/directories*`, `/ping`) (`specs/execd-api.yaml:50-1082`) [doc].
  - SSE parsing is trivial. The SDK's own parser just takes `data:` lines and skips `event:`, `id:`, `retry:` and comment lines (`ExecdEventSupport.kt:32-47`) [code].
  - With JDK `HttpClient` + Jackson we control streaming, byte caps and cancellation (closing the body plus `DELETE /command`) directly, and add no new transitive dependencies.
  - Risk: the spec is `version: 0.1.0` (`specs/sandbox-lifecycle.yml:1-4`) and could drift. Mitigation: pin the OpenSandbox server image in CI and run the contract suite against it (Q13).

## Q13 Running the server in CI

**Verdict: run `opensandbox/server:<tag>` (or `uvx`/`uv run` of `opensandbox-server` from source) with the host Docker socket mounted. This means sibling containers, not DinD. It needs a TOML config with `runtime.type="docker"`, a required `execd_image`, `docker.host_ip` when the server itself is in a container, and either `api_key` set or `OPENSANDBOX_INSECURE_SERVER=YES`. `tests/java` plus `scripts/java-e2e.sh` show the upstream pattern: run from source, SDK from `mavenLocal`.**

Evidence:
- Images: Docker Hub `opensandbox/{server,execd,egress,ingress,controller,...}`, also mirrored to GHCR and an Aliyun registry (`.github/workflows/release-umbrella.yml:128-144,194-205`; `server/build.sh:31-39`) [code].
  - Umbrella release tags look like `release-<VERSION>` (`release-umbrella.yml:202,226`) [code]. Per-component `v`-tags also appear: `opensandbox/execd:v1.1.0`, `opensandbox/egress:v1.1.7` (`server/docker-compose.example.yaml:19-23`) [doc]. `latest` is published by `server/build.sh:37-39` [code].
  - Which tags actually exist on Docker Hub today is [unknown — live check].
- Reference compose (`server/docker-compose.example.yaml:1-81`) [doc]:
  - Server on port 8090 with `/var/run/docker.sock` mounted (`:57-60`), `SANDBOX_CONFIG_PATH=/etc/opensandbox/config.toml` (`:64-65`).
  - `extra_hosts: host.docker.internal:host-gateway` and `[docker] host_ip="host.docker.internal"` (`:29-31,55-56`), plus `[proxy] resolve_internal=false` (`:8-12`), because sandboxes land on the default bridge network and are reached via host-published ports.
  - Hardening knobs sit in server config: `drop_capabilities`, `no_new_privileges`, `pids_limit` (`:37-42`).
- Config:
  - `SANDBOX_CONFIG_PATH`, default `~/.sandbox.toml` (`server/opensandbox_server/config.py:52-53`); API port defaults to 8080 (`config.py:531-535`); `runtime.execd_image` is required, `Field(...)` (`config.py:1122-1127`) [code].
  - Startup refuses an empty `server.api_key` in non-interactive mode unless `OPENSANDBOX_INSECURE_SERVER=YES` (`startup_guard.py:22,55-115`) [code].
  - CLI entry point: `opensandbox-server` (`server/pyproject.toml:71-72`) [code].
- Upstream Java E2E (`.github/workflows/real-e2e.yml:262-380`, config at `:331-349`) [code]:
  - Runs on a `self-hosted` runner.
  - Config: `runtime.type=docker`, `execd_image=opensandbox/execd:local`, `egress.image=opensandbox/egress:local`, `egress.mode="dns+nft"`, `docker.network_mode="bridge"`, `storage.allowed_host_paths`.
  - `scripts/java-e2e.sh:39-92`: build execd from source, pull `opensandbox/code-interpreter:${TAG}`, pre-create a named volume, `uv run python -m opensandbox_server.main` with `OPENSANDBOX_INSECURE_SERVER=YES`, `sleep 10`, `publishToMavenLocal` the SDK, `./gradlew test`.
  - The Python E2E additionally enables `[renew_intent] enabled=true` (`real-e2e.yml:102-104`) [code].
  - Cleanup keys on the container label `opensandbox` (`real-e2e.yml:54,124`), which is useful for our CI teardown [code].
- Reusable harness: `tests/java/src/test/java/com/alibaba/opensandbox/e2e/BaseE2ETest.java` reads the domain from `opensandbox.test.domain` or an env var, default `localhost:8080` (`:41-43,66-113`) [code]. It is SDK-based, so we would reuse its setup ideas (config, images, volume seeding) rather than its classes. The Go/Python/JS suites under `tests/` cover the same scenarios (volume, credential vault, streaming timeout) and are good sources of expected behaviour [code: file names].
- DinD / privileges:
  - The server drives the host daemon via `docker.from_env` / `DOCKER_HOST` (`services/docker/docker_service.py:156-180`), so a socket mount is enough and DinD is not required [code].
  - The egress sidecar is created with `cap_add: ["NET_ADMIN"]` (`services/docker/networking.py:512`), and `dns+nft` needs nftables inside it. This works on a normal Linux runner (GitHub-hosted `ubuntu-latest` should be fine). On Docker Desktop (macOS) and rootless Docker it is [unknown — live test].
  - Sandboxes publish execd on host ports from `port_range_min..max` (compose `:32-33`), so the test JVM must reach the Docker host's published ports.

## Q14 Contradictions with / gaps in design §6 (and §9, §11.3, §13.3, §20)

**Verdict: the §6 SPI shape survives. The provider needs these corrections: per-sandbox endpoint resolution with returned headers (re-resolved after resume); the `OPEN-SANDBOX-API-KEY` header; `/v1` base; `202` create returning `Running`; eight state names; a mixed error model (`{code,message}` plus FastAPI's default 422 body); no duplicate detection (409 on create is not idempotency). Security finding: in the Docker runtime, execd has no access token, and in single-tenant mode the server proxy path skips API-key auth.**

1. **Base path / version.** Routers are mounted both at `/` and at `/v1` (`server/opensandbox_server/main.py:247-258`); the spec server is `http://localhost:8080/v1` (`specs/sandbox-lifecycle.yml:39-40`); the spec version is `0.1.0` (`:1-4`) [code/doc]. Use `/v1`.
2. **Auth header.** `OPEN-SANDBOX-API-KEY` (`middleware/auth.py:37,89`; spec `:1095-1099`). Exempt paths are `/health`, `/version`, `/docs`, `/redoc`, `/openapi.json` (`auth.py:49`). If no API key is configured, auth is skipped entirely (`auth.py:85-87`) [code]. The SDK env var is `OPEN_SANDBOX_API_KEY` (spec `:33-35`) [doc]. Design §13.2 already uses `api-key: ${OPEN_SANDBOX_API_KEY}`, which is consistent.
3. **State names.** `Pending, Running, Pausing, Paused, Resuming, Stopping, Terminated, Failed`, plus the documented transitions (`sandbox-lifecycle.yml:1514-1531`) [doc]. §6 `ProviderSandbox.state` must map all eight, and treat `Pausing`/`Resuming`/`Stopping` as transient. Pause and resume are asynchronous and callers must poll GET (`:800-851`) [doc]. Docker returns 409 `DOCKER::SANDBOX_NOT_RUNNING` when pausing a non-running or paused sandbox, and 409 `DOCKER::SANDBOX_NOT_PAUSED` when resuming a sandbox that is not paused (`services/docker/docker_service.py:1142-1153,1236-1247`) [code]. So `pause`/`resume` are **not** idempotent. The provider should treat "already Paused" / "already Running" 409s as success after a status check.
4. **Create semantics / idempotency.**
   - `POST /sandboxes` returns **202** with `status.state: "Running"` ("provisioning completed synchronously") (`api/lifecycle.py:70-75`; spec `:443-450`) [code/doc].
   - The request has no client-supplied id, name or idempotency key. Fields are `image, snapshotId, platform, timeout, resourceLimits, resourceRequests, env, metadata, lifecycle, entrypoint, networkPolicy, credentialProxy, secureAccess, volumes, extensions, templateId` (`api/schema.py:461-565`), and the id is a server UUID4 (`services/sandbox_service.py:57-62`) [code]. This confirms §6.3: idempotency is best-effort label lookup.
   - **409 on create is not a duplicate signal.** The only create-path 409 found in K8s is a PVC owned by another sandbox (`services/k8s/kubernetes_service.py:919-948`) [code]. Other 403/429 responses exist: K8s quota `KUBERNETES::QUOTA_EXCEEDED` (spec `:466-480`) and pool exhaustion 429 with `Retry-After` (`:488-497`) [doc].
5. **Endpoint resolution is mandatory to reach execd** (§6.1 `connect(ref)` must do it).
   - Call `GET /v1/sandboxes/{id}/endpoints/{port}`, returning `{endpoint, headers?}`. The endpoint is **scheme-less** (`host:port[/path]`), and "requests targeting the sandbox must include the corresponding header(s)" (spec `:892-955,1979-1997`) [doc]. The SDK prepends `protocol://` and adds `headers` to every execd call (`CommandsAdapter.kt:118-128`) [code].
   - The execd port is `44772` (`domain/models/execd/Constants.kt:19`; spec `execd-api.yaml:26-27`) [code].
   - Docker returns `{host}:{hostPortOf44772}/proxy/{port}`, including for port 44772 itself (`services/docker/networking.py:313-338`; test `server/tests/test_docker_endpoint.py:155-157,340-342`), and `{host}:{hostPort}` for 8080 [code].
   - K8s gateway mode returns `{id}-{port}.{base}`, `{address}/{id}/{port}`, or a header `OpenSandbox-Ingress-To: {id}-{port}` (`services/helpers.py:212-257`, header constant `services/constants.py:35`) [code]. Signed routes (`expires`) are K8s only; Docker returns 400 (`networking.py:236-246`) [code].
   - Alternative: `use_server_proxy=true`, which routes through `/v1/sandboxes/{id}/proxy/{port}/...` on the server (`api/proxy.py:678-730`) [code]. This helps when AIMON nodes cannot reach sandbox networks.
   - **Re-resolve after resume.** The SDK says the execd endpoint "may change across pause/resume on some backends" and re-resolves (`Sandbox.kt:609-621`) [doc/code]. §6.1 `SandboxConnection` must not cache an endpoint across pause/resume.
6. **execd auth (security; affects §6.4 NETWORK_ISOLATION and §11.3 seed check).**
   - execd accepts `X-EXECD-ACCESS-TOKEN` (`components/execd/pkg/web/model/header.go:18`, spec `execd-api.yaml:1836-1843`). With no token configured it lets every request through (`components/execd/pkg/web/router.go:206-209`) [code]. No code in `server/` or `kubernetes/` sets `EXECD_ACCESS_TOKEN` or calls `/internal/init` (grep finds nothing) [code]. So execd is unauthenticated by default.
   - `secureAccess` (header `OpenSandbox-Secure-Access`) is "currently supported only for Kubernetes sandboxes exposed through ingress gateway mode" (`api/schema.py:543-551`), and Docker refuses it (`networking.py:193-200`) [code].
   - In single-tenant mode, the server's `/sandboxes/{id}/proxy/{port}/...` path **skips API-key auth** (`middleware/auth.py:53,82-83`) [code]. Anyone who can reach the server and knows a sandbox UUID can reach its execd, unless `secureAccess` applies.
   - Docker publishes sandbox ports on all host interfaces unless `docker.publish_host` is set (`server/docker-compose.example.yaml:34-36`) [doc].
   - Consequence: the §11.3 seed self-check ("connecting to the OpenSandbox server endpoint must fail") should also cover the Docker host's published execd port range. `network-isolation: declared` in the Docker runtime needs host-level controls. Whether one sandbox can reach another's host port is [unknown — live test].
7. **Error model.**
   - `HTTPException` bodies are flattened to `{code, message}` (`main.py:280-290`; spec `ErrorResponse` `:1963-1978`) [code]. Codes are runtime-prefixed, for example `DOCKER::SANDBOX_NOT_FOUND` and `KUBERNETES::SANDBOX_NOT_FOUND` (`services/constants.py:112-140`) [code]. Classify by HTTP status and treat `code` only as a hint.
   - There is **no `RequestValidationError` handler**, so body/schema validation failures come back as FastAPI's default **422 `{"detail":[...]}`**, not `ErrorResponse` (no handler in `server/opensandbox_server`; `server/tests/test_metrics_api.py:141-147` asserts 422) [code]. The provider must map 400 and 422 to PERMANENT.
   - execd errors have a different shape, for example `{"error": "..."}` on 401 (`router.go:221-225`) [code].
   - Every response carries `X-Request-ID` (spec `:1150-1200`) [doc]. Log it.
8. **Renew/expiry notes relevant to §6.1 `extendExpiry(until)`.** The API takes an absolute `expiresAt`. The SDK's `Duration` helper computes `now+d` on the client (`SandboxManager.kt:193-198`) [code]. Docker returns 409 `DOCKER::INVALID_EXPIRATION` for a sandbox created without automatic expiration (`docker_service.py:1333-1345`) [code]. Error code `EXPIRATION_NOT_EXTENDED` exists (`services/constants.py:122`) [code]. Forward-only details are covered in Q3.
9. **Egress mode discoverability (§6.4 says "the client cannot know the mode").** `GET /v1/sandboxes/{id}/networkpolicy` returns `PolicyStatusResponse{status, mode, enforcementMode?}`, where `enforcementMode` is the "optional sidecar enforcement backend" (`sandbox-lifecycle.yml:54-60,2001-2018`) [doc]. It is per sandbox and optional, so it can serve as a post-create check but not as a capability probe before create. Whether it reports `dns+nft` is [unknown — live test].
10. **Shared volume ownership (§6 volume paragraph, §6.4 SHARED_VOLUME).** An auto-created PVC or Docker volume is left alone on sandbox deletion **unless** `deleteOnSandboxTermination=true` (default `false`, `api/schema.py:256-264`; Docker `services/docker/volumes.py:83-84`; K8s `kubernetes_service.py:815-819`) [code]. A PVC created with that flag is owned by the first sandbox, and any other sandbox mounting it gets **409** (`kubernetes_service.py:919-948`) [code]. The provider must never set `deleteOnSandboxTermination` for workspace volumes, which is consistent with the design's separate `VolumeReclaimer`.
11. **§9 assumption check (one line; details in Q5).** execd starts the shell as `bash --noprofile --norc -c <code>`, with the command in argv (`components/execd/pkg/runtime/command.go:90-100`), in its own process group (`Setpgid: true`, `:241-247`) [code]. So §9's "the exec script itself is one argument (MAX_ARG_STRLEN 128 KiB)" holds. `/command` also accepts `argv` (native, no shell) (`execd-api.yaml:401-410`) [doc].
12. **§13.3 "execd uid / runAsUser 1000".** `CreateSandboxRequest` has no user, securityContext or runAsUser field (`api/schema.py:461-565`; grep finds none in `services/docker`, `services/k8s`, `api/schema.py`) [code]. K8s only merges a *server-side template's* container `securityContext` (`services/k8s/batchsandbox_provider.py:79-93,431-451`) [code]. So `runAsUser: 1000` cannot be requested per sandbox; it must come from the image `USER` or the operator's pod template. Details are in Q10.
