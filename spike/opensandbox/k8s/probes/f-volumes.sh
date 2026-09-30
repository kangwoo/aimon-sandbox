#!/usr/bin/env bash
# Probe f: volumes[].pvc on kind (local-path provisioner)
source "$(dirname "$0")/lib.sh"
ts() { date -u +%H:%M:%S; }
mk() { # probe claim modes readOnly
  create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":1800,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"$1\"},\"volumes\":[{\"name\":\"shared\",\"pvc\":{\"claimName\":\"$2\",\"createIfNotExists\":true,\"accessModes\":$3,\"storage\":\"1Gi\"},\"mountPath\":\"/shared\",\"readOnly\":$4}]}"; }
echo "== f1: RWX PVC via createIfNotExists (local-path)"; T0=$(date +%s)
F1=$(mk f1 spike-rwx '["ReadWriteMany"]' false 2> >(cut -c1-400 >&2)); echo "create returned after $(( $(date +%s)-T0 ))s id='$F1'"
k -n $NS get pvc spike-rwx -o wide; k -n $NS describe pvc spike-rwx | sed -n '/Events/,$p' | tail -4 | cut -c1-250
echo "== f2: RWO PVC, sandbox S1 rw + S2 readOnly (same node)"
S1=$(mk f2-rw spike-rwo '["ReadWriteOnce"]' false 2>/dev/null); echo "S1=$S1 $(wait_state $S1 Running 90)"
S2=$(mk f2-ro spike-rwo '["ReadWriteOnce"]' true 2>/dev/null); echo "S2=$S2 $(wait_state $S2 Running 90)"
echo "-- PVC metadata (labels / ownerReferences / finalizers / annotations)"
k -n $NS get pvc spike-rwo -o jsonpath='labels={.metadata.labels}{"\n"}ownerRefs={.metadata.ownerReferences}{"\n"}finalizers={.metadata.finalizers}{"\n"}modes={.spec.accessModes} sc={.spec.storageClassName} size={.spec.resources.requests.storage} phase={.status.phase}{"\n"}annotations={.metadata.annotations}{"\n"}'
echo "-- pod volume wiring S1/S2"; for x in $S1 $S2; do k -n $NS get pod $x-0 -o jsonpath='{.spec.volumes[?(@.persistentVolumeClaim)]} mount={.spec.containers[0].volumeMounts[?(@.mountPath=="/shared")]}{"\n"}'; done
echo "-- write from S1 (rw), read + write from S2 (ro)"
podx $S1 sh -c 'id -u; ls -ld /shared; echo from-s1 > /shared/f.txt && echo S1-write-ok'
podx $S2 sh -c 'cat /shared/f.txt; echo x > /shared/g.txt 2>&1 || echo S2-write-refused'
echo "== f3: delete S1, S2 -> PVC survives?"; del_sb $S1; del_sb $S2; sleep 8
k -n $NS get pvc spike-rwo --no-headers 2>&1
echo "== f4: remount existing PVC in S3, then delete PVC via K8s API while mounted"
S3=$(mk f4 spike-rwo '["ReadWriteOnce"]' false 2>/dev/null); echo "S3=$S3 $(wait_state $S3 Running 90)"; podx $S3 cat /shared/f.txt
k -n $NS delete pvc spike-rwo --wait=false; sleep 3
k -n $NS get pvc spike-rwo -o jsonpath='phase={.status.phase} deletionTimestamp={.metadata.deletionTimestamp} finalizers={.metadata.finalizers}{"\n"}'
podx $S3 sh -c 'cat /shared/f.txt && echo still-readable'
echo "-- new sandbox referencing the Terminating PVC:"; S4=$(mk f4b spike-rwo '["ReadWriteOnce"]' false 2> >(cut -c1-300 >&2)); echo "S4='$S4'"
echo "-- delete S3 -> PVC released?"; del_sb $S3; for i in $(seq 1 20); do k -n $NS get pvc spike-rwo >/dev/null 2>&1 || { echo "$(ts) PVC gone after ~$((i*2))s"; break; }; sleep 2; done
k get pv | grep spike-rwo || echo "PV removed too (reclaimPolicy Delete)"
echo "== f5: deleteOnSandboxTermination=true"
S5=$(create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":600,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"f5\"},\"volumes\":[{\"name\":\"v\",\"pvc\":{\"claimName\":\"spike-ephemeral\",\"deleteOnSandboxTermination\":true},\"mountPath\":\"/v\"}]}" 2>/dev/null); echo "S5=$S5 $(wait_state $S5 Running 90)"
k -n $NS get pvc spike-ephemeral -o jsonpath='labels={.metadata.labels} ownerRefs={.metadata.ownerReferences} annotations={.metadata.annotations} modes={.spec.accessModes}{"\n"}'
del_sb $S5; sleep 10; k -n $NS get pvc spike-ephemeral --no-headers 2>&1
echo "== leftovers"; k -n $NS get pvc; [ -n "$F1" ] && del_sb $F1; [ -n "$S4" ] && del_sb $S4
