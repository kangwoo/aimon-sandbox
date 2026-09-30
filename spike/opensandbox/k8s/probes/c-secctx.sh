#!/usr/bin/env bash
# Probe c: pod SecurityContext as created, and in-container view. Usage: c-secctx.sh [label]
source "$(dirname "$0")/lib.sh"
ID=$(create_sb "{\"image\":{\"uri\":\"$IMG\"},\"entrypoint\":[\"sleep\",\"infinity\"],\"timeout\":1800,\"resourceLimits\":{\"cpu\":\"250m\",\"memory\":\"128Mi\"},\"metadata\":{\"aimon.at/probe\":\"c\"}}" 2>/dev/null)
echo "ID=$ID ($(wait_state $ID Running 120))"
echo "== pod.spec.securityContext / automount / serviceAccount / runtimeClassName"
k -n $NS get pod $ID-0 -o jsonpath='podSC={.spec.securityContext}{"\n"}automount={.spec.automountServiceAccountToken}{"\n"}sa={.spec.serviceAccountName}{"\n"}runtimeClassName={.spec.runtimeClassName}{"\n"}'
echo "== containers[].securityContext"
k -n $NS get pod $ID-0 -o jsonpath='{range .spec.containers[*]}{.name}: {.securityContext}{"\n"}{end}{range .spec.initContainers[*]}init {.name}: {.securityContext}{"\n"}{end}'
echo "== in-container: id"; podx $ID id
echo "== /proc/1/status and /proc/self/status (Cap*, NoNewPrivs, Seccomp)"
podx $ID sh -c 'for p in 1 self; do echo "-- pid $p ($(cat /proc/$p/comm))"; grep -E "^(Uid|Cap(Inh|Prm|Eff|Bnd|Amb)|NoNewPrivs|Seccomp):" /proc/$p/status; done'
echo "== decoded CapEff"; podx $ID sh -c 'grep CapEff /proc/self/status' | awk '{print $2}' | python3 -c '
import sys; v=int(sys.stdin.read().strip(),16)
names="chown dac_override dac_read_search fowner fsetid kill setgid setuid setpcap linux_immutable net_bind_service net_broadcast net_admin net_raw ipc_lock ipc_owner sys_module sys_rawio sys_chroot sys_ptrace sys_pacct sys_admin sys_boot sys_nice sys_resource sys_time sys_tty_config mknod lease audit_write audit_control setfcap mac_override mac_admin syslog wake_alarm block_suspend audit_read perfmon bpf checkpoint_restore".split()
print([n for i,n in enumerate(names) if v>>i&1])'
echo "== service account token dir"; podx $ID sh -c 'ls -la /var/run/secrets/kubernetes.io/serviceaccount 2>&1; env | grep -i KUBERNETES_SERVICE'
echo "== who runs execd"; podx $ID sh -c 'for d in /proc/[0-9]*; do printf "%s %s uid=%s\n" "${d#/proc/}" "$(tr "\0" " " < $d/cmdline | cut -c1-60)" "$(awk "/^Uid/{print \$2}" $d/status)"; done'
echo "C_ID=$ID"
