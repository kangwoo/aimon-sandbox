# B — OpenSandbox spike, Docker runtime (live)

Date: 2026-09-30 (UTC 23:31–23:53 on 09-29). Machine: macOS arm64, Docker Desktop 29.2.1 (linux/arm64 VM), uv 0.8.4.
Everything is under `spike/docker/`: `config.toml`, `start-server.sh`, `probes/*.py|sh` (raw REST via httpx, no SDK;
run with `uv run --with httpx python probes/<x>.py`), and `evidence/*.txt` (full raw output of each probe).

## 0. Setup (what was run)

| item | value |
|---|---|
| server | from source, `OpenSandbox` commit `3738975fc7b1da6875694f912b0422fe5d622064` (2026-09-29, "fix(kubernetes): fixed license header rule"), shallow clone so the package version falls back to `0.1.0.dev0`; `uv sync` + `uv run opensandbox-server`, `SANDBOX_CONFIG_PATH=spike/docker/config.toml` |
| execd image | `opensandbox/execd:v1.1.0` @ `sha256:6cf7dba2f21f0b536e100563d841ac58a9f31c2b0a081b7ac76796a24d6f47e2` (built 2026-08-28, arm64). The execd Go source at HEAD is newer than this image, so the behaviour below is the image's, not HEAD's |
| egress image | `opensandbox/egress:v1.1.7` @ `sha256:db7345d567b0970f384b8e3fa7a93a71b7f43d4b16bb2009de34096e9a87b3b5` (built 2026-08-24) |
| sandbox image | `spike-docker-base:1` = `debian:bookworm-slim` + curl, procps, util-linux, iproute2, dnsutils, netcat, libcap2-bin (`spike/docker/image/Dockerfile`), entrypoint `["sleep","infinity"]` |
| Hub tags seen | execd `v1.1.0` (latest v-tag), `release-1.1.0` (09-21), `release-1.1.1-rc.1` (09-28), `latest`; egress `v1.1.7` latest v-tag. The docs and example configs pin `execd:v1.1.0` / `egress:v1.1.7`, so those were used |

`config.toml` (the only change between runs is `[egress].mode`: `dns` for probes a–i and f(dns), then `dns+nft` for f(nft)):

```toml
[server]
host = "0.0.0.0"
port = 8090
api_key = "spike-docker-key-1234567890"
max_sandbox_timeout_seconds = 3600
[proxy]
resolve_internal = false        # macOS: the host cannot route to bridge IPs; use host-published ports
[log]
level = "INFO"
[runtime]
type = "docker"
execd_image = "opensandbox/execd:v1.1.0"
[storage]
allowed_host_paths = []
[store]
type = "sqlite"
path = ".../spike/docker/state/opensandbox.db"
[docker]
network_mode = "bridge"
port_range_min = 41000
port_range_max = 41999
drop_capabilities = ["AUDIT_WRITE","MKNOD","NET_ADMIN","NET_RAW","SYS_ADMIN","SYS_MODULE","SYS_PTRACE","SYS_TIME","SYS_TTY_CONFIG"]
no_new_privileges = true
apparmor_profile = ""
pids_limit = 4096
seccomp_profile = ""
[ingress]
mode = "direct"
[egress]
image = "opensandbox/egress:v1.1.7"
mode = "dns"                    # later "dns+nft"
readiness_timeout_seconds = 30.0
```

Timings: server start → `/health` 200 in about 1 s. Create (image already local) 0.5–1.0 s without `networkPolicy`, about 1.9 s with one (sidecar).
Containers are named `sandbox-<uuid>` and `sandbox-egress-<uuid>` by the server. It does not let the caller choose these names, so the `spike-docker-` prefix applies only to the image and volumes created here.

---

## a. Lifecycle, labels, list, idempotency

**Verdict: PASS — the §6.3 labels work as designed. Base32 values are accepted in both cases and filtering is case-sensitive, so always send lower case. Invalid labels get a clean 400. The metadata filter must be ONE `metadata=` parameter; if it is repeated, only the last one is used. The server has no idempotency. Deleted and expired sandboxes disappear completely, with no Terminated state.**

Evidence (`evidence/a.txt`, `probes/a_lifecycle.py`):

```
POST /v1/sandboxes {metadata: aimon.at/managed=true, aimon.at/sandbox-key=2o7l2id7upeaxxhvhsnjnkv5h5zd6bcg,
                    aimon.at/generation=3, aimon.at/deployment=spike-docker, spike-run=r…} -> 202, metadata echoed verbatim
upper-case 2O7L2ID7UPEAXXHVHSNJNKV5H5ZD6BCG -> 202 (accepted)
value 'ws:abc'  -> 400 {"code":"SANDBOX::INVALID_METADATA_LABEL","message":"Metadata value 'ws:abc' is invalid: must be 63 characters or less, start/end with an alphanumeric character, and contain only alphanumeric, '-', '_', or '.' characters."}
value 'dep/ws/1' -> 400 same code;  64×'a' -> 400 same;  '-abc' -> 400 same
63×'a' -> 202;  empty value '' -> 202 (accepted)
key 'opensandbox.io/id' -> 400 "...uses the reserved prefix 'opensandbox.io/'..."
key 64×'k' -> 400 "Metadata key ... is invalid: must be either a name or a DNS-subdomain prefix and name separated by /..."
same labels twice -> two different ids (81262d8a… vs 6d0f8dc1…)  => no server-side idempotency
GET /sandboxes?metadata=<urlencode("aimon.at/sandbox-key=…&spike-run=…")>  -> both sandboxes (AND works)
   second label not matching -> {"items":[],"pagination":{"page":1,"pageSize":20,"totalItems":0,...}}
repeated params ?metadata=aimon.at/managed=true&metadata=spike-run=… -> 5 items = only the LAST filter applied
filter with the upper-case value -> only the upper-case sandbox (case-sensitive match)
pagination pageSize=2: 4 pages, 8/8 unique, pages 5,6 empty with hasNextPage=false; order = newest first
pageSize=1000 -> 422 "Input should be less than or equal to 200";  page=0 -> 422 (ge 1)
DELETE -> 204; then GET -> 404 {"code":"DOCKER::SANDBOX_NOT_FOUND",...}; DELETE again -> 404; DELETE never-existed -> 404
list after DELETE: absent; ?state=Terminated -> []
PATCH /sandboxes/{id}/metadata {"aimon.at/generation":"4"} -> 200, GET shows generation=4 (stored in the server store; Docker labels are immutable)
no key -> 401 MISSING_API_KEY; wrong key -> 401 INVALID_API_KEY
```

Notes for the provider:
- The metadata filter is double-encoded: build `k=v&k=v` with url-encoded k and v, then url-encode that whole string as the single `metadata` value. Sending it single-encoded also worked.
- `destroy` must treat 404 `DOCKER::SANDBOX_NOT_FOUND` as success. The error code has a runtime prefix (`DOCKER::`), so match on HTTP status, not on the code.
- The list is newest-first by page/offset with no cursor. A create during pagination shifts the pages, so reconciliation should tolerate duplicates and misses and re-list.
- Metadata is mutable through PATCH. Anyone holding the API key can rewrite `aimon.at/*`. This does not affect label verification (§6.3), but label values are not tamper-proof.

## b. Expiry

**Verdict: PARTIAL. Create is capped by `max_sandbox_timeout_seconds` and expiry fires on time. However, renew-expiration accepts ANY future time, both backwards and beyond the max. "Forward only" and "≤ maxExpiry" must be enforced client-side. At expiry the container is deleted: the id then returns 404 and is gone from the list.**

Evidence (`evidence/b.txt`, `evidence/b9_restart.txt`):

```
create timeout=60 -> expiresAt - createdAt = 60.0s
renew +60s                         -> 200 {"expiresAt":"2026-09-29T23:36:51.703446Z"}
renew BACKWARD (-30s, still future) -> 200 {"expiresAt":"2026-09-29T23:36:21.703446Z"}   (accepted!)
renew to the past                   -> 400 {"code":"DOCKER::INVALID_EXPIRATION","message":"New expiration time must be in the future."}
renew now+5h (max is 3600s)         -> 200 {"expiresAt":"2026-09-30T04:34:52.231015Z"}   (NOT capped)
renew with +09:00 offset, no fraction -> 200 normalised to Z
create timeout=3601 -> 400 {"code":"SANDBOX::INVALID_PARAMETER","message":"Sandbox timeout 3601s exceeds configured maximum of 3600s."}
create timeout=59   -> 422 (ge 60)
create without timeout while max is configured -> 202, no expiresAt  (a max does not force a timeout)
expiry watch:  t-exp=-88.9s Running/inList  ->  t-exp=+1.0s  GET 404, not in list, `docker ps -a` empty
DELETE after expiry -> 404;  renew after expiry -> 404
server DOWN across expiry (b9): created timeout=60 at 23:47:38, killed server, container still Up at 23:48:58;
   restart -> log "Sandbox e7f61e35… already expired; terminating now." -> container gone, GET 404
```

Source check (`services/docker/docker_service.py::renew_expiration`) confirms this. It only calls `ensure_future_expiration`. Expiry is an in-process `threading.Timer` per sandbox, and `_restore_existing_sandboxes` rebuilds the timers at startup from the persisted store or the `opensandbox.io/expires-at` container label.
- `ProviderCapabilities.maxExpiry()` cannot be read from the API. It must be configured on the AIMON side to mirror the server's `max_sandbox_timeout_seconds`.
- `extendExpiry` must read the current `expiresAt` and refuse a backward move itself. The server will happily shorten the lifetime.
- Expiry = hard delete. Nothing is left to inspect. This matches `status()` returning empty.

## c. Pause / resume (Docker)

**Verdict: PAUSE_RESUME works (`docker pause`, a cgroup freeze). Processes and files survive and the processes freeze. The EXPIRY CLOCK KEEPS RUNNING while paused: a paused sandbox is deleted at its `expiresAt`. Renew works while paused. Exec and files calls while paused HANG (TCP accepted, no response) instead of failing.**

Evidence (`evidence/c.txt`):

```
POST /pause -> 202; state Paused; docker: "Up 3 seconds (Paused)"
execd /command while paused     -> ReadTimeout after 8.0s   (no error response; docker-proxy accepts the TCP connection)
execd /files/info while paused  -> ReadTimeout after 8.0s
GET /endpoints/44772 while paused -> 200 (endpoint still handed out)
renew while paused -> 200
pause when paused  -> 409 {"code":"DOCKER::SANDBOX_NOT_RUNNING","message":"Sandbox is not in a running state."}
resume -> 202 -> Running; background counter (1/s) went 5 -> 8 across a ~20 s pause (frozen, then continues); /tmp/state intact
resume when running -> 409 {"code":"DOCKER::SANDBOX_NOT_PAUSED",...}
c4: create timeout=60, pause at once, wait:  t-exp=-59.5s Paused  ->  t-exp=+1.5s  404, container removed
```

Implication: the provider's `pause()` does not stop the expiry clock. §10 must either renew before pausing (to `pausedUntil + slack`) or accept that a paused workspace dies at `terminateAfter`. Every execd call needs a client-side timeout. The provider should check `status()` = Paused before `run`/`files` and not wait for a hang. With an egress sidecar the server pauses the sidecar too (source: `pause_sandbox`); that path was not exercised live.

## d. execd `/command`

**Verdict: EXEC is usable, with sharp edges. (1) stdout and stderr are separate but not ordered relative to each other. (2) Output is line-framed and NOT byte-exact. (3) The command is `bash -c <cmd>` as ONE argv, so the command and each env value are limited to 128 KiB (MAX_ARG_STRLEN). (4) Interrupt and timeout kill the process group, but `setsid` children survive. (5) Client disconnect does NOT kill the command. (6) There is no output cap. (7) Commands run as root. (8) Output is spooled to files in the sandbox's /tmp.**

Evidence (`evidence/d.txt`, `evidence/d_uid.txt`, `evidence/d10b.txt`). The SSE body is JSON objects separated by blank lines:

```
{"type":"init","text":"<commandId>",...}  {"type":"ping","text":"pong"}  {"type":"stdout","text":"out1"} ...
exit 7 -> {"type":"error","error":{"ename":"CommandExecError","evalue":"7","traceback":["exit status 7"]}}
exit 0 -> {"type":"execution_complete","execution_time":2}
killed -> {"type":"error","error":{"ename":"CommandExecError","evalue":"-1","traceback":["signal: terminated"|"signal: killed"]}}
```

| check | observed |
|---|---|
| separation | `echo out1; echo err1 >&2; echo out2` → stdout `out1\nout2\n`, stderr `err1\n` — PASS |
| ordering | `o1 e1 o2 e2 o3 e3` arrives as `o1 o2 o3 e1 e2 e3`: each stream is tailed from its own file |
| fidelity | one event per line with the `\n` stripped. `printf 'no-newline'; printf 'A\r\nB\n\n\nC'` → events `"no-newlineA"`, `"B"`, `"\n"`, `"\n"`, `"C\u0000�"`. `\r` lost, blank lines ambiguous, a missing final newline is not visible, 0xff → U+FFFD. A 200 000-byte line arrives as 1 event |
| exit code | from `error.evalue` (string), `-1` for signals |
| cwd default | `/` (execd's cwd) |
| cwd missing | HTTP **400** `{"code":"INVALID_REQUEST_BODY","message":"invalid request, validation error working directory does not exist: /does/not/exist: ..."}` before any SSE (no fallback) |
| cwd expansion | `cwd:"$HOME"` → `/root` (env-expanded) |
| env | `envs` merged over the base env (`EXECD_ENVS=/opt/opensandbox/.env`, `HOME=/root`, `OPENSANDBOX_ID`, `PATH`); PATH overridable; multi-line values OK. A 200 KB value → `fork/exec /usr/bin/bash: argument list too long` (as an SSE `error`, HTTP 200) |
| transport | `/proc/$$/cmdline` = `bash\|-c\|<whole command>\|`; stdin = `/dev/null`; ppid = execd (pid 20); own pgid; sid 1 (shares bootstrap's session) |
| body size | 64 KiB OK; 127 KiB OK; **129 KiB → `argument list too long`**; 1 MiB and 8 MiB → same (HTTP 200 + SSE error, 1.3 s / 2.9 s). No HTTP body limit hit before the exec limit |
| interrupt `DELETE /command?id=<init id>` | 200 in 0.08 s. Before: `sleep 700` (pgid 59), `sleep 701 &` (pgid 59), `nohup sleep 703 &` (pgid 59), `setsid sleep 702` (pgid 61, sid 61). After: **only `setsid sleep 702` survives**. Stream ends with `error … "signal: terminated"`; `/command/status/{id}` → `running:false, exit_code:-1` |
| client disconnect | closing the SSE stream mid-command leaves `sleep 800/801/802` all running; status `running:true`. **Disconnect ≠ kill** (execd v1.1.0) |
| `timeout` (ms) | `timeout:2000` → SIGKILL to the group at 2 s (`signal: killed`); `setsid sleep 902` survives |
| `cmd &` child | the call returns when bash exits (1.0 s) even if a `&` child keeps stdout; output is spooled to files, so no pipe is held |
| background | `background:true` → SSE `init` + `execution_complete` at once; `GET /command/status/{id}` → `{"running":false,"exit_code":4,"error":"exit status 4",...}`; `GET /command/{id}/logs` → stdout+stderr **merged** (`bg-start\nbg-err\nbg-end\n`), header `EXECD-COMMANDS-TAIL-CURSOR: 23`; `?cursor=1` returned `g-start…`, i.e. the cursor behaves as a **byte** offset, not a line index as the spec says |
| 50 MB stdout | fully delivered (50 000 001 chars, 495 050 events, 76.7 MB of SSE), no truncation and no server cap. The 295 s wall time was my Python client (quadratic line buffer). Raw curl: 10 MB as 100-byte lines 2.3 s (100 000 events, 15.4 MB SSE), as 1 line 1.3 s, as 64 KiB lines 1.1 s |
| spooling | execd writes `<cmdId>.stdout/.stderr` (fg) and `<cmdId>.output` (bg) into the sandbox **/tmp**; the 50 MB run left /tmp at 49 MB used; stale 0–34-byte files from killed and disconnected commands remained |
| uid | commands run as `uid=0(root)`; execd is root (pid 20, child of `/bin/sh /opt/opensandbox/bootstrap.sh`). `"uid":1000,"gid":1000` works (`uid=1000 gid=1000`) |
| files-API owner | an upload lands as `root 0 755` (default mode **755**); a uid-1000 command then gets `Permission denied` appending to it — confirms the §6.1 decision to run commands as execd's uid |

Implications for §9 and the provider:
- The §9 wrapper design fits: commands are data, output goes to files with a `head -c` cap, and a trailer. Keep the "≤ 64 KiB inline, else upload `run.cmd`" rule; the hard wall is 128 KiB per argv string, and envs count too.
- Do NOT derive truncation from received SSE byte counts. The framing is lossy (`\r`, trailing newline, blank lines). Compare the trailer's `out=`/`err=` against the file sizes, or read `run.out`/`run.err` via `/files/download` when byte-exact output matters.
- `kill()` = `DELETE /command?id=<init.text>`. It hits the process group only, and `setsid`/daemonised children escape. The per-run watchdog (`kill -KILL 0`) has the same gap. Losing the connection does not stop the command, so `await(timeout)` must call DELETE explicitly.
- The provider must enforce `maxCaptureBytes` itself. The in-sandbox `head -c` is the only cap, since execd has none and spools everything to the sandbox's /tmp (disk-fill risk).

## e. Files API

**Verdict: FILES works. stat carries nanosecond `modified_at` (a same-size rewrite 18 ms apart is visible), so mtime alone satisfies the §6.1 change-detection contract. No hash or etag is exposed. `list` returns full stat entries including `type`. Move never overwrites. Missing-file errors are 404 with `FILE_NOT_FOUND`, but deletes of missing paths return 200.**

Evidence (`evidence/e.txt`):

```
GET /files/info?path=/work/a/one.txt -> {"/work/a/one.txt":{"path":…,"type":"file","size":2,"modified_at":"2026-09-29T23:43:46.891791008Z",
                                        "created_at":…,"owner":"root","group":"root","mode":644}}
upload "aaaa" then "bbbb" (same size, same second): modified_at .971791009Z -> .989791009Z  (PASS; kernel stat agrees)
stat keys = created_at, group, mode, modified_at, owner, path, size, type  (no hash/etag)
download headers: accept-ranges: bytes, content-disposition, content-length, last-modified (1 s resolution), NO etag
multi-path stat with one missing -> 404 for the whole request
stat of a dir -> type "directory"; stat of a symlink -> type "symlink" (lstat, not followed)
upload into non-existent parents -> 200 (parents created, 755); default file mode 755; metadata {"mode":600,"owner":"nobody","group":"nogroup"} honoured
upload to /proc/forbidden -> 500 RUNTIME_ERROR;  mkdir -p via POST /directories -> 200 (idempotent); mkdir over a file -> 500
GET /directories/list?path=/work            -> immediate children, each a full FileInfo incl. "type" (file|directory|symlink), lexical order
GET /directories/list?path=/work&depth=3    -> descendants in pre-order
list of a missing dir -> 404 FILE_NOT_FOUND; list of a file -> 400 "path is not a directory"
list of 5000 entries -> 200, 5000 items, 924 KB, 0.05 s (no limit/paging parameter)
GET /files/search?path=/work (default "**") and pattern "*.txt" -> recursive, FILES ONLY (+symlinks), no directories
POST /files/mv onto existing file   -> 500 {"code":"RUNTIME_ERROR","message":"error accessing file: destination path already exists: /work/dst.txt"}
mv onto an existing empty directory -> 500 same (no "move into dir" semantics)
mv missing src -> 404 FILE_NOT_FOUND;  mv into a missing parent dir -> 200 (parents created, contradicting the spec's "Target directory must exist")
mv a directory -> 200
download missing -> 404; stat missing -> 404
DELETE /files?path=missing -> 200;  DELETE /directories?path=missing -> 200 (idempotent)
DELETE /files on a directory -> 500 "path is a directory";  GET /files/download on a directory -> connection closed with no response (transport error)
relative path "work/m.txt" -> resolved against execd cwd "/" (200)
Range bytes=10-19 -> 206 "bytes 10-19/1024"; bytes=1000- -> 206; beyond EOF -> 416; multi-range -> 206 with only the first range
line mode ?offset=2&limit=2 -> "l2\nl3"
50 MiB random upload 0.3 s + download 0.1 s, byte-identical
POST /files/replace?verbose=true -> {"/work/lines.txt":{"replacedCount":1}}
```

Upload gotcha: `metadata` must be a **file part** with a filename (`form.File["metadata"]`). Sending it as a plain form field gives 400 `{"code":"INVALID_FILE_METADATA","message":"metadata file is missing"}`.
Provider mapping: `move(overwrite=true)` needs delete-then-move, which is not atomic, or `mv -f` through `/command`. `write` should pass `mode` explicitly, otherwise files become 755. Map a 500 whose message says "already exists" to `FileAlreadyExistsException`, but that means parsing the message, since the code is only `RUNTIME_ERROR`. Do not call download on directories: stat first.

## f. Network isolation (Docker runtime)

**Verdict: without `networkPolicy` there is no isolation at all. With `dns` mode, IP egress is NOT blocked. With `dns+nft`, egress is blocked well, but INBOUND east-west is never blocked. Any sandbox can run root commands in any other sandbox on the bridge through its unauthenticated execd. NETWORK_ISOLATION cannot be advertised on the stock Docker runtime.**

Evidence (`evidence/f_dns.txt`, `evidence/f_nft.txt`, `evidence/f_nft_inbound.txt`). Host LAN IP 192.168.20.15, `host.docker.internal` = 192.168.65.254, bridge 172.17.0.0/16:

| from inside sandbox → | no policy | deny+[] `dns` | deny+[] `dns+nft` | deny+[example.com] `dns+nft` |
|---|---|---|---|---|
| server `host.docker.internal:8090/health` | 200 | DNS fail | DNS fail | DNS fail |
| server by host IP `192.168.20.15:8090` | 200 | **200** | timeout | timeout |
| server API w/o key | 401 (reachable) | DNS fail | DNS fail | DNS fail |
| peer execd by container IP `172.17.0.3:44772` | 200 | **200** | timeout | timeout |
| peer execd via host-published port | 200 | **200** | timeout | timeout |
| run a command in the peer (`POST /command`) | **LATERAL-EXEC-OK** | **LATERAL-EXEC-OK** | no | no |
| `https://example.com` | 200 | DNS fail | DNS fail | 200 |
| `https://1.1.1.1` / `http://1.1.1.1` (by IP) | 301 | **301** | timeout | timeout |
| `dig @8.8.8.8 example.com` | answers | no answer | no answer | answers (DNS is intercepted) |
| `ip route add` (NET_ADMIN) | – | not permitted | not permitted | not permitted |

```
GET /sandboxes/{id}/networkpolicy -> {"status":"ok","mode":"deny_all","enforcementMode":"dns+nft","policy":{"egress":[],"defaultAction":"deny"}}
sandbox container NetworkMode = container:<sidecar>; sidecar /sandbox-egress-<id>, image egress:v1.1.7, CapAdd ["NET_ADMIN"],
   label opensandbox.io/egress-sidecar-for=<id>, publishes 18080 (egress API), 44772 (execd), 8080 on 0.0.0.0
egress API on its host port without token -> 401 "unauthorized" (OPENSANDBOX_EGRESS_TOKEN protects it)
INBOUND: from a no-policy sandbox A -> deny-policy sandbox C (172.17.0.5:44772) POST /command -> "INBOUND-EXEC-OK-in-policy-sandbox"
```

- `dns` mode is exactly "DNS allow-list only". Anything reachable by IP stays open, including the server by host IP and every other sandbox. Advertise EGRESS_POLICY only with `dns+nft`, as the design says.
- `dns+nft` gives egress-only enforcement. The execd port is still published on `0.0.0.0` and reachable from other bridge containers, from the LAN, and through the server proxy (see i). Real east-west isolation on Docker needs things OpenSandbox does not provide: every sandbox under a deny policy, plus `publish_host` set to 127.0.0.1 / the bridge gateway, plus host firewalling. That is an operator declaration (`network-isolation: declared`), exactly as §6.4 anticipates. The §11.3 seed self-check ("connection to the server endpoint fails") passes under `dns+nft`, but inbound from peers is not covered by that check.
- On Docker Desktop, 172.17.0.1:8090 was refused even without a policy: the server listens on the macOS host, not in the VM. On a Linux CI runner the bridge gateway IS the host, so expect it to be reachable there.

## g. Security context (what the server applied)

**Verdict: PARTIAL HARDENED_SECURITY_CONTEXT. Configured cap drops, no-new-privileges, the docker-default seccomp profile, pids/mem/cpu limits and no docker socket are all applied. But the sandbox runs as ROOT with CHOWN/DAC_OVERRIDE/SETUID/SETGID/FOWNER/etc., the rootfs is writable, and the create API has no way to request a non-root user or security context (unknown fields are silently ignored).**

Evidence (`evidence/g.txt`, `evidence/g_inspect_and_proxy.txt`):

```
id -> uid=0(root) gid=0(root)
CapPrm/CapEff/CapBnd 00000000800405fb = cap_chown,cap_dac_override,cap_fowner,cap_fsetid,cap_kill,cap_setgid,cap_setuid,
                                        cap_setpcap,cap_net_bind_service,cap_sys_chroot,cap_setfcap ; CapAmb 0
NoNewPrivs: 1   Seccomp: 2 (filter)   pids.max 4096   memory.max 536870912   cpu.max "50000 100000"
rootfs writable (touch /usr/bin/x ok); /var/run/docker.sock absent; mount tmpfs -> permission denied; unshare -U -> not permitted
chown 1234 ok; setuid-root copy of bash run as nobody -> uid=65534 (nnp blocks escalation)
/proc/1/environ readable (PATH, HOSTNAME, OPENSANDBOX_ID, HOME) — no secrets there in this setup
docker inspect: User="" Privileged=false CapDrop=[configured 9] CapAdd=null SecurityOpt=["no-new-privileges=true"] PidsLimit=4096
                Memory=536870912 NanoCpus=500000000 ReadonlyRootfs=false UsernsMode="" Runtime=runc Binds=null
                PortBindings 44772/tcp, 8080/tcp -> HostIp 0.0.0.0
create with {"user":"1000"} -> 202 (field ignored, still root)
```

The only non-root path is per-command `uid`, which §6.1 rejects because files-API writes are root-owned. Changing that would need an image whose entrypoint drops privileges, or execd itself running as non-root; neither is controllable through the Docker runtime config. Advertise HARDENED_SECURITY_CONTEXT only with `insecure-allow` on Docker.

## h. Volumes (Docker named volumes via `volumes[].pvc`)

**Verdict: SHARED_VOLUME creation works: auto-created named volumes can be mounted rw in one sandbox and ro in another, and ro is enforced for both commands and the files API. There is no delete API. `deleteOnSandboxTermination` removes the volume only when the creating sandbox is deleted, and only if nothing else still mounts it; otherwise it silently leaks. The provider needs a Docker-API VolumeReclaimer.**

Evidence (`evidence/h.txt`, `evidence/h2.txt`):

```
volumes:[{"name":"ws","pvc":{"claimName":"spike-docker-vol1","createIfNotExists":true,"storage":"1Gi","accessModes":["ReadWriteMany"]},"mountPath":"/workspace"}] -> 202
docker volume inspect -> Driver local, Labels {"opensandbox.io/volume-managed-by":"server"}   (storage/accessModes ignored on Docker)
second sandbox same claimName, readOnly:true -> 202; Mounts A {"Mode":"rw","RW":true}, B {"Mode":"ro","RW":false}
A: echo > /workspace/shared.txt (root, 644; mount root dir 755 root) ; B: cat -> "from-a"
B: append -> "Read-only file system" rc=1 ; B files upload -> 500 "open /workspace/via-files.txt: read-only file system"
createIfNotExists=false on a missing name -> 400 {"code":"VOLUME::PVC_NOT_FOUND",...}
host bind with allowed_host_paths=[] -> 400 {"code":"VOLUME::HOST_PATH_NOT_ALLOWED",...}
claimName "Bad_Name" -> 422 pattern ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$
docker volume rm while mounted -> "volume is in use - [<container ids>]" (the VolumeInUseException signal)
after both sandboxes deleted (default deleteOnSandboxTermination=false) -> volume remains
deleteOnSandboxTermination=true, creator deleted while a 2nd sandbox mounts it -> server log
   "failed to remove managed volume 'spike-docker-vol2': 409 Client Error" -> never retried; volume remains after the 2nd sandbox is deleted too
deleteOnSandboxTermination=true, sole user -> removed on DELETE ("removed managed volume 'spike-docker-vol3'")
docker volume rm after unmount -> ok
```

- `SharedVolumes.list(prefix)` = `docker volume ls` filtered by the name prefix. All server-created volumes carry `opensandbox.io/volume-managed-by=server`, but there is no per-owner label, which matches the design's name-based identification. The server also creates and removes its own `opensandbox-runtime-<sandboxId>` volume for egress sandboxes; the reclaimer must ignore those, and the `aimon-` prefix already does.
- Mounted-ness can be read from the Docker API (`docker ps --filter volume=<name>`) or from the 409 on remove.
- Do not use `deleteOnSandboxTermination`, because it leaks when shared.

## i. Endpoint / how the client reaches execd

**Verdict: `GET /sandboxes/{id}/endpoints/44772` returns `{"endpoint":"<hostIP>:<hostPort>/proxy/44772"}` with no headers. execd on Docker has NO authentication. `X-EXECD-ACCESS-TOKEN` exists in the spec but is not configured, and any value is accepted. The lifecycle server's own proxy route `/v1/sandboxes/{id}/proxy/{port}/…` is also exempt from the API key.**

Evidence (`evidence/i.txt`, `evidence/g_inspect_and_proxy.txt`):

```
GET /endpoints/44772                     -> {"endpoint":"192.168.20.15:41698/proxy/44772"}          (no "headers")
GET /endpoints/44772?use_server_proxy=true -> {"endpoint":"localhost:8090/v1/sandboxes/<id>/proxy/44772"}
GET /endpoints/44772?expires=<epoch>     -> 400 {"code":"SANDBOX::API_NOT_SUPPORTED","message":"Signed routes (expires parameter) are not supported when runtime.type='docker'..."}
GET /endpoints/8080 -> {"endpoint":"192.168.20.15:41776"} ; GET /endpoints/9999 -> {"endpoint":"192.168.20.15:41698/proxy/9999"}
http://<endpoint>/ping -> 200 ; http://<hostIP>:<hostPort>/ping (root path) -> 200
server proxy WITH key -> 200 ; server proxy WITHOUT key -> 200
POST localhost:8090/v1/sandboxes/<id>/proxy/44772/command (NO API key) -> SSE "uid=0(root)…" — full root exec with only the sandbox id
direct with X-EXECD-ACCESS-TOKEN: garbage -> 200
```

The source confirms the proxy exemption (`middleware/auth.py`: `_PROXY_PATH_RE = ^(/v1)?/sandboxes/[^/]+/proxy/\d+(/|$)`, skipped when not multi-tenant). `secureAccess` is Kubernetes+gateway only. The host IP comes from the server's host detection (`docker.host_ip`/`server.eip` override it). Auth header names: lifecycle `OPEN-SANDBOX-API-KEY`; execd `X-EXECD-ACCESS-TOKEN` (spec only, unused on Docker); egress sidecar uses `OPENSANDBOX_EGRESS_TOKEN` internally. SSE comes through the server proxy intact.
For the provider, always resolve the endpoint through the API, never cache the host port, and treat "knows the sandbox id" as "can exec as root" on Docker. Do not log sandbox ids at INFO in shared logs.

## CI (GitHub Actions `ubuntu-latest`)

**Verdict: feasible, with no special privileges beyond the runner's Docker. Budget about 1 min of pulls, then about 1 s server start and about 1 s per sandbox.**

- Images to pull: `opensandbox/execd:v1.1.0` (171 MB; arm64 pulled here — confirm amd64 for the runner), `opensandbox/egress:v1.1.7` (560 MB; only needed for EGRESS_POLICY tests), plus a sandbox image with `bash` and whatever the tests need (debian-slim+curl ≈ 230 MB). Pin by digest (above). `execd` is injected into the sandbox from its image by the server, so the sandbox image needs no execd.
- Server: `astral-sh/setup-uv`, checkout OpenSandbox at a pinned commit (or `pip install opensandbox-server` / the `opensandbox/server` image with `/var/run/docker.sock` mounted and `extra_hosts: host.docker.internal:host-gateway`, as in `server/docker-compose.example.yaml`), `SANDBOX_CONFIG_PATH=<toml>`, `uv run opensandbox-server &`, poll `GET /health` (about 1 s). Upstream's own `real-e2e.yml` does the same on a self-hosted runner, with `execd_image=opensandbox/execd:local` built from source and `mode="dns+nft"`.
- Config differences vs this spike: on Linux, `resolve_internal = true` (the default) works because the host routes to the bridge. Set `publish_host = "127.0.0.1"` so execd ports are not on the runner's public interface. Leave `api_key` set: an empty key needs `OPENSANDBOX_INSECURE_SERVER=YES`. Use `sqlite` in `$RUNNER_TEMP`.
- The Docker socket is the runner's default `/var/run/docker.sock` (the runner user is in the `docker` group). `dns+nft` needs nftables in the runner kernel; the sidecar gets `NET_ADMIN` itself. It worked in the Docker Desktop VM; on ubuntu-latest it is expected to work (upstream CI uses it) but was NOT run here.
- Cleanup: `docker ps -aq --filter label=opensandbox.io/id | xargs -r docker rm -f`, sidecars via `label=opensandbox.io/egress-sidecar-for`, volumes by name prefix.

## Cleanup performed

All sandboxes deleted through the API (the remaining 4 from the KEEP run → 204). `docker ps -a` shows no `sandbox-*` / `sandbox-egress-*` containers. Volumes `spike-docker-vol1/2/3` and the `opensandbox-runtime-*` volumes were removed. The server was stopped (port 8090 free). Kept: images `opensandbox/execd:v1.1.0`, `opensandbox/egress:v1.1.7`, `debian:bookworm-slim`, `spike-docker-base:1`, plus `OpenSandbox/server/.venv` and `spike/docker/state/` (sqlite) in the scratchpad. The other agent's `spike-os-*` kind containers were not touched.

## Impact on the design doc (summary)

1. §6.1 `extendExpiry` "forward only" and `maxExpiry` are client-side rules; the server allows backward moves and has no max on renew, and it does not expose max.
2. §6.4 PAUSE_RESUME: the expiry keeps running while paused, and a paused sandbox is deleted at `expiresAt`. execd calls hang while paused.
3. §6.1/§9 EXEC: the process-group kill misses `setsid` children; a disconnect does not kill; there is no output cap; output is spooled to the sandbox's /tmp; the SSE framing is lossy (use trailer or file sizes); 128 KiB per argv/env string is confirmed.
4. §6.4 FILES: nanosecond mtime satisfies change detection without an etag. `list` gives `type`. Move has no overwrite. The upload default mode is 755.
5. §6.4 NETWORK_ISOLATION / §12: on Docker, execd is unauthenticated and reachable from every other sandbox (inbound is not covered by egress policy) and via the key-less server proxy route. `dns` mode leaks by IP. Only `dns+nft` plus operator-level port/firewall isolation is acceptable.
6. §6.4 HARDENED_SECURITY_CONTEXT: not achievable on Docker (root, writable rootfs, no user/securityContext in the API).
7. §6.4 SHARED_VOLUME: rw/ro sharing works. Deletion must be the provider's Docker-API reclaimer; avoid `deleteOnSandboxTermination`.
