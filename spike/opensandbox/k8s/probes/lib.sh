# shared helpers for spike-os probes (source me)
export KUBECONFIG_CTX=kind-spike-os
API=${API:-http://127.0.0.1:8091/v1}
KEY=${KEY:-spike-key}
NS=opensandbox
IMG=${IMG:-python:3.12-slim}
k() { kubectl --context kind-spike-os "$@"; }
api() { # method path [json]
  local m=$1 p=$2; shift 2
  if [ $# -gt 0 ]; then curl -sS -X "$m" -H "OPEN-SANDBOX-API-KEY: $KEY" -H 'content-type: application/json' "$API$p" -d "$1" -w '\nHTTP %{http_code}\n';
  else curl -sS -X "$m" -H "OPEN-SANDBOX-API-KEY: $KEY" "$API$p" -w '\nHTTP %{http_code}\n'; fi
}
body() { sed '$d'; }            # strip HTTP line
code() { tail -1 | awk '{print $2}'; }
create_sb() { # json -> prints id (echo raw to stderr)
  local out; out=$(api POST /sandboxes "$1"); echo "$out" >&2; echo "$out" | body | python3 -c 'import sys,json; print(json.load(sys.stdin).get("id",""))' 2>/dev/null
}
state() { api GET /sandboxes/$1 | body | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"]["state"])' 2>/dev/null; }
wait_state() { # id state [timeout]
  local t=${3:-180} s; for i in $(seq 1 $t); do s=$(state $1); [ "$s" = "$2" ] && { echo "$s"; return 0; }; sleep 1; done; echo "TIMEOUT(last=$s)"; return 1; }
pod_of() { k -n $NS get pods -l batch-sandbox.sandbox.opensandbox.io/name=$1 -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true; }
podx() { # id cmd...  -> kubectl exec into main container
  local id=$1; shift; local p; p=$(pod_of $id); k -n $NS exec $p -c sandbox -- "$@"; }
verdict() { echo "VERDICT[$1]: $2"; }
del_sb() { api DELETE /sandboxes/$1 | code; }
