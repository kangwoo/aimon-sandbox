"""Probe f: network reachability from inside a Docker-runtime sandbox, with and without networkPolicy.

EGRESS_MODE env is only a label for the report (the server's [egress].mode decides).
"""
import subprocess
import time

from oslib import *

MODE = os.environ.get("EGRESS_MODE", "dns")


def dinspect(name, fmt):
    return subprocess.run(["docker", "inspect", name, "--format", fmt], capture_output=True, text=True).stdout.strip()


def cur(ex, url, extra=""):
    o = ex.run(f"curl -sS -o /dev/null -m 6 -w '%{{http_code}} %{{remote_ip}}' {extra} {url} 2>&1; echo \" rc=$?\"")
    return (o["stdout"] + o["stderr"]).strip().replace("\n", " ")


def battery(ex, label, host_ip, peer_ip, peer_host_port, peer_execd_port=44772):
    log(f"--- {label} (server egress mode={MODE})")
    log("   resolv: " + ex.sh("cat /etc/resolv.conf | grep -v '^#' | tr '\\n' ' '; echo; getent hosts host.docker.internal example.com || true").replace("\n", " | "))
    tests = [
        ("server via host.docker.internal:8090/health", "http://host.docker.internal:8090/health", ""),
        (f"server via host LAN IP {host_ip}:8090/health", f"http://{host_ip}:8090/health", ""),
        ("server API without key (reachable => 401)", "http://host.docker.internal:8090/v1/sandboxes", ""),
        (f"peer sandbox execd via container IP {peer_ip}:{peer_execd_port}/ping", f"http://{peer_ip}:{peer_execd_port}/ping", ""),
        (f"peer sandbox execd via host-published port {host_ip}:{peer_host_port}/ping", f"http://{host_ip}:{peer_host_port}/ping", ""),
        ("internet by name https://example.com", "https://example.com", ""),
        ("internet by IP https://1.1.1.1", "https://1.1.1.1", ""),
        ("internet by IP http://1.1.1.1", "http://1.1.1.1", ""),
        ("docker bridge gateway 172.17.0.1:8090", "http://172.17.0.1:8090/health", ""),
    ]
    res = {}
    for name, url, extra in tests:
        out = cur(ex, url, extra)
        res[name] = out
        verdict("OBSERVED", f"[{label}] {name}", out)
    o = ex.run(f"curl -sS -m 6 -XPOST http://{peer_ip}:{peer_execd_port}/command -H 'content-type: application/json' -d '{{\"command\":\"hostname; echo LATERAL-EXEC-OK\"}}' 2>&1 | grep -o 'LATERAL-EXEC-OK' | head -1 || true")
    verdict("OBSERVED", f"[{label}] run a command in the PEER sandbox through its unauthenticated execd", o["stdout"].strip() or (o["stderr"].strip()[:200] or "no"))
    o = ex.run("dig +short +time=3 +tries=1 example.com 2>&1 | head -3; dig +short +time=3 +tries=1 @8.8.8.8 example.com 2>&1 | head -2")
    verdict("OBSERVED", f"[{label}] DNS (system resolver / direct @8.8.8.8)", o["stdout"].replace("\n", " | "))
    return res


def run():
    tag = f"f{int(time.time())}"
    a = create({"spike-run": tag, "role": "a"}, timeout=900).json()["id"]
    b = create({"spike-run": tag, "role": "b"}, timeout=900).json()["id"]
    exa = Execd(a)
    host_ip = exa.base.split("//")[1].split(":")[0]
    b_ip = dinspect(f"sandbox-{b}", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}")
    b_port = Execd(b).base.split(":")[2].split("/")[0]
    log(f"host_ip={host_ip} peer B container ip={b_ip} peer B host port={b_port}")
    log("A network: " + dinspect(f"sandbox-{a}", "{{.HostConfig.NetworkMode}} {{json .NetworkSettings.Networks}}")[:400])
    battery(exa, "no networkPolicy", host_ip, b_ip, b_port)

    for label, pol in [
        ("deny + empty allow list", {"defaultAction": "deny", "egress": []}),
        ("deny + allow example.com", {"defaultAction": "deny", "egress": [{"action": "allow", "target": "example.com"}]}),
    ]:
        t0 = time.time()
        r = create({"spike-run": tag, "role": "policy"}, timeout=900, extra={"networkPolicy": pol}, expect_ok=False)
        log(f"create with networkPolicy={pol}: {r.status_code} in {time.time() - t0:.1f}s {r.text[:300]}")
        if r.status_code not in (200, 202):
            verdict("FAIL", f"create with {label}", r.text[:300])
            continue
        c = r.json()["id"]
        cname = f"sandbox-{c}"
        log("   sandbox container network: " + dinspect(cname, "{{.HostConfig.NetworkMode}} capadd={{json .HostConfig.CapAdd}}"))
        side = subprocess.run(["docker", "ps", "--filter", f"label=opensandbox.io/id={c}", "--format", "{{.Names}} {{.Image}} {{.Ports}}"], capture_output=True, text=True).stdout.strip()
        log("   containers for this sandbox: " + side.replace("\n", " | "))
        for line in side.splitlines():
            n = line.split()[0]
            if n != cname:
                log(f"   sidecar {n}: caps={dinspect(n, '{{json .HostConfig.CapAdd}}')} env={[e for e in json.loads(dinspect(n, '{{json .Config.Env}}') or '[]') if 'EGRESS' in e and 'TOKEN' not in e]}")
        pr = api.get(f"/sandboxes/{c}/networkpolicy")
        log("   GET networkpolicy: " + show(pr, 400))
        exc = Execd(c)
        battery(exc, label, host_ip, b_ip, b_port)
        o = exc.run("ip -4 addr show | grep inet; nft list ruleset 2>&1 | head -3; ip route add 10.9.9.0/24 dev eth0 2>&1 | head -1")
        verdict("OBSERVED", f"[{label}] can sandbox alter its netns? (NET_ADMIN dropped)", o["stdout"].replace("\n", " | ") + o["stderr"].replace("\n", " | "))


main_guard(run)
