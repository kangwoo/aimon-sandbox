# 워크스페이스 샌드박스 — notice

모델이 자기 환경의 변화 — 파일이 사라졌다, 다른 워크스페이스로 옮겨졌다, 프로비저닝이 오래 걸렸다 — 를 언제, 어떤 문구로, 몇 번
듣는지를 정한다. 환경 notice 와 명령 notice, 큐, 워크스페이스 전환 notice, generation notice, 아직 전달되지 않은 초기화를 세션에
영속하는 장치(미전달 초기화)를 다룬다.

> 관련: [개요](workspace-sandbox.md) · [도메인](workspace-sandbox-domain.md) · [바인딩](workspace-sandbox-binding.md) ·
> [수명](workspace-sandbox-lifecycle.md) · [셸](workspace-sandbox-shell.md) · [관리](workspace-sandbox-management.md) ·
> [코어 실행 환경 §10](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)

초기화를 일으키는 경로(idle reopen, 재생성, 소실)는 [수명 §2 · §7](workspace-sandbox-lifecycle.md) 이, 세션 옵션 레코드의 필드
(`lastWorkspaceId`, `unannouncedReset`)는 [도메인 §3](workspace-sandbox-domain.md) 이 소유한다.

## 1. 두 종류의 notice

| | 환경 notice | 명령 notice |
|---|---|---|
| 무엇 | 실행 환경 수준의 사실 | 그 명령 하나에 대한 사실 |
| 채널 | 코어의 `ExecutionEnvironment.drainNotices()` | `ShellCommandResult.notices()` |
| 실리는 곳 | 모델에게 가는 모든 도구 결과의 머리에 `[environment] ` 줄로 | 그 명령의 결과 |

**환경 notice** 는 다음이다. 문구는 모델에게 워크스페이스 이름을 말하지 않고 id 만 쓴다.

| 사실 | 문구(요지) | 만드는 곳 |
|---|---|---|
| idle close 뒤 자동 reopen | "/workspace was reset: this workspace was closed after being idle, and its files and shell state are gone" | [수명 §7.2](workspace-sandbox-lifecycle.md) |
| TERMINATED 슬롯의 재생성(idle terminate · LOST 확정 뒤) | "the sandbox was recreated (generation N): files under /workspace and shell state (cwd, exported variables) from before are gone" | §4 |
| connect 가 RUNNING 슬롯의 샌드박스가 없음을 발견해 재생성 | "the sandbox was lost and has been recreated; /workspace was reset" | [수명 §2.1](workspace-sandbox-lifecycle.md) |
| 프로비저닝 + seed 가 5초 이상 | "sandbox provisioned in {n}s" | [수명 §2.1](workspace-sandbox-lifecycle.md) |
| 워크스페이스 전환 | "this session now runs in sandbox workspace <to>; …" | §3 |
| 이 실행이 마지막으로 쓴 뒤 다른 실행이 만든 새 generation | "the sandbox was re-created since this execution last used it (generation N); …" | §4 |
| 이전 실행의 미전달 초기화 | "/workspace was reset at <시각> and this session may not have been told: …" | §5 |

앞의 셋 — idle reopen, 재생성, 소실 — 을 묶어 **초기화**라 부른다. 셋 다 `/workspace` 의 파일과 셸 상태가 사라졌다는 뜻이다.

**명령 notice** 는 명령이 죽어 그 명령의 `cd`/`export` 가 반영되지 않았다는 것과, 저장한 작업 디렉터리가 없어 다른 곳에서 돌았다는
것이다([셸 §6 · §10](workspace-sandbox-shell.md)). 결과를 담은 예외(`ShellExecutionException` · `ShellTimeoutException` ·
`ShellCancelledException`)와 `BashOutput` 이 읽는 백그라운드 명령의 최종 결과도 같다. "셸 busy", 명령 도중 소실, 띄우기 전에 걸린
취소의 예외는 명령 notice 가 없다. 훅의 명령도 따로 다루지 않는다 — 코어가 훅이 돈 도구의 결과에서 drain 하기 때문이다.

**환경 notice 는 `drainNotices()` 로만 보내고 명령 결과에는 싣지 않는다.** 코어 계약이 같은 사실을 두 채널에 넣지 말라고 한다. 둘
다 쓰면 어느 결과가 사실을 실을지가 `Bash` 결과와 코어 drain 의 경합에 달리고, 한 턴 뒤 `BashOutput` 이 읽는 백그라운드 결과가 지금의
초기화를 삼킨다.

### 1.1 코어가 주는 것

이 문서의 설계는 코어의 다음 계약에 기댄다([코어 실행 환경 §10](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)).

- 코어는 모델에게 가는 모든 도구 호출 뒤에(PostTool 훅 뒤, 도구 에러 포함) `drainNotices()` 를 한 번 부르고, 돌려받은 줄을 그 결과
  머리에 `[environment] ` 로 싣는다. `Read` 만 쓴 턴도 듣는다. `Bash` 결과에서는 drain 한 줄, 명령 notice, 출력 순서다.
- **drain 이 돌려준 것은 전달된 것이다**(at-most-once). 도구가 돌아온 뒤 결과가 기록되기 전에 턴이 취소되면 그 notice 는 잃는다.
- 코어가 drain 하지 않는 경로가 있다 — 루틴 단계, 워크플로 스크립트의 도구 호출, `RecentFilesRestoreHook`, 훅의 셸 액션. 스테이징도
  drain 과 무관하다. 이 경로의 notice 는 큐에 남는다.
- drain 은 원격 I/O 로 오래 막히면 안 된다.
- 한 세션은 한 번에 한 턴만 돈다(세션 lease 와 턴 게이트). 그래서 턴 T 의 쓰기는 턴 T+1 의 바인딩이 레코드를 읽기 전에 일어난다.

### 1.2 코어 계약의 알려진 한계

코어 백로그에 열린 채로 있는 두 한계는 이 모듈이 막을 수 없다
([코어 백로그 EE-107 · EE-108](https://github.com/kangwoo/aimon-core/blob/main/docs/backlog/execution-environment-open-items.md)).

- 사용자가 친 인라인 `/skill` 의 도구 호출은 메인 턴의 환경 큐를 스킬의 스크래치 루프로 꺼내고, 메인 에이전트는 그것을 듣지 못한다.
  샌드박스는 스크래치 루프와 메인 루프를 구별할 수 없다.
- Java 훅이 `drainNotices()` 를 부르면(계약 위반) 그 notice 는 잃고, 전환은 기록되며 초기화 표시는 지워진다. 문서로만 막는다.

## 2. 큐

**큐는 실행 환경 인스턴스마다 하나다.** `resolve` 는 루트와 포크 요청마다 새 환경을, 그래서 새 큐를 만든다. 포크와 나누지도, 부모의
것으로 채우지도 않는다. 코어가 공유를 금지하고, 포크의 모델은 옛 파일을 본 적이 없기 때문이다. 포크가 발견한 재생성을 부모가 들어야
하는 반대 방향은 generation 추적(§4)이 맡는다.

**샌드박스에 닿는 호출은 모두 한 진입점으로 connect 하고 notice 를 같은 큐에 넣는다.** 셸 · 파일 · 스테이징 · 검색이 그렇다. 그래서
초기화 표시(§5) · replay · generation 기준선이 호출 경로마다 갈리지 않는다.

- 같은 문구는 한 번만 남는다(중복이 접힌다).
- 큐는 동기화되어 있다. 같은 인스턴스의 다른 스레드에서 connect 와 drain 이 경합해도 각 notice 는 한 번만 나가고, drain 뒤에 들어온
  것은 다음 도구 결과로 간다.
- 개수 상한은 없다. 코어와 `Bash` 도 두지 않는다.
- drain 은 저장소 I/O 를 기다리지 않는다. drain 뒤의 쓰기(§3 의 전환 기록, §5.2 의 확인)는 배경 실행기([수명 §7.5](workspace-sandbox-lifecycle.md))
  에 넘기고, 거부되면 로그를 남기고 버린다 — 최악은 notice 의 한 번 반복이다.

## 3. 워크스페이스 전환 notice

세션의 실행이 지난번과 다른 워크스페이스로 바인딩되면 모델은 그 사실을 한 번 들어야 한다. 그렇지 않으면 예전 파일이 `/workspace`
아래에 있다고 믿고 일한다. 기준은 세션 옵션 레코드의 `lastWorkspaceId` — 모델이 마지막으로 들은 워크스페이스다.

**판정은 바인딩 정책이, 기록은 실행 환경이 한다.** 정책은 바인딩한 워크스페이스(우선순위의 어느 행이든, [바인딩 §2](workspace-sandbox-binding.md))
와 `lastWorkspaceId` 를 비교해 바인딩에 `WorkspaceSwitch(from?, to)` 를 싣고, 레코드를 읽은 version 도 함께 싣는다. 정책은 쓰지
않는다 — `lastWorkspaceId` 의 쓰는 쪽은 하나다.

| 경우 | 판정 |
|---|---|
| `from == to` | 전환 없음 |
| 레코드가 없고 요청 속성이 바인딩하지 않음 | 전환 없음 — 한 번도 옮겨지지 않았다 |
| 요청 속성이 바인딩했고 `lastWorkspaceId` 가 없음 | 알린다(from 없음) — 레코드 유무와 상관없이 |
| 레코드가 있고 `lastWorkspaceId` 도 `workspace` 도 없음(규칙만 있는 레코드) | 조용히 기록한다 — 옮겨진 것이 없다 |
| 미전달 초기화만 든 레코드, 요청되지 않은 바인딩 | 레코드가 없는 것으로 본다(아래) |
| 그 밖 | 알린다(from → to) |

**알리는 문구.** "this session now runs in sandbox workspace `<to>`; files and shell state from any earlier workspace of this session are
not here" — from 이 없는 모든 전환(요청 속성의 첫 바인딩, 세션 옵션의 첫 붙임)이 이 문구를 쓴다. 요청 속성으로 첫 턴부터 고정된 새
세션도 이 문구를 한 번 듣는다.

**큐에 넣는 시점과 기록 시점.** 실행 환경은 첫 connect 가 성공하면 알릴 전환을 connect 자신의 notice 앞에 큐에 넣는다. 조용한
전환은 그 첫 connect 에서 기록한다. **알린 전환은 connect 가 아니라 그 notice 가 꺼내질 때 기록한다.** 훅 · 워크플로 스크립트 ·
스테이징처럼 큐를 꺼내지 않는 경로에서 connect 한 인스턴스가 notice 를 든 채 사라지면, connect 때 기록했다면 공지를 영영 잃지만 drain 때
기록하면 다음 턴이 다시 알린다. 코어가 drain 을 전달 시점으로 정했으니 확인 시점도 그곳이다. 두 기록 모두 배경 실행기에서 돌고, 실제
쓰기는 §5.2 의 확인 쓰기 하나로 초기화 표시 지우기와 합쳐진다.

**기록은 읽은 version 에 대한 CAS 다.** 최선 노력이고, 저장소 오류나 진 CAS 는 로그(DEBUG)로 남기고 도구 호출을 실패시키지 않는다.
최악은 다음 턴에 notice 가 한 번 반복되는 것이다. 다시 붙이기(re-attach)와 경합하면, 그 전에 바인딩된 턴은 바인딩된 대상을 알리고
CAS 를 지며, 다음 턴이 새 대상을 한 번 알린다 — 도는 턴은 자기 바인딩을 끝까지 쓰기 때문이다.

**레코드가 없으면 기록하면서 만든다.** 이렇게 생긴 레코드(그리고 §5 의 초기화 표시가 만든 레코드)처럼 워크스페이스 연결 없이
`lastWorkspaceId` 나 `unannouncedReset` 만 든 세션 옵션 레코드를 **bookkeeping 레코드**라 부른다.

- `setBy` 는 실행의 호출자이고 세션 소유자가 아닐 수 있다. 아무 권한도 주지 않는다 — 세션에 대한 모든 권리는 소유자 출처로 검사하고
  `setBy` 로 검사하지 않는다([바인딩 §4](workspace-sandbox-binding.md)).
- `workspace` 가 없으므로 붙음으로 보이지 않는다. 운영자의 세션 목록과 워크스페이스 상세는 그것을 나열하지 않는다([관리 §4](workspace-sandbox-management.md)).
- 세션 삭제 리스너와 janitor 스윕이 세션과 함께 지운다([수명 §7.4](workspace-sandbox-lifecycle.md)).
- 기록이 레코드를 *만들어야* 할 때, 코어 세션 저장소가 배선되어 있고 그 세션이 이미 없으면 건너뛴다. 지운 세션의 상태를 되살리지 않기
  위해서다. `exists` 검사와 쓰기 사이의 창은 잔여 위험이고, 세션 저장소가 없으면 검사도 스윕도 없다.

**미전달 초기화만 든 레코드는 전환 판정에서 없는 것으로 본다**(요청되지 않은 바인딩에서). 그러지 않으면 파생 워크스페이스 세션에
§5 가 만든 bookkeeping 레코드가 다음 바인딩을 조용한 전환으로 만들어 `lastWorkspaceId` 를 쓰고, 원래 없던 영구 레코드를 남긴다.

**clear 는 `lastWorkspaceId` 가 없으면 떼어 내는 워크스페이스로 채운다.** 세션 옵션의 clear 는 `workspace` 와 `unattached` 를 지우되,
`lastWorkspaceId` 가 아직 없으면 붙어 있던 워크스페이스를 그 값으로 남긴다. drain 된 전환의 비동기 기록이 소유자의 clear 에 CAS 를
지면 레코드가 `lastWorkspaceId` 없이 지워져, 모델이 마지막으로 `wsm:A` 에서 돈다고 들었는데도 다음 턴이 조용히 `ws:{sid}` 로 떨어진다.
이 대체가 그것을 막고, 비용은 없다. 세션 옵션 API 쪽 서술은 [관리 §3.3](workspace-sandbox-management.md) 에 있다.

**포크는 전환을 싣지 않는다.** 포크는 부모의 워크스페이스를 쓰고, 부모의 모델이 이미 들었다.

**도구를 부르지 않은 턴**은 아무것도 꺼내지 않아 기록하지 않으므로 다음 턴이 다시 알린다. 도구를 부르지 않은 턴의 모델은 파일에
손대지 않았으니 놓친 것이 없다.

## 4. generation notice

**포크가 만든 재생성을 부모가 들어야 한다.** 포크의 첫 connect 가 샌드박스를 다시 만들면 "recreated" notice 는 *포크의* 큐에 들어간다.
부모의 다음 connect 는 새 generation 의 RUNNING 슬롯을 만나 아무것도 보고하지 않고, 부모의 모델은 자기 파일이 있다고 믿는 트리를
고친다.

**그래서 인스턴스마다 마지막으로 connect 한 슬롯의 `(incarnation, generation)` 을 기억한다.** connect 가 성공할 때마다, 이 인스턴스가
전에 connect 한 적이 있고, 쌍이 다르고(다른 incarnation, 또는 같은 incarnation 의 더 큰 generation), 이번 connect 가 스스로 초기화를
보고하지 않았으면 "the sandbox was re-created since this execution last used it (generation N); files under /workspace from before are
gone" 을 큐에 넣는다.

- 첫 connect 는 기준선이 없으므로 아무것도 말하지 않는다.
- 비교와 갱신은 한 동기화 단계다. 같은 인스턴스의 병렬 호출은 한 번만 알린다.
- "스스로 초기화를 보고했는가" 는 connect 가 돌려주는 플래그로 판단하고 notice 문자열을 비교하지 않는다. 문구를 바꿔도 이중 공지가
  생기지 않게 하기 위해서다.
- 병렬 호출의 초기화 notice 가 뒤에 오면, 같은 `(incarnation, generation)` 에 대해 아직 큐에 있는 generation notice 를 지운다 — 같은
  사건이고 초기화 notice 가 더 나은 문구다. 그 사이 drain 이 돌았으면 모델은 둘 다 한 번씩 듣는다.
- 지금보다 오래된 generation(재생성 전에 슬롯을 읽은 병렬 호출)은 아무것도 바꾸지 않는다.

connect 가 스스로 TERMINATED 슬롯을 다시 프로비저닝하면(idle terminate 나 LOST 확정 뒤) "the sandbox was recreated (generation N): …"
한 문구를 붙인다. idle reopen 뒤에는 붙이지 않는다 — 초기화 notice 가 이미 말하기 때문이다.

이 추적은 한 인스턴스 안에서만 본다. 턴 사이에 남이 일으킨 재생성은 §8 의 열린 질문이다.

## 5. 미전달 초기화

### 5.1 왜 영속하는가

초기화 notice 는 환경 인스턴스의 메모리 큐에 있고 코어가 drain 할 때만 전달된다. 훅 · 워크플로 스크립트의 도구 호출 · 스테이징처럼
코어가 drain 하지 않는 경로에서 초기화가 일어나고 인스턴스가 끝나면 notice 도 사라지고, 다음 턴은 이미 RUNNING 인 슬롯을 만나 알릴
것이 없다. 모델은 예전 파일이 `/workspace` 아래에 있다고 계속 믿는다. 그래서 **아직 어떤 drain 도 전달하지 않은 초기화를 세션별로
영속한다** — 세션 옵션 레코드의 `unannouncedReset = (workspace, slot, at)`([도메인 §3](workspace-sandbox-domain.md)).

자리를 이렇게 고른 이유:

- **슬롯이 아니라 세션 옵션에 둔다.** 슬롯은 붙은 모든 세션이 공유하므로 처음 drain 한 세션이 모두의 표시를 지우게 되고, 들어야 할
  세션은 끝내 듣지 못할 수 있다. 세션별로 기록하면 워크스페이스 레코드가 세션 수만큼 커진다.
- **노드 메모리에 두지 않는다.** 다음 턴은 다른 노드에서 돌 수 있고, 재시작하면 사라진다. 공유 세션 옵션 저장소가 모든 노드가 보는
  유일한 세션별 상태다.
- **notice 원문은 저장하지 않는다.** `(workspace, slot, at)` 에서 고정 문구를 만든다. 원문을 저장하면 모델용 문구가 호스트 DB 에 굳고,
  원인마다 문구가 다르다. 모델은 "그 시각 이전의 파일이 없다" 만 알면 된다. 원인도 저장하지 않는다.
- **세션별 "마지막으로 본 incarnation · generation" 을 모든 drain 에 기록하는 일반 해법은 쓰지 않는다.** 보통 경로에 쓰기가 생기고,
  샌드박스에 닿는 모든 세션에 레코드가 생긴다. 다른 주체의 재생성까지 알리는 열린 질문(§8)을 닫는 방법으로 남긴다.

세션 하나에 표시는 하나다. 새 초기화가 옛것을 대신한다 — 둘 다 "예전 파일이 없다" 를 말한다.

### 5.2 mark · drain · ack · replay

```
mark   — 루트 환경의 connect 가 초기화를 보고하면, notice 를 큐에 넣기 전에 동기로 표시를 쓴다
replay — 세션의 다음 루트 환경이 그 슬롯에 처음 connect 할 때, 남은 표시를 한 번 다시 알린다
drain  — 표시를 덮는 notice(자기 초기화 notice 나 replay)를 돌려준 drain 이 확인(ack)을 제출한다
ack    — 배경 실행기에서: 표시가 아직 그 값이면 지우고, 대기 중인 전환을 기록한다
```

**누가 표시하나.** 세션이 있는 루트 실행의 환경만이다. 바인딩 정책은 루트 바인딩마다 세션 옵션 레코드를 이미 한 번 읽으므로, 그때 읽은
version 과 표시를 바인딩에 실어 준다 — 추가 I/O 가 없다. 포크와 루틴의 바인딩에는 실리지 않는다.

**mark 는 동기이고 큐에 넣기 전이다.** 다시 읽고 CAS 로 쓰며, 레코드가 없으면 bookkeeping 레코드를 만든다(코어 세션 저장소가 배선되어
있고 세션이 이미 없으면 건너뛴다). CAS 충돌은 `casRetries` 까지 다시 시도한다. 큐에 넣기 전에 쓰므로 어떤 drain 도 영속되기 전의 초기화
notice 를 돌려줄 수 없고, 그래서 표시가 자기 지우기보다 늦게 도착하는 일이 구조적으로 없다. 비동기로 쓰면 ack 가 먼저 돌아 남은 표시가
다음 턴에 다시 알려진다. 동기 쓰기의 비용은 이미 수 초 걸리는 프로비저닝 경로에만 든다.

- `at` 은 매니저의 시계로 찍는다. 저장소가 `Instant` 를 자기 정밀도로 줄일 수 있으므로, 환경은 **저장소가 돌려준** 값을 표시의 정체로
  쓴다(정밀도 요구는 [테스트 §1](workspace-sandbox-testing.md)).
- mark 가 성공하면 돌려받은 레코드의 version 과 표시가 이 인스턴스의 새 기준(known version)이 된다. ack 는 정책이 읽은 version 이
  아니라 이 known version 을 쓴다. 같은 인스턴스의 앞선 mark 때문에 전환 기록이 CAS 를 지지 않게 하기 위해서다.
- 반대 순서도 막는다. drain 이 전환을 전달해 그 기록이 version V 를 기대하는데 같은 인스턴스의 뒤 호출이 초기화를 표시해 레코드를 V+1 로
  먼저 올리면, 전환 기록이 CAS 를 지고 다음 턴이 같은 전환을 다시 듣는다. 그래서 전환 기록이 아직 대기 중이면 mark 가 그 쓰기에
  `lastWorkspaceId` 를 함께 적는다(레코드가 그 version 일 때만, 없던 레코드면 함께 만든다). 둘 중 뒤에 도착한 쪽은 이미 기록된 전환을
  보고 아무것도 하지 않거나(ack 의 CAS 가 무해하게 진다), 다시 붙이기로 version 이 넘어갔으면 둘 다 건너뛴다. mark 가 이미 날아가는 중에
  병렬 호출의 drain 이 전환을 전달하면, mark 는 자기가 저장한 version 으로 전환을 다시 확인한다.
- mark 가 실패하면(저장소 오류, CAS 소진) WARN 을 남기고 메모리 전달은 그대로 한다. 잃는 것은 끝내 drain 되지 않는 경우뿐이다.

**replay 는 그 슬롯의 첫 성공 connect 에서 한 번.** 바인딩이 실어 온 표시가 방금 connect 한 워크스페이스와 슬롯을 가리키고, 이번 connect
가 스스로 초기화를 보고하지 않았으면, 전환 notice 뒤 · connect 자신의 notice 앞에 replay 를 큐에 넣는다.

- 한 인스턴스는 replay 를 최대 한 번 넣는다. "아직 말하지 않은 표시" 는 replay 가 소비하고, 이 인스턴스 자신의 초기화 notice(성공한
  connect 든 실패한 connect 든)도 소비한다. 그러지 않으면 실패한 connect 가 큐에 넣은 초기화를 다음 connect(FAILED 슬롯의 재프로비저닝은
  초기화가 아니다)가 한 번 더 replay 한다.
- 다른 워크스페이스나 슬롯에는 replay 하지 않는다. 표시는 세션이 그 슬롯에 다시 connect 할 때까지 남는다 — 그때도 "예전 파일이 없다"
  는 참이다. 그 사이 워크스페이스가 purge 되어도 표시는 무해하다.
- 실패한 첫 connect 는 표시를 남겨 뒤의 connect 에 맡긴다.
- 초기화 notice 가 큐에 들어오면 아직 큐에 있는 replay 를 지우고, 그 뒤로는 replay 를 넣지 않는다. 병렬 호출에서 한 사실을 두 번 말하지
  않기 위해서다.

**replay 문구.** "/workspace was reset at 2026-10-18T04:00:00Z and this session may not have been told: files and shell state (cwd,
exported variables) from before then are gone" — 시각은 초 단위 UTC 다. 문구는 세 규칙을 지킨다: 파일과 셸 상태가 사라졌다고 말한다,
워크스페이스 이름을 말하지 않는다, idle reopen 의 문구와 달라서 중복 접기가 새 초기화를 replay 뒤에 숨기지 않는다. **시각을 붙이는
이유**: ack 를 잃으면 이미 들은 초기화를 한 번 더 듣게 되는데, 시각이 있으면 모델은 그 뒤에 만든 파일이 해당하지 않음을 안다. 같은
이유로, 부모의 턴이 모델에게 가는 일을 포크에서만 하면 부모는 connect 하지 않아 replay 가 큐에 들어가지 않고 표시는 부모의 다음
connect 를 기다리는데, 그때의 replay 가 포크가 그 사이 만든 파일까지 가리키지 않는 것도 시각 덕분이다.

**ack 는 drain 안에서 하지 않고 배경 실행기에 넘긴다.** drain 은 원격 I/O 로 막히면 안 된다는 코어 계약 때문이다. drain 이 전환을
전달했거나 표시를 덮는 notice 를 전달했을 때만 확인을 하나 제출한다.

```
ack(session, knownVersion, lastWorkspace?, delivered?):     -- 배경 실행기, 최선 노력, 예외 없음
loop (최대 casRetries):
  current = find(session)
  없음: lastWorkspace 가 있고 knownVersion 이 없으면(세션 저장소가 배선되었다면 세션이 있을 때만) { lastWorkspaceId } 로 만든다
        그 밖이면 끝 -- 지울 표시가 없는 초기화
  next = current
  lastWorkspace 가 있고 current.version == knownVersion  → next.lastWorkspaceId = lastWorkspace   -- 엄격한 version
  delivered 가 있고 current.unannouncedReset == delivered → next.unannouncedReset = 없음        -- 값 비교 지우기
  next == current 면 끝
  next 에 workspace · unattached · lastWorkspaceId · unannouncedReset 이 모두 없으면 delete, 아니면 put (CAS)
```

`lastWorkspaceId` 쪽은 엄격한 version CAS 다 — 진 CAS 는 전환을 한 번 반복한다(§3). 지우기 쪽은 값 비교라 관계없는 version 상승을
견딘다. 그 사이 쓰인 새 표시는 값이 다르므로 남는다.

**보통 경로에서는 두 번 전달되지 않는다.** 같은 사실이 메모리(전달됨)와 영속(ack 가 지움) 두 곳에 있지만, 이 인스턴스에서 보이는 것은
메모리 쪽뿐이다. 영속 쪽은 *뒤의* 인스턴스만, 그것도 ack 가 지우지 못했을 때만 보인다. ack 는 턴 중간의 drain 에서 제출되고 턴이 끝나기
전에 LLM 호출이 적어도 한 번 따르므로, 보통은 다음 턴의 바인딩이 레코드를 읽기 훨씬 전에 도착한다.

**포크는 표시를 읽지도 쓰지도 지우지도 않는다.** 부모 인스턴스는 포크가 쓴 것을 알 수 없다. 부모가 재생성된 슬롯에 connect 해서(기준선이
없어 generation notice 도 없다) 모델이 파일을 만들면, 표시가 남아 다음 턴이 "예전 파일이 없다" 는 거짓 notice 를 듣는다 — 알리지 못하는
것보다 나쁘다. 살아 있는 부모는 generation notice(§4)로 포크의 재생성을 듣는다. **루틴**은 세션이 없어 표시가 없다 — 루틴 실행은 예전
파일의 기억 없이 시작하므로 알릴 것이 없다.

**세션 삭제.** 세션 옵션 레코드를 지우는 모든 경로(리스너, 운영자 delete, janitor 스윕)가 표시도 함께 지운다. 리스너는 세션의 턴
게이트를 쥐고 돌므로 mark 가 삭제와 경합하지 않는다. 삭제 뒤에 줄 선 ack 는 레코드를 찾지 못하고, 초기화에 대해서는 아무것도 만들지 않는다.
세션 삭제의 절차는 [수명 §7.4](workspace-sandbox-lifecycle.md) 에 있다.

### 5.3 다중 노드와 비용

영속하는 것은 모두 공유 세션 옵션 저장소에 있고, 메모리 큐는 인스턴스마다라 노드를 떠나지 않는다. 턴 T 의 노드 A 가 mark 하고, 턴 T+1
의 노드 B 가 바인딩에서 표시를 읽어 replay 한다. A 의 ack 는 A 의 배경 실행기에서 공유 저장소에 CAS 로 가고, B 자신의 mark · ack 와
섞여도 값 비교 지우기라 안전하다. 저장소는 키마다 선형화 가능해야 한다 — CAS 를 위해 이미 그래야 한다.

핫패스 비용은 작다. 바인딩에는 추가 읽기가 없다(이미 읽은 레코드). 초기화가 없는 connect 는 분기 하나다. 초기화한 connect 는 세션 옵션
읽기 한 번과 CAS 쓰기 한 번(레코드를 만들 때 `exists` 한 번 더)을 동기로 하지만, 샌드박스를 프로비저닝하는 경로다. replay 는 바인딩에서
오므로 I/O 가 없다. drain 은 호출 스레드에서 아무것도 하지 않고, 전환이나 초기화를 전달했을 때만 배경 작업 하나를 낸다.

### 5.4 실패

| 경우 | 결과 |
|---|---|
| idle close 뒤 턴 T 의 유일한 샌드박스 사용이 `onStop` 훅이고, 그것이 reopen 으로 초기화함 | mark 를 쓰고 notice 는 큐에 남은 채 인스턴스가 사라진다. 턴 T+1 의 바인딩이 표시를 읽고, 첫 connect(같은 워크스페이스, RUNNING, 초기화 없음)가 replay 를 넣고, 첫 도구 결과가 그것을 한 번 싣고, ack 가 지운다 |
| 보통 경로: 같은 턴에 drain 됨 | 모델은 원래 notice 를 한 번 듣고, ack 가 턴이 끝나기 전에 지운다. 다음 턴은 표시를 읽지 않는다 |
| ack 가 다음 턴의 바인딩보다 늦음(배경 실행기 포화, 저장소 장애) | 다음 턴에 replay 한 번. 이미 들은 초기화이므로 거짓 notice 가 될 수 있고, 시각이 그 범위를 좁힌다. replay 자신도 확인되므로 창은 한 턴이다 |
| mark 실패(저장소 오류, CAS 소진) | WARN. 메모리 전달은 그대로. 끝내 drain 되지 않는 경우만 잃는다. 같은 슬롯의 옛 표시는 이번 턴의 전달이 확인한다 |
| ack 실패 · CAS 모두 짐 | DEBUG. 표시가 남아 다음 턴에 replay 한 번 |
| drain 전에 한 인스턴스에서 초기화 두 번 | 둘째 mark 가 표시를 대신한다. drain 이 두 notice 를 전달하고 새 값을 확인해 지운다 |
| drain 이 첫 초기화를 전달한 뒤 둘째 초기화 | 첫 ack 의 값 비교가 새 값에 실패해 남는다. 둘째 drain 이 확인한다 |
| 다음 턴의 첫 connect 가 또 초기화 | replay 없음(새 notice 가 말한다). 새 mark 가 표시를 대신한다 |
| 다음 턴도 훅만 돎 | replay 가 메모리에 들어가고 drain 되지 않는다. 표시는 그대로이고 다음에 drain 되는 턴이 알린다 |
| 다음 턴이 다른 워크스페이스로 감(전환) | 지금은 replay 없음, 전환 notice. 표시는 그 슬롯에 다시 connect 할 때까지 남는다 |
| 포크가 슬롯을 초기화 | 메모리에만(포크의 모델이 듣는다). 살아 있는 부모는 generation notice 를 듣는다. 다시 connect 하지 않고 끝나는 부모는 듣지 못한다(§8) |
| 한 인스턴스의 병렬 호출 중 하나가 초기화 | mark 는 하나(초기화하는 connect 는 하나뿐). notice 는 접힌다. replay 는 락 아래 한 번이고, 초기화 notice 가 큐에 있던 replay 를 지운다 |
| 결과가 drain 된 뒤 버려짐(at-most-once) | 잃고 확인된다. 코어의 at-most-once 를 받아들인다 |
| Java 훅이 `drainNotices()` 를 부름 | 그 notice 는 잃고 확인된다(§1.2) |
| 커스텀 세션 옵션 저장소가 필드를 버림 | replay 가 일어나지 않는다. 계약 스위트가 잡는다(§7) |

## 6. connect 실패 때의 notice

idle reopen 이나 소실 뒤의 재생성이 실패하면, 다음 호출은 TERMINATED 가 아니라 FAILED 슬롯을 보므로 초기화를 알릴 근거가 없다. 그것을
아는 것은 실패한 그 호출뿐이다. 그래서 실패한 connect 가 모은 notice 는 버리지 않는다. 가는 곳은 호출자에 따라 둘이다.

- **실행 환경 안 — 큐에 넣고, 오류에는 이유만.** 매니저는 실패한 connect 가 모은 notice 를 예외에 따로 실어 던진다. 실행 환경은 그것을
  떼어, 초기화이면 표시를 쓰고(§5.2), notice 를 큐에 넣은 뒤, 이유만 담은 `SandboxUnavailableException` 으로 다시 던진다. 모델에게 가는
  호출이면 도구 에러는 이유만 읽고 초기화 notice 는 같은 결과에 `[environment]` 줄로 실린다(한 번 전달되고 확인된다). 훅의 호출이면
  이유만 받고, notice 는 다음 drain 이나 다음 턴을 기다린다.
- **실행 환경 밖 — 메시지에.** `provisionSlot`(관리형 즉시 프로비저닝)이나 운영자 경로처럼 환경 밖에서 connect 를 부르는 호출자는
  `SandboxUnavailableException` 의 메시지 끝 "(also: …)" 로 notice 를 받는다.

환경 안에서 notice 를 메시지에도 남기고 표시도 쓰는 방식은 쓰지 않는다. 모델에게 가는 실패라면 지금 에러에서 초기화를 보이고, 어떤
drain 도 메시지 텍스트를 "돌려준" 적이 없으니 다음 턴에 replay 까지 하게 된다. 큐로 보내면 채널 하나, 전달 한 번이고, 훅의 실패한
connect 도 초기화를 삼키지 않는다.

실패한 connect 는 기준선을 세우지 않는다 — 전환 notice, replay, generation 추적은 첫 *성공* connect 를 기다린다. 다만 그 실패가 초기화를
큐에 넣었으면 "아직 말하지 않은 표시" 는 소비된다(§5.2).

저장소나 admission 의 예외를 사용 불가로 바꾸는 규칙은 [수명 §2.1](workspace-sandbox-lifecycle.md) 에 있다.

## 7. 호환성

**커스텀 `SessionOptionsStore` 는 `unannouncedReset` 을 영속해야 한다.** 따로 구현할 메서드는 없고, 이 값을 다른 필드처럼 다루면 된다. `deleteByWorkspace` 는 각
레코드의 다른 필드와 함께 이 값을 남기고, `sessions(...)` 는 이 값만 든 레코드도 돌려준다. 저장소가 이 값을 버리면 replay 가 일어나지 않는다
— 훅만 돈 턴의 초기화를 다음 턴이 듣지 못한다. 계약 스위트가 이것을 검사한다([테스트 §1](workspace-sandbox-testing.md)).

| 호스트 · 호출자 | 영향 |
|---|---|
| 커스텀 `SandboxBindingPolicy` | 없다. `WorkspaceSandbox` 가 그것을 감싸 세션 옵션 처리와 표시 전달을 더한다 |
| connect 의 `SandboxUnavailableException` 을 잡는 코드 | 실행 환경 밖에서는 메시지 끝의 "(also: …)" 로 notice 를 받는다. 환경 안에서는 도구 에러에 그 꼬리가 없고 notice 는 `[environment]` 줄로 온다(§6) |
| 운영자 화면(세션 옵션 뷰, 워크스페이스의 세션 목록) | bookkeeping 레코드는 `workspace` 가 없어 붙음으로 나열되지 않는다(§3) |

## 8. 열린 질문

- **턴 사이의 재생성 알림** — 실행 안의 재생성(포크가 만든 것 포함)은 generation 비교로 알리고(§4), 세션 *자신의* 루트 실행이 일으키고
  전달하지 못한 초기화는 미전달 초기화가 알린다(§5). 남은 것은 다른 주체가 일으킨 재생성이다 — 다른 세션(공유 `wsm:` 워크스페이스), 부모가
  그 턴에 다시 connect 하지 않은 포크, 운영자의 `reopen` 이나 슬롯 terminate 뒤의 `provisionSlot` 처럼 세션이 아닌 경로. 다음 턴의 새
  환경은 비교할 기준이 없어 이것들을 알리지 못한다. 막으려면 세션마다 마지막으로 본 generation 을 `lastWorkspaceId` 옆에 기록해야 한다
  (§5.1) — 보통 경로에도 쓰기가 생기고 세션마다 레코드가 남으며, 공유 워크스페이스에서 남의 재생성을 알릴지는 제품 판단이다.
- **청소 없는 호스트에 남는 레코드** — 수명 리스너도 `sessionRecords(...)` 도 없는 호스트에서는, 마지막 초기화를 전달하지 못한 채 지워진
  세션의 작은 레코드가 영원히 남는다(`lastWorkspaceId` bookkeeping 레코드와 같다). 보통 경로에서는 ack 가 쓸모없어진 레코드를 지우므로,
  남는 것은 마지막 초기화가 전달되지 않았고 다시 돌지 않은 세션뿐이다. `closedRetention` 같은 만료를 둘지 정하지 않았다.
- **관리 화면에 보이지 않는 `unannouncedReset`** — 세션 옵션 뷰와 관리 API 는 아직 전달되지 않은 초기화를 보이지 않는다([관리 §5](workspace-sandbox-management.md)).
  "모델이 파일을 잃었다" 는 보고를 볼 때 쓸모가 있을지 정하지 않았다.
