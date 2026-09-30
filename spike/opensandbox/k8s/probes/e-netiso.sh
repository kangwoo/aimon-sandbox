#!/usr/bin/env bash
# Probe e: east-west / server / K8s API / internet reachability from a sandbox, before and after an operator NetworkPolicy
source "$(dirname "$0")/lib.sh"; D=$(dirname "$0")
mk() { create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":1800,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"$1\"}}" 2>/dev/null; }
A=$(mk e-a); B=$(mk e-b); wait_state $A Running 60 >/dev/null; wait_state $B Running 60 >/dev/null
BIP=$(k -n $NS get pod $B-0 -o jsonpath='{.status.podIP}'); SIP=$(k -n opensandbox-system get svc opensandbox-server -o jsonpath='{.spec.clusterIP}')
SPOD=$(k -n opensandbox-system get pod -l app.kubernetes.io/name=opensandbox-server -o jsonpath='{.items[0].status.podIP}')
NODE=$(k get node -o jsonpath='{.items[0].status.addresses[0].address}')
echo "A=$A B=$B B.ip=$BIP server.svc=$SIP server.pod=$SPOD node=$NODE"
T="peer-execd=$BIP:44772:http:/ping server-svc=opensandbox-server.opensandbox-system.svc:80:http:/health server-pod=$SPOD:80:http:/health k8s-api-svc=kubernetes.default.svc:443:tls k8s-api-node=$NODE:6443:tls kubelet=$NODE:10250 metadata=169.254.169.254:80 internet=example.com:80:http:/"
run() { k -n $NS exec -i $A-0 -c sandbox -- python3 - $T < $D/netcheck.py; }
echo "== existing NetworkPolicies (cluster-wide) before:"; k get networkpolicy -A
echo "== CNI: $(k -n kube-system get ds kindnet -o jsonpath='{.spec.template.spec.containers[0].image}')"
echo "== reachability from A (no policy)"; run
cat > $D/../deploy/netpol-sandbox-isolation.yaml <<Y
# Example operator policy for the sandbox namespace:
#  ingress: only the OpenSandbox server (proxy/execd) may connect to sandbox pods
#  egress : DNS to kube-dns only; internet allowed; pod/service/node CIDRs (east-west, server, K8s API, kubelet) denied
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: sandbox-isolation, namespace: $NS}
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: opensandbox-system}}
          podSelector: {matchLabels: {app.kubernetes.io/name: opensandbox-server}}
  egress:
    - to:
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: kube-system}}
          podSelector: {matchLabels: {k8s-app: kube-dns}}
      ports: [{protocol: UDP, port: 53}, {protocol: TCP, port: 53}]
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10]
Y
k apply -f $D/../deploy/netpol-sandbox-isolation.yaml; sleep 5
echo "== reachability from A (with sandbox-isolation policy)"; run
echo "== server -> sandbox still works (proxy to A's execd /ping via API)"; curl -sS -m 10 -H "OPEN-SANDBOX-API-KEY: $KEY" "$API/sandboxes/$A/proxy/44772/ping" -w ' HTTP %{http_code}\n'
echo "== B -> A execd (east-west ingress) blocked?"; k -n $NS exec -i $B-0 -c sandbox -- python3 - "peer-execd=$(k -n $NS get pod $A-0 -o jsonpath='{.status.podIP}'):44772:http:/ping" < $D/netcheck.py
echo "E_IDS=$A $B"
