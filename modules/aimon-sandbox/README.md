# AIMON Sandbox

영속적(persistent) 컨테이너/파드 환경에서 명령을 실행하고, VFS와 양방향으로 파일을 전송하는 샌드박스 모듈입니다.

## 특징

- **영속 샌드박스**: 식별자(identifier) 기반으로 컨테이너를 재사용하며, TTL 만료 시 자동 정리
- **순차 명령 실행**: 여러 명령을 순서대로 실행하고, 실패 시 중단 또는 계속 옵션 제공
- **양방향 파일 전송**: VFS → 샌드박스 (`CopyToSandbox`), 샌드박스 → VFS (아티팩트 추출)
- **동시성 제어**: `SandboxLock`을 통한 per-identifier 직렬화
- **멀티 인스턴스 지원**: `RunStore`, `SandboxLock`, `SandboxBackend` 인터페이스 분리

## 의존성 추가

```kotlin
// build.gradle.kts
dependencies {
    implementation("at.aimon.sandbox:aimon-sandbox:<version>")
    // 도구를 등록할 에이전트가 있어야 하므로 프레임워크도 함께 필요하다
    implementation("at.aimon.core:aimon-core:<version>")
}
```

백엔드를 함께 쓴다면 `aimon-sandbox` 를 따로 적을 필요가 없다 — 두 백엔드 모듈이 이 모듈을
`api` 로 내보내므로 `at.aimon.sandbox:aimon-sandbox-docker` 한 줄이면 SPI 까지 따라온다.

> 좌표가 `at.aimon.core:aimon-sandbox` 였던 것을 찾고 있다면: 0.2.4 까지가 그 이름이고,
> 그 아티팩트는 Central 에 그대로 남아 있다. 이 저장소로 분리되면서 group 이 바뀌었다.

## 아키텍처

```
┌──────────────────────────────────────────────────────────┐
│                      Tools (Agent 연동)                   │
│  RunSandboxTool  CopyToSandboxTool  RestartSandboxTool   │
│  DeleteSandboxTool                                       │
└──────────────┬───────────────────────────────────────────┘
               │
┌──────────────▼───────────────────────────────────────────┐
│              RunManager ↔ RunStore (interface)            │
│              SandboxLock (interface)                      │
│              TarCreator / TarExtractor                   │
└──────────────┬───────────────────────────────────────────┘
               │
┌──────────────▼───────────────────────────────────────────┐
│              SandboxBackend (interface)                   │
│              구현체는 별도 모듈 (Docker, Kubernetes 등)     │
└──────────────────────────────────────────────────────────┘
```

## 사용 방법

### Orca 에이전트에 등록

```java
SandboxBackend backend = new DockerSandboxBackend(...);
SandboxConfig config = SandboxConfig.builder()
    .defaultTtlSeconds(1800)
    .defaultImage("ubuntu:22.04")
    .build();
RunStore runStore = new InMemoryRunStore();
SandboxLock sandboxLock = new LocalSandboxLock();

OrcaSandboxToolProvider provider = new OrcaSandboxToolProvider(
    backend, config, runStore, sandboxLock
);
provider.registerTools(toolRegistry, toolProviderContext);
```

등록되는 Tool:

| Tool | 설명 |
|------|------|
| `RunSandbox` | 명령 실행 및 아티팩트 추출 |
| `CopyToSandbox` | VFS 파일을 샌드박스로 복사 |
| `RestartSandbox` | 샌드박스 삭제 후 재생성 |
| `DeleteSandbox` | 샌드박스 삭제 |

### SandboxConfig 옵션

| 메서드 | 설명 | 기본값 |
|--------|------|--------|
| `defaultTtlSeconds(int)` | 기본 TTL (초) | `1800` |
| `maxTtlSeconds(int)` | 최대 TTL (초) | `86400` |
| `defaultCommandTimeoutMs(int)` | 기본 명령 타임아웃 (ms) | `120000` |
| `maxCommandTimeoutMs(int)` | 최대 명령 타임아웃 (ms) | `600000` |
| `defaultImage(String)` | 기본 컨테이너 이미지 | `ubuntu:22.04` |
| `defaultCwd(String)` | 기본 작업 디렉토리 | `/workspace` |
| `defaultLockSandbox(boolean)` | 기본 락 사용 여부 | `true` |

## 핵심 인터페이스

### SandboxBackend

컨테이너/파드 생명주기 관리와 명령 실행을 담당하는 인터페이스입니다. Docker, Kubernetes 등의 구현체를 별도 모듈로 제공합니다.

```java
public interface SandboxBackend {
    Sandbox ensure(String identifier, int ttlSeconds);
    void delete(String identifier);
    Sandbox restart(String identifier, int ttlSeconds);
    ExecResult exec(String sandboxId, ExecParams params);
    InputStream copyArtifacts(String sandboxId);
    void copyToSandbox(String sandboxId, InputStream tarStream, String destPath);
}
```

### RunStore

실행 이력 저장소입니다. 기본 구현은 `InMemoryRunStore`이며, 분산 환경에서는 외부 저장소(Redis, JDBC 등) 구현체를 사용합니다.

### SandboxLock

per-identifier 직렬화 락입니다. 기본 구현은 `LocalSandboxLock` (in-process ReentrantLock)이며, 분산 환경에서는 Redis, ZooKeeper 등의 구현체를 사용합니다.

## 보안 정책

Tar 아카이브 생성/추출 시 다음 보안 제한이 적용됩니다:

| 항목 | 제한 |
|------|------|
| 최대 파일 수 | 1,000개 |
| 최대 총 크기 | 100 MB |
| 최대 파일 크기 | 50 MB |
| 경로 순회 | `..`, 절대 경로, 드라이브 문자 차단 |
| 파일 타입 | 일반 파일 + 디렉토리만 (심볼릭 링크 무시) |

## 실행 상태 (RunState)

```
QUEUED → RUNNING → COMPLETED
                 → FAILED
```

| 상태 | 설명 |
|------|------|
| `QUEUED` | 생성 완료, 실행 대기 |
| `RUNNING` | 명령 실행 중 |
| `COMPLETED` | 정상 완료 |
| `FAILED` | 오류로 실패 |

## 참고

- [Tool 개발 가이드](../../docs/features/tool/tool-development-guide.md)
- [SOLID 원칙](../../docs/project/solid-principles.md)
