"""Probe b: expiry — timeout, renew forward/backward, max timeout, behaviour at expiry."""
import datetime as dt
import time

from oslib import *


def iso(t):
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def parse(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def run():
    tag = f"b{int(time.time())}"
    log("== b1 create timeout=60")
    r = create({"spike-run": tag}, timeout=60)
    log(show(r, 400))
    sid = r.json()["id"]
    created = parse(r.json()["createdAt"])
    exp = parse(r.json()["expiresAt"])
    verdict("OBSERVED", "expiresAt - createdAt", f"{(exp - created).total_seconds()}s")

    log("== b2 renew forward +60s")
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(exp + dt.timedelta(seconds=60))})
    log(show(r))
    verdict("PASS" if r.status_code == 200 else "FAIL", "renew forward")
    exp2 = parse(r.json()["expiresAt"]) if r.status_code == 200 else exp

    log("== b3 renew backward (earlier than current expiresAt but still in the future)")
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(exp2 - dt.timedelta(seconds=30))})
    log(show(r))
    verdict("PASS(rejected)" if r.status_code >= 400 else "OBSERVED(accepted: backward renew allowed)", "renew backward", f"status={r.status_code}")
    log("   now: " + show(get(sid), 400))
    exp_now = parse(get(sid).json()["expiresAt"])

    log("== b4 renew to the past")
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=10))})
    log(show(r))
    verdict("PASS(rejected)" if r.status_code >= 400 else "OBSERVED(accepted)", "renew to the past", f"status={r.status_code}")

    log("== b5 renew beyond max_sandbox_timeout_seconds (3600) from now")
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=5))})
    log(show(r))
    verdict("PASS(rejected)" if r.status_code >= 400 else "OBSERVED(accepted: renew is NOT capped by max timeout)", "renew +5h", f"status={r.status_code}")
    if r.status_code == 200:
        # put it back to short expiry so b8 can observe expiry — backward renew if allowed
        r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(exp_now)})
        log("   restore short expiry: " + show(r))

    log("== b6 create timeout above max (3601) / 59 / null")
    for t in (3601, 59):
        r = create({"spike-run": tag}, timeout=t, expect_ok=False)
        verdict("PASS(rejected)" if r.status_code >= 400 else "OBSERVED(accepted)", f"create timeout={t}", show(r, 400))
    r = create({"spike-run": tag, "kind": "no-timeout"}, timeout=None, expect_ok=False)
    verdict("OBSERVED", "create without timeout while max is configured", show(r, 400))

    log("== b7 renew with timezone offset / no fraction")
    t = dt.datetime.now(dt.timezone(dt.timedelta(hours=9))) + dt.timedelta(seconds=600)
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": t.replace(microsecond=0).isoformat()})
    log("   " + show(r))
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(exp_now)})
    log("   back: " + show(r))

    log("== b8 observe expiry of the 60s sandbox (poll GET + list)")
    cur = parse(get(sid).json()["expiresAt"])
    log(f"   expiresAt={cur.isoformat()}")
    seen = []
    deadline = time.time() + 240
    while time.time() < deadline:
        r = get(sid)
        now = dt.datetime.now(dt.timezone.utc)
        in_list = sid in [s["id"] for s in list_sb({"spike-run": tag}).json().get("items", [])]
        st = r.json()["status"]["state"] if r.status_code == 200 else r.status_code
        entry = (round((now - cur).total_seconds(), 1), st, in_list)
        if not seen or seen[-1][1:] != entry[1:]:
            log(f"   t-exp={entry[0]}s state={st} inList={in_list} {show(r, 250) if r.status_code != 200 else r.json()['status']}")
        seen.append(entry)
        if r.status_code == 404:
            break
        time.sleep(2)
    import subprocess
    log("   docker ps -a for container: " + subprocess.run(["docker", "ps", "-a", "--filter", f"label=opensandbox.io/id={sid}", "--format", "{{.Names}} {{.Status}}"], capture_output=True, text=True).stdout.strip())
    r = delete(sid)
    verdict("OBSERVED", "DELETE after expiry", show(r, 300))
    r = api.post(f"/sandboxes/{sid}/renew-expiration", json={"expiresAt": iso(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=300))})
    verdict("OBSERVED", "renew after expiry", show(r, 300))


main_guard(run)
