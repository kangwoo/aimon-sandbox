# 워크스페이스 샌드박스 — 기존 도구가 OpenSandbox 위의 격리 환경을 투명하게 쓴다

> Status: **PROPOSED** — 구현 전 설계. 채택되면 [`sandbox.md`](sandbox.md) 가 설명하는 identifier 기반
> 설계(도구 4개 · `SandboxBackend` · Docker/K8s 백엔드)를 **대체한다.** 하위 호환은 목표가 아니다 —
> 옛 도구·타입·모듈을 남겨 두지 않고, 이행 경로 대신 대응표(§17)를 둔다.
>
> 입력: 공개하지 않은 두 초안 — *OpenSandbox 기반 Multi-Agent Sandbox Platform 설계* (v0.2),
> *AIMON Core + OpenSandbox 기반 Multi-Agent Sandbox 설계* (v0.3). 초안은 보존하지 않는다 — 채택한 것은 본문에,
> 버린 것은 이유와 함께 §19 에, 미룬 것은 §20 에 옮겼다. 본문의 "초안" 은 이 둘을 가리킨다. 두 초안의 방향(Workspace 1:N Sandbox,
> Hybrid 스토리지, LLM 에게 sandbox id 를 보이지 않음, OpenSandbox 를 인프라로 씀)은 유지했다. 그 밖에
> AIMON 이 라이브러리로 임베드된다는 사실과 코어의 기존 SPI 에 맞춰 줄이거나 바꾼 결정이 여럿이다. 그
> 차이는 §19 에 모았다.

---

## 1. 무엇을 푸는가

### 1.1 지금 설계가 막히는 자리

[`sandbox.md`](sandbox.md) 의 샌드박스는 **도구가 들고 다니는 별도 세계**다. 그래서 다음 문제가 생긴다.

| 문제 | 원인 |
|------|------|
| `Write` 로 고친 파일을 `RunSandbox` 가 못 본다 | 파일 도구는 VFS, 샌드박스는 컨테이너. 둘을 잇는 것은 `CopyToSandbox` 의 tar 복사뿐이다 |
| 모델이 인프라를 관리한다 | `identifier` · `ttl_seconds` · `lock_sandbox` 가 도구 인자다. 모델이 id 를 짓고, 수명을 정하고, 락을 켠다 |
| 셸 상태가 없다 | `exec` 1건마다 새 프로세스다. `cd` · `export` 가 다음 명령까지 이어지지 않는다 |
| 취소가 없다 | `exec` 에 취소 채널이 없어 `RunState.CANCELED` 도 없다(sandbox.md §13) |
| 멀티 에이전트 개념이 없다 | 여러 에이전트가 한 샌드박스를 나눠 쓰는 방법, 역할별로 다른 샌드박스를 두는 방법이 모두 "같은 identifier 를 쓰기로 약속한다"뿐이다 |
| 백엔드가 인프라를 직접 구현한다 | 파드 생성·exec·파일 전송·만료 라벨을 Docker 와 K8s 에 각각 따로 적었다. 네트워크 정책·자격 증명·pause 는 아예 없다 |
| 멀티 인스턴스가 이름뿐이다 | `RunStore` · `SandboxExpiryStore` · `SandboxLock` 모두 인터페이스는 있지만 구현은 로컬 하나뿐이다 |

### 1.2 목표

- **투명성** — 에이전트는 이미 가진 `Bash` · `Read` · `Write` · `Edit` · `Grep` 을 그대로 쓴다. 샌드박스를
  켜면 그 도구들이 격리 환경에서 돈다. 샌드박스 전용 실행 도구는 없다
- **한 파일 시스템** — 파일 도구와 셸은 **반드시 같은 파일 시스템**을 본다. `Write Foo.java` 다음의
  `./gradlew test` 는 정확히 그 파일을 컴파일한다
- **플랫폼이 고른다** — 어느 샌드박스에서 돌지는 바인딩 정책이 정한다. 모델은 sandbox id 를 보지도,
  지정하지도 못한다
- **1 워크스페이스 : N 샌드박스** — 기본은 워크스페이스 하나에 샌드박스 하나이고, 필요할 때만 늘린다.
  데이터 모델은 처음부터 N 이다
- **지속되는 셸** — 실행 주체마다 cwd·환경 변수가 유지되는 셸 세션을 준다
- **멀티 노드** — 어느 AIMON 노드에서든 같은 샌드박스에 다시 붙는다. JVM 메모리에는 영속 상태를 두지 않는다
- **인프라 위임** — 수명·격리·네트워크·자격 증명은 OpenSandbox 가 맡는다. 이 모듈은 AIMON 쪽 개념
  (워크스페이스·바인딩·수명 정책)만 소유한다

### 1.3 비목표

- **범용 원격 개발 환경이 아니다.** 사람이 붙는 IDE·포트 포워딩·브라우저 미리보기는 다루지 않는다
- **PTY/대화형 명령을 지원하지 않는다.** 에이전트의 명령은 입력을 기다리지 않아야 한다
  (`ShellFeature.INTERACTIVE` 를 광고하지 않는다)
- **공유 파일의 버전 관리 시스템이 아니다.** 경로 락과 리비전 DB 를 두지 않는다(§11.4, §19)
- **격리의 최종 방어선이 아니다.** 탈출 방어는 런타임(gVisor/Kata)과 OpenSandbox 가 맡고, 이 모듈은
  그 설정을 프로파일로 고를 뿐이다
- **Sandbox Gateway 같은 별도 서비스를 두지 않는다.** AIMON 은 애플리케이션에 임베드되는 라이브러리다.
  인가·라우팅은 프로세스 안에서 바인딩 정책과 권한 훅이 맡는다(§19)

---

## 2. 결정 요약

1. **샌드박스는 도구가 아니라 실행 환경이다.** 코어의 파일·셸 도구가 실행마다 `ExecutionEnvironment` 를
   `ToolContext` 에서 꺼내 쓰도록 바꾸고(§7), 샌드박스 모듈은 그 환경을 끼워 넣는다
2. **식별은 `(워크스페이스, 슬롯)` 이다.** 슬롯은 워크스페이스 안의 이름(`primary`, `review`, `exp-a`)이고,
   무엇을 실행할지는 슬롯에 붙은 **프로파일**이 정한다. 역할 enum 은 없다(§5.2)
3. **워크스페이스는 한 애그리게이트로 영속한다.** 워크스페이스·샌드박스·셸 세션 참조를 한 레코드에 담고
   버전 CAS 로 갱신한다. 저장소가 셋으로 갈라져 서로 어긋나는 일이 없다(§5.3)
4. **프로비저닝은 게으르다.** 세션을 열 때가 아니라 첫 도구 호출 때 샌드박스를 만든다
5. **`/shared` 는 워크스페이스 볼륨이다.** 프로파일이 허용한 샌드박스에 마운트되므로 파일 도구와 셸이 같은
   공유 영역을 본다. `/workspace` 는 샌드박스 전용이다. `/shared` 를 쓰기로 공유하는 슬롯들은 **한 신뢰
   도메인**이다(§11.3)
6. **코드 협업은 git 이다.** `/shared` 의 bare 저장소를 통해 샌드박스끼리 커밋을 주고받는다. 네트워크가
   막힌 샌드박스도 받을 수 있다(§11.3)
7. **백엔드는 OpenSandbox 하나다.** 자체 Docker/K8s 백엔드는 없앤다. OpenSandbox 가 Docker 런타임과
   K8s 런타임을 모두 가지므로 로컬 개발과 운영이 같은 코드 경로를 탄다(§6.4)
8. **프로바이더 타임아웃이 최후 방어선이다.** AIMON 노드가 전부 죽어도 샌드박스가 영원히 남지 않도록
   프로바이더 쪽 idle 타임아웃을 걸고, 활동이 있으면 갱신한다(§10.3)
9. **실패는 닫힌 쪽으로 간다.** 샌드박스 모드에서 환경을 얻지 못하면 도구가 에러를 돌려준다. 호스트 셸로
   조용히 되돌아가지 않는다(§12.1)
10. **멀티 노드는 영속 저장소가 들어와야 성립한다.** 기본 `InMemory` 저장소는 단일 노드 전용이고, 분산
    저장소는 구현 순서의 독립 단계다(§5.3, §18)

---

## 3. 용어와 수명

### 3.1 용어

| 용어 | 뜻 |
|------|-----|
| **SandboxWorkspace** | 여러 실행이 한 작업을 위해 공유하는 논리적 작업 공간. 샌드박스들과 공유 볼륨을 소유한다. AIMON 의 에이전트 워크스페이스(`agents/`, `skills/` 디렉터리)와 다른 개념이라 접두어를 붙였다 |
| **slot** | 워크스페이스 안에서 샌드박스를 가리키는 이름. `^[a-z][a-z0-9-]{0,30}$`. 기본 슬롯은 `primary` |
| **SandboxProfile** | 샌드박스를 어떻게 만들지 정한 운영자 설정 — 이미지·자원·런타임 클래스·egress·자격 증명·idle 정책 |
| **generation** | 슬롯의 샌드박스가 새로 만들어질 때마다 1씩 늘어나는 정수. 이전 셸 세션과 파일이 사라졌음을 알리는 신호다 |
| **SandboxBinding** | 한 실행이 쓸 `(workspaceId, slot, shellKey, root)`. 바인딩 정책이 만든다 |
| **shellKey** | 지속 셸 세션을 가리키는 키. 같은 키로 들어온 명령들은 cwd·환경 변수를 공유한다 |
| **provider** | 실제 샌드박스를 만드는 인프라. 이 설계에서는 OpenSandbox |

### 3.2 수명 배치

[`scope-model.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) 의 4단계에
다음처럼 놓인다. 수명은 이름이 아니라 **키와 저장 위치**로 판단한다.

| 대상 | 수명 | 키 / 저장 위치 | 닫는 곳 |
|------|------|---------------|--------|
| `SandboxProvider` · `SandboxWorkspaceStore` · `SandboxWorkspaceManager` · `SandboxProfileRegistry` · `SandboxBindingPolicy` · `SandboxJanitor` · `SandboxExecutionEnvironmentProvider` | Application | 어셈블리 싱글턴 | 앱 shutdown |
| 오케스트레이터 도구 | Agent | `ToolRegistry` | 상태 없음 |
| `SandboxWorkspace` 레코드, 원격 샌드박스, 공유 볼륨 | **Workspace** (영속) | `SandboxWorkspaceId` / `SandboxWorkspaceStore` | 명시적 close 또는 idle 만료(§10) |
| `SandboxBinding` · `SandboxExecutionEnvironment` | Execution | `ToolContext` | 실행과 함께 버린다. 원격 자원을 쥐지 않는다 — 코어 계약상 환경은 닫을 것이 없는 뷰다 |
| 프로바이더 연결 캐시 · shellKey 직렬화 락 · `exec:` 셸 세션 id | 노드 로컬 | `SandboxConnectionCache` | 앱 shutdown. `exec:` 셸은 `execShellIdle` 동안 안 쓰이면 닫는다(§9) |

Workspace 수명은 4단계에 새로 끼우는 층이 아니다. **Session 과 같은 층의 영속 애그리게이트이되 키가
다르다** — 한 워크스페이스는 여러 세션과 세션 없는 실행(서브에이전트 포크, 스케줄 루틴)에 걸칠 수 있다.
그래서 다음이 성립한다.

- `LiveSession.close()` 는 샌드박스를 건드리지 않는다. 같은 워크스페이스를 다른 노드의 다른 세션이 쓰고 있을 수 있다
- `AgentRuntime.close()` 도 건드리지 않는다. 런타임은 여러 워크스페이스의 실행을 받는다
- 샌드박스를 끝내는 경로는 워크스페이스 close, idle 정책, 오케스트레이터 도구의 `SandboxStop` 세 가지뿐이다

---

## 4. 아키텍처

### 4.1 모듈

```
aimon-core (repo: aimon-core)
  at.aimon.core.environment        ExecutionEnvironment (new neutral SPI, §7)
  tools.file / tools.bash          resolve environment from ToolContext per execution
        ▲
        │ compileOnly/api
┌───────┴───────────────────────────────────────────────────────────────┐
│ aimon-sandbox                      (repo: aimon-sandbox)               │
│   workspace/   SandboxWorkspace · SandboxWorkspaceStore(I) · InMemory  │
│                SandboxWorkspaceManager · SandboxJanitor                │
│   binding/     SandboxBindingPolicy(I) · DefaultSandboxBindingPolicy   │
│   provider/    SandboxProvider(I) · SandboxConnection(I) · specs       │
│   profile/     SandboxProfile · SandboxProfileRegistry                 │
│   environment/ SandboxExecutionEnvironment · SandboxFileSystem         │
│                SandboxShell · SandboxExecutionEnvironmentProvider      │
│   tool/        OrcaSandboxToolProvider (orchestrator tools only)       │
└───────────────────────────────────────────────────────────────────────┘
        ▲ api                                   ▲ api
┌───────┴─────────────────────────┐   ┌─────────┴──────────────────────┐
│ aimon-sandbox-opensandbox       │   │ aimon-sandbox-testkit          │
│   OpenSandboxProvider           │   │   SandboxProviderContract      │
│   OpenSandboxProviderConfig     │   │   LocalProcessSandboxProvider  │
│   -> com.alibaba.opensandbox    │   │   (tests only, not production) │
└─────────────────────────────────┘   └────────────────────────────────┘
```

의존은 한 방향이다. 코어는 샌드박스를 모르고, `aimon-sandbox` 는 OpenSandbox SDK 를 모른다. OpenSandbox
SDK 타입이 `aimon-sandbox-opensandbox` 밖으로 새어 나가지 않는다 — 공개 메서드의 인자·반환에 SDK 타입이
나오면 안 된다.

### 4.2 호출 경로

```
LLM tool call: Bash {"command": "./gradlew test"}
  │
  ▼
BashTool.execute(input, ctx)
  │  env = ctx.get(EXECUTION_ENVIRONMENT)          <- resolved by SandboxExecutionEnvironmentProvider
  ▼
SandboxShell.execute(cmd, options)
  │  binding = (ws-42, primary, shellKey=session:S1, root=/workspace/repo)
  ▼
SandboxWorkspaceManager.connect(binding)           <- lazy: provision / resume if needed
  │  CAS on SandboxWorkspace record
  ▼
SandboxConnection.run(shellKey, ExecSpec)          <- node-local connection cache
  ▼
OpenSandboxProvider  ──>  OpenSandbox API  ──>  execd in sandbox  ──>  bash
```

---

## 5. 도메인 모델

모두 불변 클래스 + 빌더다(코어 컨벤션). 상태 변경은 `with*()` 가 새 인스턴스를 돌려주고, 원자적 교체는
저장소의 CAS 가 책임진다.

### 5.1 `SandboxWorkspace`

| 필드 | 뜻 |
|------|-----|
| `id` | `SandboxWorkspaceId` — 불투명 문자열. 기본 정책은 루트 세션에서 결정론적으로 만든다(§8.2) |
| `owner` | `Principal` — 테넌트 경계. 바인딩 시 실행 주체와 대조한다. 레코드를 처음 만든 실행의 주체로 정해진다(§8.3) |
| `state` | `OPEN` · `CLOSING` · `CLOSED` |
| `sharedVolume` | `VolumeRef`? — 공유 볼륨이 만들어졌으면 그 참조 |
| `slots` | `Map<String, SandboxSlot>` |
| `quota` | `WorkspaceQuota` — 생성 시점의 설정을 복사해 둔다(설정이 바뀌어도 진행 중인 워크스페이스는 흔들리지 않는다) |
| `version` | `long` — CAS 용 |
| `createdAt` · `lastActivityAt` | |

`SandboxWorkspace` 는 sandbox id 를 "하나" 갖지 않는다. 초안 두 개가 모두 강조한 점이고 그대로 따른다.

### 5.2 `SandboxSlot`

| 필드 | 뜻 |
|------|-----|
| `name` | 슬롯 이름 |
| `profile` | 프로파일 이름. 슬롯이 처음 만들어질 때 고정된다 |
| `state` | `PROVISIONING` · `RUNNING` · `PAUSED` · `TERMINATED` · `FAILED` |
| `generation` | 새로 프로비저닝할 때마다 +1 |
| `providerRef` | `ProviderSandboxRef`? — `(provider, providerSandboxId)`. OpenSandbox 의 id 다 |
| `seeded` | `boolean` — 이 generation 의 seed(§11.3)가 끝났는가. RUNNING 이어도 false 면 다음 `connect` 가 seed 를 다시 돈다 |
| `shells` | `Map<String, String>` — `session:` shellKey → 프로바이더 셸 세션 id. `exec:` 키는 담지 않는다(§9) |
| `lastActivityAt` · `failure`? | |

**역할 enum(`PRIMARY`/`DEVELOPMENT`/`REVIEW`/`TEST`…)을 두지 않는다.** 초안의 역할은 두 가지 일을 했다 —
라우팅 키, 그리고 보안 정책 선택이다. 전자는 슬롯 이름이, 후자는 프로파일이 더 잘한다. enum 은 특정
개발 프로세스(개발→리뷰→테스트)를 SPI 에 굳힌다. 실험 슬롯 셋(`exp-a`, `exp-b`, `exp-c`)이나 브라우저·GPU
슬롯을 만들 때마다 enum 을 고치거나 `CUSTOM` 으로 뭉개야 한다. 슬롯 + 프로파일이면 둘 다 설정 문제다.

상태 전이:

```
            ensure()                  idle(pauseAfter)
(absent) ──────────> PROVISIONING ──> RUNNING <──────────> PAUSED
                         │   ▲          │        resume()     │
                    fail │   │ retry    │ stop / idle(terminateAfter) / lost
                         ▼   │          ▼                     │
                       FAILED ───────> TERMINATED <───────────┘
                                          │ ensure() -> generation+1
                                          └──────────> PROVISIONING
```

초안의 `REQUESTED` · `READY` · `ACTIVE` 구분은 없앴다. `READY` 와 `ACTIVE` 의 차이는 "지금 명령이 돌고
있는가"인데, 그것은 `lastActivityAt` 이 이미 말하고, 상태로 올리면 명령마다 저장소 쓰기가 생긴다.
`TERMINATED` 는 끝이 아니다 — 같은 슬롯을 다시 쓰면 generation 을 올려 새로 만든다.

### 5.3 저장소 — 애그리게이트 하나

```java
public interface SandboxWorkspaceStore {
    Optional<SandboxWorkspace> find(SandboxWorkspaceId id);
    SandboxWorkspace createIfAbsent(SandboxWorkspace initial);          // returns the stored one
    SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion,
                            SandboxWorkspace next);                     // throws StaleVersionException
    List<SandboxWorkspace> scan(WorkspaceScan scan);                    // janitor: idle / state filters, paged
}
```

초안은 `WorkspaceRepository` · `SandboxInstanceRepository` · `AgentSandboxSessionRepository` 셋을 제안했다.
셋으로 나누면 "슬롯을 TERMINATED 로 바꾸면서 그 슬롯의 셸 세션을 지운다" 같은 한 가지 변경이 세 저장소
쓰기가 되고, 중간에 죽으면 어긋난다. 워크스페이스 하나의 크기는 작다(슬롯 수 개, 셸 세션 수십 개) — 통째로
CAS 하는 비용이 트랜잭션 조율보다 싸다.

기본 구현은 `InMemorySandboxWorkspaceStore` 이고 **단일 노드 전용**이다. 멀티 노드 배포에는 영속 구현
(`aimon-sandbox-store-jdbc`)이 필요하며, 이것은 구현 순서의 독립 단계다(§18). 그 단계가 끝나기 전까지
§1.2 의 "멀티 노드" 목표는 달성되지 않은 것으로 본다. 옛 설계처럼 인터페이스만 있고 구현이 없는 상태를
"멀티 인스턴스 대비"라고 부르지 않는다.

`InMemory` 저장소로 도는 노드가 재시작하면 레코드가 모두 사라진다. 그 순간 프로바이더에 남은 샌드박스는
조정 표(§10.4)에서 ORPHAN 이 되어 `orphanGrace` 뒤 회수된다. 단일 노드 배포에서는 이것이 의도된 동작이다 —
재시작을 넘어 샌드박스를 이어 쓰려면 영속 저장소를 쓴다.

`lastActivityAt` 갱신은 **스로틀**한다. 마지막 기록보다 `activityWriteInterval`(기본 30초) 이상 지났을
때만 CAS 를 시도하고, 충돌하면 버린다. 명령마다 쓰면 저장소가 명령 처리량을 따라가야 한다.

활동은 명령의 **시작**만이 아니다. 명령(백그라운드 포함)이 도는 동안 그 명령을 가진 노드가
`activityWriteInterval` 마다 `lastActivityAt` 기록과 프로바이더 `renew` 를 반복한다(heartbeat). 그래야
20분짜리 빌드가 도는 동안 도구 호출이 없어도 janitor 가 `pauseAfter` 로 멈추지 않는다. 카운터를 레코드에
두지 않는 이유는, 노드가 죽으면 카운터가 내려가지 않기 때문이다 — heartbeat 는 노드와 함께 멈추고, 그러면
idle 정책이 정상적으로 이어받는다.

---

## 6. 프로바이더 SPI

### 6.1 계약

```java
public interface SandboxProvider extends AutoCloseable {
    ProviderCapabilities capabilities();

    ProviderSandboxRef create(CreateSpec spec);            // idempotent on spec.key()
    Optional<ProviderSandboxStatus> status(ProviderSandboxRef ref);
    void pause(ProviderSandboxRef ref);                     // requires PAUSE_RESUME
    void resume(ProviderSandboxRef ref);
    void renew(ProviderSandboxRef ref, Duration idleTimeout);
    void destroy(ProviderSandboxRef ref);                   // idempotent: absent is success
    List<ProviderSandboxRef> list(Map<String, String> labels);

    VolumeRef createVolume(VolumeSpec spec);                // requires SHARED_VOLUME, idempotent
    void deleteVolume(VolumeRef ref);

    SandboxConnection connect(ProviderSandboxRef ref);
}

public interface SandboxConnection extends AutoCloseable {
    String openShell(ShellSpec spec);                       // returns provider shell session id
    RunningCommand run(String shellSessionId, ExecSpec spec, OutputSink sink);  // null session = one-shot
    SandboxFiles files();
}

public interface RunningCommand {
    ExecOutcome await(Duration timeout) throws InterruptedException;  // exit code, truncation flags
    void kill();                                                      // SIGTERM, then SIGKILL after grace
}
```

`SandboxFiles` 는 `read(path, range)` · `write(path, InputStream, length, mode)` · `stat` · `list(dir,
recursive, limit)` · `delete` · `move` 를 갖는다. 전부 샌드박스 안 **절대 경로**를 받는다.

### 6.2 옛 `SandboxBackend` 와 다른 점

| 옛 계약 | 새 계약 | 이유 |
|---------|--------|------|
| `ensure(identifier, ttl)` — 조회+생성+TTL 갱신을 한 메서드에 | `create` (멱등) · `status` · `renew` 로 분리 | "있으면 재사용"은 워크스페이스 레코드가 판단한다. 프로바이더는 레코드가 준 키로 멱등하게 만들기만 한다 |
| `exec` 블로킹, 취소 없음 | `run` 이 `RunningCommand` 를 돌려주고 `kill()` 이 있다 | `VirtualShell` 계약(데드라인·인터럽트 시 프로세스를 죽인다)을 원격에서 지키려면 취소 채널이 필요하다 |
| 출력은 끝나고 한 번에 | `OutputSink` 로 흘려보낸다 | 지금은 `SandboxShell` 이 모아서 돌려주지만, 스트리밍 도구 출력이 생길 때 SPI 를 고치지 않아도 된다 |
| `copyArtifacts` / `copyToSandbox` (tar) | `files()` | 파일 도구가 샌드박스 파일 시스템을 직접 보므로 tar 왕복이 필요 없다 |
| `reapExpired()` | 없음 | 만료 판단은 워크스페이스 레코드와 프로바이더 타임아웃의 일이다(§10) |
| `count()` | `list(labels)` | 조정(reconciliation)에는 개수가 아니라 목록이 필요하다 |

### 6.3 멱등 생성

`CreateSpec.key` 는 `"{deployment}/{workspaceId}/{slot}/{generation}"` 이다. 프로바이더는 이 키를 라벨
(`aimon.at/sandbox-key`)로 붙이고, 같은 키로 이미 만들어진 샌드박스가 있으면 새로 만들지 않고 그것을
돌려준다. 두 노드가 같은 슬롯을 동시에 프로비저닝해도 결과는 하나다. generation 이 키에 들어가므로 종료된
옛 샌드박스를 잘못 되살리는 일도 없다.

공통 라벨: `aimon.at/managed=true` · `aimon.at/deployment` · `aimon.at/workspace` · `aimon.at/slot` ·
`aimon.at/generation` · `aimon.at/owner`(테넌트). 조정 로직(§10.4)이 이 라벨로 고아를 찾는다.

`deployment` 는 한 워크스페이스 저장소를 공유하는 AIMON 노드 집합의 이름이다(설정 필수, 기본값 없음).
OpenSandbox 서버 하나를 여러 애플리케이션이나 여러 개발자 머신이 같이 쓰면 `managed=true` 만으로는
남의 샌드박스와 내 고아를 구분할 수 없다. janitor 는 **자기 deployment 라벨이 붙은 샌드박스만** 조정한다.
기본값을 두지 않는 이유는, 두 배포가 같은 기본값을 쓰면 라벨이 없는 것과 같기 때문이다.

### 6.4 capability 와 OpenSandbox 대응

| capability | 뜻 | OpenSandbox 대응 (구현 시 확인) |
|-----------|-----|-------------------------------|
| `EXEC` | 1회성 명령 | commands API |
| `SHELL_SESSION` | cwd·env 가 유지되는 셸 | persistent session |
| `FILES` | 파일 API | files API (read/write/search/delete) |
| `PAUSE_RESUME` | 일시 정지 | pause/resume |
| `IDLE_TIMEOUT` | 프로바이더 측 만료 + 갱신 | timeout/renew |
| `SHARED_VOLUME` | 여러 샌드박스에 RWX 볼륨 마운트, 샌드박스별 읽기 전용 마운트 | volume (K8s 런타임은 RWX PVC 필요) |
| `EGRESS_POLICY` | 목적지 허용 목록 | network policy |
| `CREDENTIAL_INJECTION` | 샌드박스에 비밀을 노출하지 않고 egress 에서 주입 | credential vault |
| `SNAPSHOT` · `FORK` | 스냅숏·복제 | snapshot (fork 는 snapshot 위에 조립) |

`SandboxWorkspaceManager` 는 프로파일이 요구하는 capability 를 프로바이더가 광고하지 않으면 **시작 시점에**
거부한다. egress 를 막으라는 프로파일이 egress 정책 없는 프로바이더 위에서 조용히 열린 채로 도는 일은
없어야 한다.

**왜 백엔드를 OpenSandbox 하나로 줄이는가.** 옛 Docker/K8s 백엔드 두 개는 같은 수명 관리를 두 번 구현했고
(이름 충돌 catch, 라벨/애노테이션, Ready 대기), 네트워크 정책·자격 증명·pause 는 둘 다 없었다. 그것을
채우는 일은 OpenSandbox 가 이미 한 일을 다시 하는 것이다. OpenSandbox 는 Docker 런타임으로 로컬에서도
돌기 때문에, 로컬 개발용으로 자체 Docker 백엔드를 남길 이유도 없다. SPI 는 남긴다 — 테스트용 로컬
프로바이더(§16)와, 언젠가 생길 다른 인프라(e.g. `kubernetes-sigs/agent-sandbox`)를 위해서다.

SDK 의 `SandboxPool`(warm pool)은 SPI 에 올리지 않는다. 풀은 `create` 의 구현 세부이고, 실험적 API 에 공개
계약이 묶이면 SDK 가 바뀔 때마다 SPI 가 흔들린다. 풀에서 꺼낸 샌드박스에 §6.3 의 라벨을 붙일 수 없다면
그 샌드박스의 소속은 레코드만 기억하게 된다. 그때는 풀 샌드박스에 `aimon.at/pool=true` 를 붙여 조정
대상에서 빼야 한다.

---

## 7. 코어 통합 — `ExecutionEnvironmentProvider` 를 구현한다

코어 쪽 변경은 aimon-core 의
[실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)
가 정본이다. **그 변경이 먼저 들어가야 한다.** 요지는 다음과 같다.

- 코어의 파일 도구와 `Bash` 는 생성자로 파일 시스템·셸을 받지 않고, 실행마다
  `ToolContextKeys.EXECUTION_ENVIRONMENT` 에서 `ExecutionEnvironment` 를 꺼낸다
- 실행기는 실행마다 `ExecutionEnvironmentProvider.resolve(EnvironmentRequest)` 를 한 번 불러 그 키에 넣는다.
  이 키는 한 번만 쓸 수 있으므로 enricher 가 덮어쓸 수 없다. 실패하면 호출마다 에러를 내는
  `UnavailableExecutionEnvironment` 가 들어간다
- 에이전트·스킬 정의, 태스크 출력, artifact 보관은 `controlFileSystem` 으로 분리되어 파일 도구에서 보이지 않는다

이 모듈은 `SandboxExecutionEnvironmentProvider` 로 그 SPI 를 구현한다. 코어 문서 §13 의 계약을 이렇게 지킨다.

| 코어 SPI | 샌드박스 구현 |
|---------|-------------|
| `resolve(request)` | 바인딩 정책(§8)으로 `SandboxBinding` 을 만들고 `SandboxExecutionEnvironment` 를 돌려준다. **원격 자원은 만들지 않는다** — 첫 파일·셸 호출이 `connect()` 로 프로비저닝한다(§10.1) |
| `fileSystem()` | `SandboxFileSystem` — 프로바이더 `files()` 위의 VFS. 상대 경로는 바인딩의 `root` 기준 |
| `shell()` | `SandboxShell` — shellKey 의 지속 셸 세션(§9) |
| `descriptor()` | 이미지의 실제 platform·OS, `workingDirectory = root`, notes 에 "isolated sandbox" 와 egress 요약 |
| `contentSearch()` | 샌드박스 안에서 `rg --json` 한 번. 이미지 계약(§13.3)이 ripgrep 을 요구하는 이유다 |
| `durable()` | `false` — 코어가 artifact 를 제어 저장소로 복사한다(§11.5) |
| `stage(resource)` | `/workspace/.aimon/skills/{name}/{contentKey}/` 로 materialize. 샌드박스 안의 `.staged` 마커가 있을 때만 생략한다(코어 §4.4, 이 문서 §11.1) |
| `isolate(branchKey)` | `git worktree add /workspace/.worktrees/{branchKey}` 후 `root` 만 바꾼 환경(§11.2) |
| `parent` 가 있는 요청(포크) | 부모와 같은 워크스페이스·슬롯, shellKey 만 `exec:{executionId}` |
| `ShellCommandResult.notices()` | 셸 세션 재생성, generation 변경, 샌드박스 소실 후 재생성을 알린다 |

코어의 파일 stamp 검사(읽은 뒤 바뀐 파일에 쓰기 거부)는 샌드박스에서 특히 중요하다. 같은 슬롯을 여러 실행이
공유할 때 다른 실행이 파일 도구로 바꾼 경우와 **셸이 바꾼 경우를 모두 잡는다.** 실제 파일 상태와 비교하기
때문이다. 초안의 `expectedRevision` 은 파일 도구 경로만 추적하고 셸 경로는 놓친다(초안 v0.3 이 스스로
인정한 한계다). 프로바이더 `files().stat()` 은 그래서 mtime 을
밀리초 이상 해상도로 주거나 etag 를 줘야 한다. `/shared` 가 NFS 계열 RWX 볼륨이면 속성 캐시 때문에 mtime 이
늦게 보일 수 있으므로, `SandboxFileSystem` 은 `/shared` 아래 경로에 대해 **항상 etag(내용 해시)** 를 준다.
stamp 검사는 확인과 쓰기 사이에 틈이 있는 최선 노력 장치다 — 원자적 비교-후-쓰기가 아니다.

---

## 8. 바인딩 — 어느 실행이 어느 샌드박스를 쓰는가

### 8.1 계약

```java
public interface SandboxBindingPolicy {
    SandboxBinding bind(BindingContext ctx);
}
```

`BindingContext` 는 제공자가 코어의 `EnvironmentRequest` 에서 옮겨 담는다: `Agent`(이름·메타데이터) ·
`AgentRuntimeId` · `SessionId`? · `ExecutionId` · `invokingSessionId`? · `Principal` · 워크플로 브랜치 키?.

바인딩을 만드는 것은 **이 정책뿐**이다. 도구 인자는 바인딩에 들어가지 않는다. 그래서 프롬프트 인젝션으로
모델이 다른 워크스페이스의 id 를 불러도 닿을 경로가 없다.

### 8.2 기본 정책

**`parent` 가 있는 요청(포크)은 정책을 타지 않는다.** 부모 환경의 바인딩에서 workspaceId · slot · root 를
그대로 물려받고 shellKey 만 `exec:{executionId}` 로 바꾼다. 포크가 몇 단계로 중첩되어도, 부모가 세션 없는
실행(스케줄 루틴)이어도 같은 워크스페이스에 머문다. `invokingSessionId` 로 워크스페이스를 다시 계산하면
세션 없는 부모의 포크가 자기 `executionId` 로 새 워크스페이스를 만들게 된다. 아래 표는 `parent` 가 없는
요청에만 적용한다. 에이전트 정의가 포크에 다른 `sandbox.slot` 을 지정한 경우에만 워크스페이스는 물려받고
슬롯을 바꾼다.

| 항목 | 규칙 |
|------|-----|
| workspaceId | 세션에서 결정론적으로 — `ws:{sessionId}`. 루트가 아닌 실행이 `parent` 없이 오면 `invokingSessionId` 를 쓴다. 세션이 없는 실행(스케줄 루틴)은 `ws:{executionId}` |
| slot | 에이전트 메타데이터 `sandbox.slot` 이 있으면 그것, 없으면 `primary` |
| profile | 슬롯을 처음 만들 때만 쓴다. 에이전트 메타데이터 `sandbox.profile`, 없으면 설정의 기본 프로파일 |
| shellKey | 메인 턴: `session:{sessionId}` — 턴을 넘어 cwd 가 유지된다. 서브에이전트·스킬 포크: `exec:{executionId}` — 부모 셸을 오염시키지 않는다 |
| root | `/workspace/repo`. 워크플로 격리 브랜치는 `/workspace/.worktrees/{branchKey}` |

workspaceId 를 결정론적으로 만들기 때문에 별도의 세션→워크스페이스 매핑 저장소가 필요 없다. 세션이 다른
노드로 옮겨가도 같은 id 가 다시 계산된다. 여러 세션에 걸치는 워크스페이스(티켓 하나에 세션 여럿)가
필요한 애플리케이션은 자기 도메인에서 id 를 찾는 정책을 주입한다. 그 매핑은 애플리케이션의 데이터다.

### 8.3 테넌트 검사

`SandboxWorkspaceManager.connect()` 는 워크스페이스의 `owner` 와 바인딩 컨텍스트의 `Principal` 이 같은
테넌트인지 확인한다. 정책이 잘못 구현되어 다른 테넌트의 워크스페이스 id 를 돌려줘도 여기서 막힌다.
정책을 믿되 경계는 두 번 긋는다.

`owner` 는 레코드를 처음 만든(`createIfAbsent`) 실행의 주체로 정해지고 바뀌지 않는다. 코어에서 `Principal` 은
선택값이므로 없는 경우를 정해 둔다 — `Principal` 이 없는 실행은 **`anonymous` 테넌트**로 취급하고, 설정
`require-principal: true` 이면 바인딩 단계에서 거부한다. 멀티 테넌트 배포는 이 설정을 켜야 한다. 끄고 쓰면
principal 없는 두 실행이 결정론적 id 만 같으면 같은 워크스페이스에 닿는다.

### 8.4 한 샌드박스를 여러 에이전트가 쓸 때

기본값은 **공유 샌드박스**다. 오케스트레이터·서브에이전트가 모두 `primary` 를 쓰고 셸 세션만 다르다.
파일 시스템은 공유되고 셸 상태는 격리된다. 서로의 파일을 덮어쓰는 문제는 §7 의 stamp 검사가 잡고, 동시에
코드를 바꿔야 하는 병렬 작업은 worktree(§11.2)로 가른다.

슬롯을 가르는 것은 다음 경우다 — 의존성 충돌, 동시 빌드, 프로파일(네트워크·자격 증명·런타임 클래스)이
달라야 할 때, 신뢰 도메인이 다를 때, 여러 해법을 병렬로 탐색할 때. 신뢰 도메인이 다르면 **반드시** 가른다.
같은 샌드박스 안의 모든 실행은 같은 신뢰 도메인이다.

**슬롯을 가르는 것만으로 신뢰 도메인이 갈리지는 않는다.** `/shared` 를 쓰기로 마운트한 슬롯들은 bare 저장소의
훅·ref 와 공유 문서를 통해 서로의 실행에 영향을 준다(§11.3). 신뢰 도메인을 가르려면 슬롯을 가르고, 신뢰가 낮은
쪽의 프로파일을 `sharedAccess: ro` 또는 `none` 으로 둔다.

### 8.5 오케스트레이터 도구

`OrcaSandboxToolProvider` 는 명시적으로 허용된 에이전트에게만 다음을 등록한다. 모두 **호출자의
워크스페이스 안에서만** 슬롯 이름으로 동작한다.

| 도구 | 입력 | 동작 |
|------|-----|------|
| `SandboxList` | — | 슬롯·프로파일·상태·generation |
| `SandboxStart` | `slot`, `profile`(허용 목록 중) | 슬롯 생성 + 프로비저닝. 쿼터 검사 |
| `SandboxStop` | `slot`, `mode`(`pause`\|`terminate`) | |

`SandboxRestart` 는 두지 않는다 — `terminate` 후 다음 사용이 generation 을 올려 새로 만든다. `SandboxFork`
는 `SNAPSHOT` capability 가 갖춰지면 추가한다(§18).

다른 에이전트를 새 슬롯에 붙이는 방법은 에이전트 정의의 `sandbox.slot` 과 워크플로 스크립트의 슬롯
지정이다. 모델이 `Task` 인자로 임의 슬롯을 넘기는 경로는 열어 두지 않았다(§20).

---

## 9. 셸 세션

- `SandboxShell` 은 shellKey 의 프로바이더 셸 세션을 쓴다. 레코드에 id 가 없거나 프로바이더가 그 세션을
  모르면 새로 열고, `session:` 키면 CAS 로 레코드에 기록한 뒤 notice 를 붙인다(§7)
- **같은 shellKey 의 명령은 직렬화한다.** 지속 셸은 한 번에 명령 하나다. 노드 로컬 락으로 충분하다 —
  shellKey 는 세션이나 실행에 묶이고, 세션은 `SessionLease` 로 한 노드에만 있다
- `ExecutionOptions.workingDirectory` 가 주어지면 그 명령에만 적용하고 세션의 cwd 는 바꾸지 않는다
  (`(cd X && cmd)` 로 감싼다). `environment` 도 같다. 세션 상태를 바꾸는 것은 모델이 직접 친 `cd` 와 `export` 뿐이다
- 데드라인이나 스레드 인터럽트가 오면 `RunningCommand.kill()` 을 부른다. 기다리던 쪽만 포기하고 명령은 계속
  도는 상태를 만들지 않는다 — `tools.bash` 패키지 문서가 금지한 바로 그 동작이다
- `kill()` 은 **명령의 프로세스 그룹만** 끝내고 셸 세션은 살린다. 프로바이더가 세션 안의 명령만 골라 죽이지
  못하면 세션째 닫고, 다음 명령이 새 세션을 열며 notice 를 붙인다(cwd·env 소실을 알린다). 어느 쪽인지는
  §6.4 의 확인 항목이다
- **백그라운드 명령은 지속 셸에서 돌리지 않는다.** 지속 셸은 한 번에 명령 하나라서, 백그라운드 명령이 세션을
  쥐면 그 shellKey 의 모든 다음 명령이 기다린다. 코어가 `ExecutionOptions.background` 를 켜서 넘긴 명령
  (코어 §5.3 — `Bash(run_in_background=true)` 가 켠다)은
  one-shot(`run(null, …)`)으로 돌리되, 시작 시점의 세션 cwd 를 `ExecSpec.workingDirectory` 로 넘겨 모델이 기대한
  디렉터리에서 돈다. 도는 동안은 heartbeat 가 활동을 기록한다(§5.3)
- `exec:` shellKey 의 셸 세션 id 는 **레코드에 쓰지 않고** 노드 로컬 캐시에만 둔다. executionId 는 노드 로컬이라
  포크가 노드를 옮겨 다시 붙을 일이 없고, 레코드에 쓰면 포크마다 항목이 쌓이며 병렬 포크가 같은 레코드에 CAS
  경합을 일으킨다. 코어 계약상 환경은 실행 끝을 알리지 않으므로, 캐시는 `execShellIdle`(기본 10분) 동안 안
  쓰인 세션을 프로바이더에서 닫고 지운다. 샌드박스가 끝나면 남은 세션도 같이 사라진다
- `maxCaptureBytes` 는 샌드박스 쪽에서 자른다. 수십 MB 로그를 JVM 까지 끌고 와서 자르지 않는다
- pause→resume 이나 generation 변경 뒤의 셸 세션 id 는 **유효하다고 가정하지 않는다.** 첫 사용에서
  확인하고 없으면 새로 연다

`session:` 셸 세션 id 를 레코드에 두는 이유는 노드 이동이다. 세션이 다른 노드로 옮겨가도 같은 프로바이더 셸 세션에
다시 붙어 cwd 가 이어진다. 프로바이더가 그 세션을 이미 버렸다면 새로 열 뿐이다. 셸 세션은 최선 노력
(best-effort) 상태다 — 잃어도 정확성이 깨지지 않고 notice 가 한 번 나갈 뿐이다.

---

## 10. 수명 관리

### 10.1 게으른 프로비저닝

`SandboxWorkspaceManager.connect(binding)`:

```
1. record = store.find(ws) or store.createIfAbsent(new OPEN workspace)
2. tenant check (§8.3)
3. slot = record.slots[binding.slot]
     absent / TERMINATED  -> quota check -> CAS: PROVISIONING (generation+1)
                             -> provider.create(key) -> CAS: RUNNING(providerRef)
     PAUSED               -> quota check (maxRunning) -> provider.resume -> CAS: RUNNING
     PROVISIONING (other node) -> poll status with backoff, up to provisionTimeout
     FAILED               -> retry after failureBackoff, else error
4. if !slot.seeded: seed (§11.3, idempotent) -> CAS: seeded=true
5. connectionCache.get(providerRef)
```

seed 완료를 `RUNNING` 과 별개의 표시(`seeded`)로 두는 이유: RUNNING 으로 CAS 한 노드가 seed 도중에 죽으면,
RUNNING 만 보고는 `/workspace/repo` 가 비었는지 알 수 없다. seed 단계는 전부 멱등이라(§11.3) 두 노드가 동시에
돌거나 중간부터 다시 돌아도 결과가 같다. seed 가 끝나지 않은 슬롯에서 온 명령은 seed 를 기다린다.

**프로비저닝 시간은 명령 타임아웃에 넣지 않는다.** 첫 `Bash` 는 샌드박스 기동과 큰 저장소 clone 을 기다릴 수
있다(수 초 ~ 수 분). 이 대기는 `provisionTimeout`(기본 5분)으로 따로 제한하고, 모델이 준 명령 타임아웃은
명령이 실제로 시작된 뒤부터 센다. 대기가 길어지면 결과에 notice("sandbox provisioned in 94s")를 붙인다.

세션을 여는 시점에 만들지 않는 이유: 많은 세션은 명령을 한 번도 실행하지 않는다. 샌드박스 하나는 수 초의
기동 시간과 CPU·메모리 예약을 뜻한다.

CAS 가 옛 `SandboxLock` 을 대체한다. 같은 슬롯을 두 노드가 동시에 만들려 하면 한쪽만 `PROVISIONING` 전이에
성공하고, 다른 쪽은 그 상태를 보고 기다린다. 성공한 쪽이 `create` 중에 죽으면 레코드는 `PROVISIONING` 에
남는다. 이때 `provisionTimeout` 이 지나면 다른 노드가 같은 키로 `create` 를 다시 부른다. 키가 같으므로
중복 생성되지 않는다(§6.3).

### 10.2 idle 정책

프로파일마다 `pauseAfter` 와 `terminateAfter` 를 둔다(둘 다 선택). 워크스페이스에는 `closeAfter`(모든 슬롯이
TERMINATED 인 채로 지난 시간)가 있다.

| 기본 프로파일 예 | pauseAfter | terminateAfter |
|-----------------|-----------|----------------|
| `standard` | 15분 | 2시간 |
| `review` | 5분 | 30분 |
| `experiment` | — | 20분 |

idle 은 `lastActivityAt` 기준이고, 도는 명령이 있는 동안은 heartbeat 가 그 값을 계속 밀어 준다(§5.3). 그래서
`pauseAfter` 보다 긴 명령도 도중에 멈추지 않는다.

### 10.3 프로바이더 타임아웃 — 최후 방어선

슬롯을 만들 때 프로바이더의 idle 타임아웃을 `terminateAfter` 로 건다. 활동이 있으면 `renew` 한다(갱신도
`lastActivityAt` 기록과 같은 간격으로 스로틀하고, 명령이 도는 동안은 heartbeat 가 부른다). AIMON 쪽 janitor 가 전부 멈춰도 샌드박스는 프로바이더가
회수한다. 옛 설계가 TTL 을 스토어와 라벨 두 곳에 둔 것은 "재시작한 프로세스가 만료 시각을 잃는다"는
문제 때문이었다(sandbox.md §4.3). 만료를 집행하는 쪽이 프로바이더면 그 문제 자체가 없다.

### 10.4 `SandboxJanitor`

application-scoped 단일 루프(기본 30초)이며 두 일을 한다.

1. **idle 집행** — `store.scan` 으로 `pauseAfter`/`terminateAfter`/`closeAfter` 를 넘긴 대상을 찾아 CAS 로
   전이한 뒤 프로바이더를 호출한다
2. **조정** — `provider.list(managed=true, deployment={자기 deployment})` 와 레코드를 비교한다. 다른
   deployment 라벨의 샌드박스는 보지도 건드리지도 않는다(§6.3)

| 레코드 | 프로바이더 | 판정 | 조치 |
|-------|-----------|------|-----|
| RUNNING/PAUSED | 없음 | LOST | 슬롯 → TERMINATED. 다음 사용이 generation+1 로 새로 만든다. 레코드에 `lostAt` 을 남겨 첫 notice 에 포함 |
| PROVISIONING (같은 generation) | 있음 또는 없음 | IN-FLIGHT | 건드리지 않는다. `create` 가 끝났지만 RUNNING CAS 전일 수 있다. `provisionTimeout` 이 지나면 §10.1 의 인계가 처리한다 |
| FAILED (같은 generation) | 있음 | FAILED-LEFTOVER | destroy. 실패한 `create` 가 일부만 만들어 둔 샌드박스다. 재시도는 같은 키로 다시 만든다 |
| TERMINATED 또는 레코드 없음 | 있음 | ORPHAN | `orphanGrace`(기본 10분) 뒤 destroy |
| PAUSED | RUNNING | DRIFT | pause 재시도 |
| 다른 generation | 있음 | STALE | destroy |

`orphanGrace` 는 `provisionTimeout` 보다 길어야 한다. 짧으면 레코드 쓰기가 늦은 정상 프로비저닝을 고아로
오인한다.

모든 노드에서 janitor 가 돌아도 된다. 모든 조치가 CAS 로 보호되고 프로바이더 호출은 멱등이기 때문이다.
리더 선출은 두지 않는다 — 선출 저장소가 하나 더 생기는 비용이 중복 `list` 호출보다 크다.

### 10.5 워크스페이스 종료

`SandboxWorkspaceManager.close(id)` — `CLOSING` 으로 CAS → 모든 슬롯 destroy → 공유 볼륨 삭제
(`retainSharedVolume` 이면 남김) → `CLOSED`. 애플리케이션이 작업 종료 시점을 알면 부른다. 모르면 idle 정책이
결국 닫는다.

---

## 11. 파일 시스템과 협업

### 11.1 배치

```
/workspace              sandbox-private (container fs or per-sandbox volume)
  repo/                 default working root
  .worktrees/<key>/     git worktrees for parallel work inside one sandbox
  .aimon/skills/<name>/<contentKey>/  staged skill files (on demand)
/shared                 workspace volume, mounted per profile (sharedAccess: rw | ro | none)
  git/repo.git          bare repository used to exchange commits between slots
  context/ plans/ artifacts/ results/
```

파일 도구는 샌드박스 환경의 `SandboxFileSystem` 하나만 본다. `/shared` 도 샌드박스 **안에** 마운트되므로
경로 기반 라우터(초안의 `WorkspaceFileResolver`)는 필요 없다. 셸이 보는 것과 파일 도구가 보는 것이
구조적으로 같다.

같은 파일 시스템이어도 **상대 경로의 기준은 다르다.** 파일 도구는 바인딩의 `root` 를, 셸은 모델이 `cd` 한
세션 cwd 를 기준으로 삼는다. `cd src` 뒤의 `Read Foo.java` 는 `src/Foo.java` 가 아니다. 파일 도구의 기준을 셸
cwd 에 맞추면 도구 결과가 직전 명령에 따라 달라지므로, 기준은 `root` 로 고정한다. 대신 서술자(`descriptor`)의
notes 에 "file tools resolve relative paths against {root}; prefer absolute paths" 를 넣어 모델에게 알린다.

`root` 는 항상 존재한다. seed 가 없는 프로파일도 seed 단계에서 `mkdir -p {root}` 를 하고, `isolate()` 는
worktree 를 만든 뒤에 환경을 돌려준다.

**파일 도구에 경로 제한을 두지 않는다.** 샌드박스 밖으로 나가는 경로는 애초에 없고, 샌드박스 안에서는
셸이 같은 uid 로 이미 무엇이든 할 수 있다. 파일 도구만 막으면 모델이 `cat` 으로 돌아갈 뿐이다.

**에이전트 VFS(스킬·메모리 파일)는 샌드박스 모드의 파일 도구에서 보이지 않는다.** 스킬이 스크립트를
실행해야 하면 코어가 스킬을 렌더하는 시점에 `stage()` 를 부르고, 이 모듈은 그 스킬의 파일만
`/workspace/.aimon/skills/{name}/{contentKey}/` 로 materialize 한다. 스킬 전체를 기동 시점에 복사하지 않는다.
규칙은 코어 [실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)
§4.4 가 정본이며, 이 모듈이 지킬 것은 셋이다.

- **렌더하는 모든 경로에서 불린다.** `Skill` 도구만이 아니라 스킬 포크와 스킬 기반 슬래시 커맨드도 같은 경로로
  `stage()` 를 부른다. 활성화 시점을 이 모듈이 따로 가정하지 않는다
- **생략은 샌드박스 안의 마커로 판단한다.** 복사를 마친 뒤 마지막에 `.staged` 마커를 쓰고, 마커가 있을 때만
  건너뛴다. "이 generation 에 이미 올렸다"를 노드 메모리나 레코드에 기억하지 않는다 — 소실 후 재생성(§10.4)이나
  다른 노드에서 온 요청이 그 기록을 믿으면 없는 경로를 모델에게 준다
- **worktree 와 공유한다.** `.aimon/skills/` 는 `repo/` 와 `.worktrees/` 밖에 있으므로 `isolate()` 가 만든
  환경도 같은 사본을 쓰고, git 병합에 섞이지 않는다

**전체 VFS 동기화는 없다.** 명령마다 VFS↔샌드박스를 동기화하는 방식은 큰 저장소에서 느리고, 동시 수정을
합칠 규칙이 없다. 초안 두 개가 모두 기각했고, 이 설계는 호환 모드로도 남기지 않는다.

### 11.2 한 샌드박스 안의 병렬 — worktree

같은 샌드박스에서 여러 실행이 동시에 코드를 바꿔야 하면 `git worktree add /workspace/.worktrees/{key}` 를 만들고
바인딩의 `root` 를 거기로 둔다. 워크플로 격리 브랜치는 샌드박스 모드에서 이 경로를 탄다. 브랜치 병합은
`WorktreeMerge` 의 VFS 복사가 아니라 git merge/cherry-pick 이다. worktree 는 **샌드박스 안의 병렬성**을,
슬롯 분리는 **런타임 자체의 격리**를 푼다. 둘은 함께 쓸 수 있다.

### 11.3 슬롯 사이의 협업 — `/shared` 의 bare 저장소

슬롯은 `/workspace` 를 공유하지 않는다. 커밋을 주고받는 통로는 `/shared/git/repo.git` 이다.

```
provision(slot, gen) seed:                                   (every step idempotent)
  command -v git rg || fail "image contract"                 (§13.3)
  if /shared/git/repo.git absent:                            (needs sharedAccess=rw + egress to remote)
      git clone --bare <remote> /shared/git/.tmp-<uuid>
      mv -T /shared/git/.tmp-<uuid> /shared/git/repo.git     (atomic; loser removes its tmp)
  if /workspace/repo absent:
      git clone /shared/git/repo.git /workspace/.tmp-repo && mv -T /workspace/.tmp-repo /workspace/repo
  mkdir -p <root>
developer slot:  git push origin HEAD:refs/heads/dev/<topic>
review slot:     git fetch origin && git checkout dev/<topic>        (no external network needed)
```

bare 저장소는 임시 경로에 받은 뒤 rename 으로 올린다. 두 슬롯이 동시에 처음 떠도 `mv -T` 는 한쪽만 성공하고,
clone 이 중간에 끊겨도 반쯤 받은 디렉터리가 `repo.git` 이름으로 남지 않는다. "있으면 건너뛴다" 검사가
완성된 저장소만 보게 하려는 것이다.

외부 원격(GitHub)과 통신하는 것은 그 권한을 프로파일로 받은 슬롯뿐이다. `review` · `test` 프로파일은
egress 를 전부 막아도 개발 슬롯의 커밋을 받을 수 있다. 공유 PVC 위의 **작업 트리** `.git` 을 여러 샌드박스가
동시에 만지는 방식은 쓰지 않는다. bare 저장소에 대한 push/fetch 는 git 이 ref 락으로 직렬화한다.

**bare 저장소는 슬롯 사이의 코드 실행 통로가 될 수 있다.** 로컬 경로로 `git push` 하면 `receive-pack` 이
**push 하는 쪽 샌드박스 안에서** `repo.git/hooks/*` 와 `repo.git/config` 를 읽어 실행한다. `/shared` 에 쓸 수
있는 슬롯은 누구나 훅을 심을 수 있고, 그 훅은 다음에 push 하는 슬롯 — 대개 egress 와 자격 증명을 가진 개발
슬롯 — 에서 돈다. 그래서:

- 프로파일의 `sharedAccess` 로 `/shared` 마운트를 `rw` · `ro` · `none` 중에서 고른다. 기본은 `ro` 다. `rw` 를
  받은 슬롯들은 서로 한 신뢰 도메인이 되므로, 신뢰가 다른 슬롯에 `rw` 를 함께 주지 않는다
- `ro` 슬롯은 fetch 만 할 수 있다. 리뷰 결과처럼 되돌려 줄 것이 있으면 코드가 아니라 도구 결과(모델이 읽는 텍스트)나
  artifact 로 돌려준다
- seed 는 bare 저장소를 만든 직후 `hooks/` 를 비우고, seed 자신의 git 호출은 `-c core.hooksPath=/dev/null` 로
  돈다. 모델이 치는 git 명령까지 막을 수는 없으므로 이것은 방어가 아니라 기본값 정리다 — 경계는 `sharedAccess` 다

`SHARED_VOLUME` 을 갖추지 못한 프로바이더에서는 워크스페이스가 슬롯 하나로 제한된다. 슬롯을 둘 이상 만들려
하면 명확한 에러를 낸다. 워크스페이스의 첫 bare 저장소는 `sharedAccess: rw` 이면서 원격에 닿는 슬롯만 만들 수
있다. 그런 슬롯이 아직 없는데 `ro` 슬롯이 먼저 뜨면, seed 는 `/workspace/repo` 를 빈 저장소로 두고 notice 로
알린다.

### 11.4 공유 파일의 동시성

`/shared` 의 계획·설계 문서를 여러 에이전트(`sharedAccess: rw` 슬롯)가 고칠 때 쓰는 장치는 §7 의 stamp 검사
하나다. 경로 락
(TTL·heartbeat)과 리비전 DB 는 두지 않는다. 셸이 둘 다 우회하므로 락이 보장하는 것은 "파일 도구끼리는
안 부딪힌다"뿐인데, 그것은 stamp 검사가 이미 준다. 진짜 격리가 필요하면 파일 단위 조율이 아니라 git
브랜치와 슬롯 분리를 쓴다.

### 11.5 artifact

artifact 는 샌드박스보다 오래 살아야 한다. 샌드박스 파일을 가리키는 경로를 `ArtifactCollector` 에
등록하면 샌드박스가 종료될 때 artifact 가 끊어진다. 그래서 등록 시점에 제어 저장소로 복사해야 한다.

**복사는 이 모듈이 하지 않는다.** 이 모듈은 `durable() == false` 를 정직하게 답할 뿐이고, 코어의 artifact-aware
도구가 그것을 보고 `controlFileSystem` 으로 복사한다. 복사 경로와 상한(파일당 50MB · 실행당 총 100MB — 옛
`TarSecurityPolicy` 의 값을 옮겼다)의 정본은 코어 설계 §9.3 이다. 이 모듈에 artifact 전용 경로나 전송 상한
클래스를 두지 않는다. 두면 같은 정책이 두 저장소에서 따로 바뀐다.

---

## 12. 보안

### 12.1 층

| 층 | 무엇을 막나 | 누가 |
|----|------------|-----|
| 바인딩 정책 + 테넌트 검사 | 다른 워크스페이스·테넌트의 샌드박스에 닿기 | 이 모듈(§8) |
| 권한 훅 (`PreToolUse` · `permissionRequest` 이벤트) | 허용되지 않은 도구·명령 | 코어 — 샌드박스와 무관하게 그대로 적용된다 |
| 프로파일: runtimeClass | 컨테이너 탈출 | gVisor / Kata via OpenSandbox |
| 프로파일: egress 허용 목록 | 데이터 유출, 메타데이터 엔드포인트 | OpenSandbox network policy. **기본 전부 차단** |
| 프로파일: 자격 증명 바인딩 | 토큰 탈취 | OpenSandbox credential vault — 샌드박스에는 가짜 값만, egress 에서 실제 값 주입 |
| 프로파일: 자원 | 자원 고갈 | cpu · memory · disk · pids |
| 프로바이더 기본값: 비루트 · 권한 상승 금지 · capability 제거 · 서비스 계정 토큰 미마운트 | 권한 상승, K8s API 접근 | OpenSandbox 가 만드는 파드의 SecurityContext(구현 시 확인). 확인되지 않으면 프로파일이 요구할 수 없다(§6.4) |
| 프로파일: `sharedAccess` | `/shared` 를 거친 슬롯 간 영향(bare 저장소 훅·ref, 공유 문서) | 볼륨 마운트 모드(§11.3). **기본 `ro`** |
| 쿼터 | 워크스페이스가 샌드박스를 무한히 만들기 | `WorkspaceQuota` + `SandboxAdmission` |

명령 블랙리스트(`mount`, `nsenter` …)는 두지 않는다. 우회가 쉬워 보안 경계가 될 수 없고, 권한 훅이 이미
정책을 걸 자리를 준다.

**닫힌 실패.** 샌드박스 제공자의 `resolve()` 가 실패하면 코어가 호출마다 에러를 내는
`UnavailableExecutionEnvironment` 를 넣는다(코어 실행 환경 설계 §5.1). 환경 키는 한 번만 쓸 수 있고 제공자는
하나이므로, 호스트 환경이 조용히 남는 경로가 구조적으로 없다.

이 보장은 **`ToolContext` 를 조립하는 모든 지점이 제공자를 거칠 때만** 성립한다. 코어 설계가 꼽는 조립 지점은
`OrcaAgentExecutor.createToolContext()` 와 `DefaultSubagentExecutor` 둘이다. 지금 코어에는 셋째 지점 —
슬래시 커맨드(`/my-skill`)가 쓰는 손으로 조립한 컨텍스트(`OrcaAgentExecutor` 의 command 경로) — 이 있다. 이
경로가 환경 없이 남으면 인라인 스킬의 도구가 "No execution environment" 로 실패하고, 로컬 환경을 직접 넣으면
샌드박스 모드에서 호스트로 샌다. 코어 PR 은 이 경로도 `resolve()` 를 거치게 해야 하며, 이 모듈의 통합 테스트가
슬래시 커맨드 경로를 덮는다(§16).

**스킬 선언 훅의 셸 액션**(`ShellActionExecutor`)은 지금 호스트에서 돈다. 운영자 설정이 아니라 **스킬 파일이
선언한** 코드다(코어 실행 환경 설계 §14). 그래서 샌드박스를 써도 같은 스킬의 스크립트가 `Bash` 로는 샌드박스에서,
훅으로는 호스트에서 돌고, 스킬 작성자에게 호스트 셸을 주는 통로가 된다. 어디서 돌릴지는 열린 질문이다(§20).

### 12.2 쿼터

`WorkspaceQuota` — `maxSlots`(기본 6) · `maxRunning`(기본 3) · `maxCpu` · `maxMemory`. 슬롯을
`PROVISIONING` 으로 올리는 CAS 에서 같이 검사하므로 경합으로 넘치지 않는다. 테넌트 단위 쿼터는 워크스페이스
레코드 하나로 판단할 수 없다. 그래서 애플리케이션이 `SandboxAdmission.admit(owner, profile)` 을 주입한다.
기본 구현은 전부 허용이다.

---

## 13. 설정

### 13.1 `SandboxProfile`

| 필드 | 예 / 기본 |
|------|----------|
| `name` | `standard` |
| `image` | `ghcr.io/kangwoo/aimon-sandbox-runtime:1` |
| `cpu` · `memory` · `disk` · `pids` | `2` · `4Gi` · `20Gi` · `512` |
| `runtimeClass` | `gvisor` |
| `egress` | `[]` (전부 차단) |
| `credentials` | 자격 증명 바인딩 이름 목록 — vault 쪽 정의를 가리킨다 |
| `env` | 정적 환경 변수 |
| `pauseAfter` · `terminateAfter` | §10.2 |
| `sharedAccess` | `ro` (`rw` · `ro` · `none`, §11.3) |
| `seed` | `git`(원격 URL · ref · 자격 증명 이름) 또는 없음 |

프로파일은 운영자 설정이다. 모델은 프로파일을 **고를** 수만 있고(오케스트레이터 도구의 허용 목록 안에서),
만들거나 고칠 수 없다.

### 13.2 설정 예 (Spring 속성 — 키는 제안)

```yaml
aimon:
  sandbox:
    enabled: true
    deployment: acme-prod            # required; scopes labels and janitor reconciliation (§6.3)
    require-principal: true          # multi-tenant deployments (§8.3)
    provision-timeout: 5m
    orphan-grace: 10m                # must exceed provision-timeout (§10.4)
    exec-shell-idle: 10m
    opensandbox:
      endpoint: ${OPEN_SANDBOX_ENDPOINT}
      api-key: ${OPEN_SANDBOX_API_KEY}
      request-timeout: 30s
    default-profile: standard
    profiles:
      standard:
        image: ghcr.io/kangwoo/aimon-sandbox-runtime:1
        cpu: 2
        memory: 4Gi
        pids: 512
        runtime-class: gvisor
        egress: [github.com, repo.maven.apache.org]
        credentials: [github-rw]
        pause-after: 15m
        terminate-after: 2h
        shared-access: rw
        seed: { git: { url: "https://github.com/acme/app.git", ref: main, credential: github-rw } }
      review:
        image: ghcr.io/kangwoo/aimon-sandbox-runtime:1
        cpu: 1
        memory: 2Gi
        egress: []
        pause-after: 5m
        terminate-after: 30m
        shared-access: ro            # can fetch dev branches, cannot plant hooks or move refs
    workspace:
      max-slots: 6
      max-running: 3
      shared-volume: { size: 5Gi, retain-on-close: false }
    orchestrator-tools:
      agents: [orchestrator]
      allowed-profiles: [standard, review]
```

### 13.3 이미지 계약

샌드박스 이미지가 지켜야 하는 것: `bash` · coreutils · `git` · `rg`(ripgrep) · uid 1000 `sandbox` 사용자 ·
그 사용자 소유의 `/workspace`. OpenSandbox 가 execd 를 주입하므로 이미지가 execd 를 포함할 필요는 없다
(구현 시 확인). 계약을 벗어난 이미지는 첫 프로비저닝의 seed 단계에서 `command -v git rg` 검사로 실패시킨다.
실패를 늦게 발견하면 `Grep` 이 원인 모를 에러를 낸다.

---

## 14. 관측성

span 속성: `aimon.sandbox.workspace` · `aimon.sandbox.slot` · `aimon.sandbox.generation` ·
`aimon.sandbox.provider_id` · `aimon.sandbox.shell_key`. span: `sandbox.provision` · `sandbox.resume` ·
`sandbox.exec` · `sandbox.files.{read,write,search}`. 모두 코어 tracing 의 tool span 아래에 달린다.

메트릭: `aimon_sandbox_running`(게이지, slot·profile) · `aimon_sandbox_provision_seconds` ·
`aimon_sandbox_resume_seconds` · `aimon_sandbox_exec_seconds` · `aimon_sandbox_exec_failures_total` ·
`aimon_sandbox_lost_total` · `aimon_sandbox_orphans_destroyed_total` · `aimon_sandbox_quota_rejections_total`.

감사는 별도 서브시스템을 만들지 않는다. 명령·파일 쓰기의 감사는 코어의 도구 훅(PostToolUse)과 tracing 이
이미 남기며, 거기에 위 속성이 붙는다. 샌드박스 수명 이벤트(provisioned · paused · terminated · lost ·
orphan-destroyed)는 `SandboxEventListener` 로 내보낸다. 애플리케이션은 그것을 자기 감사 로그로 보낸다.
이벤트는 `workspaceId` · `slot` · `generation` · `owner` · `profile` · 원인을 담는다. 테넌트별 감사는 `owner` 로
가른다.

---

## 15. 에러 처리

도구는 예외를 던지지 않는다(코어 규칙). 환경 계층의 실패는 VFS 예외와 `ShellExecutionException` 으로
올라오고, 도구가 그것을 `ToolResult.error` 로 바꾼다.

| 상황 | 결과 |
|------|------|
| 프로비저닝 실패 | 슬롯 `FAILED`. 에러에 프로파일과 원인을 적는다. `failureBackoff` 뒤 다음 호출이 재시도 |
| 쿼터 초과 | 어떤 쿼터가 몇인지 적는다. 모델이 `SandboxStop` 으로 자리를 비울 수 있게 |
| 명령 도중 샌드박스 소실 | 에러 + "환경이 다음 호출에서 다시 만들어지며 `/workspace` 가 초기화된다" |
| 셸 세션 소실 | 새 세션으로 명령을 실행하고 notice 를 붙인다(에러 아님) |
| 프로바이더 접속 불가 | 에러. 호스트로 되돌아가지 않는다(§12.1) |
| 명령 타임아웃 | `VirtualShell` 계약대로 — 원격 명령을 kill 하고 부분 출력과 함께 `ShellTimeoutException` |
| 다른 노드가 프로비저닝 중 | `provisionTimeout` 까지 기다린다. 넘으면 에러 |
| 프로비저닝·seed 가 오래 걸림 | 명령 타임아웃에 넣지 않는다. `provisionTimeout` 안이면 명령을 실행하고 걸린 시간을 notice 로 붙인다(§10.1) |
| seed 실패(이미지 계약 위반, clone 실패) | 슬롯은 RUNNING 이지만 `seeded=false`. 에러에 실패한 단계를 적는다. 다음 호출이 seed 를 다시 돈다 |
| 명령 kill 로 셸 세션까지 잃음 | 다음 명령이 새 세션을 열고 notice 를 붙인다(§9) |
| `Principal` 없음 + `require-principal` | 바인딩 거부 — `UnavailableExecutionEnvironment` 가 이유를 담는다(§8.3) |
| `sharedAccess: ro` 슬롯의 `/shared` 쓰기 | 파일 시스템의 읽기 전용 에러를 그대로 돌려준다 |
| 읽은 뒤 파일이 바뀜 | `Edit`/`Write` 에러 — 다시 읽으라고 안내(§7) |
| `SHARED_VOLUME` 없이 두 번째 슬롯 | 에러 — 프로바이더가 멀티 슬롯을 지원하지 않는다 |

---

## 16. 테스트

**계약 스위트** — `aimon-sandbox-testkit` 의 `SandboxProviderContract`(abstract JUnit 클래스). 모든 프로바이더가
통과해야 한다: 멱등 생성(같은 키 두 번 → 하나) · destroy 멱등 · exec 의 stdout/stderr/exit code · 출력 상한
truncation · `kill()` 이 실제로 프로세스를 끝냄 · 셸 세션의 cwd/env 유지 · 세션 간 env 격리 · files
read/write/stat/list/move · `list(labels)` · capability 가 광고한 기능만 동작.

**`LocalProcessSandboxProvider`** — testkit 에만 있는 프로바이더. 임시 디렉터리 + 로컬 프로세스로 계약을
흉내 낸다. 격리가 없으므로 운영용이 아니며, 이름과 javadoc 에 그렇게 적는다. 매니저·바인딩·환경 제공자·코어
도구 통합 테스트를 Docker 없이 돌리기 위한 것이다.

**통합 테스트**(`@Tag("docker")`) — Testcontainers 로 OpenSandbox 서버(Docker 런타임)를 띄워
`OpenSandboxProvider` 에 계약 스위트를 돌린다. K8s 런타임 검증은 별도 프로파일로 뺀다.

**반드시 있어야 하는 시나리오** (초안 v0.3 의 멀티 에이전트·멀티 샌드박스·장애 복구·보안 테스트에서
가져오고, 이 설계가 더한 것을 붙였다):

| 시나리오 | 기대 |
|---------|------|
| 에이전트 A `Write Foo.java` → 같은 슬롯의 에이전트 B `Read Foo.java` | 즉시 보인다 |
| A `export FOO=A` → B `echo $FOO` | 비어 있다 (셸 격리) |
| `Write` → `Bash cat` 같은 파일 | 같은 내용 (한 파일 시스템) |
| 슬롯 X `touch /workspace/a` → 슬롯 Y | 없다 |
| 슬롯 X(`rw`) `/shared/r.json` 쓰기 → 슬롯 Y | 보인다 |
| 슬롯 Y(`ro`) `/shared` 쓰기 | 읽기 전용 에러 |
| 개발 슬롯 push → egress 차단 리뷰 슬롯(`ro`) fetch | 성공 |
| `ro` 슬롯이 `repo.git/hooks` 에 훅 쓰기 시도 → 개발 슬롯 push | 쓰기 실패, 개발 슬롯에서 아무것도 실행되지 않음 |
| 두 슬롯이 동시에 첫 seed | bare 저장소 하나, 둘 다 완전한 clone |
| seed 도중 노드 종료 → 다른 노드의 다음 호출 | seed 를 다시 돌아 완료 |
| 세션 없는 루틴 → 서브에이전트 포크 → 손자 포크 | 셋 다 같은 워크스페이스·슬롯 |
| `pauseAfter` 보다 긴 명령, 그동안 도구 호출 없음 | 명령 도중 pause 되지 않음 |
| 백그라운드 명령 실행 중 같은 세션의 다음 `Bash` | 기다리지 않고 바로 돈다 |
| 노드 재시작 → 같은 세션의 다음 턴 (영속 저장소, §18-5) | 같은 샌드박스, 같은 cwd (또는 notice) |
| 노드 재시작 (`InMemory` 저장소) | 새 샌드박스. 옛 샌드박스는 `orphanGrace` 뒤 회수 |
| 레코드 삭제 후 janitor | 고아 destroy |
| 다른 `deployment` 라벨의 샌드박스 | janitor 가 건드리지 않음 |
| 두 노드가 같은 슬롯 동시 `connect` | 샌드박스 하나 |
| 다른 테넌트 principal 로 같은 workspaceId | 거부 |
| principal 없는 실행 + `require-principal` | 거부 |
| 슬래시 커맨드(`/skill`)로 부른 스킬의 `Bash` | 샌드박스에서 돈다 (호스트 아님) |
| pause 된 슬롯에서 다음 `Bash` | resume 후 실행된다. 셸 세션을 잃었으면 notice |
| 명령 사이에 샌드박스 소실(LOST) → 다음 호출 | generation+1 로 재생성, notice, 스킬 스테이징을 다시 복사 |
| 자원 한도 | pids 폭주·메모리 초과가 그 샌드박스 안에서 끝나고 다른 슬롯에 번지지 않음 |
| 보안 | 호스트 파일·Docker 소켓·K8s API·클라우드 메타데이터 접근 불가, 실제 자격 증명 조회 불가 |

---

## 17. 옛 설계 대응표

하위 호환을 두지 않으므로 이행 계층도 없다. 좌표 `at.aimon.sandbox:*` 의 다음 0.x 마이너에서 한 번에 바뀐다.

| 옛 것 | 새 것 |
|------|------|
| `RunSandboxTool` | 코어 `Bash` (샌드박스 환경) |
| `CopyToSandboxTool` | 필요 없음 — 파일 도구가 샌드박스를 직접 본다. 입력 파일은 프로파일 `seed` 나 `/shared` 로 |
| `DeleteSandboxTool` · `RestartSandboxTool` | 오케스트레이터 `SandboxStop` (+ 다음 사용 시 재생성) |
| `identifier` (모델이 지정) | `(workspace, slot)` (정책이 지정) |
| `SandboxBackend` | `SandboxProvider` + `SandboxConnection` |
| `SandboxRun` · `RunManager` · `RunStore` · `RunState` | 없음 — 실행 기록은 tracing 과 도구 결과가 맡는다 |
| `SandboxExpiryStore` + 만료 라벨 | 프로바이더 idle 타임아웃 + 워크스페이스 레코드 |
| `SandboxLock` · `LocalSandboxLock` | 레코드 CAS + shellKey 로컬 직렬화 |
| `ReaperService` | `SandboxJanitor` |
| `TarCreator` · `TarExtractor` · `TarSecurityPolicy` | 프로바이더 `files()`. artifact 상한은 코어 실행 환경 설계 §9.3 으로 옮겨 간다 |
| `IdentifierValidator` | 슬롯 이름 검증 |
| `aimon-sandbox-docker` · `aimon-sandbox-kubernetes` | 삭제 → `aimon-sandbox-opensandbox` |
| `SandboxConfig` | `SandboxProfile` + `WorkspaceQuota` + 매니저 설정 |

---

## 18. 구현 순서

각 단계가 그 자체로 쓸모 있게 자른다.

1. **aimon-core — 실행 환경 SPI** (§7). 코어 실행 환경 설계의 §11 이행 순서 전체. 샌드박스 없이도 이득이다 —
   워크플로 브랜치의 레지스트리 복제가 사라지고, 모델이 파일 도구로 제어 평면을 덮어쓸 수 없게 된다. 슬래시
   커맨드 경로의 컨텍스트 조립도 `resolve()` 를 거치게 하는 것이 이 단계의 완료 조건이다(§12.1)
2. **aimon-sandbox — 도메인과 로컬 경로.** 레코드·`InMemory` 저장소·매니저·프로바이더 SPI·기본 바인딩 정책·
   환경 제공자·활동 heartbeat·testkit(계약 + 로컬 프로바이더). 슬롯은 `primary` 하나만, 단일 노드만. 이
   시점에 옛 도구와 백엔드 모듈을 지운다
3. **aimon-sandbox-opensandbox.** 프로바이더·셸 세션·idle 타임아웃·`deployment` 라벨·janitor 조정·계약 스위트
   통합 테스트. 조정은 `InMemory` 저장소의 재시작 뒤 고아를 치우는 유일한 장치이므로 여기서 들어간다. 이 단계의
   관문은 §6.4 표의 "구현 시 확인" 칸을 모두 확인으로 바꾸는 것이다. 확인되지 않은 capability 는 광고하지 않는다
4. **멀티 슬롯.** 슬롯별 프로파일 · 공유 볼륨과 `sharedAccess` · bare 저장소 seed · 오케스트레이터 도구 · 쿼터 ·
   에이전트 메타데이터의 `sandbox.slot`/`sandbox.profile`
5. **영속 저장소 — 멀티 노드.** `aimon-sandbox-store-jdbc` 와 그것을 덮는 저장소 계약 스위트(CAS 충돌·scan
   페이징). 이 단계가 끝나야 §1.2 의 "멀티 노드" 목표와 §16 의 노드 재시작 시나리오가 성립한다
6. **수명 최적화.** pause/resume · warm pool(프로바이더 내부) · 샌드박스 worktree 로 워크플로 격리 브랜치 전환
7. **스냅숏·포크.** `SNAPSHOT` capability · `SandboxFork` · 병렬 해법 탐색 패턴

---

## 19. 기각한 대안

| 대안 | 왜 기각했나 |
|------|------------|
| identifier 기반 도구를 새 방식과 나란히 유지 | 모델이 두 세계 중 하나를 골라야 하고, `Write` 와 `RunSandbox` 가 서로 다른 파일을 보는 문제가 그대로 남는다. 하위 호환이 목표가 아니므로 남길 이유가 없다 |
| LLM 이 `sandboxId` 를 도구 인자로 지정 | 인젝션 한 줄로 다른 워크스페이스에 닿는다. 두 초안 모두 기각했다 |
| 전체 VFS ↔ 샌드박스 동기화 | 큰 저장소에서 명령마다 I/O. 동시 수정의 병합 규칙이 없다. 초안은 호환 모드로 남겼지만 여기서는 없앴다 |
| `WorkspaceFileResolver` (경로별 파일 시스템 라우터) | `/shared` 를 샌드박스 안에 마운트하면 라우팅할 대상이 하나뿐이다. 라우터를 두면 셸과 파일 도구가 `/shared` 를 서로 다른 경로로 보게 될 여지가 생긴다 |
| 별도 Sandbox Gateway / Workspace Service (네트워크 서비스) | AIMON 은 임베드 라이브러리다. 서비스를 두면 홉이 하나 늘고 인가가 두 곳이 된다. 필요한 애플리케이션은 이 모듈을 자기 서비스 안에 넣으면 된다 |
| `SandboxRole` enum | §5.2 — 개발 프로세스를 SPI 에 굳힌다. 슬롯 + 프로파일이 같은 일을 설정으로 한다 |
| 워크스페이스·샌드박스·에이전트세션 저장소 3분할 | §5.3 — 한 변경이 세 저장소 쓰기가 되어 어긋날 수 있다 |
| 공유 파일 리비전 DB + 경로 락 | §11.4 — 셸이 우회한다. 파일 도구끼리의 충돌은 stamp 검사가 실제 파일 상태로 잡는다 |
| Docker/K8s 자체 백엔드 유지 | §6.4 — 같은 수명 관리를 두 번 구현하고, 보안 기능은 둘 다 없다. OpenSandbox 가 두 런타임을 모두 덮는다 |
| 세션을 열 때 샌드박스 생성 | 명령을 실행하지 않는 세션이 많다. 기동 시간과 자원 예약이 낭비다 |
| 워크스페이스를 `AgentRuntime` 단위로 | 런타임은 여러 세션·테넌트의 실행을 받는다. 테넌트 경계가 무너진다 |
| 셸 세션을 에이전트 이름 단위로 | 같은 에이전트의 다른 세션이 cwd 를 공유하게 된다 |
| 셸 세션을 턴 단위로 | 턴마다 cwd 가 초기화되어 모델이 매번 `cd` 부터 다시 한다 |
| SDK `Sandbox` 객체를 상태로 보관 | 노드 이동·재시작을 견디지 못한다. 저장하는 것은 `providerRef` 와 셸 세션 id 뿐이고, 연결은 캐시다 |
| 도구 레지스트리를 실행마다 복제 (`WorktreeToolEnvironmentFactory` 방식 확대) | 코어 실행 환경 설계 §1.2 — 실행마다 도구 N개를 만들고 artifact-aware 분기를 반복한다. 환경을 컨텍스트로 넘기면 레지스트리는 그대로다 |
| 명령 블랙리스트 | §12.1 — 우회가 쉬워 경계가 되지 못한다 |
| janitor 리더 선출 | §10.4 — CAS 와 멱등 호출로 충분하다. 선출 저장소가 하나 더 생긴다 |
| 도는 명령 수를 레코드의 카운터로 | §5.3 — 노드가 죽으면 카운터가 내려가지 않아 슬롯이 영원히 활동 중이 된다. heartbeat 는 노드와 함께 멈춘다 |
| 모든 슬롯에 `/shared` 를 rw 로 | §11.3 — bare 저장소 훅으로 신뢰가 낮은 슬롯이 개발 슬롯에서 코드를 실행할 수 있다 |
| 파일 도구의 상대 경로를 셸 cwd 에 맞춤 | §11.1 — 같은 `Read` 가 직전 `cd` 에 따라 다른 파일을 읽는다 |
| 한 실행이 여러 슬롯에서 명령 실행 (에이전트↔샌드박스 N:M) | 실행마다 환경은 하나다(코어 §5.1). 파일 도구와 셸이 같은 것을 본다는 불변식이 실행 단위로 성립한다. 다른 슬롯의 일은 그 슬롯에 바인딩된 서브에이전트 포크에 맡긴다 |
| 슬롯을 만들 수 없을 때 `primary` 로 대체 | 다른 프로파일·신뢰 도메인에서 조용히 도는 것이다. egress 나 자격 증명이 다른 곳으로 옮겨 가는 열린 쪽 실패다. 쿼터 초과는 에러로 알린다(§15) |
| 워크스페이스 통합 이벤트 스트림 (명령·파일 변경·커밋) | 명령·파일 쓰기는 도구 훅과 tracing 이 이미 남긴다(§14). 에이전트 사이의 조율은 `/shared` 와 git 이 맡는다. 스트림을 두면 저장소가 하나 더 생기고 순서·보존 정책을 새로 정해야 한다. 수명 이벤트만 `SandboxEventListener` 로 내보낸다 |
| 워크스페이스 저장 모드 enum (`SHARED`/`ISOLATED`/`HYBRID`) | Hybrid 하나만 둔다. SHARED(모든 샌드박스가 한 볼륨을 작업 트리로 씀)는 `.git` 동시 변경과 신뢰 경계 약화를 부른다(§11.3). ISOLATED 는 `sharedAccess: none` 으로 표현된다 |
| 워크스페이스 단위 pause/resume | idle 은 슬롯마다 다르다(§10.2). 워크스페이스를 끝내는 경로는 close 하나로 충분하다. 필요하면 애플리케이션이 슬롯마다 멈춘다 |
| 샌드박스 자원 사용량을 이 모듈이 수집 (`METRICS` capability) | OpenSandbox 와 K8s 가 이미 낸다. §6.3 의 라벨로 이어 붙이면 된다. capability 로 올리면 SDK 의 메트릭 API 에 SPI 가 묶인다 |

---

## 20. 열린 질문

- **동적 슬롯 배정** — 오케스트레이터가 실행 중에 `exp-a/b/c` 를 만들고 서브에이전트를 각 슬롯에 붙이는
  경로. 워크플로 스크립트(`agent(prompt, { sandbox: 'exp-a' })`)는 설계 가능하지만, `Task` 도구 인자로 여는
  것은 모델에게 슬롯 선택권을 주는 일이다. 허용 목록으로 좁혀 열지, 워크플로에만 둘지 정해야 한다
- **스킬 선언 훅의 실행 위치** — 스킬 파일이 선언한 셸 훅을 호스트에서 돌리는 것이 맞는가, 바인딩된 샌드박스에서
  돌려야 하는가(§12.1, 코어 §14)
- **워크스페이스 단위 seed 매개변수** — `seed`(원격 · ref · 자격 증명 이름)는 지금 프로파일에만 있다. 그래서 저장소나
  브랜치가 티켓마다 다르면 프로파일을 저장소 수만큼 만들어야 하고, 테넌트마다 다른 자격 증명을 쓸 방법이 없다.
  병렬 해법 탐색(§18-7)도 모든 실험 슬롯이 같은 기준 커밋에서 출발해야 하는데 `SandboxStart` 에는 ref 인자가
  없다. 방법은 둘이다 — `SandboxWorkspace` 에 `createIfAbsent` 시점의 seed 매개변수를 두고 바인딩 정책이 채우거나,
  프로파일의 `seed` 를 템플릿으로 두고 워크스페이스가 값만 채운다. 어느 쪽이든 모델이 원격이나 자격 증명을 고르는
  경로는 열지 않는다
- **오래 도는 백그라운드 명령** — heartbeat(§5.3)는 명령이 도는 동안 idle 판정을 막는다. 코어의 백그라운드 명령은
  상한이 24시간이고 모델이 그것을 끝낼 도구가 없다. 그래서 개발 서버 하나가 슬롯을 하루 동안 깨워 둔다. 셋 중
  무엇을 둘지 정해야 한다 — 백그라운드 명령을 끝내는 도구(코어), 프로파일의 백그라운드 명령 상한, 워크스페이스의
  절대 수명(`maxLifetime`, 활동과 무관). 마지막 것은 프로바이더 타임아웃(§10.3)이 활동마다 갱신되므로 그것으로는
  대신할 수 없다
- **워크스페이스 체크포인트** — §18-7 의 스냅숏은 슬롯 단위다. 슬롯 여럿과 `/shared`(bare 저장소 ref 포함)를 한
  시점으로 묶는 체크포인트가 필요한지는 `SNAPSHOT` 을 확인한 뒤 정한다. 따로 뜬 슬롯 스냅숏을 함께 복원하면 bare
  저장소의 ref 와 각 슬롯의 작업 트리가 서로 다른 시점을 가리킨다
- **백그라운드 명령의 노드 이동** — `BackgroundBashManager` 는 노드 로컬이다. 노드가 죽으면 샌드박스 안의
  명령은 계속 돌지만 결과를 받을 쪽이 없다. 프로바이더 쪽 명령 id 를 레코드에 남겨 다른 노드가 다시 붙게
  할지는 5단계(영속 저장소) 이후에 판단한다. 그때까지는 heartbeat 가 노드와 함께 멈추므로, 결과를 받을 쪽이
  없는 명령은 idle 정책에 따라 샌드박스와 함께 정리된다
- **스트리밍 도구 출력** — SPI 는 `OutputSink` 로 준비되어 있지만 코어 `BashTool` 이 부분 출력을 이벤트로
  내보내는 경로가 없다
- **§6.4 의 확인 칸** — 특히 K8s 런타임에서 RWX 공유 볼륨과 샌드박스별 읽기 전용 마운트, credential vault 의
  egress 주입 범위, 셸 세션의 pause/resume 생존 여부, 세션 안의 명령만 골라 죽일 수 있는지(§9), 파드
  SecurityContext 기본값(§12.1)

---

## 21. 하지 말 것

- **`LiveSession.close()` · `AgentRuntime.close()` 에서 샌드박스를 끝내지 말 것.** 워크스페이스 수명이다(§3.2)
- **샌드박스 환경을 얻지 못했을 때 호스트 셸로 되돌아가지 말 것.** 닫힌 쪽으로 실패한다(§12.1)
- **도구 인자로 워크스페이스·슬롯·sandbox id 를 받지 말 것.** 오케스트레이터 도구의 슬롯 이름은 호출자의
  워크스페이스 안에서만 해석된다(§8.5)
- **OpenSandbox SDK 타입을 `aimon-sandbox-opensandbox` 밖으로 내보내지 말 것**(§4.1)
- **프로파일이 요구한 capability 를 프로바이더가 광고하지 않는데 조용히 진행하지 말 것**(§6.4)
- **슬롯 상태 전이를 CAS 없이 쓰지 말 것.** 노드 간 직렬화 장치는 CAS 뿐이다(§10.1)
- **`lastActivityAt` 을 명령마다 쓰지 말 것.** 스로틀한다(§5.3)
- **셸 세션 id 가 레코드에 있다고 유효하다고 가정하지 말 것**(§9)
- **`/workspace` 에 대해 `durable()` 을 `true` 로 답하지 말 것.** 코어가 그 답을 보고 artifact 를 제어 저장소로 복사한다. 이 모듈이 직접 복사하거나 자기 상한을 두지도 말 것(§11.5)
- **"이미 스테이징했다"를 메모리나 레코드로 판단하지 말 것.** 샌드박스 안의 `.staged` 마커를 본다(§11.1)
- **공유 볼륨 위의 작업 트리 `.git` 을 여러 샌드박스가 동시에 쓰게 하지 말 것.** bare 저장소를 거친다(§11.3)
- **신뢰가 다른 슬롯에 `/shared` 쓰기 권한을 함께 주지 말 것.** bare 저장소의 훅이 슬롯 사이의 실행 통로가
  된다(§11.3)
- **`deployment` 라벨 없이 조정하지 말 것.** 같은 OpenSandbox 서버를 쓰는 다른 배포의 샌드박스를 지운다(§6.3)
- **명령이 도는 동안 활동 기록을 멈추지 말 것.** heartbeat 가 없으면 긴 명령 도중에 pause 된다(§5.3)
- **`exec:` 셸 세션 id 를 레코드에 쓰지 말 것.** 노드 로컬 캐시에 둔다(§9)
- **백그라운드 명령을 지속 셸 세션에서 돌리지 말 것.** 그 세션의 다음 명령이 모두 기다린다(§9)
- **프로비저닝 대기를 명령 타임아웃에 넣지 말 것.** `provisionTimeout` 으로 따로 제한한다(§10.1)

---

## 관련 문서

- [`sandbox.md`](sandbox.md) — 이 설계가 대체하는 identifier 기반 설계
- [`scope-model.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) — 수명과 소멸 책임
- [`glossary.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/glossary.md) §4 — 턴 · iteration · execution
- [`session/routing.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/design/session/routing.md) — 세션의 노드 배치와 lease
- [`workflow/workflow.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/design/workflow/workflow.md) — 격리 브랜치와 `WorktreeToolEnvironmentFactory`
- [`agent-execution/artifact.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/design/agent-execution/artifact.md) — `ArtifactCollector`
- [`features/tool/tool-development-guide.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/features/tool/tool-development-guide.md) — 도구 규칙
