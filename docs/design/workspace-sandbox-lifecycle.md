# 워크스페이스 샌드박스 — 수명

샌드박스와 워크스페이스가 언제 생기고, 무엇이 그것을 유지하고, 무엇이 끝내는지를 정한다. connect 상태 기계와 프로비저닝,
seed, idle 정책, 프로바이더 만료와 heartbeat, janitor 의 idle 집행과 조정, 워크스페이스 종료(close · 툼스톤 · purge · 세션
삭제)와 배경 실행기를 다룬다.

> 관련: [개요](workspace-sandbox.md) · [도메인](workspace-sandbox-domain.md) · [바인딩](workspace-sandbox-binding.md) ·
> [notice](workspace-sandbox-notices.md) · [관리](workspace-sandbox-management.md) · [프로바이더](workspace-sandbox-provider.md) ·
> [OpenSandbox](workspace-sandbox-opensandbox.md) · [설정](workspace-sandbox-configuration.md)

워크스페이스 · 슬롯 · generation · incarnation 의 정의와 상태 목록은 [도메인 §1 · §2](workspace-sandbox-domain.md) 에 있다. 이
문서는 그 상태를 언제, 누가, 어떤 순서로 바꾸는지를 쓴다. 타이머와 상한의 기본값은 [설정 §2](workspace-sandbox-configuration.md)
가 소유한다.

## 1. 샌드박스와 볼륨을 끝내는 경로

워크스페이스의 수명은 세션 · 런타임의 수명과 다르다([개요 §4](workspace-sandbox.md)). `LiveSession.close()` 와
`AgentRuntime.close()` 는 샌드박스를 건드리지 않는다. 같은 워크스페이스를 다른 노드의 다른 세션이 쓰고 있을 수 있고, 런타임은 여러
워크스페이스의 실행을 받기 때문이다.

샌드박스를 끝내는 경로는 다음뿐이다.

| 경로 | 무엇을 끝내나 | 정본 |
|---|---|---|
| 워크스페이스 close(명시적 close, idle close, 세션 삭제) | 그 워크스페이스의 모든 샌드박스 | §7 |
| idle 정책의 terminate | `terminateAfter` 를 넘긴 슬롯의 샌드박스 | §4, §6.1 |
| janitor 의 버려진 claim 회수 | 죽은 claimer 가 만들었을 샌드박스 | §6.1 |
| janitor 조정 — ORPHAN · STALE · FAILED_LEFTOVER · DUPLICATE | 레코드가 쓰지 않는 샌드박스 | §6.2 |
| 프로비저닝 중 검증 · seed 실패 | 그 claim 이 만든 자기 샌드박스(FAILED CAS 를 이긴 경우만) | §2.1, §3 |
| 운영자의 슬롯 terminate | 한 슬롯의 샌드박스 | [관리 §4.5](workspace-sandbox-management.md) |
| 프로바이더 만료 | 마지막 활동에서 `terminateAfter` 가 지난 샌드박스 | §5 |
| 오케스트레이터 도구의 `SandboxStop` **(계획 — 구현 단계 5)** | 호출자가 지정한 슬롯 | [바인딩 §7](workspace-sandbox-binding.md) |

**(계획 — 구현 단계 5)** 공유 볼륨을 지우는 경로는 워크스페이스 close(§7.1)와 janitor 의 볼륨 조정(§6.3)뿐이다. 지금은
`sharedAccess` 가 `none` 이 아닌 프로파일을 기동 시 거부하므로 공유 볼륨이 생기지 않는다.

## 2. connect 와 프로비저닝

실행 환경의 `resolve()` 는 바인딩만 만들고 원격 자원을 만들지 않는다. 샌드박스는 첫 파일 · 셸 · 스테이징 · 검색 호출이
`SandboxWorkspaceManager.connect(binding)` 을 부를 때 생긴다(§2.3). 모든 호출은 명령 하나마다 connect 를 다시 돈다 — 읽기는
매번, 쓰기는 스로틀한다([도메인 §6](workspace-sandbox-domain.md)).

### 2.1 상태 기계

```
connect(binding):
loop (최대 casRetries; StaleVersionException → 다시 읽고 처음부터):
1. record = find(id), 없으면 createIfAbsent(OPEN, owner = 바인딩의 owner, 새 incarnation)
   -- mustExist 바인딩은 만들지 않는다: "sandbox workspace <id> does not exist"
2. record 가 OPEN 이 아니고 closeCause = SESSION_DELETED → "being removed; retry shortly"   (§7.4)
3. 사용 권한 검사(owner 검사) -- 레코드 상태에 따른 분기보다 먼저
4. CLOSED(IDLE)      → CAS reopen(새 incarnation, 슬롯 모두 TERMINATED), 초기화 notice
   CLOSING           → "being closed; retry shortly"
   CLOSED(EXPLICIT)  → "closed; the application has to reopen it"
   슬롯 프로파일 검사(요구한 프로파일 ≠ 슬롯의 프로파일, 기록된 프로파일이 설정에서 사라짐 → 사용 불가)
5. slot = record.slots[binding.slot]
     없음 / TERMINATED / 재시도 가능한 FAILED
        → admission · 쿼터 → CAS PROVISIONING(generation+1, claim = (now, 노드))
        → provider.create(key, expiresAt = now + terminateAfter) → status · 라벨 대조 → CAS RUNNING(providerRef)
     RUNNING            → 연결 캐시. 샌드박스가 없으면 CAS TERMINATED(lostAt), 소실 notice, 처음부터
     FAILED(permanent)  → 기록된 실패로 거절 (프로파일 해시가 바뀌거나 슬롯이 terminate 될 때까지)
     FAILED(transient)  → backoff 전이면 기록된 실패로 거절
     PROVISIONING, since + provisionTimeout 전 → 레코드를 backoff 로 다시 읽으며 기다린다
     PROVISIONING, since + provisionTimeout 지남 → 넘겨받기 CAS(같은 generation, 새 claim) → create   (§2.4)
     PAUSED             → admission · 쿼터 → resume → CAS RUNNING            (계획 — 구현 단계 7, §4.1)
6. !slot.seeded → seed(§3) → CAS seeded = true, 검사 실패면 CAS FAILED(permanent, step)
7. 연결을 돌려준다. 프로비저닝 + seed 가 5초 이상 걸렸으면 notice
```

**owner 검사는 상태 분기보다 먼저다.** 검사가 뒤에 있으면 남의 주체가 idle reopen CAS 를 일으켜 소유자가 들을 초기화 notice
를 가져가고, 워크스페이스가 닫혀 있다는 사실을 알게 된다. 이 순서가 지키는 노출 범위와 그 예외(2단계의 세션 삭제)는
[바인딩 §8](workspace-sandbox-binding.md) 이 정한다.

**`create` 뒤의 검증이 실패하면 그 샌드박스를 지운다.** `create` 가 돌려준 샌드박스의 `status` 가 실패하거나 샌드박스가 보이지
않으면 FAILED CAS 를 걸고, 그 CAS 를 이겼을 때만 destroy 한다. 그 샌드박스는 이 generation 의 키로 이 claim 이 만든 것이고,
CAS 를 이겼으므로 넘겨받은 쪽도 없다. 라벨이 레코드와 맞지 않는 샌드박스는 FAILED(permanent, 단계 `labels`)로 두되 지우지
않는다. 남의 샌드박스일 수 있기 때문이다([프로바이더 §4](workspace-sandbox-provider.md)).

**프로바이더 호출 뒤 CAS 가 지면 처음부터 다시 판단한다.** `create` 를 마친 뒤 RUNNING CAS 가 지면(그 사이 janitor 나 close 가
상태를 바꿨거나, 넘겨받은 노드가 이겼거나) 방금 만든 샌드박스를 호출 경로에서 지우지 않는다. 레코드를 다시 읽어 상태 기계를 다시
돌고, 쓰이지 않게 된 샌드박스는 조정(§6.2)이 STALE · DUPLICATE · ORPHAN 으로 회수한다. 호출 경로에서 destroy 를 부르면 이긴
쪽이 쓰는 샌드박스를 지울 수 있다.

**새 슬롯의 프로파일은 정해진 순서로 고른다.** 이미 있는 슬롯은 그 슬롯의 프로파일을 쓴다. 새로 만들 때는 바인딩이 요구한
프로파일, 없으면 (`primary` 슬롯이고 관리형 레코드가 프로파일을 가지면) 레코드의 프로파일, 없으면 **지금** 설정의 기본
프로파일이다. 기록된 프로파일이 재배포로 설정에서 사라진 슬롯은 connect 가 "slot … was created with profile X, which is no longer
configured" 로 거부한다. 그 슬롯의 서술자는 `resolve()` 에서 기본 프로파일로 쓴다. 설정되지 않은 프로파일을 *요구*하는 바인딩은
`resolve()` 가 거부한다.

**한 호출이 다시 만드는 횟수에는 상한이 있다.** 한 connect 는 소실된 샌드박스를 최대 세 번 다시 만들고, 그래도 쓸 수 없으면
"lost again right after being recreated … retry later" 로 답한다. 세는 것은 이긴 PROVISIONING claim 뿐이다. 대기에는 시간
상한(`provisionTimeout`)이 있지만 소실 → 재생성 순환에는 상한이 없으면 한 도구 호출이 끝나지 않을 수 있기 때문이다.

**`seeded` 를 RUNNING 과 따로 둔다.** RUNNING 으로 CAS 한 노드가 seed 도중에 죽으면 RUNNING 만으로는 seed 가 끝났는지 알 수
없다. seed 는 전부 멱등이라(§3) 두 노드가 동시에 돌거나 중간부터 다시 돌아도 결과가 같다. seed 가 끝나지 않은 슬롯에서 온 명령은
seed 를 기다린다.

**프로비저닝 시간은 명령 타임아웃에 넣지 않는다.** 첫 명령은 샌드박스 기동을 기다릴 수 있다(수 초 이상). 이 대기는
`provisionTimeout` 으로 따로 제한하고, 모델이 준 명령 타임아웃은 명령이 실제로 시작된 뒤부터 센다. 프로비저닝과 seed 가 5초 이상
걸리면 "sandbox provisioned in {n}s" notice 를 붙인다([notice §1](workspace-sandbox-notices.md)).

**`mustExist` 바인딩은 레코드를 만들지 않는다.** 1단계가 `createIfAbsent` 대신 "does not exist" 로 답하고 저장소는 그대로다. 그
의미와 이유는 [바인딩 §1](workspace-sandbox-binding.md) 이 정한다.

**`extendExpiry` 가 not found 를 받으면 LOST 경로로 간다.** 슬롯을 TERMINATED(`lostAt`)로 CAS 하고(generation 과 `providerRef`
로 보호), 도는 명령을 끝내고, "the sandbox was lost mid-command; the environment will be recreated on the next call and /workspace
will be reset" 으로 실패시킨다. 다음 호출이 generation+1 로 새로 만들고 notice 를 붙인다.

**예외는 사용 불가로 바꾼다.** 저장소나 애플리케이션의 `SandboxAdmission` 이 던진 `RuntimeException` 은
`SandboxUnavailableException` 으로 바꾼다. 코어 도구는 실행 환경의 사용 불가 예외만 잡아 그 메시지를 모델에게 주므로, 날것의
예외는 사유를 잃는다([개요 §7](workspace-sandbox.md)). 실패한 connect 가 모은 notice 를 어디로 보내는지는
[notice §6](workspace-sandbox-notices.md) 이 정한다.

### 2.2 실패 분류

**재시도해도 결과가 같은 실패는 `permanent` 다.** 선언과 다른 platform · OS 버전, root 실행, 서비스 계정 토큰 마운트, 라벨
불일치, 이미지 계약 위반, 쓸 수 없는 `/workspace`, 제어면에 닿는 네트워크, `verify` 가 찾은 egress 모드 · vault 불일치(§3),
선언한 런타임 없음, 프로바이더의 영구 에러(`SandboxProviderException.kind = PERMANENT`). 설정이나 인프라를 고쳐야 풀리므로
프로파일 내용이 바뀌거나(레코드의 `profileHash` 와 달라지거나) 슬롯이 terminate 될 때까지 재시도하지 않는다. 그렇지 않으면 활성
세션마다 backoff 간격으로 샌드박스를 만들고 버리는 순환이 생긴다.

**그 밖의 실패는 `transient` 다.** 프로바이더 5xx · 타임아웃, 제때 Running 이 되지 않은 샌드박스, seed 락 경합(§3)이 그렇다.
재시도 간격은 `failureBackoff` 에서 시작해 실패마다 두 배로 늘고 `maxFailureBackoff` 에서 멈춘다. backoff 가 지난 뒤의 호출은
TERMINATED 와 같이 generation 을 올려 새로 만든다. 재시도가 generation 을 올리는 이유는 [도메인 §2.2](workspace-sandbox-domain.md)
에 있다.

`create` 는 제때 Running 이 되지 않은 샌드박스를 지우지 않고 transient 실패로 끝낸다(이유는 [프로바이더 §4.2](workspace-sandbox-provider.md)).
남은 샌드박스는 조정이 레코드를 다시 읽은 뒤 FAILED_LEFTOVER 로 회수한다(§6.2).

### 2.3 게으른 프로비저닝과 즉시 프로비저닝

**세션을 열 때가 아니라 첫 사용 때 만든다.** 많은 세션은 명령을 한 번도 실행하지 않는다. 샌드박스 하나는 수 초의 기동 시간과
CPU · 메모리 예약을 뜻한다.

**묻기만 해서는 프로비저닝하지 않는다.** `resolve()`, `descriptor()`, `shell()`, 셸 엔진의 `supports` 판단,
`runtimeFileSystem()` 은 원격 자원을 만들지 않는다. 서술자는 그래서 프로파일의 선언값을 쓰고, 실제 이미지와의 대조는 seed 가
한다(§3). 코어가 셸 엔진을 이 환경에 낼지 판단하는 것도 이 원칙을 따른다([셸 엔진 §1](workspace-sandbox-shell-engine.md)).

**관리형 워크스페이스의 `create` 는 바로 프로비저닝한다.** 호스트는 에이전트가 돌기 전에 워크스페이스가 준비되었는지 알아야
하기 때문이다. `create` 는 레코드를 만든 뒤 같은 상태 기계를 2단계부터 돈다 — 프로비저닝 경로를 하나로 두기 위해서다. 이때 쓰는
매니저 내부 경로 `provisionSlot(id, slot, profile, caller)` 는 `SandboxBinding` 을 만들지 않는다. connect 는 명령을 실행하지
않는데 바인딩에는 shellKey 가 필수이기 때문이다. `provisionSlot` 은 `mustExist` 이고 레코드의 프로파일을 요구 프로파일로 쓴다.
실패하면 레코드는 남고(슬롯 FAILED, admission 거부면 슬롯 없음) 호스트가 `provision(id)` 로 다시 시도한다. API 의 모양과 실패
응답은 [관리 §2.2](workspace-sandbox-management.md) 에 있다.

### 2.4 프로비저닝 인계

같은 슬롯을 두 노드가 동시에 만들려 하면 레코드 CAS 때문에 한쪽만 PROVISIONING 으로 올리고, 다른 쪽은 그 상태를 보고
기다린다. claim 한 노드가 `create` 중에 죽으면 레코드는 PROVISIONING 에 남는다.

- `provisioning.since` 에서 `provisionTimeout` 이 지나면 다른 노드가 **CAS 로 claim 을 넘겨받은 뒤에만** 같은 키로 `create` 를
  다시 부른다. 넘겨받기 CAS 는 워크스페이스가 OPEN 일 것도 조건으로 건다. close 가 막 CLOSING 으로 바꾼 레코드에서 `create` 를
  시작하지 않기 위해서다. 넘겨받기 CAS 에 진 노드는 계속 기다린다.
- 원래 노드가 죽지 않고 느렸을 뿐이면 같은 키의 샌드박스가 둘 생길 수 있다. 프로바이더의 멱등 생성이 최선 노력이기
  때문이다([프로바이더 §4](workspace-sandbox-provider.md)). 레코드에 적히지 않은 쪽은 조정이 DUPLICATE 로 회수한다(§6.2).
- 기다리는 노드가 `provisionTimeout` 을 넘기면 에러를 돌려주고, 넘겨받기는 그 다음 호출이 한다. 한 도구 호출이
  `provisionTimeout` 을 두 번 기다리지 않게 하려는 것이다.
- 남의 PROVISIONING 을 기다리는 시간은 주입한 시계와 실제 시간 둘 다로 `provisionTimeout` 을 넘지 않는다. 주입한 시계가 멈춰
  있으면 시계만으로는 영원히 기다린다. 넘겨받을지는 주입한 시계로 판단한다.
- 아무도 넘겨받지 않은 claim 은 janitor 가 `2 × provisionTimeout` 뒤 회수한다(§6.1).

## 3. seed

seed 는 새 generation 마다 한 번, 샌드박스를 쓰기 전에 도는 검사와 초기화다. 모든 단계는 멱등이다. 순서는 다음과 같다.

```
seed(slot, generation):
1. provider.verify(ref, 요구 capability)          -- 프로바이더만 볼 수 있는 것: egress 모드, vault 바인딩
   --- 아래는 샌드박스 안의 한 exec, 작업 디렉터리 "/" ---
2. /workspace 가 쓰기 가능한 디렉터리인가          -- step workspace
3. 이미지 계약: command -v git rg flock sha256sum base64,
   /usr/bin/sha256sum · /usr/bin/base64 실행 가능, sha256sum -c --strict - 가 GNU 처럼 동작   -- step image-contract
4. /workspace/.aimon-seed.lock 을 열고 flock       -- 열지 못하면 step workspace(permanent), 경합이면 exit 75(transient)
   --- 여기부터 seed 락 아래 ---
5. uname -s = 선언한 platform, 선언했을 때만 uname -sr 이 osVersion 글롭에 맞음   -- step platform · os-version
6. HARDENED_SECURITY_CONTEXT 를 면제하지 않았으면: uid ≠ 0, 서비스 계정 토큰 없음   -- step root · service-account-token
7. NETWORK_ISOLATION 을 요구하고 면제하지 않았으면: 제어면 엔드포인트마다 TCP 연결이 실패해야 한다   -- step network-isolation
8. runtimes 에 python3 이 있으면: python3 확인                -- step runtime-python3
9. rm -rf /workspace/.tmp-*                         -- 중단된 seed 의 잔재
10. mkdir -p /workspace/.aimon-staged /workspace/.aimon-shell <root>
```

**이미지 계약을 seed 락보다 먼저 검사한다.** `flock` 이 없는 이미지는 락 단계에서 실패하므로, 락 오류가 아니라 이미지 계약
위반으로 먼저 보고해야 한다. 그보다 앞서 `/workspace` 를 검사한다. 락 파일이 그 안에 있고, bash 는 리다이렉션이 실패해도
계속 가므로 `flock` 이 없는 fd 를 락 경합으로 보고하기 때문이다 — 그러면 permanent 실패가 매 호출 재시도되는 transient 실패가 된다.
검사 항목의 내용은 [설정 §4](workspace-sandbox-configuration.md)(이미지 계약)와 [셸 엔진 §2](workspace-sandbox-shell-engine.md)
(python3)에 있다. 이 절은 순서만 정한다.

**seed exec 은 `/` 에서 돈다.** 없는 작업 디렉터리를 거부하는 프로바이더가 `/workspace` 부재를 스크립트의 검사보다 먼저
transient 에러로 만들지 않게 하기 위해서다.

**seed 락은 같은 슬롯을 넘겨받은 두 노드의 seed 를 직렬화한다.** 락 경합은 종료 코드 75 의 transient 실패라 다음 호출이 다시
시도한다. 검사 실패는 종료 코드 65 와 실패한 step 을 내는 permanent 실패다.

**실패한 검사는 permanent 이고 그 샌드박스를 지운다.** 슬롯을 FAILED(permanent, step)로 CAS 하고, 그 CAS 를 이겼을 때 샌드박스를
destroy 한다. 라벨 대조를 통과한 이 generation 의 자기 샌드박스이고, 이제 어떤 레코드도 그것을 가리키지 않는다. `runtime-python3`
실패가 permanent 인 이유: 같은 이미지로 다시 만들어도 결과가 같고, 운영자가 선언을 고치면 프로파일 해시가 바뀌어 FAILED 슬롯이 다시
시도된다.

**`verify` 는 seed 스크립트 앞에서 프로바이더만 볼 수 있는 것을 본다.** 선언으로 광고한 capability 가 살아 있는 샌드박스에 실제로
걸렸는지다. 선언은 `dns+nft` 인데 서버가 `dns` 로 돌면 FAILED(permanent, `egress`), vault 가 생성 때 받은 바인딩을 갖고 있지
않으면 FAILED(permanent, `credentials`)이고, 위와 같이 샌드박스를 지운다. 샌드박스가 사라졌으면 LOST 경로, 검사를 돌리지
못했으면(일시 실패) `seeded` 가 false 로 남아 다음 호출이 다시 한다. OpenSandbox 가 무엇을 대조하는지는
[OpenSandbox §4.9](workspace-sandbox-opensandbox.md) 에 있다.

**네트워크 격리 탐침은 seed 스크립트가 샌드박스 안에서 한다.** 프로바이더는 찌를 주소(`controlPlaneEndpoints`)만 준다. 탐침을
프로바이더마다 다시 만들지 않고, 로컬 프로바이더로 기본 빌드에서 시험하기 위해서다. 탐침 규칙:

- bash 의 `/dev/tcp` 에 3초 감시자를 붙인다. 이미지 계약에 `timeout` 이나 `nc` 는 없고, 라우팅되지 않는 주소는 `/dev/tcp` 만으로
  1분 넘게 붙잡는다.
- 거절 · 시간 초과 · 이름 해석 실패는 모두 통과다. **연결이 맺어질 때만** 실패다.
- 대상 주소는 다른 선언값처럼 작은따옴표로 넣은 데이터다.
- 샌드박스가 풀 수 없는 이름은 늘 통과하므로, AIMON 이 NAT 나 port-forward 뒤에 있으면 운영자가 샌드박스가 보는 주소를 적어야
  한다(OpenSandbox 의 `controlPlaneProbes`, [OpenSandbox §5](workspace-sandbox-opensandbox.md)).
- 탐침은 들어오는 방향(옆 샌드박스 → 나)을 보지 못한다. 그래서 `NETWORK_ISOLATION` 은 여전히 운영자의 선언이 필요하다
  ([보안 §2](workspace-sandbox-security.md)).

**서술자는 seed 가 대조한다.** 서술자는 프로비저닝 없이 프로파일의 선언값으로 정해지므로(§2.3), seed 가 실제 이미지와
대조해(platform 은 `uname -s`, osVersion 은 선언했을 때만 `uname -sr` 에 글롭으로 — `Linux 6.x` 의 `x` 는 `*`) 다르면 슬롯을
FAILED 로 둔다. seed 가 확인할 수 없는 런타임 이름을 프로파일에 쓸 수 없는 이유도 같다([셸 엔진 §2](workspace-sandbox-shell-engine.md)).

**(계획 — 구현 단계 5)** git 으로 받는 seed — `/shared/git/repo.git` bare 저장소와 작업 트리 clone, `sharedAccess: none` 슬롯의
직접 clone — 는 seed 락 아래, 위 검사 뒤에 붙는다. 매번 다른 임시 경로(`.tmp-<uuid>`)에 받은 뒤 `mv -T` 로 올려, 동시 seed 는 한쪽만
성공하고 끊긴 clone 이 완성된 이름으로 남지 않는다. 잔재 정리는 `/workspace` 는 seed 락 안이라 전부, `/shared` 는 다른 슬롯의 seed 가
진행 중일 수 있어 `provisionTimeout` 보다 오래된 것만 지운다. clone 실패는 transient 이고 슬롯은 RUNNING 에 `seeded = false` 로 남아,
backoff 가 지난 뒤의 호출만 seed 를 다시 돈다. 협업 설계는 [파일 시스템 §8](workspace-sandbox-filesystem.md) 에 있다. 그 전까지
`seed` 를 가진 프로파일은 기동 시 거부한다 — 구현되지 않은 경로가 조용히 아무것도 하지 않는 것보다 낫기 때문이다.

## 4. idle 정책

프로파일마다 `terminateAfter`(**필수**)를 두고, 워크스페이스에는 `closeAfter` 가 있다. 값과 기본값은
[설정 §1 · §2](workspace-sandbox-configuration.md) 에 있다.

- **`terminateAfter`** — 슬롯의 `lastActivityAt` 에서 이만큼 지나면 janitor 가 그 슬롯을 TERMINATED 로 두고 샌드박스를
  지운다(§6.1). 다음 사용이 generation 을 올려 새로 만든다. 프로바이더 만료도 이 값으로 건다(§5).
- **`closeAfter`** — RUNNING · PAUSED · PROVISIONING 슬롯이 하나도 없는 채로 이만큼 지나면 워크스페이스를 idle close 한다(§7.2).
  기준을 "모든 슬롯이 TERMINATED" 로 두지 않는다. 재시도하지 않는 FAILED 슬롯 하나가 워크스페이스를 영원히 열어 두기 때문이다.
  같은 이유로 claim 한 노드가 죽어 남은 PROVISIONING 슬롯을 janitor 가 회수한다(§6.1). 그러지 않으면 그 슬롯은 누가 다시 접속할
  때까지 idle close 를 막고 admission 자리를 차지한다.

idle 은 `lastActivityAt` 으로 잰다. 도는 명령이 있는 동안은 heartbeat 가 그 값을 계속 밀어 주므로(§5), `terminateAfter` 보다 긴
명령도 도중에 끝나지 않는다.

**기동 시 거부하는 프로파일**: `terminateAfter` 가 없는 것, `pauseAfter ≥ terminateAfter` 인 것, `terminateAfter` 가 프로바이더의
최대 만료(`ProviderCapabilities.maxExpiry()`)를 넘는 것. `terminateAfter` 가 없으면 프로바이더 만료를 걸 값이 없어 최후 방어선(§5)이
설정에 따라 사라지기 때문이다. 검사 목록 전체는 [설정 §3](workspace-sandbox-configuration.md) 에 있다.

| 예시 프로파일 | terminateAfter | pauseAfter **(계획 — 구현 단계 7)** |
|---|---|---|
| `standard` | 2시간 | 15분 |
| `review` | 30분 | 5분 |
| `experiment` | 20분 | — |

### 4.1 pause (계획 — 구현 단계 7)

프로파일의 `pauseAfter`(선택, `PAUSE_RESUME` 필요)가 지나면 janitor 가 슬롯을 PAUSED 로 두고 샌드박스를 일시 정지한다. 지금은
`pauseAfter` 를 가진 프로파일을 기동 시 거부한다.

- **일시 정지 중에도 만료 시각은 흐른다.** 그래서 PAUSED 슬롯의 최후 방어선도 프로바이더 만료이고, 멈춘 슬롯은 마지막 활동에서
  `terminateAfter` 뒤에 사라진다 — 원하는 동작이다.
- **pause 하기 전에 만료를 민 뒤 멈추고, 멈춘 샌드박스에는 `extendExpiry` 를 부르지 않는다.** OpenSandbox 1.1.x 의 K8s 런타임에서는 멈춘
  샌드박스를 renew 하면 샌드박스가 `Failed` 가 되었다. 1.2.0 소스에서는 이 문제가 고쳐졌다([OpenSandbox §3.1](workspace-sandbox-opensandbox.md))
  (확인 필요: pause 를 구현할 때 이 규칙을 유지할지). 프로바이더는 `Paused` 상태의 `extendExpiry` 를 PERMANENT 에러로 거부한다.
- 멈춘 샌드박스로 간 execd 호출은 실패하지 않고 멈춘다(Docker 런타임, 1.1.x 에서 잼 — 1.2.0 확인 필요). 그래서 모든 execd 호출에
  클라이언트 타임아웃을 둔다([OpenSandbox §4](workspace-sandbox-opensandbox.md)).
- **resume 은 연결의 엔드포인트를 다시 해석한다.** K8s 의 resume 은 새 파드를 띄운다. resume 할 때도 만료를 `now + terminateAfter`
  로 민다.
- resume 이 not found 를 받으면 슬롯을 TERMINATED(`lostAt`)로 두고 generation+1 로 새로 만든 뒤 명령을 실행한다. notice 를 붙이고
  에러로 끝내지 않는다.
- 레코드가 PAUSED 인데 프로바이더가 RUNNING 으로 보고하면 조정이 DRIFT 로 판정해 pause 를 다시 시도한다(§6.2).
- 워크스페이스 단위 pause/resume 은 두지 않는다. idle 은 슬롯마다 다르고, 워크스페이스를 끝내는 경로는 close 하나로 충분하다.
  필요하면 애플리케이션이 슬롯마다 멈춘다.

## 5. 프로바이더 만료와 heartbeat

**프로바이더 만료가 최후 방어선이다.** 슬롯을 만들 때 `expiresAt = now + terminateAfter` 로 걸고, 활동이 있을 때마다
`extendExpiry(ref, now + terminateAfter)` 로 민다. 연장은 `lastActivityAt` 기록과 같은 간격으로 스로틀한다. AIMON 쪽 janitor 가
모두 멈춰도 샌드박스는 마지막 활동에서 `terminateAfter` 뒤에 프로바이더가 회수한다. 만료는 프로바이더가 절대 시각으로
집행하므로 만료 시각을 저장소나 라벨에 따로 두지 않는다. 노드가 재시작해도 만료를 잃지 않는다.

**`extendExpiry` 는 앞으로만 늘리고 상한을 넘지 않는다 — 프로바이더가 지킨다.** 어느 프로바이더든 이 계약을 지켜야 한다
([프로바이더 §1](workspace-sandbox-provider.md)). OpenSandbox 서버의 renew 는 만료를 줄이는 요청도, 서버 상한을 넘는 요청도
받아들이고, 상한을 알려 주는 API 가 없다. 그래서 OpenSandbox 프로바이더가 현재 `expiresAt` 을 읽어 뒤로 가는 요청을 무시하고
선언된 최대 만료로 자르며, `create` 에 늘 `timeout` 을 보낸다(서버 동작의 근거는 [OpenSandbox §3.1 · §4](workspace-sandbox-opensandbox.md)).
읽고 미는 사이가 원자적이지 않으므로 두 노드의 연장이 경합할 수 있다. 둘 다 대략 `now + terminateAfter` 를 쓰므로 잃는 것은 수
초이고, 이것을 받아들인다.

**활동 heartbeat** — 명령(백그라운드 포함)이 도는 동안 그 명령을 가진 노드가 `activityWriteInterval` 마다 `lastActivityAt`
기록과 `extendExpiry` 를 반복한다. 그래서 20분짜리 빌드가 도는 동안 도구 호출이 없어도 janitor 가 `terminateAfter` 로 그 샌드박스를
끝내지 않는다. 도는 명령 수를 세는 카운터를 레코드에 두지 않는다. 노드가 죽으면 카운터가 내려가지 않기 때문이다. heartbeat 는
노드와 함께 멈추고, 그러면 idle 정책이 정상적으로 이어받는다.

- 샌드박스 안 셸 락의 heartbeat 파일은 다른 것이다. 그것은 [셸 §3](workspace-sandbox-shell.md) 이 소유한다.
- 백그라운드 명령은 `backgroundHeartbeatLimit` 이 지나면 heartbeat 를 멈춘다. 그 의미와 이유는 [셸 §7](workspace-sandbox-shell.md)
  에 있다.
- 활동 기록 쓰기는 충돌해도 버리지 않는다. 그 재시도 상한은 [도메인 §4.3](workspace-sandbox-domain.md) 에 있다.
- 기동 검사는 `terminateAfter < 3 × activityWriteInterval` 인 설정을 거부한다. heartbeat 쓰기 한 번이 늦거나 실패해도 도는 명령의
  샌드박스가 만료되지 않게 하기 위해서다([설정 §3](workspace-sandbox-configuration.md)).

**OpenSandbox 의 활동 기반 만료 연장(OSEP-0009)은 heartbeat 의 `extendExpiry` 를 대신하지 못한다.** 샌드박스마다 켜야 하고,
서버 프록시나 K8s ingress 를 거친 요청만 활동으로 세기 때문이다.

**(계획 — 구현 단계 5)** 공유 볼륨에는 프로바이더 만료가 없다. 볼륨의 최후 방어선은 janitor 의 볼륨 조정(§6.3)이고, 노드가 모두
멈추면 볼륨은 다음 기동까지 남는다. 볼륨은 비용이 작고 실행 중인 코드가 없으므로 이것을 받아들인다.

## 6. janitor

`SandboxJanitor` 는 application 수명의 단일 루프다. 주기(`janitorInterval`)마다 idle 집행(§6.1)을 하고, 그 뒤에 샌드박스
조정(§6.2)을 한다. 볼륨 조정(§6.3)은 계획이다. 노드 로컬의 `exec:` 셸 디렉터리 정리는 연결 캐시가 한다([셸 §10.3](workspace-sandbox-shell.md)).

- **janitor 는 자기 스케줄러 스레드에서 돈다.** 주기 작업이 `Error` 를 던져도 다음 주기에 다시 돈다. 한 번의 실패가 idle 집행과
  조정을 영영 멈추지 않게 하기 위해서다. 기동 검사 뒤 어셈블리 조립이 실패하면 그때까지 시작한 스케줄러를 닫는다.
- **항목마다 격리한다.** 한 워크스페이스의 실패는 로그로 남기고 루프는 계속된다. 다음 주기가 다시 읽은 상태에서 재시도한다.
- **모든 노드에서 돌아도 된다. 리더 선출은 두지 않는다.** 모든 조치가 CAS 와 destroy 직전의 재확인으로 보호되고 프로바이더
  호출은 멱등이기 때문이다. 선출 저장소가 하나 더 생기는 비용이 중복 `list` 호출보다 크다.
- **`InMemory` 저장소는 한 노드 전용이다.** 여러 노드가 각자 `InMemory` 저장소로 같은 deployment 를 돌리면 각 janitor 가 다른
  노드의 샌드박스를 레코드 없는 고아로 보고 지운다. 이것은 감지할 수 없는 운영 계약이다([도메인 §4.4](workspace-sandbox-domain.md)).

### 6.1 idle 집행

`store.scan` 으로 다음을 찾아 처리한다.

1. **`terminateAfter` 를 넘긴 RUNNING 슬롯** → TERMINATED, 그다음 destroy. 기록된 프로파일이 설정에서 사라진 슬롯은 설정된 프로파일
   가운데 가장 긴 `terminateAfter` 로 판단한다. 어떤 살아 있는 프로파일보다 먼저 끝내지 않기 위해서이고, 옛 값으로 걸어 둔 프로바이더
   만료가 여전히 최후 방어선이다.
2. **버려진 PROVISIONING claim** — `provisioning.since` 에서 `2 × provisionTimeout` 이 지나도록 아무도 넘겨받지 않은 슬롯. 같은 claim
   이고 워크스페이스가 OPEN 인지 다시 읽어 확인한 뒤 TERMINATED 로 CAS 하고, 죽은 claimer 가 그 키로 만들었을 샌드박스를 destroy
   한다. connect 의 넘겨받기(`provisionTimeout`, §2.4)보다 늦게 잡아, 느리지만 살아 있는 claimer 와 다음 접속이 먼저 처리하게 한다.
3. **`closeAfter` 를 넘긴 OPEN 워크스페이스** → idle close(§7.1, 원인 `IDLE`). 기준 시각은 `max(createdAt, openedAt, 슬롯들의
   lastActiveAt)` 이다. `openedAt` 은 OPEN 으로 들어가는 모든 전이(생성, `reopen`, idle 자동 reopen)가 찍으므로, 다시 연 워크스페이스는
   첫 프로비저닝이 admission 에 거부되더라도 `closeAfter` 를 온전히 받는다. 슬롯의 `lastActiveAt` 은 PROVISIONING · RUNNING · PAUSED 를
   떠나는 모든 전이가 찍으므로, 유일한 슬롯이 실패한 워크스페이스도 일찍 닫히지 않는다([도메인 §6](workspace-sandbox-domain.md)).
4. **`closeResumeAfter` 넘게 CLOSING 에 머문 워크스페이스** → close 절차(§7.1)를 저장된 원인으로 처음부터 이어서 돈다.
5. **기한이 지난 CLOSED 레코드** → `delete(id, version)`(§7.2). `DERIVED` 레코드는 `closedRetention` 뒤, `CLOSED(SESSION_DELETED)` 는 첫
   순회에 지운다. `MANAGED` 레코드는 지우지 않는다 — `purge` 만 지운다(§7.3).
6. **레코드가 없어진 워크스페이스를 가리키는 세션 옵션** → 그 연결(`workspace`)을 지운다. `purge` 의 두 번째 쓰기가 실패한 경우다.
7. **지운 세션의 세션 옵션** → 코어의 세션 저장소가 배선되었을 때만 쓸어 낸다(§7.4).

모든 전이 CAS 는 **방금 다시 읽은 레코드에서 조건을 다시 확인한 뒤** 걸고(그 사이 heartbeat 가 밀었으면 건너뛴다), CAS 가 성공한
뒤에 프로바이더를 호출한다. janitor 의 close 는 가드를 받는 내부 close 로 닫는다. 가드는 새로 읽은 레코드에 상태와 무관하게 적용된다.
idle close 의 가드는 idle 조건이고, 멈춘 close 를 이을 때의 가드는 "스캔한 incarnation 그대로 CLOSING 인가" 다. 그래서 스캔 뒤에 다른
쪽이 close 를 끝내고 다시 연 워크스페이스를 janitor 가 다시 닫지 않는다.

### 6.2 샌드박스 조정

샌드박스 조정은 `InMemory` 저장소가 재시작으로 레코드를 잃은 뒤 남은 샌드박스를 치우는 유일한 장치다. 프로비저닝 경합(§2.4)이나 RUNNING
CAS 를 진 `create`(§2.1)가 남긴 샌드박스도 여기서 회수한다. 조정은 매니저 쪽 일이고 프로바이더와 무관하다 — 프로바이더는 `list` 와
`status` 만 준다.

**순서: `store.scan` 을 먼저(끝까지), `provider.list(managed = true, deployment = 자기 deployment)` 를 나중에.** list 를 먼저 하면 그
사이 다른 노드가 만든 샌드박스가 "레코드는 RUNNING, 목록에는 없음" 으로 보인다. 반대로 scan 은 목록보다 오래된 스냅숏이 되므로,
**샌드박스를 지우는 판정은 모두 destroy 직전에 레코드를 다시 읽어 같은 판정이 나올 때만** 실행한다. 다른 deployment 라벨의
샌드박스는 보지도 건드리지도 않는다([프로바이더 §4](workspace-sandbox-provider.md)). `list` 나 scan 이 실패하면 그 주기의 조정은
거기서 끝난다 — 일부만 본 것에서 아무것도 추론하지 않는다.

판정은 위에서부터 처음 맞는 행 하나만 적용한다. "레코드 generation" 은 슬롯의 현재 generation, "샌드박스 generation" 은 샌드박스
라벨의 값이다.

| 레코드 | 프로바이더 | 판정 | 조치 |
|---|---|---|---|
| 무엇이든 | 라벨에 generation · slot · incarnation 이 없거나 읽을 수 없다 | SKIP | 건드리지 않는다(WARN 한 번) |
| FAILED(같은 generation, 실패 단계 `labels`) | 있음 | SKIP | 건드리지 않는다. 라벨 대조에 실패한 샌드박스는 남의 것일 수 있다. 프로바이더 만료만 지운다 |
| 레코드 없음 · 슬롯 없음 · 다른 incarnation | 있음 | ORPHAN | `orphanGrace` 뒤 destroy |
| 무엇이든 | 샌드박스 generation > 레코드 generation | IN_FLIGHT | 건드리지 않는다. scan 뒤에 다른 노드가 새 generation 을 프로비저닝했다. `orphanGrace` 가 지나도 레코드가 따라오지 않으면 ORPHAN 으로 본다 |
| 무엇이든 | 샌드박스 generation < 레코드 generation | STALE | destroy |
| PROVISIONING(같은 generation) | 있음 또는 없음 | IN_FLIGHT | 건드리지 않는다. `create` 가 끝났지만 RUNNING CAS 전일 수 있다. 인계는 §2.4 |
| FAILED(같은 generation) | 있음 | FAILED_LEFTOVER | destroy. 실패한 `create` 가 일부만 만들어 둔 샌드박스다. 재시도는 generation 을 올리므로 이 샌드박스를 다시 쓰는 쪽은 없다 |
| TERMINATED(같은 generation) | 있음 | ORPHAN | `orphanGrace` 뒤 destroy |
| RUNNING/PAUSED(ref = X, 같은 generation) | 같은 키의 Y ≠ X | DUPLICATE | `orphanGrace` 뒤 Y 를 destroy. 인계 경합이 만든 두 번째 샌드박스다 |
| RUNNING/PAUSED(ref = X, 같은 generation) | X | OK | 없음 |
| PAUSED | RUNNING | DRIFT | pause 재시도 **(계획 — 구현 단계 7)** |
| RUNNING/PAUSED | 목록에 없음, 또는 TERMINATED · FAILED 로 있음 | LOST 의심 | 아래. 그런 샌드박스는 일을 할 수 없으므로 없는 것으로 친다 |

SKIP 의 `labels` 행에는 잔여 위험이 있다. 프로파일이 바뀌어 슬롯이 generation+1 로 가면 라벨 대조에 실패했던 샌드박스는 STALE 로
지워진다. 자기 deployment 의 `managed` 라벨을 단 것만 목록에 오므로 그 위험은 작다.

**grace 는 샌드박스의 나이로 잰다.** 고아에게는 (맞는) 레코드가 없으므로 레코드에 시계를 둘 수 없다. 나이는
`min(now − 샌드박스 createdAt, now − 이 노드가 처음 본 시각)` 이다. 프로바이더가 `createdAt` 을 주지 않으면 뒤쪽만 쓴다. 작은 쪽을
쓰므로 서버와 노드의 시계가 어긋나도 destroy 가 늦어질 수만 있고 빨라지지는 않는다. 대가: janitor 가 재시작하면(처음 본 시각을
잃으면) 고아는 `orphanGrace` 를 한 번 더 기다린다.

**레코드 없는 고아는 순서 불변식이 지킨다.** 다시 읽을 레코드가 없기 때문이다. 레코드는 첫 샌드박스보다 먼저 생기고
(`createIfAbsent`) `orphanGrace > provisionTimeout` 이다(기동 시 검사). 그래서 grace 보다 오래되었고 앞선 scan 에 레코드가 없던
샌드박스는 지금 있는 같은 incarnation 의 레코드에 속할 수 없다. `orphanGrace` 가 `provisionTimeout` 보다 짧으면 레코드 쓰기가 늦은
정상 프로비저닝을 고아로 오인한다.

**destroy 는 기록을 남긴다.** 레코드가 있는 destroy 는 `ORPHAN_DESTROYED`(원인 `orphan` · `stale` · `failed-leftover`) 또는
`DUPLICATE_DESTROYED` 이벤트를 남긴다([관리 §7](workspace-sandbox-management.md)). 레코드 없는 고아는 워크스페이스 라벨의 해시만
INFO 로 남기고 샌드박스 id 는 남기지 않는다.

**LOST 는 시간으로 확정한다.** 목록에 없거나 TERMINATED · FAILED 로 보고된 RUNNING/PAUSED 슬롯은 `status(ref)` 로 다시 묻는다.
없다고 답하면(TERMINATED · FAILED 도 없는 것으로) 슬롯에 `missingSince` 를 적는다(이미 있으면 둔다). 다음에 볼 때 여전히 없고
`missingSince` 에서 `lostConfirmAfter` 가 지났으면 LOST 로 확정한다 — 슬롯 → TERMINATED(`lostAt`), 연결 캐시에서 빼고, `LOST`
이벤트. 다음 사용이 generation+1 로 새로 만든다. 목록이나 status 에 다시 보이면 `missingSince` 를 지운다. 횟수가 아니라 시각으로 세는
이유: janitor 가 모든 노드에서 돌므로 "연속 N 번" 은 한 주기 안에 여러 노드의 관측으로 채워진다. `list` 의 페이지가 밀려 한 번
빠진 샌드박스도 그래서 LOST 가 되지 않는다. 이 쓰기들도 CAS 규칙을 따른다 — 충돌하면 다시 읽고, 슬롯의 generation · `providerRef`
· 상태가 판정 때와 같을 때만 쓴다.

운영자의 샌드박스 목록(`SandboxAdministration.sandboxes()`)은 같은 순서와 같은 판정(유예 전)을 쓰되 아무것도 지우지 않는다
([관리 §4.4](workspace-sandbox-management.md)).

### 6.3 볼륨 조정 (계획 — 구현 단계 5)

`sharedVolumes().list` 로 자기 deployment 접두어의 볼륨([프로바이더 §5](workspace-sandbox-provider.md))을 찾아 레코드와 비교한다.

| 레코드 | 볼륨 | 조치 |
|---|---|---|
| OPEN, 같은 incarnation | 있음 | 둔다 |
| CLOSING · CLOSED, 같은 incarnation, `retainVolumeUntil` 이 미래 | 있음 | 둔다 |
| OPEN, 다른 incarnation, 옛 incarnation 의 `retainVolumeUntil` 이 미래 | 있음 | 둔다. idle 자동 reopen 뒤에도 보존 기한을 지킨다 — 레코드가 옛 incarnation 과 그 기한을 `retainedVolumes` 로 들고 간다 |
| 그 밖(CLOSED 이고 보존 기한이 지남 · 레코드 없음 · 다른 incarnation) | 있음, 마운트한 샌드박스 없음 | `orphanGrace` 뒤 삭제. `VolumeInUseException` 이면 다음 주기에 재시도 — K8s 에서는 파드 종료가 끝나야 PVC 가 풀린다 |

## 7. 워크스페이스 종료

### 7.1 close 절차

`SandboxWorkspaceManager.close(id, caller)` 는 관리 권한을 검사한 뒤([바인딩 §5](workspace-sandbox-binding.md)) 다음 절차를 돈다.
janitor(§6.1)와 세션 삭제(§7.4)는 같은 절차를 가드를 붙여 내부 경로로 부른다.

```
1. CAS: OPEN → CLOSING(stateSince = now, closeCause)      -- 이제 connect 는 사용 불가로 답한다(§2.1)
   이미 CLOSING 이면 저장된 원인을 둔다. CLOSED(IDLE) 에 명시적 close 가 오면 CLOSED(EXPLICIT)(§7.2)
2. TERMINATED 가 아닌 모든 슬롯: CAS TERMINATED → provider.destroy
   그다음 레코드가 가진 모든 providerRef 를 destroy (멱등: 이어받은 close 는 destroy 가 돌지 않은 채
   TERMINATED 인 슬롯을 만날 수 있다)
3. provider.list(workspace = h(id)) 가 빌 때까지 기다린다(최대 closeWait). 그 사이 목록에 남은 것도 destroy 한다
   (close 에 RUNNING CAS 를 진 creator 의 샌드박스처럼 어떤 레코드도 가리키지 않는 것). 넘기면 WARN 후 조정에 맡긴다
4. (계획 — 구현 단계 5) 볼륨: 보존이면 retainVolumeUntil = now + retainFor 를 기록, 아니면 sharedVolumes.delete
   (VolumeInUseException 이면 janitor 에 맡긴다)
5. CAS: CLOSING → CLOSED(stateSince = now, closeCause 유지)
1 다음의 모든 단계는 레코드가 여전히 같은 incarnation 의 CLOSING 일 때만 움직인다
```

**각 단계는 멱등이다.** 도중에 노드가 죽으면 레코드는 CLOSING 에 남고, janitor 가 `closeResumeAfter` 뒤에 처음부터 이어서 돈다.
이어받은 쪽이 느려 그 사이 다른 쪽이 close 를 끝내고 애플리케이션이 reopen 했더라도, 매 단계 CLOSING 과 incarnation 을 다시
확인하므로 새 incarnation 의 샌드박스를 건드리지 않는다.

**close 가 destroy 하는 동안 새 슬롯이 생기지 않는다.** 슬롯 전이 CAS 는 워크스페이스 레코드 전체에 걸리므로, CLOSING 전이 뒤에
다른 노드가 PROVISIONING 으로 올리려는 CAS 는 version 충돌로 지고, 다시 읽으면 CLOSING 을 보고 멈춘다. 넘겨받기 CAS 도 OPEN 을
조건으로 건다(§2.4).

애플리케이션이 작업 종료 시점을 알면 close 를 부른다. 모르면 idle 정책(`closeAfter`)이 결국 닫는다. 없는 id 의 close 는 멱등이라
성공이다 — 툼스톤이 만료된 뒤에도 소유자의 close 가 실패하지 않게 하기 위해서다. 이것이 드러내는 것은 [바인딩 §8](workspace-sandbox-binding.md)
이 정한다.

`reopen(id, caller)` 는 CLOSED 레코드를 OPEN 으로 바꾸고 새 incarnation 을 뽑는다. owner 는 그대로다. 슬롯은 모두 TERMINATED 이므로
다음 사용이 generation 을 올려 새로 만든다. CLOSING 인 레코드의 reopen 은 거부한다 — 첫 읽기와 CAS 사이에 CLOSING 이 된 경우도 CAS
판단 안에서 거부한다. CLOSING 은 일시적이므로 닫힌 뒤에 다시 열라고 답한다.

### 7.2 툼스톤과 idle close

**툼스톤은 connect 를 막는 명시적 close 의 CLOSED 레코드다.** 다른 CLOSED 레코드는 그냥 CLOSED 레코드라 부른다. idle close 의 것은
"막지 않는 CLOSED 레코드" 다.

**명시적 close 는 툼스톤을 남긴다.** 레코드는 `closedRetention` 동안 남아 같은 id 로 들어온 connect 에 "closed; the application has to
reopen it" 으로 답한다. 결정론적 id(`ws:{sessionId}`) 때문에, 닫은 뒤 같은 세션의 다음 턴이 새 워크스페이스를 조용히 만들면
애플리케이션이 끝낸 작업이 빈 환경에서 이어진다. 계속 쓰려면 애플리케이션이 `reopen` 을 부른다.

**`closedRetention` 이 지나면 janitor 가 레코드를 지운다**(`delete(id, version)` — 버전으로 보호하므로 그 사이 들어온 reopen 을 지우지
않는다). 그 뒤의 connect 는 새 incarnation 으로 새 워크스페이스를 만들고 **notice 를 붙이지 않는다.** 레코드가 사라진 id 는 처음 보는
세션과 구별할 근거가 남지 않기 때문이다. 이것은 받아들이는 결정이다 — 툼스톤을 영원히 두면 레코드가 끝없이 쌓인다.
**(계획 — 구현 단계 5)** 볼륨의 `retainFor` 는 `closedRetention` 이하여야 한다(기동 시 검사). 보존 기한이 더 길면 기한을 기억할 레코드가
먼저 사라진다.

**idle close 의 레코드는 connect 를 막지 않는다.** `closeAfter` 로 닫힌 워크스페이스는 애플리케이션이 작업을 끝낸 것이 아니다. 하루
쉬었다 돌아온 사용자의 같은 세션을 "closed" 로 막으면 애플리케이션은 reopen 할 계기도 모른다. 그래서 idle close 는 레코드를
`CLOSED(IDLE)` 로 남기고, 다음 connect 가 그것을 자동으로 reopen(새 incarnation, owner 그대로)하며 초기화 notice 를 붙인다
([notice §1](workspace-sandbox-notices.md)). 레코드를 지워 버리면 다음 connect 가 "초기화" 와 "처음 보는 세션" 을 구별하지 못해 이
notice 를 줄 수 없다. 이 레코드도 `closedRetention` 뒤 janitor 가 지운다.

**명시적 close 는 idle close 를 이긴다.** idle 로 닫혔거나 닫히는 중인 워크스페이스를 애플리케이션이 명시적으로 close 하면 원인이
`EXPLICIT` 으로 바뀌어 툼스톤이 된다(CLOSED 였으면 `stateSince` 를 다시 찍어 보존 기간을 명시적 close 부터 센다). 그러지 않으면 세션을
끝낸 뒤의 다음 턴이 조용히 자동 reopen 된다. 이 전환(원인 `IDLE` → `EXPLICIT`)은 close 의 상태 전환 CAS 한 번 안에서 결정한다. 두
번의 읽기-CAS 로 나누면 그 사이에 끼어든 자동 reopen 때문에 close 가 툼스톤 없이 OPEN 인 채로 끝날 수 있다. 한 CAS 이면 reopen 이
이겼을 때 재시도가 OPEN 을 읽고 그것을 닫으므로 어느 순서든 결과가 정해진다.

**`InMemory` 저장소는 재시작하면 툼스톤도 잃는다.** 단일 노드 기본 구성에서는 재시작 뒤 닫힌 세션의 다음 턴이 새 워크스페이스를
만든다. 툼스톤을 지켜야 하는 애플리케이션은 영속 저장소를 쓴다([도메인 §4.4](workspace-sandbox-domain.md), 계획 — 구현 단계 6).

### 7.3 purge

**`MANAGED` 레코드는 `closedRetention` 으로 지워지지 않는다.** 그 id 는 세션 옵션과 에이전트 정의가 이름 붙이고 있을 수 있다. idle
close 는 그대로 적용된다 — 샌드박스를 없애고 레코드는 `CLOSED(IDLE)`, 다음 사용이 reopen 한다. 영구 삭제는 소유자(또는 운영자)의
명시적 `purge` 뿐이다.

purge 절차:

1. `MANAGED` 레코드만, CLOSED 일 때만 받는다(먼저 close 한다). 관리 권한이 필요하다.
2. 읽은 version 으로 `delete` 한다. 그 사이 reopen 하면 CAS 가 지고 아무것도 바뀌지 않는다.
3. `WORKSPACE_PURGED` 이벤트를 낸다.
4. `SessionOptionsStore.deleteByWorkspace(id)` 로 이 워크스페이스에 붙은 세션 옵션의 연결을 지운다. 이 두 번째 쓰기가 실패하면
   janitor 의 스윕이 마친다(§6.1). 관리형 id 는 다시 쓰지 않으므로 두 쓰기 사이에 다른 워크스페이스가 끼어들 수 없다.

없는 id 의 purge 는 성공이다(응답을 잃은 뒤의 재시도). **`DERIVED` 레코드는 purge 할 수 없다.** 지우면 그 세션의 다음 턴이
`ws:{sid}` 를 조용히 다시 만들기 때문이다. API 의 모양은 [관리 §2.3](workspace-sandbox-management.md) 에 있다.

### 7.4 세션 삭제

세션이 지워지면 그 세션의 `DERIVED` 워크스페이스(`ws:{sessionId}`)와 세션 옵션도 간다. 호스트가 `WorkspaceSandbox.sessionLifecycleListener()`
를 코어 세션에 등록해 이것을 켠다([관리 §1.3](workspace-sandbox-management.md)).

**코어가 주는 것.** 코어의 세션 라우터는 세션을 지울 때마다 `SessionLifecycleListener.onDeleted` 를 한 번 부른다 — 지운 노드에서,
세션의 lease 를 쥔 채로, lease 를 돌려주기 전에. at-most-once 다(노드가 죽으면 잃는다). 없던 id 를 지워도 빈 owner 로 부르고,
`SessionRecordStore` 를 직접 지우면 부르지 않는다([코어 세션 라우팅 §9](https://github.com/kangwoo/aimon-core/blob/main/docs/design/session/routing.md)).

```
onDeleted(sessionId):                                   -- 지운 노드, 세션 lease 안. 짧아야 한다. 권한 검사 없음
  -- 동기: 저장소 읽기와 CAS 쓰기만, 프로바이더 호출 없음
  1. 세션 옵션: find → delete(version), CAS 재시도. 있었으면 SESSION_OPTIONS_CLEARED
  2. ws:{sessionId} 가 DERIVED 로 있으면 CAS:
       OPEN     → CLOSING(stateSince = now, 원인 SESSION_DELETED)
       CLOSING  → CLOSING, 원인만 SESSION_DELETED        -- 진행 중인 idle close
       CLOSED   → CLOSED,  원인만 SESSION_DELETED        -- idle close 의 레코드나 툼스톤
       없음     → 아무것도 하지 않는다
  -- 지연: 2단계에서 읽은 incarnation 과 함께 배경 실행기(§7.5)에 넘긴다
  3. close 절차(§7.1, 가드: 같은 incarnation 의 CLOSING) → 레코드가 그 incarnation 의 CLOSED(SESSION_DELETED) 이면
     delete(id, version), WORKSPACE_PURGED(원인 "session deleted")
  예외를 던지지 않는다. RuntimeException 은 잡아서 WARN 으로 남긴다
```

**표시는 lease 안에서 동기로 한다.** lease 는 "새 세션 X 가 지운 세션 X 의 상태를 보지 않는다" 는 코어의 보증이고, 2단계의 표시
하나가 그것을 지킨다. lease 를 돌려준 뒤 같은 id 로 다시 만든 세션의 connect 는 `SESSION_DELETED` 를 보고 "this session's sandbox
workspace is being removed; retry shortly" 로 답하므로(§2.1 2단계) 옛 샌드박스에 닿지 않는다. 지연 단계도 새 턴 아래의 샌드박스를
끝낼 수 없다 — 그 incarnation 에서 샌드박스를 받은 새 턴이 없기 때문이다. `closeWait` 까지 기다리는 프로바이더 호출은 lease 를 오래
쥐므로 지연 단계로 미룬다. 이 응답이 owner 검사보다 먼저인 이유는 [바인딩 §8](workspace-sandbox-binding.md) 에 있다.

**원인 `SESSION_DELETED` 를 영속한다.** 지연 단계가 돌지 못했을 때 janitor 가 `closedRetention` 없이 마무리하고, 애플리케이션의 close
와 구별하기 위해서다. 명시적 close 로 표시하면 노드가 죽었을 때 `CLOSED(EXPLICIT)` 툼스톤이 남아 다시 만든 세션을 `closedRetention`
동안 막고, 무엇도 그것을 애플리케이션의 close 와 구별하지 못한다.

**파생 워크스페이스를 닫는 이유.** 세션 단위 상태(`ws:{sessionId}`)이고 그 샌드박스는 `closeAfter` 까지 비용이 든다. 또 그 레코드는
지운 세션의 owner 를 들고 있어, id 를 다시 쓰는 새 세션이 툼스톤이나 idle 레코드의 자동 reopen 에서 `closedRetention` 동안 "not
permitted" 로 거부된다. 툼스톤의 이유(같은 세션의 다음 턴)는 세션이 지워지면 없어진다. 그래서 CLOSED 레코드도(idle 이든 명시적이든)
표시한다. 샌드박스가 없으므로 3단계는 레코드만 지운다. 레코드 삭제는 `purge` 의 파생 레코드판이다.

**관리형 워크스페이스는 건드리지 않는다.** 세션이 붙어 있던 관리형 워크스페이스는 공유물이고 자기 owner 가 있다.

**janitor 가 수렴시킨다.** 지연 단계가 돌지 못하면(노드 사망, 배경 실행기 거부, 어셈블리 종료) 영속된 원인이 의도를 들고 있다. janitor
가 멈춘 CLOSING 을 `closeResumeAfter` 뒤 원인을 둔 채 이어서 닫고, `CLOSED(SESSION_DELETED)` 를 첫 순회에 지운다(§6.1). 그때까지 같은
id 로 다시 만든 세션은 "being removed" 를 듣는다 — 길어야 `closeResumeAfter` 에 janitor 주기 한 번을 더한 시간이다.

**두 번 불려도 된다.** 두 번째 호출은 옵션이 이미 없고 레코드가 이미 표시된 것을 본다(CAS 가 같은 원인을 다시 쓰거나 아무것도 찾지
않는다). 표시가 남아 있으면 지연 단계를 다시 제출하는데, 그 단계는 할 일이 없거나 버려진 첫 작업이 남긴 것을 마친다. 효과는 no-op 이다.

**다른 노드는 이벤트가 필요 없다.** 지운 노드만 이벤트를 듣는다. 이 모듈이 세션으로 키를 잡는 상태는 공유 저장소(옵션 레코드, 워크스페이스
레코드)나 샌드박스 안에 있으므로 한 번의 호출로 충분하다. 세션으로 키를 잡는 노드 로컬 상태는 일시적이다 — 연결 캐시의 셸 락(명령이 끝나면
풀린다)과 `exec:` 셸 정리 기록(janitor 가 쓸어 낸다). 노드 로컬 소유자 캐시는 두지 않는다([바인딩 §4](workspace-sandbox-binding.md)).

**잃은 이벤트는 스윕이 맡는다.** 삭제와 리스너 사이의 크래시, `SessionRecordStore` 를 직접 지운 삭제, 리스너를 등록하지 않은 호스트에서는
이벤트가 없다.

- janitor 가 `SessionOptionsStore.sessions(...)` 를 넘기며, 코어 세션 저장소에 없고(`exists` 거짓) `updatedAt` 이 `closedRetention`
  보다 오래된 세션 옵션 레코드를 지운다. 유예는 첫 submit 전에 쓴 옵션(호스트 lookup 이 대체 출처일 때 가능)을 지키기 위해서다. 이 스윕은
  `SessionRecordStore` 가 배선되었을 때만 돈다. 레코드에 무엇이 들었든(전환 기록 · 미전달 초기화만 든 bookkeeping 레코드 포함) 지운다.
- 잃은 이벤트의 파생 워크스페이스는 표시하지 않는다. 이벤트 없이는 세션이 지워졌는지, 아직 만들어지지 않았는지 증명할 수 없기 때문이다.
  그 워크스페이스는 idle 정책(`closeAfter`)과 `closedRetention` 으로 끝난다.
- 라우터 밖에서 세션을 지운 호스트는 운영자의 `deleteSessionOptions` 로 옵션을 지울 수 있다([관리 §4.3](workspace-sandbox-management.md)).

세션 옵션 레코드를 지우면 그 안의 `lastWorkspaceId` 와 미전달 초기화도 함께 간다 — 세션이 없어졌으니 알릴 것이 없다. 전환 기록과
초기화 표시가 지운 세션의 레코드를 되살리지 않는 규칙은 [notice §3 · §5.2](workspace-sandbox-notices.md) 에 있다.

### 7.5 배경 실행기

일회성 작업 두 가지는 heartbeat · janitor 스케줄러와 분리된 배경 실행기에서 돈다.

1. 세션 삭제의 지연 단계(§7.4 3단계)
2. notice 의 확인 쓰기 — drain 뒤의 기록과 첫 connect 의 조용한 전환 기록([notice §3 · §5.2](workspace-sandbox-notices.md))

**스케줄러와 분리한다.** 삭제의 닫기가 `closeWait` 동안 스케줄러 스레드를 쥐면 heartbeat 가 늦어 바쁜 워크스페이스가 idle close 될 수
있기 때문이다. 호스트가 구현하는 공개 SPI 인 `SandboxScheduler` 에는 일회성 실행을 더하지 않는다. 추상 메서드는 기존 구현을 깨고, 기본
구현은 인라인 실행(피하려던 막힘)이 되기 때문이다.

**소유 규칙은 스케줄러와 같다.** 기본은 어셈블리가 소유하는 데몬 스레드 풀이다 — JVM 종료를 막지 않는다. 호스트가
`WorkspaceSandbox.Builder.backgroundExecutor(...)` 로 준 실행기는 빌려 쓰고 종료하지 않는다.

**큐는 유한하고, 받지 못한 작업은 WARN 을 남기고 버린다.** 두 작업 모두 최선 노력이고 뒤를 받치는 것이 있다. 삭제는 janitor 가 마치고,
기록이 빠지면 다음 턴에 notice 가 한 번 반복된다.

**`WorkspaceSandbox.close()` 는 janitor 를 멈춘 뒤 소유 실행기를 닫는다.** 새 작업을 받지 않고, 유한한 시간 동안 남은 작업을 기다린 뒤
끊는다. 이렇게 끊긴 삭제는 `SESSION_DELETED` 표시가 남아 janitor 가 마친다. 호스트 쪽의 닫는 순서는 [관리 §1.5](workspace-sandbox-management.md)
에 있다.

스레드 수, 큐 크기, 종료 대기 시간은 설정으로 드러내지 않는 코드 상수다. 두 작업 모두 최선 노력이라 값이 달라져도 정확성 계약은 그대로다.

## 8. 실패

도구는 예외를 던지지 않고, 이 절의 사용 불가는 모두 도구 에러로 모델에게 간다([개요 §7](workspace-sandbox.md)).

| 상황 | 결과 |
|---|---|
| 프로비저닝 실패(transient) | 슬롯 FAILED(transient). 에러에 프로파일과 원인을 적는다. backoff 뒤 다음 호출이 generation+1 로 재시도(§2.2) |
| 프로비저닝 실패(permanent: platform · OS 버전 불일치, root, 토큰 마운트, 네트워크 미격리, 라벨 불일치, 이미지 계약, `/workspace`, `verify`, 런타임) | 슬롯 FAILED(permanent), 실패한 step 을 에러에 적는다. 프로파일이 바뀌거나 슬롯이 terminate 될 때까지 재시도하지 않는다. 라벨 불일치가 아니면 샌드박스를 지운다(§3) |
| 쿼터 초과 · admission 거부 | 사용 불가 — 어떤 상한이 몇인지 적는다([보안 §8](workspace-sandbox-security.md)) |
| 다른 노드가 프로비저닝 중 | `provisionTimeout` 까지 기다린다. 넘으면 에러, 다음 호출이 넘겨받는다(§2.4) |
| 프로비저닝 · seed 가 오래 걸림 | 명령 타임아웃에 넣지 않는다. `provisionTimeout` 안이면 명령을 실행하고 5초 이상이면 걸린 시간을 notice 로 붙인다 |
| `create` 가 Running 이 되지 않음(Pending 고착, 생성 뒤 Failed) · vault 설정 실패 | `create` 의 transient(또는 상태 코드에 따라 permanent) 실패 → 슬롯 FAILED. 남은 샌드박스는 FAILED_LEFTOVER 로 회수(§6.2) |
| `create` 응답을 잃음 · RUNNING CAS 를 짐 · 인계 경합 | 레코드는 ref 하나만 갖는다. 다른 샌드박스는 조정이 DUPLICATE · ORPHAN 으로 `orphanGrace` 뒤 회수 |
| 명령 도중 샌드박스 소실(명령 실패 또는 `extendExpiry` not found) | 에러 — "the environment will be recreated on the next call and /workspace will be reset". 슬롯은 LOST 경로 |
| 한 호출 안에서 소실이 되풀이됨 | 세 번 다시 만든 뒤 "lost again right after being recreated … retry later"(§2.1) |
| resume 이 not found **(계획 — 구현 단계 7)** | LOST 로 처리하고 generation+1 로 새로 만든 뒤 명령을 실행한다. notice(에러 아님) |
| 프로바이더 호출 뒤 레코드 CAS 실패 | 다시 읽고 상태 기계를 처음부터 돈다. `casRetries` 를 넘으면 "the sandbox workspace is busy" |
| 프로바이더 접속 불가 | 에러. 호스트로 되돌아가지 않는다([보안 §3](workspace-sandbox-security.md)). janitor 는 그 주기를 끝내고 다음 주기에 재시도 |
| 워크스페이스가 CLOSING | 사용 불가 — "the sandbox workspace is being closed; retry shortly". CLOSING 은 일시적이고 reopen 도 거부하기 때문이다 |
| 워크스페이스가 CLOSED(`EXPLICIT`) — 툼스톤 | 사용 불가 — "the sandbox workspace is closed; the application has to reopen it before it can be used again" |
| 워크스페이스가 CLOSING · CLOSED(`SESSION_DELETED`) | 사용 불가 — "this session's sandbox workspace is being removed; retry shortly". owner 검사보다 먼저 답한다(§7.4) |
| idle close 뒤의 같은 id | 자동 reopen 으로 새 incarnation 에서 실행하고 초기화 notice. 그 뒤의 재생성이 실패했을 때 notice 가 가는 곳은 [notice §6](workspace-sandbox-notices.md) |
| `closedRetention` 이 지나 레코드가 지워진 뒤의 같은 id | 새 워크스페이스로 실행한다. notice 없음 — 구별할 레코드가 없다(§7.2) |
| CLOSING 중인 워크스페이스의 `reopen` | 에러 — "still closing; reopen it once it is closed". 첫 읽기 뒤 CAS 사이에 CLOSING 이 되어도 같다 |
| `mustExist` 바인딩의 레코드가 없음(`purge` 뒤, 지운 id 를 이름 붙인 정의) | 사용 불가 — "sandbox workspace <id> does not exist". 레코드를 만들지 않는다 |
| 기록된 프로파일이 설정에서 사라짐 | 사용 불가 — "… which is no longer configured"(§2.1) |
| close 도중 노드 사망 | 레코드는 CLOSING 에 남고 janitor 가 `closeResumeAfter` 뒤 이어서 닫는다 |
| close 가 `closeWait` 안에 끝나지 않음 | WARN 후 CLOSED 로 간다. 남은 샌드박스는 조정이 회수 |
| janitor 조치 실패 | 워크스페이스마다 로그를 남기고 주기는 계속된다. 다음 주기가 다시 읽은 상태에서 재시도 |
| `list` 가 샌드박스를 빠뜨림(페이지 밀림) | `status` 도 `lostConfirmAfter` 동안 빠뜨려야 LOST. id 로 중복을 없앤다 |
| 두 janitor 가 같은 샌드박스에 손댐 | destroy 는 멱등이고 레코드 쓰기는 generation · ref · 상태로 CAS 보호 |
| 멀티 노드에서 `InMemory` 저장소를 쓰는 janitor | 다른 노드의 샌드박스를 ORPHAN 으로 지운다. 감지할 수 없다 — 운영 계약(§6) |
| 세션 삭제 리스너: 옵션 저장소 오류 | WARN. 스윕이 `closedRetention` 뒤 지운다 |
| 세션 삭제 리스너: 동기 표시 실패(저장소 오류, CAS 소진) | WARN. 레코드는 그대로이고 idle 정책과 `closedRetention` 으로 끝난다(잃은 이벤트와 같음). 그 사이 같은 id 로 다시 만든 세션은 옛 레코드에 묶인다 — 삭제 순간의 저장소 장애가 필요한 잔여 경우다 |
| 세션 삭제 리스너: 지연 단계 실패 · 거부 · 노드 사망 | 레코드가 이미 `SESSION_DELETED` 다. janitor 가 CLOSING 을 이어서 닫고 CLOSED 레코드를 첫 순회에 지운다 |
| 세션 삭제 직후 같은 id 재사용 | 지연 단계나 janitor 가 레코드를 지울 때까지 "being removed", 그 뒤 새 `ws:{sid}` |
| 리스너가 없던 id 로 불림 | 찾는 것이 없고 아무것도 하지 않는다 |
| 배경 실행기 큐가 참 | WARN 후 버린다. 삭제는 janitor 가 마치고, 확인 쓰기는 다음 턴에 notice 를 한 번 반복한다 |

## 9. 열린 질문

- **세션을 지울 때 `ws:{sessionId}` 도 닫는가** — 지금은 닫는다(§7.4). "대화 지우기" 를 같은 id 로 지우고 다시 만드는 호스트는 그때마다
  샌드박스를 잃고, 다시 만든 세션의 첫 턴은 옛 샌드박스가 사라질 때까지(보통 최대 `closeWait`, 지연 단계가 버려지면 `closeResumeAfter` 에
  janitor 순회 한 번 더) "being removed" 로 도구가 실패한다. 옵션만 지우고 닫기는 선택으로 둘지(`sessionLifecycleListener(boolean
  closeDerived)`) 정해야 한다.
- **지운 세션의 옵션을 쓸어 내는 유예** — janitor 는 코어의 세션 저장소에 없는 세션의 옵션을 `closedRetention` 이 지난 뒤에 지운다(§7.4).
  첫 submit 전에 쓴 옵션을 지키려는 유예인데, 따로 설정을 둘지 정하지 않았다.
- **지운 세션이 관리형 워크스페이스에 남긴 셸 상태** — `/workspace/.aimon-shell/session-{sid}` 는 지우지 않는다. 지우려면 짧게 끝나야
  하는 리스너 자리에서 connect(프로비저닝)를 해야 한다. 같은 워크스페이스에서 같은 id 로 다시 만든 세션은 그 cwd 와 export 에서 시작한다.
  지연 단계에서 RUNNING 슬롯이 있을 때만 지우는 방법이 있다.
- **비동기 · 멱등 `create`** — 동기 `create` 는 `provisionTimeout` 까지 막는다(§2.3). HTTP 호스트에는 PROVISIONING 을 바로 돌려주는
  경로와, 응답을 잃어도 둘째 워크스페이스를 만들지 않는 멱등 키가 필요할 수 있다. 지금은 `create` 가 늘 새 id 를 만들고, 응답을 잃으면
  호스트가 목록으로 찾는다([관리 §2.2](workspace-sandbox-management.md)).
- **워크스페이스 체크포인트** — 스냅숏은 슬롯 단위다(`SNAPSHOT` capability, 계획 — 구현 단계 8). 슬롯 여럿과 `/shared`(bare 저장소 ref 포함)를 한
  시점으로 묶는 체크포인트가 필요한지는 `SNAPSHOT` 을 확인한 뒤 정한다. 따로 뜬 슬롯 스냅숏을 함께 복원하면 bare 저장소의 ref 와 각 슬롯의
  작업 트리가 서로 다른 시점을 가리킨다.
