# 워크스페이스 샌드박스 — 바인딩

어느 실행이 어느 워크스페이스·슬롯을 어떤 주체로 쓰는지를 다룬다. 바인딩 정책과 우선순위, 포크 규칙, 주체와 테넌트, 사용·관리 권한,
신뢰 도메인이 여기 있다. 레코드의 필드는 [도메인](workspace-sandbox-domain.md)이, 바인딩이 가리키는 워크스페이스를 실제로 프로비저닝하는
절차는 [수명](workspace-sandbox-lifecycle.md)이 소유한다.

> 관련: [개요](workspace-sandbox.md) · [도메인](workspace-sandbox-domain.md) · [관리](workspace-sandbox-management.md) ·
> [보안](workspace-sandbox-security.md) · [수명](workspace-sandbox-lifecycle.md) · aimon-core
> [실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)

## 1. 바인딩 계약

**바인딩**(`SandboxBinding`)은 한 실행이 쓸 `(workspaceId, owner, slot, requiredProfile?, shellKey, root)` 와 그 실행의 주체인 `caller` 다
(용어는 [개요 §3](workspace-sandbox.md)). 매니저의 owner 검사(§5)가 `caller` 를 레코드의 owner 와 비교한다. 바인딩은 실행과 함께 버리며
원격 자원을 쥐지 않는다.

```java
public interface SandboxBindingPolicy {
    SandboxBinding bind(BindingContext ctx);                       // root requests: workspace, owner, slot, profile

    default SlotChoice forkSlot(SandboxBinding parent, ForkDefinition fork) {   // forks: slot and profile only
        return SlotChoice.fromAttributes(fork.attributes(), parent);
    }
}
```

`BindingContext` 는 환경 제공자(`SandboxExecutionEnvironmentProvider`)가 코어의 `EnvironmentRequest` 에서 옮겨 담는다 — 에이전트?,
`AgentRuntimeId`, 세션 id?, 실행 id?, 호출한 세션 id?, `Principal`?, 정의 속성, 요청 속성(§2). 코어가 채우는 조합은 넷이다.

| 요청 | 채워지는 것 | 이 모듈의 처리 |
|------|-----------|--------------|
| 메인 턴 | 에이전트 · 세션 · 주체 · 요청 속성 | `bind` |
| 스케줄 루틴 | 에이전트 · 실행 id · 주체 · 요청 속성 | `bind` |
| 포크(부모 환경 있음) | 실행 id · 호출한 세션 id · 주체 · 부모 환경 · `fork()` | 부모 바인딩을 물려받고 `forkSlot` 만 묻는다(§3) |
| 포크(부모 환경 없음) | 실행 id · 주체 · `fork()` | **사용 불가**(§3). 부모 환경 없는 런타임 범위 워크플로 러너의 단계가 그렇다 |

`resolve` 는 바인딩을 만들고 환경을 돌려줄 뿐, **프로바이더를 부르지 않는다.** 워크스페이스 저장소를 최대 한 번, 세션이 있으면 세션 옵션
저장소를 한 번 읽는다. 원격 자원은 첫 파일·셸 호출의 `connect` 가 만든다([수명 §2.3](workspace-sandbox-lifecycle.md)). 바인딩을 만들 수 없으면
코어의 `ExecutionEnvironmentUnavailableException` 으로 던지고, 코어는 사용 불가 환경을 넣어 도구가 그 이유로 실패하게 한다 — 호스트로
물러서지 않는다([개요 §7](workspace-sandbox.md)).

**바인딩을 만드는 것은 정책뿐이다.** 포크도 슬롯은 정책의 `forkSlot` 이 고르므로, 애플리케이션이 주입한 정책은 포크에도 규칙(테넌트별 슬롯
제한 등)을 걸 수 있다. 다만 포크의 워크스페이스 · owner · root 는 정책이 아니라 환경 제공자가 부모에서 강제로 물려준다 — 정책이 포크를 다른
워크스페이스로 보낼 수 없다. **도구 인자는 바인딩에 들어가지 않는다.** 그래서 프롬프트 인젝션으로 모델이 다른 워크스페이스의 id 를 불러도
닿을 경로가 없다. 설정된 정책을 감싸는 `SessionOptionsBindingPolicy`(§2)도 정책이라 이 규칙은 그대로다.

**`caller` 는 정책이 무엇을 넣었든 환경 제공자가 요청의 principal 로 덮어쓴다**(루트·포크 모두, 주체 검사 §4 를 거친 값). 정책이 `caller` 에
owner 를 넣을 수 있으면 owner 검사와 `require-principal` 을 우회하기 때문이다.

**`mustExist`** 는 파생하지 않은 워크스페이스를 가리키는 바인딩(§2 의 ⓪①②)의 표지다. 이 바인딩의 `connect` 는 레코드가 없으면 만들지 않고
"does not exist" 로 답한다([수명 §2.1](workspace-sandbox-lifecycle.md)). 만들게 두면 purge 된 관리형 id 가 바인딩한 호출자의 소유로 되살아나기
때문이다. `toBuilder()` 가 이 표지를 복사하므로 환경 제공자가 다시 만든 바인딩과 포크도 물려받는다.

**실행마다 환경은 하나다.** 한 실행이 여러 슬롯에서 명령을 돌리는 경로(실행↔샌드박스 N:M)는 두지 않는다. 파일 도구와 셸이 같은 것을 본다는
불변식이 실행 단위로 성립해야 하기 때문이다. 다른 슬롯의 일은 그 슬롯에 바인딩된 서브에이전트 포크에 맡긴다. 다른 에이전트를 다른 슬롯에
붙이는 경로는 에이전트 정의의 `sandbox.slot` 과 워크플로 단계가 싣는 정의 속성뿐이다(등록된 정의가 정한 키는 워크플로 스크립트가 덮지
못한다). 모델이 `Task` 인자로 임의 슬롯을 넘기는 경로는 열지 않는다(§9). `primary` 밖의 슬롯은 다중 슬롯과 함께 쓸 수 있다
([도메인 §2.3](workspace-sandbox-domain.md)).

**워크플로 격리 브랜치는 정책을 타지 않는다.** 코어가 부모 환경의 `isolate()` 로 만든다. 그래서 `branchKey` 는 바인딩에 쓰지 않는다.
지금 `isolate()` 는 늘 비어 있음을 돌려준다 — worktree 는 [파일 시스템 §7](workspace-sandbox-filesystem.md) (계획 — 구현 단계 7).

## 2. 우선순위

`WorkspaceSandbox` 는 설정된 정책을 `SessionOptionsBindingPolicy` 로 감싼다. 루트 요청은 아래에서 처음 적용되는 행이 정한다.

| 행 | 출처 | 누가 정하나 | 적용 범위 | 바인딩 |
|---|---|---|---|---|
| ⓪ | 요청 속성 `environment.sandbox.workspace` | 호스트, submit · 루틴 실행마다 | 그 실행만(메인 턴 또는 루틴, 포크는 아님) | `wsm:` 만 · 호출자를 owner 로 · `mustExist` · 정의의 slot/profile |
| ① | 세션 옵션의 `workspace` | 세션 소유자(또는 운영자) | 그 세션의 모든 루트 턴 | 같음, shellKey `session:{id}` |
| ② | 정의 속성 `sandbox.workspace` | 정의 작성자 | 그 에이전트의 모든 루트 실행 | 같음 |
| ③ | 실효 unattached 규칙 | — | ⓪–② 가 없을 때 | `per-session` → 감싼 정책의 바인딩(기본 정책이면 §3), `reject` → 사용 불가 |

**실효 unattached 규칙**은 ⓪–② 가 적용되지 않을 때만 본다.

```
rule = strictest(배포 설정 unattached, 정의 sandbox.unattached, 세션 옵션 unattached, 요청 environment.sandbox.unattached)
```

**각 층은 위의 층을 조이기만 한다.** 배포 설정이 바닥이다. 정의 작성자는 호스트에 따라 테넌트일 수 있으므로 운영자의 `reject` 를 끌 수
없다. 세션 소유자는 호스트 API 로 운영자와 에이전트가 허락하는 범위 안에서 세션마다 고른다. 요청의 `per-session` 은 세션이나 정의의 `reject`
를 풀지 못하고, 요청의 `reject` 는 아무것도 붙지 않은 그 실행을 거부한다. 배포 수준의 기본 *워크스페이스*는 없다 — 배포 설정은 규칙에만
참여한다. 세션 옵션의 "규칙" 은 레코드의 `unattached` 필드다. `workspace` 나 `lastWorkspaceId` 만 가진 레코드, bookkeeping 레코드
([도메인 §3](workspace-sandbox-domain.md))는 규칙에 아무것도 더하지 않는다.

**⓪①② 는 관리형 `wsm:` id 만 받는다.** 파생 `ws:` id 는 툼스톤이 만료된 뒤 다시 쓰이므로, 옵션이나 정의가 가리키면 다른 워크스페이스로
조용히 옮겨 갈 수 있다. 관리형 id 는 생성되고 다시 쓰이지 않는다([관리 §2](workspace-sandbox-management.md)). 형식이 틀린 값은 그 출처
(요청 속성, 정의 이름)를 적어 바인딩을 거부한다. ⓪①② 의 바인딩은 호출자를 owner 로 쓰고 `mustExist` 이며, `sandbox.slot` ·
`sandbox.profile` 의 뜻은 그대로다. 실행 주체가 그 워크스페이스에 들어갈 수 있는지는 `connect` 의 사용 권한 검사(§5)가 정한다.

**정의 속성**은 코어가 정의 파일의 `attributes:` 블록을 점 표기 키로 평탄화한 것이다. 이 모듈이 읽는 키는 `sandbox.slot` · `sandbox.profile`
· `sandbox.workspace` · `sandbox.unattached` 다. `definitionAttributes()` 는 포크면 서브에이전트의, 아니면 에이전트의 속성을 준다. 워크플로
단계도 정의 속성을 싣는다.

**요청 속성**은 호스트가 실행 하나에 붙이는 배치 입력이다. 코어는 메인 턴이면 `SubmitOptions` 의 실행 속성에서, 루틴이면 호스트의
`RoutineExecutionAttributes` 에서 `environment.` 로 시작하는 키만 접두사를 떼어 넘긴다. 코어가 보장하는 계약: 값은 null 이 아니고 String 이며,
잘못된 값이면 제공자를 부르기 전에 환경을 사용 불가로 만든다. 포크 · 워크플로 단계 · CLI 탐침에는 늘 비어 있다. 요청 속성은 호스트가 주는
값이므로 **모델 출력으로 채우지 않는다** — 이 모듈은 모델이 만든 값인지 가려내지 않으며, 권한 검사는 호스트가 무엇을 주든 적용된다.

**요청 키는 `sandbox.workspace` 와 `sandbox.unattached` 둘뿐이고, 그 밖의 `sandbox.*` 키는 거부한다.** `sandbox.profile` 은 저신뢰 정의가
신뢰 도메인을 고정하는 수단이라 submit 마다 덮게 하면 권한 결정이 정의 작성자에게서 옮겨 가고, 조용히 버린 배치 입력은 더 나쁜 실패다.

**요청 속성은 그 실행만 옮기고 세션 옵션을 쓰지 않는다.** 실행 단위 값을 영속 상태로 바꾸면 다른 주체의 이후 턴까지 옮기게 되고, 그것은
세션 옵션을 소유자에게만 허락하는 바로 그 권한이다. 요청 속성이 요구하는 권한은 §5 에 있다.

**세션이 있는 루트 요청은 세션 옵션 저장소를 한 번 읽는다.** 그 읽기가 실패하면 `per-session` 으로 넘어가지 않고 사용 불가로 답한다. 넘어가면
붙어 있던 사용자가 다른 워크스페이스로 들어가기 때문이다. ⓪ 이 적용되는 요청도 같다 — 워크스페이스 전환 notice 가 레코드를 필요로 하고, 한
규칙이 단순하고 안전하다. 바인딩이 마지막으로 알린 워크스페이스와 다른 곳에 놓이면 전환을 싣는다([notice §3](workspace-sandbox-notices.md)).

**포크는 행이 아니다.** 포크는 부모의 바인딩을 물려받는다 — ⓪ 으로 묶인 부모의 포크도 부모의 워크스페이스에 머문다(§3).

## 3. 기본 정책과 포크

`DefaultSandboxBindingPolicy` 의 루트 `bind` 규칙이다. 포크가 아닌 요청에만 적용한다.

| 항목 | 규칙 |
|------|-----|
| workspaceId | 세션에서 결정론적으로 — `ws:{sessionId}`. 세션이 없고 실행 id 가 있는 실행(스케줄 루틴)은 `ws:{executionId}`. **둘 다 없는 요청은 사용 불가**다 — 워크스페이스를 지어낼 근거가 없는 요청에 공유될 id 를 만들지 않는다 |
| owner | `(tenantId, TYPE:id)`. 메인 턴은 **세션의 소유자**, 루틴은 요청의 주체(§4). tenantId 는 `SandboxTenantResolver` 가 정한다 |
| slot | 정의 속성 `sandbox.slot` 이 있으면 그것, 없으면 `primary` |
| profile | `sandbox.profile` 이 있으면 그것이 **요구 프로파일**이고, 설정된 프로파일이어야 한다. 없으면 요구가 없고, 슬롯을 처음 만들 때 설정의 기본 프로파일을 쓴다(§5 프로파일 요구) |
| shellKey | 메인 턴: `session:{sessionId}` — 턴을 넘어 cwd 가 유지된다. 루틴: `exec:{executionId}`. 요청 속성(⓪)으로 묶인 메인 턴도 `session:{sessionId}` 다 — 코어가 메인 턴 요청에 실행 id 를 싣지 않기 때문이다 |
| root | `/workspace/repo`. 워크플로 격리 브랜치의 root 는 `isolate()` 가 정한다 |

**workspaceId 를 결정론적으로 만들어 세션→워크스페이스 매핑 저장소가 필요 없다.** 세션이 다른 노드로 옮겨가도 같은 id 가 다시 계산된다.
여러 세션에 걸치는 워크스페이스(티켓 하나에 세션 여럿)가 필요한 애플리케이션은 자기 도메인에서 id 를 찾는 정책을 주입한다 — 그 매핑은
애플리케이션의 데이터다. 결정론적 id 는 **파생 워크스페이스**의 이야기다. 관리형 워크스페이스에는 요청 속성 · 세션 옵션 · 정의 속성으로
닿고(§2), 그래서 루틴도 `ws:{executionId}` 대신 관리형 워크스페이스에 묶일 수 있다.

**포크인지는 `fork().isPresent()` 로 판단한다.** 부모 환경이 있는지로 판단하면 부모 없는 포크가 "실행 id 가 있는 루트 요청" 으로 보여, 단계마다
`ws:{executionId}` 로 빈 워크스페이스와 새 샌드박스를 만들고 쿼터를 따로 쓴다.

**포크는 부모의 워크스페이스를 물려받는다.** 부모 환경의 바인딩에서 workspaceId · owner · root 와 `mustExist` 를 그대로 물려받고, shellKey 만
`exec:{executionId}` 로 바꾼다 — 부모 셸을 오염시키지 않는다. 슬롯은 정책의 `forkSlot` 이 정한다. 기본 구현은 `ForkDefinition` 의
`sandbox.slot` 속성이 있으면 그것, 없으면 부모의 슬롯이다. 슬롯이 부모와 같으면 정책이 무엇을 골랐든 부모의 root 를 쓰고, 다르면 그 슬롯의
기본 root(`/workspace/repo`)다 — 다른 슬롯은 다른 샌드박스이기 때문이다. 포크가 몇 단계로 중첩되어도, 부모가 세션 없는 실행(스케줄 루틴)이어도
같은 워크스페이스에 머문다. 호출한 세션 id 로 워크스페이스를 다시 계산하면 세션 없는 부모의 포크가 자기 실행 id 로 새 워크스페이스를 만들게
된다.

포크는 `BindingContext` 를 만들지 않는다. 그래서 요청 속성을 실은 포크 요청이 와도 부모의 워크스페이스에 바인딩된다. 포크의 바인딩은 부모의
워크스페이스 전환 notice 와 미전달 초기화 표시를 싣지 않는다 — 부모의 환경이 알리고 기록한다([notice §3](workspace-sandbox-notices.md),
[§5](workspace-sandbox-notices.md)).

**포크의 `caller` 는 포크 요청의 principal 을 루트 요청과 같은 주체 검사(§4)에 통과시킨 값이다.** 요청에 principal 이 없어도 부모의 `caller` 를
물려받지 않는다 — `require-principal` 이면 거부되고, 아니면 principal 없는 루트처럼 익명으로 돈다. 어느 쪽이든 owner 검사가 그 `caller` 로 부모의
워크스페이스를 쓸 수 있는지 다시 본다. 그래서 포크는 부모 워크스페이스가 허락하는 것과 다른 주체로 돌 수 없다. principal 없는 포크를 위한
대체 경로를 두지 않는다. 코어의 포크 경로는 모두 루트 환경을 만든 요청 principal 을 그대로 싣고(루틴이면 작업 소유자), principal 이 없는 환경은
부모 환경도 없는 에이전트 범위 워크플로 러너 하나뿐이라 주체 검사 전에 거부된다.

**부모 환경이 없거나, 사용 불가이거나, `SandboxExecutionEnvironment` 가 아니면(다른 제공자의 환경) 포크도 사용 불가다.** 부모가 있으면 그 원인을
그대로 싣는다. 부모를 만들지 못한 이유 — 주체 거부, 쿼터, 프로바이더 장애 — 를 포크가 정책으로 다시 계산해 우회하면 안 되기 때문이다.

## 4. 주체와 테넌트

**테넌트는 주입받는다.** 코어 `Principal` 은 `type` 과 `id` 만 갖고 테넌트 필드가 없다. 그래서 애플리케이션이
`SandboxTenantResolver.resolve(Principal) → TenantId` 를 주입한다. 멀티 테넌트 배포(`require-principal: true`)에서는 필수이고, 없으면 기동을
거부한다([설정 §3](workspace-sandbox-configuration.md)). 단일 테넌트 배포의 기본 구현은 모든 주체를 한 테넌트로 본다.

**owner · caller · actor.** owner 는 워크스페이스의 소유자, caller 는 그 실행(또는 API 호출)의 주체다. 둘 다 `WorkspaceOwner(tenantId, principal)`
이다. actor 는 운영자 쓰기 등에서 이벤트를 일으킨 주체로, [관리 §7](workspace-sandbox-management.md) 이 소유한다.

**주체는 타입과 id 로 식별한다.** owner 와 caller 는 principal 을 `TYPE:id`(`USER:alice`, `GROUP:eng`, `SYSTEM:system`)로 기록하고
`(tenant, TYPE:id)` 로 비교한다. 코어가 주체를 type 과 id 로 식별하므로(`Principal#equals`), id 만 비교하면 같은 id 의 USER 와 GROUP, SYSTEM 주체와
이름이 같은 USER, 주체 없는 실행과 `anonymous` 라는 USER 가 서로의 검사를 통과한다. 주체 없는 실행의 owner 는 타입 없는 `anonymous` 여서
`USER:anonymous` 와 다르다. 저장소가 owner 를 영속하면 이 형태를 그대로 쓴다. `WorkspaceOwner.parse` 는 타입 없는 id(`anonymous` 제외)와 빈 id 를
거부한다.

**owner 는 먼저 부른 쪽이 정하지 않는다.** 기본 정책은 메인 턴의 owner 를 세션의 소유자에서 가져온다. 출처는 둘이고 이 순서로 묻는다.

```
owner(session) = recorded(sessionRecords).orElse(hostLookup)      // 배선된 것만, 이 순서로
```

- **코어가 기록한 소유자** — 코어는 세션 레코드에 소유자를 기록한다(`SessionRecordView.getOwner()`). 세션 레코드를 만드는 submit 에 호스트가
  `SubmitRequest.owner(...)` 를 실었을 때만, 처음 값으로 기록하고, 지우지 않으며, 강제하지 않는다. `WorkspaceSandbox.Builder.sessionRecords(...)` 를
  주면 이 출처를 쓴다. 소유자만 읽고(`SessionRecordStore.loadOwner`) 대화 기록을 포함한 레코드 전체를 읽지 않는다. 세션 레코드는 메인 턴이 바인딩될
  때 이미 있으므로 첫 submit 에 실은 소유자가 첫 턴부터 보인다
- **호스트의 `SessionOwnerLookup.ownerOf(SessionId)`** — 코어가 소유자를 기록하지 않은 세션의 대체 출처

| 배선 | 소유자를 알 때 | 어느 출처도 소유자를 모를 때 |
|---|---|---|
| 둘 다 없음(단일 테넌트만) | — | 요청 주체가 `ws:{sid}` 의 owner. 세션 옵션 쓰기는 거부 |
| 코어 기록 · 호스트 lookup 중 하나 이상 | 그 소유자 | **바인딩 거부**("set `SubmitRequest.owner(...)` …"). 세션 옵션 쓰기도 거부 |

**어느 출처도 소유자를 모르는 세션은 `require-principal` 과 상관없이 바인딩을 거부한다 — 요청 주체로 물러서지 않는다.** 그것이 이 규칙이 막으려는
"먼저 부른 쪽이 owner" 이기 때문이다. 그러면 레코드가 없을 때(`InMemory` 재시작, CLOSED 레코드 삭제 뒤) 세션 id 를 아는 다른 주체가 먼저 불러도
owner 는 세션 소유자로 정해지고, 그 주체는 검사에서 막힌다. 같은 id 의 재생성은 명시적 close 의 툼스톤이 `closedRetention` 동안 막는다
([수명 §7.2](workspace-sandbox-lifecycle.md)). `require-principal: true` 이면 두 출처 중 하나가 기동 시 필수다. 단일 테넌트 배포에서 둘 다 없으면 요청
주체를 owner 로 쓴다 — 먼저 부른 쪽이 owner 가 되는 약점을 받아들이는 구성이다. 출처의 저장소가 예외를 던지면 바인딩이 실패하고(사용 불가 환경),
세션 옵션 호출에는 그 예외가 간다.

코어가 기록한 소유자를 기본 출처로 쓰되 `SessionOwnerLookup` SPI 는 대체 출처로 남긴다. 신원 공급자를 가진 호스트가 이미 쓰고 있고, 호스트가
`SubmitRequest.owner` 를 넣기 전에 만들어진 세션은 기록된 소유자가 없기 때문이다. `SessionOwnerLookup` 은 런타임이 제시하는 그대로의 principal 을
돌려줘야 한다 — owner 를 type + id 로 비교하므로, GROUP 이나 SERVICE 주체로 도는 세션에 `Principal.user(id)` 를 답하면 `workspace-access: principal`
에서 모든 턴이 "not permitted" 가 된다.

**노드 로컬 소유자 캐시는 두지 않는다.** 소유자는 한 세션 레코드의 수명 동안만 불변이라, 같은 id 의 삭제·재생성(다른 노드는 듣지 못한다) 뒤 낡은
소유자가 새 세션의 `ws:{sid}` 레코드에 굳어 끈질긴 거부가 된다.

**주체가 없거나 시스템 주체인 실행.** 코어에서 `Principal` 은 선택값이고, Spring 스타터의 기본 진입점은 요청을 `Principal.system()` 으로 제출한다.
그래서 `require-principal: true` 는 "주체가 있다" 가 아니라 **"`USER` 또는 `GROUP` 주체다"** 를 뜻한다. 주체가 없거나 `SYSTEM` · `SERVICE` 인 실행은
바인딩 단계에서 거부한다. 시스템 작업에 샌드박스가 필요한 애플리케이션은 `allowed-system-principals` 에 그 주체 id 를 명시하고,
`SandboxTenantResolver` 가 그 주체의 테넌트를 돌려줘야 한다(돌려주지 못하면 거부). `require-principal` 을 끈 배포는 주체 없는 실행을 기본 테넌트의
`anonymous` 로 취급한다. 이 주체 검사(`CallerResolver`)는 바인딩만이 아니라 `Principal` 을 받는 매니저 진입점(`close` · `reopen`)에도 같은 규칙으로
걸린다.

**루틴.** 루틴에는 세션이 없다 — 옵션을 읽지 않고, 전환도 없으며, shellKey 는 `exec:{executionId}` 다. 코어는 루틴 요청의 principal 로 작업 소유자
(`task.getOwner()`, 보통 작업을 만든 사용자)를 싣는다. 그 주체는 다른 호출자처럼 주체 검사를 통과해야 하고(`SYSTEM` · `SERVICE` 소유자는
`require-principal` 아래에서 `allowed-system-principals` 로만), ⓪ · ② 로 관리형 워크스페이스에 묶이면 그 워크스페이스를 쓸 수 있어야 한다 — 그
주체가 소유했거나, 같은 테넌트의 `access: tenant` 워크스페이스다.

## 5. 권한

**검사는 두 층이다.** 바인딩 정책이 첫 층이고, 워크스페이스 id 를 받는 매니저의 **모든 공개 진입점** — `connect` · `close` · `reopen` 과 관리 API
([관리 §2](workspace-sandbox-management.md)) — 이 호출자의 주체로 다시 검사한다. 정책이 잘못 구현되어 다른 테넌트의 워크스페이스 id 를 돌려주거나
`caller` 에 owner 를 넣어도 여기서 막힌다. 정책을 믿되 경계는 두 번 긋는다. 실행 안의 호출은 그 실행의 주체를, 실행 밖에서 애플리케이션이 부르는
`close` · `reopen` 은 인자로 받은 `Principal` 을 쓴다. janitor 의 close 는 공개 API 가 아닌 내부 경로를 탄다. **(계획 — 구현 단계 5, 7)** 오케스트레이터
도구가 부르는 슬롯 조회·시작·정지(§7)와 `SandboxWorktrees` 도 같은 검사를 거친다.

**사용 권한과 관리 권한은 다르다.**

| 권한 | 규칙 | 진입점 |
|---|---|---|
| 사용 | 같은 테넌트이고, 레코드의 `access` — 없으면 배포 설정 `workspace-access` — 가 `tenant` 이거나 principal 이 owner 와 같다 | `connect`, `provision`, `describe`, 세션 옵션의 대상이 되는 것, 파일 API |
| 관리 | `MANAGED` 레코드: 접근 모드와 상관없이 owner 의 테넌트와 principal 이어야 한다. `DERIVED` 레코드: 사용 규칙 그대로 | `close`, `reopen`, `purge` |

- **테넌트는 항상 같아야 한다.** `workspace-access: principal`(기본)이면 principal 도 owner 와 같아야 한다. 같은 테넌트의 다른 사용자가 남의 세션
  워크스페이스에 닿지 못하게 하려는 것이다. 팀이 한 워크스페이스를 같이 쓰는 애플리케이션은 `tenant` 로 낮추고, 그 공유를 자기 정책(여러 세션에
  걸치는 워크스페이스 id)으로 표현한다
- 여러 사용자가 메시지를 넣는 세션에서 `principal` 은 소유자가 아닌 참여자의 턴을 모두 거부한다. 그런 애플리케이션은 `tenant` 로 낮춘다
- **레코드의 `access: tenant` 는 공유 결정이다.** `principal` 배포에서는 `allow-shared-workspaces: true` 가 있어야 만들 수 있다. `tenant` 배포에서는
  이미 테넌트 안에서 공유이므로 그 설정이 더하는 것이 없다. `principal` 로 좁히는 것은 언제나 된다. `access` 가 없는 `DERIVED` 레코드는 배포 설정을
  따른다
- **세션 옵션을 바꾸는 것은 워크스페이스가 아니라 세션에 대한 권한이다.** 소유자 출처(§4)가 답한 세션 소유자만 바꾸고, 출처가 없거나 소유자를
  모르면 거부한다. 옵션으로 붙인 세션의 턴도 `connect` 에서 **실행하는** 주체로 다시 검사한다 — 세션을 돌리는 것은 세션에 대한 권한이고, 붙은
  워크스페이스에 들어가는 것은 그 실행 주체의 사용 권한이다
- **요청 속성(⓪)이 요구하는 권한은 실행 주체가 그 워크스페이스를 쓸 수 있는가(사용 권한)뿐이다.** 세션 옵션은 세션의 모든 미래 턴을 옮기지만 요청
  속성은 자기 턴만, 이미 쓸 수 있는 워크스페이스로 옮긴다. 세션 소유를 요구하면 세션이 없는 루틴과 여러 사람이 쓰는 세션에서 쓸모가 없어진다.
  `connect` 는 프로비저닝 전에 이 검사를 한다
- **정의에 `sandbox.workspace` 를 쓰는 것은 그 에이전트의 모든 세션을 그 워크스페이스로 보내는 권한이다.** 다른 주체의 세션은 공유된 워크스페이스에만
  닿는다 — `principal` 배포에서는 owner 가 `allow-shared-workspaces` 아래에서 공유를 골랐어야 한다. 그래도 테넌트가 정의를 쓰는 호스트는 이 권한을
  가볍게 다루지 않는다

**운영자 경로는 owner · 테넌트 검사를 하지 않는다.** `SandboxAdministration.of(WorkspaceSandbox)` 는 모든 워크스페이스와 붙은 세션을 보이고, 대신
만들기 · 세션 옵션 · 슬롯 terminate · close · reopen · purge 를 owner 와 테넌트를 보지 않고 한다(API 는 [관리 §4](workspace-sandbox-management.md)). JVM
안의 보안 경계가 아니라 **따로 만드는 API 면**이기 때문이다 — `WorkspaceSandbox` 가 건네지 않으므로, 호스트가 자기 운영자 인가 뒤에만 둔다. 대신
이렇게 지킨다.

- 쓰기는 모두 행위자(`actor`)를 이벤트에 남긴다
- 대신 만들 때의 owner 는 주체 검사와 상한을 그대로 받는다
- 운영자의 세션 옵션 set · clear 는 권한 검사만 면제된다 — 세션 소유자 출처는 여전히 필요하고, 운영자가 세션을 붙일 때도 그 세션 소유자가 대상
  워크스페이스를 쓸 수 있어야 한다. 운영자의 `deleteSessionOptions` 만 소유자 출처도 면제된다(라우터 밖에서 지운 세션의 레코드를 치우는 용도다)

**슬롯의 프로파일은 호출 순서로 바뀌지 않는다.** 슬롯의 프로파일은 처음 만들 때 고정된다([도메인 §2.1](workspace-sandbox-domain.md)). 정의가
`sandbox.profile` 을 **명시한** 바인딩은 그 프로파일을 요구하고, 이미 있는 슬롯의 프로파일이 다르면 `connect` 는 요구한 프로파일과 실제 프로파일을 적어
사용 불가로 답한다. 그렇지 않으면 다른 실행이 넓은 프로파일로 슬롯을 먼저 만들어, 저신뢰로 설계한 정의가 egress · 자격 증명 · 쓰기 가능한 `/shared` 를
가진 신뢰 도메인에서 돌게 할 수 있다(오케스트레이터 도구가 생기면 `SandboxStart` 가 그 경로다). 그래서 **저신뢰 정의는 `sandbox.profile` 을 반드시
적는다.** 프로파일을 적지 않은 바인딩은 슬롯에 이미 있는 프로파일을 받아들인다. 그래야 운영자가 기본 프로파일을 바꿔도 진행 중인 워크스페이스의 슬롯이
막히지 않고(진행 중인 워크스페이스를 설정 변경이 흔들지 않는다는 원칙, [도메인 §1](workspace-sandbox-domain.md)), 다른 정의가 만든 슬롯에
`sandbox.slot` 만 적은 서브에이전트가 붙는다. 이때 받아들이는 프로파일은 그 슬롯을 만든 쪽보다 넓지 않다 — `SandboxStart` 의 부분집합 규칙(§7) 때문이다.
관리형 워크스페이스의 레코드 `profile` 이 `primary` 의 요구 프로파일이 되는 규칙은 [관리 §2](workspace-sandbox-management.md).

## 6. 신뢰 도메인과 공유

**신뢰 도메인은 서로의 실행에 영향을 줄 수 있는 실행들의 집합이다.** 같은 샌드박스 안의 모든 실행은 한 신뢰 도메인이다. 명령과 셸 상태 파일이 모두
execd 의 uid 소유라, 같은 샌드박스의 실행끼리 서로의 파일과 셸 상태를 읽고 쓸 수 있기 때문이다([셸 §2](workspace-sandbox-shell.md)). 보안 층에서 이
경계가 갖는 의미는 [보안 §2](workspace-sandbox-security.md).

**기본값은 공유 샌드박스다.** 오케스트레이터와 서브에이전트가 모두 `primary` 를 쓰고 shellKey 만 다르다. 파일 시스템은 공유되고 셸 상태는 격리된다.
서로의 파일을 덮어쓰는 문제는 코어의 stamp 검사가 잡는다([파일 시스템 §5](workspace-sandbox-filesystem.md)). **(계획 — 구현 단계 7)** 동시에 코드를
바꿔야 하는 병렬 작업은 worktree 로 가른다([파일 시스템 §7](workspace-sandbox-filesystem.md)).

**(계획 — 구현 단계 5)** 슬롯을 가르는 것은 다음 경우다 — 의존성 충돌, 동시 빌드, 프로파일(네트워크 · 자격 증명 · 런타임 클래스)이 달라야 할 때, 신뢰
도메인이 다를 때, 여러 해법을 병렬로 탐색할 때. 신뢰 도메인이 다르면 **반드시** 가른다. 슬롯을 만들 수 없을 때 `primary` 로 대체하지 않는다. 다른
프로파일 · 신뢰 도메인에서 조용히 도는 것이고, egress 나 자격 증명이 다른 곳으로 옮겨 가는 열린 쪽 실패이기 때문이다. 쿼터 초과는 에러로 알린다.

**공유 워크스페이스도 하나의 신뢰 도메인이다.** 여러 주체가 한 `access: tenant` 워크스페이스에 붙거나, 많은 사용자의 세션이 한 에이전트 기본
워크스페이스(정의의 `sandbox.workspace`)를 쓰면, 모든 세션이 모든 파일 — 셸 래퍼가 명령마다 읽어 들이는 다른 세션의 `/workspace/.aimon-shell/` 셸
상태까지 — 을 읽고 바꾸며 같은 프로파일 자격 증명을 쓴다. 그래서 주체를 넘는 공유는 운영자가 고르는 것이다(`principal` 배포에서는
`allow-shared-workspaces`, `tenant` 배포에서는 이미 그렇다). **공유 워크스페이스를 격리 경계로 쓰지 않는다.**

**(계획 — 구현 단계 5)** **슬롯을 가르는 것만으로 신뢰 도메인이 갈리지는 않는다.** `/shared` 를 쓰기로 마운트한 슬롯들은 bare 저장소의 훅 · ref 와
공유 문서를 통해 서로의 실행에 영향을 준다([파일 시스템 §8](workspace-sandbox-filesystem.md)). 신뢰 도메인을 가르려면 슬롯을 가르고, 신뢰가 낮은 쪽의
프로파일을 `sharedAccess: ro` 또는 `none` 으로 둔다.

## 7. 오케스트레이터 도구 (계획 — 구현 단계 5)

샌드박스 전용 **실행** 도구는 없다 — 명령과 파일은 코어의 `Bash` · 파일 도구가 처리한다. 계획된 `OrcaSandboxToolProvider` 는 슬롯의 수명을 다루는 다음
세 도구만 등록하고, 명시적으로 허용된 에이전트에게만 등록한다. 모두 **호출자의 워크스페이스 안에서만** 슬롯 이름으로 동작하고, §5 의 owner 검사를
거친다.

| 도구 | 입력 | 동작 |
|------|-----|------|
| `SandboxList` | — | 슬롯 · 프로파일 · 상태 · generation |
| `SandboxStart` | `slot`, `profile`(허용 목록 중) | 슬롯 생성 + 프로비저닝. 쿼터 · admission 검사 |
| `SandboxStop` | `slot`, `mode`(`pause` \| `terminate`) | |

도구는 생성자로 아무것도 받지 않는다. 실행마다 코어의 `EXECUTION_ENVIRONMENT` 에서 환경을 꺼내 `SandboxExecutionEnvironment` 이면 그 바인딩의
워크스페이스를 쓰고, 아니면(사용 불가, 다른 제공자) 에러를 돌려준다. 호출자의 워크스페이스를 아는 통로는 이것 하나다 — 도구 인자로 받지 않는다.
§1 의 "바인딩을 만드는 것은 정책뿐, 도구 인자는 바인딩에 들어가지 않는다" 와 같은 규칙이다.

**`SandboxStart` 의 프로파일 부분집합 규칙.** 고를 수 있는 프로파일은 설정의 `allowed-profiles` 중에서도 **호출자 슬롯의 프로파일보다 권한이 크지 않고
격리가 약하지 않은 것**뿐이다 — egress 허용 목록 · 자격 증명 · `sharedAccess` · `insecureAllow` 가 모두 호출자 프로파일의 부분집합이고, `runtimeClass` 가
같으며, 자원(cpu · memory · disk · pids)이 호출자 이하여야 한다. 모델이 고르는 경로로는 오케스트레이터가 인젝션을 받아도 자기보다 넓은 샌드박스를 만들 수
없게 하기 위해서다. 정의를 통해 만들어지는 슬롯(`Task` 로 부른 서브에이전트의 `sandbox.slot` · `sandbox.profile`)의 신뢰 상한은 운영자가 쓴 정의 파일이
정한다 — 모델은 등록된 정의를 고를 수 있을 뿐 새로 쓰지 못한다. `primary` 는 `SandboxStart` 로 만들 수 없다 — 메인 턴의 첫 호출이 기본 프로파일로 만든다.
호출자보다 넓은 프로파일이나 `primary` 를 요구하면 도구 에러다.

`SandboxRestart` 는 두지 않는다 — `terminate` 뒤 다음 사용이 generation 을 올려 새로 만든다. `SandboxFork` 는 `SNAPSHOT` capability 와 함께 더한다
(계획 — 구현 단계 8).

## 8. 실패

바인딩 단계의 실패는 모두 사용 불가 환경이 된다. 코어는 그 실행의 파일·셸 도구를 이유와 함께 실패시키고, 턴은 계속 돈다.

| 상황 | 결과 |
|------|------|
| 세션도 실행 id 도 없는 루트 요청 | 사용 불가 — 워크스페이스를 정할 수 없다(§3) |
| 포크의 부모 환경이 없음 · 사용 불가 · 다른 제공자의 것 | 포크도 사용 불가. 부모가 있으면 그 원인을 그대로 싣는다(§3) |
| 주체 없음 · 시스템 주체 + `require-principal`(허용 목록 밖) | 바인딩 거부, 사용 불가 환경이 이유를 담는다(§4) |
| 어느 출처도 세션 소유자를 모름(출처가 배선됨) | 바인딩 거부 — `SubmitRequest.owner(...)` 를 싣거나 `SessionOwnerLookup` 으로 답하라고 적는다(§4) |
| 소유자 출처의 저장소 장애 | 바인딩 실패 → 사용 불가(§4) |
| 세션 옵션 저장소를 읽을 수 없음 | 그 세션의 루트 바인딩이 사용 불가. ⓪ 이 적용되어도 같다. `per-session` 으로 넘어가지 않는다(§2) |
| 요청 속성에 `sandbox.workspace` · `sandbox.unattached` 밖의 `sandbox.*` 키 | 바인딩 거부, 그 키를 적는다(§2) |
| 요청 · 정의의 `sandbox.workspace` 가 형식이 틀리거나 파생 `ws:` id | 바인딩 거부, 출처(요청 속성 또는 정의 이름)를 적는다(§2) |
| 요청 · 정의의 `sandbox.unattached` 를 해석할 수 없음 | 바인딩 거부, 출처를 적는다(§2) |
| 요청 속성 값이 String 이 아님 · 루틴의 속성 리졸버 실패 | 코어가 제공자를 부르기 전에 사용 불가로 만든다 |
| 붙은 워크스페이스도 기본 워크스페이스도 없고 실효 규칙이 `reject` | 사용 불가 — "this session is not attached to a sandbox workspace"(세션 없는 실행이면 "this execution …")(§2) |
| `mustExist` 바인딩의 레코드가 없음(purge 뒤, 지운 id 를 가리키는 옵션·정의) | 첫 도구 호출에서 사용 불가 — "sandbox workspace <id> does not exist". 레코드를 만들지 않는다(§1) |
| `primary` 가 아닌 슬롯을 고른 바인딩 | 사용 불가 — 다중 슬롯 전까지 `primary` 만 쓸 수 있다([도메인 §2.3](workspace-sandbox-domain.md)) |
| 슬롯이 이미 다른 프로파일로 있음 | `connect` 가 사용 불가 — 요구한 프로파일과 실제 프로파일을 적는다(§5) |
| owner 검사 실패(다른 테넌트 · 다른 사용자) | 사용 불가 · 도구 에러 — "not permitted". 아래 노출 규칙 |
| 세션 옵션 쓰기에 소유자 출처가 없음 · 소유자를 모름 · 호출자가 소유자가 아님 | 그 API 호출이 거부된다(§5) |

**owner 검사 전에 무엇을 드러내는가.** owner 검사 전에는 레코드의 상태를 드러내지 않는다. 존재 여부는 보장하지 않는다 — 도구 경로의 멱등 close 가
드러낼 수 있다(없는 id 의 `close` 는 성공이고 남의 id 면 "not permitted" 이므로). 호스트 API(`describe`, 파일 API)는 존재도 숨긴다 — 없는 것과 쓸 수 없는
것을 같게 답한다. 예외: 세션 삭제로 닫히는 `ws:{sessionId}` 는 검사 전에 "being removed" 로 답한다 — id 가 그 세션 id 에서 파생되어 새 정보가 없다.
파생 id 는 세션 id 를 알아야 만들 수 있고, 세션 id 는 추측할 수 없다.

## 9. 열린 질문

- **동적 슬롯 배정** — 오케스트레이터가 실행 중에 `exp-a` · `exp-b` · `exp-c` 를 만들고 서브에이전트를 각 슬롯에 붙이는 경로. 워크플로 스크립트의
  `agent({..., attributes: { sandbox: { slot: 'exp-a' } }})` 는 이미 쓸 수 있지만(등록된 정의가 슬롯을 정했으면 스크립트가 바꾸지 못한다), `Task` 도구
  인자로 여는 것은 모델에게 슬롯 선택권을 주는 일이다. 허용 목록으로 좁혀 열지, 워크플로에만 둘지 정해야 한다. 다중 슬롯(계획 — 구현 단계 5)과 함께
  닫는다
- **소유자 없는 세션과 `require-principal`** — 어느 출처도 소유자를 모르는 세션은 샌드박스를 받지 못한다(§4). `SubmitRequest.owner` 를 싣기 전에 만든
  세션이 남은 호스트는 그 세션이 사라질 때까지 `SessionOwnerLookup` 을 대체 출처로 둔다. 이 엄격함을 유지할지 확인이 필요하다. 같은 이유로
  `sessionRecords(...)` 만 배선한 배포는 첫 submit 전에 세션 옵션을 정할 수 없다(세션 레코드와 소유자가 아직 없다). 우회는 셋이다 — 첫 턴을 요청
  속성으로 옮기거나, 세션 레코드를 `SessionRecordStore.provision(id, agentRef, owner)` 로 먼저 만들거나, 호스트 lookup 을 대체 출처로 둔다
