"""Probe a: lifecycle, metadata labels, list filter/pagination, idempotency."""
import base64
import hashlib
import time

from oslib import *


def h(s, upper=False):
    v = base64.b32encode(hashlib.sha256(s.encode()).digest()).decode().rstrip("=")[:32]
    return v if upper else v.lower()


def run():
    run_tag = f"r{int(time.time())}"
    good = {
        "aimon.at/managed": "true",
        "aimon.at/sandbox-key": h("dep/ws:abc/inc1/main/3"),
        "aimon.at/generation": "3",
        "aimon.at/deployment": "spike-docker",
        "spike-run": run_tag,
    }
    log("== a1 create with aimon labels (lower-case base32)", good)
    r = create(good)
    log(show(r))
    ok = r.status_code in (200, 202) and r.json().get("metadata") == good
    verdict("PASS" if ok else "FAIL", "lower-case base32 sandbox-key accepted and echoed back")
    sid1 = r.json()["id"] if r.status_code in (200, 202) else None

    up = dict(good, **{"aimon.at/sandbox-key": h("dep/ws:abc/inc1/main/3", upper=True)})
    log("== a2 create with UPPER-case base32 sandbox-key", up["aimon.at/sandbox-key"])
    r = create(up, expect_ok=False)
    log(show(r))
    verdict("OBSERVED", "upper-case base32 value", f"status={r.status_code}")

    for label, meta in [
        ("value with ':'", {"aimon.at/workspace": "ws:abc"}),
        ("value with '/'", {"aimon.at/sandbox-key": "dep/ws/1"}),
        ("64-char value", {"aimon.at/sandbox-key": "a" * 64}),
        ("63-char value", {"aimon.at/sandbox-key": "a" * 63}),
        ("empty value", {"aimon.at/x": ""}),
        ("key prefix 'opensandbox.io/'", {"opensandbox.io/id": "spoof"}),
        ("key without prefix, 64-char name", {"k" * 64: "v"}),
        ("value starting with '-'", {"aimon.at/x": "-abc"}),
    ]:
        log(f"== a3 invalid? {label}: {meta}")
        r = create(dict(meta, **{"spike-run": run_tag}), expect_ok=False)
        log("   " + show(r, 400))
        kind = "PASS(rejected)" if r.status_code >= 400 else "OBSERVED(accepted)"
        verdict(kind, f"label {label}", f"status={r.status_code}")

    log("== a4 idempotency: second create with identical labels")
    r2 = create(good)
    log(show(r2, 300))
    sid2 = r2.json()["id"] if r2.status_code in (200, 202) else None
    verdict("OBSERVED", "same labels twice", f"ids differ={sid1 != sid2} ({sid1} vs {sid2}) -> no server-side idempotency" if sid1 != sid2 else "same id")

    log("== a5 list filtered by AND of two labels")
    r = list_sb({"aimon.at/sandbox-key": good["aimon.at/sandbox-key"], "spike-run": run_tag})
    log(show(r, 1500))
    ids = [s["id"] for s in r.json().get("items", [])]
    verdict("PASS" if set(ids) >= {sid1, sid2} else "FAIL", "AND filter returns both", f"ids={ids}")
    r = list_sb({"aimon.at/sandbox-key": good["aimon.at/sandbox-key"], "spike-run": "nope"})
    verdict("PASS" if not r.json().get("items") else "FAIL", "AND filter with a non-matching 2nd label is empty", show(r, 300))
    # key containing '/' and '.' needs url-encoding of the whole k=v&k=v string (spec). Try raw unencoded style too.
    r = api.get("/sandboxes", params={"metadata": f"aimon.at/managed=true&spike-run={run_tag}"})
    verdict("OBSERVED", "metadata filter sent as single param 'k=v&k=v'", f"n={len(r.json().get('items', []))} {show(r, 200)}")
    r = api.get("/sandboxes", params=[("metadata", "aimon.at/managed=true"), ("metadata", f"spike-run={run_tag}")])
    verdict("OBSERVED", "metadata filter sent as repeated params (does it AND, or keep only one?)", f"n={len(r.json().get('items', []))} status={r.status_code} ids={[s['id'][:8] for s in r.json().get('items', [])]}")
    r = list_sb({"aimon.at/sandbox-key": good["aimon.at/sandbox-key"].upper()})
    verdict("OBSERVED", "filter value case-sensitivity (upper-case key value)", f"ids={[s['id'][:8] for s in r.json().get('items', [])]}")

    log("== a6 pagination")
    for i in range(3):
        create(dict(good, **{"aimon.at/generation": str(10 + i)}))
    seen = []
    total = list_sb({"spike-run": run_tag}).json()["pagination"]["totalItems"]
    for page in range(1, 7):
        r = list_sb({"spike-run": run_tag}, page=page, page_size=2)
        j = r.json()
        log(f"   page={page}: ids={[s['id'][:8] for s in j.get('items', [])]} pagination={j.get('pagination')}")
        seen += [s["id"] for s in j.get("items", [])]
    verdict("PASS" if len(seen) == len(set(seen)) == total else "FAIL", f"pagination pageSize=2 visits all {total} exactly once (order: createdAt desc?)", f"n={len(seen)} uniq={len(set(seen))}")
    r = list_sb({"spike-run": run_tag}, page_size=1000)
    verdict("OBSERVED", "pageSize=1000", show(r, 200))
    r = api.get("/sandboxes", params={"page": 0})
    verdict("OBSERVED", "page=0", show(r, 200))

    log("== a7 terminated sandboxes in list / GET after delete")
    r = delete(sid2)
    log("   " + show(r))
    time.sleep(2)
    r = list_sb({"spike-run": run_tag})
    ids = [s["id"] for s in r.json().get("items", [])]
    verdict("OBSERVED", "deleted sandbox appears in list?", f"{sid2 in ids}")
    r = list_sb({"spike-run": run_tag}, states=["Terminated"])
    verdict("OBSERVED", "list state=Terminated", show(r, 300))
    r = get(sid2)
    verdict("OBSERVED", "GET after DELETE", show(r, 300))
    r = delete(sid2)
    verdict("OBSERVED", "DELETE twice", show(r, 300))
    r = delete("00000000-0000-0000-0000-000000000000")
    verdict("OBSERVED", "DELETE never-existed id", show(r, 300))
    r = get("00000000-0000-0000-0000-000000000000")
    verdict("OBSERVED", "GET never-existed id", show(r, 300))

    log("== a8 metadata PATCH (label mutation after create)")
    r = api.patch(f"/sandboxes/{sid1}/metadata", json={"aimon.at/generation": "4"})
    verdict("OBSERVED", "PATCH metadata", show(r, 400))
    log("   " + show(get(sid1), 600))

    log("== a9 auth")
    r = httpx.get(BASE + "/sandboxes")
    verdict("PASS" if r.status_code == 401 else "FAIL", "no API key -> 401", show(r, 200))
    r = httpx.get(BASE + "/sandboxes", headers={"OPEN-SANDBOX-API-KEY": "wrong"})
    verdict("PASS" if r.status_code == 401 else "FAIL", "wrong API key", show(r, 200))


main_guard(run)
