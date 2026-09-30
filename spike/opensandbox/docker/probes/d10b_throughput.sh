#!/bin/bash
# d10b: raw curl throughput of execd /command output. Needs the server running on :8090.
set -u
K="OPEN-SANDBOX-API-KEY: ${OS_KEY:-spike-docker-key-1234567890}"
B=http://localhost:8090/v1
ID=$(curl -s -XPOST $B/sandboxes -H "$K" -H 'content-type: application/json' -d '{"image":{"uri":"spike-docker-base:1"},"entrypoint":["sleep","infinity"],"timeout":600,"resourceLimits":{"cpu":"500m","memory":"512Mi"},"metadata":{"spike-run":"d10b"}}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
EP=$(curl -s $B/sandboxes/$ID/endpoints/44772 -H "$K" | python3 -c 'import sys,json;print(json.load(sys.stdin)["endpoint"])')
run() { # label, command
  python3 - "$EP" "$1" "$2" <<'PY'
import json, subprocess, sys, time
ep, label, cmd = sys.argv[1:]
t0 = time.time()
p = subprocess.run(["curl", "-s", "-XPOST", f"http://{ep}/command", "-H", "content-type: application/json", "-d", json.dumps({"command": cmd})], capture_output=True)
lines = [l for l in p.stdout.split(b"\n") if l.strip()]
n = sum(1 for l in lines if b'"stdout"' in l)
print(f"{label}: sse_bytes={len(p.stdout)} stdout_events={n} time={time.time()-t0:.1f}s last={lines[-1][:120] if lines else b''}")
PY
}
echo "## d10b raw curl throughput (sandbox $ID)"
run "10MB as 100-byte lines" "yes 012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678 | head -c 10000000"
run "10MB as one line" "head -c 10000000 /dev/zero | tr '\\0' x"
run "10MB as 64KiB lines" "yes \$(head -c 65535 /dev/zero | tr '\\0' y) | head -c 10000000"
curl -s -XDELETE $B/sandboxes/$ID -H "$K"
