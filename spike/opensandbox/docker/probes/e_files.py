"""Probe e: execd files API."""
import time

from oslib import *


def up(ex, path, data, meta_extra=None):
    return upload(ex.base, path, data, meta_extra)


def run():
    tag = f"e{int(time.time())}"
    sid = create({"spike-run": tag}, timeout=900).json()["id"]
    ex = Execd(sid)
    c = httpx.Client(base_url=ex.base, timeout=60)
    ex.run("mkdir -p /work/a/b && echo 1 > /work/a/one.txt && echo 2 > /work/a/b/two.txt && ln -s /work/a/one.txt /work/link && mkdir /work/empty")

    log("== e1 stat metadata + mtime resolution")
    r = c.get("/files/info", params={"path": "/work/a/one.txt"})
    log("   " + show(r))
    r1 = up(ex, "/work/m.txt", b"first")
    s1 = c.get("/files/info", params={"path": "/work/m.txt"}).json()
    r2 = up(ex, "/work/m.txt", b"second!")
    s2 = c.get("/files/info", params={"path": "/work/m.txt"}).json()
    log(f"   upload1={r1.status_code} upload2={r2.status_code}")
    log(f"   stat1={s1}\n   stat2={s2}")
    m1, m2 = s1["/work/m.txt"]["modified_at"], s2["/work/m.txt"]["modified_at"]
    verdict("OBSERVED", "mtime format", f"{m1!r} vs {m2!r}; changed={m1 != m2}")
    o = ex.run("stat -c '%y %s' /work/m.txt")
    verdict("OBSERVED", "kernel mtime (stat inside)", o["stdout"].strip())
    # same size, same second
    up(ex, "/work/n.txt", b"aaaa")
    a = c.get("/files/info", params={"path": "/work/n.txt"}).json()["/work/n.txt"]
    up(ex, "/work/n.txt", b"bbbb")
    b = c.get("/files/info", params={"path": "/work/n.txt"}).json()["/work/n.txt"]
    verdict("PASS" if a != b else "FAIL", "same-size rewrite within a second is detectable from stat", f"{a} -> {b}")
    verdict("OBSERVED", "any hash/etag field in stat", f"keys={sorted(a.keys())}")
    d = c.get("/files/download", params={"path": "/work/n.txt"})
    verdict("OBSERVED", "download response headers (etag?)", str({k: v for k, v in d.headers.items()}))
    r = c.get("/files/info", params=[("path", "/work/a/one.txt"), ("path", "/work/nope")])
    verdict("OBSERVED", "multi-path stat with one missing", show(r, 400))
    r = c.get("/files/info", params={"path": "/work/a"})
    verdict("OBSERVED", "stat of a directory", show(r, 300))
    r = c.get("/files/info", params={"path": "/work/link"})
    verdict("OBSERVED", "stat of a symlink (follows?)", show(r, 300))

    log("== e2 write mode / owner / mkdir parents")
    r = up(ex, "/work/deep/new/dir/f.sh", b"#!/bin/sh\necho hi\n", {"mode": 755})
    verdict("OBSERVED", "upload into non-existent parent dirs", show(r, 300))
    o = ex.run("stat -c '%a %U:%G %s' /work/deep/new/dir/f.sh /work/m.txt; stat -c '%a' /work/deep/new/dir")
    verdict("OBSERVED", "resulting modes (upload mode=755 vs default)", o["stdout"].replace("\n", " | ") + o["stderr"])
    r = up(ex, "/work/m2.txt", b"x", {"mode": 600, "owner": "nobody", "group": "nogroup"})
    o = ex.run("stat -c '%a %U:%G' /work/m2.txt")
    verdict("OBSERVED", "upload with owner=nobody mode=600", f"{r.status_code} {o['stdout'].strip()} {o['stderr'].strip()}")
    r = up(ex, "/proc/forbidden", b"x")
    verdict("OBSERVED", "upload to unwritable path", show(r, 300))
    r = c.post("/directories", json={"/work/mk/p/q": {"mode": 700}})
    verdict("OBSERVED", "mkdir -p via /directories", show(r, 200) + " " + ex.sh("stat -c '%a' /work/mk/p/q"))
    r = c.post("/directories", json={"/work/mk/p/q": {"mode": 700}})
    verdict("OBSERVED", "mkdir on existing dir", show(r, 200))
    r = c.post("/directories", json={"/work/m.txt": {"mode": 755}})
    verdict("OBSERVED", "mkdir where a file exists", show(r, 200))

    log("== e3 list / search")
    r = c.get("/directories/list", params={"path": "/work"})
    verdict("OBSERVED", "/directories/list depth=1", show(r, 1500))
    r = c.get("/directories/list", params={"path": "/work", "depth": 3})
    verdict("OBSERVED", "/directories/list depth=3", show(r, 2500))
    r = c.get("/directories/list", params={"path": "/nope"})
    verdict("OBSERVED", "/directories/list missing dir", show(r, 300))
    r = c.get("/directories/list", params={"path": "/work/m.txt"})
    verdict("OBSERVED", "/directories/list on a file", show(r, 300))
    r = c.get("/files/search", params={"path": "/work"})
    verdict("OBSERVED", "/files/search default pattern", show(r, 2000))
    r = c.get("/files/search", params={"path": "/work", "pattern": "*.txt"})
    verdict("OBSERVED", "/files/search *.txt (recursive?)", show(r, 1500))
    r = c.get("/files/search", params={"path": "/work", "pattern": "**/*.txt"})
    verdict("OBSERVED", "/files/search **/*.txt", show(r, 1500))
    r = c.get("/files/search", params={"path": "/nope"})
    verdict("OBSERVED", "/files/search missing root", show(r, 300))
    ex.run("mkdir -p /work/many && cd /work/many && for i in $(seq 1 5000); do : > f$i; done")
    t0 = time.time()
    r = c.get("/directories/list", params={"path": "/work/many"})
    verdict("OBSERVED", "list 5000 entries (no limit param)", f"{r.status_code} n={len(r.json())} bytes={len(r.content)} {time.time() - t0:.2f}s")

    log("== e4 move")
    up(ex, "/work/src.txt", b"src")
    up(ex, "/work/dst.txt", b"dst")
    r = c.post("/files/mv", json=[{"src": "/work/src.txt", "dest": "/work/dst.txt"}])
    verdict("OBSERVED", "mv onto existing file (overwrite?)", show(r, 300) + " dst=" + ex.sh("cat /work/dst.txt; ls /work/src.txt 2>&1"))
    r = c.post("/files/mv", json=[{"src": "/work/nope.txt", "dest": "/work/x.txt"}])
    verdict("OBSERVED", "mv missing src", show(r, 300))
    r = c.post("/files/mv", json=[{"src": "/work/dst.txt", "dest": "/work/newdir/x.txt"}])
    verdict("OBSERVED", "mv into missing parent dir", show(r, 300) + " -> " + ex.sh("ls -la /work/newdir 2>&1 | tail -1"))
    r = c.post("/files/mv", json=[{"src": "/work/a", "dest": "/work/a-moved"}])
    verdict("OBSERVED", "mv a directory", show(r, 300) + " " + ex.sh("ls /work"))
    up(ex, "/work/f3.txt", b"f3")
    r = c.post("/files/mv", json=[{"src": "/work/f3.txt", "dest": "/work/empty"}])
    verdict("OBSERVED", "mv file onto an existing empty directory", show(r, 300) + " " + ex.sh("ls -la /work/empty; ls -la /work/f3.txt 2>&1"))

    log("== e5 missing-file errors")
    for name, req in [
        ("download missing", lambda: c.get("/files/download", params={"path": "/work/missing"})),
        ("stat missing", lambda: c.get("/files/info", params={"path": "/work/missing"})),
        ("delete missing file", lambda: c.delete("/files", params={"path": "/work/missing"})),
        ("delete missing dir", lambda: c.delete("/directories", params={"path": "/work/missing-dir"})),
        ("delete a directory via /files", lambda: c.delete("/files", params={"path": "/work/mk"})),
        ("download a directory", lambda: c.get("/files/download", params={"path": "/work/mk"})),
        ("relative path download", lambda: c.get("/files/download", params={"path": "work/m.txt"})),
    ]:
        try:
            verdict("OBSERVED", name, show(req(), 300))
        except Exception as e:
            verdict("OBSERVED", name, f"transport error {type(e).__name__}: {e}")

    log("== e6 range read")
    up(ex, "/work/r.bin", bytes(range(256)) * 4)
    r = c.get("/files/download", params={"path": "/work/r.bin"}, headers={"Range": "bytes=10-19"})
    verdict("PASS" if r.status_code == 206 and r.content == bytes(range(10, 20)) else "FAIL", "Range bytes=10-19", f"{r.status_code} {r.headers.get('content-range')} len={len(r.content)}")
    r = c.get("/files/download", params={"path": "/work/r.bin"}, headers={"Range": "bytes=1000-"})
    verdict("OBSERVED", "Range bytes=1000- (open-ended)", f"{r.status_code} {r.headers.get('content-range')} len={len(r.content)}")
    r = c.get("/files/download", params={"path": "/work/r.bin"}, headers={"Range": "bytes=5000-6000"})
    verdict("OBSERVED", "Range beyond EOF", show(r, 200))
    r = c.get("/files/download", params={"path": "/work/r.bin"}, headers={"Range": "bytes=0-1,5-6"})
    verdict("OBSERVED", "multi-range", f"{r.status_code} ct={r.headers.get('content-type')} len={len(r.content)}")
    ex.run("printf 'l1\\nl2\\nl3\\nl4\\n' > /work/lines.txt")
    r = c.get("/files/download", params={"path": "/work/lines.txt", "offset": 2, "limit": 2})
    verdict("OBSERVED", "line-based read offset=2 limit=2", show(r, 200))

    log("== e7 big upload/download (50 MB) round-trip")
    blob = os.urandom(50 * 1024 * 1024)
    t0 = time.time()
    r = up(ex, "/work/big.bin", blob)
    t1 = time.time()
    d = c.get("/files/download", params={"path": "/work/big.bin"})
    verdict("PASS" if d.content == blob else "FAIL", "50 MB upload+download identical", f"up={r.status_code} {t1 - t0:.1f}s down={d.status_code} {time.time() - t1:.1f}s")

    log("== e8 replace + permissions endpoints exist")
    r = c.post("/files/replace", params={"verbose": "true"}, json={"/work/lines.txt": {"old": "l2", "new": "L2"}})
    verdict("OBSERVED", "replace", show(r, 200))


main_guard(run)
