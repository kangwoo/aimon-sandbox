# 워크스페이스 샌드박스 — 도메인

영속 상태의 모양과 그것을 바꾸는 규칙을 다룬다. 워크스페이스 레코드와 슬롯, 세션 옵션 레코드, 두 저장소의 SPI 와 CAS 규칙, 활동 기록이
여기 있다. 상태를 바꾸는 절차(connect, close, janitor)는 [수명](workspace-sandbox-lifecycle.md)이, 레코드의 owner 와 접근 모드가 무엇을
허락하는지는 [바인딩](workspace-sandbox-binding.md)이 소유한다.

> 관련: [개요](workspace-sandbox.md) · [바인딩](workspace-sandbox-binding.md) · [수명](workspace-sandbox-lifecycle.md) ·
> [notice](workspace-sandbox-notices.md) · [관리](workspace-sandbox-management.md) · [테스트](workspace-sandbox-testing.md)

## 1. 워크스페이스 레코드

`SandboxWorkspace` 는 한 작업을 위해 여러 실행이 공유하는 워크스페이스 하나를 나타내는 애그리게이트다(용어는 [개요 §3](workspace-sandbox.md)).
레코드와 그 안의 슬롯은 모두 불변 클래스 + 빌더다. 상태 변경은 `with*()` 가 새 인스턴스를 돌려주고, 원자적 교체는 저장소의 CAS 가
맡는다(§4).

| 필드 | 뜻 |
|------|-----|
| `id` | `SandboxWorkspaceId` — 불투명 문자열. 파생 워크스페이스는 바인딩 정책이 세션·실행 id 에서 결정론적으로 만들고(`ws:{sessionId}`, `ws:{executionId}`, [바인딩 §3](workspace-sandbox-binding.md)), 관리형 워크스페이스는 `wsm:{UUID}` 를 새로 뽑으며 다시 쓰지 않는다(규칙과 이유는 [관리 §2](workspace-sandbox-management.md)) |
| `origin` | `DERIVED`(`connect` 가 파생 id 로 만든 것, 기본값) · `MANAGED`(`create` 가 만든 것). janitor 는 기한 지난 `DERIVED` 의 CLOSED 레코드를 지우고, `MANAGED` 레코드는 지우지 않는다 — `purge` 만 지운다([수명 §7](workspace-sandbox-lifecycle.md)) |
| `name`? · `profile`? · `access`? | 관리형만. 사람이 보는 이름(모델에는 보이지 않고, 유일하지 않다), `create` 가 요구한 프로파일(슬롯이 아직 없어도 남아 `primary` 를 처음 만들 때 요구 프로파일이 된다), 워크스페이스 자신의 접근 모드(없으면 배포 설정 `workspace-access`, [바인딩 §5](workspace-sandbox-binding.md)) |
| `owner` | `WorkspaceOwner` — `(tenantId, principal)`, principal 은 `TYPE:id`(예 `USER:alice`). 레코드를 만들 때 고정된다. 호출한 실행의 주체가 아니라 **워크스페이스의 소유자**다. 누가 owner 가 되는지는 [바인딩 §4](workspace-sandbox-binding.md) |
| `state` | `OPEN` · `CLOSING` · `CLOSED` |
| `incarnation` | 레코드를 만들 때와 `reopen` 할 때마다 새로 뽑는 짧은 무작위 id |
| `sharedVolume`? | `VolumeRef` — 공유 볼륨 이름. 이름 규칙으로 정해지고, 볼륨 자체는 첫 샌드박스 생성이 만든다([프로바이더 §5](workspace-sandbox-provider.md)) |
| `stateSince` · `closeCause`? · `retainVolumeUntil`? · `retainedVolumes` | CLOSING · CLOSED 로 바뀐 시각, close 원인, 보존할 볼륨의 기한, reopen 뒤에도 기한까지 남길 옛 incarnation 볼륨 목록. janitor 가 멈춘 close 를 이어받고 CLOSED 레코드와 보존 볼륨을 지우는 기준이다([수명 §6](workspace-sandbox-lifecycle.md), [§7](workspace-sandbox-lifecycle.md)) |
| `slots` | 슬롯 이름 → `SandboxSlot`(§2) |
| `quota` | `WorkspaceQuota` — `maxSlots` · `maxRunning` · `maxCpu`? · `maxMemory`?. 생성 시점의 설정을 복사해 둔다. 설정이 바뀌어도 진행 중인 워크스페이스가 흔들리지 않게 하기 때문이다. 집행은 다중 슬롯과 함께 온다(§2.3, [보안 §8](workspace-sandbox-security.md)) |
| `version` | `long` — CAS 용. 저장소가 매긴다(§4.2) |
| `createdAt` · `lastActivityAt` | `lastActivityAt` 은 활동 기록(§6)이 갱신한다 |

**`closeCause`** 는 세 값이다.

- `EXPLICIT` — 애플리케이션이나 운영자가 닫았다. 이 CLOSED 레코드가 **툼스톤**이다. `reopen` 하거나 `closedRetention` 이 지날 때까지 같은 id
  의 `connect` 를 막는다
- `IDLE` — `closeAfter` 가 지나 닫혔다. 막지 않는 CLOSED 레코드다. 다음 `connect` 가 새 incarnation 으로 다시 열고 초기화 notice 를 붙인다
- `SESSION_DELETED` — 세션이 지워져 그 세션의 파생 `ws:{sessionId}` 를 닫는다. `connect` 와 `reopen` 은 이 레코드를 다시 열지 않고
  "being removed" 로 답하며, 샌드박스가 사라지면 `closedRetention` 을 기다리지 않고 지운다

세 원인이 각각 무엇을 일으키는지는 [수명 §7](workspace-sandbox-lifecycle.md)이 소유한다.

**incarnation 을 두는 이유.** 같은 워크스페이스 id 로 다시 만들어지거나 다시 열린 워크스페이스가 옛 자원과 섞이지 않게 하기 위해서다.
incarnation 은 샌드박스의 멱등 키와 공유 볼륨 이름에 들어간다([프로바이더 §4](workspace-sandbox-provider.md)). 파생 id 는 툼스톤이 지워진
뒤 다시 쓰이므로, id 만으로는 옛 워크스페이스의 샌드박스·볼륨과 새 것을 가를 수 없다.

**레코드는 sandbox id 를 "하나" 갖지 않는다.** 워크스페이스는 슬롯마다 샌드박스를 갖고, 샌드박스의 프로바이더 id 는 슬롯의 `providerRef`
에 있으며 generation 마다 바뀐다(§2.1). 워크스페이스를 가리키는 것은 워크스페이스 id 이고, 모델과 호스트 어느 쪽에도 sandbox id 를 내주지
않는다.

세션을 워크스페이스에 붙이는 사실은 이 레코드에 두지 않는다. 세션 옵션 레코드(§3)가 따로 갖는다.

## 2. 슬롯

### 2.1 필드

| 필드 | 뜻 |
|------|-----|
| `name` | 슬롯 이름. 기본 슬롯은 `primary` |
| `profile` | 프로파일 이름. 슬롯을 처음 만들 때 고정된다. 다른 프로파일을 요구하는 바인딩을 어떻게 다루는지는 [바인딩 §5](workspace-sandbox-binding.md) |
| `profileHash` | 현재 generation 을 만들 때 쓴 프로파일 내용의 SHA-256(정규형, `SandboxProfile.contentHash()`). `permanent` 실패를 "프로파일이 바뀔 때까지" 재시도하지 않는 규칙([수명 §2.2](workspace-sandbox-lifecycle.md))이 비교하는 대상이다 |
| `state` | `PROVISIONING` · `RUNNING` · `PAUSED` · `TERMINATED` · `FAILED`(§2.2) |
| `generation` | 슬롯의 샌드박스를 새로 프로비저닝할 때마다 +1. FAILED 에서 재시도할 때도 +1 이다 |
| `provisioning`? | `(since, nodeId)` — PROVISIONING 에 들어간 시각과 그 노드. 프로비저닝 인계의 기준이다([수명 §2.4](workspace-sandbox-lifecycle.md)) |
| `providerRef`? | `ProviderSandboxRef` — `(provider, providerSandboxId)`. OpenSandbox 라면 그 샌드박스 id |
| `seeded` | 이 generation 의 seed 가 끝났는가. RUNNING 이어도 false 면 다음 `connect` 가 seed 를 다시 돈다([수명 §3](workspace-sandbox-lifecycle.md)) |
| `missingSince`? | janitor 가 프로바이더에서 이 샌드박스를 처음 못 본 시각. 다시 보이거나 generation 이 바뀌면 지운다([수명 §6.2](workspace-sandbox-lifecycle.md)) |
| `failure`? | `(at, kind, step, reason, attempts, profileHash)`. `kind` 는 `transient` · `permanent`, `step` 은 실패한 단계(`create`, seed 의 각 검사, `clone`)다([수명 §2.2](workspace-sandbox-lifecycle.md), [§3](workspace-sandbox-lifecycle.md)) |
| `lastActivityAt` · `lastActiveAt`? · `lostAt`? | 마지막 활동(§6), 슬롯이 마지막으로 RUNNING · PAUSED · PROVISIONING 이던 시각(`closeAfter` 의 기준, [수명 §4](workspace-sandbox-lifecycle.md)), 샌드박스를 잃었다고 기록한 시각 |

**레코드는 `providerRef` 만 저장하고 프로바이더 연결 객체를 저장하지 않는다.** 연결은 노드 로컬 캐시(`SandboxConnectionCache`)다. 연결
객체를 상태로 들고 있으면 노드 이동과 재시작을 견디지 못하기 때문이다.

**셸 세션 참조는 레코드에 없다.** 셸 상태는 샌드박스 안의 파일이다([셸 §1](workspace-sandbox-shell.md)). 그래서 레코드 크기는 슬롯 수에만
비례하고, 세션이나 shellKey 가 늘어도 자라지 않는다.

**슬롯에 역할 enum(`PRIMARY` · `REVIEW` · `TEST` 등)을 두지 않는다.** 라우팅은 슬롯 이름이, 보안 정책 선택은 프로파일이 맡는다. enum 은
특정 개발 프로세스를 SPI 에 굳혀, 실험 · 브라우저 · GPU 슬롯을 만들 때마다 enum 을 고치게 한다. 슬롯 이름 + 프로파일이면 둘 다 설정 문제다.

### 2.2 상태 전이

```
            ensure()                  idle(pauseAfter)*
(absent) ──────────> PROVISIONING ──> RUNNING <──────────> PAUSED*
                         │              │        resume()*    │
                    fail │              │ stop / idle(terminateAfter) / lost
                         ▼              ▼                     │
                       FAILED       TERMINATED <──────────────┘
                         │              │
                         │ ensure() after failureBackoff -> generation+1
                         └──────────────┴──────────> PROVISIONING

* (계획 — 구현 단계 7) PAUSED 와 그리로 가는 전이
```

**(계획 — 구현 단계 7)** `PAUSED` 상태와 `idle(pauseAfter)` · `resume()` 전이는 pause 와 함께 온다. 지금은 `pauseAfter` 를 가진 프로파일을
기동 시 거부하므로 슬롯이 PAUSED 가 되지 않는다([개요 §8](workspace-sandbox.md)).

**FAILED 에서 같은 generation 으로 재시도하지 않는다.** 같은 키로 다시 만들면 멱등 `create` 가 실패한 생성이 남긴 샌드박스를 되돌려 줄
수 있고, janitor 가 옛 FAILED 스냅숏을 보고 그것을 지울 수도 있다. generation 을 올리면 남은 것은 STALE 로 깔끔하게 회수된다
([수명 §6.2](workspace-sandbox-lifecycle.md)). 언제 재시도하는지(`failureBackoff`, `permanent` 는 프로파일이 바뀔 때까지 재시도하지 않음)는
[수명 §2.1](workspace-sandbox-lifecycle.md) 이 소유한다.

**"명령이 도는 중" 을 뜻하는 READY · ACTIVE 같은 상태를 두지 않는다.** 그 사실은 `lastActivityAt` 이 말하고, 상태로 올리면 명령마다 저장소
쓰기가 생긴다. **TERMINATED 는 끝이 아니다** — 같은 슬롯을 다시 쓰면 generation 을 올려 새로 만든다.

### 2.3 다중 슬롯 (계획 — 구현 단계 5)

데이터 모델은 처음부터 한 워크스페이스에 슬롯 N 개다(`slots` 맵, `quota.maxSlots`). 지금 구현은 `primary` 하나만 프로비저닝하고, `primary`
가 아닌 슬롯을 고른 바인딩은 사용 불가로 답한다([바인딩 §8](workspace-sandbox-binding.md)).

구현 단계 5 가 더하는 것: 슬롯별 프로파일, `quota` 의 집행([보안 §8](workspace-sandbox-security.md)), 슬롯 사이의 협업 볼륨
([파일 시스템 §8](workspace-sandbox-filesystem.md)), 슬롯을 만드는 오케스트레이터 도구([바인딩 §7](workspace-sandbox-binding.md)). 슬롯을
언제 가르는지는 [바인딩 §6](workspace-sandbox-binding.md) 이 소유한다.

## 3. 세션 옵션 레코드

`SessionSandboxOptions` 는 세션 하나의 샌드박스 옵션이다. 세션마다 최대 하나이고 `SessionOptionsStore`(§5)에 저장한다.

| 필드 | 뜻 |
|------|-----|
| `session` | 세션 id |
| `setBy` | 마지막으로 옵션을 바꾼 주체 — 세션 소유자 또는 대신 바꾼 운영자. `WorkspaceOwner` 형식 |
| `workspace`? | 이 세션의 루트 실행이 붙는 관리형 워크스페이스(attachment). `wsm:` id 만 허용한다([바인딩 §2](workspace-sandbox-binding.md)) |
| `unattached`? | 세션 자신의 unattached 규칙(`per-session` · `reject`). 위층의 규칙을 조이기만 한다([바인딩 §2](workspace-sandbox-binding.md)) |
| `lastWorkspaceId`? | 이 세션이 마지막으로 접속했다고 모델에게 알린 워크스페이스. 워크스페이스 전환 notice 의 기준이다([notice §3](workspace-sandbox-notices.md)) |
| `unannouncedReset`? | 모델이 아직 듣지 못했을 수 있는 샌드박스 초기화. 아래 |
| `updatedAt` | 레코드를 마지막으로 쓴 시각 |
| `version` | CAS 용. 저장소가 매긴다 |

**`unannouncedReset`** 은 `(workspace, slot, at)` 이다 — 초기화된 워크스페이스와 슬롯, 그 초기화를 관찰한 시각. `lastWorkspaceId` 와 같이
"모델이 무엇을 들었나" 에 관한 사실이라 그 옆에 둔다. 세션당 하나이고, 새 초기화가 옛 것을 대체한다(둘 다 "그 전의 파일은 사라졌다" 를
말한다). `at` 은 값의 정체성에 들어간다. 그래서 같은 슬롯의 더 새로운 초기화는 다른 값이고, 옛 초기화에 대한 확인은 새 것을 지우지 않는다.
`at` 은 저장소가 돌려준 값을 쓴다(§4.2 의 정밀도 규칙). 저장소는 이 필드를 영속해야 한다 — 버리는 저장소는 코어가 꺼내지 않는 경로에서
일어난 초기화의 notice 를 잃는다. 언제 남기고 다시 알리고 지우는지는 [notice §5](workspace-sandbox-notices.md) 가 소유한다.

**bookkeeping 레코드.** `lastWorkspaceId` 나 `unannouncedReset` 만 담고 `workspace` 도 `unattached` 도 없는 세션 옵션 레코드다. 레코드가 없는
세션에 전환을 기록하거나 초기화를 남길 때 환경이 이 레코드를 만든다. bookkeeping 레코드는 붙음(attachment)도 규칙도 아니다 — 바인딩
우선순위에 아무것도 더하지 않고([바인딩 §2](workspace-sandbox-binding.md)), 아무 권한도 주지 않는다.

**세션 옵션을 워크스페이스 레코드가 아니라 별도 저장소에 둔다.** 한 워크스페이스에 붙는 세션 수에는 상한이 없고, 워크스페이스 레코드는
가장 자주 CAS 되는 문서라 세션과 함께 자라면 안 되기 때문이다. 같은 이유로 공유 슬롯에 세션별 사실(예: 초기화를 아직 듣지 못한 세션)을
두지 않는다.

**요청 속성이 있어도 세션 옵션 저장소는 세션 단위 기본값으로 남는다.** 요청 속성([바인딩 §2](workspace-sandbox-binding.md))이 대신하지 못하는
셋을 갖기 때문이다 — 매 submit 에 값을 찍지 않으려는 호스트의 한 번 설정 기본값, 무엇이 워크스페이스를 골랐든 전환 notice 가 필요로 하는
`lastWorkspaceId`, "워크스페이스 X 에 붙은 세션" 관리 뷰.

옵션을 바꾸는 API(`setSessionOptions` · `clearSessionOptions`)와 그 갱신 규칙은 [관리 §3](workspace-sandbox-management.md), 누가 바꿀 수
있는지는 [바인딩 §5](workspace-sandbox-binding.md), 세션이 지워질 때 레코드를 지우는 경로는 [수명 §7.4](workspace-sandbox-lifecycle.md) 다.

## 4. 워크스페이스 저장소

### 4.1 SPI

```java
public interface SandboxWorkspaceStore {
    Optional<SandboxWorkspace> find(SandboxWorkspaceId id);
    SandboxWorkspace createIfAbsent(SandboxWorkspace initial);          // returns the stored one
    SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion,
                            SandboxWorkspace next);                     // throws StaleVersionException
    void delete(SandboxWorkspaceId id, long expectedVersion);           // CAS; an absent record is success
    List<SandboxWorkspace> scan(WorkspaceScan scan);                    // filters and page, ordered by id
}
```

- `createIfAbsent` 는 같은 id 의 레코드가 없을 때만 저장하고, 저장된 것(넘긴 것이든 이미 있던 것이든)을 돌려준다
- `update` 는 레코드가 아직 `expectedVersion` 일 때만 바꾼다. 레코드가 바뀌었거나 없으면 `StaleVersionException` 이다
- `delete` 는 janitor 가 기한 지난 CLOSED 레코드를 지울 때 쓴다. 버전으로 보호하므로 그 사이 들어온 `reopen` 을 지우지 않는다. 레코드가
  이미 없으면 성공이다
- `scan` 의 필터는 상태 집합 · 테넌트 · owner · origin · `lastActivityAt` 이전 · `stateSince` 이전이고, id 순으로 `afterId` 뒤에서 `limit`
  개까지 돌려준다. 매니저는 owner · origin 으로 거른 결과(소유한 워크스페이스 목록, 관리형 개수 상한)를 필터로 **다시 검사한다** —
  나중에 더한 owner · origin 필터를 모르고 무시하는 저장소도 있기 때문이다

**워크스페이스와 슬롯을 한 애그리게이트로 통째 CAS 한다.** 저장소를 나누면 한 변경(예: 슬롯을 TERMINATED 로 바꾸면서 공유 볼륨 참조를
정리)이 여러 쓰기가 되어 중간에 죽으면 어긋난다. 워크스페이스는 `maxSlots` 로 작게 제한되므로 통째 CAS 가 트랜잭션 조율보다 싸다.

워크스페이스 매니저는 이 애그리게이트 위의 CAS 상태 기계다. 모든 쓰기는 읽기 → 판단 → `update(id, expectedVersion, next)` 이고, 프로바이더
호출은 그것을 허락한 CAS 가 성공한 뒤에만 한다. 프로바이더 호출 뒤 CAS 를 지면 호출이 만든 것을 지우지 않는다 — 그 샌드박스는 이긴 쪽의
것일 수 있다. 아무의 것도 아님이 확인되면 janitor 의 조정이 회수한다([수명 §2.1](workspace-sandbox-lifecycle.md),
[§6.2](workspace-sandbox-lifecycle.md)).

모든 구현은 testkit 의 `SandboxWorkspaceStoreContract` 를 통과해야 한다([테스트 §1](workspace-sandbox-testing.md)).

### 4.2 version 규칙

**version 은 저장소가 매기고, 지운 레코드의 version 을 다시 쓰지 않는다.** 처음 보는 id 는 1 에서 시작하지만, 지웠다가 다시 만든 id 는
예전에 가졌던 어느 version 보다 큰 값에서 시작한다. 지워진 레코드를 읽어 둔 호출자의 CAS 가 새 레코드에 맞아떨어지는 ABA 를 막기 위해서다.
`update` 는 저장한 사본에 `expectedVersion + 1` 을 매기고, `next` 가 들고 온 version 은 무시하며, id 가 다른 레코드는 거부한다.

**저장소가 돌려준 것이 저장된 것이다.** `update` · `createIfAbsent` 의 반환값과 나중의 `find` 가 같아야 한다. 저장소는 `Instant` 를 자기
정밀도로 줄여 저장해도 되지만(적어도 밀리초), 그래서 호출자는 넘긴 값이 아니라 **돌려받은 값**과 비교한다. 예컨대 프로비저닝을 시작한
노드는 자기가 넘긴 claim 이 아니라 저장된 슬롯의 claim 과 비교해 자기 것인지 판단한다.

### 4.3 충돌과 재시도

**CAS 충돌은 다시 읽고 다시 판단한다.** version 이 워크스페이스에 하나이므로 다른 슬롯의 전이나 활동 기록도 같은 version 을 올린다. 그래서
쓰기는 `StaleVersionException` 을 받으면 레코드를 다시 읽고, 자기 조건이 여전히 성립하는지 확인한 뒤 다시 시도한다. 충돌했다고 쓰기를 그냥
버리는 곳은 없다. 다시 읽은 레코드에서 조건이 더는 성립하지 않으면(예: 슬롯의 generation 이 바뀌었다) 그 쓰기는 할 일이 없어진 것이다.

재시도 상한은 두 가지다.

- **일반 쓰기는 `casRetries`**(설정, 기본값은 [설정 §2](workspace-sandbox-configuration.md)). 넘으면 "the sandbox workspace is busy" 로
  실패하고, 호출은 다시 시도하라는 에러를 받는다
- **활동 기록(`lastActivityAt` · heartbeat, §6)은 훨씬 큰 별도 상한(1000회)과 무작위 대기를 쓴다.** 활동 기록은 버리지 않는 것이 계약이기
  때문이다 — 활동 기록을 잃으면 janitor 가 도는 명령의 샌드박스를 멈출 수 있다. `casRetries` 로는 동시 쓰기가 많은 워크스페이스에서
  heartbeat 를 잃었다

### 4.4 단일 노드와 영속 저장소

기본 구현 `InMemorySandboxWorkspaceStore` 는 **단일 노드 전용**이다.

- `InMemory` 저장소로 도는 노드가 재시작하면 레코드가 모두 사라진다. 그 순간 프로바이더에 남은 샌드박스는 janitor 의 조정에서 ORPHAN 이
  되어 `orphanGrace` 뒤 회수된다([수명 §6.2](workspace-sandbox-lifecycle.md)). 단일 노드 배포에서는 이것이 의도된 동작이다 — 재시작을 넘어
  샌드박스를 이어 쓰려면 영속 저장소를 쓴다
- 멀티 노드 배포에서 `InMemory` 저장소를 쓰면 각 노드의 janitor 가 다른 노드의 샌드박스를 레코드 없는 ORPHAN 으로 보고 회수한다. 이 모듈은
  그 구성을 감지하지 못한다. 그래서 이것은 운영 계약이다 — 노드가 둘 이상이면 영속 저장소가 필요하다

**(계획 — 구현 단계 6)** 영속 구현 `aimon-sandbox-store-jdbc` 가 워크스페이스 저장소와 세션 옵션 저장소(§5)를 함께 덮고, 두 저장소의 계약
스위트를 통과한다. 이 구현이 생기기 전까지 [개요 §1.1](workspace-sandbox.md) 의 멀티 노드 목표는 달성되지 않은 것으로 본다. 레코드 스키마를
굳히기 전에 워크스페이스 단위 seed 매개변수를 정해야 한다([설정 §6](workspace-sandbox-configuration.md)) — 레코드 모양을 바꾸는 결정이다.

## 5. 세션 옵션 저장소

```java
public interface SessionOptionsStore {
    Optional<SessionSandboxOptions> find(SessionId session);
    SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expectedVersion); // empty: must not exist
    void delete(SessionId session, long expectedVersion);                                  // absent: success
    Page<SessionSandboxOptions, SessionId> byWorkspace(SandboxWorkspaceId id, Optional<SessionId> after, int limit);
    Page<SandboxWorkspaceId, SandboxWorkspaceId> workspaces(Optional<SandboxWorkspaceId> after, int limit);
    Page<SessionSandboxOptions, SessionId> sessions(Optional<SessionId> after, int limit);
    int deleteByWorkspace(SandboxWorkspaceId id);
}
```

- **CAS 규칙은 워크스페이스 저장소와 같다**(§4.2), no-ABA 규칙도 포함한다. `put` 은 `expectedVersion` 이 비어 있으면 만들고(이미 있으면
  `StaleVersionException`), 있으면 그 version 일 때만 바꾼다. 지웠다가 다시 만든 레코드는 지운 레코드가 가졌던 어느 version 도 다시 쓰지
  않는다. 기본 `InMemory` 저장소는 지운 키를 모두 기억하는 대신 전역 version 바닥 하나를 둔다
- **돌려준 것이 저장한 것이다** — `unannouncedReset` 을 포함한 모든 필드(§3)
- `byWorkspace` 는 `workspace` 가 있는 레코드만, 세션 id 순으로 돌려준다 — "워크스페이스 X 에 붙은 세션" 뷰
- `workspaces` 는 어느 레코드든 붙어 있는 워크스페이스 id 를 한 번씩, 순서대로 돌려준다 — janitor 가 purge 된 id 에 붙은 레코드를 쓸어 낼 때
- `sessions` 는 붙었든 아니든 모든 레코드를 세션 id 순으로 돌려준다 — janitor 가 지워진 세션의 레코드를 쓸어 낼 때
  ([수명 §7.4](workspace-sandbox-lifecycle.md))
- `deleteByWorkspace` 는 그 워크스페이스에 붙은 모든 레코드의 `workspace` 만 지우고 나머지 필드(`lastWorkspaceId`, `unannouncedReset` 포함)는
  남긴다. 바뀐 레코드마다 새 version 을 받는다. 멱등이다. purge 가 부른다
- 목록은 키가 타입인 `Page<T, K>` 로 돌려준다

세션 옵션은 세션과 함께 지운다. 호스트가 코어의 세션 라우터에 `WorkspaceSandbox.sessionLifecycleListener()` 를 등록하면 세션 삭제가 그
레코드를 지운다. 라우터 밖에서 지운 세션은 이벤트가 없으므로 운영자의 `deleteSessionOptions` 나 janitor 의 스윕이 지운다
([수명 §7.4](workspace-sandbox-lifecycle.md)).

모든 구현은 testkit 의 `SessionOptionsStoreContract` 를 통과해야 한다([테스트 §1](workspace-sandbox-testing.md)). 커스텀 저장소가 미전달
초기화 때문에 새로 지는 의무는 [notice §7](workspace-sandbox-notices.md) 에 있다. 기본 `InMemory` 저장소는 워크스페이스 저장소와 같은 단일
노드 한계를 갖는다(§4.4).

## 6. 활동 기록

활동 기록은 워크스페이스와 슬롯의 `lastActivityAt` 이다. idle 정책과 janitor 가 이 값으로 샌드박스를 끝낼지 판단한다
([수명 §4](workspace-sandbox-lifecycle.md), [§6.1](workspace-sandbox-lifecycle.md)).

**`lastActivityAt` 쓰기는 스로틀한다.** 마지막 기록보다 `activityWriteInterval`(기본값은 [설정 §2](workspace-sandbox-configuration.md)) 이상
지났을 때만 쓴다. 명령마다 쓰면 저장소가 명령 처리량을 따라가야 하기 때문이다. 쓸 때는 기존 값과 새 값 중 늦은 쪽을 남기고, 충돌하면 다시
읽어 같은 규칙으로 재시도한다(§4.3 의 활동 기록 상한). 활동 기록이 유실되면 janitor 가 도는 명령을 멈출 수 있기 때문이다.

**활동 기록은 한 generation 에 묶인다.** 활동을 기록하는 핸들은 그것을 만든 슬롯의 generation 과 `providerRef` 를 기억하고, 레코드의 슬롯이
여전히 같은 generation · 같은 `providerRef` 의 RUNNING 일 때만 쓴다. 재생성 뒤 옛 핸들의 쓰기는 아무것도 하지 않는다. 샌드박스를 잃었다는
기록(`lostAt`)도 같은 조건으로 지킨다. 그래야 끝난 generation 의 명령이 새 샌드박스의 활동이나 상태를 바꾸지 않는다.

**명령을 시작하기 전에 레코드를 매번 읽는다.** 쓰기는 스로틀해도 읽기는 매번이다. 명령마다 `connect` 가 레코드를 읽고, 슬롯이 RUNNING 이고
generation 이 연결 캐시와 같을 때만 그 연결로 명령을 보낸다. 그렇지 않으면(janitor 가 슬롯을 끝냈거나 다른 실행이 재생성했다) 상태 기계를
처음부터 돈다([수명 §2.1](workspace-sandbox-lifecycle.md)). 연결 캐시만 보고 끝난 샌드박스에 명령을 보내지 않는다.
**(계획 — 구현 단계 7)** 슬롯이 PAUSED 면 `connect` 의 resume 경로를 탄다.

명령이 도는 동안의 활동은 명령의 시작만이 아니다. 도는 명령(백그라운드 포함)을 가진 노드가 `activityWriteInterval` 마다 `lastActivityAt` 을
기록하고 프로바이더 만료를 연장한다 — heartbeat 의 규칙과 이유는 [수명 §5](workspace-sandbox-lifecycle.md), 백그라운드 명령의 heartbeat
상한은 [셸 §7](workspace-sandbox-shell.md) 이 소유한다. 사람용 파일 API 는 변경만 활동으로 기록하고 읽기는 기록하지 않는다
([관리 §6](workspace-sandbox-management.md)).

## 7. 열린 질문

도메인 모델 고유의 열린 질문은 없다. 레코드 모양에 영향을 줄 수 있는 질문은 각 정본 문서에 있다.

- 워크스페이스 단위 seed 매개변수 — 영속 저장소의 스키마를 굳히기 전에 닫는다([설정 §6](workspace-sandbox-configuration.md))
- 다른 주체가 일으킨 재생성을 다음 턴에 알리려면 세션마다 마지막으로 본 generation 을 세션 옵션 레코드에 기록해야 한다
  ([notice §8](workspace-sandbox-notices.md))
