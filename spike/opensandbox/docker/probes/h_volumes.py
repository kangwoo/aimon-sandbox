"""Probe h: named volumes on the Docker runtime (pvc backend -> docker named volume)."""
import subprocess
import time

from oslib import *

VOL = "spike-docker-vol1"


def dv(*a):
    return subprocess.run(["docker", "volume", *a], capture_output=True, text=True)


def run():
    tag = f"h{int(time.time())}"
    log("pre: " + (dv("inspect", VOL).stdout.strip()[:200] or "volume absent"))
    rw = {"name": "ws", "pvc": {"claimName": VOL, "createIfNotExists": True, "storage": "1Gi", "accessModes": ["ReadWriteMany"]}, "mountPath": "/workspace"}
    r = create({"spike-run": tag, "role": "rw"}, timeout=900, extra={"volumes": [rw]}, expect_ok=False)
    log("create rw: " + show(r, 400))
    a = r.json()["id"]
    log("docker volume inspect: " + dv("inspect", VOL).stdout.strip().replace("\n", " "))
    ro = dict(rw, readOnly=True)
    r = create({"spike-run": tag, "role": "ro"}, timeout=900, extra={"volumes": [ro]}, expect_ok=False)
    log("create ro: " + show(r, 300))
    b = r.json()["id"]
    exa, exb = Execd(a), Execd(b)
    verdict("OBSERVED", "A writes", exa.sh("echo from-a > /workspace/shared.txt && id -u && stat -c '%U %a' /workspace /workspace/shared.txt"))
    verdict("OBSERVED", "B reads", exb.sh("cat /workspace/shared.txt"))
    verdict("OBSERVED", "B writes (ro mount)", exb.sh("echo from-b >> /workspace/shared.txt; echo rc=$?"))
    up = upload(exb.base, "/workspace/via-files.txt", b"x")
    verdict("OBSERVED", "B files API upload into ro mount", show(up, 300))
    log("mounts A: " + subprocess.run(["docker", "inspect", f"sandbox-{a}", "--format", "{{json .Mounts}}"], capture_output=True, text=True).stdout.strip())
    log("mounts B: " + subprocess.run(["docker", "inspect", f"sandbox-{b}", "--format", "{{json .Mounts}}"], capture_output=True, text=True).stdout.strip())

    r = create({"spike-run": tag}, timeout=600, extra={"volumes": [{"name": "x", "pvc": {"claimName": "spike-docker-absent", "createIfNotExists": False}, "mountPath": "/x"}]}, expect_ok=False)
    verdict("OBSERVED", "createIfNotExists=false on a missing volume", show(r, 300))
    r = create({"spike-run": tag}, timeout=600, extra={"volumes": [{"name": "x", "host": {"path": "/tmp"}, "mountPath": "/x"}]}, expect_ok=False)
    verdict("OBSERVED", "host bind with empty allowed_host_paths", show(r, 300))
    r = create({"spike-run": tag}, timeout=600, extra={"volumes": [{"name": "x", "pvc": {"claimName": "Bad_Name"}, "mountPath": "/x"}]}, expect_ok=False)
    verdict("OBSERVED", "invalid claimName", show(r, 300))

    log("== delete paths")
    rm = dv("rm", VOL)
    verdict("OBSERVED", "docker volume rm while mounted", f"rc={rm.returncode} {rm.stderr.strip()}")
    delete(a); delete(b)
    time.sleep(2)
    verdict("OBSERVED", "volume after both sandboxes deleted (deleteOnSandboxTermination default false)", dv("inspect", VOL, "--format", "{{.Name}} {{json .Labels}}").stdout.strip() or "gone")
    # deleteOnSandboxTermination=true on an auto-created volume
    v2 = "spike-docker-vol2"
    r = create({"spike-run": tag}, timeout=600, extra={"volumes": [{"name": "y", "pvc": {"claimName": v2, "deleteOnSandboxTermination": True}, "mountPath": "/y"}]}, expect_ok=False)
    c = r.json()["id"]
    log("vol2 labels: " + dv("inspect", v2, "--format", "{{json .Labels}}").stdout.strip())
    # second sandbox sharing vol2 while first still alive
    r = create({"spike-run": tag}, timeout=600, extra={"volumes": [{"name": "y", "pvc": {"claimName": v2}, "mountPath": "/y"}]}, expect_ok=False)
    d = r.json()["id"]
    delete(c)
    time.sleep(2)
    verdict("OBSERVED", "deleteOnSandboxTermination=true: creator deleted while another sandbox still mounts it", dv("inspect", v2, "--format", "{{.Name}}").stdout.strip() or ("gone " + dv("inspect", v2).stderr.strip()))
    delete(d)
    time.sleep(2)
    verdict("OBSERVED", "vol2 after all users deleted", dv("inspect", v2, "--format", "{{.Name}}").stdout.strip() or "gone")
    rm = dv("rm", VOL)
    verdict("OBSERVED", "docker volume rm after unmount", f"rc={rm.returncode} {rm.stdout.strip()} {rm.stderr.strip()}")
    dv("rm", v2)


main_guard(run)
