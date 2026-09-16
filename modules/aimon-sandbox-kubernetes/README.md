# aimon-sandbox-kubernetes

Kubernetes 기반 샌드박스 백엔드 구현 모듈입니다. `SandboxBackend` 인터페이스를 Kubernetes Pod로 구현하여 클러스터 환경에서 격리된 명령 실행 환경을 제공합니다.

## 의존성 추가

```kotlin
// build.gradle.kts — `aimon-sandbox` 는 `api` 로 따라오므로 적지 않아도 된다
dependencies {
    implementation("at.aimon.sandbox:aimon-sandbox-kubernetes:<version>")
    implementation("at.aimon.core:aimon-core:<version>")
}
```

## 이 모듈 자신의 의존성

```kotlin
dependencies {
    api(project(":aimon-sandbox"))
    implementation("io.kubernetes:client-java")
    implementation("io.kubernetes:client-java-extended")
}
```

## 핵심 클래스

| 클래스 | 역할 |
|--------|------|
| `KubernetesSandboxConfig` | Kubernetes 전용 설정 (네임스페이스, 리소스, 보안 컨텍스트 등) |
| `KubernetesClientFactory` | Kubernetes ApiClient 인스턴스 생성 팩토리 |
| `KubernetesSandboxBackend` | `SandboxBackend` 구현체 (Pod 생명주기 관리) |

## 사용법

### 기본 설정

```java
SandboxConfig sandboxConfig = SandboxConfig.builder()
    .defaultImage("ubuntu:22.04")
    .build();

KubernetesSandboxConfig k8sConfig = KubernetesSandboxConfig.builder()
    .namespace("sandbox")
    .memoryRequest("256Mi")
    .memoryLimit("512Mi")
    .cpuRequest("500m")
    .cpuLimit("1")
    .build();
```

### 백엔드 생성 및 사용

```java
ApiClient apiClient = KubernetesClientFactory.create(k8sConfig);
SandboxExpiryStore expiryStore = new InMemorySandboxExpiryStore();

try (KubernetesSandboxBackend backend = new KubernetesSandboxBackend(
        apiClient, sandboxConfig, k8sConfig, expiryStore)) {

    // 샌드박스 생성 (TTL 30분)
    Sandbox sandbox = backend.ensure("my-sandbox", 1800);

    // 명령 실행
    ExecResult result = backend.exec(sandbox.getSandboxId(), ExecParams.builder()
        .command("echo 'Hello from sandbox'")
        .cwd("/workspace")
        .asUser(SandboxUser.SANDBOX)
        .build());

    System.out.println(result.getStdout());

    // 샌드박스 삭제
    backend.delete("my-sandbox");
}
```

## 설정 옵션

### KubernetesSandboxConfig

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `kubeconfigPath` | (자동 감지) | kubeconfig 파일 경로 |
| `namespace` | `"default"` | Pod 배포 네임스페이스 |
| `memoryRequest` | `"256Mi"` | 메모리 요청량 |
| `memoryLimit` | `"512Mi"` | 메모리 제한량 |
| `cpuRequest` | `"500m"` | CPU 요청량 |
| `cpuLimit` | `"1"` | CPU 제한량 |
| `dropCapabilities` | `["ALL"]` | 제거할 Linux capability |
| `allowPrivilegeEscalation` | `false` | 권한 상승 허용 여부 |
| `podReadyTimeoutMs` | 60000 | Pod Ready 대기 시간 (ms) |
| `serviceAccountName` | (없음) | Pod에 할당할 ServiceAccount |
| `nodeSelector` | (없음) | Pod 스케줄링 노드 셀렉터 |
| `sandboxUserUid` | 1000 | 샌드박스 사용자 UID |

### Kubeconfig 탐색 순서

1. `kubeconfigPath`가 지정된 경우 해당 파일 사용
2. 미지정 시 표준 탐색 순서: `~/.kube/config` → in-cluster 설정

## Pod 관리

### 명명 규칙

- Pod 이름: `sandbox-{identifier}`
- 컨테이너 이름: `sandbox`
- 레이블: `aimon.at/role=sandbox`, `aimon.at/identifier={identifier}`
- 어노테이션: `aimon.at/expires-at={ISO-8601}` (만료 시간)

### 초기화 과정

1. Pod 생성 (`sleep infinity` 실행, `restartPolicy: Never`)
2. Pod Ready 상태 대기 (폴링 간격 500ms)
3. 샌드박스 사용자 생성 (UID 1000)
4. `/workspace`, `/artifacts`, `/artifacts/logs` 디렉토리 생성
5. 디렉토리 소유권 설정

### 명령 실행

Kubernetes exec API (WebSocket)를 통해 명령을 실행합니다:

- `sh -c` 래퍼로 셸 명령 실행
- 사용자 전환: `su -s /bin/sh {user} -c {command}`
- 환경 변수: `export KEY='VALUE'` 형태로 주입
- stdout/stderr 별도 스레드로 읽기 (교착 상태 방지)

### TTL 관리

- `SandboxExpiryStore`를 통해 만료 시간 추적
- `reapExpired()`로 만료된 Pod 정리
- Pod 어노테이션에 만료 시간 기록 (폴백용, ISO-8601 형식)

## 보안

Pod는 다음과 같은 보안 컨텍스트로 실행됩니다:

- 모든 Linux capability 제거 (`drop: ["ALL"]`)
- 권한 상승 차단 (`allowPrivilegeEscalation: false`)
- 리소스 요청/제한 설정 (CPU, 메모리)
- ServiceAccount 분리 가능

## Docker 백엔드와의 차이점

| 항목 | Docker | Kubernetes |
|------|--------|------------|
| 실행 단위 | 컨테이너 | Pod |
| 만료 시간 저장 | 레이블 | 어노테이션 (ISO-8601) |
| 명령 실행 | Docker exec API | WebSocket exec API |
| 사용자 전환 | `--user` 플래그 | `su` 명령 래핑 |
| 환경 변수 | exec 파라미터로 전달 | `export` 명령으로 주입 |
| 아티팩트 전송 | Docker API 네이티브 | `tar` 명령 파이프 |
| 스케일링 | 단일 호스트 | 멀티 노드 클러스터 |

## 테스트

```bash
# 단위 테스트
./gradlew :aimon-sandbox-kubernetes:test

# 통합 테스트 (Kubernetes 클러스터 필요)
AIMON_KUBERNETES_IT=true ./gradlew :aimon-sandbox-kubernetes:test
```

통합 테스트는 `AIMON_KUBERNETES_IT=true` 환경 변수가 설정된 경우에만 실행됩니다.

### 통합 테스트 요구사항

- 접근 가능한 Kubernetes 클러스터
- Pod 생성/삭제/exec 권한
- 컨테이너에 필요한 capability: `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `SETGID`, `SETUID`
