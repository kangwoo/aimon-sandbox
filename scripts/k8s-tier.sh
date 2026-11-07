#!/usr/bin/env bash
# Provisions the cluster the k8s test tier (`./gradlew :aimon-sandbox-opensandbox:k8sTest`, @Tag("k8s")) runs against,
# and runs it. The tier is manual and pre-release: release.sh asks for `--k8s-verified`, and this is how to earn it.
#
#   scripts/k8s-tier.sh up           kind cluster + OpenSandbox chart + hardened template + isolation NetworkPolicy +
#                                    RuntimeClass + the contract image loaded into the node + the pool of pool mode
#   scripts/k8s-tier.sh test         port-forward the server and run k8sTest with every check enabled (needs internet),
#                                    template and pool mode
#   scripts/k8s-tier.sh status       the cluster's nodes, the tier's pods, the pool and the sandboxes, for the record
#   scripts/k8s-tier.sh reconfigure  re-render the server's configuration (AIMON_K8S_EXECD_INIT) and restart it
#   scripts/k8s-tier.sh down         delete the cluster
#
# Every subcommand reads and writes its own kubeconfig, AIMON_K8S_KUBECONFIG (default $AIMON_K8S_WORK/kubeconfig):
# `kind create` and `kind delete` edit only that file, and neither the user's ~/.kube/config nor its current context is
# touched. To run k8sTest by hand, export KUBECONFIG to the same file (the runtime-class check runs kubectl). `up`
# refuses a cluster name that already exists, so it never adopts a cluster it did not create. AIMON_K8S_CLUSTER names
# the cluster (default aimon-k8s-tier).
#
# The server runs with `execd_run_as_init = true` (execd as the sandbox's init, OSEP-0018): in template mode execd is
# PID 1, in pool mode a subreaper under task-executor, and either way it reaps the orphans of a run (core's watchdogs).
# Pool mode needs it — task-executor as a pool pod's PID 1 reaps nothing — so this is the configuration the tier
# verifies and the one an operator deploys (docs/guide/opensandbox-kubernetes.md §9.3). AIMON_K8S_EXECD_INIT=0 leaves it out, for
# `up` and `reconfigure`, to measure the server's default.
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
pool=aimon-pool
export KUBECONFIG="${AIMON_K8S_KUBECONFIG:-$work/kubeconfig}"

# The chart values, with `execd_run_as_init = true` under [runtime] unless AIMON_K8S_EXECD_INIT=0.
values() {
  mkdir -p "$work"
  if [ "${AIMON_K8S_EXECD_INIT:-1}" != 0 ]; then
    awk '{ print } /^    \[runtime\]$/ { print "    execd_run_as_init = true" }' "$deploy/values-sr-spike-runc.yaml" \
      > "$work/values.yaml"
  else
    cp "$deploy/values-sr-spike-runc.yaml" "$work/values.yaml"
  fi
  echo "$work/values.yaml"
}

up() {
  mkdir -p "$work"
  if kind get clusters 2>/dev/null | grep -qx "$cluster"; then
    echo "kind cluster $cluster already exists: not adopting it (scripts/k8s-tier.sh down deletes it)" >&2
    exit 1
  fi
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
    && helm install opensandbox opensandbox -n opensandbox-system -f "$(values)")
  kubectl apply -f "$deploy/netpol-sandbox-isolation.yaml"

  docker build -q -t "$image" "$repo/modules/aimon-sandbox-opensandbox/src/test/docker"
  kind load docker-image "$image" --name "$cluster"

  kubectl -n opensandbox-system rollout status deploy/opensandbox-server --timeout=300s
  kubectl -n opensandbox-system rollout status deploy/opensandbox-controller-manager --timeout=300s

  kubectl apply -f "$repo/modules/aimon-sandbox-opensandbox/src/test/k8s/pool.yaml"
  # The pool's own count, not a Running pod: a pod that starts and then crash-loops is Running for a moment.
  for _ in $(seq 1 60); do
    [ "$(kubectl -n opensandbox get pool "$pool" -o jsonpath='{.status.available}')" -ge 1 ] 2>/dev/null && return 0
    sleep 5
  done
  echo "the pool $pool had no available pod within 300s" >&2
  kubectl -n opensandbox get pool,pods -o wide >&2 || true
  exit 1
}

reconfigure() {
  (cd "$work/OpenSandbox/manifests/charts" \
    && helm upgrade opensandbox opensandbox -n opensandbox-system -f "$(values)")
  kubectl -n opensandbox-system rollout restart deploy/opensandbox-server
  kubectl -n opensandbox-system rollout status deploy/opensandbox-server --timeout=300s
}

status() {
  kind get clusters
  kubectl config current-context
  kubectl get nodes -o wide
  kubectl -n opensandbox-system get pods -o wide
  kubectl -n opensandbox get pool,batchsandboxes,pods -o wide
}

run_tests() {
  kubectl config use-context "kind-$cluster" >/dev/null
  kubectl -n opensandbox-system port-forward svc/opensandbox-server "$port:80" >/dev/null 2>&1 &
  # Global, not local: the EXIT trap runs after this function has returned, and under `set -u` an out-of-scope name
  # would abort the trap — leaving the forward running and failing the script after a green run.
  forward=$!
  trap 'kill "$forward" 2>/dev/null || true' EXIT
  for _ in $(seq 1 30); do
    curl -s -o /dev/null -H "OPEN-SANDBOX-API-KEY: $api_key" "http://localhost:$port/v1/sandboxes" && break
    sleep 1
  done
  (cd "$repo" && OPENSANDBOX_K8S_ENDPOINT="http://localhost:$port" OPENSANDBOX_K8S_API_KEY="$api_key" \
    OPENSANDBOX_K8S_IMAGE="$image" OPENSANDBOX_K8S_RUNTIME_CLASS="$runtime_class" OPENSANDBOX_K8S_INTERNET=1 \
    OPENSANDBOX_K8S_POOL="$pool" \
    ./gradlew --no-daemon :aimon-sandbox-opensandbox:k8sTest --rerun-tasks "$@")
}

case "${1:-}" in
  up) up ;;
  test) shift; run_tests "$@" ;;
  status) status ;;
  reconfigure) reconfigure ;;
  down) kind delete cluster --name "$cluster" ;;
  *) echo "usage: $0 up|test|status|reconfigure|down" >&2; exit 2 ;;
esac
