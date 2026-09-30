#!/usr/bin/env bash
# Probe g: networkPolicy egress (server [egress] mode = dns+nft); optional 1st arg = label
source "$(dirname "$0")/lib.sh"; D=$(dirname "$0")
EXIP=$(dig +short example.com A | head -1); echo "example.com A (host resolver) = $EXIP"
mk() { create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":1800,\"resourceLimits\":{\"cpu\":\"250m\",\"memory\":\"128Mi\"},\"networkPolicy\":$2,\"metadata\":{\"aimon.at/probe\":\"$1\"}}"; }
echo "== g1: defaultAction deny + empty allow list"; G1=$(mk g1 '{"defaultAction":"deny","egress":[]}' 2> >(cut -c1-300 >&2)); echo "G1=$G1 $(wait_state $G1 Running 90)"
echo "== g2: deny + allow example.com"; G2=$(mk g2 '{"defaultAction":"deny","egress":[{"action":"allow","target":"example.com"}]}' 2> >(cut -c1-300 >&2)); echo "G2=$G2 $(wait_state $G2 Running 90)"
echo "== pod shape (G2)"; k -n $NS get pod $G2-0 -o jsonpath='{range .spec.initContainers[*]}init {.name} sc={.securityContext}{"\n"}{end}{range .spec.containers[*]}ctr {.name} image={.image} sc={.securityContext}{"\n"}{end}podSC={.spec.securityContext}{"\n"}'
echo "== NetworkPolicy objects created by OpenSandbox?"; k get networkpolicy -A
for x in G1 G2; do id=${!x}; echo "== $x egress checks"; k -n $NS exec -i $id-0 -c sandbox -- python3 - $EXIP < $D/egresscheck.py; done
echo "== G2: can the sandbox user tamper with nft/iptables? (id, caps)"; k -n $NS exec $G2-0 -c sandbox -- sh -c 'id; grep CapEff /proc/self/status; (nft list ruleset 2>&1 || iptables -S 2>&1) | head -3'
echo "== runtime policy read/update: GET/PATCH /sandboxes/{id}/networkpolicy? "; api GET /sandboxes/$G2/networkpolicy | cut -c1-300
echo "G_IDS=$G1 $G2"
