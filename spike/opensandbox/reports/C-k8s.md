# C — OpenSandbox spike, Kubernetes runtime (live, kind)

Date: 2026-09-30 (UTC 23:30–00:30). Operator: sub-agent. aimon-sandbox repo untouched.
Everything lives in `spike/k8s/`: `deploy/` (values, templates, NetworkPolicy), `probes/` (scripts), `probes/out/` (raw logs).
Closes the K8s "구현 시 확인" items in design §6.4 / §11.3 / §12 / §16.

## 0. Summary table

| Probe | Verdict |
|---|---|
| a labels | PASS. Metadata becomes labels on the BatchSandbox CR and the Pod. The API `metadata=` filter ANDs keys. Invalid label values and keys under `opensandbox.io/` get a 400. PATCH /metadata relabels the CR only, not the running Pod |
| b runtimeClass | PASS with a caveat. There is **no per-request field**. The class is set server-wide via `[secure_runtime].k8s_runtime_class` or the BatchSandbox template. An unknown field in the request is silently ignored |
| c securityContext | Default is **not hardened**: uid 0, default caps, Seccomp 0 (unconfined), NNP 0. The SA token is not mounted (`automountServiceAccountToken:false` is always set). Hardening works only via the server-wide template. Not per request |
| d pause/resume | PASS. Pause commits the rootfs to a registry, **deletes the Pod** and later recreates it (new UID, new IP, same name, restartCount 0). The rootfs is kept and processes are lost. Pause needs a snapshot registry |
| d2 renew while paused | **BUG / FAIL**. Renew on a paused sandbox makes the controller recreate the Pod from the ORIGINAL image. The CR goes `Failed` (PauseFailed/PodNotFound) and resume is refused (409). Snapshot state is lost |
| e network isolation | OpenSandbox creates **no NetworkPolicy**. Without a policy a sandbox reaches peers, the server, the K8s API and kubelet. An operator NetworkPolicy works with kindnet (verified). Server→execd proxy still works |
| f volumes | PASS on RWO and on RWX (NFS). kind local-path cannot do RWX (PVC stays Pending, create returns 504 after 60s, **PVC leaks**). Auto-created PVCs have **no labels or ownerRefs** unless `deleteOnSandboxTermination`. The PVC survives sandbox deletion. pvc-protection holds a deleted PVC until its last mount is gone |
| g egress | PASS (dns+nft). Deny-all and a one-host allow-list are enforced, including by-IP, raw DNS to 8.8.8.8 and cluster IPs. **Incompatible with a pod-level runAsUser** (init and sidecar need root). It **replaces the template's `capabilities.drop:[ALL]` with `[NET_ADMIN]`**. Credential Vault works, scoped by scheme+host+method+path |
| h expiry | Expiry = hard delete of the CR and Pod at `expiresAt` (to the second), **paused or not**. Renew is **not forward-only** (it can shorten) and is **not capped** by `max_sandbox_timeout_seconds`. `timeout` omitted means the sandbox never expires (accepted on K8s) |
| CI | Feasible: about 30s for kind, about 1 min for images and helm, sandbox create 1–3s. Needs a local registry for pause |

## 1. Deployment (step 1)

Versions / images:
- kind **v0.33.0** (installed now via `brew install kind helm`), helm **v4.3.0**, node image k8s **v1.37.0**, CNI **kindnetd v20260820-69b56db7** (enforces NetworkPolicy, verified in e), storage `rancher.io/local-path` (RWO only).
- OpenSandbox checkout `3738975` (2026-09-29), umbrella chart `manifests/charts/opensandbox` **1.1.1-rc.1**.
- Images (all multi-arch amd64+arm64 on Docker Hub, tag `release-1.1.1-rc.1`): `opensandbox/controller@sha256:d7b485be…`, `opensandbox/server@sha256:e417bfea…`, `opensandbox/execd`, `opensandbox/egress`, `opensandbox/image-committer`, `opensandbox/task-executor`.
- For RWX in f: `nfs-ganesha/nfs-server-provisioner` chart 1.8.0 (image `registry.k8s.io/sig-storage/nfs-provisioner:v4.0.8`, arm64 OK). The helm repo `nfs-ganesha` was added locally.

Steps (`deploy/values.yaml`):
1. `kind create cluster --config kind-config.yaml` (single node `spike-os`) took 28s.
2. `cd manifests/charts && helm dependency build opensandbox`.
3. The chart **renders its own Namespace object**. `--create-namespace` therefore conflicts, and without it helm fails with "namespace not found". Workaround: pre-create `opensandbox-system` with helm ownership label and annotations, then `helm install opensandbox opensandbox -n opensandbox-system -f values.yaml`. The sandbox namespace `opensandbox` must also be created by hand.
4. Overrides needed:
   - The chart defaults every image to `sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/*`. That registry is unreachable or slow from here, so I switched to `docker.io/opensandbox/*:release-1.1.1-rc.1`, including `execd_image`, `[egress].image` and `snapshot.imageCommitterImage` in the config.
   - `server.api_key = ""` makes the server crash-loop: "Startup blocked: server.api_key is empty in non-interactive mode" (or set `OPENSANDBOX_INSECURE_SERVER=YES`). I set `spike-key`.
   - Custom BatchSandbox template mounted from ConfigMap `spike-bs-template` at `/etc/spike/template.yaml` (`[kubernetes].batchsandbox_template_file`). The server reads it at startup, so each swap needs a server restart (`probes/swap-template.sh`).
   - Pause: `controller.snapshot.registry=spike-os-registry:5000/snapshots`, `registryInsecure=true`. `registry:2` runs as a docker container on the `kind` network, plus containerd `config_path=/etc/containerd/certs.d` and a `hosts.toml` for http. The kind node image has **no config_path by default**, so I appended it and restarted containerd.
5. Server reachable via `kubectl port-forward svc/opensandbox-server 8091:80`. Sandbox pods are ClusterIP-only. In-sandbox HTTP was tested through the server proxy `/v1/sandboxes/{id}/proxy/44772/...` and through `kubectl exec`.
- arm64 notes: no arm64 problems with Docker Hub images. `kind load docker-image` of multi-arch images failed under the Docker Desktop containerd store (`ctr: content digest … not found`), so I pulled on the node with `crictl pull` instead. The Aliyun default registry is the practical blocker, not arch.
- Create API: `resourceLimits` is **required** on K8s (422 otherwise). Create blocks until Running+IP, up to 60s. On timeout it returns `504 KUBERNETES::POD_READY_TIMEOUT` and **deletes the BatchSandbox** (rollback).

## a. Labels — PASS
Script `probes/a-labels.sh`, logs `out/a.log`, `out/a-invalid.log`.
```
CR labels : {"aimon.at/deployment":"spike","aimon.at/managed":"true","aimon.at/sandbox-key":"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567","opensandbox.io/id":"35f98bd9-…"}
Pod labels: {… same aimon.at/* …, "batch-sandbox.sandbox.opensandbox.io/name":"35f98bd9-…","batch-sandbox.sandbox.opensandbox.io/pod-index":"0","opensandbox.io/id":"35f98bd9-…"}
kubectl -l aimon.at/sandbox-key=ABC…567 -> batchsandbox/35f98bd9…, pod/35f98bd9…-0
GET /v1/sandboxes?metadata=<urlenc aimon.at/sandbox-key=ABC…> -> exactly A
GET ?metadata=<aimon.at/managed=true&aimon.at/deployment=spike> -> 2 (AND)
metadata value "a/b" or 70 chars -> 400 SANDBOX::INVALID_METADATA_LABEL (k8s label-value rules, ≤63)
metadata key "opensandbox.io/id" -> 400 "reserved prefix 'opensandbox.io/'"
PATCH /sandboxes/{id}/metadata {"aimon.at/gen":"2"} -> 200; CR labels gain aimon.at/gen, Pod labels do NOT
```
Implications:
- A 32-char base32 key fits (≤63, alnum).
- The janitor should select on the **BatchSandbox CR** (or the API), not on Pods. Pod labels are stale after a PATCH, and Pods vanish while paused.

## b. runtimeClassName — PASS (server-wide only)
Script `probes/b-runtimeclass.sh`, logs `out/b.log`, `out/b-rerun.log`, `out/b-secure-runtime.log`.
```
RuntimeClass spike-runc (handler runc) created
request with top-level "runtimeClassName":"spike-runc" -> 202, pod.spec.runtimeClassName='' (unknown field silently ignored)
template runtimeClassName: spike-runc      -> pod.spec.runtimeClassName=spike-runc handler=runc
template runtimeClassName: spike-missing   -> create blocks 60s -> 504 KUBERNETES::POD_READY_TIMEOUT, CR deleted (rollback);
   controller event: FailedCreate … pod rejected: RuntimeClass "spike-missing" not found
[secure_runtime] type="kata" k8s_runtime_class="spike-missing" -> server CrashLoop at startup:
   "Secure runtime validation failed: Configured Kubernetes RuntimeClass 'spike-missing' does not exist"
[secure_runtime] type="kata" k8s_runtime_class="spike-runc" -> pod.spec.runtimeClassName=spike-runc
```
Implications for `RUNTIME_CLASS`:
- The class is a property of the **OpenSandbox server/deployment**, not of the request. A profile's `runtimeClass` can only be honoured if it equals the server's configured class.
- Advertise `RUNTIME_CLASS=<name>` from provider config, and verify it by reading `pod.spec.runtimeClassName` (needs K8s read access), or trust the operator's declaration.
- A pydantic model that ignores unknown fields means a typo'd field is silently dropped.
- Static finding: `ensure_egress_runtime_compatible` rejects `networkPolicy` when the runtime type or RuntimeClass **name** is `gvisor` (400, "gVisor does not support the iptables nat table"). So **gVisor + EGRESS_POLICY is impossible**, and only Kata-type runtimes combine with egress.

## c. SecurityContext — default NOT hardened; template-only hardening works
Script `probes/c-secctx.sh`, logs `out/c-default.log`, `out/c-hardened.log`, `out/c-hardened-execd.log`.

Default template:
```
podSC={}  automount=false  sa=default  containers[].securityContext: (none)
id -> uid=0(root)
CapEff 00000000a80425fb = chown dac_override fowner fsetid kill setgid setuid setpcap net_bind_service net_raw sys_chroot mknod audit_write setfcap
NoNewPrivs 0   Seccomp 0 (unconfined — kubelet default, no RuntimeDefault)
/var/run/secrets/kubernetes.io/serviceaccount: No such file   (KUBERNETES_SERVICE_HOST env still present)
execd runs as uid 0
```
Hardened template (`deploy/template-hardened.yaml`: pod runAsNonRoot/runAsUser 1000/fsGroup/seccomp RuntimeDefault; container allowPrivilegeEscalation:false, drop ALL):
```
podSC={"fsGroup":1000,"runAsGroup":1000,"runAsNonRoot":true,"runAsUser":1000,"seccompProfile":{"type":"RuntimeDefault"}}
sandbox: {"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}
uid=1000; CapPrm/Eff/Bnd = 0; NoNewPrivs 1; Seccomp 2; execd runs as 1000 and works (proxy /command: uid=1000)
```
Can the request set these? **No.** There is no request field. The server merges the template's pod spec and its `containers[name=sandbox].securityContext`, and that is server-wide.

Two traps, both from g:
1. A **pod-level** `runAsUser/runAsNonRoot` also hits the privileged `execd-installer` init container and the egress sidecar. With `networkPolicy` the init container fails: `can't create /proc/sys/net/ipv6/conf/all/disable_ipv6: Permission denied`, so create returns 500 `POD_FAILED/InitContainerFailed`. Hardening must be **container-level** (`deploy/template-hardened-ctr.yaml`).
2. With `networkPolicy`, the runtime sets `capabilities.drop:["NET_ADMIN"]` on the sandbox container. Runtime wins on conflicting leaves, so the template's `drop:["ALL"]` is **replaced**, not merged. Evidence: `sc={"allowPrivilegeEscalation":false,"capabilities":{"drop":["NET_ADMIN"]},"runAsNonRoot":true,"runAsUser":1000}`. CapEff was still 0 because the process is non-root with NNP, but the bounding set is the default one.

The seed's uid and token checks (§11.3) are justified. The server enforces neither by default.

## d. Pause/resume — Pod is deleted and recreated; rootfs kept, processes lost
Script `probes/d-pause.sh`, logs `out/d.log` (root, `/` + `/workspace`) and `out/d3.log` (container-hardened, background process via execd).
```
before: pod …-0 uid=1a442f8b… ip=10.244.0.25
pause (202) -> Pausing (pod still Running, pause-commit Job pod `<id>-pause-commit-xxxx` Completed) -> Paused after ~4-10s, pods=[]
registry: snapshots/<id>-sandbox
resume (202) -> Resuming -> Running in ~2-3s
after: same name …-0, NEW uid=b31a6dfe…, NEW ip=10.244.0.27, restartCount 0,
       image=spike-os-registry:5000/snapshots/<id>-sandbox@sha256:843d…
/marker-root and /workspace/marker-ws (rootfs, written as root) -> preserved; /tmp marker (uid 1000) -> preserved
d3 processes: before [bootstrap, execd, sleep infinity, sleep 7777(background via execd)] -> after [bootstrap, execd, sleep infinity]  (process state lost)
GET /endpoints/44772 after resume -> 10.244.0.64:44772 (changed)
expiresAt unchanged by pause/resume (absolute timestamp keeps flowing; see h2)
```
Implications:
- `PAUSE_RESUME` on K8s = a commit-image cold stop. The provider must re-resolve endpoints after resume, and the §9 shell state must be restored from files.
- **Needs** a snapshot registry, push/pull secrets and a controller image-committer (containerd socket).
- **Snapshot images are never garbage-collected.** Images stayed in the registry for sandboxes that were deleted or expired.
- No SandboxSnapshot CR is created for pause.

### d2. renew-expiration while paused — BUG
Script `probes/d2-renew-while-paused.sh`, log `out/d2.log` (also seen unintentionally in `out/h.log`).
```
Paused, spec.pause=true, gen=2, pods=[]
POST renew-expiration (+20m) -> 200
00:04:47 api=Pausing cr.phase=Failed gen=3 pods=[f861…:Pending]      <- a pod is recreated
00:04:50 api=Failed  cr.phase=Failed pods=[f861…:Running]  image=python:3.12-slim  (ORIGINAL image, not the snapshot)
condition PauseFailed: "source pod not found: no running pod found for BatchSandbox …" (PodNotFound)
POST resume -> 409 "Cannot resume: sandbox is not available"
```
The renew patch bumps `generation`. The controller re-runs pause reconciliation, recreates the Pod from the base image, and loses the paused state.
- The provider must **never renew a paused K8s sandbox**. It should resume first, or carry expiry itself.
- Worth an upstream issue.

## e. Network isolation — nothing by default; operator NetworkPolicy works on kindnet
Script `probes/e-netiso.sh` (uses `probes/netcheck.py`), log `out/e.log`. Policy: `deploy/netpol-sandbox-isolation.yaml`.
```
NetworkPolicies before: No resources found (controller/server create none, also not with networkPolicy in the request — checked in g)
no policy:   peer-execd 10.244.0.40:44772 OPEN 200 | server svc :80 OPEN 200 | server pod OPEN | kubernetes.default.svc:443 OPEN (GET /version 200)
             node 172.18.0.2:6443 OPEN | kubelet :10250 OPEN | 169.254.169.254 refused (no metadata svc in kind) | example.com OPEN
with policy: all of the above BLOCKED (timeout) except example.com OPEN; B -> A execd BLOCKED
             server -> sandbox proxy (/proxy/44772/ping) still HTTP 200 (ingress allowed from server pods only)
```
The example policy for the sandbox namespace (podSelector {}):
- Ingress: only from `opensandbox-system/app.kubernetes.io/name=opensandbox-server`.
- Egress: DNS to kube-dns, plus `0.0.0.0/0 except 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10`.

Also in g: `networkPolicy` (dns+nft) alone blocks the sandbox's **egress** to peers, the server, the API, kubelet and NFS. It does **not** block **ingress** from other sandboxes. An unrestricted peer reached G1's execd (44772) and its egress-sidecar API (18080; that API needs a token and returns 401 without it).
- `NETWORK_ISOLATION` therefore still needs the operator NetworkPolicy (ingress side), as the design says.
- The seed self-check "connect to server must fail" works under either mechanism.

## f. Volumes
Scripts `probes/f-volumes.sh` and `probes/f-rwx-nfs.sh`, logs `out/f.log`, `out/f-rwx-nfs.log`, `out/f-nfs-locked.log`.
```
RWX on local-path: PVC created (accessModes [ReadWriteMany]) -> ProvisioningFailed "NodePath only supports ReadWriteOnce and ReadWriteOncePod";
   create 504 after 60s, CR rolled back, **PVC spike-rwx left Pending (leak), no labels/ownerRefs**
RWO: S1 rw + S2 readOnly on one node -> both Running; S1 (uid 1000) writes; S2 reads "from-s1", write -> "Read-only file system"
   pod wiring: persistentVolumeClaim{claimName, readOnly:true} + volumeMount readOnly:true
   PVC (createIfNotExists, default): labels=<none> ownerRefs=<none> finalizers=[kubernetes.io/pvc-protection]
delete S1,S2 -> PVC still Bound (survives)
remount in S3, `kubectl delete pvc` -> deletionTimestamp set, stays Bound with pvc-protection; S3 still reads
   new sandbox referencing the terminating PVC -> 504 after 60s (pod Pending)
   delete S3 -> PVC gone ~4s later; PV Released -> deleted (reclaim Delete)
deleteOnSandboxTermination=true -> PVC labels {opensandbox.io/id:<id>, opensandbox.io/volume-managed-by:server},
   ownerRef BatchSandbox (controller:false, blockOwnerDeletion:false) -> PVC deleted with the sandbox
RWX on NFS (nfs-server-provisioner, storageClass "nfs"): A rw + B rw + C readOnly -> A/B write & read each other, C "Read-only file system"
   (mount: nfs ro vers=3 addr=<svc ip>); PVC survives sandbox deletion; no labels/ownerRefs
NFS mount under NetworkPolicy + egress deny-all -> mount works (kubelet mounts on the node), sandbox -> NFS svc :2049 BLOCKED
```
Implications for `SHARED_VOLUME`:
- It needs an RWX StorageClass. `accessModes` and `storageClass` pass through.
- There is no delete API, and shared PVCs carry **no labels**. The `VolumeReclaimer` must track PVC names itself, or pre-create PVCs with its own labels and use `createIfNotExists:false`.
- A failed create can leak an auto-created PVC.

## g. Egress (dns+nft) + Credential Vault
Scripts `probes/g-egress.sh` (`egresscheck.py`, `dnsq.py`) and `probes/g-vault.py`, logs `out/g.log`, `out/g-dns.log`, `out/g-sidecar-api.log`, `out/g-vault.log`, `out/g-hardened-podlevel.log`.
```
pod shape: init execd-installer {privileged:true}; sandbox {drop:[NET_ADMIN] (+template)}; sidecar egress {add:[NET_ADMIN]} port 18080
G1 deny + []:        DNS example.com/google/github -> NXDOMAIN; https 1.1.1.1 by IP -> timeout; example.com IP by IP -> timeout
G2 deny + example.com: DNS example.com -> 104.20.23.154,172.66.147.243, others NXDOMAIN; http/https example.com 200;
                     example.com's IP with Host header -> 200 (IP learnt from DNS); https 1.1.1.1 -> timeout
raw DNS to 8.8.8.8 / 1.1.1.1 udp+tcp (bypassing resolv.conf) -> rcode=3 (redirected to sidecar filter; sidecar logs "[dns] denied by policy question=exfil-abc123.example.org")
cluster-internal from G1 (no K8s NetworkPolicy): peer execd, server svc/pod, 10.96.0.1:443, node:6443, kubelet -> all BLOCKED
GET /v1/sandboxes/{id}/networkpolicy -> {"mode":"deny_all","enforcementMode":"dns+nft",…}  (the server reports the mode — the client CAN learn it)
from inside the sandbox: GET/POST/PATCH 127.0.0.1:18080/policy -> 401 unauthorized (token only in sidecar env)
```
The **pod-level hardened template combined with networkPolicy** fails: create returns 500 `InitContainerFailed` (see c). The container-level template works.

Credential Vault (Python SDK from the checkout, `use_server_proxy=True`, `credentialProxy.enabled`, allow `httpbin.org`, binding `{schemes:[https], hosts:[httpbin.org], methods:[GET], paths:[/headers]}`, apiKey header `X-Api-Key`):
```
GET  https://httpbin.org/headers    -> X-Api-Key= s3cr3t-value   (injected)
POST https://httpbin.org/anything/x -> X-Api-Key= None
GET  https://httpbin.org/anything/x -> None
GET  https://httpbin.org/get        -> None
sandbox env contains secret? False
```
Scoping is scheme + host + port + **method** + **path** (glob). The sidecar has an env switch `OPENSANDBOX_EGRESS_CREDENTIAL_VAULT_REQUIRE_SCOPED_MATCH` that forces methods and paths.

Notes:
- The check disabled TLS verification. Whether the sandbox trusts the MITM CA was not examined.
- Vault writes go through the server proxy to the sidecar, not through the lifecycle API.
- Vault could meet the §12 path-prefix and method contract.
- `GET /networkpolicy` exposes `enforcementMode`, so the provider could verify `dns+nft` instead of relying only on an operator declaration.

## h. Expiry
Scripts `probes/h-expiry.sh` and `d2`, logs `out/h.log`, `out/h2.log`, `out/h3.log`.
```
timeout 30 -> 422 (min 60); timeout omitted -> 202, CR has no spec.expireTime, expiresAt=null (never expires)
renew to past -> 400 "New expiration time must be in the future" (error code says DOCKER::INVALID_EXPIRATION on K8s)
renew to now+50s when current expiry was now+60s -> 200 (SHORTENS; not forward-only)
renew to now+90000s with max_sandbox_timeout_seconds=86400 -> 200 expiresAt 2026-10-01T00:59:35Z (cap NOT enforced on renew)
running sandbox at expiresAt (7896061b, 00:10:11Z) -> controller "batch sandbox expired, delete" at 00:10:11; Pod gone; GET -> 404 SANDBOX_NOT_FOUND
paused sandbox at expiresAt (h2, 00:06:44Z) -> Paused until 00:06:39, 404 at 00:06:44 (deleted while paused); snapshot image left in registry
list API after expiry: expired sandboxes are absent (no terminal "Expired" state retained)
```
Implications:
- `EXPIRY` holds. The provider itself must enforce "forward only" and the cap.
- Expiry is a hard delete. Record reconciliation sees 404, not a state.
- Expiry flows while paused, so `pauseAfter` must renew **before** pausing (never while paused, see d2).

## 3. CI requirements for the K8s runtime (`@Tag("k8s")`)
- GitHub Actions `ubuntu-latest` (amd64) with `helm/kind-action` (or `kind` binary) and Helm 3.x/4.x. All OpenSandbox images are multi-arch on Docker Hub (`opensandbox/*:release-X.Y.Z`). **Override the chart's Aliyun default registry.** Consider a Docker Hub login to avoid pull rate limits (about 7 images: controller, server, execd, egress, image-committer, plus test image and nfs).
- The kind config should include the registry patch up front instead of restarting containerd:
  ```yaml
  containerdConfigPatches:
  - |-
    [plugins."io.containerd.grpc.v1.cri".registry]
      config_path = "/etc/containerd/certs.d"
  ```
  Also run `registry:2` on the `kind` network with `hosts.toml` (kind "local registry" recipe). This is needed only for pause/resume tests.
- Pre-create `opensandbox-system` with helm ownership metadata, or install the per-component charts. Also create the `opensandbox` namespace, set a server `api_key`, and supply a ConfigMap-mounted BatchSandbox template (hardened, container-level).
- RWX tests need an in-cluster NFS provisioner (`nfs-server-provisioner`, about 10s). The kind node has `mount.nfs`. NetworkPolicy tests work with the default kindnet, so no Calico is needed.
- Timings measured here on an M-series Mac:
  - kind create: 28s.
  - Image pulls: about 1–2 min cold (cached afterwards). Controller ready in under 1 min.
  - Server rollout: about 12s.
  - Sandbox create: 1–3s with cached images.
  - Pause: 4–10s. Resume: 2–3s.
  - Estimate for CI: about 3–4 min to ready, then a few minutes of tests.
- Access from the test JVM uses `kubectl port-forward svc/opensandbox-server` plus the server proxy (`/v1/sandboxes/{id}/proxy/{port}`). Pod IPs are not reachable from the runner.

## 4. Teardown
- `kind delete cluster --name spike-os` done. Registry container `spike-os-registry` removed. Port-forward on 8091 stopped.
- kind and helm were **installed by this spike via Homebrew** and left installed. The helm repo `nfs-ganesha` remains in the local helm config.
