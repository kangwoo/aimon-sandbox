"""Probe g: what hardening the Docker runtime applied inside the sandbox."""
import subprocess
import time

from oslib import *


def run():
    sid = create({"spike-run": f"g{int(time.time())}"}, timeout=600).json()["id"]
    ex = Execd(sid)
    checks = [
        ("identity", "id"),
        ("caps/nnp/seccomp", "grep -E 'Cap(Inh|Prm|Eff|Bnd|Amb)|NoNewPrivs|Seccomp' /proc/self/status"),
        ("decoded CapBnd", "capsh --decode=$(awk '/CapBnd/{print $2}' /proc/self/status)"),
        ("pids limit", "cat /sys/fs/cgroup/pids.max 2>/dev/null || cat /sys/fs/cgroup/pids/pids.max"),
        ("memory limit", "cat /sys/fs/cgroup/memory.max 2>/dev/null"),
        ("cpu limit", "cat /sys/fs/cgroup/cpu.max 2>/dev/null"),
        ("rootfs writable?", "touch /usr/bin/x-probe && echo rootfs-writable; rm -f /usr/bin/x-probe"),
        ("docker socket visible?", "ls -la /var/run/docker.sock 2>&1"),
        ("mount allowed?", "mount -t tmpfs none /mnt 2>&1 | head -1"),
        ("unshare userns?", "unshare -U -r id 2>&1 | head -1"),
        ("raw socket (NET_RAW dropped)?", "ping -c1 -W1 127.0.0.1 2>&1 | head -1 || true"),
        ("chown other uid (CHOWN kept?)", "touch /tmp/c && chown 1234 /tmp/c && stat -c %u /tmp/c"),
        ("setuid escalation (nnp)", "cp /bin/bash /tmp/sb && chmod u+s /tmp/sb && su nobody -s /bin/sh -c '/tmp/sb -p -c id' 2>&1 | head -1"),
        ("execd pid/user", "ps -o user,pid,args -e | head -5"),
        ("/proc/1 environ readable", "tr '\\0' ' ' < /proc/1/environ | head -c 300"),
        ("mounts", "cat /proc/self/mountinfo | awk '{print $5, $6}' | head -20"),
    ]
    for name, cmd in checks:
        o = ex.run(cmd)
        verdict("OBSERVED", name, (o["stdout"] + o["stderr"]).strip().replace("\n", " | ")[:600])
    fmt = "user={{.Config.User}} privileged={{.HostConfig.Privileged}} capdrop={{json .HostConfig.CapDrop}} capadd={{json .HostConfig.CapAdd}} secopt={{json .HostConfig.SecurityOpt}} pids={{.HostConfig.PidsLimit}} mem={{.HostConfig.Memory}} nanocpu={{.HostConfig.NanoCpus}} ro={{.HostConfig.ReadonlyRootfs}} userns={{.HostConfig.UsernsMode}} runtime={{.HostConfig.Runtime}} tmpfs={{json .HostConfig.Tmpfs}} binds={{json .HostConfig.Binds}}"
    log("docker inspect: " + subprocess.run(["docker", "inspect", f"sandbox-{sid}", "--format", fmt], capture_output=True, text=True).stdout.strip())
    # can the create request ask for a non-root user / securityContext? try unknown fields
    r = create({"spike-run": "g-user"}, timeout=600, extra={"user": "1000"}, expect_ok=False)
    verdict("OBSERVED", "create with unknown field 'user'", show(r, 300))


main_guard(run)
