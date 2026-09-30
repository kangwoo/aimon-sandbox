#!/usr/bin/env bash
# Probe f (RWX): createIfNotExists + ReadWriteMany on an NFS StorageClass (nfs-server-provisioner, in-cluster)
source "$(dirname "$0")/lib.sh"
mk() { create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":1800,\"resourceLimits\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"metadata\":{\"aimon.at/probe\":\"$1\"},\"volumes\":[{\"name\":\"shared\",\"pvc\":{\"claimName\":\"spike-nfs\",\"createIfNotExists\":true,\"storageClass\":\"nfs\",\"accessModes\":[\"ReadWriteMany\"],\"storage\":\"1Gi\"},\"mountPath\":\"/shared\",\"readOnly\":$2}]}"; }
A=$(mk rwx-a false 2> >(cut -c1-300 >&2)); echo "A=$A $(wait_state $A Running 90)"
B=$(mk rwx-b false 2>/dev/null); echo "B=$B $(wait_state $B Running 90)"
C=$(mk rwx-ro true 2>/dev/null); echo "C=$C $(wait_state $C Running 90)"
k -n $NS get pvc spike-nfs -o jsonpath='modes={.spec.accessModes} sc={.spec.storageClassName} phase={.status.phase} labels={.metadata.labels} ownerRefs={.metadata.ownerReferences}{"\n"}'
podx $A sh -c 'id -u; ls -ld /shared; echo from-a > /shared/a.txt && echo A-write-ok'
podx $B sh -c 'cat /shared/a.txt; echo from-b > /shared/b.txt && echo B-write-ok'
podx $C sh -c 'cat /shared/a.txt /shared/b.txt; echo x > /shared/c.txt 2>&1 || echo C-write-refused; mount | grep /shared'
for x in $A $B $C; do del_sb $x; done; sleep 5; echo "PVC after all sandboxes deleted: $(k -n $NS get pvc spike-nfs --no-headers 2>&1)"
k -n $NS delete pvc spike-nfs
