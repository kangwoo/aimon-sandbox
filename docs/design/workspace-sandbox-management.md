# 관리 — 배선과 호스트 API

호스트 애플리케이션이 이 모듈과 만나는 표면 전부를 정한다. 무엇을 배선하고, 무엇을 호출하고, 무엇을 듣는가다. 호스트는 에이전트가 돌기
전에 워크스페이스를 만들어 준비되었는지 알고, 세션을 거기에 붙이고, 에이전트 정의에 기본 워크스페이스를 주고, 사람에게 그 파일을 보여 주고,
모든 워크스페이스와 샌드박스를 운영자로서 보고 멈추고 지울 수 있어야 한다.

> 관련: [개요](workspace-sandbox.md) · [도메인](workspace-sandbox-domain.md) · [바인딩](workspace-sandbox-binding.md) ·
> [수명](workspace-sandbox-lifecycle.md) · [notice](workspace-sandbox-notices.md) · [파일 시스템](workspace-sandbox-filesystem.md) ·
> [보안](workspace-sandbox-security.md) · [설정](workspace-sandbox-configuration.md)

---

## 1. 배선

어셈블리 `WorkspaceSandbox` 가 무엇이고 왜 싱글턴 묶음인지는 [개요 §5.2](workspace-sandbox.md)가 정한다. 이 절은 호스트가 그것을 만들고,
코어에 넘기고, 닫는 절차를 정한다.

### 1.1 만들기

```java
WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
        .settings(settings).provider(provider)          // 프로바이더는 빌린다. ownProvider(true) 면 close() 가 함께 닫는다
        .tenantResolver(...)                             // require-principal 이면 필수
        .sessionRecords(() -> stack.sessionRecordStore()) // 코어가 기록한 세션 소유자. 필요하면 .sessionOwnerLookup(...) 도
        .build();                                        // 기동 검사 위반을 모두 모아 SandboxConfigurationException
sandbox.janitor().start();
```

| 빌더 항목 | 기본 | 뜻 |
|---|---|---|
| `settings` · `provider` | 필수 | 배포 설정([설정 §2](workspace-sandbox-configuration.md))과 프로바이더. 프로바이더는 빌린 것으로 보고 닫지 않는다. `ownProvider(true)` 면 `close()` 와 실패한 `build()` 가 닫는다 |
| `store` · `sessionOptionsStore` | 메모리 저장소 | 둘 다 한 노드 전용이다([도메인 §4.4](workspace-sandbox-domain.md)) |
| `tenantResolver` | 모두 한 테넌트 | `require-principal` 이면 필수 |
| `sessionRecords(...)` · `sessionOwnerLookup(...)` | 없음 | 세션 소유자의 출처. 의미와 우선순위는 [바인딩 §4](workspace-sandbox-binding.md) |
| `bindingPolicy` | `DefaultSandboxBindingPolicy` | 무엇을 주든 `SessionOptionsBindingPolicy` 가 감싼다([바인딩 §1](workspace-sandbox-binding.md)) |
| `admission` | `DefaultSandboxAdmission` | [보안 §8](workspace-sandbox-security.md) |
| `eventListener` | 아무것도 하지 않음 | 수명 이벤트를 받는다(§7) |
| `scheduler` | 어셈블리가 소유하는 데몬 스케줄러 둘(heartbeat 용, janitor 용) | 호스트가 주면 하나로 둘 다 돌리고 닫지 않는다 |
| `backgroundExecutor` | 어셈블리가 소유하는 데몬 풀 | 일회성 작업용. 의미는 [수명 §7.5](workspace-sandbox-lifecycle.md). 호스트가 주면 빌려 쓰고 닫지 않는다 |
| `clock` | 시스템 UTC | 테스트용 |

`sessionRecords` 는 `SessionRecordStore` 를 바로 받거나 `Supplier` 로 받는다. `Supplier` 는 처음 필요할 때 푼다. 코어 스택은 보통 이 어셈블리보다
늦게 만들어지기 때문이다.

워크스페이스 레코드를 쓰는 것은 매니저 하나뿐이다. `SandboxWorkspaces` · `SandboxAdministration` · `SandboxWorkspaceFiles` 는 얇은 겉면이고,
권한 검사 · 주체 게이트 · CAS 루프 · 이벤트 발행은 모두 매니저에 있다. 그래서 owner 면과 운영자 면이 같은 규칙을 같은 코드로 지킨다.

`WorkspaceSandbox` 는 세션 옵션 저장소를 꺼내 주는 getter 를 두지 않는다. 빌더는 저장소를 받지만, 공개된 저장소는 세션에 대한 권한(§3)을
건너뛰는 길이 되기 때문이다. 세션 옵션은 `workspaces()` 와 운영자 API 로만 읽고 쓴다.

### 1.2 코어에 넘기기

```java
// 손으로 만든 런타임
runtimeBuilder.executionEnvironmentProvider(sandbox.environmentProvider());

// aimon-bootstrap
ExecutionEnvironmentSpec.shared(sandbox.environmentProvider());
```

aimon-bootstrap 에는 `shared(...)` 로 넘기고 `provider(Supplier)` 로 넘기지 않는다. 닫아야 하는 것은 환경 제공자가 아니라 janitor · 스케줄러 ·
연결을 가진 `WorkspaceSandbox` 전체이고, 그것은 호스트가 소유하기 때문이다. 스택이 제공자 하나를 닫아도 나머지는 결국 호스트가 닫아야 한다.

### 1.3 세션 수명 리스너

```java
SessionSpec.builder().lifecycleListener(sandbox.sessionLifecycleListener());   // 또는 SessionRouterBuilder, 또는 빈
```

`sessionLifecycleListener()` 는 어셈블리마다 하나다. 코어의 세션 라우터가 세션을 지우면 이 리스너가 그 세션의 옵션을 지우고 `ws:{sessionId}` 를
닫고 지운다. 무엇을 어떤 순서로 지우는지, 라우터 밖에서 지운 세션과 잃어버린 이벤트를 무엇이 마무리하는지는 [수명 §7.4](workspace-sandbox-lifecycle.md)가
정한다. 호스트가 할 일은 리스너를 거는 것뿐이다. 걸지 않으면 지운 세션의 옵션은 운영자의 `deleteSessionOptions`(§4.3) 나 janitor 의 스윕이
지울 때까지 남는다.

### 1.4 실행 하나를 배치하기

호스트는 세션 옵션을 쓰지 않고 요청 속성으로 실행 하나를 워크스페이스에 둘 수 있다.

```java
live.submit(prompt, SubmitOptions.builder()
        .executionAttributes(SandboxRequestAttributes.workspace(ws.id())).build());

schedulingSpec = schedulingSpec.withRoutineExecutionAttributes((task, executionId) ->
        placementOf(task.getId()).map(SandboxRequestAttributes::workspace).orElse(Map.of()));
```

요청 속성은 그 실행에만 적용되고 세션 옵션을 쓰지 않는다. 필요한 권한은 사용 권한뿐이다. 우선순위 안에서의 자리, 허용하는 키, 모델 출력으로
채우면 안 되는 이유는 [바인딩 §2](workspace-sandbox-binding.md)가 정한다.

### 1.5 닫는 순서

```java
coreStack.close();   // 먼저
sandbox.close();
```

코어 스택(또는 그 런타임)을 먼저 닫고 `WorkspaceSandbox` 를 나중에 닫는다. 스택 종료는 아직 도는 백그라운드 명령을 그 셸을 통해 멈추는데, 샌드박스
셸은 이 어셈블리가 쥔 연결로 명령에 닿기 때문이다. 반대로 닫으면 멈춤 요청이 실패하고 그 명령은 샌드박스가 끝날 때까지 돈다. 코드는 반대 순서를
막지 않는다. 잘못된 순서로 문서에 적는다.

같은 이유로 런타임은 자유롭게 오갈 수 있다. 환경 제공자는 런타임 단위로 쥐는 것이 없어서(`bindRuntime` 은 코어 기본값을 쓴다,
[개요 §6.4](workspace-sandbox.md)) 런타임 하나를 축출해도 도는 명령은 멈추지 않고 샌드박스도 그대로다.

`WorkspaceSandbox.close()` 는 다음 순서로 닫는다. 샌드박스는 건드리지 않는다 — 샌드박스는 워크스페이스만큼 산다.

1. janitor 를 멈춘다.
2. 소유한 배경 실행기를 닫는다. 새 작업을 받지 않고, 유한한 시간 동안 남은 작업을 기다린 뒤 끊는다. 이렇게 끊긴 세션 삭제는 표시가 남아 다음 실행의
   janitor 가 마치고, 끊긴 `lastWorkspaceId` 기록은 notice 를 한 번 반복할 뿐이다([수명 §7.5](workspace-sandbox-lifecycle.md)).
3. 노드의 연결 캐시를 닫는다.
4. 소유한 스케줄러를 닫는다.
5. 소유한 프로바이더를 닫는다(`ownProvider(true)` 일 때만).

---

## 2. 관리형 워크스페이스

`WorkspaceSandbox.workspaces()` 가 owner 면 `SandboxWorkspaces` 를 준다. 모든 호출은 호출 주체를 받고, 그 주체는 실행과 같은 주체 게이트를 지난다.

| 메서드 | 권한 | 하는 일 |
|---|---|---|
| `create(CreateWorkspace, caller)` | — (호출자가 owner) | 새 `wsm:` id 로 만들고 `primary` 를 프로비저닝한 뒤 돌려준다 |
| `provision(id, caller)` | 사용 | 실패한 `create` 의 재시도 |
| `describe(id, caller)` | 사용 | 없거나 쓸 수 없으면 빈 값 |
| `list(caller, WorkspaceQuery)` | — | 호출자가 owner 인 워크스페이스만, id 순 |
| `setSessionOptions` · `clearSessionOptions` · `sessionOptions` | 세션에 대한 권한 | §3 |
| `close(id, caller)` | 관리 | 샌드박스를 없애고 CLOSED |
| `reopen(id, caller)` | 관리 | 다시 열고 프로비저닝 |
| `purge(id, caller)` | 관리 | CLOSED 인 관리형 레코드를 영구히 지움 |
| `files()` | 사용 | 워크스페이스 파일 API(§6) |

사용 권한과 관리 권한의 규칙은 [바인딩 §5](workspace-sandbox-binding.md)가 정한다.

### 2.1 id 와 이름

관리형 워크스페이스의 id 는 `create` 가 `wsm:{UUID}` 로 만든다. 호출자가 고르지 않고, 한 번 쓴 id 는 다시 쓰지 않는다. 전역 이름 공간에서 호출자가
id 를 고르면 다른 테넌트가 이름을 선점할 수 있고, 존재 여부가 드러나며, CLOSED 레코드가 만료된 뒤 정의가 아직 가리키는 id 를 남이 다시 만들 수
있기 때문이다. id 를 다시 쓰지 않으므로 그 id 를 가리키는 세션 옵션이나 에이전트 정의가 나중에 다른 워크스페이스로 바뀌어 향하는 일이 없다.
id 형식은 [도메인 §1](workspace-sandbox-domain.md)에 있다.

호출자는 `CreateWorkspace` 로 표시용 이름을 준다.

| 필드 | 규칙 |
|---|---|
| `name` | 필수. 출력 가능한 문자 1–80자. 유일하지 않아도 된다 |
| `profile` | 없으면 `default-profile`. 설정에 없는 프로파일이면 `IllegalArgumentException` |
| `access` | 없으면 배포의 `workspace-access`. `principal` 배포에서 `tenant` 는 `allow-shared-workspaces` 가 필요하다(공유는 opt-in, [바인딩 §6](workspace-sandbox-binding.md)). `principal` 로 좁히는 것은 늘 허용한다 |

사용자가 준 `name` 은 모델이 보는 문자열(notice, 도구 오류)에 넣지 않는다. 모델에게는 id 만 보인다.

### 2.2 만들기와 프로비저닝

```
create(request, owner, actor):
1. who = 주체 게이트(owner)
2. profile 이 설정에 있는가, access tenant 가 허용되는가        -- 실패하면 아무것도 쓰지 않는다
3. who 의 관리형 워크스페이스 수 < max-managed-per-owner        -- 최선 노력(admission 처럼)
4. 레코드 생성: wsm:{UUID}, OPEN, origin MANAGED, name · profile · access · quota
   WORKSPACE_CREATED(actor)
5. provision(id, who)
6. 뷰를 돌려준다 — primary RUNNING
```

5단계는 connect 상태 기계를 명령 없이 돌린다(즉시 프로비저닝, [수명 §2.3](workspace-sandbox-lifecycle.md)). 기다림의 상한은 `provisionTimeout` 이다([설정 §2](workspace-sandbox-configuration.md)).
5단계가 실패하면 레코드는 남고(슬롯은 FAILED, admission 이 거절했으면 슬롯 없음) `WorkspaceProvisioningException` 이 새 워크스페이스의 id 를 싣고
던져진다. 호출자는 `provision(id)` 로 다시 시도하거나(기록된 실패의 backoff 를 따른다) `purge` 한다. admission 이 거절한 레코드도 지울 때까지
`max-managed-per-owner` 에 센다. 개수는 모든 상태를 세고, 저장소의 scan 결과를 필터로 다시 검사한다 — 필터를 무시하는 저장소도 있기 때문이다.

호스트의 HTTP 호출이 `create` 도중에 시간이 끝나도 프로비저닝은 계속된다. `create` 는 언제나 새 id 를 만들므로 같은 호출을 되풀이하면 워크스페이스가
둘 생긴다. 응답을 잃은 호출자는 `list`(이름, 생성 시각)로 찾는다. 비동기 · 멱등 `create` 는 [수명 §9](workspace-sandbox-lifecycle.md)의 열린 질문이다.

레코드의 `profile` 은 매니저가 새 `primary` 를 만들 때, 바인딩이 프로파일을 요구하지 않으면 적용한다. 그 레코드로 향하는 모든 바인딩에 같다.
바인딩 정책이 적용하면 bind 마다 워크스페이스 저장소를 한 번 더 읽어야 하지만 매니저는 이미 레코드를 쥐고 있기 때문이다. `provision(id)` 는 그
프로파일을 아직 없는 슬롯에만 요구한다. 정의의 `sandbox.profile` 이 먼저 만든 슬롯은 그대로 받는다.

### 2.3 닫기 · 다시 열기 · 지우기

- `close` 는 샌드박스를 없애고 레코드를 CLOSED 로 둔다. 붙은 세션의 도구는 다시 열 때까지 "the sandbox workspace is closed" 를 받는다.
- `reopen` 은 다시 연 뒤 프로비저닝한다. 다시 연 관리형 워크스페이스는 쓰려고 연 것이기 때문이다.
- idle close 는 관리형에도 적용된다. 다음 사용이 자동으로 다시 연다. janitor 는 관리형 CLOSED 레코드를 지우지 않는다.
- `purge` 는 관리형 · CLOSED 레코드만 지우고, 그 뒤 모든 세션의 그 워크스페이스 attachment 를 지운다. OPEN 이면 거부한다("close it before purging it").
  파생 레코드는 purge 할 수 없다. 절차 · 순서 · 동시 reopen · 실패한 둘째 단계를 마무리하는 스윕은 [수명 §7.3](workspace-sandbox-lifecycle.md)이 정한다.
- 없는 id 의 `purge` 는 `close` 처럼 성공한다. 응답을 잃은 뒤의 재시도가 멱등이어야 하기 때문이다.

**관리형 워크스페이스의 파일은 아직 샌드박스를 넘어 남지 않는다.** 슬롯은 프로파일의 `terminateAfter` 를 따른다. 만들고 쓰지 않은 샌드박스도
생성에서 `terminateAfter` 가 지나면 끝나고, 다음 사용은 빈 `/workspace` 로 새 generation 을 만든다(늘 하는 "재생성" notice 와 함께). 이 사실은
`WorkspaceView` 의 슬롯 상태로 보인다. **(계획 — 구현 단계 5)** 공유 볼륨이 생기면 파일이 샌드박스를 넘어 남는다.

---

## 3. 세션 옵션 API

세션 옵션은 세션을 워크스페이스에 붙이는 세션별 레코드다. 필드와 별도 저장소에 두는 이유는 [도메인 §3](workspace-sandbox-domain.md), 바인딩이 그것을
읽는 순서는 [바인딩 §2](workspace-sandbox-binding.md), 세션이 옮겨 갔다고 모델에게 알리는 방법은 [notice §3](workspace-sandbox-notices.md)이 정한다.

### 3.1 권한과 소유자 출처

세션을 다른 워크스페이스로 돌리는 것은 워크스페이스가 아니라 세션에 대한 권한이다. 세션의 owner 만 그 옵션을 쓰고 지우고 읽는다(운영자는 §4.3).
호출자는 접근 모드와 무관하게 세션 owner 와 테넌트 · principal 이 모두 같아야 한다.

세션 owner 는 배선된 소유자 출처에서 온다 — 코어가 기록한 owner 가 먼저, 호스트 `SessionOwnerLookup` 은 owner 없이 기록된 세션용 폴백이다
([바인딩 §4](workspace-sandbox-binding.md)). 출처가 하나도 없으면 세션 옵션 호출은 기동이 아니라 호출 때 거부된다("session options need a session
owner lookup"). 출처가 그 세션을 모르면 "unknown session" 이다.

`sessionRecords(...)` 만 배선하면 세션 레코드는 첫 submit 때 생기므로 그 전에는 옵션을 쓸 수 없다. 첫 턴을 요청 속성으로 배치하거나(§1.4),
`SessionRecordStore.provision(id, agentRef, owner)` 로 레코드를 미리 만들거나, `SessionOwnerLookup` 을 폴백으로 둔다(열린 질문은
[바인딩 §9](workspace-sandbox-binding.md)).

### 3.2 `setSessionOptions`

```
setSessionOptions(session, update, caller):
1. who = 주체 게이트(caller)
2. sessionOwner = 소유자 출처(session)        -- 없으면 "unknown session"
   who == sessionOwner (테넌트 · principal)     -- 세션에 대한 권한
3. update.workspace 가 있으면:
     wsm: id 인가(ManagedIds)                    -- 파생 id 는 거부 (이유: 바인딩 §2)
     레코드가 있는가                             -- 없으면 "does not exist"
     OPEN 이거나 idle close 된 CLOSED 인가       -- CLOSING · 명시적 CLOSED 는 "reopen 한 뒤에"
     who 와 sessionOwner 둘 다 사용 권한이 있는가 -- 일관성 검사
4. CAS 로 쓴다(충돌하면 다시 읽고 다시 적용)
   SESSION_OPTIONS_SET(actor = who)
```

`Update` 는 `workspace` 와 `unattached` 두 필드를 모두 바꾼다. 주지 않은 필드는 지운다. `lastWorkspaceId` 와 `unannouncedReset` 은 그대로 둔다.
"set" 의 뜻을 하나로 두어 부분 패치의 모호함을 없애기 위해서다. `Update.attach(id)` · `Update.rule(rule)` · `Update.of(id, rule)` 로 만들고, 둘 다
빈 `Update` 는 clear 다.

옵션을 바꿔도 샌드박스는 건드리지 않는다. 세션이 떠난 워크스페이스는 자기 idle 정책이 끝낼 때까지 돈다. 이미 도는 턴은 끝날 때까지 자기 바인딩을
지키고, 그 턴이 시작한 백그라운드 명령은 옛 워크스페이스에서 계속 돈다.

옵션은 세션 owner 가 쓰지만 각 턴은 connect 에서 그 턴을 **실행하는** 주체로 다시 검사한다. 여러 참여자가 있는 세션에서 owner 가 아닌 참여자의 턴은
그 참여자가 붙은 워크스페이스를 쓸 수 있을 때만 통과한다([바인딩 §5](workspace-sandbox-binding.md)).

### 3.3 `clearSessionOptions` 와 `sessionOptions`

`clearSessionOptions` 는 `workspace` 와 `unattached` 를 지운다. `lastWorkspaceId` 가 비어 있으면 떼어 내는 워크스페이스로 채운다. drain 된 전환의
비동기 기록이 clear 에 CAS 를 지더라도 다음 턴이 세션이 옮겨 갔다는 것을 듣게 하기 위해서다([notice §3](workspace-sandbox-notices.md)). 그 뒤
`lastWorkspaceId` 도 `unannouncedReset` 도 남지 않은 레코드만 지운다. 아무 필드도 없는 레코드는 턴마다 읽기 한 번만 쓰게 하기 때문이다.
이벤트는 `SESSION_OPTIONS_CLEARED` 다.

`sessionOptions` 의 권한도 세션 owner 다. 그렇지 않으면 같은 테넌트의 다른 주체가 남의 세션이 어디서 도는지 알게 된다.

owner 면에는 세션 옵션을 통째로 지우는 메서드가 없다. 세션과 함께 지우는 일은 세션 수명 리스너(§1.3)가 한다.

---

## 4. 운영자 API

### 4.1 얻는 법과 성격

```java
SandboxAdministration admin = SandboxAdministration.of(sandbox);
```

운영자 API 는 `WorkspaceSandbox` 의 getter 가 아니라 따로 만드는 객체다. `WorkspaceSandbox` 는 모든 통합 코드가 쥐므로 getter 로 두면 누구에게나
열린다. 운영자를 가리키는 설정(`admin-principals`)도 두지 않는다. principal id 는 역할이 아니고, 누가 운영자인지는 호스트의 인증 계층이 정하기 때문이다.
그래서 호스트는 이 객체를 자기 운영자 인가 뒤에서만 내놓는다. JVM 안의 보안 경계는 아니다 — 같은 프로세스의 코드는 언제든 매니저에 닿는다. 별도의
API 표면일 뿐이다.

운영자 API 는 actor 의 owner · 테넌트를 검사하지 않는다. 그 이유와 대신 거는 검사는 [바인딩 §5](workspace-sandbox-binding.md)가 정한다. 모든 쓰기는
actor 주체를 받아 이벤트에 기록한다(§7).

| 메서드 | 하는 일 |
|---|---|
| `workspaces(AdminWorkspaceQuery)` | 모든 워크스페이스, id 순 |
| `workspace(id)` | `WorkspaceDetail` — 워크스페이스와 붙은 세션의 첫 페이지 |
| `sessions(id, after, limit)` | 붙은 세션을 세션 id 순으로 페이지 |
| `sandboxes()` | 프로바이더가 이 deployment 로 돌리는 모든 샌드박스와 판정(§4.4) |
| `files()` | 운영자의 워크스페이스 파일 API(§6) |
| `create(request, owner, actor)` | owner 대신 만든다 |
| `setSessionOptions` · `clearSessionOptions` · `deleteSessionOptions` | 아무 세션의 옵션(§4.3) |
| `terminateSlot(id, slot, actor)` | 슬롯 하나의 샌드박스를 끝낸다(§4.5) |
| `close` · `reopen` · `purge` | owner 면과 같은 동작, 권한 검사 없음 |

`workspace(id)` 는 레코드와 세션 목록을 따로 읽는다. 둘 사이에 purge 가 끼면 이미 사라진 워크스페이스의 세션이 보일 수 있다(purge 가 곧이어 지운다).
첫 페이지 크기는 `SandboxAdministration.DETAIL_SESSIONS` 다.

### 4.2 대신 만들기와 다시 열기

`create` 는 owner 를 주체 게이트에 통과시킨다. 배포가 거부하는 주체가 owner 인 워크스페이스가 생기지 않게 하기 위해서다. admission · 쿼터 ·
`max-managed-per-owner` 는 owner 에게 적용한다. `GROUP` owner 도 받지만 `principal` 접근에서는 어떤 USER 호출자도 그것과 같지 않으므로 그런 워크스페이스는
`access: tenant` 가 필요하다.

`reopen` 은 레코드 owner 를 먼저 지금의 주체 게이트에 통과시키고(거부되면 아무것도 바뀌지 않는다), 다시 연 뒤 owner 로서 프로비저닝한다. 운영자에게는
검사할 자기 사용 권한이 없고, 프로비저닝 비용은 워크스페이스 주인에게 매겨야 하며, 배포가 이제 거부하는 주체는 샌드박스를 받지 않아야 하기
때문이다 — 대신 만들기와 같다.

### 4.3 세션 옵션

운영자의 `setSessionOptions` · `clearSessionOptions` 는 세션 owner 와의 일치 검사만 건너뛴다. 소유자 출처는 여전히 필요하고(배선되지 않았거나 세션을
모르면 거부), `setSessionOptions` 는 일관성 검사를 한다 — 세션 owner 가 쓸 수 없는 워크스페이스에 붙이면 그 owner 의 모든 턴이 거부되기 때문이다.

`setBy` 는 테넌트 resolver 가 푼 actor 다. resolver 가 actor 의 테넌트를 모르면 세션 owner 의 테넌트에 둔 actor 다. `setBy` 는 `WorkspaceOwner` 라
테넌트가 있어야 하기 때문이다. 이벤트의 `actor` 도 같은 푼 형태이고, 풀지 못하면 `TYPE:id` 그대로다.

`deleteSessionOptions(session, actor)` 는 레코드를 `lastWorkspaceId` · `unannouncedReset` 까지 통째로 지운다. 코어 라우터 밖에서 지운 세션(원시
`SessionRecordStore.delete`, 리스너를 걸지 않은 호스트)을 치우는 운영자의 수단이다. 이 호출만 소유자 출처를 요구하지 않는다. `sessionRecords(...)` 만
배선한 호스트에서는 정확히 이 세션들에 "unknown session" 이 나고, 소유자 출처가 없는 단일 테넌트 호스트에서도 요청 속성이 `lastWorkspaceId` 레코드를
남기기 때문이다. 이벤트의 owner 는 출처가 아는 owner, 모르면 레코드의 `setBy` 다. 같은 세션은 `sessionRecords(...)` 를 배선했다면 janitor 의 스윕도
지운다([수명 §7.4](workspace-sandbox-lifecycle.md)).

### 4.4 모든 샌드박스 보기

`sandboxes()` 는 저장소를 끝까지 scan 한 뒤 프로바이더를 `{managed: true, deployment}` 라벨로 나열한다. janitor 의 조정과 같은 순서다
([수명 §6.2](workspace-sandbox-lifecycle.md)). 프로바이더의 샌드박스마다 `aimon.at/workspace` 라벨로 레코드를 찾고, 조정과 같은 판정을 **유예 전**
값으로 붙인다. 레코드가 없으면 `ORPHAN` 이다. 판정의 뜻과 규칙은 수명 문서가 정한다.

- 판정은 공개 enum `SandboxVerdict`(`SKIP` · `OK` · `IN_FLIGHT` · `ORPHAN` · `STALE` · `FAILED_LEFTOVER` · `DUPLICATE`)로 보인다. 조정기의 내부 타입을
  드러내지 않는다. 드러내면 뒤에 판정을 더할 때 운영자 API 가 깨지기 때문이다.
- 유예 전 판정을 보인다. 조정기의 `firstSeen` 은 노드 로컬이라 겉면에서 볼 수 없고, 순수한 판정만 재현할 수 있기 때문이다. 그래서 레코드보다 앞선
  generation 의 샌드박스는 janitor 가 `orphan-grace` 뒤 고아로 올리더라도 여기서는 `IN_FLIGHT` 로 읽힌다.
- `pastGrace` 는 janitor 가 유예를 기다리는 것 — `ORPHAN`, `DUPLICATE`, 레코드보다 앞선 `IN_FLIGHT` — 에만 붙고, 프로바이더가 `createdAt` 을 줄 때만
  있다. `STALE` · `FAILED_LEFTOVER` 는 유예 없이 다음 회차에 없어지므로 붙지 않는다.
- 운영자 목록은 따로 분류를 만들지 않고 조정기의 판정을 그대로 보인다. 따로 분류하면 조정기와 어긋나고, 만들어지는 중인 샌드박스를 낡은 것으로
  부르게 된다.
- 목록은 정보일 뿐이다. 아무것도 지우지 않는다. 없애는 것은 janitor 뿐이다.
- `provider.list` 가 실패하면 호출이 실패한다. 일부만 보여 샌드박스를 숨기는 결과를 내지 않는다.
- 비용은 호출마다 O(레코드 + 샌드박스)이고 캐시하지 않는다. 운영자 호출이지 주기적으로 부를 것이 아니다.

### 4.5 슬롯 끝내기

`terminateSlot(id, slot, actor)` 는 슬롯을 CAS 로 TERMINATED 로 바꾼 뒤 샌드박스를 없앤다. 원인은 "terminated by administrator" 이고 `TERMINATED`
이벤트가 `actor` 를 싣는다. 워크스페이스는 OPEN 으로 남고 다음 사용이 빈 `/workspace` 로 새 generation 을 만든다. 그 안에서 돌던 명령은 "lost" 로
실패한다.

- 없는 워크스페이스는 "does not exist", 없는 슬롯은 "has no slot" 이다. 오타가 성공하면 안 되기 때문이다.
- PROVISIONING 슬롯은 거부한다. CAS 직전에 프로비저닝을 시작한 경우도 CAS 안에서 다시 보고 거부한다. 프로비저닝하는 쪽의 발밑에서 끝내면 그쪽이
  만든 샌드박스가 고아가 되고 곧바로 다시 프로비저닝하기 때문이다(버려진 claim 은 janitor 가 거둔다).
- 이미 TERMINATED 인 슬롯은 그대로 둔다.
- FAILED 슬롯을 끝내면 기록된 실패와 backoff 를 지운다. 다음 사용이 곧바로 다시 시도한다.

---

## 5. 뷰 모델

owner 면과 운영자 면은 내부 레코드가 아니라 뷰를 돌려준다.

```
WorkspaceView(id, name?, owner, origin, access, state, closeCause?, createdAt, lastActivityAt, slots: List<SlotView>)
SlotView(name, profile, state, generation, seeded, failure?: (kind, step, message), lastActivityAt)
SessionOptionsView(session, setBy, workspace?, unattached?, updatedAt)
WorkspaceDetail(workspace: WorkspaceView, sessions: Page<SessionOptionsView, SessionId>)
SandboxView(refHash, providerState, expiresAt?, createdAt?, workspaceId?, slot?, generation?, verdict, pastGrace?)
WorkspaceQuery(states, origin?, namePrefix?, after?, limit ≤ 500)
AdminWorkspaceQuery(states, origin?, namePrefix?, tenant?, owner?, after?, limit ≤ 500)
Page<T, K>(items, next?: K)
WorkspaceFile(path, name, sandboxPath, directory, size, modifiedAt, etag?, readOnly)
WorkspaceFileListing(directory: WorkspaceFile, entries, truncated)
```

- `WorkspaceView.access` 는 실효 접근 모드다 — 레코드의 것, 없으면 배포의 것.
- `SessionOptionsView` 는 레코드에서 version 과 notice 장부(`lastWorkspaceId`, `unannouncedReset`)를 뺀 것이다. 관리 화면에서 미전달 초기화가 보이지
  않는 문제는 [notice §8](workspace-sandbox-notices.md)의 열린 질문이다.
- `Page` 의 `next` 는 그 목록의 키 타입이다(`byWorkspace` · `sessions` 는 `SessionId`, `list` · `workspaces` 는 `SandboxWorkspaceId`). `after` 인자가
  타입을 가지므로 타입 없는 커서는 다시 파싱해야 하기 때문이다. 마지막 페이지에는 `next` 가 없다.
- `AdminWorkspaceQuery` 는 `WorkspaceQuery` 를 조합하지 않고 일곱 필드를 가진 독립 클래스다. 두 빌더를 조합해 얻는 것이 없고 저장소 scan 은 평평한
  필드를 받기 때문이다.
- `WorkspaceFile.path` 는 `/workspace` 기준 상대 경로이고 `""` 가 `/workspace` 자체다. `WorkspaceFileListing.entries` 는 디렉터리 먼저, 그다음 이름 순이다.

**어떤 뷰도 프로바이더의 샌드박스 id 를 싣지 않는다.** 자격 증명 · seed 인자 · 엔드포인트도 싣지 않는다. owner 뷰는 슬롯 상태와 generation 만
보이고, 운영자의 `SandboxView` 는 샌드박스를 `refHash = h(sandboxId)` 로, 라벨이 레코드로 풀리면 워크스페이스 · 슬롯 · generation 으로 가리킨다. id 를
숨기는 이유는 [보안 §7](workspace-sandbox-security.md)이 정한다. 원시 id 가 필요한 운영자는 프로바이더 콘솔을 쓴다. `SlotView.failure` 에 기록되는
프로바이더 오류 문구도 샌드박스 id 를 해시로 바꿔 둔다.

---

## 6. 워크스페이스 파일 API

### 6.1 범위

`SandboxWorkspaceFiles` 는 사람이 쓰는 파일 탐색기의 백엔드다. 워크스페이스의 실행 중인 `primary` 샌드박스에서 `/workspace` 아래를 나열 · stat ·
읽기 · 검색 · 쓰기 · 디렉터리 만들기 · 옮기기 · 지우기 한다. 에이전트용이 아니다. 에이전트의 파일 도구는 실행 안에서만 있고 모델에게 말하며,
프로바이더 콘솔은 원시 샌드박스 id 를 요구한다. 사람이 에이전트가 만든 것을 보고 입력 파일을 넣으려면 워크스페이스가 이미 가진 권한 뒤에서 같은
연산이 필요하다.

이 API 는 새 프로바이더 SPI 를 더하지 않는다. `SandboxFiles` 로 충분하다. REST 엔드포인트와 화면은 호스트 몫이고 이 저장소에 없다. 대화형 개발
환경(터미널 · 포트 포워딩 · 브라우저 미리보기)을 다루지 않는다는 비목표와의 관계는 [개요 §1.2](workspace-sandbox.md)가 정한다.

| 면 | 얻는 곳 | 검사 | 이벤트의 actor |
|---|---|---|---|
| owner 면 | `sandbox.workspaces().files()` | 사용 권한(§6.6) | 테넌트 resolver 가 본 호출자 |
| 운영자 면 | `SandboxAdministration.of(sandbox).files()` | 없음 | 운영자 주체, 필수 |

두 면은 같은 코드다. 모든 메서드는 워크스페이스 id 를 먼저, 호출 주체를 마지막에 받는다.

```java
Optional<WorkspaceFile> stat(id, path, caller);
WorkspaceFileListing list(id, directory, limit, caller);                 // limit 1..MAX_LIST(10 000)
InputStream read(id, path, offset, length, caller);                      // length < 0: 끝까지
List<WorkspaceFile> search(id, directory, pattern, maxResults, caller);  // maxResults 1..MAX_SEARCH(1 000)
WorkspaceFile write(id, path, content, length, overwrite, caller);
WorkspaceFile createDirectory(id, path, caller);
WorkspaceFile move(id, from, to, overwrite, caller);
void delete(id, path, recursive, caller);
```

범위의 경계와 그 이유:

- **도는 것만 보인다.** 샌드박스가 RUNNING 이고 seed 된 동안만 닿는다. 파일이 아직 샌드박스를 넘어 남지 않으므로(§2.3) 샌드박스가 없는 워크스페이스는
  보여 줄 것이 없다.
- **`/workspace` 만.** 이미지의 나머지(`/usr`, `/etc`, `/tmp`)는 에이전트의 작업이 아니다.
- **사용량 요약이 없다.** 원격 트리의 크기를 재려면 파일마다 걸어야 한다.
- **모델에게 알리지 않는다**(§6.5).
- **`primary` 만.** **(계획 — 구현 단계 5)** 다중 슬롯과 `/shared` 가 생기면 슬롯 인자와 둘째 루트가 더해진다.

다음 대안은 쓰지 않는다.

- 파일 API 는 에이전트의 connect 를 쓰지 않는다. connect 는 없는 레코드를 만들고, 프로비저닝하고, idle close 된 워크스페이스를 다시 열어(모델이 들어야
  할 초기화를 일으켜) 다른 노드의 프로비저닝을 기다린다 — 보기만 하는 사람에게는 모두 틀린 동작이다.
- 코어 VFS 를 건네지 않는다. 목록이 이름만 주어 항목마다 원격 stat 이 들고, 범위 읽기 · 새 파일 전용 쓰기 · 재귀 삭제가 없다.
- 파일 API 는 프로비저닝하지 않는다. 새 샌드박스의 빈 `/workspace` 는 볼 것이 없고 샌드박스만 쓴다. 시작이 필요하면 호스트가 `provision` 을 부른다.

### 6.2 프로비저닝 없이 닿기

호출마다 레코드를 한 번 읽고 다음 판정을 차례로 한다.

1. 레코드가 없거나 호출자에게 사용 권한이 없다 → `NOT_FOUND`. 운영자 면은 권한을 건너뛴다.
2. CLOSING → `CLOSING`. OPEN 이 아닌 나머지 → `CLOSED`. idle close 된 워크스페이스를 다시 열지 **않는다**.
3. `primary` 가 없거나, RUNNING 이 아니거나, seed 되지 않았다 → `NOT_RUNNING`. PROVISIONING 슬롯을 기다리지 않는다.
4. 슬롯의 프로파일이 설정에서 사라졌다 → `UNREACHABLE`.
5. 연결을 얻는다. 프로바이더가 샌드박스가 없다고 하면 슬롯을 소실로 표시하고(generation 으로 보호, 파일 도구와 같다) `NOT_RUNNING`. 다른 프로바이더
   실패는 `UNREACHABLE`.

판정 ①–④ 는 아무것도 쓰지 않는다. ⑤ 의 소실 표시는 탐색기가 일으킨 사실이 아니라 발견한 사실을 기록한다. 다음 에이전트 턴은 어차피 들었을
"샌드박스가 다시 만들어졌다" 를 듣는다. 이렇게 얻은 파일 핸들은 그 generation 에 묶이고, 그것을 거치는 모든 프로바이더 호출이 같은 실패 매핑을 받는다.
저장소 읽기 자체가 실패하면 `UNREACHABLE` 이다.

### 6.3 읽기

- `list` 는 디렉터리를 stat 하고(없으면 `FileNotFoundException`, 파일이면 `InvalidPathException`) 재귀 없이 `limit + 1` 개를 요청한다. `limit` 보다
  많으면 `truncated` 다. 가진 것을 정렬하지만 어느 항목이 잘렸는지는 프로바이더의 순서가 정한다.
- `read` 는 먼저 stat 하고(없음, 디렉터리) 호출자가 읽는 만큼 **4 MiB 씩** 범위 읽기로 가져오는 스트림을 준다. 다운로드를 호스트 메모리에 통째로 두지
  않기 위해서다 — OpenSandbox 는 범위 읽기를 한 바이트 배열로 답한다([OpenSandbox §3.3](workspace-sandbox-opensandbox.md)). 가져오기마다 같은 실패
  매핑을 받으므로 다운로드 도중의 실패는 `read()` 에서 `WorkspaceFilesUnavailableException` 으로 나온다. 스트림은 요청한 길이나 첫 짧은 덩어리에서
  끝난다. 다운로드 중에 커지는 파일은 각 덩어리를 가져온 시점까지 읽는다.
- `search` 는 `directory` 아래를 재귀 목록 한 번(최대 100 000 항목)으로 걷고 각 항목의 **이름**을 맞춘다. 더 많으면 트리 일부로 답하지 않고 실패한다.
  패턴은 먼저 코어의 `SearchPatterns` 로 정리해 코어 `Glob` 과 같게 읽는다. `*` 나 `?` 가 남으면 glob 이고, 아니면 원래 패턴을 대소문자 무시 부분
  문자열로 맞춘다 — 검색창이 뜻하는 것이다(코어가 지우는 `[` · `{` 는 그래서 `report[1]` 을 정확 일치로 바꾸지 않는다). 디렉터리도 맞을 수 있고, 검색하는
  디렉터리 자체는 맞지 않는다. OpenSandbox 의 재귀 목록은 깊이 64 에서 멈추므로 더 깊은 항목은 검색되지 않는다([OpenSandbox §3.3](workspace-sandbox-opensandbox.md)).

읽기는 활동을 기록하지 않는다. 열어 둔 탐색기가 idle close 를 막지 않게 하기 위해서다(활동 기록의 정의는 [도메인 §6](workspace-sandbox-domain.md)).

### 6.4 경로와 읽기 전용

호출자의 경로는 `/workspace` 기준 상대 경로다. 코어의 `VfsPaths.normalizeRelative` 로 정규화한다(`.` · `..` · 겹친 슬래시와 끝 슬래시 · `\` 를 `/` 로).
밖으로 나가는 경로, NUL 바이트, `/` 로 시작하는 경로는 `InvalidPathException` 이다. 절대 경로는 추측하지 않고 거부한다. `/workspace/repo` 와 `repo` 가
헷갈리지 않게 하기 위해서다. 프로바이더의 답도 상대 경로로 되돌리고, `/workspace` 밖의 답은 보이지 않고 실패한다.

`/workspace` 자체와 최상위의 모든 `.aimon-*` 항목 — `.aimon-shell`(세션마다의 cwd 와 export, 모든 명령이 source 한다), `.aimon-staged`(스테이징한 스킬),
`.aimon-seed.lock` — 은 `readOnly()` 다. 나열하고 읽을 수 있지만, 그 안이나 그것으로의 쓰기 · mkdir · 삭제 · 이동은 `FileAccessDeniedException` 이다.

이 범위는 에이전트 파일 도구의 읽기 전용 영역([파일 시스템 §3](workspace-sandbox-filesystem.md))보다 넓다. 두 규칙은 합치지 않는다. 사람이 실수로
`.aimon-shell` 에 올린 파일은 이후 모든 명령에서 실행되기 때문에 사람 쪽은 접두어 전체를 막는다. 실수 방지일 뿐 보안 경계가 아니다. 에이전트는 같은
샌드박스를 쓰고 `repo/x` 를 `.aimon-shell` 로 가는 심볼릭 링크로 바꿀 수 있으며, 프로바이더는 여느 경로처럼 그것을 따라간다.

### 6.5 변경

`write` · `createDirectory` · `move` · `delete` 는 stat 으로 전제 조건(존재, 디렉터리, 비어 있음, `move` 가 자기 안으로 가지 않음)을 확인한 뒤 다음을
한다.

1. 슬롯의 활동을 기록한다. 스로틀되며, `lastActivityAt` 과 프로바이더 만료를 미룬다 — 에이전트의 파일 호출과 같다. 여기서 샌드박스가 없다고 나오면
   `NOT_RUNNING` 이다. 샌드박스 소실이 아닌 실패(워크스페이스 저장소가 CAS 재시도를 넘겨 바쁘거나 실패함)는 `UNREACHABLE` 이지 소실이 아니다 —
   샌드박스는 멀쩡하고 재시도가 통할 수 있다.
2. 변경한다.
3. `FILES_CHANGED` 이벤트를 낸다. 워크스페이스 · 슬롯 · generation · actor 와, 연산과 경로를 담은 원인(예: `wrote repo/in/data.csv (4 bytes)`,
   `moved repo/a to repo/b`)을 싣는다. 길이는 호출자가 준 값이고 `-1` 이면 적지 않는다. 변경이 돌아오자마자, 결과를 만드는 stat 전에 낸다. 그 stat 이
   실패해도 이미 일어난 변경이 감춰지지 않게 하기 위해서다. 아무것도 바뀌지 않으면(이미 있는 디렉터리의 `createDirectory`) 내지 않는다.

`move` 는 옮기기 전에 목적지의 없는 부모를 스스로 만든다. SPI 는 프로바이더의 move 가 그렇게 한다고 약속하지 않기 때문이다. `write` 는 프로바이더처럼
`length` 를 받고(모르면 `-1`) 스트림을 프로바이더가 읽고 닫는다. 업로드 크기의 상한은 호스트가 호출 전에 건다.

확인과 변경은 원자적이지 않다. 동시에 도는 에이전트가 사이에서 같은 경로를 바꿀 수 있고, 그때는 프로바이더의 답이 정한다(`CREATE_NEW` 는 있는 파일을
여전히 거부한다). 사람이 바꿨다는 것은 모델에게 알리지 않는다. 대신 코어의 `Edit` · `Write` 는 모델이 읽은 뒤 바뀐 파일을 덮어쓰지 않으므로
([파일 시스템 §5](workspace-sandbox-filesystem.md)), 모델의 파일 도구와 부딪히면 조용히 덮어쓰지 않고 "다시 읽으라" 는 오류로 드러난다. 에이전트의
셸(`sed -i`, `>`)에는 그런 검사가 없다. 모델이 알아야 하는 호스트는 다음 프롬프트에서 알린다.

### 6.6 권한

owner 면은 호출자를 `SandboxWorkspaces` 처럼 풀고(`require-principal` 이 적용된다) **사용** 권한을 검사한다
([바인딩 §5](workspace-sandbox-binding.md)). 파일을 바꾸는 것은 관리가 아니라 사용이다. 그 워크스페이스에서 세션을 돌릴 수 있는 자는 이미 셸로
모든 파일을 바꿀 수 있기 때문이다. 세션에서 파생된 `ws:{sessionId}` 는 세션 owner 가 소유하므로 같은 규칙이 "내 세션의 샌드박스 보기" 를 덮는다.
없는 워크스페이스와 쓸 수 없는 워크스페이스는 같게 `NOT_FOUND` 로 답한다([바인딩 §8](workspace-sandbox-binding.md)).

운영자 면은 아무것도 검사하지 않고, actor 를 요구해 기록한다. 다른 `SandboxAdministration` 쓰기와 같다.

### 6.7 사용할 수 없을 때

샌드박스에 닿을 수 없으면 `WorkspaceFilesUnavailableException`(`SandboxUnavailableException` 의 하위 타입)이 `Reason` 을 싣는다. 화면은 메시지가 아니라
`reason()` 으로 갈린다.

| Reason | 언제 | 재시도 |
|---|---|---|
| `NOT_FOUND` | 워크스페이스가 없거나 호출자가 쓸 수 없다 — 둘을 같게 답한다 | 하지 않는다 |
| `NOT_RUNNING` | OPEN 이지만 `primary` 가 없음 · PROVISIONING · TERMINATED · FAILED · seed 전 · 소실 | 하지 않는다. 보여 줄 상태다. 다음 에이전트 사용이나 `provision` 이 샌드박스를 시작한다 |
| `CLOSING` | 닫는 중 | 하지 않는다 |
| `CLOSED` | idle 이든 명시적이든 닫혔다. 파일은 사라졌다 | 하지 않는다 |
| `UNREACHABLE` | 프로바이더나 워크스페이스 저장소가 실패하거나 바쁘다, 또는 슬롯의 프로파일이 설정에서 사라졌다 | 할 만하다 |

파일 단위 실패는 에이전트 파일 도구처럼 코어 VFS 예외로 남는다: `FileNotFoundException` · `FileAlreadyExistsException` · `InvalidPathException` ·
`FileAccessDeniedException` · `VirtualFileSystemException`.

---

## 7. 수명 이벤트

`SandboxEventListener`(빌더의 `eventListener`)가 샌드박스 수명 이벤트를 받는다. 변경을 만든 스레드에서 동기로 불린다. 리스너가 던지는 예외는
(`LinkageError` 도) 로그를 남기고 삼키며 도구 호출로 번지지 않는다. 다른 버전으로 빌드한 리스너가 자기가 듣는 동작을 실패시키면 안 되기 때문이다.
애플리케이션은 이 이벤트를 자기 감사 로그로 보낸다.

감사 서브시스템은 따로 만들지 않는다. 에이전트의 명령과 파일 쓰기는 코어의 도구 훅(PostToolUse)과 tracing 이 이미 남긴다. 워크스페이스 통합 이벤트
스트림(명령 · 파일 변경 · 커밋)도 두지 않는다. 그런 스트림은 저장소를 하나 더 만들고 순서 · 보존 정책을 새로 정해야 하며, 에이전트 사이의 조율은
`/shared` 와 git 이 맡기 때문이다. 이 모듈은 수명 이벤트만 낸다.

### 7.1 이벤트 종류

| 타입 | 언제 |
|---|---|
| `PROVISIONED` | 슬롯의 샌드박스를 만들고 검증했다 |
| `TERMINATED` | 슬롯의 샌드박스를 끝냈다(idle, close, 운영자) |
| `LOST` | 슬롯의 샌드박스가 사라진 것을 발견했다 |
| `ORPHAN_DESTROYED` · `DUPLICATE_DESTROYED` | 조정이 고아 · 중복을 없앴다([수명 §6.2](workspace-sandbox-lifecycle.md)) |
| `WORKSPACE_CLOSED` | 워크스페이스가 CLOSED 에 닿았다 |
| `WORKSPACE_REOPENED` | 워크스페이스가 OPEN 으로 돌아왔다(명시적으로, 또는 idle close 뒤 자동으로) |
| `WORKSPACE_CREATED` | 관리형 레코드를 만들었다 |
| `WORKSPACE_PURGED` | 관리형 레코드를 영구히 지웠다, 또는 지운 세션의 파생 레코드를 지웠다 |
| `SESSION_OPTIONS_SET` · `SESSION_OPTIONS_CLEARED` | 세션 옵션을 썼다 · 지웠다 |
| `FILES_CHANGED` | 사람이 파일 API 로 파일을 바꿨다(§6.5) |
| `PAUSED` **(계획 — 구현 단계 7)** | 슬롯을 멈췄다 |
| `VOLUME_DELETED` **(계획 — 구현 단계 5)** | 공유 볼륨을 지웠다 |

`FILES_CHANGED` 는 사람이 파일 API 로 바꾼 파일마다 하나씩 낸다. 사람의 변경에는 도구 호출이 없어 코어 훅이 남는 기록이 없기 때문이다. 자체 저장 ·
순서 · 보존을 가진 스트림이 아니며, 에이전트의 쓰기는 이벤트를 내지 않는다.

### 7.2 필드

이벤트는 타입 · 워크스페이스 · 슬롯 · generation · owner · 프로파일 · 원인 · 시각과, 해당하면 `session` · `actor` 를 담는다.

- **owner** 는 모든 이벤트에 있다. 세션 옵션 이벤트에서는 세션의 owner 다. 테넌트별 감사는 owner 로 가른다.
- **워크스페이스** 가 없을 수 있는 것은 세션 옵션 이벤트(규칙만 쓴 set, 그런 레코드의 clear)뿐이다. 그것은 `workspace()`(Optional)로 읽는다.
  `workspaceId()` 와 `owner()` 의 반환 타입은 바꾸지 않는다. 바꾸면 이미 컴파일된 리스너와 바이너리 호환이 깨지기 때문이다. 그래서 워크스페이스가 없는
  세션 이벤트에서 `workspaceId()` 는 `IllegalStateException` 을 던진다.
- **session** 은 세션 옵션 이벤트에 있다.
- **actor** 는 주체가 요청한 것에 있다 — 운영자의 모든 쓰기, owner 의 create · close · reopen · purge 와 세션 옵션 쓰기, 그 close 가 낸 `TERMINATED`,
  `FILES_CHANGED`. 형식은 `tenant/TYPE:id`, 테넌트를 모르면 `TYPE:id` 다. janitor 와 idle 정책이 낸 것에는 없다. 감사 로그가 요청된 전이와 시스템
  스스로의 전이를 가를 수 있게 하기 위해서다.

### 7.3 세션 삭제가 내는 이벤트

세션 수명 리스너(§1.3)의 삭제는 원인 "session deleted" 로, actor 없이 다음을 낸다.

- 옵션 레코드가 있었으면 `SESSION_OPTIONS_CLEARED`. owner 는 코어가 기록한 owner, 없으면 레코드의 `setBy`.
- 파생 워크스페이스의 `WORKSPACE_CLOSED` 는 close 절차가 레코드를 CLOSED 로 만들 때 한 번만 낸다. 삭제 표시를 하는 단계는 이벤트를 내지 않는다.
- 레코드를 지울 때 `WORKSPACE_PURGED`. 지연 단계가 지우든 janitor 가 지우든 같다.

janitor 의 지운 세션 스윕과 purge 된 attachment 스윕은 이벤트를 내지 않고 로그만 남긴다([수명 §6](workspace-sandbox-lifecycle.md)).

### 7.4 span 과 메트릭을 두지 않는다

이 모듈은 수명 이벤트만 내고 span 과 메트릭은 내지 않는다. span · 메트릭은 소비처가 생기면 그 요구로 설계한다. Micrometer 의존성과 라벨 설계가 함께
따라오기 때문이다.

그때 메트릭 라벨에는 `profile` · `operation` · `outcome` · 판정 같은 닫힌 집합만 쓴다. 워크스페이스 · 슬롯 · shellKey · owner 는 라벨로 달지 않는다 —
워크스페이스는 세션 수만큼 생기고 슬롯 이름은 사용자가 정한다.

---

## 8. 실패

### 8.1 관리형 워크스페이스 · 세션 옵션 · 운영자

| 경우 | 결과 |
|---|---|
| `create` 의 이름이 틀림 · 프로파일이 설정에 없음 | `IllegalArgumentException`. 아무것도 쓰지 않는다 |
| `principal` 배포에서 `allow-shared-workspaces` 없이 `access: tenant` 로 `create` | `SandboxConfigurationException`. 아무것도 쓰지 않는다 |
| 주체 게이트가 owner 를 거부 | `BindingRejectedException`. 아무것도 쓰지 않는다 |
| `max-managed-per-owner` 초과 | `SandboxUnavailableException`(상한을 밝힘). 아무것도 쓰지 않는다 |
| admission · 쿼터가 첫 프로비저닝을 거부 | `WorkspaceProvisioningException(id)`. 레코드는 남고 슬롯은 없다 |
| 프로비저닝 실패 | `WorkspaceProvisioningException(id)`. 슬롯은 FAILED 와 기록된 실패. `provision(id)` 가 다시 시도한다 |
| 호스트의 HTTP 호출이 `create` 도중 시간 초과 | 프로비저닝은 계속된다. 호출자는 `list` 로 찾는다 |
| `create` 도중 이벤트 리스너가 던짐 | 모든 이벤트처럼 삼키고 로그(§7) |
| 소유자 출처 없이 · 모르는 세션에 · owner 아닌 호출자가 `setSessionOptions` | `BindingRejectedException`("session options need a session owner lookup" / "unknown session" / "only the owner …"). 아무것도 쓰지 않는다 |
| 형식이 틀린 id 로 `setSessionOptions` | `BindingRejectedException`. 아무것도 쓰지 않는다 |
| CLOSING · 명시적 CLOSED · 없는 워크스페이스로 `setSessionOptions` | `SandboxUnavailableException` |
| 세션 owner 가 쓸 수 없는 워크스페이스로 `setSessionOptions`(운영자 경로 포함) | `BindingRejectedException` |
| 두 `setSessionOptions` 경합 | CAS — 진 쪽이 다시 읽고 다시 적용한다. 마지막 쓰기가 이기고 둘 다 이벤트를 낸다. CAS 재시도를 넘기면 `SandboxUnavailableException`(옵션이 바쁨) |
| 명시적 close 뒤 붙은 세션의 턴 | reopen 할 때까지 도구 오류 "the sandbox workspace is closed; the application has to reopen it …"([수명 §8](workspace-sandbox-lifecycle.md)) |
| purge 뒤 붙은 세션의 턴 | 스윕이 옵션을 지울 때까지 "does not exist"(`mustExist`, [바인딩 §1](workspace-sandbox-binding.md)), 그 뒤로는 unattached 규칙 |
| `sandbox.workspace` 가 purge 된 id · 형식이 틀린 id | 그 에이전트의 모든 루트 요청이 "does not exist" / 정의를 밝힌 거부 |
| 세션 옵션 저장소를 쓸 수 없음 | 세션의 루트 bind 가 `SandboxUnavailableException`([바인딩 §8](workspace-sandbox-binding.md)) |
| OPEN 워크스페이스의 `purge` | 거부("close it before purging it") |
| 관리형이 아닌 워크스페이스의 `purge` | 거부 — 파생 레코드는 `closedRetention` 이 지나면 만료된다 |
| `purge` 와 `reopen` 경합 | delete 의 CAS 가 실패한다. 워크스페이스는 남고 `purge` 가 그것을 보고한다 |
| `sandboxes()` 에서 `provider.list` 실패 | 호출이 프로바이더 오류로 실패한다 |
| `terminateSlot` 의 대상이 없음 · 슬롯이 없음 · PROVISIONING | `SandboxUnavailableException`(§4.5) |
| `terminateAfter` 가 짧은 프로파일로 `create` | 허용한다. 끝난 것은 `WorkspaceView` 의 슬롯 상태로 보인다 |

### 8.2 워크스페이스 파일 API

| 상황 | 결과 |
|---|---|
| 레코드를 읽은 뒤 호출 전에 프로바이더에서 샌드박스가 만료됨 | 슬롯을 소실로 표시, `NOT_RUNNING`. 다음 에이전트 턴이 다시 만든다 |
| 변경 전에 프로바이더가 내려갔거나 느림 | `UNREACHABLE`. 아무것도 바뀌지 않았다 |
| 변경 도중이나 뒤에 프로바이더가 실패(쓰기는 됐는데 시간 초과) | `UNREACHABLE`. 변경은 일어났을 수 있다 — 프로바이더 호출이 돌아왔으면 `FILES_CHANGED` 는 나갔다 |
| 활동 기록에서 워크스페이스 저장소가 바쁘거나 실패 | `UNREACHABLE`. 아무것도 바뀌지 않았고 슬롯은 RUNNING 그대로다 |
| 사람이 보는 동안 idle close | 다음 호출이 `CLOSED`. 아무것도 다시 열지 않는다 |
| 사람이 에이전트가 고치는 파일을 씀 | 사람의 쓰기가 들어간다. 에이전트의 다음 `Edit` · `Write` 는 낡은 읽기로 거부된다 |
| `limit` 보다 많은 항목의 디렉터리 | 프로바이더 순서로 처음 `limit` 개, `truncated = true` |
| 100 000 항목보다 큰 트리의 검색 | `VirtualFileSystemException` — 디렉터리를 좁힌다 |

---

## 9. 열린 질문

- **디렉터리 통째 다운로드**(zip · tar). 연결로 `tar` 를 exec 하면 되지만, 사람을 대신해 에이전트의 샌드박스에서 명령을 돌리게 된다. 호스트가 요구할
  때까지 두지 않는다.
- **모델에게 알리기.** 호스트가 고른 notice 를 세션의 다음 도구 결과에 싣는 방법은 환경 notice 큐를 쓰므로 붙일 세션이 필요하다. 워크스페이스에는
  세션이 여럿일 수도 없을 수도 있다.
- **OpenSandbox 의 파일 단위 stat 오류.** 프로바이더의 stat 은 404 만 "없음" 으로 본다. execd 가 파일을 지나는 경로(`a.txt/b`, `ENOTDIR`)에 다른 상태를
  답하면 `UNREACHABLE` 로 나온다. execd 에서 확인하지 않았다(확인 필요: `ENOTDIR` 경로에 execd 가 답하는 상태 코드). 그 Reason 에 기대기 전에 docker
  계층 케이스로 고정한다([OpenSandbox §3.3](workspace-sandbox-opensandbox.md)).

다른 문서가 소유하는 관련 질문: 비동기 · 멱등 `create` 는 [수명 §9](workspace-sandbox-lifecycle.md), 첫 submit 전의 세션 옵션은
[바인딩 §9](workspace-sandbox-binding.md), 관리 화면에 보이지 않는 미전달 초기화는 [notice §8](workspace-sandbox-notices.md), 메모리 세션 옵션
저장소의 한 노드 제한은 [도메인 §4.4](workspace-sandbox-domain.md)에 있다.
