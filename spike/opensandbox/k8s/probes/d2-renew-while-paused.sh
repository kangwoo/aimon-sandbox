#!/usr/bin/env bash
# Probe d2: renew-expiration on a PAUSED K8s sandbox — clean repro
source "$(dirname "$0")/lib.sh"
ts() { date -u +%H:%M:%S; }
iso() { python3 -c "import datetime as d;print((d.datetime.now(d.timezone.utc)+d.timedelta(seconds=$1)).strftime('%Y-%m-%dT%H:%M:%SZ'))"; }
snap() { echo "$(ts) api=$(state $ID) cr.spec.pause=$(k -n $NS get batchsandbox $ID -o jsonpath='{.spec.pause}') cr.phase=$(k -n $NS get batchsandbox $ID -o jsonpath='{.status.phase}') gen=$(k -n $NS get batchsandbox $ID -o jsonpath='{.metadata.generation}')/pauseObs=$(k -n $NS get batchsandbox $ID -o jsonpath='{.status.pauseObservedGeneration}') pods=[$(k -n $NS get pods -l batch-sandbox.sandbox.opensandbox.io/name=$ID -o jsonpath='{range .items[*]}{.metadata.uid}:{.status.phase} {end}')]"; }
ID=$(create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":900,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"d2\"}}" 2>/dev/null); wait_state $ID Running 60 >/dev/null; snap
api POST /sandboxes/$ID/pause | code; wait_state $ID Paused 90 >/dev/null; sleep 10; snap
echo "== renew while paused at $(ts)"; api POST /sandboxes/$ID/renew-expiration "{\"expiresAt\":\"$(iso 1200)\"}" | cut -c1-200
for i in $(seq 1 12); do snap; sleep 3; done
echo "== try pause/resume API now"; api POST /sandboxes/$ID/resume | cut -c1-200
echo D2_ID=$ID
