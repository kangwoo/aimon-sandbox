# Upstream issue drafts — opensandbox-group/OpenSandbox

Drafts of the issues listed in [`docs/design/opensandbox-spike.md`](../../docs/design/opensandbox-spike.md) §10.
**Not filed yet.** A duplicate search (2026-09-30: "renew paused", "renew-expiration", "proxy api key",
"create-namespace", "Namespace helm chart") found nothing matching. All four were observed on OpenSandbox `3738975`
(2026-09-29 main); evidence paths are relative to this directory.

---

## 1. [Bug] Renewing a paused BatchSandbox recreates the pod from the original image and fails the sandbox

**Runtime:** Kubernetes (BatchSandbox), chart `1.1.1-rc.1`, images `release-1.1.1-rc.1`, kind k8s v1.37.

**What happens**

Calling `POST /v1/sandboxes/{id}/renew-expiration` on a sandbox in state `Paused` returns 200. Then the controller
recreates the sandbox pod from the **original** image (not the pause snapshot), the BatchSandbox goes to `Failed`
with condition `PauseFailed` / `PodNotFound`, and `resume` is refused with 409. The paused state is lost.

**Steps**

1. Create a sandbox (any image; we used `python:3.12-slim`) with a snapshot registry configured for pause.
2. `POST /v1/sandboxes/{id}/pause` and wait for `Paused` (pods for the sandbox: none).
3. `POST /v1/sandboxes/{id}/renew-expiration` with `expiresAt = now + 20m` → 200.
4. Watch the CR and pods.

**Observed**

```
Paused, spec.pause=true, generation=2, pods=[]
POST renew-expiration (+20m) -> 200
00:04:47 api=Pausing  cr.phase=Failed generation=3 pods=[<id>-0: Pending]
00:04:50 api=Failed   cr.phase=Failed pods=[<id>-0: Running] image=python:3.12-slim   <- original image
condition PauseFailed: "source pod not found: no running pod found for BatchSandbox …" (PodNotFound)
POST resume -> 409 "Cannot resume: sandbox is not available"
```

**Likely cause:** the renew patch changes the BatchSandbox spec (bumps `metadata.generation`). The controller then
re-runs pause reconciliation, which expects a running source pod.

**Expected:** renewing a paused sandbox only moves its expiry, since expiry keeps running while paused (a paused
sandbox is deleted at `expiresAt`). Renew-before-pause is currently the only safe order, and nothing documents it.

Evidence: `k8s/probes/d2-renew-while-paused.sh`, `k8s/probes/out/d2.log`.

---

## 2. [Bug] `renew-expiration` can shorten the expiry and ignores `max_sandbox_timeout_seconds`

**Runtime:** Docker and Kubernetes.

The endpoint is described as extending a sandbox's expiration. The implementation only checks that the new time is
in the future:

- Docker: `server/opensandbox_server/services/docker/docker_service.py` `renew_expiration` calls
  `ensure_future_expiration` and nothing else.
- Kubernetes: `server/opensandbox_server/services/k8s/kubernetes_service.py` `renew_expiration` behaves the same.

**Observed** (`max_sandbox_timeout_seconds = 3600`, Docker):

```
create timeout=60                       -> expiresAt = createdAt + 60s
renew +60s                              -> 200
renew to 30s EARLIER than current (still in the future) -> 200   (expiry shortened)
renew to now + 5h                       -> 200   (max is 1h; not capped)
create timeout=3601                     -> 400 "Sandbox timeout 3601s exceeds configured maximum of 3600s."
```

The same was seen on Kubernetes: renew to `now+50s` while the current expiry was `now+60s` returned 200, and renew
to `now+90000s` with a max of 86400 returned 200.

**Expected:** either:
- renew rejects a time earlier than the current `expiresAt` and a time beyond `now + max_sandbox_timeout_seconds`, or
- the docs say it is a "set expiration" and the max applies only at create.

Related: there is no API that exposes `max_sandbox_timeout_seconds`, so a client cannot keep itself under the max
without out-of-band configuration. A field in `/version` or a small `/v1/limits` endpoint would help.

Evidence: `docker/probes/b_expiry.py`, `docker/evidence/b.txt`, `k8s/probes/h-expiry.sh`, `k8s/probes/out/h.log`.

---

## 3. [Docs/Security] In single-tenant mode the sandbox proxy route bypasses the API key

**Runtime:** any, single-tenant (no tenant provider).

`server/opensandbox_server/middleware/auth.py` skips authentication for `^(/v1)?/sandboxes/[^/]+/proxy/\d+(/|$)`
when the server is not multi-tenant:

```python
if self._is_proxy_path(request.url.path) and not self._is_multi_tenant:
    return await call_next(request)
```

So with an `api_key` configured, anyone who can reach the server and knows a sandbox id can run commands in it:

```
POST http://<server>/v1/sandboxes/<id>/proxy/44772/command   (no OPEN-SANDBOX-API-KEY)
-> SSE stdout "uid=0(root) gid=0(root) …"
```

On the Docker runtime, execd also accepts any `X-EXECD-ACCESS-TOKEN`, and its port is published on `0.0.0.0` by
default. Other sandboxes on the bridge network can therefore reach it too, and an egress `networkPolicy` does not
block that inbound traffic.

This may be intentional (the id acts as a capability, and endpoints from `GET /endpoints` are meant to be usable
without the key). But nothing in the docs warns operators that setting `api_key` does not protect command execution.

**Suggested:**
- Document the behaviour prominently in the security and deployment docs.
- Optionally add a config switch to require the API key on proxy routes in single-tenant mode.

Evidence: `docker/probes/i_endpoint.py`, `docker/evidence/i.txt`, `docker/evidence/g_inspect_and_proxy.txt`,
`docker/evidence/f_nft_inbound.txt`.

---

## 4. [Helm] The documented umbrella install (`--create-namespace`) conflicts with the base chart's Namespace

**Chart:** `manifests/charts/opensandbox` `1.1.1-rc.1`.

`docs/deployment/index.md` (Option 1) installs the umbrella chart with:

```bash
helm install opensandbox opensandbox --namespace opensandbox-system --create-namespace
```

The `base` subchart renders `kind: Namespace` for `fastSandbox.namespaces.system` (default `opensandbox-system`) in
`manifests/charts/base/templates/fast-sandbox-rbac.yaml`, because `fastSandbox.namespaces.createSystem` defaults to
`true`. The umbrella `values.yaml` does not override it. Result:

- With `--create-namespace`, Helm creates `opensandbox-system` itself, and the rendered Namespace then conflicts
  (it cannot adopt an object without its ownership metadata).
- Without `--create-namespace`, the install fails because the release namespace does not exist.

The base chart's comments already describe this ("a pre-existing namespace without Helm ownership metadata cannot be
adopted"), but the umbrella chart does not account for it.

**Workarounds**, either:
- `--set base.fastSandbox.namespaces.createSystem=false` together with `--create-namespace`, or
- pre-create the namespace with Helm ownership metadata (what we did):

```bash
kubectl create namespace opensandbox-system
kubectl label namespace opensandbox-system app.kubernetes.io/managed-by=Helm
kubectl annotate namespace opensandbox-system meta.helm.sh/release-name=opensandbox meta.helm.sh/release-namespace=opensandbox-system
```

The sandbox namespace (`opensandbox`) also has to be created by hand.

**Suggested:** set `base.fastSandbox.namespaces.createSystem: false` in the umbrella chart's values (the umbrella's
release namespace already is the system namespace), or change the documented command.

Two more notes from the same install, which may belong in the docs rather than an issue:
- The chart defaults every image to `sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com`, which is slow or unreachable
  outside China. Docker Hub `opensandbox/*:release-*` images work as a drop-in.
- An empty `server.api_key` makes the server crash-loop in a non-interactive environment, which is correct. The
  chart's default values could say so.

Evidence: `reports/C-k8s.md` §1, `k8s/deploy/values.yaml`.
