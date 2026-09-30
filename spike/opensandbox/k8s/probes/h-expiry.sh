#!/usr/bin/env bash
# Probe h (+d2): renew forward/backward, expiry of a running and of a PAUSED sandbox, hardened template pause
source "$(dirname "$0")/lib.sh"
ts() { date -u +%H:%M:%S; }
iso() { python3 -c "import datetime as d;print((d.datetime.now(d.timezone.utc)+d.timedelta(seconds=$1)).strftime('%Y-%m-%dT%H:%M:%SZ'))"; }
mk() { create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":$1,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"$2\"}}" 2>/dev/null; }
info() { api GET /sandboxes/$1 | body | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("status",{}).get("state"), d.get("expiresAt"), d.get("code",""))'; }
echo "== timeout below 60 -> ?"; api POST /sandboxes "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":30,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"}}" | cut -c1-200
echo "== timeout omitted (null) on K8s -> ?"; api POST /sandboxes "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"h-null\"}}" | cut -c1-300
R=$(mk 60 h-run); Q=$(mk 60 h-paused); echo "R=$R Q=$Q"; wait_state $R Running 60 >/dev/null; wait_state $Q Running 60 >/dev/null
echo "R: $(info $R)   CR spec.expireTime=$(k -n $NS get batchsandbox $R -o jsonpath='{.spec.expireTime}')"
echo "== renew R backward (now+65s < current? try past time)"; api POST /sandboxes/$R/renew-expiration "{\"expiresAt\":\"$(iso -30)\"}" | cut -c1-250
echo "== renew R to now+50s (earlier than current expiry ~now+60)"; api POST /sandboxes/$R/renew-expiration "{\"expiresAt\":\"$(iso 50)\"}" | cut -c1-250; echo "R: $(info $R)"
echo "== renew R forward to now+120s"; api POST /sandboxes/$R/renew-expiration "{\"expiresAt\":\"$(iso 120)\"}" | cut -c1-250
echo "R: $(info $R)   CR spec.expireTime=$(k -n $NS get batchsandbox $R -o jsonpath='{.spec.expireTime}')"
echo "== renew beyond max_sandbox_timeout_seconds (86400) -> ?"; api POST /sandboxes/$R/renew-expiration "{\"expiresAt\":\"$(iso 90000)\"}" | cut -c1-250
echo "== Q: start background proc, then pause (hardened template)"
podx $Q sh -c 'id -u; (nohup sleep 7777 >/dev/null 2>&1 &); echo hello > /tmp/rootfs-marker; for d in /proc/[0-9]*; do echo "${d#/proc/} $(tr "\0" " " < $d/cmdline)"; done'
api POST /sandboxes/$Q/pause | code; wait_state $Q Paused 90
echo "Q paused: $(info $Q) pods=$(k -n $NS get pods -l batch-sandbox.sandbox.opensandbox.io/name=$Q --no-headers 2>/dev/null | wc -l)"
echo "== renew while paused"; api POST /sandboxes/$Q/renew-expiration "{\"expiresAt\":\"$(iso 75)\"}" | cut -c1-250; echo "Q: $(info $Q)"
echo "== watch until both expire"
for i in $(seq 1 50); do echo "$(ts) R=[$(info $R)] Q=[$(info $Q)] CRs=$(k -n $NS get batchsandbox -l 'aimon.at/probe in (h-run,h-paused)' --no-headers 2>/dev/null | awk '{print $1}' | cut -c1-8 | tr '\n' ,) pods=$(k -n $NS get pods -l 'aimon.at/probe in (h-run,h-paused)' --no-headers 2>/dev/null | awk '{print $1":"$3}' | tr '\n' ,)"; 
  r=$(info $R); q=$(info $Q); case "$r$q" in *SANDBOX_NOT_FOUND*SANDBOX_NOT_FOUND*) break;; esac; sleep 5; done
echo "== after expiry: API GET"; api GET /sandboxes/$R | cut -c1-200; api GET /sandboxes/$Q | cut -c1-200
echo "== controller log"; k -n opensandbox-system logs deploy/opensandbox-controller-manager --since=5m | grep -i 'expired' | cut -c1-250 | tail -4
echo "== snapshot image left in registry?"; docker exec spike-os-control-plane curl -s http://spike-os-registry:5000/v2/_catalog
k -n $NS get sandboxsnapshots 2>&1 | head -3
