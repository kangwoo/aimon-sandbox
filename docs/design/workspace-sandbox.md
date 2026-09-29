# 워크스페이스 샌드박스 — 기존 도구가 OpenSandbox 위의 격리 환경을 투명하게 쓴다

> Status: **ACCEPTED** — 3단계(도메인과 로컬 경로)까지 구현되었다. 운영에 쓸 프로바이더(4단계)는 아직 없다. 3단계의
> 구현 설계와 거기서 벗어난 점은 [`workspace-sandbox-step3.md`](workspace-sandbox-step3.md) 에 있다. identifier 기반 옛 설계(도구 4개 · `SandboxBackend` · Docker/K8s 백엔드)를
> **대체한다.** 옛 코드와 문서는 저장소에서 지웠다 — 마지막 모습은 커밋 `704013c` 의
> [`sandbox.md`](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md) 이고, 배포본은 `at.aimon.core:aimon-sandbox{,-docker,-kubernetes}:0.2.4` 다. 하위 호환은
> 목표가 아니다 — 이행 경로 대신 대응표(§17)를 둔다.
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

옛 설계([`sandbox.md`](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md))의 샌드박스는 **도구가 들고 다니는 별도 세계**다. 그래서 다음 문제가 생긴다.

| 문제 | 원인 |
|------|------|
| `Write` 로 고친 파일을 `RunSandbox` 가 못 본다 | 파일 도구는 VFS, 샌드박스는 컨테이너. 둘을 잇는 것은 `CopyToSandbox` 의 tar 복사뿐이다 |
| 모델이 인프라를 관리한다 | `identifier` · `ttl_seconds` · `lock_sandbox` 가 도구 인자다. 모델이 id 를 짓고, 수명을 정하고, 락을 켠다 |
| 셸 상태가 없다 | `exec` 1건마다 새 프로세스다. `cd` · `export` 가 다음 명령까지 이어지지 않는다 |
| 취소가 없다 | `exec` 에 취소 채널이 없어 `RunState.CANCELED` 도 없다([옛 설계](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md) §13) |
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
- **지속되는 셸** — 실행 주체마다 cwd·export 된 환경 변수가 이어지는 셸 상태를 준다
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

1. **샌드박스는 도구가 아니라 실행 환경이다.** 코어의 파일·셸 도구는 실행마다 `ExecutionEnvironment` 를
   `ToolContext` 에서 꺼내 쓴다(코어에 구현됨, §7). 샌드박스 모듈은 그 환경을 끼워 넣는다
2. **식별은 `(워크스페이스, 슬롯)` 이다.** 슬롯은 워크스페이스 안의 이름(`primary`, `review`, `exp-a`)이고,
   무엇을 실행할지는 슬롯에 붙은 **프로파일**이 정한다. 역할 enum 은 없다(§5.2)
3. **워크스페이스는 한 애그리게이트로 영속한다.** 워크스페이스와 슬롯(샌드박스 참조)을 한 레코드에 담고
   버전 CAS 로 갱신한다. 저장소가 셋으로 갈라져 서로 어긋나는 일이 없다(§5.3). **셸 상태(cwd·환경 변수)는
   레코드가 아니라 샌드박스 안에 둔다**(§9)
4. **프로비저닝은 게으르다.** 세션을 열 때가 아니라 첫 도구 호출 때 샌드박스를 만든다
5. **`/shared` 는 워크스페이스 볼륨이다.** 프로파일이 허용한 샌드박스에 마운트되므로 파일 도구와 셸이 같은
   공유 영역을 본다. `/workspace` 는 샌드박스 전용이다. `/shared` 를 쓰기로 공유하는 슬롯들은 **한 신뢰
   도메인**이다(§11.3)
6. **코드 협업은 git 이다.** `/shared` 의 bare 저장소를 통해 샌드박스끼리 커밋을 주고받는다. 네트워크가
   막힌 샌드박스도 받을 수 있다(§11.3)
7. **백엔드는 OpenSandbox 하나다.** 자체 Docker/K8s 백엔드는 없앤다. OpenSandbox 가 Docker 런타임과
   K8s 런타임을 모두 가지므로 로컬 개발과 운영이 같은 코드 경로를 탄다(§6.4)
8. **프로바이더 만료가 최후 방어선이다.** AIMON 노드가 전부 죽어도 샌드박스가 영원히 남지 않도록 모든
   샌드박스에 프로바이더 쪽 만료 시각을 걸고, 활동이 있으면 뒤로 민다. 그래서 `terminateAfter` 는 필수다(§10.3)
9. **실패는 닫힌 쪽으로 간다.** 샌드박스 모드에서 환경을 얻지 못하면 도구가 에러를 돌려준다. 호스트 셸로
   조용히 되돌아가지 않는다. 격리를 요구했는데 확인할 수 없으면 시작하지 않는다(§6.4, §12.1)
10. **멀티 노드는 영속 저장소가 들어와야 성립한다.** 기본 `InMemory` 저장소는 단일 노드 전용이고, 분산
    저장소는 구현 순서의 독립 단계다(§5.3, §18)
11. **프로바이더의 멱등은 최선 노력이다.** OpenSandbox 에는 멱등 키가 없으므로 중복 생성이 드물게 생길 수 있다.
    레코드가 정본이고, janitor 조정이 레코드에 없는 샌드박스와 볼륨을 치운다(§6.3, §10.4)

---

## 3. 용어와 수명

### 3.1 용어

| 용어 | 뜻 |
|------|-----|
| **SandboxWorkspace** | 여러 실행이 한 작업을 위해 공유하는 논리적 작업 공간. 샌드박스들과 공유 볼륨을 소유한다. AIMON 의 에이전트 워크스페이스(`agents/`, `skills/` 디렉터리)와 다른 개념이라 접두어를 붙였다 |
| **slot** | 워크스페이스 안에서 샌드박스를 가리키는 이름. `^[a-z][a-z0-9-]{0,30}$`. 기본 슬롯은 `primary` |
| **SandboxProfile** | 샌드박스를 어떻게 만들지 정한 운영자 설정 — 이미지·자원·런타임 클래스·egress·자격 증명·idle 정책 |
| **generation** | 슬롯의 샌드박스가 새로 만들어질 때마다 1씩 늘어나는 정수. 이전 셸 상태와 파일이 사라졌음을 알리는 신호다 |
| **SandboxBinding** | 한 실행이 쓸 `(workspaceId, owner, slot, requiredProfile?, shellKey, root)` 와, 그 실행의 주체인 `caller`(`(tenantId, principalId)`). owner 검사(§8.3)가 `caller` 를 레코드의 owner 와 비교한다. 바인딩 정책이 만들되, `caller` 는 정책이 무엇을 넣었든 환경 provider 가 요청의 principal 로 덮어쓴다(루트·fork 모두, §8.3). principal 이 없는 fork 요청은 부모 바인딩의 `caller` 를 물려받는다(§8.2) |
| **shellKey** | 지속 셸 상태를 가리키는 키. 같은 키로 들어온 명령들은 cwd·환경 변수를 공유한다 |
| **provider** | 실제 샌드박스를 만드는 인프라. 이 설계에서는 OpenSandbox |

### 3.2 수명 배치

[`scope-model.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) 의 4단계에
다음처럼 놓인다. 수명은 이름이 아니라 **키와 저장 위치**로 판단한다.

| 대상 | 수명 | 키 / 저장 위치 | 닫는 곳 |
|------|------|---------------|--------|
| `SandboxProvider` · `SandboxWorkspaceStore` · `SandboxWorkspaceManager` · `SandboxProfileRegistry` · `SandboxBindingPolicy` · `SandboxTenantResolver` · `SessionOwnerLookup` · `SandboxAdmission` · `SandboxEventListener` · `SandboxJanitor` · `SandboxExecutionEnvironmentProvider` | Application | 어셈블리 싱글턴 — `WorkspaceSandbox` 가 만들고 검사하고(§13.2) 닫는다(§4.1) | 앱 shutdown |
| 오케스트레이터 도구 | Agent | `ToolRegistry` | 상태 없음 |
| `SandboxWorkspace` 레코드, 원격 샌드박스, 공유 볼륨 | **Workspace** (영속) | `SandboxWorkspaceId` / `SandboxWorkspaceStore` | 명시적 close 또는 idle 만료(§10). CLOSED 레코드는 `closedRetention` 동안 남는다 — 명시적 close 의 것은 막는 툼스톤, idle close 의 것은 막지 않는 기록이다(§10.5) |
| 셸 상태 파일(cwd·환경 변수) | 샌드박스 generation | 샌드박스 안 `/workspace/.aimon-shell/` | generation 과 함께 사라진다. `exec:` 키의 파일은 `execShellIdle` 뒤 지운다(§9) |
| `SandboxBinding` · `SandboxExecutionEnvironment` | Execution | `ToolContext` | 실행과 함께 버린다. 원격 자원을 쥐지 않는다 — 코어 계약상 환경은 닫을 것이 없는 뷰다 |
| 프로바이더 연결 캐시 · shellKey 직렬화 락 | 노드 로컬 | `SandboxConnectionCache` | 앱 shutdown |

Workspace 수명은 4단계에 새로 끼우는 층이 아니다. **Session 과 같은 층의 영속 애그리게이트이되 키가
다르다** — 한 워크스페이스는 여러 세션과 세션 없는 실행(서브에이전트 포크, 스케줄 루틴)에 걸칠 수 있다.
그래서 다음이 성립한다.

- `LiveSession.close()` 는 샌드박스를 건드리지 않는다. 같은 워크스페이스를 다른 노드의 다른 세션이 쓰고 있을 수 있다
- `AgentRuntime.close()` 도 건드리지 않는다. 런타임은 여러 워크스페이스의 실행을 받는다
- 샌드박스를 끝내는 경로는 워크스페이스 close, idle 정책, 오케스트레이터 도구의 `SandboxStop`, janitor 조정
  (ORPHAN·STALE·FAILED-LEFTOVER·DUPLICATE, §10.4), 프로바이더 만료(§10.3)뿐이다. 공유 볼륨을 지우는 경로는
  워크스페이스 close 와 janitor 의 볼륨 조정뿐이다(§10.4)

---

## 4. 아키텍처

### 4.1 모듈

```
aimon-core (repo: aimon-core)
  at.aimon.core.environment        ExecutionEnvironment (new neutral SPI, §7)
  tools.file / tools.bash          resolve environment from ToolContext per execution
        ▲
        │ api
┌───────┴───────────────────────────────────────────────────────────────┐
│ aimon-sandbox                      (repo: aimon-sandbox)               │
│   (root)       WorkspaceSandbox (assembly) · SandboxSettings           │
│   workspace/   SandboxWorkspace · SandboxWorkspaceStore(I) · InMemory  │
│                SandboxWorkspaceManager · SandboxJanitor                │
│                SandboxAdmission(I) · SandboxEventListener(I)           │
│   binding/     SandboxBindingPolicy(I) · DefaultSandboxBindingPolicy   │
│                SandboxTenantResolver(I) · SessionOwnerLookup(I)        │
│                CallerResolver                                          │
│   provider/    SandboxProvider(I) · SandboxConnection(I) · specs       │
│                SharedVolumes(I) · SandboxConnectionCache               │
│   profile/     SandboxProfile · SandboxProfileRegistry                 │
│   environment/ SandboxExecutionEnvironment · SandboxFileSystem         │
│                SandboxShell · SandboxExecutionEnvironmentProvider      │
│                SandboxWorktrees                                        │
│   tool/        OrcaSandboxToolProvider (orchestrator tools only)       │
└───────────────────────────────────────────────────────────────────────┘
        ▲ api                     ▲ api                     ▲ api
┌───────┴───────────────────┐ ┌───┴───────────────────┐ ┌───┴──────────────────────────┐
│ aimon-sandbox-opensandbox │ │ aimon-sandbox-        │ │ aimon-sandbox-testkit        │
│   OpenSandboxProvider     │ │   store-jdbc          │ │   SandboxProviderContract    │
│   OpenSandboxProvider-    │ │   JdbcSandbox-        │ │   SandboxWorkspaceStore-     │
│     Config                │ │   WorkspaceStore      │ │     Contract                 │
│   VolumeReclaimer(I)      │ │   (§18-6)             │ │   LocalProcessSandbox-       │
│   -> com.alibaba.         │ │                       │ │     Provider (tests only)    │
│      opensandbox          │ │                       │ │   FaultInjectingSandbox-     │
│                           │ │                       │ │     Provider · ManualClock · │
│                           │ │                       │ │     ManualScheduler          │
└───────────────────────────┘ └───────────────────────┘ └──────────────────────────────┘
```

`aimon-sandbox` 는 코어를 `api` 로 의존한다. 공개 타입이 코어 SPI(`ExecutionEnvironmentProvider`,
`VirtualFileSystem`, `VirtualShell`)를 구현하고 코어 타입(`Principal`, `SessionId`)을 인자로 노출하기 때문이다.

**어셈블리는 루트 패키지의 `WorkspaceSandbox` 다.** §3.2 의 애플리케이션 싱글턴은 만드는 순서와 닫는 의무가 있고,
기동 시 검사(§13.2)는 그 전부를 한 번에 봐야 한다. 이 모듈은 DI 컨테이너를 가정하지 않으므로 `WorkspaceSandbox.builder()`
가 설정(`SandboxSettings` — §13.2 의 키 가운데 그 단계가 읽는 것만)과 프로바이더를 받아 검사하고, 싱글턴을 만들고,
`close()` 로 닫는다. 샌드박스는 닫지 않는다 — 워크스페이스 수명이다(§3.2). 스킬 셸 훅 거부(§12.1)에 쓸 파서도 여기서
준다(`WorkspaceSandbox.markdownSkillParser()`). 이후 Spring 스타터가 생기면 그 속성이 `SandboxSettings` 로 옮겨 담긴다.

`aimon-sandbox-testkit` 은 발행한다 — 이 저장소 밖의 프로바이더와 저장소 구현도 계약 스위트를 통과해야 하기
때문이다(§16). 계약 스위트는 JUnit 5 추상 클래스이므로 testkit 은 JUnit·AssertJ 를 `api` 로 싣는다.

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
SandboxConnection.run(ExecSpec)                    <- node-local connection cache; ExecSpec wraps the
  │                                                   command with the shellKey's state file (§9)
  ▼
OpenSandboxProvider  ──>  OpenSandbox API (/command)  ──>  execd in sandbox  ──>  bash
```

---

## 5. 도메인 모델

모두 불변 클래스 + 빌더다(코어 컨벤션). 상태 변경은 `with*()` 가 새 인스턴스를 돌려주고, 원자적 교체는
저장소의 CAS 가 책임진다.

### 5.1 `SandboxWorkspace`

| 필드 | 뜻 |
|------|-----|
| `id` | `SandboxWorkspaceId` — 불투명 문자열. 기본 정책은 루트 세션에서 결정론적으로 만든다(§8.2) |
| `owner` | `WorkspaceOwner` — `(tenantId, principalId)`. 바인딩 정책이 정하고(§8.3) 레코드를 만들 때 고정된다. 호출한 실행의 주체가 아니라 **워크스페이스의 소유자**다 |
| `state` | `OPEN` · `CLOSING` · `CLOSED` |
| `incarnation` | 레코드를 만들 때와 `reopen` 할 때마다 새로 뽑는 짧은 무작위 id. 샌드박스 키와 공유 볼륨 이름에 들어가, 같은 워크스페이스 id 로 다시 만들어진 워크스페이스가 옛 자원과 섞이지 않게 한다(§6.3, §10.5) |
| `sharedVolume` | `VolumeRef`? — 공유 볼륨 이름. 이름 규칙으로 정해지고(§6.3), 볼륨 자체는 첫 샌드박스 생성이 만든다(§6.1) |
| `stateSince` · `closeCause`? · `retainVolumeUntil`? · `retainedVolumes` | CLOSING · CLOSED 로 바뀐 시각, close 원인(`explicit` · `idle`), 보존할 볼륨의 기한, reopen 뒤에도 기한까지 남길 옛 incarnation 볼륨 목록. janitor 가 멈춘 close 를 이어받고(§10.4) 툼스톤과 보존 볼륨을 지우는(§10.5) 기준 |
| `slots` | `Map<String, SandboxSlot>` |
| `quota` | `WorkspaceQuota` — 생성 시점의 설정을 복사해 둔다(설정이 바뀌어도 진행 중인 워크스페이스는 흔들리지 않는다) |
| `version` | `long` — CAS 용 |
| `createdAt` · `lastActivityAt` | |

`SandboxWorkspace` 는 sandbox id 를 "하나" 갖지 않는다. 초안 두 개가 모두 강조한 점이고 그대로 따른다.

### 5.2 `SandboxSlot`

| 필드 | 뜻 |
|------|-----|
| `name` | 슬롯 이름 |
| `profile` | 프로파일 이름. 슬롯이 처음 만들어질 때 고정된다. 이후 다른 프로파일을 요구하는 바인딩은 거부된다(§8.3) |
| `profileHash` | 현재 generation 을 만들 때 쓴 프로파일 내용의 SHA-256. `permanent` 실패를 "프로파일이 바뀔 때까지" 재시도하지 않는다는 규칙(§10.1)이 비교할 대상이다 |
| `state` | `PROVISIONING` · `RUNNING` · `PAUSED` · `TERMINATED` · `FAILED` |
| `generation` | 새로 프로비저닝할 때마다 +1. FAILED 에서 재시도할 때도 +1 이다 |
| `provisioning` | `(since, nodeId)`? — PROVISIONING 에 들어간 시각과 그 노드. 인계(§10.1)의 기준이다 |
| `providerRef` | `ProviderSandboxRef`? — `(provider, providerSandboxId)`. OpenSandbox 의 id 다 |
| `seeded` | `boolean` — 이 generation 의 seed(§11.3)가 끝났는가. RUNNING 이어도 false 면 다음 `connect` 가 seed 를 다시 돈다 |
| `missingSince`? | janitor 가 프로바이더에서 이 샌드박스를 처음 못 본 시각. 다시 보이거나 generation 이 바뀌면 지운다(§10.4) |
| `failure`? | `(at, kind, step, reason, attempts, profileHash)`. kind 는 `transient` · `permanent`, step 은 실패한 단계(`create` · seed 의 각 검사 · `clone`)다(§10.1) |
| `lastActivityAt` · `lastActiveAt`? · `lostAt`? | `lastActiveAt` 은 슬롯이 마지막으로 RUNNING·PAUSED·PROVISIONING 이던 시각 — `closeAfter` 의 기준(§10.2) |

셸 세션 참조는 레코드에 없다. 셸 상태는 샌드박스 안의 파일이다(§9). 그래서 레코드 크기는 슬롯 수에만 비례한다.

**역할 enum(`PRIMARY`/`DEVELOPMENT`/`REVIEW`/`TEST`…)을 두지 않는다.** 초안의 역할은 두 가지 일을 했다 —
라우팅 키, 그리고 보안 정책 선택이다. 전자는 슬롯 이름이, 후자는 프로파일이 더 잘한다. enum 은 특정
개발 프로세스(개발→리뷰→테스트)를 SPI 에 굳힌다. 실험 슬롯 셋(`exp-a`, `exp-b`, `exp-c`)이나 브라우저·GPU
슬롯을 만들 때마다 enum 을 고치거나 `CUSTOM` 으로 뭉개야 한다. 슬롯 + 프로파일이면 둘 다 설정 문제다.

상태 전이:

```
            ensure()                  idle(pauseAfter)
(absent) ──────────> PROVISIONING ──> RUNNING <──────────> PAUSED
                         │              │        resume()     │
                    fail │              │ stop / idle(terminateAfter) / lost
                         ▼              ▼                     │
                       FAILED       TERMINATED <──────────────┘
                         │              │
                         │ ensure() after failureBackoff -> generation+1
                         └──────────────┴──────────> PROVISIONING
```

FAILED 에서 같은 generation 으로 재시도하는 길은 없다. `permanent` 실패는 `failureBackoff` 가 지나도 재시도하지 않는다(§10.1). 같은 키로 다시 만들면 멱등 `create` 가 실패한 생성이 남긴
샌드박스를 되돌려 줄 수 있고, janitor 가 옛 FAILED 스냅숏을 보고 그것을 지울 수도 있다. generation 을 올리면 남은
것은 STALE 로 깔끔하게 회수된다(§10.4).

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
    void delete(SandboxWorkspaceId id, long expectedVersion);           // expired CLOSED records (§10.5); CAS
    List<SandboxWorkspace> scan(WorkspaceScan scan);                    // idle / state / tenant filters, paged
}
```

`delete` 는 janitor 가 기한이 지난 CLOSED 레코드를 지울 때(§10.5) 쓴다. 버전으로 보호하므로 그 사이 들어온 `reopen` 을
지우지 않는다. 레코드가 이미 없으면 성공이다. `update` 도 레코드가 없으면 `StaleVersionException` 이다.

**version 은 저장소가 매기고, 지운 레코드의 version 을 다시 쓰지 않는다.** 처음 보는 id 는 1 에서 시작하지만, 지웠다가
다시 만든 id 는 예전에 가졌던 어느 version 보다 큰 값에서 시작한다 — 지워진 레코드를 읽어 둔 호출자의 CAS 가 새 레코드에
맞아떨어지는 ABA 를 막는다. `update` 는 `next` 가 들고 온 version 을 무시하고, id 가 다른 레코드는 거부한다.
**저장소가 돌려준 것이 저장된 것이다.** `update`·`createIfAbsent` 의 반환값과 나중의 `find` 가 같아야 한다. 저장소는
`Instant` 를 자기 정밀도로 줄여 저장해도 되지만(적어도 밀리초) 호출자는 넘긴 값이 아니라 돌려받은 값과 비교한다.

초안은 `WorkspaceRepository` · `SandboxInstanceRepository` · `AgentSandboxSessionRepository` 셋을 제안했다.
셋으로 나누면 "슬롯을 TERMINATED 로 바꾸면서 그 슬롯의 공유 볼륨 참조를 정리한다" 같은 한 가지 변경이 세 저장소
쓰기가 되고, 중간에 죽으면 어긋난다. 워크스페이스 하나의 크기는 작다(슬롯 수 개, `maxSlots` 로 제한) — 통째로
CAS 하는 비용이 트랜잭션 조율보다 싸다.

**CAS 충돌은 다시 읽고 다시 판단한다.** version 이 워크스페이스에 하나이므로 다른 슬롯의 전이나 heartbeat 도 같은
version 을 올린다. 그래서 모든 쓰기는 `StaleVersionException` 을 받으면 레코드를 다시 읽고, 자기 조건이 여전히
성립하는지 확인한 뒤 다시 시도한다(상한 `casRetries`, 기본 5). 충돌했다고 쓰기를 버리는 곳은 없다.

기본 구현은 `InMemorySandboxWorkspaceStore` 이고 **단일 노드 전용**이다. 멀티 노드 배포에는 영속 구현
(`aimon-sandbox-store-jdbc`)이 필요하며, 이것은 구현 순서의 독립 단계다(§18). 그 단계가 끝나기 전까지
§1.2 의 "멀티 노드" 목표는 달성되지 않은 것으로 본다. 옛 설계처럼 인터페이스만 있고 구현이 없는 상태를
"멀티 인스턴스 대비"라고 부르지 않는다.

`InMemory` 저장소로 도는 노드가 재시작하면 레코드가 모두 사라진다. 그 순간 프로바이더에 남은 샌드박스는
조정 표(§10.4)에서 ORPHAN 이 되어 `orphanGrace` 뒤 회수된다. 단일 노드 배포에서는 이것이 의도된 동작이다 —
재시작을 넘어 샌드박스를 이어 쓰려면 영속 저장소를 쓴다.

`lastActivityAt` 갱신은 **스로틀**한다. 마지막 기록보다 `activityWriteInterval`(기본 30초) 이상 지났을
때만 쓴다. 명령마다 쓰면 저장소가 명령 처리량을 따라가야 한다. 쓸 때는 위 규칙대로 충돌하면 다시 읽고
`max(기존, 새 값)` 으로 재시도한다 — 활동 기록이 유실되면 janitor 가 도는 명령을 멈출 수 있기 때문이다.

활동은 명령의 **시작**만이 아니다. 명령(백그라운드 포함)이 도는 동안 그 명령을 가진 노드가
`activityWriteInterval` 마다 `lastActivityAt` 기록과 프로바이더 만료 연장(`extendExpiry`, §10.3)을 반복하고,
셸 락을 쥔 포그라운드 명령이면 샌드박스 안 락의 heartbeat 파일도 갱신한다(§9 — 락을 잡지 않는 백그라운드 명령은
갱신하지 않는다. 갱신하면 죽은 노드가 쥔 락이 살아 있는 것처럼 보인다)(heartbeat). 그래야 20분짜리 빌드가 도는 동안 도구 호출이 없어도
janitor 가 `pauseAfter` 로 멈추지 않는다. 카운터를 레코드에 두지 않는 이유는, 노드가 죽으면 카운터가 내려가지
않기 때문이다 — heartbeat 는 노드와 함께 멈추고, 그러면 idle 정책이 정상적으로 이어받는다.

**백그라운드 명령의 heartbeat 에는 상한이 있다.** 프로파일의 `backgroundHeartbeatLimit`(기본 1시간)이 지나면 그
명령의 heartbeat 를 멈춘다. 명령은 계속 돌지만 더는 샌드박스를 깨워 두지 않으므로, 개발 서버 하나가 슬롯을 하루 동안
붙잡는 일이 없다. 그 뒤로는 idle 정책이 평소대로 pause·terminate 한다. 포그라운드 명령은 명령 타임아웃이 상한이므로
따로 두지 않는다.

**명령을 시작하기 전에 슬롯이 RUNNING 인지 다시 본다.** 명령마다 레코드를 읽고(쓰기는 스로틀해도 읽기는 매번),
활동을 기록했다면 그 CAS 가 성공한 레코드를 기준으로, 슬롯이 RUNNING 이고 generation 이 연결 캐시와 같을 때만 명령을
보낸다. 그 사이 janitor 가 PAUSED 로 바꿨다면 `connect` 의 resume
경로를 다시 탄다. 연결 캐시만 보고 멈춘 샌드박스에 명령을 보내지 않는다.

---

## 6. 프로바이더 SPI

### 6.1 계약

```java
public interface SandboxProvider extends AutoCloseable {
    ProviderCapabilities capabilities();

    ProviderSandboxRef create(CreateSpec spec);            // best-effort idempotent on spec.key() (§6.3)
    Optional<ProviderSandbox> status(ProviderSandboxRef ref);   // state + labels; empty when absent
    void pause(ProviderSandboxRef ref);                     // requires PAUSE_RESUME
    void resume(ProviderSandboxRef ref);                    // SandboxNotFoundException when absent
    void extendExpiry(ProviderSandboxRef ref, Instant until);   // forward only; SandboxNotFoundException
    void destroy(ProviderSandboxRef ref);                   // idempotent: absent is success
    List<ProviderSandbox> list(Map<String, String> labels); // every page; ref + state + labels

    Optional<SharedVolumes> sharedVolumes();                // present iff SHARED_VOLUME

    SandboxConnection connect(ProviderSandboxRef ref);
}

public interface SharedVolumes {
    List<ProviderVolume> list(Map<String, String> labels);  // ref + labels + whether still mounted
    void delete(VolumeRef ref);                             // idempotent; VolumeInUseException while mounted
}

public interface SandboxConnection extends AutoCloseable {
    RunningCommand run(ExecSpec spec, OutputSink sink);     // one process group per call
    SandboxFiles files();
}

public interface RunningCommand {
    ExecOutcome await(Duration timeout) throws InterruptedException;  // exit code, stdout/stderr, truncation flags;
                                                                     // past the timeout: kill() and timedOut
    void kill();                          // SIGTERM to the process group, then SIGKILL after grace
}

public final class ProviderCapabilities {                // advertised() + maxExpiry()
    Set<Capability> advertised();
    Optional<Duration> maxExpiry();                      // the server's max expiry; terminateAfter must not exceed it
}
```

`ExecSpec` 는 명령 문자열(`bash -c` 로 도는 스크립트) · `workingDirectory` · `environment`(프로파일의 `env` 만 — 명령별
환경은 §9 의 서브셸로. 예외는 스테이징 검증이 `PATH` 를 고정하는 것 하나다, §11.1) · 타임아웃(프로바이더 쪽 최후 방어선) ·
`maxCaptureBytes`(stdout · stderr 각각의 상한) 를 담는다. `ProviderCapabilities.maxExpiry()` 는 §13.2 의
`terminate-after ≤ max-expiry` 검사가 읽는다 — 서버 설정이라 매니저가 볼 수 있는 통로는 SPI 뿐이다. 명령은 **execd 의 uid** 로 돈다. `/command` 의 uid 인자는 쓰지 않는다 — files
API 에는 uid 인자가 없으므로, 명령만 다른 uid 로 돌리면 파일 도구가 쓴 파일과 소유자가 어긋난다(§13.3). 프로바이더는 stdout 과 stderr 를 **구분해서** 돌려주고, `kill()` 은
그 호출의 프로세스 그룹만 끝낸다. 지속 셸 세션은 SPI 에 없다 — `SandboxShell` 이 이 위에서 셸 상태를 이어 붙인다(§9).

공유 볼륨은 따로 만들지 않는다. `CreateSpec` 이 볼륨 마운트(이름 · `rw`/`ro` · 크기 · storage class · access mode ·
없으면 만든다)를 선언하고, 워크스페이스의 첫 샌드박스 생성이 볼륨을 만든다. 삭제만 `SharedVolumes` 가 맡는다 —
샌드박스 생성과 달리 볼륨 삭제는 어느 샌드박스 요청에도 딸려 있지 않기 때문이다.

`SandboxFiles` 는 `read(path, range)` · `write(path, InputStream, length, mode)` · `stat` · `list(dir,
recursive, limit)` · `createDirectories` · `delete` · `move` 를 갖는다. 전부 샌드박스 안 **절대 경로**를 받는다. `list` 는
이름이 아니라 항목마다의 `stat`(디렉터리 여부 포함)을 돌려준다 — VFS 목록이 디렉터리를 가려야 하는데, 이름만 주면
항목마다 `stat` 호출이 하나씩 더 든다. 실패는 코어 VFS 예외(`FileNotFoundException` 등)이고, 샌드박스가 없으면
`SandboxNotFoundException` 이다. 프로바이더 호출의 실패는 `SandboxProviderException` 이며 `kind`(`TRANSIENT` ·
`PERMANENT`)가 §10.1 의 실패 분류다. 광고하지 않은 capability 의 호출은 `UnsupportedOperationException` 이다. `stat` 은 코어
`FileMetadata` 의 변경 감지 계약을 지켜야 한다 — 내용이 바뀌면 mtime(밀리초 이상 해상도) 또는 etag 가 반드시 바뀐다.
해상도가 초 단위인 프로바이더는 etag 를 줘야 한다(§7).

### 6.2 옛 `SandboxBackend` 와 다른 점

| 옛 계약 | 새 계약 | 이유 |
|---------|--------|------|
| `ensure(identifier, ttl)` — 조회+생성+TTL 갱신을 한 메서드에 | `create` (최선 노력 멱등) · `status` · `extendExpiry` 로 분리 | "있으면 재사용"은 워크스페이스 레코드가 판단한다. 프로바이더는 레코드가 준 키로 만들기만 하고, 중복은 조정이 치운다 |
| `exec` 블로킹, 취소 없음 | `run` 이 `RunningCommand` 를 돌려주고 `kill()` 이 있다 | `VirtualShell` 계약(데드라인·인터럽트 시 프로세스를 죽인다)을 원격에서 지키려면 취소 채널이 필요하다 |
| 볼륨 없음 | 생성은 `CreateSpec` 의 마운트 선언, 삭제는 `SharedVolumes` | OpenSandbox 에는 볼륨 생성 API 가 없고 샌드박스 생성 요청이 볼륨을 만든다(§6.4) |
| 출력은 끝나고 한 번에 | `OutputSink` 로 흘려보낸다 | 지금은 `SandboxShell` 이 모아서 돌려주지만, 스트리밍 도구 출력이 생길 때 SPI 를 고치지 않아도 된다 |
| `copyArtifacts` / `copyToSandbox` (tar) | `files()` | 파일 도구가 샌드박스 파일 시스템을 직접 보므로 tar 왕복이 필요 없다 |
| `reapExpired()` | 없음 | 만료 판단은 워크스페이스 레코드와 프로바이더 만료의 일이다(§10) |
| `count()` | `list(labels)` | 조정(reconciliation)에는 개수가 아니라 목록이 필요하다 |

### 6.3 멱등 생성

`CreateSpec.key` 는 `"{deployment}/{workspaceId}/{incarnation}/{slot}/{generation}"` 이다. 프로바이더는 이 키로 라벨
(`aimon.at/sandbox-key`)을 붙이고, 같은 키로 이미 만들어진 샌드박스가 있으면 새로 만들지 않고 그것을
돌려준다. generation 과 incarnation 이 키에 들어가므로 종료된 옛 샌드박스를 — 같은 워크스페이스 id 로 다시 만들어진
워크스페이스에서도 — 잘못 되살리는 일은 없다.

**이 멱등은 최선 노력이다.** OpenSandbox 의 생성 API 에는 멱등 키가 없으므로 프로바이더는 "라벨로 조회한 뒤
생성"할 수밖에 없고, 둘 사이는 원자적이지 않다. 두 노드가 같은 키로 동시에 `create` 하면(§10.1 의 인계에서 원래
노드가 느렸을 뿐 살아 있던 경우) 샌드박스가 둘 생길 수 있다. 정확성은 레코드가 지킨다 — 레코드의 `providerRef`
하나만 쓰이고, 같은 키의 다른 샌드박스는 조정 표의 DUPLICATE 로 회수된다(§10.4). 레코드 CAS 가 슬롯당 PROVISIONING
소유자를 하나로 만들므로 이 경합은 인계 때만 생긴다.

**라벨 값은 인코딩한다.** OpenSandbox 는 metadata 를 K8s 라벨 규칙(영숫자와 `-_.`, 63자 이하)으로 검사한다.
`workspaceId`(`ws:{sessionId}`)와 키에는 `:` 와 `/` 가 들어가므로 그대로 쓰면 첫 `create` 가 거부된다. 잘라 쓰면 다른
워크스페이스와 겹친다. 그래서 식별 라벨은 원문의 SHA-256 을 base32 로 적은 앞 32자(160비트)를 쓴다. 원문은
레코드에만 둔다. 조정의 `list(labels)` 필터도 같은 인코딩을 쓴다.

| 라벨 | 값 |
|------|-----|
| `aimon.at/managed` | `true` |
| `aimon.at/deployment` | deployment 이름(설정에서 라벨 규칙으로 검증) |
| `aimon.at/workspace` | `h(workspaceId)` |
| `aimon.at/sandbox-key` | `h(key)` |
| `aimon.at/incarnation` | 워크스페이스의 incarnation(§5.1). 무작위 영숫자라 그대로 쓴다 |
| `aimon.at/slot` · `aimon.at/generation` | 슬롯 이름(§3.1 규칙이 라벨 규칙을 만족한다) · 정수 |
| `aimon.at/owner` | `h(tenantId)` — principal id 같은 개인 식별 정보를 공유 인프라에 평문으로 남기지 않는다 |

**공유 볼륨은 이름으로 식별한다.** OpenSandbox 의 볼륨 선언(`volumes[].pvc`)에는 라벨 필드가 없고, Docker 의 named
volume 은 만든 뒤 라벨을 바꿀 수 없다. 그래서 볼륨은 라벨이 아니라 이름 규칙
`aimon-{h(deployment)[:8]}-{h(workspaceId)[:16]}-{incarnation}`(DNS 라벨 63자 이내)으로 식별하고,
`SharedVolumes.list` 는 `aimon-{h(deployment)[:8]}-` 접두어로 찾는다. 보존 여부 같은 상태는 볼륨이 아니라 레코드에
둔다(§10.5). `VolumeReclaimer` 는 이 접두어 밖의 볼륨을 보지 않는다.

**돌려받은 샌드박스는 대조한 뒤에만 쓴다.** `create` 가 기존 샌드박스를 돌려주거나 `status` 로 다시 찾은 경우,
매니저는 그 샌드박스의 workspace · sandbox-key · generation · owner 라벨이 레코드와 같은지 확인한다. 다르면 쓰지
않고 슬롯을 FAILED(`permanent`, 단계 `labels`)로 둔다. 인코딩 버그나 라벨 충돌이 남의 샌드박스를 넘겨주는 경로를
막는다. 대조에 실패한 샌드박스는 남의 것일 수 있으므로 지우지도 않는다. 라벨 계산과 대조는 매니저 쪽 일이라
`CreateSpec` 을 만드는 3단계에 이미 들어 있다(`SandboxLabels`) — 4단계에 남는 것은 OpenSandbox 가 그 라벨을 실제로
받아들이는지의 검증이다.

`deployment` 는 한 워크스페이스 저장소를 공유하는 AIMON 노드 집합의 이름이다(설정 필수, 기본값 없음).
OpenSandbox 서버 하나를 여러 애플리케이션이나 여러 개발자 머신이 같이 쓰면 `managed=true` 만으로는
남의 샌드박스와 내 고아를 구분할 수 없다. janitor 는 **자기 deployment 라벨이 붙은 샌드박스만** 조정한다.
기본값을 두지 않는 이유는, 두 배포가 같은 기본값을 쓰면 라벨이 없는 것과 같기 때문이다.

### 6.4 capability 와 OpenSandbox 대응

| capability | 뜻 | OpenSandbox 대응 |
|-----------|-----|-----------------|
| `EXEC` | 명령 실행: cwd · env · stdout/stderr 구분 · 프로세스 그룹 kill | `/command` API(cwd·envs·background 지원). 세션 API(`RunInSession`)는 쓰지 않는다 — 명령 하나를 죽이면 세션 전체가 닫히고, stdout/stderr 가 합쳐지며, 출력 상한과 uid 지정이 없다(§9) |
| `FILES` | 파일 API | files API (read/write/search/delete). `stat` 의 mtime 해상도와 해시 제공 여부는 구현 시 확인 |
| `PAUSE_RESUME` | 일시 정지 | pause/resume. 일시 정지 중에도 만료 시각이 흐르는지, K8s 런타임에서 pause 가 파드를 다시 띄우는지는 구현 시 확인 |
| `EXPIRY` | 프로바이더 측 절대 만료 시각 + 연장 | `timeout` · `renew-expiration`(앞으로만 늘릴 수 있다). 서버의 `max_sandbox_timeout_seconds` 가 상한이다. idle 타임아웃이 아니다 |
| `SHARED_VOLUME` | 여러 샌드박스에 RWX 볼륨 마운트, 샌드박스별 읽기 전용 마운트, 볼륨 삭제 | 생성은 `volumes[].pvc.createIfNotExists`(기본 access mode 가 `ReadWriteOnce` 이므로 RWX 를 명시). 삭제 API 는 없다 — `OpenSandboxProvider` 에 `VolumeReclaimer`(K8s API 또는 Docker 볼륨 API)를 설정해야 광고한다 |
| `EGRESS_POLICY` | 목적지 허용 목록, 명시하지 않은 목적지는 차단 | `networkPolicy` + `defaultAction: deny`. 서버 egress 모드가 `dns+nft` 여야 IP/CIDR 까지 막힌다. 모드는 클라이언트가 알 수 없으므로 운영자가 `egress-enforcement: dns+nft` 로 선언해야 광고한다 |
| `CREDENTIAL_INJECTION` | 샌드박스에 비밀을 노출하지 않고 egress 에서 주입. host · 경로 prefix · 메서드로 범위를 좁힘 | credential vault(`dns+nft` 필요). 범위 지정 수준은 구현 시 확인 |
| `NETWORK_ISOLATION` | 샌드박스 사이(east-west)와 샌드박스 → OpenSandbox 서버·K8s API·클러스터 내부 트래픽 차단 | OpenSandbox 가 강제하지 않으므로 운영자가 네트워크 정책을 걸고 `network-isolation: declared` 로 선언해야 광고한다. seed 가 서버 엔드포인트로의 연결이 실패하는지 자가 점검한다(§11.3). Docker 런타임의 동작은 구현 시 확인 |
| `RUNTIME_CLASS` | 요청한 runtimeClass(gVisor/Kata)로 실제 실행 | K8s 런타임의 `runtimeClassName`. Docker 런타임은 광고하지 않는다 |
| `HARDENED_SECURITY_CONTEXT` | 비루트 · 권한 상승 금지 · capability 제거 · 서비스 계정 토큰 미마운트 | 파드 SecurityContext. 서버가 요청을 무시하지 않는지 구현 시 확인. seed 가 결과를 다시 검사한다(§11.3) |
| `SNAPSHOT` · `FORK` | 스냅숏·복제 | snapshot (fork 는 snapshot 위에 조립) |

"구현 시 확인"은 SPI 를 굳히기 전 스파이크 단계(§18-2)에서 닫는다. 확인되지 않은 capability 는 광고하지 않는다.

`SandboxWorkspaceManager` 는 프로파일이 요구하는 capability 를 프로바이더가 광고하지 않으면 **기동 시점에**
거부한다. egress 를 막으라는 프로파일이 egress 정책 없는 프로바이더 위에서 조용히 열린 채로 도는 일은
없어야 한다. 프로파일이 요구하는 capability 는 설정에서 유도한다 — `egress` 가 있으면(빈 목록 포함) `EGRESS_POLICY`,
`credentials` 가 있으면 `CREDENTIAL_INJECTION`, `runtimeClass` 가 있으면 `RUNTIME_CLASS`, `sharedAccess` 가 `none` 이
아니면 `SHARED_VOLUME`, `pauseAfter` 가 있으면 `PAUSE_RESUME`. `EXPIRY` · `HARDENED_SECURITY_CONTEXT` ·
`NETWORK_ISOLATION` 은 모든 프로파일이 요구한다. 로컬 개발처럼 격리를
일부러 풀려면 프로파일에 `insecure-allow: [HARDENED_SECURITY_CONTEXT, …]` 를 적어야 하고, 기동 로그에 경고가 남는다.

프로바이더는 `EGRESS_POLICY` 를 광고했더라도 egress 를 **생략하지 않는다.** OpenSandbox 는 `networkPolicy` 가 없거나
비면 전부 허용하므로, 프로파일의 `egress: []` 는 `defaultAction: deny` 와 빈 허용 목록으로 명시해 보낸다.

**왜 백엔드를 OpenSandbox 하나로 줄이는가.** 옛 Docker/K8s 백엔드 두 개는 같은 수명 관리를 두 번 구현했고
(이름 충돌 catch, 라벨/애노테이션, Ready 대기), 네트워크 정책·자격 증명·pause 는 둘 다 없었다. 그것을
채우는 일은 OpenSandbox 가 이미 한 일을 다시 하는 것이다. OpenSandbox 는 Docker 런타임으로 로컬에서도
돌기 때문에, 로컬 개발용으로 자체 Docker 백엔드를 남길 이유도 없다. SPI 는 남긴다 — 테스트용 로컬
프로바이더(§16)와, 언젠가 생길 다른 인프라(e.g. `kubernetes-sigs/agent-sandbox`)를 위해서다.

SDK 의 `SandboxPool`(warm pool)은 SPI 에 올리지 않는다. 풀은 `create` 의 구현 세부이고, 실험적 API 에 공개
계약이 묶이면 SDK 가 바뀔 때마다 SPI 가 흔들린다. 풀 모드는 `networkPolicy` · `volumes` · credential proxy 와 함께 쓸
수 없으므로 **egress 정책·공유 볼륨·자격 증명이 없는 프로파일에만** 쓴다. 풀에서 꺼낸 샌드박스에 §6.3 의 라벨을 붙일
수 없다면 그 샌드박스의 소속은 레코드만 기억하게 된다. 그때는 풀 샌드박스에 `aimon.at/pool=true` 를 붙여 조정
대상에서 빼야 한다.

---

## 7. 코어 통합 — `ExecutionEnvironmentProvider` 를 구현한다

코어 쪽 변경은 aimon-core 의
[실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)
가 정본이고, **이미 구현되었다**(aimon-core PR #195, #196). 구현이 설계에서 벗어난 점은 그 저장소의
`execution-environment-implementation.md` §10, 남은 항목은 `docs/backlog/execution-environment-open-items.md`
(EE-*)에 있다. 이 모듈이 쓰는 코어 기능은 모두 들어가 있다 — 경로 규칙 래퍼의 공개 팩토리
`VirtualFileSystems.withPathRules`(§11.1), notice 를 싣는 백그라운드 `Bash`, 포크 요청의 `EnvironmentRequest.fork()`
(`ForkDefinition` — 서브에이전트 이름과 속성)와 `definitionAttributes()`. 이 모듈이 기대는 열린 항목은 둘이다 —
EE-42(워크플로 스크립트의 인라인 서브에이전트가 속성을 싣지 못한다, §18-5 의 선행 조건)와 EE-30(부모 환경 없는
워크플로 러너, 이 모듈이 사용 불가로 답하는 것으로 충분하다 — §8.2). 요지는 다음과 같다.

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
| `shell()` | `SandboxShell` — shellKey 의 셸 상태를 이어 붙인 셸(§9) |
| `descriptor()` | 프로파일에 **선언한** platform·OS·shellName(§13.1), `workingDirectory = root`, notes 에 "isolated sandbox" · egress 요약 · 상대 경로 기준(§11.1). `resolve()` 는 원격 자원을 만들지 않으므로 이미지에 물어볼 수 없다 — 선언값을 쓰고, 프로비저닝의 seed 단계가 실제 이미지와 대조해(platform 은 `uname -s`, osVersion 은 선언했을 때만 `uname -sr` 에 글롭으로) 다르면 슬롯을 FAILED 로 둔다. notes 에는 셸 상태가 cwd 와 export 된 변수만 잇는다는 사실(§9)도 넣는다. 선언값이라 재생성을 넘어 같은 값이고, 프롬프트 캐시가 흔들리지 않는다(코어 §10) |
| `contentSearch()` | 샌드박스 안에서 `rg --json` 한 번. 이미지 계약(§13.3)이 ripgrep 을 요구하는 이유다 |
| `durable()` | `false` — 코어가 artifact 를 제어 저장소로 복사한다(§11.5) |
| `stage(resource)` | `/workspace/.aimon-staged/{name}/{contentKey}/` 로 materialize. 샌드박스 안의 `.staged` 마커가 있고 **사본의 내용 해시가 `contentKey` 와 맞을 때만** 생략한다. 스테이징 영역은 **파일 도구에 읽기 전용**이다(코어 §4.4, 이 문서 §11.1) |
| `isolate(branchKey)` | `git worktree add /workspace/.worktrees/{branchKey}` 후 `root` 만 바꾼 환경(§11.2). `root` 가 git 저장소가 아니면(seed 없는 프로파일) 비어 있음을 돌려주고, 코어는 그 워크플로 단계를 거부한다(C30) |
| `fork()` 가 있는 요청(포크) | 부모와 같은 워크스페이스, shellKey 는 `exec:{executionId}`. 슬롯은 기본적으로 부모와 같고, 바인딩 정책이 `ForkDefinition` 의 속성을 보고 바꿀 수 있다(§8.2). 부모 환경이 없거나, 샌드박스 환경이 아니거나, 사용 불가면 포크도 사용 불가 |
| `ShellCommandResult.notices()` | 셸 상태 소실, generation 변경, 샌드박스 소실 후 재생성, 앞 노드가 남긴 명령의 정리를 알린다 |

코어의 파일 stamp 검사(읽은 뒤 바뀐 파일에 쓰기 거부)는 샌드박스에서 특히 중요하다. 같은 슬롯을 여러 실행이
공유할 때 다른 실행이 파일 도구로 바꾼 경우와 **셸이 바꾼 경우를 모두 잡는다.** 실제 파일 상태와 비교하기
때문이다. 초안의 `expectedRevision` 은 파일 도구 경로만 추적하고 셸 경로는 놓친다(초안 v0.3 이 스스로
인정한 한계다). 프로바이더 `files().stat()` 은 그래서 §6.1 의 변경 감지 계약(밀리초 이상 mtime 또는 etag)을
지켜야 하고, 계약 스위트가 그것을 검사한다(§16). `/shared` 가 NFS 계열 RWX 볼륨이면 속성 캐시 때문에 mtime 이
늦게 보일 수 있으므로, `SandboxFileSystem` 은 `/shared` 아래 경로에 대해 **항상 etag(내용 해시)** 를 준다.
OpenSandbox files API 가 해시를 주지 않으면 stat 마다 `sha256sum` 을 exec 하게 되므로, 그 비용은 §6.4 의 확인
항목이다.
stamp 검사는 확인과 쓰기 사이에 틈이 있는 최선 노력 장치다 — 원자적 비교-후-쓰기가 아니다.

---

## 8. 바인딩 — 어느 실행이 어느 샌드박스를 쓰는가

### 8.1 계약

```java
public interface SandboxBindingPolicy {
    SandboxBinding bind(BindingContext ctx);                       // root requests: workspace, owner, slot, profile

    default SlotChoice forkSlot(SandboxBinding parent, ForkDefinition fork) {   // forks: slot and profile only
        return SlotChoice.fromAttributes(fork.attributes(), parent);
    }
}
```

`BindingContext` 는 제공자가 코어의 `EnvironmentRequest` 에서 옮겨 담는다: `Agent`? · `AgentRuntimeId` ·
`SessionId`? · `ExecutionId`? · `invokingSessionId`? · `Principal`? · `definitionAttributes()`. 코어가 채우는 조합은
넷이다.

| 요청 | 채워지는 것 | 이 모듈의 처리 |
|------|-----------|--------------|
| 메인 턴 | 에이전트 · 세션 · 주체 | `bind` |
| 스케줄 루틴 | 에이전트 · 실행 id · 주체 | `bind` |
| 포크(부모 환경 있음) | 실행 id · invokingSessionId · 주체 · `parent` · `fork()` | 부모 바인딩을 물려받고 `forkSlot` 만 묻는다 |
| 포크(부모 환경 없음) | 실행 id · 주체 · `fork()`, `parent` 없음 | **사용 불가.** 부모 환경 없는 런타임 범위 워크플로 러너의 단계가 그렇다(EE-30) |

포크인지는 `parent` 가 아니라 **`fork().isPresent()`** 로 판단한다. `parent` 로 판단하면 부모 없는 포크가 "실행 id 가
있는 루트 요청"으로 보여 단계마다 `ws:{executionId}` 로 빈 워크스페이스와 새 샌드박스를 만들고 쿼터를 따로 쓴다.

워크플로 격리 브랜치는 정책을 타지 않는다 — 코어가 부모 환경의 `isolate()` 로 만든다(§11.2). 그래서 `branchKey` 는
바인딩에 쓰지 않는다.

바인딩을 만드는 것은 **이 정책뿐**이다. 포크도 슬롯은 정책의 `forkSlot` 이 고르므로, 애플리케이션이 주입한 정책은
포크에도 규칙(테넌트별 슬롯 제한 등)을 걸 수 있다. 다만 포크의 워크스페이스·owner·root 는 정책이 아니라 제공자가
부모에서 강제로 물려준다 — 정책이 포크를 다른 워크스페이스로 보낼 수 없다. 도구 인자는 바인딩에 들어가지 않는다.
그래서 프롬프트 인젝션으로 모델이 다른 워크스페이스의 id 를 불러도 닿을 경로가 없다.

정의의 속성은 코어가 정의 파일의 `attributes:` 블록을 점 표기 키로 평탄화한 것이다(`sandbox.slot`,
`sandbox.profile`). `definitionAttributes()` 는 포크면 서브에이전트의, 아니면 에이전트의 속성을 준다.

### 8.2 기본 정책

**포크는 부모의 워크스페이스를 물려받는다.** 부모 환경의 바인딩에서 workspaceId · owner · root 를 그대로 물려받고
shellKey 만 `exec:{executionId}` 로 바꾼다. `caller` 는 포크 요청의 principal 이고, 요청에 principal 이 없으면 부모의
`caller` 다 — 코어의 스킬 포크 경로(`SubagentBackedSkillForkExecutor`)는 principal 을 넘기지 않는데, 포크는 부모 실행을
대신해 도는 것이고 부모의 `caller` 는 이미 주체 검사를 통과했다. 포크가 받은 부모 환경 자체가 그 워크스페이스를 쓸
자격이다. 슬롯은 정책의 `forkSlot` 이 정한다 — 기본 구현은 `ForkDefinition` 의
`sandbox.slot` 속성이 있으면 그것, 없으면 부모의 슬롯이다. 슬롯이 부모와 다르면 root 는 그 슬롯의 `/workspace/repo`
다. 포크가 몇 단계로 중첩되어도, 부모가 세션 없는 실행(스케줄 루틴)이어도 같은 워크스페이스에 머문다.
`invokingSessionId` 로 워크스페이스를 다시 계산하면 세션 없는 부모의 포크가 자기 `executionId` 로 새 워크스페이스를
만들게 된다.

부모가 없거나 `SandboxExecutionEnvironment` 가 아니면(사용 불가 환경, 다른 제공자의 환경) 포크도 **사용 불가**로
돌려준다. 부모를 만들지 못한 이유 — 주체 거부(§8.3), 쿼터, 프로바이더 장애 — 를 포크가 정책으로 다시 계산해 우회하면
안 된다.

아래 표는 포크가 아닌 요청의 `bind` 에 적용한다.

| 항목 | 규칙 |
|------|-----|
| workspaceId | 세션에서 결정론적으로 — `ws:{sessionId}`. 세션이 없고 실행 id 가 있는 실행(스케줄 루틴)은 `ws:{executionId}`. **둘 다 없는 요청은 사용 불가**다. 워크스페이스를 지어낼 근거가 없는 요청에 공유될 id 를 만들지 않는다 |
| owner | `(tenantId, principalId)`. 메인 턴은 **세션의 소유자**(`SessionOwnerLookup`), 루틴은 요청의 주체. tenantId 는 `SandboxTenantResolver` 가 주체에서 구한다(§8.3) |
| slot | `definitionAttributes()` 의 `sandbox.slot` 이 있으면 그것, 없으면 `primary` |
| profile | `sandbox.profile` 이 있으면 그것이 **요구 프로파일**이다. 없으면 요구가 없고, 슬롯을 처음 만들 때만 설정의 기본 프로파일을 쓴다. 슬롯의 프로파일은 만들 때 고정된다(§8.3) |
| shellKey | 메인 턴: `session:{sessionId}` — 턴을 넘어 cwd 가 유지된다. 루틴: `exec:{executionId}`. 포크는 위의 규칙대로 `exec:{executionId}` — 부모 셸을 오염시키지 않는다 |
| root | `/workspace/repo`. 워크플로 격리 브랜치의 root 는 `isolate()` 가 정한다(§11.2) |

workspaceId 를 결정론적으로 만들기 때문에 별도의 세션→워크스페이스 매핑 저장소가 필요 없다. 세션이 다른
노드로 옮겨가도 같은 id 가 다시 계산된다. 여러 세션에 걸치는 워크스페이스(티켓 하나에 세션 여럿)가
필요한 애플리케이션은 자기 도메인에서 id 를 찾는 정책을 주입한다. 그 매핑은 애플리케이션의 데이터다.

### 8.3 테넌트 검사

**테넌트는 주입받는다.** 코어 `Principal` 은 `type` 과 `id` 만 가지고 테넌트 필드가 없다. 그래서 애플리케이션이
`SandboxTenantResolver.resolve(Principal) → TenantId` 를 주입한다. 멀티 테넌트 배포(`require-principal: true`)에서는
필수이고, 없으면 기동을 거부한다. 단일 테넌트 배포의 기본 구현은 모든 주체를 한 테넌트로 본다.

**검사는 두 층이다.** 워크스페이스 id 를 받는 매니저의 **모든 공개 진입점** — `connect`, `close`, `reopen`, 오케스트레이터
도구가 부르는 슬롯 조회·시작·정지, `SandboxWorktrees` — 은 호출자의 주체로 다음을 확인한다. 실행 안의 호출은 그
실행의 주체를, 실행 밖에서 애플리케이션이 부르는 `close` · `reopen` · `SandboxWorktrees` 는 인자로 받은 `Principal` 을
쓴다. janitor 의 close 는 공개 API 가 아닌 내부 경로를 탄다. 정책이 잘못 구현되어
다른 테넌트의 워크스페이스 id 를 돌려줘도 여기서 막힌다. 정책을 믿되 경계는 두 번 긋는다.

- 테넌트가 같아야 한다(항상)
- `workspace-access: principal`(기본)이면 principal 도 `owner.principalId` 와 같아야 한다. 같은 테넌트의 다른 사용자가
  남의 세션 워크스페이스에 닿지 못하게 하려는 것이다. 팀이 한 워크스페이스를 같이 쓰는 애플리케이션은 `tenant` 로
  낮추고, 그 공유를 자기 정책(여러 세션에 걸치는 워크스페이스 id)으로 표현한다

**owner 는 먼저 부른 쪽이 정하지 않는다.** 기본 정책은 메인 턴의 owner 를 세션의 소유자에서 가져온다. 코어의 세션
저장소(`SessionRecord`)에는 소유 주체가 없으므로, 소유자는 애플리케이션이 `SessionOwnerLookup.ownerOf(SessionId)` 로
알려 준다 — 세션을 만든 사용자는 애플리케이션의 데이터다. `require-principal: true` 이면 이 빈이 필수이고 없으면 기동을
거부한다(§13.2). 그러면 레코드가 없을 때(`InMemory` 재시작, 툼스톤 만료 뒤) 세션 id 를 아는 다른 주체가 먼저 불러도
owner 는 세션 소유자로 정해지고, 그 주체는 검사에서 막힌다. 단일 테넌트 배포에서 빈이 없으면 요청 주체를 owner 로
쓴다 — 먼저 부른 쪽이 owner 가 되는 약점을 받아들이는 구성이다. CLOSED 레코드는 `closedRetention` 동안 툼스톤으로 남아
같은 id 의 재생성을 막는다(§10.5).

여러 사용자가 메시지를 넣는 세션에서 `workspace-access: principal`(기본)은 소유자가 아닌 참여자의 턴을 모두 거부한다.
그런 애플리케이션은 `tenant` 로 낮춘다.

**주체가 없거나 시스템 주체인 실행.** 코어에서 `Principal` 은 선택값이고, Spring 스타터의 기본 진입점은 요청을
`Principal.system()` 으로 제출한다. 그래서 `require-principal: true` 는 "주체가 있다"가 아니라 **"`USER` 또는 `GROUP`
주체다"** 를 뜻한다. 주체가 없거나 `SYSTEM` · `SERVICE` 인 실행은 바인딩 단계에서 거부한다. 이 주체 검사는 바인딩만이 아니라 `Principal` 을 받는
매니저의 진입점(`close` · `reopen`)에도 같은 규칙(`CallerResolver`)으로 걸린다. 시스템 작업(스케줄 루틴
등)에 샌드박스가 필요한 애플리케이션은 `allowed-system-principals` 에 그 주체 id 를 명시하고, `SandboxTenantResolver` 가
그 주체의 테넌트를 돌려줘야 한다(돌려주지 못하면 거부). 끄고 쓰는 단일 테넌트
배포에서는 주체 없는 실행을 `anonymous` 로 취급한다.

**슬롯의 프로파일은 호출 순서로 바뀌지 않는다.** 정의가 `sandbox.profile` 을 **명시한** 바인딩은 그 프로파일을
요구하고, 이미 있는 슬롯의 프로파일이 다르면 `connect` 는 사용 불가로 답한다. 그렇지 않으면 오케스트레이터가
`SandboxStart(slot=review, profile=standard)` 로 슬롯을 먼저 만들어, 저신뢰로 설계한 reviewer 가 `rw` `/shared` ·
egress · 자격 증명을 가진 신뢰 도메인에서 돌게 할 수 있다. 그래서 **저신뢰 정의는 `sandbox.profile` 을 반드시
적는다.** 프로파일을 적지 않은 바인딩은 슬롯에 이미 있는 프로파일을 받아들인다 — 그래야 `SandboxStart(exp-a,
experiment)` 로 만든 슬롯에 `sandbox.slot: exp-a` 만 적은 서브에이전트가 붙고, 운영자가 `default-profile` 을 바꿔도 진행
중인 워크스페이스의 슬롯이 막히지 않는다(§5.1 의 원칙). 이때 받아들이는 프로파일은 `SandboxStart` 의 부분집합 규칙
(§8.5) 때문에 그 슬롯을 만든 오케스트레이터보다 넓지 않다.

### 8.4 한 샌드박스를 여러 에이전트가 쓸 때

기본값은 **공유 샌드박스**다. 오케스트레이터·서브에이전트가 모두 `primary` 를 쓰고 shellKey 만 다르다.
파일 시스템은 공유되고 셸 상태는 격리된다. 서로의 파일을 덮어쓰는 문제는 §7 의 stamp 검사가 잡고, 동시에
코드를 바꿔야 하는 병렬 작업은 worktree(§11.2)로 가른다.

슬롯을 가르는 것은 다음 경우다 — 의존성 충돌, 동시 빌드, 프로파일(네트워크·자격 증명·런타임 클래스)이
달라야 할 때, 신뢰 도메인이 다를 때, 여러 해법을 병렬로 탐색할 때. 신뢰 도메인이 다르면 **반드시** 가른다.
같은 샌드박스 안의 모든 실행은 같은 신뢰 도메인이다.

**슬롯을 가르는 것만으로 신뢰 도메인이 갈리지는 않는다.** `/shared` 를 쓰기로 마운트한 슬롯들은 bare 저장소의
훅·ref 와 공유 문서를 통해 서로의 실행에 영향을 준다(§11.3). 신뢰 도메인을 가르려면 슬롯을 가르고, 신뢰가 낮은
쪽의 프로파일을 `sharedAccess: ro` 또는 `none` 으로 둔다.

### 8.5 오케스트레이터 도구

샌드박스 전용 **실행** 도구는 없다 — 명령과 파일은 코어의 `Bash`·파일 도구가 처리한다. `OrcaSandboxToolProvider`
가 등록하는 것은 슬롯의 수명을 다루는 다음 세 도구뿐이고, 명시적으로 허용된 에이전트에게만 등록한다. 모두
**호출자의 워크스페이스 안에서만** 슬롯 이름으로 동작한다.

도구는 생성자로 아무것도 받지 않는다(코어가 `OrcaToolProviderContext.getFileSystem()` 을 없앴다, EE-1). 실행마다
`EXECUTION_ENVIRONMENT` 에서 환경을 꺼내 `SandboxExecutionEnvironment` 이면 그 바인딩의 워크스페이스를 쓰고, 아니면
(사용 불가, 다른 제공자) 에러를 돌려준다. 호출자의 워크스페이스를 아는 통로는 이것 하나다 — 도구 인자로는 받지
않는다(§21).

| 도구 | 입력 | 동작 |
|------|-----|------|
| `SandboxList` | — | 슬롯·프로파일·상태·generation |
| `SandboxStart` | `slot`, `profile`(허용 목록 중) | 슬롯 생성 + 프로비저닝. 쿼터·admission 검사 |
| `SandboxStop` | `slot`, `mode`(`pause`\|`terminate`) | |

세 도구 모두 §8.3 의 owner 검사를 거친다. `SandboxStart` 가 고를 수 있는 프로파일은 설정의 `allowed-profiles` 중에서도
**호출자 슬롯의 프로파일보다 권한이 크지 않고 격리가 약하지 않은 것**뿐이다 — egress 허용 목록 · 자격 증명 ·
`sharedAccess` · `insecureAllow` 가 모두 호출자 프로파일의 부분집합이고, `runtimeClass` 가 같으며, 자원(cpu · memory ·
disk · pids)이 호출자 이하여야 한다. 모델이 고르는 경로(`SandboxStart`)로는 오케스트레이터가 인젝션을 받아도 자기보다
넓은 샌드박스를 만들 수 없다. 정의를 통해 만들어지는 슬롯(`Task` 로 부른 서브에이전트의 `sandbox.slot`/`sandbox.profile`)
의 신뢰 상한은 운영자가 쓴 정의 파일이 정한다 — 모델은 등록된 정의를 고를 수 있을 뿐 새로 쓰지 못한다.
`primary` 는 `SandboxStart` 로 만들 수 없다 — 메인 턴의 첫 호출이 기본 프로파일로 만든다.

`SandboxRestart` 는 두지 않는다 — `terminate` 후 다음 사용이 generation 을 올려 새로 만든다. `SandboxFork`
는 `SNAPSHOT` capability 가 갖춰지면 추가한다(§18).

다른 에이전트를 새 슬롯에 붙이는 방법은 에이전트 정의의 `sandbox.slot` 과 워크플로 스크립트의 슬롯 지정(EE-42
이후)이다. 모델이 `Task` 인자로 임의 슬롯을 넘기는 경로는 열어 두지 않았다(§20).

---

## 9. 셸 상태

지속 셸은 **프로세스가 아니라 샌드박스 안의 상태 파일**이다. OpenSandbox 의 세션 API 는 명령 하나를 죽이면 세션
전체가 닫히고, stdout/stderr 를 합치며, 출력 상한과 실행 uid 를 받지 않는다. 세션도 execd 메모리에 있어 execd 가
재시작하면 사라진다. 그래서 `SandboxShell` 은 명령마다 `run`(`/command`) 한 번을 부르고, 셸 상태는 이 모듈이 직접
이어 붙인다.

```
/workspace/.aimon-shell/{h(shellKey)}/
  state        cwd + exported variables that differ from the base environment
  lock         flock target — one command per shellKey, across nodes
  owner        "{nodeId} {pid} {pidStartTime}" of the wrapper holding the lock
  heartbeat    rewritten by the owning node every activityWriteInterval while it holds the lock (§5.3)
  run-{id}.cmd / .out / .err / .in / .timedout / .cwd    one command's text, output, stdin and flags; deleted afterwards

run(ExecSpec{ command: OUTER, workingDirectory: /workspace, timeout: commandTimeout + lockWait + 5s,
              maxCaptureBytes: max + 1 KiB }):
OUTER (its fds 1/2 are the exec's pipes and are never redirected):
  printf '%s' '{model's command, every ' as '\''}' > run.cmd  # data, never script syntax; a builtin, no argv
                                                              # (over 64 KiB: uploaded as run.cmd, not embedded)
  exec 9>lock; flock -w {lockWait} 9 || exit 75               # busy -> see below
  write owner
  watchdog: sleep {commandTimeout}; touch run.timedout; kill -KILL 0   (started after the lock: waits don't count)
  bash -c INNER dir run fg root 9>&- >run.out 2>run.err <run.in
  rc=$?; stop the watchdog
  run.out gone? -> "the command removed ...; its output is lost" >&2; exit 71
  head -c {max} run.out; head -c {max} run.err >&2           # truncated inside the sandbox
  printf '\n{nonce} exit=%d out=%d err=%d cwd=%d\n' ... >&2   # trailer: the command's own exit code, byte counts
INNER:
  base = export -p                                            # profile env, execd variables
  read cmd < run.cmd
  . state, or cd root for a new shell                         # restore cwd and exported variables
  readonly __aimon_*                                          # the wrapper's own state, out of the command's reach
  trap 'save cwd + (exported variables minus unchanged base) atomically (tmp + mv)' EXIT   # fg only
  eval "$cmd"                                                 # same process: cd / export persist
```

래퍼는 **두 bash 프로세스**다. 바깥(outer)은 exec 의 stdout/stderr 를 쥔 채 락 · 워치독 · 트레일러를 맡고, 안쪽(inner)이
상태를 복원하고 명령을 돌리고 상태를 저장한다. 명령이 `exit 3` · `set -e` 로 셸을 끝내도 끝나는 것은 안쪽뿐이므로 바깥은
출력과 그 exit code 를 그대로 싣는다. 모델 명령의 출력은 exec 파이프가 아니라 **실행마다의 파일**로 간다. 그래서
`npm run dev &` 처럼 명령이 남긴 자손은 파일을 쥘 뿐 파이프를 붙잡지 않고, 다음 명령이 기다리지 않는다. 결과는 파일에서
`head -c` 로 상한만큼 내보내고, 마지막에 무작위 nonce 가 붙은 트레일러(`exit=N out=BYTES err=BYTES cwd=0|1|2`)를 stderr 에
적는다. `cwd` 는 저장된 작업 디렉터리를 되살리지 못했을 때 root 로 갔으면 1, root 도 없어 `/workspace` 로 갔으면 2 다. `SandboxShell` 은 트레일러를 떼어 내고 바이트 수로 잘림을 판단한다. 트레일러가 없으면 명령이 끝까지 가지 못한
것이다 — `.timedout` 이 있으면 타임아웃, exit 75 면 락 대기 실패, 그 밖은 래퍼 실패다(명령이 `.aimon-shell` 을 지워 실행
파일이 사라진 경우는 exit 71 과 그 이유 — 빈 성공으로 보고하지 않는다). JVM 이 스스로 `kill()` 한
경우(인터럽트, 최후 방어선, 샌드박스 소실)는 무엇이 돌아왔든 그 판정이 먼저다. 래퍼는 bash 3.2 와 POSIX 도구만 쓴다.

- **모델 명령은 데이터다.** 명령 문자열은 작은따옴표로 감싸(`'` 는 `'\''`) 래퍼 스크립트에 값으로만 들어가고, 바깥이
  `printf` 내장 명령으로 `run.cmd` 에 적으면 안쪽 bash 가 읽어 `eval` 한다. 명령은 어느 프로세스의 인자(argv)로도
  넘어가지 않는다 — Linux 는 인자 하나를 128 KiB(MAX_ARG_STRLEN)로 제한하고, exec 의 스크립트 자체가 인자 하나다. 따옴표를
  친 명령이 64 KiB 를 넘으면 스크립트에 넣지 않고 files API 로 `run.cmd` 를 올린다. 그래서 끝의 `# 주석`, heredoc, 짝이 안 맞는 따옴표가 래퍼 문법을 깨지 않고 그 명령 자신의 오류가
  된다. NUL 이 든 명령은 실행 전에 거부한다. 래퍼 자신의 상태는 `readonly` 인 `__aimon_*` 변수에 두고 저장·복원에서
  뺀다 — **`__aimon_` 접두어는 예약되어 있다.** 명령이 `set --` 이나 같은 이름의 변수로 저장 위치를 바꿀 수 없다.
  저장은 내장 명령과 `command -p mv` 만 쓰므로 명령이 바꾼 `PATH`(비워도)나 도구 이름의 함수가 저장을 깨지 않고, 저장이
  실패하면 임시 파일을 지운다. `__aimon_save` · `builtin` · `command` 를 재정의하거나 trap 을 지우는 명령은 저장을 막을 수
  있지만, 셸 상태는 최선 노력이다. 명령이 켠 `set -x` · `set -v` · DEBUG/RETURN trap 은 저장 과정을 출력에 흘리지 않는다 —
  `set -v` 에서만 한 줄짜리 EXIT trap 문자열이 명령의 stderr 에 남는다
- **이어지는 것은 cwd 와 export 된 환경 변수뿐이다.** export 하지 않은 셸 변수 · alias · 함수 · `set -o` 는 다음 명령으로
  넘어가지 않는다. 서술자 notes 에 "shell state persists cwd and exported variables only" 를 넣어 모델에게 알린다
- **상태는 샌드박스와 수명이 같다.** 레코드에 셸 참조가 없으므로 노드가 바뀌어도 같은 파일을 읽어 cwd 가 이어지고,
  execd 재시작이나 pause/resume 도 파일 시스템이 살아 있으면 영향이 없다. generation 이 바뀌면 파일도 사라지고
  notice 가 한 번 나간다. 셸 상태는 최선 노력(best-effort) 상태다 — 잃어도 정확성이 깨지지 않는다
- **같은 shellKey 의 명령은 직렬화한다.** 노드 로컬 락이 한 노드 안의 순서를 정하고, 샌드박스 안의 `flock` 이 노드
  사이를 막는다. 세션은 `SessionLease` 로 한 노드에만 있지만, lease 가 옮겨 간 뒤에도 옛 노드의 명령이 아직 돌 수
  있으므로 노드 로컬 락만으로는 부족하다
- **락은 래퍼 bash 만 쥔다.** 모델 명령은 `9>&-` 로 락 fd 를 닫은 채 돈다. 그렇지 않으면 `./gradlew` 의 데몬이나
  `npm run dev &` 처럼 명령이 남긴 자손이 fd 를 물려받아, 명령이 끝나도 락이 풀리지 않는다. 래퍼가 끝나면(정상 종료든
  kill 이든) 락은 커널이 푼다
- **락을 쥔 쪽이 사라졌으면 정리한다.** `flock` 이 `lockWait`(기본 10초) 안에 풀리지 않으면 `owner` 와 `heartbeat` 를
  본다. 기록된 pid 가 살아 있고 시작 시각이 같은지(`/proc/{pid}/stat`) 먼저 확인한다 — pid 재사용으로 엉뚱한
  프로세스를 죽이지 않게 한다. 래퍼가 살아 있는데 heartbeat 가 `3 × activityWriteInterval` 보다 오래됐으면 그 래퍼를 가진
  노드는 죽은 것으로 보고 래퍼의 프로세스 그룹을 끝낸 뒤 락을 가져오며 notice("previous command from another node was
  terminated")를 붙인다. heartbeat 가 살아 있으면(다른 노드가 아직 명령을 돌리는 중) 기다리지 않고 "셸이 다른 노드의
  명령에 쓰이고 있다" 에러를 돌려준다. 같은 셸에 두 노드가 동시에 쓰는 일은 없다. `nodeId` 는 프로세스 기동마다 새로
  뽑으므로(호스트명 + 기동 UUID) 재시작한 노드가 옛 자기 명령을 "지금 내 명령"으로 오인하지 않는다. 3단계(단일 노드)는
  `owner` 와 heartbeat 를 기록하고 락이 풀리지 않으면 "다른 노드의 명령에 쓰이고 있다" 로 답하는 데까지다. 죽은 노드의
  그룹을 끝내고 넘겨받는 쪽은 멀티 노드 시나리오와 함께 6단계에 들어간다(단계 3 구현 설계 §12-10). heartbeat 파일은
  락을 쥔 포그라운드 명령의 노드가 files API 로 다시 쓰고, 래퍼는 건드리지 않는다
- **락 대기는 명령 타임아웃에 넣지 않는다.** 모델이 준 타임아웃은 락을 얻은 뒤부터 센다. 그래서 `ExecSpec` 의 타임아웃은
  명령 타임아웃에 `lockWait` 를 더한 값이고, 래퍼가 락을 얻은 시각부터 명령 타임아웃을 스스로 잰다. 타임아웃이 `null`
  이면 코어의 정의대로 타임아웃이 없다 — 워치독을 두지 않고, `ExecSpec` 에는 프로바이더가 요구하는 최후 방어선으로 하루를
  준다
- **exec 는 `/workspace` 에서 돈다.** seed 가 보장하고 명령이 지울 수 없는 마운트 지점이다. 작업 root 로 들어가는 것은
  래퍼다 — 명령이 `rm -rf` 로 root 를 지워도 다음 exec 가 "없는 작업 디렉터리" 로 거부되지 않고, 래퍼가 `/workspace` 로
  물러나며 notice 를 붙인다
- `ExecutionOptions.workingDirectory` 가 주어지면 그 명령만 서브셸(`(cd X && cmd)`)에서 돌려 상태 파일의 cwd 를 바꾸지
  않는다. `environment` 도 같다 — 서브셸 안의 `export` 로 넣고 `ExecSpec.environment` 로 넘기지 않는다. 상태를 바꾸는
  것은 모델이 직접 친 `cd` 와 `export` 뿐이다. 상태 파일에는 기본 환경(프로파일 `env`, execd 가 준 변수)과 **달라진**
  변수만 저장하므로 기본 환경이 명령마다 상태 파일로 복사되지 않는다
- 데드라인이나 스레드 인터럽트가 오면 `RunningCommand.kill()` 로 **그 명령의 프로세스 그룹만** 끝낸다. 기다리던 쪽만
  포기하고 명령은 계속 도는 상태를 만들지 않는다 — `tools.bash` 패키지 문서가 금지한 바로 그 동작이다. SIGKILL 로
  끝나면 `trap` 이 돌지 않고, `kill()` 의 SIGTERM 은 안쪽 bash 의 TERM trap 이 저장을 끈 채 끝내므로, 어느 쪽이든 그
  명령의 `cd`/`export` 는 반영되지 않고 직전 상태가 남는다. 셸을 통째로 잃지 않으며, 결과에 notice 를 붙인다. 워치독은
  자기 `sleep` 까지 거두므로 짧은 명령이 이어져도 프로세스가 남지 않는다. 타임아웃 kill 은 최선 노력이다 — 워치독은
  래퍼의 프로세스 그룹을 끝내므로, 명령이 `set -m` · `setsid` 로 자기 그룹에 둔 작업은 샌드박스가 사라질 때까지 남는다.
  세션이나 cgroup 단위로 끝내려면 exec 서버의 도움이 필요하다(4단계에서 execd 로 확인)
- **백그라운드 명령은 락을 잡지 않는다.** 코어가 `ExecutionOptions.background` 를 켜서 넘긴 명령(코어 §5.3 —
  `Bash(run_in_background=true)`)은 상태 파일을 **읽기만** 하고(시작 시점의 cwd·환경 변수) 락 없이 돈다. 그래서 같은
  shellKey 의 다음 명령이 기다리지 않는다. 도는 동안은 heartbeat 가 활동을 기록한다(§5.3, 상한 `backgroundHeartbeatLimit`)
- `maxCaptureBytes` 는 샌드박스 쪽에서 자른다. 래퍼가 실행 파일에서 stdout·stderr 를 각각 상한까지만 내보내고 크기는
  트레일러로 알린다. 수십 MB 로그를 JVM 까지 끌고 와서 자르지 않는다. `ExecSpec.maxCaptureBytes` 는 그 상한에 1 KiB 를
  더한 값이라 프로바이더 쪽 잘림이 트레일러를 자르는 일이 없다
- `exec:` shellKey 의 상태 디렉터리는 노드 로컬 캐시가 기억한다. 코어 계약상 환경은 실행 끝을 알리지 않으므로,
  마지막 명령이 **끝난** 뒤 `execShellIdle`(기본 10분) 동안 다음 명령이 없는 `exec:` 디렉터리를 캐시가 지운다. 락이
  잡힌 디렉터리는 지우지 않는다. 노드가 죽어 남은 디렉터리는 수 KB 이고
  generation 과 함께 사라진다
- 명령은 execd 의 uid 로 돈다(§6.1, §13.3). 셸 상태 파일도 그 uid 소유이므로, 같은 샌드박스의 실행끼리는 서로의 상태
  파일을 읽고 쓸 수 있다 — 같은 샌드박스는 한 신뢰 도메인이다(§8.4)

---

## 10. 수명 관리

### 10.1 게으른 프로비저닝

`SandboxWorkspaceManager.connect(binding)`:

```
loop (up to casRetries; StaleVersionException -> re-read and start over):
1. record = store.find(ws) or store.createIfAbsent(new OPEN workspace, owner from binding)
2. owner check (§8.3) -- before anything that depends on the record's state
3. CLOSED with closeCause=idle -> CAS: reopen (new incarnation), notice "/workspace was reset" (§10.5)
   otherwise record.state != OPEN -> unavailable ("workspace closed")
   slot profile check (§8.3)
4. slot = record.slots[binding.slot]
     absent / TERMINATED  -> admission + quota -> CAS: PROVISIONING(generation+1, since=now, node=self)
                             -> provider.create(key, expiresAt=now+terminateAfter)
                             -> verify labels (§6.3) -> CAS: RUNNING(providerRef)
     FAILED, permanent    -> error with the recorded failure, until the profile changes (profileHash)
                             or the slot is terminated (SandboxStop, close)
     FAILED, transient    -> before backoff(attempts): error with the recorded failure
                             after: same as TERMINATED (generation+1, §5.2)
     PAUSED               -> admission + quota (maxRunning) -> provider.resume -> CAS: RUNNING
                             resume says not found -> CAS: TERMINATED(lostAt) -> start over (LOST path)
     PROVISIONING, since + provisionTimeout not passed -> poll record with backoff
     PROVISIONING, since + provisionTimeout passed    -> CAS: PROVISIONING(same generation, since=now, node=self)
                                                         (takeover; the loser of this CAS keeps polling) -> create
5. if !slot.seeded: seed (§11.3, idempotent) -> CAS: seeded=true
                    check failed (image contract, platform, root, token, network) -> CAS: FAILED(permanent, step)
                    clone failed -> record failure(transient, step); seeded stays false;
                                    retry only after backoff(attempts), else error
6. connectionCache.get(providerRef)
```

**owner 검사는 상태 분기보다 먼저다.** idle close 의 레코드는 흔하고(§10.5) `connect` 가 그것을 자동으로 reopen 한다.
검사가 뒤에 있으면 남의 주체가 reopen CAS 를 일으켜 소유자가 받을 "/workspace was reset" notice 를 가져가고, 다른
테넌트에게 그 워크스페이스가 닫혀 있다는 사실을 알린다. 그래서 테넌트·principal 검사를 통과하기 전에는 레코드의 상태를
보지 않는다 — 실패 메시지는 "not permitted" 뿐이다(§15).

**실패는 두 종류다.** 재시도해도 결과가 같은 실패 — 선언과 다른 platform, root 실행, 서비스 계정 토큰 마운트,
라벨 불일치, 이미지 계약 위반, 서버 엔드포인트에 닿는 네트워크(§11.3) — 는 `permanent` 다. 설정이나 인프라를 고쳐야
풀리므로, 프로파일 내용이 바뀌거나(레코드의 `profileHash` 와 달라지거나) 슬롯이 terminate 될 때까지 재시도하지 않는다.
그렇지 않으면 활성 세션마다 `failureBackoff` 간격으로 샌드박스를 만들고 버리는 순환이 생긴다. 그 밖의 실패(프로바이더
5xx, 타임아웃, clone 실패)는 `transient` 이고, 재시도 간격이 `failureBackoff` 에서 시작해 두 배씩 늘어 `maxFailureBackoff`
(기본 15분)에서 멈춘다.

**`create` 뒤의 검증이 실패하면 그 샌드박스를 지운다.** `create` 가 돌려준 샌드박스의 `status` 가 실패하거나 보이지
않으면 FAILED CAS 를 걸고, 그 CAS 를 이겼을 때만 destroy 한다 — 이 generation 의 키로 이 claim 이 만든 것이고, CAS 를
이겼으므로 넘겨받은 쪽도 없다. 라벨이 맞지 않는 샌드박스는 우리 것이 아닐 수 있으므로 지우지 않는다.

**`connect` 가 실패로 끝나면 그 호출이 모은 notice 를 에러에 싣는다.** idle reopen 이나 소실 뒤의 재생성이 실패하면 다음
호출은 TERMINATED 가 아니라 FAILED 슬롯을 보므로 "/workspace 초기화" 를 알릴 근거가 없다 — 모델이 그것을 들을 곳은
실패한 그 호출의 에러뿐이다. 저장소나 애플리케이션의 admission 이 던진 예외도 `SandboxUnavailableException` 으로 바꿔
코어 도구가 "사용 불가" 로 보고하게 한다.

**프로바이더 호출 뒤 CAS 가 실패하면 처음부터 다시 판단한다.** `create` 나 `resume` 을 마친 뒤 RUNNING CAS 가 지면
(그 사이 janitor 나 `SandboxStop` 이 TERMINATED 로 바꿨거나, 인계한 노드가 이겼거나) 방금 만든 자원을 직접 치우지
않는다. 레코드를 다시 읽어 상태 기계를 다시 돌고, 쓰이지 않게 된 샌드박스는 janitor 조정(STALE·DUPLICATE·ORPHAN)이
회수한다. 호출 경로에서 destroy 를 부르면 이긴 쪽이 쓰는 샌드박스를 지울 위험이 있다.

**`extendExpiry` 가 not found 를 받으면 LOST 경로로 보낸다.** 명령 도중이면 그 명령은 "명령 도중 샌드박스 소실"
에러로 끝난다(§15).

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
남는다. `provisioning.since` 에서 `provisionTimeout` 이 지나면 다른 노드가 **CAS 로 PROVISIONING 의 소유를 넘겨받은
뒤에만** 같은 키로 `create` 를 다시 부른다. 넘겨받기 CAS 에 진 노드는 기다린다. 원래 노드가 죽지 않고 느렸을 뿐이면
같은 키의 샌드박스가 둘 생길 수 있다 — 프로바이더 멱등이 최선 노력이기 때문이다(§6.3). 레코드에 적히지 않은 쪽은
DUPLICATE 로 회수된다(§10.4).

기다리던 노드가 `provisionTimeout` 을 넘기면 에러를 돌려준다(§15). 넘겨받기는 그 다음 호출이 한다 — 한 도구 호출이
두 번의 `provisionTimeout` 을 기다리지 않게 하려는 것이다.

### 10.2 idle 정책

프로파일마다 `pauseAfter`(선택, `PAUSE_RESUME` 필요)와 `terminateAfter`(**필수**)를 둔다. 워크스페이스에는
`closeAfter`(RUNNING · PAUSED · PROVISIONING 슬롯이 하나도 없는 채로 지난 시간, 기본 24시간)가 있다. 기준을 "모든 슬롯이
TERMINATED" 로 두면 재시도하지 않는 FAILED 슬롯 하나가 워크스페이스를 영원히 열어 둔다. 같은 이유로 claim 한 노드가
죽어 남은 PROVISIONING 슬롯은 janitor 가 회수한다(§10.4 첫째) — 그러지 않으면 그 세션에 누가 다시 접속할 때까지 idle
close 를 막고 admission 자리를 차지한다.

| 기본 프로파일 예 | pauseAfter | terminateAfter |
|-----------------|-----------|----------------|
| `standard` | 15분 | 2시간 |
| `review` | 5분 | 30분 |
| `experiment` | — | 20분 |

idle 은 `lastActivityAt` 기준이고, 도는 명령이 있는 동안은 heartbeat 가 그 값을 계속 밀어 준다(§5.3). 그래서
`pauseAfter` 보다 긴 명령도 도중에 멈추지 않는다.

`terminateAfter` 가 없는 프로파일, `pauseAfter ≥ terminateAfter` 인 프로파일, `terminateAfter` 가 프로바이더의 최대
만료(OpenSandbox 서버의 `max_sandbox_timeout_seconds`, 운영자가 `max-expiry` 로 선언)를 넘는 프로파일은 **기동 시
거부한다**(§13.2). `terminateAfter` 가 없으면 프로바이더 만료를 걸 값이 없어 결정 8 이 설정에 따라 사라진다.

### 10.3 프로바이더 만료 — 최후 방어선

OpenSandbox 의 만료는 idle 타임아웃이 아니라 **절대 시각**이고, 앞으로만 늘릴 수 있다. 그래서 슬롯을 만들 때
`expiresAt = now + terminateAfter` 로 걸고, 활동이 있을 때마다 `extendExpiry(ref, now + terminateAfter)` 로 민다(연장도
`lastActivityAt` 기록과 같은 간격으로 스로틀하고, 명령이 도는 동안은 heartbeat 가 부른다). resume 할 때도 같은 값으로
민다. AIMON 쪽 janitor 가 전부 멈춰도 샌드박스는 마지막 활동에서 `terminateAfter` 뒤에 프로바이더가 회수한다. 옛
설계가 TTL 을 스토어와 라벨 두 곳에 둔 것은 "재시작한 프로세스가 만료 시각을 잃는다"는 문제 때문이었다
([옛 설계](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md) §4.3). 만료를 집행하는 쪽이 프로바이더면 그 문제 자체가 없다.

일시 정지 중에도 만료 시각이 흐르는지는 §6.4 의 확인 항목이다. 흐르지 않으면 PAUSED 슬롯의 최후 방어선은 janitor
뿐이므로, 그 경우 프로바이더는 `PAUSE_RESUME` 을 광고하지 않는다.

공유 볼륨에는 프로바이더 만료가 없다. 볼륨의 최후 방어선은 janitor 의 볼륨 조정(§10.4)이고, 노드가 모두 멈추면
볼륨은 다음 기동까지 남는다. 볼륨은 비용이 작고 실행 중인 코드가 없으므로 이것을 받아들인다.

### 10.4 `SandboxJanitor`

application-scoped 단일 루프(기본 30초)이며 세 일을 한다. 3단계는 첫째(idle 집행)만 하고, 노드 로컬로 `execShellIdle`
동안 쓰이지 않은 `exec:` 셸 디렉터리를 지운다(§9). 조정(둘째·셋째)은 4·5단계에 들어온다.

1. **idle 집행** — `store.scan` 으로 `pauseAfter`/`terminateAfter`/`closeAfter` 를 넘긴 대상, `closeResumeAfter`
   (기본 5분) 넘게 CLOSING 에 머문 워크스페이스, 기한이 지난 툼스톤을 찾는다. `provisioning.since` 에서
   `2 × provisionTimeout` 이 지나도록 아무도 넘겨받지 않은 PROVISIONING 슬롯은 버려진 claim 이다 — 같은 claim 인지 다시 읽어
   확인한 뒤 TERMINATED 로 CAS 하고, 죽은 claimer 가 그 키로 만들었을 샌드박스를 destroy 한다. `connect` 의 넘겨받기
   (`provisionTimeout`, §10.1)보다 늦게 잡아, 느리지만 살아 있는 claimer 와 다음 접속이 먼저 처리하게 한다. 전이 CAS 는 **방금 다시 읽은 레코드에서
   idle 조건을 다시 확인한 뒤** 걸고(그 사이 heartbeat 가 밀었으면 건너뛴다), 성공한 뒤에 프로바이더를 호출한다. 멈춘
   CLOSING 은 §10.5 의 close 를 처음부터 이어서 돈다
2. **샌드박스 조정** — `store.scan` 을 **먼저** 하고 `provider.list(managed=true, deployment={자기 deployment})` 를
   나중에 한다. list 를 먼저 하면 그 사이 다른 노드가 만든 샌드박스가 "레코드는 RUNNING, 목록에는 없음" 으로 보인다.
   반대로 scan 이 목록보다 오래된 스냅숏이 되므로, **샌드박스를 지우는 판정은 모두 destroy 직전에 레코드를 다시 읽어
   판정이 여전히 성립할 때만** 실행한다. 다른 deployment 라벨의 샌드박스는 보지도 건드리지도 않는다(§6.3)
3. **볼륨 조정** — `sharedVolumes().list` 로 자기 deployment 접두어의 볼륨(§6.3)을 찾아 레코드와 비교한다(아래 표)

샌드박스 판정은 위에서부터 처음 맞는 행 하나만 적용한다. "레코드 generation" 은 슬롯의 현재 generation, "샌드박스
generation" 은 샌드박스 라벨의 값이다.

| 레코드 | 프로바이더 | 판정 | 조치 |
|-------|-----------|------|-----|
| 슬롯 없음 · 레코드 없음 · 다른 incarnation | 있음 | ORPHAN | `orphanGrace`(기본 10분) 뒤 destroy |
| 무엇이든 | 샌드박스 generation > 레코드 generation | IN-FLIGHT | 건드리지 않는다. scan 뒤에 다른 노드가 새 generation 을 프로비저닝했다. 다음 주기에 다시 본다. `orphanGrace` 가 지나도 레코드가 따라오지 않으면 ORPHAN 으로 본다 |
| 무엇이든 | 샌드박스 generation < 레코드 generation | STALE | destroy |
| PROVISIONING (같은 generation) | 있음 또는 없음 | IN-FLIGHT | 건드리지 않는다. `create` 가 끝났지만 RUNNING CAS 전일 수 있다. 인계는 §10.1 이 처리한다 |
| FAILED (같은 generation) | 있음 | FAILED-LEFTOVER | destroy. 실패한 `create` 가 일부만 만들어 둔 샌드박스다. 재시도는 generation 을 올리므로(§5.2) 이 샌드박스를 다시 쓰는 쪽은 없다 |
| TERMINATED (같은 generation) | 있음 | ORPHAN | `orphanGrace` 뒤 destroy |
| RUNNING/PAUSED (ref=X, 같은 generation) | 같은 sandbox-key 의 Y ≠ X | DUPLICATE | `orphanGrace` 뒤 Y 를 destroy. 인계 경합이 만든 두 번째 샌드박스다(§10.1) |
| PAUSED | RUNNING | DRIFT | pause 재시도 |
| RUNNING/PAUSED | 목록에 없음 | LOST 의심 | 아래 |

**LOST 는 시간으로 확정한다.** 목록에 없으면 `status(ref)` 로 다시 묻고, 없다고 답하면 슬롯에 `missingSince` 를 적는다
(이미 있으면 두고). 다음에 볼 때 여전히 없고 `missingSince` 에서 `lostConfirmAfter`(기본 90초, janitor 주기의 세 배)가
지났으면 LOST 로 확정한다 — 슬롯 → TERMINATED(`lostAt`), 다음 사용이 generation+1 로 새로 만든다. 목록이나 status 에
보이면 `missingSince` 를 지운다. 횟수가 아니라 시각으로 세는 이유: janitor 가 모든 노드에서 돌므로 "연속 N 번" 은 한
주기 안에 여러 노드의 관측으로 채워진다. 이 쓰기들도 §5.3 의 CAS 규칙을 따른다 — 충돌하면 다시 읽고, 슬롯의
generation · `providerRef` · 상태가 판정 때와 같을 때만 다시 쓴다.

볼륨 판정:

| 레코드 | 볼륨 | 조치 |
|-------|------|------|
| OPEN, 같은 incarnation | 있음 | 둔다 |
| CLOSING · CLOSED, 같은 incarnation, `retainVolumeUntil` 이 미래 | 있음 | 둔다 |
| OPEN, 다른 incarnation, 옛 incarnation 의 `retainVolumeUntil` 이 미래 | 있음 | 둔다 — idle 자동 reopen 뒤에도 보존 기한을 지킨다. 레코드는 옛 incarnation 과 그 기한을 `retainedVolumes` 로 들고 간다 |
| 그 밖(CLOSED 이고 보존 기한이 지남 · 레코드 없음 · 다른 incarnation) | 있음, 마운트한 샌드박스 없음 | `orphanGrace` 뒤 삭제. `VolumeInUseException` 이면 다음 주기에 재시도 — K8s 에서 파드 종료가 끝나야 PVC 가 풀린다 |

`orphanGrace` 는 `provisionTimeout` 보다 길어야 한다. 짧으면 레코드 쓰기가 늦은 정상 프로비저닝을 고아로
오인한다. 이 조건은 기동 시 검사한다(§13.2).

모든 노드에서 janitor 가 돌아도 된다. 모든 조치가 CAS 와 destroy 직전의 재확인으로 보호되고 프로바이더 호출은
멱등이기 때문이다. 리더 선출은 두지 않는다 — 선출 저장소가 하나 더 생기는 비용이 중복 `list` 호출보다 크다.

### 10.5 워크스페이스 종료

`SandboxWorkspaceManager.close(id, caller)` (janitor 는 같은 절차를 내부 경로로 부른다):

```
1. CAS: CLOSING(stateSince=now, closeCause)  -- from here connect answers unavailable (§10.1 step 3)
2. every slot: CAS TERMINATED -> provider.destroy
   then destroy every providerRef the record holds (idempotent: a resumed close may find slots already
   TERMINATED whose destroy never ran)
3. wait until provider.list(workspace=h(id)) is empty (up to closeWait), else leave it to the janitor
4. retain-on-close ? record retainVolumeUntil = now + retainFor
                   : sharedVolumes.delete (VolumeInUseException -> leave it to the janitor)
5. CAS CLOSED(stateSince=now, closeCause kept)
   explicit: a tombstone that blocks connect until reopen
   idle:     a record that never blocks connect (below)
every step after 1 acts only while the record is still CLOSING with the same incarnation; step 3 also destroys
whatever the provider still lists for the workspace (a sandbox whose creator lost its RUNNING CAS to the close)
```

각 단계는 멱등이라 도중에 노드가 죽어도 janitor 가 CLOSING 을 처음부터 이어서 돈다(§10.4). 이어받은 쪽이 느려 그 사이
다른 쪽이 close 를 끝내고 애플리케이션이 reopen 했더라도, CLOSING 과 incarnation 을 매 단계 다시 확인하므로 새 incarnation 의
샌드박스를 건드리지 않는다. 슬롯 전이 CAS 는
워크스페이스 레코드 전체에 걸리므로, CLOSING 전이 뒤에 다른 노드가 PROVISIONING 으로 올리려는 CAS 는 version 충돌로
지고, 다시 읽으면 CLOSING 을 보고 멈춘다. close 가 destroy 하는 동안 새 슬롯이 생겨 CLOSED 뒤에 남는 일은 없다.

애플리케이션이 작업 종료 시점을 알면 부른다. 모르면 idle 정책(`closeAfter`)이 결국 닫는다.

**명시적 close 의 CLOSED 는 툼스톤이다.** 레코드는 `closedRetention`(기본 7일) 동안 남아 같은 id 로 들어온 `connect` 에
"workspace closed" 로 답한다. 결정론적 id(`ws:{sessionId}`) 때문에 닫은 뒤 같은 세션의 다음 턴이 새 워크스페이스를
조용히 만들면, 애플리케이션이 끝낸 작업이 빈 환경에서 이어진다. 계속 쓰려면 애플리케이션이 `reopen(id, caller)` 를
부른다 — CLOSED → OPEN, 새 incarnation, owner 는 그대로다. 슬롯은 모두 TERMINATED 이므로 다음 사용이 generation 을 올려
새로 만들고, 공유 볼륨은 새 incarnation 의 이름으로 새로 만든다(옛 볼륨이 아직 지워지는 중이어도 겹치지 않는다).
`closedRetention` 이 지나면 janitor 가 레코드를 지우고(`delete(id, version)`, §5.3), 그 뒤의 `connect` 는 새 incarnation 으로
새 워크스페이스를 만든다. **이때는 notice 가 없다** — 레코드가 사라진 id 는 처음 보는 세션과 구별할 근거가 남지 않는다.
이것은 받아들이는 결정이다 — 툼스톤을 영원히 두면 레코드가 끝없이 쌓인다(단계 3 구현 설계 §10 Q1). `retain-for` 는 `closed-retention` 이하여야 한다(기동 시 검사) — 보존 기한이 툼스톤보다 길면 기한을
기억할 레코드가 먼저 사라진다.

**idle close 의 레코드는 `connect` 를 막지 않는다.** `closeAfter` 로 닫힌 워크스페이스는 애플리케이션이 작업을 끝낸 것이
아니다. 하루 쉬었다 돌아온 사용자의 같은 세션을 "workspace closed" 로 막으면 애플리케이션은 `reopen` 할 계기도 모른다.
그래서 idle close 는 레코드를 `CLOSED(closeCause=idle)` 로 남기고, 다음 `connect` 는 그것을 자동으로 reopen(새 incarnation,
owner 그대로)하며 "/workspace 가 초기화되었다" notice 를 붙인다. 레코드를 지워 버리면 다음 `connect` 가 "초기화"와 "처음
보는 세션"을 구별하지 못해 이 notice 를 줄 수 없다. 이 레코드는 막는 툼스톤이 아니고, 명시적 close 의 것과 같이
`closedRetention` 뒤(보존 볼륨이 있으면 그 기한과 함께) janitor 가 지운다(단계 3 구현 설계 §10 Q1). idle close 로 닫혔거나
닫히는 중인 워크스페이스를 애플리케이션이 명시적으로 close 하면 원인이 `explicit` 으로 바뀌어 막는 툼스톤이 된다 — 그렇지
않으면 세션을 끝낸 뒤의 다음 턴이 조용히 자동 reopen 된다.

`InMemory` 저장소는 재시작하면 툼스톤도 잃는다. 단일 노드 기본 구성에서는 재시작 뒤 닫힌 세션의 다음 턴이 새
워크스페이스를 만든다 — 툼스톤을 지켜야 하는 애플리케이션은 영속 저장소를 쓴다(§5.3).

---

## 11. 파일 시스템과 협업

### 11.1 배치

```
/workspace              sandbox-private (container fs or per-sandbox volume)
  repo/                 default working root
  .worktrees/<key>/     git worktrees for parallel work inside one sandbox
  .aimon-staged/<name>/<contentKey>/   staged skill files (on demand; read-only to file tools)
  .aimon-shell/<h(shellKey)>/          shell state, lock, owner, heartbeat (§9)
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

**파일 도구에 보안용 경로 제한을 두지 않는다.** 샌드박스 밖으로 나가는 경로는 애초에 없고, 샌드박스 안에서는
셸이 같은 uid 로 이미 무엇이든 할 수 있다. 파일 도구만 막으면 모델이 `cat` 으로 돌아갈 뿐이다.

예외는 하나, **스테이징 영역 `/workspace/.aimon-staged/` 는 파일 도구에 읽기 전용**이다(코어 §4.4 의 계약). 보안
경계가 아니라 실수 방지다 — 모델이 `Write` 로 스킬 스크립트 사본을 무심코 고치면, 같은 `contentKey` 경로를 믿는 다른
실행이 고친 내용을 돌린다. 셸은 여전히 쓸 수 있고, 그것은 코어가 받아들인 한계다. 그래서 스테이징 경로를 권한 훅의
허용 목록(예: `Bash(/workspace/.aimon-staged/**)`)에 넣으면 안 된다 — 셸로 심은 내용이 허용된 명령으로 돈다. 코어의 경로 규칙(`PathRule.readOnly`)
을 코어의 공개 팩토리 `VirtualFileSystems.withPathRules` 로 그대로 건다. 대소문자·유니코드 별칭 처리를 이 모듈에 다시
적지 않으려는 것이다. `/workspace/.aimon-shell/` 에도 같은 읽기 전용 규칙을 건다. 이름을 코어 로컬 제공자와 같은
`.aimon-staged` 로 둔 것은, 코어에서 `.aimon/` 이 "제어 저장소(`DENY`)" 를 뜻하기 때문이다 — 샌드박스 안에서 같은
이름이 스테이징 사본을 뜻하면 두 규칙이 헷갈린다.

**에이전트 VFS(스킬·메모리 파일)는 샌드박스 모드의 파일 도구에서 보이지 않는다.** 스킬이 스크립트를
실행해야 하면 코어가 스킬을 렌더하는 시점에 `stage()` 를 부르고, 이 모듈은 그 스킬의 파일만
`/workspace/.aimon-staged/{name}/{contentKey}/` 로 materialize 한다. 스킬 전체를 기동 시점에 복사하지 않는다.
규칙은 코어 [실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)
§4.4 가 정본이며, 이 모듈이 지킬 것은 셋이다.

- **렌더하는 모든 경로에서 불린다.** `Skill` 도구만이 아니라 스킬 포크와 스킬 기반 슬래시 커맨드도 같은 경로로
  `stage()` 를 부른다. 활성화 시점을 이 모듈이 따로 가정하지 않는다
- **생략은 샌드박스 안의 사본으로 판단한다.** 복사를 마친 뒤 마지막에 `.staged` 마커를 쓴다. 다음 `stage()` 는 마커가
  있으면 샌드박스 안에서 한 번의 exec(셸 상태 없이 one-shot 으로, `PATH` 를 고정하고 `/usr/bin/sha256sum` 절대 경로로 —
  모델이 export 한 `PATH` 가 가짜 `sha256sum` 을 부르지 않게)로 **`StagedResource` 의 파일 목록 순서대로 코어와 같은
  알고리즘(`relPath\0bytes\0` 의 SHA-256)으로 content key 를 다시 계산하고** 사본의 파일 목록을 받는다. 키가
  `contentKey` 와 같고 파일 집합이 정확히 목록 + 마커일 때만(파일이 더 있어도 안 된다) 건너뛰고, 다르면 지우고 다시
  복사한다. JVM 쪽에 파일별 해시를 두지 않는 것은 `StagedResource` 가 그것을 들고 있지 않아 `stage()` 마다 스킬을 다시
  읽어야 하기 때문이다. 이 검사는 **코어의 content key 만큼 강하다** — 파일별 해시보다는 약하다. 코어 키의 틀은 한
  파일의 바이트가 `\0{다음 relPath}\0` 를 담으면 파일 경계를 넘어 옮긴 내용이 같은 스트림을 만들 수 있고, 파일 집합
  검사가 그 여지를 좁힌다. 코어 자신이 믿는 키이고 위험은 이론적이다. 마커만 보면 셸로 `/workspace/.aimon-staged/` 에
  쓸 수 있는 실행이 `contentKey` 를 예측해 스크립트와 마커를 미리 심을 수 있고, 다른 에이전트가 그 스킬을 활성화할 때
  심어 둔 내용이 돈다. "이 generation 에 이미 올렸다"를 노드 메모리나 레코드에 기억하지 않는다 — 소실 후
  재생성(§10.4)이나 다른 노드에서 온 요청이 그 기록을 믿으면 없는 경로를 모델에게 준다
- **동시 활성화에도 완성된 사본만 보인다.** 한 슬롯의 두 실행(메인 턴과 포크, 병렬 워크플로 단계 — §8.4)이 같은 스킬을
  처음 활성화하면 둘 다 마커를 못 본다. 그래서 검사와 복사는 `(providerRef, target)` 마다의 **노드 로컬 락** 안에서 하고,
  복사는 `.tmp-{contentKey}-{nonce}` 임시 디렉터리에 받은 뒤 마커를 마지막에 쓰고 제자리로 옮긴다(`move`). 락을 기다린
  쪽은 락 안에서 다시 검사해 먼저 끝난 사본을 돌려준다 — 코어 `LocalStaging` 이 `synchronized` 와 마커 재확인으로 하는
  일이다. 대상은 늘 완성된 채로만 나타나고, 중단된 복사의 임시 디렉터리는 다음 `stage()` 가 지운다. 다른 노드의 동시
  복사(6단계)는 `move` 의 "이미 있음" 을 받고 대상을 다시 검사하는 것으로 충분하다
- **읽힌 뒤 바뀐 스킬은 올리지 않는다.** 복사하며 읽은 바이트로 content key 를 다시 계산하고, `contentKey` 와 다르면
  임시 디렉터리를 지우고 코어와 같은 "changed on disk after it was loaded" `StagingException` 을 낸다. 그렇지 않으면 바뀐
  내용이 옛 키의 경로로 올라가고, 이후의 모든 `stage()` 가 키 검사에 실패해 다시 복사하며 바뀐 내용을 돌린다. 이름 ·
  키 · 파일 경로의 모양과 크기 상한(코어의 50 MiB)도 코어와 같게, 샌드박스에 닿기 전에 검사한다
- **worktree 와 공유한다.** `.aimon-staged/` 는 `repo/` 와 `.worktrees/` 밖에 있으므로 `isolate()` 가 만든
  환경도 같은 사본을 쓰고, git 병합에 섞이지 않는다

**프롬프트 조립이 파일 시스템을 읽으면 프로비저닝이 일어난다.** 코어는 컨텍스트 제공자에게 실행 환경의
`fileSystem()` 을 준다(코어 §5.1). 옵트인 제공자인 `GitStatusContextProvider`(`.git/HEAD`)와
`DirectorySummaryContextProvider`(루트 목록)는 매 턴 그것을 읽으므로, 샌드박스 모드에서는 명령을 한 번도 치지 않는
턴도 샌드박스를 띄우고, 일시 정지된 슬롯을 매 턴 resume 한다. §10.1 의 게으른 프로비저닝이 무의미해진다. 샌드박스
어셈블리는 이 두 제공자를 등록하지 않기를 권한다. 모델에게 필요한 작업 디렉터리·플랫폼은 서술자가 원격 호출 없이
준다(§7).

**전체 VFS 동기화는 없다.** 명령마다 VFS↔샌드박스를 동기화하는 방식은 큰 저장소에서 느리고, 동시 수정을
합칠 규칙이 없다. 초안 두 개가 모두 기각했고, 이 설계는 호환 모드로도 남기지 않는다.

### 11.2 한 샌드박스 안의 병렬 — worktree

같은 샌드박스에서 여러 실행이 동시에 코드를 바꿔야 하면 `git worktree add /workspace/.worktrees/{key}` 를 만들고
그 환경의 `root` 를 거기로 둔다. 워크플로 격리 브랜치는 샌드박스 모드에서 이 경로를 탄다 — 코어의 워크플로 러너가
부모 환경의 `isolate(branchKey)` 를 부르고(`DefaultWorkflowContext`), 이 모듈은 worktree 를 만든 뒤 파생 환경을
돌려준다. 브랜치 이름은 `aimon/{branchKey}` 이고 부모의 현재 HEAD 에서 갈라진다. `root` 가 git 저장소가 아니면 비어
있음을 돌려주고 코어가 그 단계를 거부한다.

worktree 의 수명 규칙:

- **생성은 슬롯 안에서 직렬화한다.** 병렬 브랜치의 `git worktree add` 는 같은 `.git` 의 config·ref 락에서 경합해 실패할
  수 있다. `isolate()` 는 샌드박스 안의 `flock /workspace/.worktrees/.lock` 을 쥔 채 worktree 를 만든다
- **같은 branchKey 로 다시 만들면 새로 만든다 — 다른 실행이 쓰는 중이 아니면.** worktree 마다 `.aimon-owner`(만든
  실행의 executionId)를 두고, `SandboxShell` 은 그 root 에서 명령을 돌릴 때마다 이 파일의 mtime 을 갱신한다. 경로나
  `aimon/{branchKey}` 브랜치가 이미 있고(같은 워크플로를 다시 돌린 경우) `.aimon-owner` 가 `worktreeRetention` 보다 오래
  갱신되지 않았으면 `git worktree remove --force` 와 `git branch -D` 로 지운 뒤 부모의 현재 HEAD 에서 다시 만든다.
  최근에 쓰였으면 지우지 않고 "branchKey 가 다른 실행에 쓰이고 있다" 로 비어 있음 대신 에러를 돌려준다. 새 `isolate()` 는
  새 브랜치 실행을 뜻하고, 옛 브랜치의 결과를 이어받는 경로는 코어에 없다
- **정리는 병합한 쪽이 한다.** 코어 `ExecutionEnvironment` 에는 닫는 훅이 없으므로 이 모듈이 끝을 알 수 없다.
  `SandboxWorktrees.remove(parent, branchKeys)` 가 worktree 와 브랜치를 지우고, `SandboxWorktrees.merge` 는 성공한
  브랜치를 스스로 지운다. `WorktreeMerge.promote` 를 쓰는 조립 코드는 promote 뒤에 `remove` 를 부른다. 놓친 것은 다음
  `isolate()` 가 같은 락 안에서 `git worktree prune` 과, `.aimon-owner` 가 `worktreeRetention`(기본 24시간)보다 오래
  갱신되지 않은 `.worktrees/*` 삭제로 치운다 — 디스크(`disk`)를 채우지 않게 하려는 것이다
- **generation 이 바뀌면 파생 환경은 에러로 끝난다.** 파생 환경은 `isolate()` 시점의 generation 을 기억한다. 샌드박스가
  소실되어 다시 만들어지면 `.worktrees/{key}/` 는 없다. 그 뒤의 호출은 빈 디렉터리를 만들어 이어 가지 않고 "격리
  브랜치의 작업 트리를 잃었다" 에러를 돌려준다. 빈 트리에서 계속하면 `WorktreeMerge.promote` 가 빈 결과를 `repo/` 에
  반영할 수 있다

**병합은 코어가 하지 않는다.** 코어에서 병합은 명시적이다 — 러너는 병합하지 않고, 조립 코드가
`WorktreeMerge.promote(parent, branches, policy)` 를 부른다(코어 §5.2, workflow.md §6.3). 그래서 두 방법이 모두 열려 있다.

- `WorktreeMerge.promote` — 파일 시스템만 쓰므로 샌드박스 환경에서도 그대로 동작한다(`.worktrees/{key}/` 에서
  `repo/` 로 파일을 복사한다). 커밋 이력은 남지 않는다
- `SandboxWorktrees.merge(parent, branches, strategy)` — 이 모듈의 공개 API. 부모 환경의 셸에서 각 브랜치를
  `git merge --no-ff aimon/{key}`(또는 cherry-pick)로 합치고, 충돌은 `MergeReport` 와 같은 모양의 결과로 돌려준다.
  코어 SPI 에 병합 메서드가 없으므로 이것을 쓸지는 조립 코드가 고른다

모델이 직접 합치는 것도 된다. 브랜치는 보통의 git 브랜치이므로 부모 실행의 `Bash` 에서 `git merge aimon/{key}` 를 치면
된다. worktree 는 **샌드박스 안의 병렬성**을, 슬롯 분리는 **런타임 자체의 격리**를 푼다. 둘은 함께 쓸 수 있다. 격리
브랜치 안에서 다시 격리하는 것(중첩 워크플로)은 코어 로컬 제공자처럼 지원하지 않고 비어 있음을 돌려준다(EE-29 와 같은 선).

### 11.3 슬롯 사이의 협업 — `/shared` 의 bare 저장소

슬롯은 `/workspace` 를 공유하지 않는다. 커밋을 주고받는 통로는 `/shared/git/repo.git` 이다.

```
provision(slot, gen) seed:                                   (every step idempotent)
  flock /workspace/.aimon-seed.lock for the rest            (two nodes seeding one slot run one at a time)
  command -v git rg flock sha256sum || fail "image contract"   (§13.3; permanent)
  uname -s matches profile.platform || fail "descriptor mismatch"   (§7; slot -> FAILED)
  [ "$(id -u)" != 0 ] || fail "runs as root"                 (§12.1; slot -> FAILED, unless insecure-allow)
  [ ! -e /var/run/secrets/kubernetes.io/serviceaccount/token ] || fail "service account token mounted"
  connecting to the OpenSandbox server endpoint must fail || fail "network not isolated"   (NETWORK_ISOLATION)
  rm -rf /workspace/.tmp-* ; remove /shared/git/.tmp-* older than provisionTimeout   (leftovers of a crashed seed)
  mkdir -p /workspace/.aimon-staged /workspace/.aimon-shell  (read-only to file tools, §11.1)
  if sharedAccess == none:                                   (no bare repository, §6.4)
      clone <remote> via /workspace/.tmp-repo-<uuid> + mv -T, then skip the two steps below
  if /shared/git/repo.git absent:                            (needs sharedAccess=rw + egress to remote)
      git clone --bare <remote> /shared/git/.tmp-<uuid>      (seed credential, read-only — §12.1)
      mv -T /shared/git/.tmp-<uuid> /shared/git/repo.git     (atomic; loser removes its tmp)
  if /workspace/repo absent:
      git clone /shared/git/repo.git /workspace/.tmp-repo-<uuid>
      mv -T /workspace/.tmp-repo-<uuid> /workspace/repo      (loser removes its tmp)
  mkdir -p <root>
developer slot:  git push origin HEAD:refs/heads/dev/<topic>
review slot:     git fetch origin && git checkout dev/<topic>        (no external network needed)
```

**3단계의 seed 는 이 절차의 부분집합이다.** 이미지 계약(`command -v git rg flock sha256sum`, seed 락보다 먼저 — `flock`
이 없는 이미지는 락 오류가 아니라 이미지 계약 위반으로 보고된다), `uname -s` 와 선언한 platform, 선언했을 때만
`uname -sr` 와 osVersion(글롭으로, `Linux 6.x` 의 `x` 는 `*`), `HARDENED_SECURITY_CONTEXT` 를 면제하지 않았으면 uid 와
서비스 계정 토큰, `.tmp-*` 정리, `mkdir -p` 까지다. 실패한 검사는 `permanent` 이고 그 샌드박스는 지운다(라벨 대조를
통과한 자기 샌드박스다). 제어면 네트워크 점검은 프로바이더 엔드포인트가 필요해 4단계에, git 으로 받는 seed(직접 clone
포함)는 5단계에 들어간다. 그 전까지 `seed` 를 가진 프로파일은 기동 시 거부한다(§13.2, §20).

bare 저장소와 작업 트리는 모두 매번 다른 임시 경로에 받은 뒤 rename 으로 올린다. 두 슬롯(또는 같은 슬롯을 인계받은 두
노드)이 동시에 seed 를 돌아도 `mv -T` 는 한쪽만 성공하고, clone 이 중간에 끊겨도 반쯤 받은 디렉터리가 `repo.git` 이나
`repo` 이름으로 남지 않는다. "있으면 건너뛴다" 검사가 완성된 저장소만 보게 하려는 것이다. 임시 경로 이름을 고정하면
동시 seed 가 같은 디렉터리에 clone 하다 섞이고, 중단된 seed 의 잔재가 이후 모든 seed 의 clone 을 막는다. 잔재는 seed 의
앞 단계가 치운다 — `/workspace` 는 seed 락 안이라 전부, `/shared` 는 다른 슬롯의 seed 가 진행 중일 수 있어
`provisionTimeout` 보다 오래된 것만. seed 락은 같은 슬롯을 인계받은 두 노드의 seed 를 직렬화한다. 락이 없으면 뒤에 시작한
seed 가 앞 seed 의 clone 을 지운다.

외부 원격(GitHub)과 통신하는 것은 그 권한을 프로파일로 받은 슬롯뿐이다. `review` · `test` 프로파일은
egress 를 전부 막아도 개발 슬롯의 커밋을 받을 수 있다. 공유 PVC 위의 **작업 트리** `.git` 을 여러 샌드박스가
동시에 만지는 방식은 쓰지 않는다. bare 저장소에 대한 push/fetch 는 git 이 ref 락으로 직렬화한다.

**bare 저장소는 슬롯 사이의 코드 실행 통로가 될 수 있다.** 로컬 경로로 `git push` 하면 `receive-pack` 이
**push 하는 쪽 샌드박스 안에서** `repo.git/hooks/*` 와 `repo.git/config` 를 읽어 실행한다. `/shared` 에 쓸 수
있는 슬롯은 누구나 훅을 심을 수 있고, 그 훅은 다음에 push 하는 슬롯 — 대개 egress 와 자격 증명을 가진 개발
슬롯 — 에서 돈다. 그래서:

- 프로파일의 `sharedAccess` 로 `/shared` 마운트를 `rw` · `ro` · `none` 중에서 고른다. 기본은 `none` 이다 — 슬롯이
  하나인 기본 구성에는 `/shared` 가 필요 없고, 기본이 `ro` 면 bare 저장소를 만들 `rw` 슬롯이 없어 seed 가 원격을 받지
  못한다. 여러 슬롯이 협업하는 배포만 `rw`/`ro` 를 적는다. `rw` 를
  받은 슬롯들은 서로 한 신뢰 도메인이 되므로, 신뢰가 다른 슬롯에 `rw` 를 함께 주지 않는다
- `ro` 슬롯은 fetch 만 할 수 있다. 리뷰 결과처럼 되돌려 줄 것이 있으면 코드가 아니라 도구 결과(모델이 읽는 텍스트)나
  artifact 로 돌려준다
- seed 는 bare 저장소를 만든 직후 `hooks/` 를 비우고, seed 자신의 git 호출은 `-c core.hooksPath=/dev/null` 로
  돈다. 모델이 치는 git 명령까지 막을 수는 없으므로 이것은 방어가 아니라 기본값 정리다 — 경계는 `sharedAccess` 다

`SHARED_VOLUME` 을 갖추지 못한 프로바이더에서는 모든 프로파일이 `sharedAccess: none` 이어야 기동한다(§6.4). 슬롯은
여럿 만들 수 있지만 bare 저장소가 없으므로 슬롯 사이에 커밋을 주고받을 수 없고, 각 슬롯의 seed 는 원격에서 직접
clone 한다(그 슬롯에 egress 가 있어야 한다). 워크스페이스의 첫 bare 저장소는 `sharedAccess: rw` 이면서 원격에 닿는 슬롯만 만들 수
있다. 그런 슬롯이 아직 없는데 `ro` 슬롯이 먼저 뜨면, seed 는 `/workspace/repo` 에 `git init` 을 하고
`git remote add origin /shared/git/repo.git` 까지 해 둔 뒤 notice 로 알린다. 빈 디렉터리만 두면 "`/workspace/repo` 가
있으면 건너뛴다" 때문에 bare 저장소가 나중에 생겨도 그 슬롯은 generation 이 바뀔 때까지 빈 채로 남는다. origin 을 걸어
두면 bare 저장소가 생긴 뒤 `git fetch origin` 이 그대로 동작한다.

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
| 바인딩 정책 + owner 검사 | 다른 워크스페이스·테넌트·사용자의 샌드박스에 닿기 | 이 모듈(§8). 매니저의 모든 공개 진입점에서 검사한다 |
| 슬롯 프로파일 고정 + `SandboxStart` 권한 부분집합 | 호출 순서나 인젝션으로 더 넓은 신뢰 도메인에서 돌기 | 이 모듈(§8.3, §8.5) |
| 권한 훅 (`PreToolUse` · `permissionRequest` 이벤트) | 허용되지 않은 도구·명령 | 코어 — 샌드박스와 무관하게 그대로 적용된다 |
| 프로파일: runtimeClass | 컨테이너 탈출 | gVisor / Kata via OpenSandbox. `RUNTIME_CLASS` 를 광고하지 않으면 기동 거부(§6.4) |
| 프로파일: egress 허용 목록 | 데이터 유출, 메타데이터 엔드포인트, 내부망 | OpenSandbox network policy(`dns+nft`). **기본 전부 차단** — 차단 범위는 아래 표 |
| 프로파일: 자격 증명 바인딩 | 토큰 탈취, 토큰 오남용 | OpenSandbox credential vault — 샌드박스에는 가짜 값만, egress 에서 실제 값 주입. 범위는 아래 |
| 프로파일: 자원 | 자원 고갈 | cpu · memory · disk · pids |
| 비루트 · 권한 상승 금지 · capability 제거 · 서비스 계정 토큰 미마운트 | 권한 상승, K8s API 접근 | 파드 SecurityContext. `HARDENED_SECURITY_CONTEXT` 를 광고하지 않으면 기동 거부하고, seed 가 uid 와 토큰 경로를 다시 검사한다(§6.4, §11.3) |
| 프로파일: `sharedAccess` | `/shared` 를 거친 슬롯 간 영향(bare 저장소 훅·ref, 공유 문서) | 볼륨 마운트 모드(§11.3). **기본 `none`** |
| 쿼터 + admission | 워크스페이스·테넌트가 샌드박스를 무한히 만들기 | `WorkspaceQuota` + `SandboxAdmission`(§12.2) |

명령 블랙리스트(`mount`, `nsenter` …)는 두지 않는다. 우회가 쉬워 보안 경계가 될 수 없고, 권한 훅이 이미
정책을 걸 자리를 준다.

**egress "전부 차단"의 범위.** 허용 목록에 없는 목적지는 다음을 포함해 모두 막힌다. 각 항목은 §16 의 보안 시나리오가
확인한다.

| 대상 | 차단 방법 |
|------|----------|
| 인터넷 도메인 | `networkPolicy` 허용 목록 + `defaultAction: deny` |
| IP 로 직접 붙기(클라우드 메타데이터 `169.254.169.254` 포함) | `dns+nft` 모드의 IP 규칙. `dns` 모드로는 막히지 않으므로 운영자 선언 없이는 `EGRESS_POLICY` 를 광고하지 않는다(§6.4) |
| DNS 로 새기(질의 이름에 데이터 싣기) | 허용 목록 밖 이름의 해석 거부(egress 사이드카의 DNS 필터) |
| 다른 샌드박스·다른 테넌트의 execd (east-west) | 샌드박스 사이 트래픽 차단. OpenSandbox 가 강제하지 않으므로 운영자가 건다(K8s 는 네임스페이스 NetworkPolicy, §13.3). 운영자가 선언하지 않으면 `NETWORK_ISOLATION` 을 광고하지 않아 기동이 거부되고, seed 가 서버 엔드포인트로의 연결이 실패하는지 점검한다(§6.4, §11.3) |
| OpenSandbox 서버 API, K8s API, 클러스터 내부 서비스 | 위와 같다. 샌드박스는 자기 execd 로 들어오는 연결만 받는다 |
| `/shared` NFS 서버 | 마운트는 노드(kubelet)가 하므로 샌드박스 네트워크에서는 닿을 필요가 없다. 차단 대상이다 |

**자격 증명의 범위.** 자격 증명 바인딩은 host 만이 아니라 **경로 prefix(저장소)와 메서드**까지 좁혀 정의한다.
`github.com` 전체에 토큰을 주입하면 모델은 그 토큰이 닿는 모든 저장소에 push·force-push 할 수 있고, 허용 도메인
자체가 유출 통로가 된다. 이 범위 지정은 `CREDENTIAL_INJECTION` 의 필수 계약이다 — vault 가 host 단위 주입만 지원하면
그 프로바이더는 `CREDENTIAL_INJECTION` 을 광고하지 않는다. seed 가 쓰는 자격 증명(`seed.git.credential`)은 런타임
자격 증명과 **따로** 두고 읽기 전용으로 만든다. 프로파일의 `env` 에는 비밀을 넣지 않는다 — 샌드박스 안에서 평문으로
보이고 셸 상태 파일(§9)에도 남는다.

한 프로파일의 자격 증명 바인딩끼리 host · 경로 범위가 겹치면 기동을 거부한다 — vault 가 겹친 요청에 무엇을 주입할지
정해져 있지 않다. 그래서 같은 저장소에 쓰기 자격 증명을 가진 슬롯은 seed 도 그 자격 증명으로 받고(`seed.git.credential`
생략), 읽기 전용 seed 자격 증명은 런타임 자격 증명이 없는 슬롯에만 쓴다.

**닫힌 실패.** 샌드박스 제공자의 `resolve()` 가 실패하면 코어가 호출마다 에러를 내는
`UnavailableExecutionEnvironment` 를 넣는다(코어 실행 환경 설계 §5.1). 환경 키는 한 번만 쓸 수 있고 제공자는
하나이므로, 호스트 환경이 조용히 남는 경로가 구조적으로 없다.

이 보장은 **도구가 도는 모든 경로가 제공자가 해석한 환경을 쓸 때만** 성립한다. 코어 구현에서 `resolve()` 를 부르는
곳은 넷이다 — `OrcaAgentExecutor.execute()` 의 시작(메인 턴, 그리고 같은 실행의 분기인 슬래시 커맨드 흐름), 포크,
스케줄 루틴, 부모 환경 없는 워크플로 러너(코어 §5.1). 슬래시 커맨드(`/my-skill`)가 손으로 조립하는 컨텍스트도 실행
시작에 해석한 그 환경을 싣는다. 새 경로가 생길 때 이 성질이 깨지지 않았는지는 이 모듈의 통합 테스트가 슬래시 커맨드
경로를 덮어 확인한다(§16).

**스킬 선언 훅의 셸 액션은 샌드박스 모드에서 거부한다.** 코어의 셸 액션 실행기(`DefaultShellActionExecutor`)는 호스트
에서 돈다. 운영자 설정이 아니라 **스킬 파일이 선언한** 코드다(코어 실행 환경 설계 §14). 그대로 두면 같은 스킬의
스크립트가 `Bash` 로는 샌드박스에서, 훅으로는 호스트에서 돌고, 스킬 작성자에게 호스트 셸을 주는 통로가 된다. 그래서
샌드박스 어셈블리는 `SkillHookSetParser` 에 `NoOpShellActionExecutor` 를 준다. 코어 파서는 셸을 지원하지 않는 실행기를
받으면 `action.type: shell` 훅을 파싱 단계에서 거부하므로, 그런 스킬은 로드되지 않고 이유가 기동 로그에 남는다.
훅을 바인딩된 샌드박스에서 돌리는 길은 열린 질문이다(§20).

### 12.2 쿼터

`WorkspaceQuota` — `maxSlots`(기본 6) · `maxRunning`(기본 3) · `maxCpu` · `maxMemory`. 슬롯을 `PROVISIONING` 으로
올리는 CAS 에서 같이 검사하므로 새 프로비저닝은 경합으로 넘치지 않는다. resume 은 쿼터 확인 → `provider.resume` →
RUNNING CAS 순서라 두 슬롯이 동시에 resume 하면 잠깐 넘칠 수 있다. 진 쪽은 다시 판단해 쿼터 에러를 내고, 이미 깨운
샌드박스는 레코드가 PAUSED 이므로 DRIFT 조정이 다시 멈춘다(§10.4) — resume 의 쿼터는 최선 노력이다.

워크스페이스는 세션마다 암묵적으로 생기므로(§8.2) 워크스페이스 쿼터는 테넌트 상한이 되지 못한다. 테넌트 단위
상한은 `SandboxAdmission.admit(owner, profile)` 이 맡고, `connect` 가 프로바이더 자원을 늘리는 모든 분기 — 새
프로비저닝, FAILED 재시도, resume, `SandboxStart` — 에서 CAS 전에 부른다. 기본 구현은 **전부 허용이 아니다.**
`store.scan`(owner 의 tenantId 필터, §5.3)으로 같은 테넌트의 RUNNING·PROVISIONING 슬롯 수를 세어 `max-running-per-tenant`(기본 10)을 넘으면
거부한다. 이 셈은 CAS 로 보호되지 않아 경합 시 조금 넘칠 수 있다 — 최선 노력의 상한이다. 정확한 상한이 필요한
애플리케이션은 자기 저장소로 admission 을 구현한다. `require-principal: true` 에서 admission 을 명시적으로 끄려면
`admission: unlimited` 를 적어야 한다.

공유 볼륨의 용량(`shared-volume.size`)은 볼륨이 강제한다. `rw` 슬롯 하나가 볼륨을 채우면 같은 워크스페이스의 다른
슬롯이 영향을 받지만, `rw` 슬롯들은 이미 한 신뢰 도메인이므로(§11.3) 워크스페이스 밖으로 번지지 않는다.

---

## 13. 설정

### 13.1 `SandboxProfile`

| 필드 | 예 / 기본 |
|------|----------|
| `name` | `standard` |
| `image` | `ghcr.io/kangwoo/aimon-sandbox-runtime:1` |
| `platform` · `osVersion` · `shellName` | `linux` · `Linux 6.x` · `bash` — 서술자에 그대로 들어간다(§7). seed 가 실제 이미지와 대조한다 |
| `cpu` · `memory` · `disk` · `pids` | `2` · `4Gi` · `20Gi` · `512` |
| `runtimeClass` | `gvisor` |
| `egress` | `[]` (전부 차단, `defaultAction: deny` 로 명시해 보낸다 — §6.4) |
| `credentials` | 자격 증명 바인딩 이름 목록 — vault 쪽 정의(host · 경로 prefix · 메서드, §12.1)를 가리킨다 |
| `env` | 정적 환경 변수. **비밀 금지**(§12.1) |
| `pauseAfter` · `terminateAfter` | §10.2. `terminateAfter` 는 필수, `pauseAfter` 는 `PAUSE_RESUME` 을 요구한다 |
| `backgroundHeartbeatLimit` | `1h` — 백그라운드 명령이 샌드박스를 깨워 두는 상한(§5.3) |
| `sharedAccess` | `none` (`rw` · `ro` · `none`, §11.3) |
| `seed` | `git`(원격 URL · ref · 자격 증명 이름 — 런타임 자격 증명이 같은 저장소를 덮으면 생략, 아니면 읽기 전용, §12.1) 또는 없음 |
| `insecureAllow` | `[]` — 일부러 풀 격리 capability(`HARDENED_SECURITY_CONTEXT`, `RUNTIME_CLASS`, `NETWORK_ISOLATION`). 로컬 개발 전용, 기동 시 경고(§6.4) |

프로파일은 운영자 설정이다. 모델은 프로파일을 **고를** 수만 있고(오케스트레이터 도구의 허용 목록 안에서, 자기
프로파일보다 넓지 않은 것만 — §8.5), 만들거나 고칠 수 없다.

### 13.2 설정 예 (Spring 속성 — 키는 제안)

```yaml
aimon:
  sandbox:
    enabled: true
    deployment: acme-prod            # required, label-safe; scopes labels and janitor reconciliation (§6.3)
    require-principal: true          # multi-tenant: USER/GROUP principals only; needs a tenant resolver (§8.3)
    workspace-access: principal      # principal | tenant (§8.3)
    allowed-system-principals: []    # SYSTEM/SERVICE principal ids that may use sandboxes
    provision-timeout: 5m
    orphan-grace: 10m                # must exceed provision-timeout (§10.4)
    failure-backoff: 1m              # first retry delay for transient failures, doubling (§10.1)
    max-failure-backoff: 15m
    activity-write-interval: 30s     # activity writes, expiry extension, heartbeat (§5.3)
    cas-retries: 5
    lost-confirm-after: 90s          # a slot missing this long is LOST (§10.4)
    close-wait: 2m                   # close waits this long for sandboxes to disappear (§10.5)
    close-resume-after: 5m           # janitor resumes a CLOSING workspace stuck this long
    closed-retention: 7d             # CLOSED tombstones (§10.5)
    exec-shell-idle: 10m
    shell-lock-wait: 10s             # §9
    worktree-retention: 24h          # §11.2
    janitor:
      interval: 30s
    admission:
      max-running-per-tenant: 10     # default SandboxAdmission (§12.2); "unlimited" must be explicit
    opensandbox:
      endpoint: ${OPEN_SANDBOX_ENDPOINT}
      api-key: ${OPEN_SANDBOX_API_KEY}
      request-timeout: 30s
      retry: { max-attempts: 3, backoff: 500ms }   # idempotent calls only (status, list, destroy, extend)
      max-concurrent-calls: 32
      max-expiry: 24h                # server's max_sandbox_timeout_seconds; terminate-after must not exceed it
      egress-enforcement: dns+nft    # operator-declared; without it EGRESS_POLICY is not advertised (§6.4)
      volume-reclaimer: kubernetes   # kubernetes | docker | none; none -> SHARED_VOLUME not advertised
      network-isolation: declared    # operator put east-west/control-plane blocking in place (§12.1)
    default-profile: standard
    profiles:
      standard:
        image: ghcr.io/kangwoo/aimon-sandbox-runtime:1
        platform: linux               # declared for the prompt; checked against the image at seed (§7)
        cpu: 2
        memory: 4Gi
        pids: 512
        runtime-class: gvisor
        egress: [github.com, repo.maven.apache.org]
        credentials: [github-acme-app-rw]        # vault: host github.com, path /acme/app, push allowed
        pause-after: 15m                          # requires PAUSE_RESUME (implementation stage 7)
        terminate-after: 2h
        shared-access: rw                         # multi-slot collaboration (§11.3); default is none
        seed: { git: { url: "https://github.com/acme/app.git", ref: main } }   # uses github-acme-app-rw
      review:
        image: ghcr.io/kangwoo/aimon-sandbox-runtime:1
        cpu: 1
        memory: 2Gi
        runtime-class: gvisor
        egress: []
        pause-after: 5m
        terminate-after: 30m
        shared-access: ro            # can fetch dev branches, cannot plant hooks or move refs
    workspace:
      max-slots: 6
      max-running: 3
      close-after: 24h
      shared-volume:
        size: 5Gi
        storage-class: nfs-rwx
        access-modes: [ReadWriteMany]
        retain-on-close: false
        retain-for: 3d               # only when retain-on-close is true; must not exceed closed-retention (§10.5)
    orchestrator-tools:
      agents: [orchestrator]
      allowed-profiles: [standard, review]
```

**기동 시 검사한다** — 어기면 애플리케이션이 뜨지 않는다.

- `deployment` 가 있고 라벨 규칙(§6.3)을 만족한다
- `orphan-grace > provision-timeout`
- 모든 프로파일에 `terminate-after` 가 있고, `pause-after < terminate-after ≤ max-expiry`
- `terminate-after ≥ 3 × activity-write-interval` — heartbeat 기록 하나가 늦거나 실패해도 도는 명령의 샌드박스가 두 기록
  사이에 만료되지 않게
- `retain-for ≤ closed-retention`
- 한 프로파일의 자격 증명 바인딩끼리 host · 경로 범위가 겹치지 않는다(§12.1)
- 프로파일이 요구하는 capability 를 프로바이더가 광고한다(§6.4)
- `require-principal: true` 이면 `SandboxTenantResolver` 와 `SessionOwnerLookup` 빈이 있다
- `default-profile` 과 `allowed-profiles` 가 모두 정의된 프로파일이다
- `insecure-allow` 는 `HARDENED_SECURITY_CONTEXT` · `RUNTIME_CLASS` · `NETWORK_ISOLATION` 만 면제한다
- `admission.max-running-per-tenant` 는 1 이상이거나 명시한 `unlimited` 다 — 값이 없어서 전부 허용이 되는 경로는 없다

아직 구현되지 않은 기능을 쓰는 프로파일은 조용히 무시하지 않고 **거부한다.** 3단계에서는 `pause-after`(`PAUSE_RESUME`,
7단계), `shared-access` 가 `none` 이 아닌 것(5단계), `seed`(5단계), 비어 있지 않은 `credentials`(4단계 — 위의 자격 증명
겹침 검사에 프로바이더의 vault 정의가 필요하고, 검사하지 못한 바인딩으로 시작하면 이 절이 필수로 둔 검사를 건너뛰게
된다)가 그렇다. 해당 단계가 들어오면 이 거부가 풀린다(§20).

### 13.3 이미지와 배포 계약

샌드박스 이미지가 지켜야 하는 것: `bash` · coreutils(`sha256sum`) · util-linux `flock` · `git` · `rg`(ripgrep) · uid 1000
`sandbox` 사용자 · 그 사용자 소유의 `/workspace`. OpenSandbox 가 execd 를 주입하므로 이미지가 execd 를 포함할 필요는
없다(구현 시 확인). 계약을 벗어난 이미지는 첫 프로비저닝의 seed 단계에서 `command -v git rg flock sha256sum` 검사로 실패시킨다.
실패를 늦게 발견하면 `Grep` 이 원인 모를 에러를 낸다.

**실행 uid 는 execd 의 uid 다.** OpenSandbox 의 files API 와 명령은 execd 프로세스의 사용자로 돈다. 그래서 "uid 1000 으로
돈다"는 이미지와 SecurityContext(`runAsUser: 1000`, `runAsNonRoot`)가 함께 지켜야 하고, seed 의 `id -u` 검사가 그 결과를
확인한다(§11.3). root 로 도는 샌드박스는 `insecureAllow` 없이는 FAILED 가 된다.

**운영자가 준비할 것(K8s 런타임):** RWX storage class, 샌드박스 네임스페이스의 east-west·제어면 차단 NetworkPolicy
(§12.1, 준비하면 `network-isolation: declared`), OpenSandbox 서버의 egress 모드 `dns+nft`, `VolumeReclaimer` 가 PVC 를
지울 수 있는 서비스 계정.

---

## 14. 관측성

span 속성: `aimon.sandbox.workspace` · `aimon.sandbox.slot` · `aimon.sandbox.generation` ·
`aimon.sandbox.provider_id` · `aimon.sandbox.shell_key`. span: `sandbox.provision` · `sandbox.seed` · `sandbox.resume` ·
`sandbox.exec` · `sandbox.files.{read,write,search}` · `sandbox.provider.{operation}`. 모두 코어 tracing 의 tool span
아래에 달린다(janitor 의 span 은 자기 루트 span 아래).

**메트릭 라벨의 카디널리티.** 메트릭에는 워크스페이스 · 슬롯 · shellKey · owner 를 라벨로 달지 않는다. 슬롯 이름은
사용자가 정하는 값이고 워크스페이스는 세션 수만큼 생긴다. 메트릭 라벨은 `profile` · `operation` · `outcome` · 판정 같은
닫힌 집합만 쓴다. 개별 워크스페이스는 span 속성과 이벤트로 본다.

**게이지는 노드 로컬만 낸다.** janitor 가 모든 노드에서 돌므로(§10.4) 레코드 기반 게이지("지금 RUNNING 인 슬롯 수")를
노드마다 내면 노드 수만큼 중복 집계된다. 그래서 이 모듈의 게이지는 그 노드가 가진 것(열린 연결, 도는 명령)만 센다.
전체 샌드박스 수는 OpenSandbox·K8s 메트릭을 §6.3 의 라벨로 모아서 본다.

| 메트릭 | 종류 | 라벨 |
|--------|------|------|
| `aimon_sandbox_node_connections` · `aimon_sandbox_node_running_commands` | 게이지(노드 로컬) | profile |
| `aimon_sandbox_provision_seconds` · `aimon_sandbox_resume_seconds` · `aimon_sandbox_exec_seconds` | 히스토그램 | profile |
| `aimon_sandbox_seed_seconds` · `aimon_sandbox_seed_failures_total` | 히스토그램 · 카운터 | profile, step |
| `aimon_sandbox_provider_call_seconds` · `aimon_sandbox_provider_errors_total` | 히스토그램 · 카운터 | operation, outcome |
| `aimon_sandbox_exec_failures_total` | 카운터 | profile, reason |
| `aimon_sandbox_cas_conflicts_total` | 카운터 | operation |
| `aimon_sandbox_heartbeat_failures_total` | 카운터 | kind(activity, expiry, lock) |
| `aimon_sandbox_shell_state_lost_total` · `aimon_sandbox_shell_takeovers_total` | 카운터 | profile |
| `aimon_sandbox_janitor_cycle_seconds` | 히스토그램 | — |
| `aimon_sandbox_janitor_verdicts_total` | 카운터 | verdict(LOST, ORPHAN, STALE, DRIFT, DUPLICATE, FAILED_LEFTOVER, VOLUME) |
| `aimon_sandbox_quota_rejections_total` · `aimon_sandbox_admission_rejections_total` | 카운터 | profile |

감사는 별도 서브시스템을 만들지 않는다. 명령·파일 쓰기의 감사는 코어의 도구 훅(PostToolUse)과 tracing 이
이미 남기며, 거기에 위 span 속성이 붙는다. 샌드박스 수명 이벤트(provisioned · paused · terminated · lost ·
orphan-destroyed · duplicate-destroyed · volume-deleted · workspace-closed · workspace-reopened)는
`SandboxEventListener` 로 내보낸다. 애플리케이션은 그것을 자기 감사 로그로 보낸다. 이벤트는 `workspaceId` · `slot` ·
`generation` · `owner` · `profile` · 원인을 담는다. 테넌트별 감사는 `owner` 로 가른다.

---

## 15. 에러 처리

도구는 예외를 던지지 않는다(코어 규칙). 환경 계층의 실패는 VFS 예외와 `ShellExecutionException` 으로
올라오고, 도구가 그것을 `ToolResult.error` 로 바꾼다.

| 상황 | 결과 |
|------|------|
| 프로비저닝 실패(일시적) | 슬롯 `FAILED`(transient). 에러에 프로파일과 원인을 적는다. 늘어나는 backoff 뒤 다음 호출이 generation+1 로 재시도 |
| 프로비저닝 실패(결정적: platform 불일치 · root · 토큰 마운트 · 네트워크 미격리 · 라벨 불일치 · 이미지 계약) | 슬롯 `FAILED`(permanent). 프로파일이 바뀌거나 슬롯이 terminate 될 때까지 재시도하지 않는다(§10.1) |
| 쿼터 초과 · admission 거부 | 어떤 상한이 몇인지 적는다. 모델이 `SandboxStop` 으로 자리를 비울 수 있게 |
| 명령 도중 샌드박스 소실(명령 실패 또는 `extendExpiry` not found) | 에러 + "환경이 다음 호출에서 다시 만들어지며 `/workspace` 가 초기화된다". 슬롯은 LOST 경로로 간다 |
| resume 이 not found | LOST 로 처리하고 generation+1 로 새로 만든 뒤 명령을 실행한다. notice 를 붙인다(에러 아님) |
| 프로바이더 호출 뒤 레코드 CAS 실패 | 다시 읽고 상태 기계를 처음부터 돈다(§10.1). `casRetries` 를 넘으면 에러 |
| 셸 상태 소실(generation 변경) | 기본 cwd·환경으로 명령을 실행하고 notice 를 붙인다(에러 아님) |
| 명령 kill 로 그 명령의 `cd`/`export` 가 반영되지 않음 | 직전 셸 상태가 남는다. notice(§9) |
| 셸 락을 죽은 노드의 명령이 쥐고 있음 | 그 명령을 끝내고 실행한다. notice(§9) |
| 셸 락을 살아 있는 다른 노드의 명령이 쥐고 있음 | 에러 — "셸이 다른 노드의 명령에 쓰이고 있다"(§9) |
| 프로바이더 접속 불가 | 에러. 호스트로 되돌아가지 않는다(§12.1) |
| 명령 타임아웃 | `VirtualShell` 계약대로 — 원격 명령의 프로세스 그룹을 kill 하고 부분 출력과 함께 `ShellTimeoutException` |
| 다른 노드가 프로비저닝 중 | `provisionTimeout` 까지 기다린다. 넘으면 에러, 다음 호출이 인계한다(§10.1) |
| 프로비저닝·seed 가 오래 걸림 | 명령 타임아웃에 넣지 않는다. `provisionTimeout` 안이면 명령을 실행하고 걸린 시간을 notice 로 붙인다(§10.1) |
| seed 의 clone 실패 | 슬롯은 RUNNING 이지만 `seeded=false`. 에러에 실패한 단계를 적는다. 늘어나는 backoff 가 지난 뒤의 호출만 seed 를 다시 돈다 — 매 호출이 같은 실패를 되풀이하지 않게. seed 의 검사 실패는 위의 결정적 실패 행이다 |
| 워크스페이스가 CLOSING · CLOSED(`closeCause=explicit`) | 사용 불가 — "workspace closed". 애플리케이션이 `reopen` 해야 한다(§10.5) |
| idle close 뒤의 같은 id | 자동 reopen 으로 새 incarnation 에서 실행하고 "/workspace 가 초기화되었다" notice(§10.5). 그 뒤의 재생성이 실패하면 notice 를 그 에러에 싣는다(§10.1) |
| `closedRetention` 이 지나 레코드가 지워진 뒤의 같은 id | 새 워크스페이스로 실행한다. notice 없음 — 구별할 레코드가 남지 않는다(§10.5) |
| 포크의 부모가 없거나 사용 불가 | 포크도 사용 불가. 부모가 있으면 그 원인을 그대로 싣는다(§8.2) |
| 세션도 실행 id 도 없는 요청 | 사용 불가 — 워크스페이스를 정할 수 없다(§8.2) |
| owner 검사 실패(다른 테넌트·다른 사용자) | 사용 불가 · 도구 에러. 워크스페이스가 있다는 사실 외에는 알리지 않는다(§8.3). 없는 id 의 `close` 는 멱등이라 성공이므로, 남의 주체도 "not permitted" 와 성공으로 id 의 존재만은 알 수 있다 — id(`ws:{sessionId}`)는 추측할 수 없다 |
| CLOSING 중인 워크스페이스의 `reopen` | 에러 — 닫힌 뒤에 reopen 하라. 첫 읽기 뒤 CAS 사이에 CLOSING 이 되어도 같다 |
| 명령이 `.aimon-shell` 을 지워 실행 파일이 사라짐 | 래퍼 실패 에러 — 출력을 잃었다고 적는다. 빈 성공(exit 0)으로 보고하지 않는다(§9) |
| 슬롯이 이미 다른 프로파일로 있음 | 사용 불가 — 요구한 프로파일과 실제 프로파일을 적는다(§8.3) |
| `SandboxStart` 가 호출자보다 넓은 프로파일 · `primary` 를 요구 | 도구 에러(§8.5) |
| 격리 브랜치의 작업 트리를 잃음(generation 변경) | 에러 — 파생 환경은 이어 가지 않는다(§11.2) |
| 같은 branchKey 의 worktree 를 다른 실행이 최근에 씀 | `isolate()` 에러(§11.2) |
| 스테이징 영역·셸 상태 영역에 `Write`/`Edit` | 코어 경로 규칙의 "Access denied" 에러(§11.1) |
| 주체 없음 · 시스템 주체 + `require-principal` | 바인딩 거부 — `UnavailableExecutionEnvironment` 가 이유를 담는다(§8.3) |
| `sharedAccess: ro` 슬롯의 `/shared` 쓰기 | 파일 시스템의 읽기 전용 에러를 그대로 돌려준다 |
| 읽은 뒤 파일이 바뀜 | `Edit`/`Write` 에러 — 다시 읽으라고 안내(§7) |
| 셸 액션 훅을 선언한 스킬 | 로드 거부, 기동 로그에 이유(§12.1) |
| 설정 검사 실패 | 기동 거부(§13.2) |

---

## 16. 테스트

**프로바이더 계약 스위트** — `aimon-sandbox-testkit` 의 `SandboxProviderContract`(abstract JUnit 클래스). 모든
프로바이더가 통과해야 한다: 같은 키 순차 생성 → 하나 · destroy 멱등 · exec 의 stdout/stderr **구분**/exit code · cwd·env
전달 · 출력 상한 truncation(stdout·stderr 각각) · `kill()` 이 그 호출의 프로세스 그룹만 끝냄 · files
read/write/stat/list/move · `stat` 의 변경 감지 계약(같은 크기로 1초 안에 다시 써도 mtime 또는 etag 가 바뀜) ·
`list(labels)` 가 모든 페이지를 돌려줌 · `status` 가 라벨(`EXPIRY` 를 광고하면 만료 시각도)을 돌려줌 · 출력이 `OutputSink`
로도 전달됨 · `kill()` 뒤 그 그룹의 자손도 끝남 · `extendExpiry`/`resume` 이 없는 샌드박스에, 그리고 destroy 전에 연 연결의
exec · files 호출에 `SandboxNotFoundException` · `SharedVolumes.delete` 멱등과 마운트 중 `VolumeInUseException` · capability 가 광고한
기능만 동작. 셸 상태(§9)는 SPI 가 아니라 `SandboxShell` 의 일이므로 매니저 통합 테스트에서 덮는다.

**저장소 계약 스위트** — `SandboxWorkspaceStoreContract`. `InMemory` 가 3단계부터 통과하고, JDBC 구현(6단계)이 같은
스위트를 통과한다: `createIfAbsent` 경합 → 하나 · stale version, 지운 뒤의 `update` → `StaleVersionException` · 지웠다가 다시 만든
레코드가 옛 version 을 다시 쓰지 않음(ABA) · `update` 가 `next` 의 version 을 무시하고 다른 id 를 거부함 · 돌려준 레코드와
`find` 가 같음(밀리초 이상의 정밀도) · scan 필터와, 걸러진 레코드가 끼어 있는 페이징 · 툼스톤 보존과 삭제. 스위트가 JDBC 와 함께 늦게 생기면 `InMemory` 와 의미가 갈린다.

**`LocalProcessSandboxProvider`** — testkit 에만 있는 프로바이더. 임시 디렉터리 + 로컬 프로세스로 계약을
흉내 낸다. 격리가 없으므로 운영용이 아니며, 이름과 javadoc 에 그렇게 적는다(`HARDENED_SECURITY_CONTEXT` 등을
광고하지 않으므로 테스트 프로파일은 `insecureAllow` 를 쓴다). 매니저·바인딩·환경 제공자·코어 도구 통합 테스트를
Docker 없이 돌리기 위한 것이다. 샌드박스 하나는 디렉터리 하나이고 **경로를 글자로 바꿔 흉내 낸다** — 명령·작업 디렉터리의
`/workspace` 와 files API 의 절대 경로를 그 디렉터리 아래로, 출력은 거꾸로 바꾼다. 실행 중에 조립된 경로는 바뀌지 않고
호스트에 닿는다는 한계를 javadoc 에 적는다. 호스트에 `flock`(macOS)이나 `rg` 가 없으면 자기 `PATH` 에만 perl `flock` 과,
검색을 하는 대신 크게 실패하는 `rg` 대역을 둔다 — 운영 이미지 계약(§13.3)은 그대로다. 명령은 perl `setpgrp` 로 자기 프로세스
그룹에서 돈다. 실제 exec 서버처럼 없는 작업 디렉터리는 다른 곳으로 옮겨 돌리지 않고 거부하고, destroy 된 샌드박스는 이미
열린 연결로도 not found 이며, destroy 는 명령이 남긴 백그라운드 프로세스(그 명령의 그룹)까지 끝낸다. **장애 주입
래퍼**(`FaultInjectingSandboxProvider`)를 함께 둔다 — 호출별로 지연·5xx·타임아웃·not
found·"생성은 됐지만 응답 유실"·노드 종료(호출 경로를 그 자리에서 멈추는 `Error`)를 주입하고 호출 수를 센다.
한 호출에 규칙이 여럿 맞으면 한 번짜리 규칙(`injectOnce` · `injectAt`)이 상시 규칙(`inject`)보다 먼저다.
`ManualClock`·`ManualScheduler` 로 idle·heartbeat·backoff 시나리오를 실제 시간을 기다리지 않고 돈다.

**통합 테스트**(`@Tag("docker")`) — Testcontainers 로 OpenSandbox 서버(Docker 런타임)를 띄워 `OpenSandboxProvider` 에
계약 스위트를 돌린다. 서버는 Docker 소켓 마운트가 필요하고, egress 시나리오는 사이드카에 `NET_ADMIN`/nft 권한이
필요하다. CI 러너가 이를 허용하지 않으면 공유 테스트 서버를 쓰되 실행마다 다른 `deployment`(`ci-{runId}`)를 주어 서로의
샌드박스를 조정하지 않게 한다. 어느 쪽을 쓰는지는 2단계 스파이크가 정한다.

**K8s 런타임 검증**(`@Tag("k8s")`) — K8s 에서만 드러나는 항목을 kind 클러스터 + OpenSandbox K8s 런타임으로 확인한다.
단계마다 그 단계의 기능만 통과 조건이 된다 — 4단계: 라벨 검증, runtimeClass·SecurityContext 적용, east-west·제어면 차단.
5단계: RWX 볼륨과 샌드박스별 `ro` 마운트, PVC 삭제와 finalizer. 7단계: pause 시 파드 재생성 여부. 이후 릴리스마다 돈다.

**멀티 노드 시나리오의 두 단계.** 4단계의 "두 노드" 시나리오는 한 JVM 안의 매니저 둘(각자 다른 `nodeId` · 연결 캐시 ·
노드 로컬 락)이 한 `InMemory` 저장소를 공유하는 시뮬레이션이다 — 경합 논리를 영속 저장소보다 먼저 검증한다. 실제
프로세스 여럿과 JDBC 저장소로 같은 시나리오를 다시 도는 것이 6단계의 통과 조건이다.

**반드시 있어야 하는 시나리오** (초안 v0.3 의 멀티 에이전트·멀티 샌드박스·장애 복구·보안 테스트에서
가져오고, 이 설계가 더한 것을 붙였다). "단계"는 그 시나리오가 통과 조건이 되는 구현 단계(§18)다.

| 시나리오 | 기대 | 단계 |
|---------|------|-----|
| 에이전트 A `Write Foo.java` → 같은 슬롯의 에이전트 B `Read Foo.java` | 즉시 보인다 | 3 |
| A `export FOO=A` → B `echo $FOO` | 비어 있다 (셸 격리) | 3 |
| 같은 shellKey 에서 `cd src && export X=1` → 다음 `Bash` | cwd 가 `src`, `X=1`. export 안 한 변수는 없다 | 3 |
| `Write` → `Bash cat` 같은 파일 | 같은 내용 (한 파일 시스템) | 3 |
| 명령 타임아웃으로 kill → 다음 명령 | 직전 셸 상태로 돈다, notice | 3 |
| 세션 없는 루틴 → 서브에이전트 포크 → 손자 포크 | 셋 다 같은 워크스페이스·슬롯 | 3 |
| 포크 → 손자 포크, 부모 환경이 사용 불가 | 둘 다 사용 불가, 부모의 원인을 싣는다 | 3 |
| 부모 환경 없는 런타임 범위 워크플로 러너의 단계(`fork()` 있음, `parent` 없음) | 사용 불가 → 새 워크스페이스를 만들지 않는다 | 3 |
| `terminateAfter` 보다 긴 명령, 그동안 도구 호출 없음, 다른 실행이 계속 레코드를 갱신 | 명령 도중 terminate 되지 않음, 프로바이더 만료도 밀림 | 3 |
| 명령이 `sleep 600 &` 같은 자손을 남기고 끝남 → 같은 shellKey 의 다음 명령 | 락 대기 없이 바로 돈다 | 3 |
| 명령 타임아웃 5초, 앞 명령이 락을 3초 더 쥠 | 락 대기는 타임아웃에 들어가지 않는다 | 3 |
| 백그라운드 명령 실행 중 같은 세션의 다음 `Bash` | 기다리지 않고 바로 돈다 | 3 |
| 백그라운드 명령이 `backgroundHeartbeatLimit` 넘게 돔 | 그 뒤 idle 정책대로 전이. 백그라운드 명령은 셸 락 heartbeat 를 갱신하지 않음 | 3 |
| 첫 턴에 명령을 치지 않는 세션 | 프로비저닝 없음. 프롬프트의 환경 블록은 프로파일 선언값이다 | 3 |
| 슬래시 커맨드(`/skill`)로 부른 스킬의 `Bash` (INLINE · FORK 모두) | 샌드박스에서 돈다 (호스트 아님). FORK 는 같은 워크스페이스 | 3 |
| 셸 액션 훅을 선언한 스킬 | 로드 거부, 호스트에서 아무것도 실행되지 않음 | 3 |
| `Write /workspace/.aimon-staged/…` · `.aimon-shell/…` | "Access denied" 에러. 같은 경로에 `Bash` 로 쓰는 것은 된다 | 3 |
| 셸로 `.aimon-staged/{name}/{contentKey}/` 에 다른 스크립트와 마커를 심은 뒤 스킬 활성화 | 해시 불일치로 다시 복사, 심은 내용은 돌지 않음 | 3 |
| 닫힌(CLOSED) 워크스페이스로 `connect` | "workspace closed". `reopen` 뒤에는 새 generation 으로 동작 | 3 |
| close 도중 다른 실행의 `connect` | CLOSED 뒤에 남는 슬롯 없음 | 3 |
| close 도중 노드 종료 | janitor 가 이어서 CLOSED 까지 | 3 |
| `closeAfter` 로 닫힌 뒤 같은 세션의 다음 턴 | 같은 워크스페이스를 새 incarnation 으로 자동 reopen 해 빈 `/workspace` 에서 실행, "/workspace 초기화" notice (막지 않는 CLOSED(idle) 레코드) | 3 |
| `default-profile` 을 바꿔 재배포 → 진행 중인 워크스페이스 | 기존 `primary` 슬롯을 계속 쓴다 | 3 |
| 다른 테넌트 principal 로 같은 workspaceId (`connect` · `close` · `reopen`) | 모두 거부 | 3 |
| 다른 테넌트 워크스페이스에 대한 `SandboxStop` · `SandboxList` | 거부 | 5 |
| 한 테넌트가 세션을 대량으로 열어 `max-running-per-tenant` 초과 | admission 거부 | 3 |
| 같은 테넌트 다른 사용자, `workspace-access: principal` | 거부 | 3 |
| `Principal.system()` 요청 + `require-principal` | 거부 | 3 |
| 레코드 유실 뒤 세션 소유자가 아닌 주체가 먼저 `connect` | owner 는 세션 소유자, 그 주체는 거부 | 3 |
| 장애 주입: `create` 응답 유실 → 재시도 | 레코드에는 하나, 나머지는 DUPLICATE/ORPHAN 으로 회수 | 4 |
| 장애 주입: `extendExpiry` not found (명령 도중) | 명령 에러, 슬롯 LOST, 다음 호출이 재생성 | 3 |
| 장애 주입: resume not found | 재생성 후 실행, notice | 7 |
| 장애 주입: `create` 성공 뒤 RUNNING CAS 실패 | 다시 판단, 쓰이지 않는 샌드박스는 janitor 가 회수 | 4 |
| FAILED(transient) 슬롯의 재시도 | generation+1, 옛 잔재는 STALE 로 destroy | 4 |
| root 로 도는 이미지 | FAILED(permanent), 샌드박스를 다시 만들지 않음 | 4 |
| janitor: `list` 결과에서 한 번 빠짐(페이징·지연 반영) | LOST 로 확정하지 않음 | 4 |
| janitor: 두 노드가 같은 주기에 각각 못 봄 | `lostConfirmAfter` 전에는 LOST 가 아님 | 4 |
| janitor: scan 뒤 다른 노드가 generation+1 을 프로비저닝 | 새 샌드박스를 STALE 로 destroy 하지 않음(IN-FLIGHT) | 4 |
| 두 노드가 같은 슬롯 동시 `connect` | 레코드에 샌드박스 하나 | 4 |
| 인계: 원래 노드가 느렸을 뿐 살아 있음 | 샌드박스 둘이 생기면 하나는 DUPLICATE 로 회수 | 4 |
| 노드 재시작 (`InMemory` 저장소) | 새 샌드박스. 옛 샌드박스는 `orphanGrace` 뒤 회수 | 4 |
| 레코드 삭제 후 janitor | 고아 destroy | 4 |
| 다른 `deployment` 라벨의 샌드박스 | janitor 가 건드리지 않음 | 4 |
| `ws:{sessionId}` 처럼 `:`·`/` 가 들어간 id 로 생성 | OpenSandbox 가 받아들인다(라벨 인코딩) | 4 |
| pause 된 슬롯에서 다음 `Bash` | resume 후 실행된다. 셸 상태가 이어진다 | 7 |
| 명령 사이에 샌드박스 소실(LOST) → 다음 호출 | generation+1 로 재생성, notice, 스킬 스테이징을 다시 복사 | 4 |
| seed 도중 노드 종료 → 다른 노드의 다음 호출 | `.tmp-*` 잔재를 치우고 seed 를 다시 돌아 완료 | 4 |
| 같은 슬롯을 인계받은 두 노드가 동시에 seed | seed 락으로 차례로 돌고, 둘 다 성공 | 4 |
| 자원 한도 | pids 폭주·메모리 초과가 그 샌드박스 안에서 끝나고 다른 슬롯에 번지지 않음 | 4 |
| 보안: 호스트 | 호스트 파일·Docker 소켓·K8s API·서비스 계정 토큰·실제 자격 증명 조회 불가, uid ≠ 0 | 4 |
| 보안: egress | 허용 목록 밖 도메인·`169.254.169.254`·허용 목록 밖 DNS 해석·OpenSandbox 서버 API 불가 | 4 |
| 보안: east-west | 다른 워크스페이스 샌드박스의 IP·execd 포트 접근 불가 | 4 |
| `network-isolation` 을 선언하지 않은 배포 | 기동 거부 | 4 |
| 보안: 자격 증명 범위 | 허용한 저장소 경로 밖으로의 push 에 토큰이 주입되지 않음 | 4 |
| 슬롯 X `touch /workspace/a` → 슬롯 Y | 없다 | 5 |
| 슬롯 X(`rw`) `/shared/r.json` 쓰기 → 슬롯 Y | 보인다 | 5 |
| 슬롯 Y(`ro`) `/shared` 쓰기 | 읽기 전용 에러 | 5 |
| 개발 슬롯 push → egress 차단 리뷰 슬롯(`ro`) fetch | 성공 | 5 |
| `ro` 슬롯이 `repo.git/hooks` 에 훅 쓰기 시도 → 개발 슬롯 push | 쓰기 실패, 개발 슬롯에서 아무것도 실행되지 않음 | 5 |
| 두 슬롯이 동시에 첫 seed | bare 저장소 하나, 둘 다 완전한 clone | 5 |
| 오케스트레이터가 `SandboxStart(review, standard)` 뒤 reviewer(`sandbox.profile: review`) 실행 | reviewer 는 사용 불가, 넓은 프로파일에서 돌지 않음 | 5 |
| `SandboxStart(exp-a, experiment)` 뒤 `sandbox.slot: exp-a` 만 적은 서브에이전트 | 그 슬롯에서 돈다 | 5 |
| `retain-on-close` 워크스페이스 close(명시적 · idle) → `retain-for` 경과 | 그 전까지 볼륨이 남고, 지나면 삭제. idle close 뒤 같은 세션은 자동 reopen | 5 |
| `review` 슬롯의 오케스트레이터가 `SandboxStart(x, standard)` | 거부 (호출자보다 넓음) | 5 |
| close → 볼륨 | 샌드박스가 사라진 뒤 볼륨 삭제. 마운트 중이면 janitor 가 나중에 삭제 | 5 |
| `SHARED_VOLUME` 없는 프로바이더 + `sharedAccess: ro` 프로파일 | 기동 거부 | 5 |
| 노드 재시작 → 같은 세션의 다음 턴 (영속 저장소) | 같은 샌드박스, 같은 cwd | 6 |
| lease 가 옮겨 간 뒤 옛 노드의 명령이 아직 돔 → 새 노드의 `Bash` | "다른 노드의 명령에 쓰이고 있다" 에러. 옛 노드가 죽었으면 정리 후 실행 | 6 |
| seed 없는 프로파일에서 `isolate()` | 비어 있음 → 코어가 단계를 거부 | 7 |
| 격리 브랜치 두 개 동시 생성 → `WorktreeMerge.promote` / `SandboxWorktrees.merge` | 둘 다 만들어짐, 둘 다 `repo/` 에 반영. 후자는 커밋 이력이 남고 브랜치를 지운다 | 7 |
| 같은 branchKey 로 워크플로 재실행 | worktree 를 새로 만든다 | 7 |
| 같은 branchKey 를 다른 실행이 쓰는 중에 `isolate()` | 에러, 쓰는 중인 worktree 는 지우지 않음 | 7 |
| 격리 브랜치 도중 LOST | 파생 환경은 에러로 끝남, 빈 트리로 이어 가지 않음 | 7 |

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
| `SandboxExpiryStore` + 만료 라벨 | 프로바이더 만료 시각(활동마다 연장) + 워크스페이스 레코드 |
| `SandboxLock` · `LocalSandboxLock` | 레코드 CAS + shellKey 직렬화(노드 로컬 락 + 샌드박스 안 `flock`) |
| `ReaperService` | `SandboxJanitor` |
| `TarCreator` · `TarExtractor` · `TarSecurityPolicy` | 프로바이더 `files()`. artifact 상한은 코어 실행 환경 설계 §9.3 으로 옮겨 간다 |
| `IdentifierValidator` | 슬롯 이름 검증 |
| `aimon-sandbox-docker` · `aimon-sandbox-kubernetes` | 삭제 → `aimon-sandbox-opensandbox` |
| `SandboxConfig` | `SandboxProfile` + `WorkspaceQuota` + 매니저 설정 |

---

## 18. 구현 순서

각 단계가 그 자체로 쓸모 있게 자른다. 각 단계의 통과 조건은 §16 시나리오 표의 "단계" 열이다.

1. **aimon-core — 실행 환경 SPI** (§7). **끝났다** — aimon-core PR #195 가 코어 실행 환경 설계의 §11 1–5단계를
   구현했고, PR #196 이 이 모듈이 쓰는 공개 팩토리·백그라운드 notice·포크 정의와 속성을 더했다. 슬래시 커맨드 흐름을
   포함한 모든 실행 경로가 해석한 환경을 쓴다(§12.1)
2. **OpenSandbox 스파이크.** SPI 를 굳히기 전에 실서버(Docker 런타임과 kind 위 K8s 런타임)에 대고 §6.4 의 "구현 시
   확인" 칸을 모두 닫는다 — files `stat` 의 해상도와 해시, pause 중 만료, pause 시 파드 재생성, runtimeClass·
   SecurityContext 적용, credential vault 의 범위 지정, RWX 볼륨과 삭제 경로, 라벨 인코딩, execd 주입 여부, CI 에서
   서버를 띄우는 방법(§16). 산출물은 채운 §6.4 표와 실서버에 대한 최소 계약 테스트 몇 개다. 결과가 SPI 모양을 바꾸면
   이 문서를 먼저 고친다
3. **aimon-sandbox — 도메인과 로컬 경로.** **구현되었다** — 구현 설계와 거기서 벗어난 점은
   [`workspace-sandbox-step3.md`](workspace-sandbox-step3.md) 에 있고, 이 문서는 그 결과를 담았다. 레코드·`InMemory` 저장소와 저장소 계약 스위트·매니저(owner 검사, CLOSED
   툼스톤, CAS 재시도 포함)·프로바이더 SPI·기본 바인딩 정책과 테넌트 해석·환경 제공자(선언 서술자, 스테이징 읽기
   전용, 셸 상태 파일)·활동 heartbeat·janitor 의 idle 집행(terminate · close · 멈춘 CLOSING 이어받기 · 툼스톤 삭제)·
   기본 admission·스킬 셸 훅 거부·기동 시 설정 검사·testkit(계약 + 로컬 프로바이더 + 장애 주입). 슬롯은 `primary`
   하나만, 단일 노드만. 이 단계에서 `isolate()` 는 비어 있음을 돌려주고 코어가 워크플로 격리 단계를 거부한다(7단계에서
   worktree 로 바뀐다). `PAUSE_RESUME` 이 없으므로 `pauseAfter` 를 가진 프로파일은 기동 거부된다(§6.4). 코어를 실행 환경
   SPI 가 있는 버전으로 올리는 것도 이 단계다. 옛 도구 넷과 백엔드 모듈은 이 단계에 앞서 **이미 지웠다** — `aimon-sandbox`
   는 빈 모듈로 이 단계를 기다린다
4. **aimon-sandbox-opensandbox.** 프로바이더·만료 연장·라벨 인코딩과 대조(매니저 쪽 계산과 대조는 3단계에 들어갔고, 여기서는
   실서버가 그 라벨을 받아들이는지 확인한다)·`deployment` 라벨·자격 증명 바인딩과 그 겹침 검사·seed 의 제어면 네트워크 점검·janitor 의 샌드박스
   조정(STALE·ORPHAN·DUPLICATE·LOST 확인)·계약 스위트 통합 테스트·K8s 런타임 검증(이 단계 항목, §16). 조정은
   `InMemory` 저장소의 재시작 뒤 고아를 치우는 유일한 장치이므로 여기서 들어간다. **3단계와 한 릴리스로 낸다** — 옛 백엔드는 이미 없고 `LocalProcessSandboxProvider`
   는 운영용이 아니므로, 3단계만 내면 운영에 쓸 프로바이더가 없다
5. **멀티 슬롯.** 슬롯별 프로파일과 프로파일 고정 검사 · 공유 볼륨과 `sharedAccess` · 볼륨 조정과 보존 · bare 저장소 seed ·
   오케스트레이터 도구 · 워크스페이스 쿼터 · 정의의 `sandbox.slot`/`sandbox.profile` · 정책의 `forkSlot`. **선행 조건:
   EE-42**(워크플로 스크립트의 인라인 서브에이전트가 속성을 싣는다) — 그 전에는 워크플로 단계를 슬롯에 나눌 수 없다.
   에이전트 정의와 포크의 슬롯은 EE-42 없이도 동작하므로, EE-42 가 늦으면 워크플로 부분만 뒤로 미룬다
6. **영속 저장소 — 멀티 노드.** `aimon-sandbox-store-jdbc` 가 저장소 계약 스위트를 통과한다. 이 단계가 끝나야 §1.2 의
   "멀티 노드" 목표가 성립하고, 4단계에서 시뮬레이션으로 돈 멀티 노드 시나리오를 실제 프로세스 여럿으로 다시 돈다(§16). 스키마를 굳히기 전에 §20 의 "워크스페이스 단위 seed
   매개변수" 를 닫는다 — 레코드 스키마를 바꾸는 결정이다
7. **수명 최적화.** pause/resume · warm pool(egress·볼륨·자격 증명 없는 프로파일 한정, §6.4) · 샌드박스 worktree 로
   워크플로 격리 브랜치(`isolate()`) · `SandboxWorktrees.merge`/`remove`(§11.2)
8. **스냅숏·포크.** `SNAPSHOT` capability · `SandboxFork` · 병렬 해법 탐색 패턴

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
| 셸 상태를 에이전트 이름 단위로 | 같은 에이전트의 다른 세션이 cwd 를 공유하게 된다 |
| 셸 상태를 턴 단위로 | 턴마다 cwd 가 초기화되어 모델이 매번 `cd` 부터 다시 한다 |
| SDK `Sandbox` 객체를 상태로 보관 | 노드 이동·재시작을 견디지 못한다. 저장하는 것은 `providerRef` 뿐이고, 연결은 캐시다 |
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
| 코어 `ExecutionEnvironment` 에 병합 메서드를 추가해 git 병합을 끼움 | 코어에서 병합은 명시적이라 러너가 부르지 않는다(§11.2). 호출자가 고르는 일에 SPI 를 늘릴 이유가 없다. 이 모듈의 API(`SandboxWorktrees.merge`)면 된다 |
| 서술자를 첫 프로비저닝 때 이미지에서 읽음 | `resolve()` 와 첫 프롬프트는 프로비저닝보다 먼저다. 읽으려면 첫 턴마다 샌드박스를 띄워야 하고, 재생성마다 값이 흔들려 프롬프트 캐시가 깨진다. 선언하고 대조한다(§7) |
| 샌드박스 자원 사용량을 이 모듈이 수집 (`METRICS` capability) | OpenSandbox 와 K8s 가 이미 낸다. §6.3 의 라벨로 이어 붙이면 된다. capability 로 올리면 SDK 의 메트릭 API 에 SPI 가 묶인다 |
| 지속 셸을 OpenSandbox 세션 API 로 | §9 — 명령 하나를 죽이면 세션 전체가 닫히고, stdout/stderr 가 합쳐지며, 출력 상한·uid 지정이 없고, execd 재시작에 세션이 사라진다. 셸 상태를 샌드박스 안 파일로 두면 노드 이동에도 레코드 쓰기가 없다 |
| 셸 세션 id 를 레코드에 기록 | §9 — 레코드가 셸 수만큼 커지고 CAS 경합이 늘며, 노드가 옮겨 가도 앞 노드의 명령이 쥔 세션에 다시 붙는다. 상태 파일과 샌드박스 안 락이 같은 일을 더 적은 비용으로 한다 |
| owner 를 레코드를 처음 만든 실행의 주체로 | §8.3 — 레코드가 없을 때 세션 id 를 아는 다른 주체가 먼저 부르면 소유권을 가져간다. 세션 소유자에서 가져온다 |
| FAILED 슬롯을 같은 generation 으로 재시도 | §5.2 — 멱등 `create` 가 실패의 잔재를 돌려주고, janitor 가 재시도한 샌드박스를 FAILED-LEFTOVER 로 지울 수 있다 |
| 프로바이더 목록에서 한 번 빠지면 LOST | §10.4 — 목록 조회의 지연 반영·페이징 누락과 경합하면 도는 샌드박스를 지운다. `status` 로 다시 확인하고 연속 관측으로 확정한다 |
| 기본 `sharedAccess: ro` | §11.3 — 슬롯이 하나인 기본 구성에서 bare 저장소를 만들 `rw` 슬롯이 없어 seed 가 원격을 받지 못하고, 공유 볼륨이 없는 배포가 기동하지 못한다 |
| 라벨에 워크스페이스 id·owner 를 원문으로 | §6.3 — OpenSandbox 가 라벨 규칙으로 거부하고, 잘라 쓰면 충돌하며, 공유 인프라에 개인 식별 정보가 남는다 |

---

## 20. 열린 질문

각 항목에 닫아야 하는 시점을 적는다. 시점이 없는 항목은 구현을 막지 않는다.

- **§6.4 의 확인 칸** *(2단계에서 닫는다)* — pause 중 만료가 흐르는지, K8s 런타임의 pause 가 파드를 다시 띄우는지,
  files `stat` 의 해상도와 해시, credential vault 의 범위 지정 수준, RWX 볼륨과 샌드박스별 `ro` 마운트, PVC 삭제 경로,
  runtimeClass·SecurityContext 가 실제로 적용되는지, execd 주입 여부. 서버가 활동에 따라 만료를 스스로 미는 기능
  (OSEP-0009, `access.renew.extend.seconds`)이 heartbeat 의 `extendExpiry` 를 대신할 수 있는지도 본다
- **워크스페이스 단위 seed 매개변수** *(6단계의 JDBC 스키마를 굳히기 전에 닫는다)* — `seed`(원격 · ref · 자격 증명
  이름)는 지금 프로파일에만 있다. 그래서 저장소나 브랜치가 티켓마다 다르면 프로파일을 저장소 수만큼 만들어야 하고,
  테넌트마다 다른 자격 증명을 쓸 방법이 없다. 병렬 해법 탐색(§18-8)도 모든 실험 슬롯이 같은 기준 커밋에서 출발해야
  하는데 `SandboxStart` 에는 ref 인자가 없다. 방법은 둘이다 — `SandboxWorkspace` 에 `createIfAbsent` 시점의 seed
  매개변수를 두고 바인딩 정책이 채우거나, 프로파일의 `seed` 를 템플릿으로 두고 워크스페이스가 값만 채운다. 어느
  쪽이든 모델이 원격이나 자격 증명을 고르는 경로는 열지 않는다
- **동적 슬롯 배정** — 오케스트레이터가 실행 중에 `exp-a/b/c` 를 만들고 서브에이전트를 각 슬롯에 붙이는
  경로. 워크플로 스크립트(`agent(prompt, { sandbox: 'exp-a' })`, EE-42)는 설계 가능하지만, `Task` 도구 인자로 여는
  것은 모델에게 슬롯 선택권을 주는 일이다. 허용 목록으로 좁혀 열지, 워크플로에만 둘지 정해야 한다
- **스킬 선언 훅을 샌드박스에서 돌리기** — 지금은 샌드박스 모드에서 셸 액션 훅을 가진 스킬을 거부한다(§12.1). 훅을
  바인딩된 샌드박스에서 돌리려면 코어의 훅 실행기가 실행 환경을 받아야 한다(코어 §14). 그 전까지 거부가 기본값이다
- **백그라운드 명령을 끝내는 도구** — `backgroundHeartbeatLimit`(§5.3)로 샌드박스를 깨워 두는 것은 막았지만, 명령
  자체는 샌드박스가 멈출 때까지 돈다. 모델이 백그라운드 명령을 끝낼 도구는 코어의 일이다
- **워크스페이스 체크포인트** — §18-8 의 스냅숏은 슬롯 단위다. 슬롯 여럿과 `/shared`(bare 저장소 ref 포함)를 한
  시점으로 묶는 체크포인트가 필요한지는 `SNAPSHOT` 을 확인한 뒤 정한다. 따로 뜬 슬롯 스냅숏을 함께 복원하면 bare
  저장소의 ref 와 각 슬롯의 작업 트리가 서로 다른 시점을 가리킨다
- **백그라운드 명령의 노드 이동** — `BackgroundBashManager` 는 노드 로컬이다. 노드가 죽으면 샌드박스 안의
  명령은 계속 돌지만 결과를 받을 쪽이 없다. 프로바이더 쪽 명령 id 를 레코드에 남겨 다른 노드가 다시 붙게
  할지는 6단계(영속 저장소) 이후에 판단한다. 그때까지는 heartbeat 가 노드와 함께 멈추므로, 결과를 받을 쪽이
  없는 명령은 idle 정책에 따라 샌드박스와 함께 정리된다
- **스트리밍 도구 출력** — SPI 는 `OutputSink` 로 준비되어 있지만 코어 `BashTool` 이 부분 출력을 이벤트로
  내보내는 경로가 없다
- **`sharedAccess: none` 슬롯의 git 직접 clone** *(5단계에서 닫는다)* — §18 은 git seed 를 5단계에 두었고 직접 clone 은
  어느 단계인지 말하지 않는다. 3단계는 `seed` 를 가진 프로파일을 기동 시 거부한다(§11.3, §13.2). 구현되지 않은 경로가
  조용히 아무것도 하지 않는 것보다 낫다. 넣을 때는 bare 저장소 seed 와 함께 `SandboxSeeder` 에 넣는다(단계 3 구현 설계
  §10 Q2)
- **자격 증명 바인딩의 겹침 검사** *(4단계에서 닫는다)* — §13.2 의 검사는 vault 쪽 정의(host · 경로 prefix · 메서드)가
  필요한데 그것은 OpenSandbox 프로바이더 설정만 안다. 프로바이더 쪽 검증 훅의 모양을 4단계가 정하고, 그 전까지
  `credentials` 를 가진 프로파일은 기동 시 거부한다(단계 3 구현 설계 §10 Q3)
- **seed 의 네트워크 격리 점검** *(4단계에서 닫는다)* — seed 가 제어면 엔드포인트에 닿지 못하는지 스스로 확인하려면
  프로바이더가 그 엔드포인트를 SPI 로 알려 줘야 한다. 그 모양이 4단계의 일이다. 그 전까지 `NETWORK_ISOLATION` 은 필수
  capability 로 남으므로, 모든 프로파일은 그것을 광고하는 프로바이더 위에서 돌거나 `insecure-allow` 로 면제하고 기동 경고를
  남긴다 — 빈틈이 조용히 생기지 않는다(단계 3 구현 설계 §10 Q4)
- **§14 의 span 과 메트릭을 어느 단계가 가지는가** *(3·4단계 릴리스 전에 닫는다)* — §18 은 관측성을 어느 단계에도 두지
  않았다. 3단계는 `SandboxEventListener` 의 수명 이벤트(§14 의 감사 쪽)만 넣었다. 메트릭은 Micrometer 의존성과 라벨
  설계가 따라오므로 소비처가 생길 때 정한다(단계 3 구현 설계 §10 Q6)
- **aimon-core SNAPSHOT 고정** *(3·4단계 릴리스 전에 닫는다)* — 3단계는 `aimon-core 0.3.1-SNAPSHOT` 을 `mavenLocal()` 에서
  받는다(그룹과 스냅숏으로 걸러 둔다). aimon-core 0.3.1 이 Central 에 나오고, 고정을 올리고, `mavenLocal()` 을 지우기
  전에는 이 저장소의 어떤 것도 릴리스하지 않는다(단계 3 구현 설계 §10 Q8)
- **`aimon-sandbox-testkit` 의 발행** *(3·4단계 릴리스에서 닫는다)* — 계약 스위트를 이 저장소 밖의 구현도 통과해야 하므로
  발행하기로 했다(`aimon.publishable`). 첫 릴리스 전까지는 되돌릴 수 있다(단계 3 구현 설계 §10 Q5)

---

## 21. 하지 말 것

- **`LiveSession.close()` · `AgentRuntime.close()` 에서 샌드박스를 끝내지 말 것.** 워크스페이스 수명이다(§3.2)
- **샌드박스 환경을 얻지 못했을 때 호스트 셸로 되돌아가지 말 것.** 닫힌 쪽으로 실패한다(§12.1)
- **도구 인자로 워크스페이스·슬롯·sandbox id 를 받지 말 것.** 오케스트레이터 도구의 슬롯 이름은 호출자의
  워크스페이스 안에서만 해석된다(§8.5)
- **OpenSandbox SDK 타입을 `aimon-sandbox-opensandbox` 밖으로 내보내지 말 것**(§4.1)
- **프로파일이 요구한 capability 를 프로바이더가 광고하지 않는데 조용히 진행하지 말 것**(§6.4)
- **슬롯 상태 전이를 CAS 없이 쓰지 말 것.** 노드 간 직렬화 장치는 CAS 뿐이다(§10.1)
- **CAS 충돌을 이유로 쓰기를 버리지 말 것.** 다시 읽고 조건을 확인한 뒤 재시도한다. 활동 기록이 유실되면 도는 명령이 멈춘다(§5.3)
- **CAS 에 진 뒤 호출 경로에서 프로바이더 자원을 destroy 하지 말 것.** 이긴 쪽이 쓰는 샌드박스일 수 있다. 조정에 맡긴다(§10.1)
- **`lastActivityAt` 을 명령마다 쓰지 말 것.** 스로틀한다(§5.3)
- **셸 상태를 레코드에 쓰지 말 것.** 샌드박스 안의 상태 파일이 정본이다(§9)
- **`/workspace` 에 대해 `durable()` 을 `true` 로 답하지 말 것.** 코어가 그 답을 보고 artifact 를 제어 저장소로 복사한다. 이 모듈이 직접 복사하거나 자기 상한을 두지도 말 것(§11.5)
- **"이미 스테이징했다"를 메모리·레코드·마커만으로 판단하지 말 것.** 샌드박스 안 사본의 해시를 확인한다(§11.1)
- **스테이징 영역을 파일 도구에 쓰기 가능으로 두지 말 것.** 코어 경로 규칙으로 읽기 전용이다(§11.1)
- **서술자를 원격 호출로 채우지 말 것.** `resolve()` 는 원격 자원을 만들지 않는다. 프로파일 선언값을 쓴다(§7)
- **사용 불가 부모의 포크를 정책으로 다시 해석하지 말 것.** 포크도 사용 불가다(§8.2)
- **세션도 실행 id 도 없는 요청에 워크스페이스 id 를 지어내지 말 것.** 사용 불가로 답한다(§8.2)
- **샌드박스 전용 실행 도구를 다시 만들지 말 것.** 명령과 파일은 코어 도구가 샌드박스 환경에서 처리한다(§8.5)
- **공유 볼륨 위의 작업 트리 `.git` 을 여러 샌드박스가 동시에 쓰게 하지 말 것.** bare 저장소를 거친다(§11.3)
- **신뢰가 다른 슬롯에 `/shared` 쓰기 권한을 함께 주지 말 것.** bare 저장소의 훅이 슬롯 사이의 실행 통로가
  된다(§11.3)
- **`deployment` 라벨 없이 조정하지 말 것.** 같은 OpenSandbox 서버를 쓰는 다른 배포의 샌드박스를 지운다(§6.3)
- **명령이 도는 동안 활동 기록을 멈추지 말 것.** heartbeat 가 없으면 긴 명령 도중에 pause 된다(§5.3)
- **백그라운드 명령에 셸 락을 잡지 말 것.** 그 shellKey 의 다음 명령이 모두 기다린다(§9)
- **프로비저닝 대기를 명령 타임아웃에 넣지 말 것.** `provisionTimeout` 으로 따로 제한한다(§10.1)
- **포크 여부를 `parent` 로 판단하지 말 것.** `fork().isPresent()` 로 판단하고, 부모 없는 포크는 사용 불가다(§8.1)
- **이미 있는 슬롯이 다른 프로파일인데 바인딩하지 말 것.** 정의가 요구한 프로파일은 호출 순서가 아니라 설정이 정한다(§8.3)
- **`SandboxStart` 로 호출자보다 넓은 프로파일의 슬롯을 만들게 하지 말 것**(§8.5)
- **워크스페이스 id 를 받는 공개 진입점에서 owner 검사를 빼지 말 것.** `connect` 만이 아니다(§8.3)
- **`Principal.system()` 을 멀티 테넌트 샌드박스의 주체로 받아들이지 말 것.** 명시적으로 허용한 시스템 주체만 쓴다(§8.3)
- **라벨에 id·owner 원문을 쓰지 말 것.** 해시로 인코딩하고, 돌려받은 샌드박스는 라벨을 대조한 뒤에만 쓴다(§6.3)
- **egress 를 생략해서 "전부 차단"을 표현하지 말 것.** OpenSandbox 는 생략을 전부 허용으로 읽는다. `defaultAction: deny` 를 명시한다(§6.4)
- **`terminateAfter` 없는 프로파일을 받아들이지 말 것.** 프로바이더 만료가 최후 방어선이다(§10.2)
- **프로바이더 목록에서 한 번 빠졌다고 LOST 로 확정하지 말 것.** `status` 로 다시 확인한다(§10.4)
- **명시적으로 닫힌 워크스페이스에 조용히 새 샌드박스를 만들지 말 것.** `reopen` 을 거친다. idle close 의 레코드는 `connect` 를 막지 않는다(§10.5)
- **모델 명령에 셸 락 fd 를 넘기지 말 것.** 명령이 남긴 자손이 락을 쥔다(§9)
- **결정적 실패를 backoff 로 재시도하지 말 것.** 프로파일이 바뀔 때까지 멈춘다(§10.1)
- **janitor 가 scan 스냅숏만 보고 샌드박스를 지우지 말 것.** destroy 직전에 레코드를 다시 읽는다(§10.4)
- **샌드박스 모드에서 스킬의 셸 액션 훅을 호스트에서 돌리지 말 것.** 그런 스킬은 로드하지 않는다(§12.1)
- **스테이징 경로(`/workspace/.aimon-staged/**`)를 권한 훅의 허용 목록에 넣지 말 것.** 셸이 그 경로에 쓸 수 있다(§11.1)
- **프로파일 `env` 에 비밀을 넣지 말 것.** 자격 증명은 vault 바인딩으로, 범위를 좁혀 준다(§12.1)
- **메트릭 라벨에 워크스페이스·슬롯·owner 를 달지 말 것.** span 속성과 이벤트로 본다(§14)

---

## 관련 문서

- [`workspace-sandbox-step3.md`](workspace-sandbox-step3.md) — 3단계(도메인과 로컬 경로)의 구현 설계, 결정(§10)과 구현이 벗어난 점(§12)
- [`sandbox.md` @ `704013c`](https://github.com/kangwoo/aimon-sandbox/blob/704013c02cb14f16ec37ebf8c07f90d7e107db73/docs/design/sandbox.md) — 이 설계가 대체한 identifier 기반 설계(삭제됨)
- [`scope-model.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) — 수명과 소멸 책임
- [`glossary.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/glossary.md) §4 — 턴 · iteration · execution
- [`session/routing.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/design/session/routing.md) — 세션의 노드 배치와 lease
- [`workflow/workflow.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/design/workflow/workflow.md) — 격리 브랜치와 `WorktreeToolEnvironmentFactory`
- [`agent-execution/artifact.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/design/agent-execution/artifact.md) — `ArtifactCollector`
- [`features/tool/tool-development-guide.md`](https://github.com/kangwoo/aimon-core/blob/main/docs/features/tool/tool-development-guide.md) — 도구 규칙
