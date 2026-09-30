"""Probe h2: deleteOnSandboxTermination=true with a single user of the volume."""
import subprocess
import time

from oslib import *

v = "spike-docker-vol3"
r = create({"spike-run": "h2"}, timeout=600, extra={"volumes": [{"name": "y", "pvc": {"claimName": v, "deleteOnSandboxTermination": True}, "mountPath": "/y"}]})
sid = r.json()["id"]
log("created " + sid + " volume: " + subprocess.run(["docker", "volume", "inspect", v, "--format", "{{.Name}}"], capture_output=True, text=True).stdout.strip())
log(show(delete(sid)))
time.sleep(2)
out = subprocess.run(["docker", "volume", "inspect", v, "--format", "{{.Name}}"], capture_output=True, text=True)
verdict("OBSERVED", "deleteOnSandboxTermination=true, sole user deleted", out.stdout.strip() or ("gone: " + out.stderr.strip()))
