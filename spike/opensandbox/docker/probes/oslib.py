"""Tiny raw-REST helper for the OpenSandbox spike (no SDK).

Run probes with:  uv run --with httpx python probes/<probe>.py
Env: OS_BASE (default http://localhost:8090/v1), OS_KEY (default spike key), OS_IMAGE.
"""
import json
import os
import sys
import time
import urllib.parse

import httpx

BASE = os.environ.get("OS_BASE", "http://localhost:8090/v1")
KEY = os.environ.get("OS_KEY", "spike-docker-key-1234567890")
IMAGE = os.environ.get("OS_IMAGE", "spike-docker-base:1")
EXECD_PORT = 44772
H = {"OPEN-SANDBOX-API-KEY": KEY}

api = httpx.Client(base_url=BASE, headers=H, timeout=120)
CREATED = []


def log(*a):
    print(*a, flush=True)


def verdict(kind, what, detail=""):
    log(f"[{kind}] {what}" + (f" :: {detail}" if detail else ""))


def show(r, limit=600):
    body = r.text
    if len(body) > limit:
        body = body[:limit] + f"...(+{len(r.text) - limit} chars)"
    return f"{r.request.method} {r.request.url.path}{('?' + r.request.url.query.decode()) if r.request.url.query else ''} -> {r.status_code} {body}"


def create(metadata=None, timeout=600, extra=None, expect_ok=True):
    body = {
        "image": {"uri": IMAGE},
        "entrypoint": ["sleep", "infinity"],
        "resourceLimits": {"cpu": "500m", "memory": "512Mi"},
        "metadata": metadata or {"probe": "x"},
    }
    if timeout is not None:
        body["timeout"] = timeout
    if extra:
        body.update(extra)
    r = api.post("/sandboxes", json=body)
    if r.status_code in (200, 202):
        CREATED.append(r.json()["id"])
    elif expect_ok:
        log("CREATE FAILED", show(r, 2000))
    return r


def get(sid):
    return api.get(f"/sandboxes/{sid}")


def delete(sid):
    return api.delete(f"/sandboxes/{sid}")


def list_sb(metadata=None, states=None, page=None, page_size=None):
    params = []
    if metadata:
        params.append(("metadata", urllib.parse.urlencode(metadata)))
    for s in states or []:
        params.append(("state", s))
    if page:
        params.append(("page", page))
    if page_size:
        params.append(("pageSize", page_size))
    return api.get("/sandboxes", params=params)


def wait_state(sid, want, timeout=90):
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout:
        r = get(sid)
        if r.status_code != 200:
            return r.status_code, r.text
        st = r.json()["status"]["state"]
        last = st
        if st in want:
            return st, r.json()
        time.sleep(1)
    return last, None


def execd_base(sid):
    r = api.get(f"/sandboxes/{sid}/endpoints/{EXECD_PORT}")
    r.raise_for_status()
    ep = r.json()
    return "http://" + ep["endpoint"], ep.get("headers") or {}


class Execd:
    def __init__(self, sid):
        self.sid = sid
        self.base, self.headers = execd_base(sid)
        self.c = httpx.Client(base_url=self.base, headers=self.headers, timeout=httpx.Timeout(600, connect=10))

    def run(self, command, cwd=None, envs=None, background=False, timeout_ms=None, raw_body=None, max_events=None):
        """Run /command, parse SSE. Returns dict(status, events, stdout, stderr, exit, error, init_id, http_text)."""
        body = raw_body if raw_body is not None else {"command": command}
        if raw_body is None:
            if cwd is not None:
                body["cwd"] = cwd
            if envs is not None:
                body["envs"] = envs
            if background:
                body["background"] = True
            if timeout_ms is not None:
                body["timeout"] = timeout_ms
        out = {"status": None, "events": [], "stdout": "", "stderr": "", "exit": None, "error": None,
               "init_id": None, "bytes": 0, "http_text": ""}
        with self.c.stream("POST", "/command", json=body) as r:
            out["status"] = r.status_code
            if r.status_code != 200:
                r.read()
                out["http_text"] = r.text[:2000]
                return out
            buf = ""
            for chunk in r.iter_text():
                out["bytes"] += len(chunk)
                buf += chunk
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    if line.startswith("data:"):
                        line = line[5:].strip()
                    try:
                        ev = json.loads(line)
                    except Exception:
                        out["events"].append({"raw": line[:200]})
                        continue
                    self._ev(out, ev)
                    if max_events and len(out["events"]) >= max_events:
                        return out
            if buf.strip():
                try:
                    self._ev(out, json.loads(buf.strip().removeprefix("data:").strip()))
                except Exception:
                    out["events"].append({"raw": buf[:200]})
        return out

    @staticmethod
    def _ev(out, ev):
        t = ev.get("type")
        slim = {k: (v if not isinstance(v, str) or len(v) < 200 else v[:200] + "...") for k, v in ev.items()}
        if len(out["events"]) < 60:
            out["events"].append(slim)
        if t == "stdout":
            out["stdout"] += ev.get("text", "") + "\n"   # execd strips the newline per event
            out["n_stdout_ev"] = out.get("n_stdout_ev", 0) + 1
        elif t == "stderr":
            out["stderr"] += ev.get("text", "") + "\n"
            out["n_stderr_ev"] = out.get("n_stderr_ev", 0) + 1
        elif t == "init":
            out["init_id"] = ev.get("text")
        elif t == "error":
            out["error"] = ev.get("error")
        elif t == "execution_complete":
            pass
        # exit code: execd reports it in error.value for non-zero, complete otherwise
        if t == "error" and isinstance(ev.get("error"), dict):
            out["exit"] = ev["error"].get("evalue") or ev["error"].get("ename")
        if t == "execution_complete" and out["exit"] is None:
            out["exit"] = 0

    def sh(self, command, **kw):
        """Convenience: run and return stdout+stderr text."""
        o = self.run(command, **kw)
        return (o["stdout"] + o["stderr"]).strip()


def cleanup():
    for sid in CREATED:
        try:
            delete(sid)
        except Exception:
            pass


def main_guard(fn):
    try:
        fn()
    finally:
        if os.environ.get("KEEP") != "1":
            cleanup()


def upload(base, path, data, meta_extra=None, timeout=120):
    """execd /files/upload: 'metadata' must itself be a *file* part (multipart filename set), then 'file'."""
    meta = {"path": path}
    meta.update(meta_extra or {})
    files = [("metadata", ("metadata.json", json.dumps(meta).encode(), "application/json")),
             ("file", (os.path.basename(path) or "f", data, "application/octet-stream"))]
    return httpx.post(base + "/files/upload", files=files, timeout=timeout)
