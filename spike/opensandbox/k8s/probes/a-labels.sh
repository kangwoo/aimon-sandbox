#!/usr/bin/env bash
# Probe a: metadata -> K8s labels, list filter by labels
source "$(dirname "$0")/lib.sh"
KEY1=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567   # 32-char base32
KEY2=QRSTUVWXYZ234567ABCDEFGHIJKLMNOP
mk() { create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":1800,\"resourceLimits\":{\"cpu\":\"250m\",\"memory\":\"128Mi\"},\"metadata\":{\"aimon.at/managed\":\"true\",\"aimon.at/sandbox-key\":\"$1\",\"aimon.at/deployment\":\"spike\"}}"; }
echo "== create two sandboxes"; A=$(mk $KEY1); B=$(mk $KEY2); echo "A=$A B=$B"
wait_state $A Running 120; wait_state $B Running 120
echo "== BatchSandbox CR labels/annotations (A)"
k -n $NS get batchsandbox $A -o jsonpath='{.metadata.labels}{"\n"}{.metadata.annotations}{"\n"}'
echo "== Pod labels (A)"; k -n $NS get pod $A-0 -o jsonpath='{.metadata.labels}{"\n"}'
echo "== kubectl selector aimon.at/sandbox-key=$KEY1"; k -n $NS get batchsandbox,pod -l aimon.at/sandbox-key=$KEY1 -o name
echo "== API list filter metadata=aimon.at/sandbox-key=$KEY1 (URL-encoded)"
Q=$(python3 -c "import urllib.parse as u;print(u.quote(u.urlencode({'aimon.at/sandbox-key':'$KEY1'})))")
api GET "/sandboxes?metadata=$Q" | body | python3 -c 'import sys,json; d=json.load(sys.stdin); print([(i["id"],i["metadata"]) for i in d["items"]])'
echo "== API list filter managed=true&deployment=spike (AND)"
Q=$(python3 -c "import urllib.parse as u;print(u.quote(u.urlencode({'aimon.at/managed':'true','aimon.at/deployment':'spike'})))")
api GET "/sandboxes?metadata=$Q" | body | python3 -c 'import sys,json; d=json.load(sys.stdin); print(len(d["items"]), sorted(i["id"] for i in d["items"]))'
echo "== invalid label value (contains '/' and 70 chars): what does create do?"
api POST /sandboxes "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":600,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/bad\":\"a/b\"}}" | cut -c1-400
api POST /sandboxes "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":600,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/long\":\"$(printf 'x%.0s' $(seq 1 70))\"}}" | cut -c1-400
echo "== reserved prefix opensandbox.io/ in metadata"
api POST /sandboxes "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":600,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"opensandbox.io/id\":\"spoof\"}}" | cut -c1-400
echo "== PATCH metadata (update labels) on A"
api PATCH /sandboxes/$A/metadata '{"aimon.at/gen":"2"}' | cut -c1-300
k -n $NS get batchsandbox $A -o jsonpath='{.metadata.labels}{"\n"}'; k -n $NS get pod $A-0 -o jsonpath='{.metadata.labels}{"\n"}'
echo "A=$A B=$B" > /tmp/spike-os-a.ids 2>/dev/null || true
for x in $A $B; do del_sb $x; done
# leave any sandbox created by the invalid-label calls to cleanup below
for id in $(api GET "/sandboxes?pageSize=200" | body | python3 -c 'import sys,json; [print(i["id"]) for i in json.load(sys.stdin)["items"] if "aimon.at/bad" in (i.get("metadata") or {}) or "aimon.at/long" in (i.get("metadata") or {}) or (i.get("metadata") or {}).get("opensandbox.io/id")=="spoof"]'); do echo "cleanup $id: $(del_sb $id)"; done
