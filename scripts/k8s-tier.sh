#!/usr/bin/env bash
# Provisions the cluster the k8s test tier (`./gradlew :aimon-sandbox-opensandbox:k8sTest`, @Tag("k8s")) runs against,
# and runs it. The tier is manual and pre-release: release.sh asks for `--k8s-verified`, and this is how to earn it.
#
#   scripts/k8s-tier.sh up      kind cluster + OpenSandbox chart + hardened template + isolation NetworkPolicy +
#                               RuntimeClass + the contract image loaded into the node
#   scripts/k8s-tier.sh test    port-forward the server and run k8sTest with every check enabled (needs internet)
#   scripts/k8s-tier.sh down    delete the cluster
#
# The values are the spike's (spike/opensandbox/k8s/deploy/, reports/C-k8s.md §1): docker.io images instead of the
# chart's default registry, the container-level hardened template (pod-level runAsUser breaks the privileged init
# container), `dns+nft` egress, and `[secure_runtime]` naming a runc-backed RuntimeClass so the runtime-class check
# has something to compare. Needs kind, kubectl, helm, git and docker; about 3 GB of Docker memory.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
deploy="$repo/spike/opensandbox/k8s/deploy"
cluster=${AIMON_K8S_CLUSTER:-aimon-k8s-tier}
work=${AIMON_K8S_WORK:-${TMPDIR:-/tmp}/aimon-k8s-tier}
opensandbox_commit=3738975
image=aimon-sandbox-contract:k8s
port=${AIMON_K8S_PORT:-8091}
api_key=spike-key
runtime_class=spike-runc

up() {
  mkdir -p "$work"
  if [ ! -d "$work/OpenSandbox" ]; then
    git clone -q https://github.com/opensandbox-group/OpenSandbox "$work/OpenSandbox"
  fi
  git -C "$work/OpenSandbox" checkout -q "$opensandbox_commit"

  sed "s/^name: .*/name: $cluster/" "$repo/spike/opensandbox/k8s/kind-config.yaml" > "$work/kind.yaml"
  kind create cluster --config "$work/kind.yaml"

  # The chart renders its own Namespace, so `--create-namespace` conflicts with it: create it with Helm's ownership
  # marks instead (C-k8s.md §1 step 3). The sandbox namespace is not the chart's.
  kubectl create namespace opensandbox-system --dry-run=client -o yaml \
    | kubectl label --local -f - app.kubernetes.io/managed-by=Helm -o yaml \
    | kubectl annotate --local -f - meta.helm.sh/release-name=opensandbox \
        meta.helm.sh/release-namespace=opensandbox-system -o yaml \
    | kubectl apply -f -
  kubectl create namespace opensandbox
  kubectl -n opensandbox-system create configmap spike-bs-template \
    --from-file=template.yaml="$deploy/template-hardened-ctr.yaml"
  printf 'apiVersion: node.k8s.io/v1\nkind: RuntimeClass\nmetadata: {name: %s}\nhandler: runc\n' "$runtime_class" \
    | kubectl apply -f -

  (cd "$work/OpenSandbox/manifests/charts" && helm dependency build opensandbox >/dev/null \
    && helm install opensandbox opensandbox -n opensandbox-system -f "$deploy/values-sr-spike-runc.yaml")
  kubectl apply -f "$deploy/netpol-sandbox-isolation.yaml"

  docker build -q -t "$image" "$repo/modules/aimon-sandbox-opensandbox/src/test/docker"
  kind load docker-image "$image" --name "$cluster"

  kubectl -n opensandbox-system rollout status deploy/opensandbox-server --timeout=300s
  kubectl -n opensandbox-system rollout status deploy/opensandbox-controller-manager --timeout=300s
}

run_tests() {
  kubectl config use-context "kind-$cluster" >/dev/null
  kubectl -n opensandbox-system port-forward svc/opensandbox-server "$port:80" >/dev/null 2>&1 &
  local forward=$!
  trap 'kill $forward 2>/dev/null || true' EXIT
  for _ in $(seq 1 30); do
    curl -s -o /dev/null -H "OPEN-SANDBOX-API-KEY: $api_key" "http://localhost:$port/v1/sandboxes" && break
    sleep 1
  done
  (cd "$repo" && OPENSANDBOX_K8S_ENDPOINT="http://localhost:$port" OPENSANDBOX_K8S_API_KEY="$api_key" \
    OPENSANDBOX_K8S_IMAGE="$image" OPENSANDBOX_K8S_RUNTIME_CLASS="$runtime_class" OPENSANDBOX_K8S_INTERNET=1 \
    ./gradlew --no-daemon :aimon-sandbox-opensandbox:k8sTest --rerun-tasks "$@")
}

case "${1:-}" in
  up) up ;;
  test) shift; run_tests "$@" ;;
  down) kind delete cluster --name "$cluster" ;;
  *) echo "usage: $0 up|test|down" >&2; exit 2 ;;
esac
