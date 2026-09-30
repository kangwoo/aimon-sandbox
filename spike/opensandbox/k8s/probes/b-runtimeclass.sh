#!/usr/bin/env bash
# Probe b: runtimeClassName. There is NO per-request field; it comes from server config
# ([secure_runtime].k8s_runtime_class) or the server-wide BatchSandbox template. Tested via the template.
source "$(dirname "$0")/lib.sh"; K=$(cd "$(dirname "$0")/.." && pwd)
echo "== RuntimeClass spike-runc (handler runc)"
cat <<Y | k apply -f -
apiVersion: node.k8s.io/v1
kind: RuntimeClass
metadata: {name: spike-runc}
handler: runc
Y
echo "== request-level attempt: extensions/unknown field runtimeClassName (is it rejected or ignored?)"
api POST /sandboxes "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":600,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"runtimeClassName\":\"spike-runc\",\"metadata\":{\"aimon.at/probe\":\"b0\"}}" | cut -c1-300
X=$(api GET "/sandboxes?metadata=aimon.at%2Fprobe%3Db0" | body | python3 -c 'import sys,json; print(" ".join(i["id"] for i in json.load(sys.stdin)["items"]))')
for x in $X; do echo "b0 pod runtimeClassName='$(k -n $NS get pod $x-0 -o jsonpath='{.spec.runtimeClassName}')'"; del_sb $x >/dev/null; done
echo "== template runtimeClassName: spike-runc"
$K/probes/swap-template.sh $K/deploy/template-rc-spike-runc.yaml >/dev/null; sleep 4
ID=$(create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":600,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"b1\"}}" 2>&1 | tail -1)
echo "ID=$ID -> $(wait_state $ID Running 90)"
echo "pod.spec.runtimeClassName=$(k -n $NS get pod $ID-0 -o jsonpath='{.spec.runtimeClassName}')"
del_sb $ID >/dev/null
echo "== template runtimeClassName: spike-missing (no such RuntimeClass)"
$K/probes/swap-template.sh $K/deploy/template-rc-spike-missing.yaml >/dev/null; sleep 4
T0=$(date +%s)
api POST /sandboxes "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":600,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"b2\"}}" | cut -c1-600
echo "create returned after $(( $(date +%s)-T0 ))s"
ID=$(api GET "/sandboxes?metadata=aimon.at%2Fprobe%3Db2" | body | python3 -c 'import sys,json; print(" ".join(i["id"] for i in json.load(sys.stdin)["items"]))')
echo "listed ids: '$ID'"
for i in 1 2 3 4 5 6; do [ -n "$ID" ] && echo "t+$((i*10))s state: $(api GET /sandboxes/$ID | body | cut -c1-300)"; sleep 10; done
k -n $NS get batchsandbox -l aimon.at/probe=b2 -o yaml | grep -A12 '^  status:' | head -30
k -n $NS get events --sort-by=.lastTimestamp | grep -i -E 'runtimeclass|spike-missing|FailedCreate' | tail -5
k -n opensandbox-system logs deploy/opensandbox-controller-manager --since=2m | grep -i -E 'runtimeclass|spike-missing' | tail -3 | cut -c1-400
[ -n "$ID" ] && echo "delete: $(del_sb $ID)"
