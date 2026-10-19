# OpenSandbox 를 Kubernetes 에 설치하기

aimon-sandbox 의 운영 환경은 Kubernetes 위의 OpenSandbox 다. 이 문서는 OpenSandbox 를 클러스터에 설치하고,
aimon-sandbox 가 요구하는 격리(강화한 SecurityContext · 네트워크 격리 · egress 집행)를 갖춘 뒤, AIMON 에서 연결하는
데까지를 다룬다.

- 여기 적은 값은 이 저장소가 kind 위에서 실측한 구성이다. 근거는 [`spike/opensandbox/reports/C-k8s.md`](../../spike/opensandbox/reports/C-k8s.md),
  배포 값은 [`spike/opensandbox/k8s/deploy/`](../../spike/opensandbox/k8s/deploy/), 자동화는 [`scripts/k8s-tier.sh`](../../scripts/k8s-tier.sh) 에 있다
- 실측 버전: OpenSandbox `3738975`(2026-09-29 main), 차트 `1.1.1-rc.1`, 이미지 `release-1.1.1-rc.1`, Kubernetes
  v1.37(kind, CNI kindnet). 다른 버전이면 [§9 확인](#9-설치-확인)의 k8s 테스트 계층을 돌려 확인한다
- AIMON 쪽 코드는 [aimon-core 와 연동하기](aimon-core-integration.md), 설정 전반은 [사용자 가이드](user-guide.md) 에 있다

---

## 1. 구성

```
 ┌─ AIMON 네임스페이스 ──────────┐        ┌─ opensandbox-system ───────────────────┐
 │  AIMON 애플리케이션 파드      │  REST  │  opensandbox-server   (수명 API :80)    │
 │  (aimon-sandbox-opensandbox)  ├───────▶│  opensandbox-controller-manager         │
 └──────────────┬────────────────┘        └────────────┬────────────────────────────┘
                │ execd :44772 / egress :18080         │ BatchSandbox CR 생성
                │ (직접, 또는 서버 프록시 경유)        ▼
                │                         ┌─ opensandbox (샌드박스 네임스페이스) ──┐
                └────────────────────────▶│  샌드박스 파드                          │
                                          │   init  execd-installer (privileged)    │
                                          │   ctr   sandbox  ← 프로파일의 이미지    │
                                          │   ctr   egress   (dns+nft 사이드카)     │
                                          │  NetworkPolicy: sandbox-isolation       │
                                          └─────────────────────────────────────────┘
```

- **서버**가 API 키로 인증한 수명 API(`/v1/sandboxes`)를 연다. 샌드박스를 만들면 서버가 BatchSandbox CR 을 만들고
  컨트롤러가 파드를 띄운다. 생성 요청은 파드가 Running 이 되고 IP 를 받을 때까지(최대 60초) 막힌다
- **execd** 는 init 컨테이너가 샌드박스 컨테이너에 넣는다. 그래서 샌드박스 이미지에 execd 가 필요 없다
- **egress 사이드카**가 `dns+nft` 모드로 나가는 연결을 허용 목록대로 막고, 자격 증명 vault 로 헤더를 끼워 넣는다
- **OpenSandbox 는 NetworkPolicy 를 만들지 않는다.** 정책이 없으면 샌드박스가 옆 샌드박스 · 서버 · K8s API · kubelet
  에 닿는다. 운영자가 직접 건다([§6](#6-네트워크-격리))

### 1.1 미리 알아 둘 보안 사실

- **서버의 프록시 경로와 execd 는 인증하지 않는다.** 단일 테넌트 모드에서 `/v1/sandboxes/{id}/proxy/{port}` 는 API
  키를 검사하지 않고, execd 도 토큰을 검사하지 않는다. 서버나 샌드박스의 execd 포트에 닿는 누구든 샌드박스 id 만 알면
  명령을 실행한다. **서버 Service 와 샌드박스 네임스페이스는 AIMON 만 닿는 네트워크에 둔다.** Ingress 로 바깥에 열지
  않는다
- **기본 템플릿은 강화되어 있지 않다.** uid 0, 기본 capability, seccomp unconfined 로 돈다. 강화는 서버 전체에
  적용되는 BatchSandbox 템플릿으로만 할 수 있다(요청마다 줄 수 없다). [§5](#5-샌드박스-템플릿-강화)
- ServiceAccount 토큰은 마운트되지 않는다(서버가 늘 `automountServiceAccountToken: false` 를 건다). 그래도
  `KUBERNETES_SERVICE_HOST` 환경 변수는 있으므로 네트워크로 막아야 한다

---

## 2. 준비물

| 항목 | 요구 |
|---|---|
| Kubernetes | NetworkPolicy 를 **집행하는** CNI(Calico, Cilium, kindnet 등). 집행하지 않으면 네트워크 격리를 선언할 수 없다 |
| 도구 | `kubectl`, `helm`(v3 이상), `git`, `docker` |
| 레지스트리 | 샌드박스 이미지를 클러스터가 받을 수 있는 곳 |
| 노드 | Linux. egress 사이드카가 nftables 를 쓴다. 샌드박스 하나당 프로파일의 cpu · memory 에 사이드카 몫이 더 든다 |
| 선택 | gVisor · Kata 같은 RuntimeClass([§7](#7-runtimeclass-선택)) |

pause/resume(스냅숏 레지스트리)과 RWX StorageClass(공유 볼륨)는 aimon-sandbox 가 아직 쓰지 않으므로 지금은
필요 없다.

---

## 3. 차트 받기

OpenSandbox 차트는 소스 저장소의 `manifests/charts/opensandbox` 에 있는 umbrella 차트(서버 + 컨트롤러)다.

```bash
git clone https://github.com/opensandbox-group/OpenSandbox
cd OpenSandbox
git checkout 3738975            # 실측한 커밋. 다른 커밋이면 §9 로 다시 확인한다
cd manifests/charts
helm dependency build opensandbox
```

---

## 4. 네임스페이스와 values

### 4.1 네임스페이스

차트가 `opensandbox-system` Namespace 객체를 **직접 렌더한다.** 그래서 `--create-namespace` 는 충돌하고, 그것을 빼면
"namespace not found" 로 실패한다. Helm 소유 표시를 단 네임스페이스를 미리 만든다. 샌드박스가 뜰 네임스페이스
`opensandbox` 는 차트가 만들지 않으므로 따로 만든다.

```bash
kubectl create namespace opensandbox-system --dry-run=client -o yaml \
  | kubectl label --local -f - app.kubernetes.io/managed-by=Helm -o yaml \
  | kubectl annotate --local -f - meta.helm.sh/release-name=opensandbox \
      meta.helm.sh/release-namespace=opensandbox-system -o yaml \
  | kubectl apply -f -

kubectl create namespace opensandbox
```

### 4.2 values.yaml

차트의 기본 이미지는 Aliyun 레지스트리(`sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/*`)를 가리킨다.
닿지 않거나 느리면 Docker Hub 의 같은 이미지로 바꾼다. **`execd_image` 와 `[egress].image` 도 함께 바꿔야 한다** —
서버 설정(`configToml`) 안에 따로 적혀 있다.

```yaml
# values.yaml
opensandbox-controller:
  controller:
    image:
      repository: docker.io/opensandbox/controller
      tag: release-1.1.1-rc.1
    resources:
      requests: {cpu: 10m, memory: 64Mi}
    snapshot:
      # pause 용. aimon-sandbox 는 아직 쓰지 않지만 기본값이 Aliyun 레지스트리라 함께 바꿔 둔다
      imageCommitterImage: docker.io/opensandbox/image-committer:release-1.1.1-rc.1
opensandbox-server:
  server:
    image:
      repository: docker.io/opensandbox/server
      tag: release-1.1.1-rc.1
    resources:
      requests: {cpu: 100m, memory: 256Mi}
      limits: {cpu: "2", memory: 2Gi}
    volumes:
      - name: bs-template
        configMap: {name: aimon-sandbox-template}
    volumeMounts:
      - name: bs-template
        mountPath: /etc/aimon
  configToml: |
    [server]
    host = "0.0.0.0"
    port = 80
    api_key = "<API 키>"                      # 비우면 서버가 기동을 거부하고 crash-loop 한다
    max_sandbox_timeout_seconds = 86400        # = 프로바이더의 maxExpiry (24h)

    [log]
    level = "INFO"

    [runtime]
    type = "kubernetes"
    execd_image = "docker.io/opensandbox/execd:release-1.1.1-rc.1"

    [kubernetes]
    kubeconfig_path = ""                       # 클러스터 안의 서비스 계정을 쓴다
    namespace = "opensandbox"                  # 샌드박스가 뜰 네임스페이스
    workload_provider = "batchsandbox"
    image_pull_policy = "IfNotPresent"
    batchsandbox_template_file = "/etc/aimon/template.yaml"   # §5 의 강화 템플릿

    [egress]
    image = "docker.io/opensandbox/egress:release-1.1.1-rc.1"
    mode = "dns+nft"                           # aimon-sandbox 의 EGRESS_POLICY 는 dns+nft 에서만 광고된다
```

알아 둘 것:

- **`api_key`** 는 `configToml` 에 평문으로 들어간다. values 파일을 비밀처럼 다룬다(SOPS · sealed-secrets · 외부 비밀
  관리자 등). AIMON 은 같은 키를 `OPEN_SANDBOX_API_KEY` 같은 환경 변수로 받는다
- **`max_sandbox_timeout_seconds`** 는 API 로 읽을 수 없다. 프로바이더 설정 `maxExpiry` 에 **같은 값**을 손으로
  적는다. 프로바이더 값이 더 크면 `create` 가 400 이다. 모든 프로파일의 `terminateAfter` 는 이 값 이하여야 한다
- **`egress.mode`**: `dns` 는 이름만 거르고 IP 직접 연결을 막지 못한다. aimon-sandbox 는 `dns+nft` 만 egress 집행으로
  인정한다
- 컨트롤러의 `snapshot.*` 은 pause/resume 용이다. aimon-sandbox 는 아직 pause 를 쓰지 않으므로 스냅숏 레지스트리
  (`snapshot.registry`)는 두지 않아도 된다

---

## 5. 샌드박스 템플릿 강화

aimon-sandbox 는 샌드박스가 **uid 1000, 권한 상승 없음, capability 없음**으로 돌기를 요구한다
(`HARDENED_SECURITY_CONTEXT`). 서버의 BatchSandbox 템플릿으로 건다.

```yaml
# template.yaml — 샌드박스 컨테이너 단위로 강화한다
metadata:
spec:
  replicas: 1
  template:
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext:
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: sandbox
          securityContext:
            runAsNonRoot: true
            runAsUser: 1000
            runAsGroup: 1000
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
```

```bash
kubectl -n opensandbox-system create configmap aimon-sandbox-template --from-file=template.yaml
```

- **파드 단위로 `runAsUser` · `runAsNonRoot` 를 걸지 않는다.** 권한이 필요한 `execd-installer` init 컨테이너와
  egress 사이드카에도 걸려, egress 정책이 있는 생성이 500 `POD_FAILED/InitContainerFailed` 로 실패한다. 파드 단위에는
  `seccompProfile` 만 둔다
- egress 정책이 있으면 서버가 샌드박스 컨테이너의 `capabilities.drop` 을 `[NET_ADMIN]` 으로 **덮어쓴다**(템플릿의
  `[ALL]` 이 사라진다). 비루트 + `allowPrivilegeEscalation: false` 라 실효 capability 는 0 으로 실측됐다
- 서버는 템플릿을 **기동할 때만** 읽는다. 바꾸면 서버를 재시작한다:
  `kubectl -n opensandbox-system rollout restart deploy/opensandbox-server`
- 이 템플릿을 건 뒤에만 프로바이더에 `hardenedSecurityContext(DECLARED)` 를 준다. seed 는 root 로 도는지(step `root`)와
  ServiceAccount 토큰이 있는지(step `service-account-token`)만 확인한다. `drop: [ALL]` · `allowPrivilegeEscalation:
  false` · seccomp 가 빠진 템플릿은 seed 를 통과하므로, 템플릿 내용은 운영자가 확인한다
- 샌드박스 이미지의 `/workspace` 는 uid 1000 이 쓸 수 있어야 한다([사용자 가이드 §7](user-guide.md#7-샌드박스-이미지-계약))

---

## 6. 네트워크 격리

`NETWORK_ISOLATION` 을 선언하려면 샌드박스 네임스페이스에 NetworkPolicy 를 건다. 실측한 정책:

```yaml
# netpol-sandbox-isolation.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: sandbox-isolation, namespace: opensandbox}
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
  ingress:
    # 서버(프록시 · execd 호출)만 샌드박스에 들어올 수 있다
    - from:
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: opensandbox-system}}
          podSelector: {matchLabels: {app.kubernetes.io/name: opensandbox-server}}
  egress:
    # DNS 는 kube-dns 로만
    - to:
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: kube-system}}
          podSelector: {matchLabels: {k8s-app: kube-dns}}
      ports: [{protocol: UDP, port: 53}, {protocol: TCP, port: 53}]
    # 인터넷은 허용, 클러스터 내부(east-west · 서버 · K8s API · kubelet · 메타데이터)는 차단
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10]
```

```bash
kubectl apply -f netpol-sandbox-isolation.yaml
```

- **`except` 를 클러스터에 맞춘다.** 파드 · 서비스 · 노드 CIDR 이 위 사설 대역 밖에 있으면 그 대역도 넣는다. 클라우드의
  메타데이터 주소(169.254.169.254)는 위 목록이 막는다
- 인터넷 허용은 egress 사이드카의 허용 목록이 다시 좁힌다. 이 정책은 클러스터 안쪽을 막는 몫이다
- 이 정책을 건 뒤에만 프로바이더에 `networkIsolation(DECLARED)` 를 준다. seed 단계가 `controlPlaneProbes` 의 주소에
  연결을 시도해 **닿으면** 슬롯을 FAILED 로 만든다. 들어오는 쪽(옆 샌드박스 → 나)은 seed 가 확인하지 못하므로 ingress
  규칙은 운영자가 책임진다

### 6.1 AIMON 이 샌드박스에 닿는 길

프로바이더는 수명 API 말고도 각 샌드박스의 execd(44772)와 egress 사이드카(18080)를 부른다. 길은 둘이다.

| 방식 | 프로바이더 설정 | NetworkPolicy |
|---|---|---|
| **서버 프록시 경유** | `useServerProxy(true)` | 위 정책 그대로. 서버만 샌드박스에 들어간다 |
| **직접 연결** | `useServerProxy(false)`(기본) | AIMON 파드에서 들어오는 44772 · 18080 을 ingress 에 더해야 한다 |

직접 연결은 서버를 거치지 않아 명령 스트림의 홉이 하나 줄지만, AIMON 파드가 클러스터 안에 있어야 하고(파드 IP 로
붙는다) 정책을 넓혀야 한다. 직접 연결이면 ingress 에 다음을 더한다.

```yaml
    - from:
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: <AIMON 네임스페이스>}}
          podSelector: {matchLabels: {app: <AIMON 앱 라벨>}}
      ports: [{protocol: TCP, port: 44772}, {protocol: TCP, port: 18080}]
```

AIMON 이 클러스터 **밖**에 있으면 서버 프록시 경유만 가능하다.

---

## 7. RuntimeClass (선택)

gVisor · Kata 같은 샌드박스 런타임을 쓰려면 RuntimeClass 를 만들고 서버 설정에 적는다. **요청마다 class 를 줄 수
없다** — 서버 전체에 하나다. 요청에 `runtimeClassName` 을 넣어도 조용히 무시된다.

```toml
# configToml 에 추가
[secure_runtime]
type = "kata"                       # gvisor | kata
k8s_runtime_class = "kata"          # 클러스터에 있는 RuntimeClass 이름
```

- 이름이 가리키는 RuntimeClass 가 없으면 서버가 기동 때 crash-loop 한다("Configured Kubernetes RuntimeClass … does
  not exist")
- **gVisor 와 `dns+nft` egress 는 함께 쓸 수 없다.** gVisor 가 iptables nat 테이블을 지원하지 않아 서버가 egress 정책을
  400 으로 거부한다. egress 를 쓰려면 Kata 계열을 쓴다
- 프로바이더에 `runtimeClass(RuntimeClass.of("kata", RuntimeKind.KATA))` 를 주고, 프로파일의 `runtimeClass` 를 같은
  이름으로 둔다. 다르면 기동이 거부된다

---

## 8. 설치

```bash
cd OpenSandbox/manifests/charts
helm install opensandbox opensandbox -n opensandbox-system -f values.yaml

kubectl -n opensandbox-system rollout status deploy/opensandbox-server --timeout=300s
kubectl -n opensandbox-system rollout status deploy/opensandbox-controller-manager --timeout=300s
```

샌드박스 이미지를 클러스터가 받을 수 있는 레지스트리에 올린다. 이미지 계약과 Dockerfile 예는
[사용자 가이드 §7](user-guide.md#7-샌드박스-이미지-계약) 에 있다.

```bash
docker build -t registry.example.com/aimon/sandbox-runtime:1 .
docker push registry.example.com/aimon/sandbox-runtime:1
```

> 비공개 레지스트리의 pull secret 을 거는 방법은 실측하지 않았다. 템플릿의 pod spec 이 서버가 만드는 파드에
> 합쳐지므로 `template.yaml` 의 `spec.template.spec.imagePullSecrets` 가 후보다. 쓰기 전에 §9 로 확인한다.

---

## 9. 설치 확인

### 9.1 API

```bash
kubectl -n opensandbox-system port-forward svc/opensandbox-server 8091:80 &
curl -s -H "OPEN-SANDBOX-API-KEY: <API 키>" http://localhost:8091/v1/sandboxes
```

### 9.2 격리 — k8s 테스트 계층

이 저장소의 k8s 테스트 계층은 계약 스위트, `WorkspaceSandbox` 종단 테스트, 강화 · 격리 · egress · 자격 증명 검사를
실제 클러스터에 대고 돈다. 설치한 클러스터에 대고 그대로 돌릴 수 있다.

```bash
# 계약 이미지(테스트 계층의 샌드박스 이미지)를 클러스터가 받을 수 있게 올린다
docker build -t registry.example.com/aimon/sandbox-contract:k8s \
  modules/aimon-sandbox-opensandbox/src/test/docker
docker push registry.example.com/aimon/sandbox-contract:k8s

kubectl -n opensandbox-system port-forward svc/opensandbox-server 8091:80 &

OPENSANDBOX_K8S_ENDPOINT=http://localhost:8091 \
OPENSANDBOX_K8S_API_KEY=<API 키> \
OPENSANDBOX_K8S_IMAGE=registry.example.com/aimon/sandbox-contract:k8s \
OPENSANDBOX_K8S_CONTROL_PLANE=opensandbox-server.opensandbox-system.svc:80 \
OPENSANDBOX_K8S_INTERNET=1 \
./gradlew :aimon-sandbox-opensandbox:k8sTest --rerun-tasks
```

- `OPENSANDBOX_K8S_RUNTIME_CLASS` 는 [§7](#7-runtimeclass-선택) 을 했을 때만 준다. 이 검사는 `kubectl` 로 현재
  컨텍스트의 파드를 읽으므로, `kubectl` 이 그 클러스터를 가리키고 있어야 한다
- `OPENSANDBOX_K8S_INTERNET=1` 은 자격 증명 주입 검사(httpbin.org, github.com)를 켠다. 클러스터에서 인터넷이 안 되면
  뺀다
- 환경 변수가 없으면 모든 테스트를 건너뛴다. 일부러 끈 검사(위 두 변수) 말고는 **건너뛴 것 없이 모두 통과해야**
  설치가 맞다
- 계약 이미지에는 `USER` 줄이 없고 `/workspace` 가 0777 이다. 강화 템플릿이 uid 1000 으로 돌려도 쓸 수 있게 한 것이다.
  운영 이미지는 [사용자 가이드 §7](user-guide.md#7-샌드박스-이미지-계약) 의 예처럼 `/workspace` 를 uid 1000 소유로 둔다
- 테스트는 실행마다 다른 `deployment` 라벨을 쓰고 끝나면 자기 샌드박스를 지운다

### 9.3 kind 로 먼저 해 보기

위 과정을 kind 위에서 한 번에 하는 스크립트가 있다. 처음이라면 여기서 동작을 보고 운영 클러스터로 옮긴다.

```bash
scripts/k8s-tier.sh up     # kind 클러스터 + 차트 + 강화 템플릿 + NetworkPolicy + RuntimeClass + 계약 이미지
scripts/k8s-tier.sh test   # 서버를 포트포워드하고 k8sTest 를 모든 검사를 켜고 돈다(인터넷 필요)
scripts/k8s-tier.sh down   # 클러스터를 지운다
```

kind, kubectl, helm, git, docker 가 필요하고 Docker 메모리가 3 GB 쯤 든다. 이 스크립트의 API 키(`spike-key`)와
RuntimeClass(runc 로 만든 `spike-runc`)는 테스트용이다.

---

## 10. AIMON 에서 연결하기

서버 설정과 프로바이더 설정은 짝을 이룬다.

| OpenSandbox (`configToml` · 클러스터) | `OpenSandboxProviderConfig` |
|---|---|
| Service `opensandbox-server.opensandbox-system.svc:80` | `endpoint(URI.create("http://opensandbox-server.opensandbox-system.svc"))` |
| `[server].api_key` | `apiKey(() -> System.getenv("OPEN_SANDBOX_API_KEY"))` |
| `[runtime].type = "kubernetes"` | `runtime(Runtime.KUBERNETES)` |
| `max_sandbox_timeout_seconds = 86400` | `maxExpiry(Duration.ofHours(24))` |
| `[egress].mode = "dns+nft"` | `egressEnforcement(EgressEnforcement.DNS_NFT)` |
| §5 강화 템플릿 | `hardenedSecurityContext(Declaration.DECLARED)` |
| §6 NetworkPolicy | `networkIsolation(Declaration.DECLARED)` + `controlPlaneProbes(...)` |
| §6.1 의 선택 | `useServerProxy(true / false)` |
| §7 `[secure_runtime]` | `runtimeClass(RuntimeClass.of("kata", RuntimeKind.KATA))` |

```java
OpenSandboxProvider provider = new OpenSandboxProvider(OpenSandboxProviderConfig.builder()
        .endpoint(URI.create("http://opensandbox-server.opensandbox-system.svc"))
        .apiKey(() -> System.getenv("OPEN_SANDBOX_API_KEY"))
        .runtime(OpenSandboxProviderConfig.Runtime.KUBERNETES)
        .maxExpiry(Duration.ofHours(24))
        .egressEnforcement(OpenSandboxProviderConfig.EgressEnforcement.DNS_NFT)
        .networkIsolation(OpenSandboxProviderConfig.Declaration.DECLARED)
        .hardenedSecurityContext(OpenSandboxProviderConfig.Declaration.DECLARED)
        // 샌드박스가 "닿지 못해야 하는" 주소 — 샌드박스 쪽에서 본 이름을 적는다
        .controlPlaneProbes(List.of(
                HostPort.parse("opensandbox-server.opensandbox-system.svc:80"),
                HostPort.parse("kubernetes.default.svc:443")))
        .build());
```

- **`controlPlaneProbes` 에는 샌드박스가 보는 주소를 적는다.** 기본값은 `endpoint` 와 `kubernetes.default.svc:443`
  인데, AIMON 이 클러스터 밖에서 포트포워드로 붙으면 `endpoint` 가 `localhost:8091` 같은 값이 되어 샌드박스 안에서
  풀리지 않는 이름을 탐침하게 된다. 그러면 탐침이 늘 통과한다(빌더가 WARN 을 남긴다)
- AIMON 을 클러스터 밖에서 돌리면 `endpoint` 를 그 경로(포트포워드 · 내부 로드밸런서)로 두고 `useServerProxy(true)`
- `WorkspaceSandbox` 를 조립하는 나머지는 [aimon-core 와 연동하기](aimon-core-integration.md) 에 있다

---

## 11. 운영 메모

- **만료는 하드 삭제다.** `expiresAt` 이 지나면 CR 과 파드가 초 단위로 정확히 지워진다. 프로바이더는 늘 `timeout` 을
  보내고 활동이 있는 동안 연장한다. `timeout` 없는 샌드박스는 영원히 살므로 다른 클라이언트로 서버를 쓰지 않는다
- **샌드박스는 CR 의 라벨로 찾는다.** aimon-sandbox 는 `aimon.at/deployment` · `aimon.at/managed` · `aimon.at/sandbox-key`
  라벨을 달고 API 로 조회한다. 같은 서버를 여러 AIMON 배포가 쓰면 `deployment` 를 서로 다르게 준다 — janitor 는 자기
  `deployment` 의 샌드박스만 지운다
- 손으로 볼 때: `kubectl -n opensandbox get batchsandbox -l aimon.at/deployment=<deployment>`
- **서버 업그레이드 · 재시작** 중에는 생성과 명령이 실패한다. 프로바이더는 멱등 호출(상태 · 목록 · 삭제 · 연장)만
  재시도하고, 프로비저닝 실패는 `failure-backoff` 로 다시 시도한다
- **AIMON 노드는 아직 하나만 둔다.** 저장소가 InMemory 라 여러 노드가 같은 `deployment` 를 쓰면 서로의 샌드박스를 고아로
  지운다([사용자 가이드 §8.3](user-guide.md#83-여러-노드))
- 실측에서 드러난 OpenSandbox 의 동작과 업스트림 이슈는 [`docs/design/opensandbox-spike.md`](../design/opensandbox-spike.md),
  [`spike/opensandbox/upstream-issues.md`](../../spike/opensandbox/upstream-issues.md) 에 있다

---

## 12. 문제 해결

| 증상 | 원인과 조치 |
|---|---|
| `helm install` 이 namespace 충돌 / not found | [§4.1](#41-네임스페이스) 대로 Helm 소유 표시를 단 네임스페이스를 미리 만든다. `--create-namespace` 를 쓰지 않는다 |
| 서버 crash-loop: "server.api_key is empty" | `api_key` 를 채운다 |
| 서버 crash-loop: "Configured Kubernetes RuntimeClass … does not exist" | `[secure_runtime].k8s_runtime_class` 가 가리키는 RuntimeClass 를 만든다 |
| 이미지 pull 이 느리거나 실패 | 차트 기본이 Aliyun 레지스트리다. [§4.2](#42-valuesyaml) 대로 controller · server · `execd_image` · `[egress].image` 를 모두 바꾼다 |
| `create` 가 504 `KUBERNETES::POD_READY_TIMEOUT` | 파드가 60초 안에 Running 이 되지 못했다(이미지 pull, 없는 RuntimeClass, 자원 부족). 서버는 CR 을 지우고 롤백한다. `kubectl -n opensandbox get events` 로 원인을 본다 |
| `create` 가 500 `InitContainerFailed` | 템플릿에 파드 단위 `runAsUser` 를 걸었다. 컨테이너 단위로 옮긴다([§5](#5-샌드박스-템플릿-강화)) |
| `create` 가 422 | 요청 값이 잘못됐다. 프로파일 `resources` 의 cpu · memory 가 Kubernetes 수량 형식(`500m`, `2Gi`)인지 본다 |
| `create` 가 400 (timeout) | `maxExpiry` 가 `max_sandbox_timeout_seconds` 보다 크다 |
| `create` 가 400 (networkPolicy, gVisor) | gVisor 와 `dns+nft` 는 함께 쓸 수 없다 |
| 첫 명령이 FAILED, step `root` | 템플릿이 적용되지 않았다. ConfigMap 을 고친 뒤 서버를 재시작했는지 본다 |
| 첫 명령이 FAILED, step `network-isolation` | NetworkPolicy 가 없거나 CNI 가 집행하지 않거나 `except` 가 클러스터 CIDR 을 덮지 못한다 |
| 첫 명령이 FAILED, step `egress` | 서버의 egress 모드가 `dns+nft` 가 아니다 |
| execd 호출이 타임아웃 | `useServerProxy(false)` 인데 NetworkPolicy 가 AIMON → 샌드박스 44772 · 18080 을 막는다. [§6.1](#61-aimon-이-샌드박스에-닿는-길) |
