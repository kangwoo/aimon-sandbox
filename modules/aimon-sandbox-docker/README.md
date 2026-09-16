# aimon-sandbox-docker

Docker 기반 샌드박스 백엔드 구현 모듈입니다. `SandboxBackend` 인터페이스를 Docker 컨테이너로 구현하여 격리된 명령 실행 환경을 제공합니다.

## 의존성 추가

```kotlin
// build.gradle.kts — `aimon-sandbox` 는 `api` 로 따라오므로 적지 않아도 된다
dependencies {
    implementation("at.aimon.sandbox:aimon-sandbox-docker:<version>")
    implementation("at.aimon.core:aimon-core:<version>")
}
```

## 이 모듈 자신의 의존성

```kotlin
dependencies {
    api(project(":aimon-sandbox"))
    implementation("com.github.docker-java:docker-java-core")
    implementation("com.github.docker-java:docker-java-transport-httpclient5")
}
```

## 핵심 클래스

| 클래스 | 역할 |
|--------|------|
| `DockerSandboxConfig` | Docker 전용 설정 (리소스 제한, 보안 옵션, tmpfs 등) |
| `DockerClientFactory` | DockerClient 인스턴스 생성 팩토리 |
| `DockerSandboxBackend` | `SandboxBackend` 구현체 (컨테이너 생명주기 관리) |

## 사용법

### 기본 설정

```java
SandboxConfig sandboxConfig = SandboxConfig.builder()
    .defaultImage("ubuntu:22.04")
    .build();

DockerSandboxConfig dockerConfig = DockerSandboxConfig.builder()
    .memoryBytes(512 * 1024 * 1024L) // 512MB
    .cpuCount(1L)
    .networkMode("none")
    .build();
```

### 백엔드 생성 및 사용

```java
DockerClient dockerClient = DockerClientFactory.create(dockerConfig);
SandboxExpiryStore expiryStore = new InMemorySandboxExpiryStore();

try (DockerSandboxBackend backend = new DockerSandboxBackend(
        dockerClient, sandboxConfig, dockerConfig, expiryStore)) {

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

### DockerSandboxConfig

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `dockerHost` | (자동 감지) | Docker 데몬 호스트 URL |
| `memoryBytes` | 512MB | 컨테이너 메모리 제한 |
| `cpuCount` | 1 | CPU 코어 수 |
| `pidsLimit` | 256 | 최대 프로세스 수 |
| `networkMode` | `"none"` | 네트워크 모드 (격리) |
| `readonlyRootfs` | `false` | 루트 파일시스템 읽기 전용 |
| `dropCapabilities` | `["ALL"]` | 제거할 Linux capability |
| `noNewPrivileges` | `true` | 권한 상승 차단 |
| `tmpfsMounts` | `/tmp:100m,noexec` | tmpfs 마운트 설정 |
| `sandboxUserUid` | 1000 | 샌드박스 사용자 UID |

## 컨테이너 관리

### 명명 규칙

- 컨테이너 이름: `sandbox-{identifier}`
- 레이블: `aimon.at/role=sandbox`, `aimon.at/identifier={identifier}`

### 초기화 과정

1. 이미지 기반 컨테이너 생성 (`sleep infinity` 실행)
2. 샌드박스 사용자 생성 (UID 1000)
3. `/workspace`, `/artifacts`, `/artifacts/logs` 디렉토리 생성
4. 디렉토리 소유권 설정

### TTL 관리

- `SandboxExpiryStore`를 통해 만료 시간 추적
- `reapExpired()`로 만료된 컨테이너 정리
- 컨테이너 레이블에 만료 시간 기록 (폴백용)

## 보안

Docker 컨테이너는 다음과 같은 보안 설정으로 실행됩니다:

- 모든 Linux capability 제거 (`--cap-drop ALL`)
- 권한 상승 차단 (`--security-opt no-new-privileges`)
- 네트워크 격리 (`--network none`)
- PID 제한 (`--pids-limit 256`)
- 메모리 제한
- tmpfs에 `noexec` 옵션 적용

## 테스트

```bash
# 단위 테스트
./gradlew :aimon-sandbox-docker:test

# 통합 테스트 (Docker 데몬 필요)
AIMON_DOCKER_IT=true ./gradlew :aimon-sandbox-docker:test
```

통합 테스트는 `AIMON_DOCKER_IT=true` 환경 변수가 설정된 경우에만 실행됩니다.
