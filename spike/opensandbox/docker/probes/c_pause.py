"""Probe c: pause/resume on Docker — does expiry keep counting while paused, renew while paused, exec/files while paused."""
import datetime as dt
import subprocess
import time

from oslib import *


def iso(t):
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def parse(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def dps(sid):
    return subprocess.run(["docker", "ps", "-a", "--filter", f"label=opensandbox.io/id={sid}", "--format", "{{.Names}} {{.Status}}"],
                          capture_output=True, text=True).stdout.strip()


def run():
    tag = f"c{int(time.time())}"

    log("== c1 pause/resume round trip keeps files + processes")
    r = create({"spike-run": tag}, timeout=600)
    sid = r.json()["id"]
    ex = Execd(sid)
    ex.run("echo before > /tmp/state; nohup sh -c 'i=0; while :; do i=$((i+1)); echo $i > /tmp/counter; sleep 1; done' >/dev/null 2>&1 &")
    time.sleep(2)
    exp_before = get(sid).json()["expiresAt"]
    r = api.post(f"/sandboxes/{sid}/pause")
    log("   " + show(r))
    st, _ = wait_state(sid, {"Paused"}, 30)
    log(f"   state after pause: {st}; docker: {dps(sid)}")
    c1 = subprocess.run(["docker", "exec", f"sandbox-{sid}", "cat", "/tmp/counter"], capture_output=True, text=True)
    log(f"   docker exec while paused -> rc={c1.returncode} {c1.stderr.strip()[:200]}")

    log("== c2 exec / files while paused")
    t0 = time.time()
    try:
        c = httpx.Client(base_url=ex.base, timeout=8)
        rr = c.post("/command", json={"command": "echo hi"})
        verdict("OBSERVED", "execd /command while paused", f"{rr.status_code} {rr.text[:200]} after {time.time()-t0:.1f}s")
    except Exception as e:
        verdict("OBSERVED", "execd /command while paused", f"{type(e).__name__}: {e} after {time.time()-t0:.1f}s")
    t0 = time.time()
    try:
        rr = httpx.get(ex.base + "/files/info", params={"path": "/tmp/state"}, timeout=8)
        verdict("OBSERVED", "execd /files/info while paused", f"{rr.status_code} {rr.text[:200]} after {time.time()-t0:.1f}s")
    except Exception as e:
        verdict("OBSERVED", "execd /files/info while paused", f"{type(e).__name__}: {e} after {time.time()-t0:.1f}s")
    r = api.get(f"/sandboxes/{sid}/endpoints/{EXECD_PORT}")
    verdict("OBSERVED", "GET endpoint while paused", show(r, 300))

    log("== c3 renew while paused")
    new = dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=900)
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(new)})
    verdict("PASS" if r.status_code == 200 else "OBSERVED", "renew while paused", show(r, 300))
    r = api.post(f"/sandboxes/{sid}/pause")
    verdict("OBSERVED", "pause when already paused", show(r, 300))

    time.sleep(3)
    r = api.post(f"/sandboxes/{sid}/resume")
    log("   resume: " + show(r))
    st, _ = wait_state(sid, {"Running"}, 30)
    log(f"   state after resume: {st}")
    n1 = ex.sh("cat /tmp/counter; cat /tmp/state")
    time.sleep(2)
    n2 = ex.sh("cat /tmp/counter")
    verdict("PASS" if n1.split()[0] != n2.split()[0] else "FAIL", "background process survives pause and continues after resume", f"{n1!r} -> {n2!r}")
    r = api.post(f"/sandboxes/{sid}/resume")
    verdict("OBSERVED", "resume when already running", show(r, 300))
    verdict("OBSERVED", "expiresAt before pause / after renew-in-pause", f"{exp_before} / {get(sid).json()['expiresAt']}")

    log("== c4 does expiry fire while paused? create timeout=60, pause immediately, wait past expiresAt")
    r = create({"spike-run": tag, "k": "short"}, timeout=60)
    sid2 = r.json()["id"]
    exp = parse(r.json()["expiresAt"])
    api.post(f"/sandboxes/{sid2}/pause")
    st, _ = wait_state(sid2, {"Paused"}, 30)
    log(f"   paused ({st}); expiresAt={exp.isoformat()}; waiting ...")
    last = None
    while dt.datetime.now(dt.timezone.utc) < exp + dt.timedelta(seconds=60):
        r = get(sid2)
        cur = (r.status_code, r.json()["status"]["state"] if r.status_code == 200 else r.text[:120])
        if cur != last:
            log(f"   t-exp={(dt.datetime.now(dt.timezone.utc) - exp).total_seconds():.1f}s -> {cur} docker={dps(sid2)!r}")
            last = cur
        if r.status_code == 404:
            break
        time.sleep(2)
    verdict("OBSERVED", "paused sandbox past expiresAt", f"final={last} docker={dps(sid2)!r}")


main_guard(run)
