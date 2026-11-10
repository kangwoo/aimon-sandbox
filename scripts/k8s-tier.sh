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
# AIMON_K8S_NODES (default 1) is the cluster's node count for `up`: more than 1 adds that many minus one kind workers,
# and the control plane keeps its NoSchedule taint, so the server, the pools' pods and the sandboxes run on workers
# and the tier's checks cross nodes (the server reaches a sandbox on another node; `kind load` puts the image on every
# node). `test` records where each pod of the sandbox namespace was scheduled, in $AIMON_K8S_WORK/placement.txt
# (pod, node, then the count per node), and prints it; `status` shows the nodes and the pods' nodes.
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
opensandbox_commit=release-1.2.0
image=aimon-sandbox-contract:k8s
port=${AIMON_K8S_PORT:-8091}
api_key=spike-key
runtime_class=spike-runc
pool=aimon-pool
nodes=${AIMON_K8S_NODES:-1}
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
  else
    # A clone left by an earlier pin may predate the tag.
    git -C "$work/OpenSandbox" fetch -q --tags origin
  fi
  git -C "$work/OpenSandbox" checkout -q "$opensandbox_commit"

  case "$nodes" in
    '' | *[!0-9]* | 0) echo "AIMON_K8S_NODES must be a positive number of nodes, not '$nodes'" >&2; exit 2 ;;
  esac
  sed "s/^name: .*/name: $cluster/" "$repo/spike/opensandbox/k8s/kind-config.yaml" > "$work/kind.yaml"
  # A counter, not `seq 2 "$nodes"`: BSD seq counts down, so on macOS one node would get two workers.
  for ((n = 1; n < nodes; n++)); do
    echo "- role: worker" >> "$work/kind.yaml"
  done
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

# $work/placement.txt from the pod watch: each pod of the sandbox namespace and the node it was scheduled on, then how
# many landed on each node, then the server's and the controller's, from which every check reaches the sandboxes.
placement() {
  {
    awk '$2 != "<none>" && !seen[$1]++ { print; count[$2]++ }
         END { print "--- pods per node"; for (n in count) print n, count[n] }' "$work/placement.watch"
    echo "--- the server and controller (namespace opensandbox-system)"
    kubectl -n opensandbox-system get pods --no-headers \
      -o custom-columns=POD:.metadata.name,NODE:.spec.nodeName 2>&1
  } > "$work/placement.txt"
  echo "pods of namespace opensandbox by node ($work/placement.txt):"
  cat "$work/placement.txt"
}

# Appends each pod of the sandbox namespace and its node to $work/placement.watch until it is killed. The API server
# ends a watch after its timeout (minutes, randomized), so a watch that ends is started again; the new one lists the
# pods that exist again, which placement() counts once. TERM stops the watch in progress, not just this loop.
watch_pods() {
  local pid=
  trap 'kill "$pid" 2>/dev/null; exit 0' TERM
  while :; do
    kubectl -n opensandbox get pods --watch --no-headers \
      -o custom-columns=POD:.metadata.name,NODE:.spec.nodeName >> "$work/placement.watch" 2>/dev/null &
    pid=$!
    wait "$pid" || true
    sleep 1
  done
}

run_tests() {
  kubectl config use-context "kind-$cluster" >/dev/null
  kubectl -n opensandbox-system port-forward svc/opensandbox-server "$port:80" >/dev/null 2>&1 &
  # Global, not local: the EXIT trap runs after this function has returned, and under `set -u` an out-of-scope name
  # would abort the trap — leaving the forward running and failing the script after a green run.
  forward=$!
  # Every pod the run creates in the sandbox namespace, as it is scheduled: the sandboxes are deleted by the end.
  : > "$work/placement.watch"
  watch_pods &
  watch=$!
  trap 'kill "$forward" "$watch" 2>/dev/null || true' EXIT
  for _ in $(seq 1 30); do
    curl -s -o /dev/null -H "OPEN-SANDBOX-API-KEY: $api_key" "http://localhost:$port/v1/sandboxes" && break
    sleep 1
  done
  (cd "$repo" && OPENSANDBOX_K8S_ENDPOINT="http://localhost:$port" OPENSANDBOX_K8S_API_KEY="$api_key" \
    OPENSANDBOX_K8S_IMAGE="$image" OPENSANDBOX_K8S_RUNTIME_CLASS="$runtime_class" OPENSANDBOX_K8S_INTERNET=1 \
    OPENSANDBOX_K8S_POOL="$pool" \
    ./gradlew --no-daemon :aimon-sandbox-opensandbox:k8sTest --rerun-tasks "$@") || local rc=$?
  kill "$watch" 2>/dev/null || true
  wait "$watch" 2>/dev/null || true
  placement
  return "${rc:-0}"
}

case "${1:-}" in
  up) up ;;
  test) shift; run_tests "$@" ;;
  status) status ;;
  reconfigure) reconfigure ;;
  down) kind delete cluster --name "$cluster" ;;
  *) echo "usage: $0 up|test|status|reconfigure|down" >&2; exit 2 ;;
esac
