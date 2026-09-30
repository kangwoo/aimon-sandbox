"""Probe i: how a client reaches execd; auth header names."""
import time

from oslib import *


def run():
    sid = create({"spike-run": f"i{int(time.time())}"}, timeout=600).json()["id"]
    for q in ["", "?use_server_proxy=true", f"?expires={int(time.time()) + 600}"]:
        r = api.get(f"/sandboxes/{sid}/endpoints/{EXECD_PORT}{q}")
        verdict("OBSERVED", f"endpoint {EXECD_PORT}{q}", show(r, 300) + f" headers={ {k: v for k, v in r.headers.items() if k.lower().startswith(('opensandbox', 'x-'))} }")
    r = api.get(f"/sandboxes/{sid}/endpoints/8080")
    verdict("OBSERVED", "endpoint for app port 8080", show(r, 300))
    r = api.get(f"/sandboxes/{sid}/endpoints/9999")
    verdict("OBSERVED", "endpoint for arbitrary port 9999", show(r, 300))
    ep = api.get(f"/sandboxes/{sid}/endpoints/{EXECD_PORT}").json()["endpoint"]
    for name, url, hdr in [
        ("direct endpoint, no headers", f"http://{ep}/ping", {}),
        ("direct host port root path /ping", "http://" + ep.split("/")[0] + "/ping", {}),
        ("server proxy WITH api key", f"{BASE}/sandboxes/{sid}/proxy/{EXECD_PORT}/ping", H),
        ("server proxy WITHOUT api key", f"{BASE}/sandboxes/{sid}/proxy/{EXECD_PORT}/ping", {}),
    ]:
        r = httpx.get(url, headers=hdr, timeout=10)
        verdict("OBSERVED", name, f"{url} -> {r.status_code} {r.text[:150]}")
    # command through the server proxy (SSE through the lifecycle server)
    with httpx.stream("POST", f"{BASE}/sandboxes/{sid}/proxy/{EXECD_PORT}/command", headers=H, json={"command": "echo via-server-proxy"}, timeout=30) as r:
        body = r.read().decode()
    verdict("OBSERVED", "/command through server proxy", f"{r.status_code} {body[:300]!r}")
    r = httpx.get(f"http://{ep}/ping", headers={"X-EXECD-ACCESS-TOKEN": "garbage"})
    verdict("OBSERVED", "direct with a garbage X-EXECD-ACCESS-TOKEN", f"{r.status_code}")


main_guard(run)
