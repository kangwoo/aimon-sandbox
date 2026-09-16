# 샌드박스 — 격리된 실행 환경을 identifier 로 재사용한다

> Status: **IMPLEMENTED** — `aimon-sandbox` (32파일) + `aimon-sandbox-docker` (3) +
> `aimon-sandbox-kubernetes` (3). 공개 표면은 `SandboxBackend` · `SandboxConfig` ·
> `RunStore` · `SandboxExpiryStore` · `SandboxLock` · `ReaperService` ·
> `OrcaSandboxToolProvider` 이고, 백엔드 두 개는 `SandboxBackend` 구현체로만 붙는다.
> 남은 것은 §14.
>
> **이 문서는 aimon-core 에서 옮겨 왔다.** 세 모듈은 이제 별도 저장소
> ([aimon-sandbox](https://github.com/kangwoo/aimon-sandbox))에 살며 `at.aimon.core:aimon-core` 를
> Maven Central 에서 받아 쓴다. 설계는 한 글자도 바뀌지 않았다 — 바뀐 것은 좌표
> (`at.aimon.core:*` → `at.aimon.sandbox:*`)와 빌드가 서는 자리뿐이다. 분리의 근거는
> 이 저장소의 `CHANGELOG.md` 와 aimon-core 의
> [`docs/migration/rename-maps.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/migration/rename-maps.md) 에 있다.

---

## 1. 무엇을 푸는가

### 1.1 목표

에이전트가 코드를 실행해야 하는 순간 — 생성한 코드를 돌려 보기, 빌드·테스트, 데이터 처리 — 에
호스트를 내주지 않는 실행 자리를 준다. `BashTool` 은 호스트에서 직접 돌기 때문에 격리가 없고,
프로세스가 끝나면 상태도 사라진다. 샌드박스는 그 둘을 뒤집는다.

- **격리** — 모든 명령은 컨테이너/파드 안에서 돈다
- **재사용** — `identifier` 하나로 같은 실행 환경에 계속 되돌아온다. 두 번째 호출은 첫 번째가
  만든 파일 시스템을 그대로 본다
- **백엔드 독립** — Docker 로 개발하고 Kubernetes 로 배포해도 도구 코드는 같다
- **멀티 인스턴스 대비** — 상태를 쥔 셋(`RunStore` · `SandboxExpiryStore` · `SandboxLock`)이
  전부 인터페이스다. 스케일아웃은 리팩터가 아니라 구현체 교체다

### 1.2 비목표

- **범용 컨테이너 오케스트레이션이 아니다.** 샌드박스는 identifier 당 파드/컨테이너 하나이고,
  레플리카·서비스·볼륨 같은 개념이 없다
- **실행 취소가 없다.** `RunState` 에 `CANCELED` 가 없는 것은 누락이 아니라 결정이다(§13)
- **샌드박스별 이미지를 고르지 않는다.** 이미지는 `SandboxConfig.defaultImage` 로 전역이다
- **격리의 최종 방어선이 아니다.** 컨테이너 탈출을 막는 것은 런타임(gVisor, Kata …)의 일이고,
  이 모듈은 그 위에서 리소스·권한·경로를 좁힌다

---

## 2. 용어

| 용어 | 뜻 |
|------|-----|
| **identifier** | 샌드박스를 가리키는 안정적 키. `^[a-zA-Z0-9_-]{1,36}$` (`IdentifierValidator`) |
| **Sandbox** | identifier 에 귀속된 장수명 실행 환경 — Docker 컨테이너 또는 K8s 파드 |
| **Run** | `RunSandboxTool` 호출 1회. `runId`(UUID) 를 받고 command 를 순차 실행한다 |
| **command** | Run 안의 명령 1건. `shell` 또는 `argv` 중 정확히 하나 |
| **artifacts** | 샌드박스 안 `/artifacts` 디렉터리의 파일. Run 이 끝나면 VFS 로 빠져나온다 |

Run 은 **턴이 아니다.** 한 턴 안에서 `RunSandboxTool` 을 세 번 부르면 Run 도 셋이고, 세 Run 은
같은 샌드박스를 공유한다. 반대로 샌드박스는 턴보다 오래 산다 — TTL 이 끝날 때까지.

---

## 3. 아키텍처

### 3.1 모듈 배치

```
┌─ aimon-sandbox ───────────────────────────────────────────────────────────┐
│                                                                           │
│  tool/     OrcaSandboxToolProvider                                        │
│            ├── RunSandboxTool      (GenericTool)  명령 실행 + artifacts   │
│            ├── CopyToSandboxTool   (GenericTool)  VFS → 샌드박스          │
│            ├── DeleteSandboxTool   (AbstractTool) 삭제                    │
│            ├── RestartSandboxTool  (AbstractTool) 재시작                  │
│            ├── RunSandboxInput / CopyToSandboxInput  (입력 record)        │
│            ├── RunResultFormatter  (결과 렌더링 + truncation)             │
│            └── SandboxToolHelper   (락·TTL 해석 공통, package-private)    │
│                                                                           │
│  run/      RunManager · RunStore(I) · InMemoryRunStore                    │
│  lock/     SandboxLock(I) · LocalSandboxLock                              │
│  artifact/ TarCreator · TarExtractor · TarSecurityPolicy                  │
│  backend/  SandboxBackend(I) · ExecParams · ExecResult                    │
│            SandboxExpiryStore(I) · InMemorySandboxExpiryStore             │
│  model/    Sandbox · SandboxRun · CommandInput · CommandResult            │
│            RunState · SandboxUser                                         │
│  config/   SandboxConfig                                                  │
│  reaper/   ReaperService                                                  │
│  util/     IdentifierValidator · ByteFormatUtils                          │
└───────────────────────────────────────────────────────────────────────────┘
                                   ▲ api
                      ┌────────────┴────────────┐
        ┌─────────────┴──────────┐   ┌──────────┴─────────────────┐
        │  aimon-sandbox-docker  │   │ aimon-sandbox-kubernetes    │
        │  DockerSandboxBackend  │   │ KubernetesSandboxBackend    │
        │  DockerSandboxConfig   │   │ KubernetesSandboxConfig     │
        │  DockerClientFactory   │   │ KubernetesClientFactory     │
        └────────────────────────┘   └─────────────────────────────┘
```

의존은 한 방향이다 — 백엔드 모듈이 `api(project(":aimon-sandbox"))` 를 보고, 코어 모듈은 백엔드를
모른다. `aimon-sandbox` 자신은 `aimon-core` 와 slf4j 만 본다. Docker 쪽은 `docker-java` 3.4.1,
K8s 쪽은 `kubernetes-client-java` 24.0.0 을 각자 끌어온다 — 어느 쪽도 코어를 오염시키지 않는다.

### 3.2 수명

전부 **application-scoped** 다. 에이전트 런타임보다 오래 살고, 런타임이 소멸할 때 닫으면 안 된다.

| 컴포넌트 | 만드는 곳 | 닫는 곳 |
|---------|----------|--------|
| `SandboxBackend` | 어셈블리 (`Closeable` 구현) | 앱 shutdown |
| `SandboxConfig` · `RunStore` · `SandboxExpiryStore` · `SandboxLock` | 어셈블리 → 프로바이더에 주입 | 앱 shutdown — **프로바이더는 소유하지 않는다** |
| `RunManager` · `TarCreator` · `TarExtractor` | `OrcaSandboxToolProvider.registerTools()` | 닫을 자원 없음 |
| 도구 4개 | 같은 곳, 레지스트리에 등록 | stateless |
| `ReaperService` | 어셈블리 — 프로바이더 **바깥** | 앱 shutdown 시 `close()` |

`ReaperService` 를 프로바이더 밖에 둔 것은 의도다. 도구 등록과 만료 정리는 서로 다른 이유로
켜고 끄고 싶은 일이고, 도구 없이 리퍼만 도는 배치(정리 전용 노드)도 성립한다.

---

## 4. `SandboxBackend` — 인터페이스는 하나다

### 4.1 계약

생명주기·명령 실행·파일 전송을 한 인터페이스로 묶었다. 여덟 메서드 전부 `throws IOException` 이다.

| 메서드 | 뜻 |
|-------|-----|
| `ensure(identifier, ttlSeconds) → Sandbox` | **멱등** — 없으면 만들고, 있으면 재사용하며 TTL 을 갱신한다 |
| `delete(identifier)` | 제거 |
| `restart(identifier, ttlSeconds) → Sandbox` | 삭제 후 재생성 |
| `count() → int` | 활성 샌드박스 수 |
| `reapExpired() → int` | 만료분 정리, 정리한 개수 반환 |
| `exec(sandboxId, ExecParams) → ExecResult` | 명령 1건 실행 |
| `copyArtifacts(sandboxId) → InputStream` | `/artifacts` 를 tar 스트림으로 |
| `copyToSandbox(sandboxId, tarStream, destPath)` | tar 스트림을 샌드박스 안으로 |

`ensure()` 의 멱등성은 구현체가 **충돌을 잡아서** 만든다 — 미리 조회하고 없으면 만드는 식이면
그 사이에 경합이 생긴다. Docker 는 `createContainer` 의 name conflict 를, K8s 는
`createNamespacedPod` 의 409 Conflict 를 catch 해서 기존 것을 돌려준다.

새로 만든 샌드박스는 초기화 단계에서 `sandbox` 사용자(uid 1000)와 `/workspace`, `/artifacts`,
`/artifacts/logs` 를 만든다. 즉 도구가 `/artifacts` 의 존재를 가정해도 된다.

### 4.2 identifier 를 백엔드 이름으로

- 이름: `sandbox-{identifier.toLowerCase()}`
- 라벨(양쪽 공통): `aimon.at/role=sandbox`, `aimon.at/identifier={identifier}`
- 만료 시각: Docker 는 **라벨** `aimon.at/expires-at`, K8s 는 같은 키를 **애노테이션**으로

K8s 만 애노테이션인 이유는 하나다 — ISO 8601 타임스탬프의 콜론이 K8s 라벨 값에서 불법이다.
Docker 라벨에는 그 제약이 없어 그대로 라벨을 쓴다.

### 4.3 TTL 은 두 곳에 산다

주 저장소는 `SandboxExpiryStore` (`put` / `get` / `remove`, 기본 구현은
`ConcurrentHashMap<String, Instant>`)이고, 백엔드의 라벨·애노테이션은 **fallback** 이다.

둘을 둔 이유: in-memory 스토어는 프로세스가 죽으면 사라지지만, 컨테이너와 파드는 살아남는다.
그때 라벨이 없으면 재시작한 프로세스는 살아 있는 샌드박스들의 만료 시각을 영영 모르고 — 리퍼가
아무것도 정리하지 못한 채 자원만 쌓인다. 멀티 인스턴스에서는 스토어를 외부 구현으로 갈아 끼우면
fallback 에 기댈 일이 줄어든다.

---

## 5. 실행 단위 — Run

### 5.1 모델

전부 불변 클래스 + 빌더다. 상태 변경은 `with*()` 가 새 인스턴스를 돌려주는 방식이고, 원자적
교체는 `RunStore` 가 책임진다.

**`SandboxRun`** — `runId`(UUID) · `identifier` · `state` · `createdAt` · `startedAt`? ·
`endedAt`? · `sandboxId` · `commands`(`List.copyOf`) · `artifactCount` · `artifactTotalBytes` ·
`error`?. `with*()` 는 `withState` · `withStartedAt` · `withEndedAt` · `withCommandResult` ·
`withArtifactSummary(count, totalBytes)` · `withError`.

artifact **목록**이 여기 없는 것은 결정이다(§13) — 개수와 총 바이트만 남기고, 파일 목록은 VFS 에서
직접 읽는다.

**`CommandInput`** — `shell` XOR `argv` · `cwd`? · `env`? · `timeoutMs`? · `allowFailure`.
`shell` 과 `argv` 의 배타성은 **생성자에서** 검증한다. 그래서 그 규칙을 상위 계층이 다시 적을
필요가 없다.

**`CommandResult`** — `index`(0-based) · `command` 요약 · `exitCode` · `stdout` · `stderr` ·
`error`?(실행 자체가 실패한 경우) · `durationMs`.

**`ExecParams`** — `command`(필수) · `cwd` · `env`(빈 Map) · `asUser` · `timeoutMs`(120,000) ·
`maxOutputBytes`(1,048,576). **`ExecResult`** — `exitCode` · `stdout`("") · `stderr`("").

**`RunState`** — `QUEUED → RUNNING → COMPLETED | FAILED`. **`SandboxUser`** — `ROOT`, `SANDBOX`.

### 5.2 `RunManager` — 상태 전이만

저장은 `RunStore` 에 위임하고 전이만 조율한다: `createRun(identifier, sandboxId)`(QUEUED) →
`start(runId)`(RUNNING, `startedAt`) → `addCommandResult(runId, result)` →
`complete(runId, count, bytes)`(COMPLETED, `endedAt`) 또는 `fail(runId, error)`(FAILED).
조회는 `getRun(runId) → Optional<SandboxRun>`.

### 5.3 `RunStore`

`save(run)` · `findById(runId)` · `update(runId, UnaryOperator<SandboxRun>)`.

기본 구현 `InMemoryRunStore` 는 `ConcurrentHashMap` + `ConcurrentLinkedDeque`(삽입 순서) 조합에
LRU 축출(기본 1000건)이다. `save()` 는 이미 있는 `runId` 에 대해 `IllegalArgumentException` 을
던지고 — 덮어쓰기를 조용히 허용하면 Run 하나가 다른 Run 의 결과를 삼킨다 — `update()` 는
`compute()` 로 원자적 교체를 보장한다.

---

## 6. 도구 4개

### 6.1 등록

`OrcaSandboxToolProvider` 는 `backend` · `config` · `runStore` · `sandboxLock` 을 생성자로 받고,
`registerTools(registry, context)` 에서 `RunManager` · `TarCreator` · `TarExtractor` 를 만들고
컨텍스트에서 VFS 를 꺼내 네 도구를 등록한다.

### 6.2 `RunSandboxTool`

```
1.  identifier 검증 (IdentifierValidator)
2.  ttl / continue_on_error / lock_sandbox 해석 (SandboxToolHelper)
3.  lock(identifier)                          ← lock_sandbox=true 일 때만
4.  backend.ensure(identifier, ttl) → Sandbox
5.  runManager.createRun() → start()          QUEUED → RUNNING
6.  for each command:
      backend.exec(sandboxId, ExecParams) → ExecResult
      runManager.addCommandResult(...)
      exitCode ≠ 0 && !allowFailure && !continueOnError → break
7.  writeCommandLogs()                        full 출력 → /artifacts/logs/
8.  backend.copyArtifacts() → TarExtractor → VFS
      /sandbox-artifacts/{identifier}/{runId}/
9.  ArtifactCollector 에 등록                 (ToolContextKeys.ARTIFACT_COLLECTOR)
10. runManager.complete() 또는 fail()
11. unlock(identifier)                        finally
```

입력은 `RunSandboxInput` record 다 — `identifier`(필수) · `commands`(필수,
`List<CommandSpec>`) · `ttl_seconds` · `continue_on_error` · `lock_sandbox`. 중첩
`CommandSpec` 은 `shell` · `argv` · `cwd` · `timeout_ms` · `allow_failure`.

**`CommandSpec` 에는 `env` 가 없다.** `CommandInput` 에는 있다 — 즉 환경 변수는 프로그램에서
`CommandInput` 을 직접 만들 때만 실릴 수 있고, 모델은 명령별 환경 변수를 지정할 수 없다.
의도적으로 좁혀 둔 자리이며 넓히려면 `CommandSpec` 에 컴포넌트를 추가하면 된다(§14).

`CommandSpec` 이 `shell`/`argv` 배타성을 다시 적지 않는 것도 의도다 — `CommandInput` 이 이미
생성자에서 강제하므로, 스키마에 중복해 적으면 두 곳이 어긋날 수 있는 자리만 하나 는다. 모든
컴포넌트가 래퍼 타입인 것 역시 그렇다: 없는 것과 `0`/`false` 로 준 것을 구분해야 한다.

### 6.3 `CopyToSandboxTool`

```
identifier 검증 → lock → backend.ensure → TarCreator.create(vfs, entries)
  → backend.copyToSandbox(sandboxId, tarStream, destPath) → unlock
```

입력 `CopyToSandboxInput` — `identifier`(필수) · `files`(필수, `List<FileEntrySpec>`) ·
`dest_path`(기본 `/workspace`) · `ttl_seconds` · `lock_sandbox`. `FileEntrySpec` 은
`source`(필수, VFS 경로) · `dest_name`(기본: 원본 파일명).

`DeleteSandboxTool` 과 `RestartSandboxTool` 은 identifier 하나(+ 재시작은 TTL)만 받는다.

### 6.4 왜 `AbstractTool` 과 `GenericTool` 이 섞여 있는가

| 도구 | 베이스 |
|------|-------|
| `RunSandboxTool` | `GenericTool<RunSandboxInput, String>` |
| `CopyToSandboxTool` | `GenericTool<CopyToSandboxInput, String>` |
| `DeleteSandboxTool` | `AbstractTool` |
| `RestartSandboxTool` | `AbstractTool` |

섞인 것은 이행이 덜 끝나서가 아니라 **끝낼 이유가 없어서**다. 앞의 둘은 중첩 리스트를 받는다 —
예전에는 `List<Map<String, Object>>` 로 도착했고, 그것이 이 도구들의 유일한 unchecked cast 였다.
철자가 틀린 `timeout_ms` 는 조용히 버려졌고 모델은 아무 단서도 받지 못했다. `CommandSpec` 을
선언하면 각 원소가 최상위 파라미터와 같은 규칙으로 바인딩되고, 실패가 위치를 짚어 준다 —
`commands[2].timeout_ms` 라고.

뒤의 둘에는 그런 구조가 없다. `SandboxToolHelper` 의 표현대로, `RestartSandboxTool` 은
`GenericTool` 로 옮길 만한 크기 문턱 아래에 있다. 그래서 `SandboxToolHelper` 의 헬퍼는
오버로드가 둘씩이다 — 하나는 `ToolInput`, 하나는 바인딩된 record 를 받는다.

`SandboxToolHelper`(package-private, all-static)가 가진 것: `withOptionalLock(lock, identifier,
lockSandbox, action)` · `resolveTtl` ×2 (`Math.min(requested, maxTtl)`) · `resolveLockSandbox` ×2 ·
함수형 인터페이스 `SandboxToolAction<T>`.

### 6.5 로그는 두 벌로 남는다

모델에게 돌아가는 것은 `RunResultFormatter` 가 `MAX_OUTPUT_LENGTH = 30,000`자로 자른 출력이고,
전체 출력은 샌드박스 안 `/artifacts/logs/command-{index}.{stdout|stderr}` 에 남아 artifacts 로
빠져나온다(`LOG_PATH_PATTERN = ^/artifacts/logs/command-\d+\.(stdout|stderr)$`).

전송은 base64 + 64KB 청크다 — 첫 청크는 `echo '<base64>' | base64 -d > path`, 이후는 `>>`.
셸을 거치면서 바이너리·개행·따옴표가 깨지지 않게 하려는 것이고, 청크로 쪼개는 것은 명령줄 길이
상한을 넘지 않기 위해서다.

빌드 로그는 쉽게 수십 MB 가 된다. 그것을 그대로 컨텍스트에 넣으면 턴 하나가 예산을 다 쓰고,
아예 버리면 실패를 진단할 방법이 없다. 30,000자와 전체 로그 파일은 그 둘의 타협이다.

### 6.6 병렬 실행 대상이 아니다

네 도구 중 어느 것도 `getConcurrencyBehavior()` 를 override 하지 않으므로 전부 기본값
`SEQUENTIAL` 이다. 넷 모두 샌드박스 상태를 변조하고, 같은 identifier 를 다투면 §7 의 락에
직렬화되어 병렬로 얻을 것이 없다.

---

## 7. 직렬화 — `SandboxLock`

`ensure → exec → artifact 추출` 구간을 identifier 단위로 감싼다. `lock(identifier)` ·
`unlock(identifier)` · `default removeLock(identifier)`(기본 no-op — 분산 구현이 필요할 때만
채운다).

기본 구현 `LocalSandboxLock` 은 `ConcurrentHashMap<String, RefCountedLock>` 이고,
`RefCountedLock` 은 `ReentrantLock` + `AtomicInteger refCount` 다.

- `lock()` — refCount 증가 후 `ReentrantLock` 획득
- `unlock()` — 해제 → refCount 감소 → 0 이면 맵에서 제거
- `removeLock()` — refCount 가 0 일 때만 제거(다른 스레드가 쥐고 있으면 무시)

refCount 가 있는 이유는 단순한 `Map<String, ReentrantLock>` 에 경합이 있기 때문이다. `unlock()`
직후 맵에서 지우는 순간, 다른 스레드가 같은 키로 이미 `lock()` 을 얻었다면 **그 스레드가 쥔 락이
지워진다.** 그다음 들어온 세 번째 스레드는 새 락을 만들어 임계 구역에 같이 들어간다. refCount 는
"지금 이 락을 보는 사람이 몇인가"를 세어 그 창을 닫는다.

락은 **끌 수 있다** — `lock_sandbox=false`. 서로 다른 identifier 를 쓰는 호출들끼리는 애초에
경합이 없고, 같은 identifier 라도 읽기만 하는 명령이라면 직렬화가 낭비다. 기본값은 안전한 쪽
(`SandboxConfig.defaultLockSandbox = true`)이다.

---

## 8. Artifact 전송 — tar

방향은 둘이다. `TarExtractor` 가 tar → VFS(artifacts 회수), `TarCreator` 가 VFS → tar(파일 투입).

### 8.1 `TarSecurityPolicy` — 상한은 한 곳에서

두 방향이 같은 상한을 봐야 하므로 상수를 한 클래스에 모았다.

| 상수 | 값 |
|------|-----|
| `MAX_FILES` | 1,000 |
| `MAX_TOTAL_BYTES` | 100MB |
| `MAX_FILE_BYTES` | 50MB |
| `TAR_BLOCK_SIZE` (package-private) | 512 |

여기에 경로·타입 규칙이 붙는다.

- **path traversal 차단** — `..`, 절대 경로, Windows 드라이브 문자를 거부한다
- **regular file 과 directory 만** — symlink·hardlink 는 무시한다. 링크를 따라가면 tar 하나로
  샌드박스 밖 경로를 가리킬 수 있고, VFS 는 그 링크의 의미를 재현할 수 없다

`TarSecurityPolicy` 의 javadoc 이 적어 둔 대로, 두 방향이 같은 정책을 보게 하는 것이 목적이다 —
나가는 쪽만 조이면 들어오는 쪽으로 같은 공격이 그대로 통과한다.

### 8.2 VFS 에 놓이는 자리

`/sandbox-artifacts/{identifier}/{runId}/`. 그다음 `ToolContextKeys.ARTIFACT_COLLECTOR` 로 꺼낸
컬렉터에 등록되어 코어의 artifact 경로를 탄다.

VFS 를 고른 것은 저장소 독립성 때문이다. 로컬 디렉터리로 했다면 멀티 인스턴스에서 공유 스토리지가
전제가 됐을 것이고, GridFS·S3 로 옮기는 것이 설정이 아니라 코드 변경이 됐을 것이다.

---

## 9. 정리 — `ReaperService`

`Closeable` 이고, 단일 daemon 스레드 `ScheduledExecutorService` 에서 `scheduleWithFixedDelay` 로
`backend.reapExpired()` 를 주기적으로 부른다(기본 5초). `start()` 는 `AtomicBoolean` 으로 멱등하고,
`close()` 는 executor 를 shutdown 한 뒤 5초 기다린다.

만료 판정은 백엔드가 한다 — `SandboxExpiryStore` 를 보고, 없으면 라벨·애노테이션으로 되돌아간다
(§4.3).

---

## 10. 설정

### 10.1 `SandboxConfig`

| 필드 | 기본값 |
|------|--------|
| `defaultTtlSeconds` | 1,800 (30분) |
| `maxTtlSeconds` | 86,400 (24시간) |
| `defaultCommandTimeoutMs` | 120,000 (2분) |
| `maxCommandTimeoutMs` | 600,000 (10분) |
| `defaultImage` | `ubuntu:22.04` |
| `defaultCwd` | `/workspace` |
| `reaperIntervalMs` | 5,000 |
| `defaultLockSandbox` | `true` |

`validate()` 가 양수 제약과 `maxTtl >= defaultTtl` 을 확인한다. 요청 TTL 은 거부되지 않고
`Math.min(requested, maxTtl)` 로 **잘린다**(`SandboxToolHelper.resolveTtl`) — 상한을 넘겼다고
실행을 실패시키면 모델이 상한을 추측해야 한다.

### 10.2 `DockerSandboxConfig`

| 필드 | 기본값 |
|------|--------|
| `memoryLimit` | 512MB |
| `cpuCount` | 1 |
| `pidsLimit` | 256 |
| `networkMode` | `none` |
| `dropCapabilities` | `["ALL"]` |
| `addCapabilities` | `[]` |
| `noNewPrivileges` | `true` |
| `readonlyRootfs` | `false` |
| `tmpfsBinds` | `{"/tmp": "size=100m,noexec"}` |
| `sandboxUser` | `sandbox` (uid 1000) |

`readonlyRootfs` 가 기본 `false` 인 것은 의도다 — 샌드박스의 용도가 빌드·설치이고, 루트를 읽기
전용으로 잠그면 `apt`·`npm` 같은 흔한 명령이 곧바로 깨진다. 실행 내용이 정해진 배치라면 켜는 쪽이
낫다.

### 10.3 `KubernetesSandboxConfig`

| 필드 | 기본값 |
|------|--------|
| `namespace` | `default` |
| `memoryLimit` / `memoryRequest` | `512Mi` / `256Mi` |
| `cpuLimit` / `cpuRequest` | `1` / `500m` |
| `dropCapabilities` | `["ALL"]` |
| `allowPrivilegeEscalation` | `false` |
| `readOnlyRootFilesystem` | `false` |
| `nodeSelector` | `{}` |
| `sandboxUser` | `sandbox` (uid 1000) |
| `podReadyTimeoutMs` | 60,000 |
| `apiConnectionTimeoutMs` | 10,000 |
| `apiReadTimeoutMs` | 30,000 |

리소스 문자열은 `QUANTITY_PATTERN` (`\d+(\.\d+)?([EPTGMK]i?|[epmunf])?`) 으로 검증한다 — 잘못된
수량은 파드 생성 시점에 API 서버가 거부하지만, 그때는 이미 identifier 가 소모된 뒤다.

API 타임아웃 둘이 따로 있는 것은 파드 준비 대기(`podReadyTimeoutMs`)와 API 호출 자체의 타임아웃이
다른 층이기 때문이다. 연결이 30초 매달려 있는 것과 파드가 30초 뒤에 Ready 가 되는 것은 다른 실패다.

---

## 11. 보안

| 위협 | 대응 |
|------|------|
| 호스트 침투 | 컨테이너/파드 격리, 네트워크 차단(Docker `networkMode=none`), 권한 제한 |
| 자원 고갈 | Docker memory/cpu/pids limit, K8s resources request/limit |
| 권한 상승 | `noNewPrivileges=true`, `dropCapabilities=["ALL"]`, `allowPrivilegeEscalation=false`, 비루트 uid 1000 |
| path traversal | §8.1 — `..`/절대경로/드라이브 문자 차단, symlink·hardlink 무시 |
| 대용량 artifact | 파일 1,000개 · 총 100MB · 파일당 50MB |
| 출력 폭탄 | `ExecParams.maxOutputBytes` 1MB, 결과 30,000자 truncation |
| 환경변수 인젝션 (K8s) | `ENV_KEY_PATTERN` = `[A-Za-z_][A-Za-z0-9_]*` 로 key 검증 |
| identifier 인젝션 | `IdentifierValidator` — `^[a-zA-Z0-9_-]{1,36}$`, 셸/라벨/파드명 어디에 넣어도 안전한 문자만 |

`identifier` 를 36자 영숫자·`_`·`-` 로 좁힌 것은 미관이 아니다. 이 값은 컨테이너 이름, K8s 라벨 값,
VFS 경로 세 곳에 그대로 들어간다 — 셋의 허용 문자 교집합이 이 패턴이다.

---

## 12. 에러 처리

도구는 예외를 던지지 않는다. 전부 `ToolResult` 다.

| 상황 | 결과 |
|------|------|
| identifier 포맷 불일치 | `ToolResult.error("Invalid parameter: ...")` |
| commands 비어 있음 | `ToolResult.error("Commands must not be empty")` |
| `ensure()` 실패 | `ToolResult.error("Run failed: ...", exception)` |
| `exec()` 중 IOException | 해당 `CommandResult.error` 에 기록 → Run 은 FAILED |
| command timeout | exit code 를 기록하고 `allowFailure`/`continueOnError` 정책을 적용 |
| exit code ≠ 0 | `allowFailure` 또는 `continueOnError` 면 계속, 아니면 Run 중단 |
| artifacts 추출 실패 | **Run 은 COMPLETED**, artifacts 에러를 결과 문자열에 포함 |
| VFS 없음 | `CopyToSandboxTool` 은 에러, `RunSandboxTool` 은 artifact 추출을 건너뛴다 |

artifacts 추출 실패가 Run 을 실패시키지 않는 것이 유일하게 헷갈리는 자리다. 명령이 다 성공했는데
회수 단계에서 넘어졌다면 실행 자체는 일어난 것이고, 모델에게 필요한 정보(exit code, 출력)는 이미
손에 있다. 그것을 FAILED 로 덮으면 모델이 명령을 다시 돌린다.

---

## 13. 기각한 대안

| 대안 | 왜 기각했나 |
|------|------------|
| `Lifecycle` / `Executor` / `Transfer` 인터페이스 3분할 | ISP 를 지키는 모양이지만 셋이 항상 같은 백엔드에서 1:1 로 붙는다. 나눠도 조합이 하나뿐이면 분할이 아니라 간접층이다 |
| artifact 저장을 로컬 디렉터리로 | 단순하지만 멀티 인스턴스에서 공유 스토리지가 전제가 된다. VFS 는 GridFS·S3 교체를 설정 문제로 만든다 |
| `SandboxRun` 에 `List<ArtifactEntry>` | 목록은 VFS 에서 직접 읽을 수 있고, Run 하나가 커지면 `RunStore` 의 메모리가 파일 수에 비례한다. 개수와 총 바이트만 남겼다 |
| TTL 을 백엔드 라벨에만 저장 | 조회가 매번 백엔드 왕복이 되고, 리퍼 주기(5초)마다 전체 목록을 긁는다 |
| TTL 을 스토어에만 저장 | 프로세스 재시작이 살아 있는 샌드박스의 만료 시각을 통째로 잃는다 (§4.3) |
| `RunState.CANCELED` | 취소를 걸 자리가 없다 — 명령은 백엔드 안에서 돌고, 중단하려면 `exec` 에 취소 채널이 필요하다. 상태만 먼저 만들면 아무도 그 상태로 보내지 않는다 |
| `ArtifactStore` 인터페이스 | VFS 가 이미 그 추상화다. 한 겹 더 얹으면 구현체가 VFS 하나뿐인 인터페이스가 생긴다 |
| `Map<String, ReentrantLock>` (refCount 없이) | §7 의 경합 — `unlock()` 후 제거가 남의 락을 지운다 |

---

## 14. 남은 것 + 하지 말 것

**남은 것**

- **분산 구현체 셋** — `RunStore` · `SandboxExpiryStore` · `SandboxLock`. 인터페이스는 이미
  갈라져 있고 Redis/JDBC 구현만 없다. 멀티 인스턴스 배포가 실제로 생길 때 붙인다
- **`CommandSpec.env`** — 모델이 명령별 환경 변수를 지정할 수 없다(§6.2). `CommandInput` 에는
  이미 있으므로 `@ToolParam` 컴포넌트 하나를 더하는 일이다. 다만 열면 검증(K8s `ENV_KEY_PATTERN`
  같은)을 도구 쪽에도 세워야 한다
- **샌드박스별 이미지** — 지금은 `SandboxConfig.defaultImage` 전역이다. 입력에 `image` 를 열면
  이미지 허용 목록이 함께 필요하다
- **`RestartSandboxTool` / `DeleteSandboxTool` 의 `GenericTool` 이행** — 이득이 크기에 비해 작아
  미뤄 둔 것이지 금지된 것은 아니다. 옮기면 `SandboxToolHelper` 의 오버로드 짝이 정리된다

**하지 말 것**

- **`AgentRuntime` 소멸과 함께 샌드박스 컴포넌트를 닫지 말 것.** 전부 application-scoped 다(§3.2).
  `RunManager` · `TarCreator` · `TarExtractor` 는 프로바이더가 만들지만 런타임보다 오래 산다
- **`OrcaSandboxToolProvider` 안에서 `ReaperService` 를 만들지 말 것.** 도구 등록과 만료 정리는
  독립적으로 켜고 끌 수 있어야 한다
- **주입받은 `RunStore`/`SandboxExpiryStore`/`SandboxLock` 을 닫지 말 것.** 프로바이더는 이들을
  소유하지 않는다 — 만든 쪽이 닫는다
- **`ensure()` 를 "조회 후 없으면 생성"으로 구현하지 말 것.** 멱등성은 충돌을 catch 해서 만든다(§4.1)
- **tar 상한을 `TarCreator`/`TarExtractor` 에 각각 적지 말 것.** `TarSecurityPolicy` 가 단일
  출처이며, 한쪽만 조이면 반대 방향이 그대로 뚫린다
- **네 도구를 `CONCURRENT_SAFE` 로 선언하지 말 것.** 전부 샌드박스 상태를 변조한다(§6.6)

---

## 부록. 참조 파일 지도

| 개념 | 파일 |
|------|------|
| 백엔드 계약 | [`backend/SandboxBackend.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/backend/SandboxBackend.java) |
| 실행 파라미터·결과 | [`backend/ExecParams.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/backend/ExecParams.java) · [`backend/ExecResult.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/backend/ExecResult.java) |
| TTL 저장소 | [`backend/SandboxExpiryStore.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/backend/SandboxExpiryStore.java) · [`backend/InMemorySandboxExpiryStore.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/backend/InMemorySandboxExpiryStore.java) |
| Run 모델 | [`model/SandboxRun.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/model/SandboxRun.java) · [`model/CommandInput.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/model/CommandInput.java) · [`model/CommandResult.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/model/CommandResult.java) |
| Run 상태 전이 | [`run/RunManager.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/run/RunManager.java) · [`run/RunStore.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/run/RunStore.java) · [`run/InMemoryRunStore.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/run/InMemoryRunStore.java) |
| 직렬화 락 | [`lock/SandboxLock.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/lock/SandboxLock.java) · [`lock/LocalSandboxLock.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/lock/LocalSandboxLock.java) |
| tar 정책과 양방향 | [`artifact/TarSecurityPolicy.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/artifact/TarSecurityPolicy.java) · [`artifact/TarCreator.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/artifact/TarCreator.java) · [`artifact/TarExtractor.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/artifact/TarExtractor.java) |
| 도구 등록 | [`tool/OrcaSandboxToolProvider.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/OrcaSandboxToolProvider.java) |
| 실행 도구 + 입력 | [`tool/RunSandboxTool.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/RunSandboxTool.java) · [`tool/RunSandboxInput.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/RunSandboxInput.java) |
| 전송 도구 + 입력 | [`tool/CopyToSandboxTool.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/CopyToSandboxTool.java) · [`tool/CopyToSandboxInput.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/CopyToSandboxInput.java) |
| 삭제·재시작 | [`tool/DeleteSandboxTool.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/DeleteSandboxTool.java) · [`tool/RestartSandboxTool.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/RestartSandboxTool.java) |
| 도구 공통 헬퍼 | [`tool/SandboxToolHelper.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/SandboxToolHelper.java) · [`tool/RunResultFormatter.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/tool/RunResultFormatter.java) |
| 만료 정리 | [`reaper/ReaperService.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/reaper/ReaperService.java) |
| 설정 | [`config/SandboxConfig.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/config/SandboxConfig.java) |
| identifier 검증 | [`util/IdentifierValidator.java`](../../modules/aimon-sandbox/src/main/java/at/aimon/sandbox/util/IdentifierValidator.java) |
| Docker 백엔드 | [`DockerSandboxBackend.java`](../../modules/aimon-sandbox-docker/src/main/java/at/aimon/sandbox/docker/DockerSandboxBackend.java) · [`DockerSandboxConfig.java`](../../modules/aimon-sandbox-docker/src/main/java/at/aimon/sandbox/docker/DockerSandboxConfig.java) |
| K8s 백엔드 | [`KubernetesSandboxBackend.java`](../../modules/aimon-sandbox-kubernetes/src/main/java/at/aimon/sandbox/kubernetes/KubernetesSandboxBackend.java) · [`KubernetesSandboxConfig.java`](../../modules/aimon-sandbox-kubernetes/src/main/java/at/aimon/sandbox/kubernetes/KubernetesSandboxConfig.java) |

---

## 관련 문서

- [`../agent-execution/artifact.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/design/agent-execution/artifact.md) — `ArtifactCollector` 와 artifact 경로
- [`../../features/tool/tool-development-guide.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/features/tool/tool-development-guide.md) — `AbstractTool` / `GenericTool` 선택 기준, `ConcurrencyBehavior`
- [`../../overview/scope-model.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) — application scope 와 소멸 책임
- [`../../project/solid-principles.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/project/solid-principles.md)
