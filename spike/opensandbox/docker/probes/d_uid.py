"""Probe d11: uid running commands vs owner of files written through the files API."""
import time

from oslib import *


def run():
    sid = create({"spike-run": f"d11-{int(time.time())}"}, timeout=600).json()["id"]
    ex = Execd(sid)
    log("== d11 uid of commands and of files written via files API")
    o = ex.run("id; ps -o user,pid,args -p 1; ps -o user,args -e | grep execd | head -3")
    verdict("OBSERVED", "command identity", o["stdout"].replace("\n", " | "))
    up = upload(ex.base, "/tmp/via-files-api.txt", b"hello")
    log(f"   upload -> {up.status_code} {up.text[:200]}")
    o = ex.run("ls -ln /tmp/via-files-api.txt; stat -c '%U %u %a' /tmp/via-files-api.txt")
    verdict("OBSERVED", "owner of a file written via files API", o["stdout"].replace("\n", " | "))
    o = ex.run("id", raw_body={"command": "id", "uid": 1000, "gid": 1000})
    verdict("OBSERVED", "uid=1000 via /command uid", f"{o['stdout'].strip()} {o['error']} {o['http_text'][:200]}")


    o = ex.run("stat -c '%U' /tmp/via-files-api.txt", raw_body={"command": "touch /tmp/as1000 && stat -c '%u' /tmp/as1000; echo hi >> /tmp/via-files-api.txt", "uid": 1000, "gid": 1000})
    verdict("OBSERVED", "uid=1000 command writing to a files-API (root-owned) file", f"{o['stdout'].strip()} {o['stderr'].strip()} {o['error']}")


main_guard(run)
