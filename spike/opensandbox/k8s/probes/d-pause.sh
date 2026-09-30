#!/usr/bin/env bash
# Probe d: pause/resume on K8s (BatchSandbox spec.pause -> image-committer snapshot -> pod deleted; resume -> new pod from snapshot image)
source "$(dirname "$0")/lib.sh"
ts() { date -u +%H:%M:%S; }
ID=$(create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":900,\"resourceLimits\":{\"cpu\":\"250m\",\"memory\":\"128Mi\"},\"metadata\":{\"aimon.at/probe\":\"d\"}}" 2>/dev/null)
echo "ID=$ID $(wait_state $ID Running 120)"
exp() { api GET /sandboxes/$ID | body | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["status"]["state"], d.get("expiresAt"))'; }
echo "== before pause"; exp
podx $ID sh -c 'mkdir -p /workspace && echo root-marker > /marker-root && echo ws-marker > /workspace/marker-ws && (nohup sleep 7777 >/dev/null 2>&1 &) ; id -u; ls -l /marker-root /workspace/marker-ws; pgrep -a sleep'
k -n $NS get pod $ID-0 -o jsonpath='pod name={.metadata.name} uid={.metadata.uid} restarts={.status.containerStatuses[0].restartCount} ip={.status.podIP}{"\n"}'
echo "== pause at $(ts)"; api POST /sandboxes/$ID/pause | tail -3
for i in $(seq 1 60); do s=$(exp); p=$(k -n $NS get pods -l batch-sandbox.sandbox.opensandbox.io/name=$ID -o jsonpath='{range .items[*]}{.metadata.name}/{.metadata.uid}/{.status.phase} {end}'); echo "$(ts) api=[$s] pods=[$p] cr.phase=$(k -n $NS get batchsandbox $ID -o jsonpath='{.status.phase}')"; case "$s" in Paused*|Failed*) break;; esac; sleep 3; done
k -n $NS get pods -A | grep -i -E 'commit|snapshot' | head; k -n $NS get sandboxsnapshots -o wide 2>/dev/null | head
echo "== while paused: expiresAt flow"; exp; sleep 20; exp
echo "registry catalog: $(docker exec spike-os-control-plane curl -s http://spike-os-registry:5000/v2/_catalog)"
echo "== resume at $(ts)"; api POST /sandboxes/$ID/resume | tail -3
for i in $(seq 1 60); do s=$(exp); p=$(k -n $NS get pods -l batch-sandbox.sandbox.opensandbox.io/name=$ID -o jsonpath='{range .items[*]}{.metadata.name}/{.metadata.uid}/{.status.phase} {end}'); echo "$(ts) api=[$s] pods=[$p]"; case "$s" in Running*) break;; Failed*) break;; esac; sleep 3; done
k -n $NS get pod $ID-0 -o jsonpath='pod name={.metadata.name} uid={.metadata.uid} restarts={.status.containerStatuses[0].restartCount} image={.spec.containers[0].image} ip={.status.podIP}{"\n"}' 2>&1
k -n $NS get pods -l batch-sandbox.sandbox.opensandbox.io/name=$ID -o jsonpath='{range .items[*]}{.metadata.name} uid={.metadata.uid} image={.spec.containers[0].image} ip={.status.podIP}{"\n"}{end}'
echo "== after resume: files / processes"
P2=$(k -n $NS get pods -l batch-sandbox.sandbox.opensandbox.io/name=$ID -o jsonpath='{.items[0].metadata.name}')
k -n $NS exec $P2 -c sandbox -- sh -c 'cat /marker-root /workspace/marker-ws; pgrep -a sleep; cat /proc/1/cmdline | tr "\0" " "; echo'
exp
echo "D_ID=$ID"
