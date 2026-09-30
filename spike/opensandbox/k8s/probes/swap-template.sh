#!/usr/bin/env bash
# swap the server's BatchSandbox template (server-wide) and restart the server
set -e; f=$1
kubectl --context kind-spike-os -n opensandbox-system create configmap spike-bs-template --from-file=template.yaml=$f -o yaml --dry-run=client | kubectl --context kind-spike-os apply -f - >/dev/null
kubectl --context kind-spike-os -n opensandbox-system rollout restart deploy/opensandbox-server >/dev/null
kubectl --context kind-spike-os -n opensandbox-system rollout status deploy/opensandbox-server --timeout=120s
for i in $(seq 1 30); do curl -sf localhost:8091/health >/dev/null && break; sleep 1; done
