# 워크스페이스 샌드박스 — 기존 도구가 OpenSandbox 위의 격리 환경을 투명하게 쓴다

워크스페이스 샌드박스가 무엇을 풀고, 어떤 결정 위에 서 있으며, 각 결정의 상세가 어느 문서에 있는지 보여 주는 진입점이다.
용어 정의, 수명 배치, 모듈 경계, 코어 실행 환경 SPI 대응, 공통 실패 원칙, 구현 상태는 이 문서가 정본이다.

> 관련: [aimon-core 실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md) ·
> [scope-model](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) · [사용자 가이드](../guide/user-guide.md) ·
> 주제 문서 전체는 §9

---

## 1. 무엇을 푸는가

에이전트가 파일 도구로 고친 파일과 셸이 보는 파일이 같아야 하고, 어느 샌드박스에서 돌지·얼마나 살지는 모델이 아니라 플랫폼이
정해야 한다. 셸 상태와 취소가 있어야 긴 개발 작업을 맡길 수 있고, 수명·격리·네트워크·자격 증명은 이미 그것을 가진 인프라에
맡긴다.

### 1.1 목표

- **투명성** — 에이전트는 이미 가진 `Bash` · `Read` · `Write` · `Edit` · `Grep` 을 그대로 쓴다. 샌드박스를 켜면 그 도구들이
  격리 환경에서 돈다. 샌드박스 전용 실행 도구는 없다
- **한 파일 시스템** — 파일 도구와 셸은 반드시 같은 파일 시스템을 본다. `Write Foo.java` 다음의 `./gradlew test` 는 정확히 그
  파일을 컴파일한다
- **플랫폼이 고른다** — 어느 샌드박스에서 돌지는 바인딩 정책이 정한다. 모델은 sandbox id 를 보지도, 지정하지도 못한다
- **1 워크스페이스 : N 샌드박스** — 기본은 워크스페이스 하나에 샌드박스 하나다. 데이터 모델은 처음부터 N 이다. 다중 슬롯
  동작은 **(계획 — 구현 단계 5)**
- **지속되는 셸** — 실행 주체마다 cwd 와 export 된 환경 변수가 이어지는 셸 상태를 준다
- **멀티 노드** — 어느 AIMON 노드에서든 같은 샌드박스에 다시 붙는다. JVM 메모리에는 영속 상태를 두지 않는다.
  **(계획 — 구현 단계 6)**: 영속 저장소가 들어오기 전에는 단일 노드에서만 성립한다(§8)
- **인프라 위임** — 수명·격리·네트워크·자격 증명은 OpenSandbox 가 맡는다. 이 모듈은 AIMON 쪽 개념(워크스페이스·바인딩·수명
  정책)만 소유한다

### 1.2 비목표

- **대화형 개발 환경이 아니다.** 터미널·포트 포워딩·브라우저 미리보기는 다루지 않는다. 호스트가 파일 탐색기를 만들 수 있는
  파일 API 는 제공한다([관리 §6](workspace-sandbox-management.md))
- **PTY/대화형 명령을 지원하지 않는다.** 에이전트의 명령은 입력을 기다리지 않아야 한다(`ShellFeature.INTERACTIVE` 를 광고하지
  않는다)
- **공유 파일의 버전 관리 시스템이 아니다.** 경로 락과 리비전 DB 를 두지 않는다([파일 시스템 §5](workspace-sandbox-filesystem.md))
- **격리의 최종 방어선이 아니다.** 탈출 방어는 런타임(gVisor/Kata)과 OpenSandbox 가 맡고, 이 모듈은 그 설정을 프로파일로 고를
  뿐이다([보안 §2](workspace-sandbox-security.md))
- **Sandbox Gateway 같은 별도 서비스를 두지 않는다.** AIMON 은 애플리케이션에 임베드되는 라이브러리다. 서비스를 두면 홉이 하나
  늘고 인가가 두 곳이 된다. 인가·라우팅은 프로세스 안에서 바인딩 정책과 권한 훅이 맡고, 서비스가 필요한 애플리케이션은 이
  모듈을 자기 서비스 안에 넣는다

---

## 2. 결정 요약

항목마다 결정 한 문장과 정본 문서를 적는다. 이유와 상세는 정본 문서에 있다.

1. **샌드박스는 도구가 아니라 실행 환경이다.** 코어의 파일·셸 도구는 실행마다 `ExecutionEnvironment` 를 `ToolContext` 에서
   꺼내 쓰고, 이 모듈은 그 환경을 끼워 넣는다 — §6
2. **식별은 `(워크스페이스, 슬롯)` 이다.** 무엇을 실행할지는 슬롯에 붙은 프로파일이 정하고, 역할 enum 은 없다 —
   [도메인 §2](workspace-sandbox-domain.md)
3. **워크스페이스는 한 애그리게이트로 영속하고 버전 CAS 로 갱신한다. 셸 상태(cwd·환경 변수)는 레코드가 아니라 샌드박스 안에
   둔다** — [도메인 §4](workspace-sandbox-domain.md), [셸 §1](workspace-sandbox-shell.md)
4. **프로비저닝은 게으르다.** 세션을 열 때가 아니라 첫 파일·셸 호출 때 샌드박스를 만든다. 관리형 워크스페이스의 생성만 즉시
   프로비저닝한다 — [수명 §2.3](workspace-sandbox-lifecycle.md)
5. **`/workspace` 는 샌드박스 전용이다. `/shared` 는 워크스페이스 볼륨이고, `/shared` 를 쓰기로 공유하는 슬롯들은 한 신뢰
   도메인이다** — [파일 시스템 §2](workspace-sandbox-filesystem.md), [바인딩 §6](workspace-sandbox-binding.md). `/shared` 는
   **(계획 — 구현 단계 5)**
6. **슬롯 사이의 코드 협업은 `/shared` 의 bare 저장소를 거친 git 이다 (계획 — 구현 단계 5)** —
   [파일 시스템 §8](workspace-sandbox-filesystem.md)
7. **백엔드는 OpenSandbox 하나다.** 수명·네트워크 정책·자격 증명·pause 를 백엔드마다 다시 구현하지 않기 위해서이고, OpenSandbox
   가 Docker 런타임으로 로컬에서도 돌므로 로컬용 백엔드를 따로 둘 이유도 없다. 프로바이더 SPI 는 남긴다 —
   [프로바이더 §1](workspace-sandbox-provider.md), [OpenSandbox §1](workspace-sandbox-opensandbox.md)
8. **프로바이더 만료가 최후 방어선이다.** AIMON 노드가 전부 죽어도 샌드박스가 영원히 남지 않도록 모든 샌드박스에 만료 시각을
   걸고 활동이 있으면 뒤로 민다. 그래서 `terminateAfter` 는 필수다 — [수명 §5](workspace-sandbox-lifecycle.md)
9. **실패는 닫힌 쪽으로 간다.** 환경을 얻지 못하면 도구가 에러를 돌려주고 호스트 셸로 되돌아가지 않는다. 요구한 격리를
   프로바이더가 광고하지 않으면 시작하지 않는다 — §7, [보안 §3](workspace-sandbox-security.md),
   [프로바이더 §6](workspace-sandbox-provider.md)
10. **멀티 노드는 영속 저장소가 들어와야 성립한다.** 기본 `InMemory` 저장소는 단일 노드 전용이다 —
    [도메인 §4.4](workspace-sandbox-domain.md) **(계획 — 구현 단계 6)**
11. **프로바이더의 멱등은 최선 노력이다.** OpenSandbox 에는 멱등 키가 없으므로 중복 생성이 드물게 생긴다. 레코드가 정본이고,
    janitor 조정이 레코드에 없는 샌드박스를 치운다 — [프로바이더 §4](workspace-sandbox-provider.md),
    [수명 §6.2](workspace-sandbox-lifecycle.md)
12. **기본 워크스페이스 id 는 세션·실행 id 에서 결정론적으로 만든다.** 세션→워크스페이스 매핑 저장소를 두지 않는다 —
    [바인딩 §3](workspace-sandbox-binding.md)
13. **세션 워크스페이스의 owner 는 먼저 부른 쪽이 정하지 않는다.** 코어가 기록한 세션 소유자(없으면 호스트의
    `SessionOwnerLookup`)가 정한다 — [바인딩 §4](workspace-sandbox-binding.md)
14. **명시적 close 는 툼스톤을 남긴다.** 결정론적 id 때문에 끝낸 작업이 빈 환경에서 조용히 이어지지 않게, 툼스톤이 남아 있는
    동안 같은 id 의 connect 를 막는다 — [수명 §7.2](workspace-sandbox-lifecycle.md)
15. **세션 옵션은 워크스페이스 레코드가 아니라 별도 저장소에 둔다** — [도메인 §3](workspace-sandbox-domain.md)
16. **모델에게 아직 전달하지 못한 초기화는 세션 옵션에 영속한다.** 코어가 notice 를 꺼내지 않는 경로(훅·워크플로 단계·
    스테이징)의 초기화도 세션의 다음 루트 실행이 한 번 듣는다 — [notice §5](workspace-sandbox-notices.md)
17. **운영자 경로는 JVM 안의 보안 경계가 아니라 호스트가 따로 노출하는 API 면이다.** 운영자 API 는 권한 검사를 하지 않는다 —
    [바인딩 §5](workspace-sandbox-binding.md), [관리 §4](workspace-sandbox-management.md)

---

## 3. 용어

이 문서 묶음의 용어 정의는 여기 하나다. 규칙과 이유는 오른쪽 열의 정본 문서에 있다.

| 용어 | 뜻 | 정본 |
|------|-----|------|
| **워크스페이스** (`SandboxWorkspace`) | 여러 실행이 한 작업을 위해 공유하는 논리적 작업 공간. 샌드박스들과 공유 볼륨을 소유한다. AIMON 의 에이전트 워크스페이스(`agents/`, `skills/` 디렉터리)와 다른 개념이라 타입 이름에 접두어를 붙였다 | [도메인 §1](workspace-sandbox-domain.md) |
| **파생 워크스페이스 · 관리형 워크스페이스** | 바인딩 정책이 세션·실행 id 에서 파생한 `ws:{…}` 워크스페이스(origin `DERIVED`) / 호스트가 `create` 로 만든 `wsm:{UUID}` 워크스페이스(origin `MANAGED`) | [도메인 §1](workspace-sandbox-domain.md), [관리 §2](workspace-sandbox-management.md) |
| **슬롯** | 워크스페이스 안에서 샌드박스 하나를 가리키는 이름. `^[a-z][a-z0-9-]{0,30}$`, 기본 슬롯은 `primary` | [도메인 §2](workspace-sandbox-domain.md) |
| **generation** | 슬롯의 샌드박스가 새로 만들어질 때마다 1씩 느는 정수. 이전 셸 상태와 파일이 사라졌다는 신호다 | [도메인 §2.1](workspace-sandbox-domain.md) |
| **incarnation** | 워크스페이스 레코드를 만들거나 reopen 할 때마다 새로 뽑는 무작위 id. 샌드박스 키와 볼륨 이름에 들어간다. generation 이 슬롯 층이라면 incarnation 은 워크스페이스 층이다 | [도메인 §1](workspace-sandbox-domain.md) |
| **프로파일** (`SandboxProfile`) | 샌드박스를 어떻게 만들지 정한 운영자 설정 — 이미지·자원·런타임 클래스·egress·자격 증명·idle 정책 | [설정 §1](workspace-sandbox-configuration.md) |
| **프로바이더** (`SandboxProvider`) | 실제 샌드박스를 만드는 인프라의 SPI. 운영 구현은 OpenSandbox 다 | [프로바이더 §1](workspace-sandbox-provider.md) |
| **환경 제공자** (`SandboxExecutionEnvironmentProvider`) | 코어의 `ExecutionEnvironmentProvider` SPI 를 구현한 이 모듈의 타입. 프로바이더와 다르다 | §6.1 |
| **deployment** | 한 워크스페이스 저장소를 공유하는 노드 집합의 이름. 필수 설정이고 기본값이 없다. 라벨과 janitor 조정의 범위를 정한다 | [프로바이더 §4](workspace-sandbox-provider.md) |
| **바인딩** (`SandboxBinding`) | 한 실행이 쓸 `(workspaceId, owner, slot, requiredProfile?, shellKey, root)` 와 그 실행의 주체 `caller`. 바인딩 정책만 만든다 | [바인딩 §1](workspace-sandbox-binding.md) |
| **자격 증명 바인딩** | 프로파일이 이름으로 부르는 vault 쪽 자격 증명 정의(host · 경로 · 메서드 범위). 바인딩(`SandboxBinding`)과 다르다 | [보안 §5](workspace-sandbox-security.md) |
| **런타임 바인딩** | 코어 `bindRuntime(id)` 가 돌려주는 런타임 단위 핸들. 이 모듈은 쓰지 않는다 | §6.4 |
| **owner · caller · actor** | 워크스페이스의 소유자 `(tenantId, TYPE:id)` / 그 실행의 주체 / 수명 이벤트를 일으킨 주체 | [바인딩 §4](workspace-sandbox-binding.md), [관리 §7](workspace-sandbox-management.md) |
| **사용 권한 · 관리 권한** | 사용 = 같은 테넌트이고 접근 모드를 만족함 / 관리 = owner 의 principal 과 같음 | [바인딩 §5](workspace-sandbox-binding.md) |
| **접근 모드** | 사용 권한을 정하는 규칙. `principal`(테넌트와 principal 이 모두 같아야 함) 또는 `tenant`(테넌트만). 배포 기본값과 레코드별 값이 있다 | [바인딩 §5](workspace-sandbox-binding.md) |
| **신뢰 도메인** | 서로의 실행에 영향을 줄 수 있는 실행의 집합. 한 샌드박스를 함께 쓰는 실행, 한 워크스페이스를 공유하는 주체들, `/shared` 를 쓰기로 공유하는 슬롯들이 각각 한 신뢰 도메인이다 | [바인딩 §6](workspace-sandbox-binding.md) |
| **shellKey** | 지속 셸 상태를 가리키는 키. `session:{sessionId}` 또는 `exec:{executionId}`. 같은 키로 들어온 명령들은 cwd·환경 변수를 공유한다 | [바인딩 §3](workspace-sandbox-binding.md), [셸 §1](workspace-sandbox-shell.md) |
| **세션 옵션** (`SessionSandboxOptions`) | 세션을 워크스페이스에 붙이는 세션별 레코드: attachment · unattached 규칙 · `lastWorkspaceId` · `unannouncedReset` | [도메인 §3](workspace-sandbox-domain.md) |
| **bookkeeping 레코드** | attachment 도 unattached 규칙도 없이 `lastWorkspaceId` · `unannouncedReset` 만 담은 세션 옵션 레코드. 바인딩 우선순위에서 "붙음"으로 치지 않고 권한도 주지 않는다 | [도메인 §3](workspace-sandbox-domain.md), [notice §3](workspace-sandbox-notices.md) |
| **요청 속성 · 정의 속성** | 호스트가 실행 하나에 붙이는 `environment.*` 속성 / 에이전트 정의 파일의 `attributes:` 를 평탄화한 속성 | [바인딩 §2](workspace-sandbox-binding.md) |
| **unattached 규칙** | 붙은 워크스페이스도 기본 워크스페이스도 없는 세션을 어떻게 다루는가. `per-session`(세션 워크스페이스를 쓴다) 또는 `reject` | [바인딩 §2](workspace-sandbox-binding.md) |
| **초기화** | idle 뒤의 reopen · 샌드박스 재생성 · 소실로 `/workspace` 와 셸 상태가 사라지는 것 | [notice §1](workspace-sandbox-notices.md) |
| **미전달 초기화** | 세션 샌드박스의 초기화 가운데 아직 어떤 drain 도 모델에게 전달하지 않은 것 `(workspace, slot, at)` | [notice §5](workspace-sandbox-notices.md) |
| **환경 notice · 명령 notice** | 코어가 `drainNotices()` 로 꺼내 다음 도구 결과에 싣는 실행 환경 수준의 사실 / `ShellCommandResult.notices()` 에 실리는 그 명령에 대한 사실 | [notice §1](workspace-sandbox-notices.md) |
| **툼스톤** | 명시적 close 가 남긴 CLOSED 레코드. `closedRetention` 동안 같은 id 의 connect 를 막는다. idle close 가 남긴 것은 툼스톤이 아니라 "막지 않는 CLOSED 레코드" 다 | [수명 §7.2](workspace-sandbox-lifecycle.md) |
| **조정** | janitor 가 워크스페이스 레코드와 프로바이더의 샌드박스 목록을 대조해 판정하고 조치하는 일 | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| **판정** (`SandboxVerdict`) | 조정이 샌드박스 하나에 내리는 분류: `SKIP` · `OK` · `IN_FLIGHT` · `ORPHAN` · `STALE` · `FAILED_LEFTOVER` · `DUPLICATE`. 레코드 쪽에서 샌드박스가 사라진 슬롯은 `LOST` 로 확인한다 | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| **seed** | 샌드박스 generation 마다 한 번 도는 검사·초기화 — 이미지 계약, 선언값 대조, 제어면 탐침, `/workspace` 배치 | [수명 §3](workspace-sandbox-lifecycle.md) |
| **이미지 계약** | 샌드박스 이미지가 갖춰야 할 것 — bash, GNU coreutils, flock, git, rg, uid 1000 사용자, 그 사용자 소유의 `/workspace` | [설정 §4](workspace-sandbox-configuration.md) |
| **제어면 탐침 · 엔진 탐침** | seed 가 샌드박스에서 제어면 주소로 연결을 시도해 닿지 않음을 확인하는 검사 / 코어 셸 엔진이 환경마다 `python3` 가용성을 확인하는 검사 | [수명 §3](workspace-sandbox-lifecycle.md), [셸 엔진 §1](workspace-sandbox-shell-engine.md) |
| **래퍼** | 모델 명령을 감싸는 두 bash. outer 는 락·워치독·트레일러를, inner 는 셸 상태 복원·명령·저장을 맡는다 | [셸 §2](workspace-sandbox-shell.md) |
| **트레일러** | 래퍼가 stderr 끝에 찍는 nonce 붙은 결과 줄. 모델 명령은 `exit out err cwd`, stream 실행은 `exit cwd` 형식이다 | [셸 §4](workspace-sandbox-shell.md) |
| **포그라운드** | 백그라운드·훅·격리가 모두 아닌 실행 | [셸 §5](workspace-sandbox-shell.md) |
| **stream 실행 · 스트림 래퍼** | 출력이 run 파일이 아니라 exec 자신의 스트림으로 오는 실행 — 훅 · 격리 · 스트리밍 / 그 실행을 감싸는 래퍼 | [셸 §5](workspace-sandbox-shell.md) |
| **run 파일** | 모델 명령의 실행별 파일 `run-{id}.cmd` · `.out` · `.err` · `.in` · `.timedout` · `.cwd`. 셸 디렉터리 안에 있다 | [셸 §2](workspace-sandbox-shell.md) |
| **실행 디렉터리** | 코어가 바이트로 넘기는 훅 파일 묶음(`ShellFeature.RUN_DIRECTORY`). run 파일과 다르다 | [셸 §9](workspace-sandbox-shell.md) |
| **비공개 디렉터리** | stream 실행마다 만드는 0700 디렉터리 `.aimon-shell/.run.{32 hex}`. 같은 uid 에게는 열려 있으므로 보안 경계가 아니다 | [셸 §9](workspace-sandbox-shell.md) |
| **셸 락 대기** (`shellLockWait`) | 같은 shellKey 의 앞 명령이 끝나기를 기다리는 시간. 기본 10초 | [셸 §3](workspace-sandbox-shell.md) |
| **활동 heartbeat · 락 heartbeat 파일** | 도는 명령의 노드가 주기적으로 하는 활동 기록과 프로바이더 만료 연장 / 샌드박스 안 셸 락 옆에 래퍼가 갱신하는 파일 | [수명 §5](workspace-sandbox-lifecycle.md), [셸 §3](workspace-sandbox-shell.md) |
| **백그라운드 상한 · heartbeat 상한** | 백그라운드 명령이 돌 수 있는 상한 `backgroundCommandTimeout`(기본 24h) / 백그라운드 명령이 샌드박스를 깨워 두는 상한 `backgroundHeartbeatLimit`(기본 1h). 둘은 독립이다 | [셸 §7](workspace-sandbox-shell.md) |
| **캡처 상한 · 대기 최후 방어선** | `SandboxShell` 이 프로바이더에 거는 출력 상한 `3 × max + 1 KiB` / 프로바이더 `await` 의 타임아웃 `timeout + 5s` | [셸 §8](workspace-sandbox-shell.md) |
| **읽기 전용 영역** | 쓰기를 거부하는 경로. 에이전트 파일 도구와 사람용 파일 API 의 범위가 다르므로 각 API 문서가 정의한다 | [파일 시스템 §3](workspace-sandbox-filesystem.md), [관리 §6.4](workspace-sandbox-management.md) |
| **셸 엔진** | 코어의 Python `ExecuteCode` 엔진(PTC, Programmatic Tool Calling). 이 모듈은 그것이 샌드박스에서 돌 수 있게 한다 | [셸 엔진 §1](workspace-sandbox-shell-engine.md) |
| **런타임 파일 시스템** (`RuntimeFileSystem`) | 셸 엔진의 응답 파일을 다루는 프레임워크 전용 파일 접근. 모델 도구에는 주지 않는다 | [셸 엔진 §3](workspace-sandbox-shell-engine.md) |
| **kill 원인** | 프로바이더가 스스로 끝낸 명령의 원인(`ExecKillCause`, 코어 쪽 `ShellKillCause`) | [셸 엔진 §5](workspace-sandbox-shell-engine.md) |
| **수명 층** | scope-model 의 네 층(Application · Agent · Session · Execution)과 이 모듈이 더하는 Workspace 층 | §4 |
| **구현 단계** | 이 설계를 나눠 구현하는 순서. 문서에는 아직 구현되지 않은 단계 5–8 만 `(계획 — 구현 단계 N)` 표지로 나온다 | §8 |
| **기본 계층 · docker 계층 · k8s 계층** | 테스트 계층. 실서버 없이 도는 것 / Docker 런타임 OpenSandbox 서버에 대고 도는 것 / Kubernetes 위 OpenSandbox 에 대고 도는 것 | [테스트 §3](workspace-sandbox-testing.md) |

---

## 4. 수명 배치

[scope-model](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) 의 네 수명 층에 다음처럼
놓인다. 수명은 이름이 아니라 **키와 저장 위치**로 판단한다.

| 대상 | 수명 | 키 / 저장 위치 | 닫는 곳 |
|------|------|---------------|--------|
| `SandboxProvider` · `SandboxWorkspaceStore` · `SessionOptionsStore` · `SandboxWorkspaceManager` · `SandboxProfileRegistry` · `SandboxBindingPolicy` · `SandboxTenantResolver` · `SessionOwnerLookup` · `SandboxAdmission` · `SandboxEventListener` · `SandboxJanitor` · `SandboxExecutionEnvironmentProvider` | Application | 어셈블리 싱글턴 — `WorkspaceSandbox` 가 만들고 검사하고 닫는다(§5.2) | 앱 shutdown |
| 오케스트레이터 도구 **(계획 — 구현 단계 5)** | Agent | `ToolRegistry` | 상태 없음 |
| 워크스페이스 레코드, 원격 샌드박스, 공유 볼륨 | **Workspace** (영속) | `SandboxWorkspaceId` / `SandboxWorkspaceStore` | 명시적 close 또는 idle 만료([수명 §7](workspace-sandbox-lifecycle.md)). CLOSED 레코드는 `closedRetention` 동안 남는다 |
| 셸 상태 파일(cwd·환경 변수) | 샌드박스 generation | 샌드박스 안 `/workspace/.aimon-shell/` | generation 과 함께 사라진다. `exec:` 키의 파일은 `execShellIdle` 뒤 지운다([셸 §1](workspace-sandbox-shell.md)) |
| `SandboxBinding` · `SandboxExecutionEnvironment` | Execution | `ToolContext` | 실행과 함께 버린다. 원격 자원을 쥐지 않는다 — 코어 계약상 환경은 닫을 것이 없는 뷰다 |
| 프로바이더 연결 캐시 · shellKey 직렬화 락 | 노드 로컬 | `SandboxConnectionCache` | 앱 shutdown |

Workspace 층은 네 수명 층 사이에 새로 끼우는 층이 아니다. **Session 과 같은 층의 영속 애그리게이트이되 키가 다르다.** 한
워크스페이스는 여러 세션과 세션 없는 실행(서브에이전트 포크, 스케줄 루틴)에 걸칠 수 있다. 그래서 다음이 성립한다.

- `LiveSession.close()` 는 샌드박스를 건드리지 않는다. 같은 워크스페이스를 다른 노드의 다른 세션이 쓰고 있을 수 있다
- `AgentRuntime.close()` 도 건드리지 않는다. 런타임은 여러 세션·테넌트의 실행을 받는다. 같은 이유로 워크스페이스를 런타임
  단위로 두지 않는다 — 테넌트 경계가 무너진다
- 샌드박스를 끝내는 경로와 공유 볼륨을 지우는 경로는 닫힌 목록이다([수명 §1](workspace-sandbox-lifecycle.md))

---

## 5. 모듈과 어셈블리

### 5.1 모듈과 의존 방향

```
aimon-core (repo: aimon-core)
  at.aimon.core.environment        ExecutionEnvironment SPI (§6)
  tools.file / tools.bash          resolve the environment from ToolContext per execution
        ▲
        │ api
┌───────┴───────────────────────────────────────────────────────────────┐
│ aimon-sandbox                                                          │
│   (root)       WorkspaceSandbox (assembly) · SandboxSettings           │
│                SandboxWorkspaces · SandboxAdministration ·             │
│                SandboxWorkspaceFiles                                   │
│   workspace/   SandboxWorkspace · SandboxWorkspaceStore(I) · InMemory  │
│                SessionOptionsStore(I) · SandboxWorkspaceManager ·      │
│                SandboxJanitor · SandboxAdmission(I) ·                  │
│                SandboxEventListener(I)                                 │
│   binding/     SandboxBindingPolicy(I) · DefaultSandboxBindingPolicy   │
│                SandboxTenantResolver(I) · SessionOwnerLookup(I)        │
│   provider/    SandboxProvider(I) · SandboxConnection(I) · specs ·     │
│                SharedVolumes(I) · SandboxConnectionCache               │
│   profile/     SandboxProfile · SandboxProfileRegistry                 │
│   environment/ SandboxExecutionEnvironmentProvider ·                   │
│                SandboxExecutionEnvironment · SandboxFileSystem ·       │
│                SandboxShell · SandboxRuntimeFileSystem                 │
│   (planned)    SandboxWorktrees (step 7) ·                             │
│                OrcaSandboxToolProvider (step 5)                        │
└───────────────────────────────────────────────────────────────────────┘
        ▲ api                     ▲ api                     ▲ api
┌───────┴───────────────────┐ ┌───┴───────────────────┐ ┌───┴──────────────────────────┐
│ aimon-sandbox-opensandbox │ │ aimon-sandbox-        │ │ aimon-sandbox-testkit        │
│   OpenSandboxProvider     │ │   store-jdbc          │ │   contract suites (provider, │
│   OpenSandboxProvider-    │ │   (planned, step 6)   │ │     workspace store, session │
│     Config                │ │                       │ │     options store, runtime   │
│   CredentialDefinition    │ │                       │ │     file system)             │
│   VolumeReclaimer(I)      │ │                       │ │   LocalProcessSandbox-       │
│   -> REST (JDK HttpClient │ │                       │ │     Provider (tests only)    │
│      + Jackson), no SDK   │ │                       │ │   FaultInjecting… · Manual…  │
└───────────────────────────┘ └───────────────────────┘ └──────────────────────────────┘
```

그림의 `(planned)` 와 `aimon-sandbox-store-jdbc` 는 아직 없다(§8).

- `aimon-sandbox` 는 코어를 `api` 로 의존한다. 공개 타입이 코어 SPI(`ExecutionEnvironmentProvider`, `VirtualFileSystem`,
  `VirtualShell`)를 구현하고 코어 타입(`Principal`, `SessionId`)을 인자로 노출하기 때문이다
- **의존은 한 방향이다.** 코어는 샌드박스를 모르고, `aimon-sandbox` 는 OpenSandbox 를 모른다. 매니저는 프로바이더의
  `ProviderCapabilities` 만 본다
- `aimon-sandbox-opensandbox` 의 공개 표면은 `OpenSandboxProvider` · `OpenSandboxProviderConfig` · `CredentialDefinition` ·
  `VolumeReclaimer` 뿐이다. HTTP 를 말하는 나머지(수명 클라이언트, execd 클라이언트, SSE 파서, vault 클라이언트, 에러 분류,
  재시도)는 패키지 안에 둔다. OpenSandbox 설정도 이 모듈이 소유한다 — `SandboxSettings` 에 OpenSandbox 키를 두지 않는다
  ([설정 §2](workspace-sandbox-configuration.md)). SDK 대신 REST 를 직접 부르는 이유는 [OpenSandbox §1](workspace-sandbox-opensandbox.md)
- `aimon-sandbox-testkit` 은 발행한다. 이 저장소 밖의 프로바이더와 저장소 구현도 계약 스위트를 통과해야 하기 때문이다
  ([테스트 §1](workspace-sandbox-testing.md)). 계약 스위트는 JUnit 5 추상 클래스이므로 testkit 은 JUnit·AssertJ 를 `api` 로
  싣는다

### 5.2 어셈블리 `WorkspaceSandbox`

어셈블리는 루트 패키지의 `WorkspaceSandbox` 다. §4 의 애플리케이션 싱글턴은 만드는 순서와 닫는 의무가 있고, 기동 검사는 그
전부를 한 번에 봐야 한다. 이 모듈은 DI 컨테이너를 가정하지 않으므로 `WorkspaceSandbox.builder()` 가 설정과 프로바이더를 받아
검사하고([설정 §3](workspace-sandbox-configuration.md)), 싱글턴을 만들고, `close()` 로 닫는다.

`close()` 는 샌드박스를 닫지 않는다. 샌드박스는 워크스페이스 수명이기 때문이다(§4). 배선 절차 — `sessionRecords`, 세션 수명
리스너, `ExecutionEnvironmentSpec.shared`, 코어 스택과의 닫는 순서 — 는 [관리 §1](workspace-sandbox-management.md) 이 정본이다.

### 5.3 호출 경로

```
LLM tool call: Bash {"command": "./gradlew test"}
  │
  ▼
BashTool.execute(input, ctx)
  │  env = ctx.get(EXECUTION_ENVIRONMENT)          <- resolved once per execution by
  │                                                   SandboxExecutionEnvironmentProvider
  ▼
SandboxShell.execute(cmd, options)
  │  binding = (ws:S1, primary, shellKey=session:S1, root=/workspace)
  ▼
SandboxWorkspaceManager.connect(binding)           <- owner check, then lazy provisioning if needed
  │  CAS on the workspace record
  ▼
SandboxConnection.run(ExecSpec)                    <- node-local connection cache; ExecSpec wraps the
  │                                                   command with the shellKey's state file
  ▼
OpenSandboxProvider  ──>  OpenSandbox API (/command)  ──>  execd in sandbox  ──>  bash
```

바인딩은 [바인딩 §1](workspace-sandbox-binding.md), connect 는 [수명 §2](workspace-sandbox-lifecycle.md), 래퍼는
[셸 §2](workspace-sandbox-shell.md), `/command` 호출은 [OpenSandbox §3.2](workspace-sandbox-opensandbox.md) 에 있다.

---

## 6. 코어 통합

코어 쪽 계약은 aimon-core 의 [실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)
가 정본이다. 이 모듈이 기대는 코어의 성질은 셋이다.

- 코어의 파일 도구와 `Bash` 는 생성자로 파일 시스템·셸을 받지 않는다. 실행마다 `ToolContextKeys.EXECUTION_ENVIRONMENT` 에서
  `ExecutionEnvironment` 를 꺼낸다
- 실행기는 실행마다 `ExecutionEnvironmentProvider.resolve(EnvironmentRequest)` 를 한 번 불러 그 키에 넣는다. 이 키는 한 번만
  쓸 수 있으므로 enricher 가 덮어쓸 수 없다. 실패하면 호출마다 에러를 내는 `UnavailableExecutionEnvironment` 가 들어간다
- 에이전트·스킬 정의, 태스크 출력, artifact 보관은 `controlFileSystem` 으로 분리되어 파일 도구에서 보이지 않는다

환경을 실행 컨텍스트로 넘기므로 도구 레지스트리를 실행마다 복제하지 않는다. 실행마다 도구 N개를 만들고 artifact 를 아는 분기를
반복하는 방식은 코어 실행 환경 설계 §1.2 가 기각했다.

### 6.1 실행 환경 SPI 대응

이 모듈은 `SandboxExecutionEnvironmentProvider`(환경 제공자)로 코어 SPI 를 구현한다. 행마다 요지와 정본을 적는다.

| 코어 SPI | 샌드박스 구현 | 정본 |
|---------|-------------|------|
| `resolve(request)` | 바인딩 정책으로 `SandboxBinding` 을 만들고 `SandboxExecutionEnvironment` 를 돌려준다. 원격 자원은 만들지 않는다 | [바인딩 §1](workspace-sandbox-binding.md), [수명 §2.3](workspace-sandbox-lifecycle.md) |
| `fileSystem()` | 프로바이더 `files()` 위의 VFS. 상대 경로는 바인딩의 `root` 기준 | [파일 시스템 §3](workspace-sandbox-filesystem.md) |
| `shell()` | shellKey 의 셸 상태를 이어 붙인 `SandboxShell` | [셸 §1](workspace-sandbox-shell.md) |
| `descriptor()` | 프로파일에 선언한 값과 notes | §6.2 |
| `descriptor().isolation()` · `runtimes()` | 늘 `SANDBOXED`. 런타임은 프로파일의 `runtimes` 선언 | §6.2, [셸 엔진 §1](workspace-sandbox-shell-engine.md) |
| `runtimeFileSystem()` | 셸 엔진 응답 파일용 프레임워크 전용 파일 접근. `/workspace/**` 로 가둔다 | [셸 엔진 §3](workspace-sandbox-shell-engine.md) |
| `contentSearch()` | 샌드박스 안에서 `rg --json` 한 번. 이미지 계약이 ripgrep 을 요구하는 이유다 | [파일 시스템 §3](workspace-sandbox-filesystem.md), [설정 §4](workspace-sandbox-configuration.md) |
| `durable()` | `false` — 코어가 artifact 를 제어 저장소로 복사한다 | [파일 시스템 §6](workspace-sandbox-filesystem.md) |
| `stage(resource)` | `/workspace/.aimon-staged/{name}/{contentKey}/` 로 materialize. 스테이징 영역은 파일 도구에 읽기 전용 | [파일 시스템 §4](workspace-sandbox-filesystem.md) |
| `isolate(branchKey)` | 늘 비어 있음을 돌려주고, 코어는 워크플로 격리 단계를 거부한다. 샌드박스 worktree 는 **(계획 — 구현 단계 7)** | [파일 시스템 §7](workspace-sandbox-filesystem.md) |
| `fork()` 가 있는 요청(포크) | 부모와 같은 워크스페이스, shellKey 는 `exec:{executionId}`. 부모 환경이 없거나 샌드박스 환경이 아니거나 사용 불가면 포크도 사용 불가 | [바인딩 §3](workspace-sandbox-binding.md) |
| `drainNotices()` | 환경 인스턴스마다 하나인 큐의 환경 notice. 코어가 모든 도구 결과 머리에 `[environment]` 줄로 한 번 싣는다 | [notice §1](workspace-sandbox-notices.md), [notice §2](workspace-sandbox-notices.md) |
| `ShellCommandResult.notices()` | 그 명령에 대한 사실만(명령 notice). connect 실패 때의 notice 는 큐로 간다 | [notice §1](workspace-sandbox-notices.md), [notice §6](workspace-sandbox-notices.md) |
| 취소 — `ShellFeature.CANCELLATION` | 신호가 걸리면 그 exec 의 프로세스 그룹을 끝내고 `ShellCancelledException` 을 던진다. 코어 `KillShell` 이 샌드박스의 백그라운드 명령을 끝낼 수 있는 이유다 | [셸 §6](workspace-sandbox-shell.md) |
| `backgroundCommandTimeout()` | 선언한 프로파일의 값을 돌려준다. 늘 값이 있고 기본은 24시간 | [셸 §7](workspace-sandbox-shell.md), [설정 §1](workspace-sandbox-configuration.md) |
| `bindRuntime(id)` | 재정의하지 않는다 | §6.4 |

### 6.2 서술자

`descriptor()` 는 프로파일에 **선언한** `platform` · `osVersion` · `shellName` 과 `workingDirectory = root` 를 담는다. notes 에는
네 가지를 싣는다 — 격리된 샌드박스이고 어느 프로파일인지, egress 요약(전부 차단 / 허용 목록 / egress 정책 없음), 파일 도구가
상대 경로를 `root` 기준으로 푼다는 사실(절대 경로를 권한다), 셸 상태는 cwd 와 export 된 변수만 잇는다는 사실.

선언값을 쓴다. `resolve()` 는 원격 자원을 만들지 않으므로 이미지에 물어볼 수 없기 때문이다. 대신 seed 가 실제 이미지와
대조해(platform 은 `uname -s`, osVersion 은 선언했을 때만 `uname -sr` 에 글롭으로) 다르면 슬롯을 영구 실패로 둔다
([수명 §3](workspace-sandbox-lifecycle.md)). 선언값이라 재생성을 넘어 같은 값이고, 프롬프트에 렌더되는 서술자가 흔들리지 않아
프롬프트 캐시를 지킨다.

`isolation()` 은 늘 `SANDBOXED` 다. 이 환경의 셸과 파일은 언제나 샌드박스 안에 있다. `runtimes()` 는 프로파일의 `runtimes`
선언이고 seed 가 확인한다. `python3` 이 있으면 코어가 셸 엔진의 `ExecuteCode` 를 이 환경에 낼 수 있다
([셸 엔진 §1](workspace-sandbox-shell-engine.md)). 사용 불가 환경의 서술자는 코어 것이라 isolation 이 없다. 서술자에 실을
선언 필드는 [설정 §1](workspace-sandbox-configuration.md) 에 있다.

### 6.3 코어 버전

**aimon-core 0.3.4 이상이 필요하다.** `aimon-sandbox` 는 코어를 `api` 로 노출하므로 이 모듈을 올리면 코어도 함께 올라간다. 이
모듈이 쓰는 코어 기능은 다음과 같다.

- 실행 환경 SPI 와 `ToolContextKeys.EXECUTION_ENVIRONMENT`, 셸 취소(`ShellFeature.CANCELLATION`, `KillShell`), 백그라운드
  상한(`backgroundCommandTimeout()`), 런타임 바인딩(`bindRuntime`)
- 경로 규칙 래퍼의 공개 팩토리 `VirtualFileSystems.withPathRules`([파일 시스템 §3](workspace-sandbox-filesystem.md))
- notice 를 싣는 백그라운드 `Bash`
- 포크 요청의 `EnvironmentRequest.fork()`(`ForkDefinition` — 서브에이전트 이름과 속성)와 `definitionAttributes()`, 워크플로
  단계가 싣는 정의 속성([바인딩 §2](workspace-sandbox-binding.md))
- 훅 표시 `ExecutionOptions.isHook()`, 격리 실행 `ExecutionOptions.isIsolated()` 와 `ShellFeature.ISOLATED_EXECUTION`, 실행
  디렉터리 `ShellFeature.RUN_DIRECTORY`([셸 §5](workspace-sandbox-shell.md), [셸 §9](workspace-sandbox-shell.md))
- 요청 속성 `EnvironmentRequest.requestAttributes()`([바인딩 §2](workspace-sandbox-binding.md))
- 환경 notice `ExecutionEnvironment.drainNotices()`([notice §1](workspace-sandbox-notices.md))
- 세션 소유자 `SessionRecordView.getOwner()` · `SessionRecordStore#loadOwner` 와 세션 삭제 이벤트
  `SessionLifecycleListener.onDeleted`([바인딩 §4](workspace-sandbox-binding.md), [수명 §7.4](workspace-sandbox-lifecycle.md))
- 스트리밍 `ShellFeature.STREAMING`([셸 §5](workspace-sandbox-shell.md))
- 셸 엔진의 샌드박스 SPI — `RuntimeFileSystem`, 서술자의 `isolation()` · `runtimes()`, kill 원인
  ([셸 엔진](workspace-sandbox-shell-engine.md))

부모 환경 없이 도는 코어 워크플로 러너에는 이 모듈이 사용 불가 환경으로 답한다. 그것으로 충분하다
([바인딩 §3](workspace-sandbox-binding.md)).

### 6.4 코어에 맡기는 것

**백그라운드 작업 목록.** 코어의 `BackgroundBashManager` · `BackgroundBashStore` 를 그대로 쓰고, 이 모듈은 그 저장소를
구현하지 않는다. 백그라운드 명령의 실행과 상한은 [셸 §7](workspace-sandbox-shell.md) 에 있다.

**파일 stamp 검사.** 동시 수정은 코어의 stamp 검사(읽은 뒤 바뀐 파일에 쓰기 거부)에 맡긴다. 리비전 번호로 막지 않는다. 리비전은
파일 도구 경로만 추적해 셸이 바꾼 파일을 놓치기 때문이다. stamp 검사는 실제 파일 상태와 비교하므로 두 경로를 모두 잡는다.
이 모듈이 지킬 것(프로바이더 `stat` 의 변경 감지 계약)과 한계는 [파일 시스템 §5](workspace-sandbox-filesystem.md) 에 있다.

**런타임 바인딩.** `bindRuntime(id)` 를 재정의하지 않고 코어 기본값 `RuntimeBinding.NONE` 을 쓴다. 이 제공자는 런타임 단위로
쥐는 것이 없기 때문이다 — 워크스페이스는 세션·실행 id 로, 연결은 샌드박스로, 셸 락은 샌드박스와 shellKey 로 잡힌다(§4). 그래서
코어 계약의 세 조항이 구성상 성립한다: 핸들을 닫아도 도는 명령이 멈추지 않고, 같은 id 의 다른 바인딩이 쓰는 것을 놓지 않으며,
바인딩하지 않은 id 의 `resolve` 도 답한다. 나중에 런타임 id 로 무언가를 쥐게 되면 그때 `bindRuntime` 을 구현하고, 그 `close()`
는 도는 명령을 건드리지 않는다. 코어 스택과 이 어셈블리를 닫는 순서는 [관리 §1](workspace-sandbox-management.md) 에 있다.

---

## 7. 실패 원칙

**도구는 예외를 던지지 않는다**(코어 규칙). 환경 계층의 실패는 VFS 예외와 `ShellExecutionException` 계열로 올라오고, 도구가
그것을 `ToolResult.error` 로 바꾼다. 이 모듈의 사용 불가 예외(`SandboxUnavailableException`, `BindingRejectedException`)는 코어의
`ExecutionEnvironmentUnavailableException` 을 상속하고, 스테이징 실패는 코어의 `StagingException` 이다. 코어가 이 타입들을
안다는 것이 계약이다.

**프로바이더 실패는 코어 도구에 날것으로 가지 않는다.** 파일 시스템과 셸 모두, 사라진 샌드박스는 소실로 처리해 "다음 호출에서
환경이 다시 만들어지며 `/workspace` 가 초기화된다" 는 결과로 바꾸고, 그 밖의 프로바이더 실패는 `SandboxUnavailableException` 으로
바꾼다. 상황별 결과는 각 주제 문서의 "실패" 절에 있다.

**닫힌 실패.** 샌드박스 모드에서 환경을 얻지 못하면 도구가 에러를 돌려준다. 프로바이더에 접속할 수 없을 때도 같다. 호스트
셸이나 호스트 파일 시스템으로 조용히 되돌아가지 않는다. 제공자의 `resolve()` 가 실패하면 코어가 호출마다 에러를 내는
`UnavailableExecutionEnvironment` 를 넣고, 환경 키는 한 번만 쓸 수 있으며 제공자는 하나이므로, 호스트 환경이 조용히 남는 경로가
구조적으로 없다. 이 보장이 성립하는 조건(도구가 도는 모든 경로가 제공자가 해석한 환경을 쓸 것)과 훅 가드의 fail-closed 는
[보안 §3](workspace-sandbox-security.md) 에 있다.

**격리는 확인할 수 없으면 시작하지 않는다.** 프로파일이 요구하는 capability 를 프로바이더가 광고하지 않으면 애플리케이션이 뜨지
않는다([프로바이더 §6](workspace-sandbox-provider.md), [설정 §3](workspace-sandbox-configuration.md)). 아직 구현되지 않은 기능을
쓰는 설정도 무시하지 않고 거부한다(§8).

---

## 8. 구현 상태

0.1.x 는 아래 계획을 뺀 나머지를 구현한다. 요구 버전은 aimon-core 0.3.4(§6.3), OpenSandbox 1.2.0
([OpenSandbox §1](workspace-sandbox-opensandbox.md)), Java 17 이다. 각 항목의 설계는 표의 문서가 `(계획 — 구현 단계 N)` 표지 아래
갖는다.

| 구현 단계 | 더하는 것 | 정본 |
|---|---|---|
| 5 — 멀티 슬롯 | `primary` 밖의 슬롯과 슬롯별 프로파일 고정 검사 | [도메인 §2.3](workspace-sandbox-domain.md) |
| | 공유 볼륨 `/shared` 와 `sharedAccess`, 슬롯 사이의 bare 저장소 협업과 git seed | [파일 시스템 §8](workspace-sandbox-filesystem.md), [수명 §3](workspace-sandbox-lifecycle.md) |
| | 볼륨 조정과 보존, 내장 `VolumeReclaimer` | [수명 §6.3](workspace-sandbox-lifecycle.md), [프로바이더 §5](workspace-sandbox-provider.md) |
| | 오케스트레이터 도구(`SandboxList` · `SandboxStart` · `SandboxStop`) | [바인딩 §7](workspace-sandbox-binding.md) |
| | 워크스페이스 쿼터 집행(지금은 레코드에 기록만 한다) | [보안 §8](workspace-sandbox-security.md) |
| 6 — 영속 저장소 · 멀티 노드 | `aimon-sandbox-store-jdbc`, 실제 프로세스 여럿의 멀티 노드 검증, 죽은 노드가 쥔 셸 락 인계 | [도메인 §4.4](workspace-sandbox-domain.md), [셸 §3](workspace-sandbox-shell.md), [테스트 §4](workspace-sandbox-testing.md) |
| 7 — 수명 최적화 | pause/resume(`PAUSE_RESUME`)과 `DRIFT` 판정 | [수명 §4](workspace-sandbox-lifecycle.md), [수명 §6.2](workspace-sandbox-lifecycle.md) |
| | warm pool | [프로바이더 §7](workspace-sandbox-provider.md) |
| | 샌드박스 worktree 로 워크플로 격리 브랜치(`isolate()`), `SandboxWorktrees` | [파일 시스템 §7](workspace-sandbox-filesystem.md) |
| 8 — 스냅숏 · 포크 | `SNAPSHOT` · `FORK` capability, `SandboxFork` | [프로바이더 §6](workspace-sandbox-provider.md) |

**지금 기동 시 거부되는 설정.** 아직 구현되지 않은 기능을 쓰는 프로파일은 조용히 무시하지 않고 거부한다. 무시하면 운영자가 켠
줄 아는 기능이 아무것도 하지 않기 때문이다. 해당 단계가 들어오면 이 거부가 풀린다.

| 설정 | 거부 문구의 요지 | 풀리는 단계 |
|---|---|---|
| 프로파일 `pauseAfter` | `PAUSE_RESUME` 이 필요하다 | 7 |
| 프로파일 `sharedAccess` 가 `none` 이 아님 | 공유 볼륨이 필요하다 | 5 |
| 프로파일 `seed` | seed 를 지원하지 않는다 | 5 |

목록은 [설정 §3](workspace-sandbox-configuration.md) 의 기동 검사 가운데 이 셋이다. 기동이 아니라 실행 때 거부되는 것도 있다 —
바인딩이 `primary` 밖의 슬롯을 고르면 그 실행의 환경이 사용 불가가 된다(구현 단계 5). 지금 프로바이더는 `PAUSE_RESUME` ·
`SNAPSHOT` · `FORK` 를 광고하지 않는다.

span · 메트릭은 어느 단계에도 두지 않는다. 지금은 수명 이벤트만 낸다([관리 §7](workspace-sandbox-management.md)).

---

## 9. 문서 지도

| 문서 | 별칭 | 다루는 것 |
|---|---|---|
| [workspace-sandbox.md](workspace-sandbox.md) | 개요 | 이 문서 — 목표 · 결정 요약 · 용어 · 수명 배치 · 모듈 · 코어 통합 · 실패 원칙 · 구현 상태 |
| [workspace-sandbox-domain.md](workspace-sandbox-domain.md) | 도메인 | 워크스페이스 레코드 · 슬롯 · 세션 옵션 레코드 · 저장소 SPI 와 CAS · 활동 기록 |
| [workspace-sandbox-binding.md](workspace-sandbox-binding.md) | 바인딩 | 바인딩 계약 · 우선순위 · 기본 정책과 포크 · 주체와 테넌트 · 권한 · 신뢰 도메인 |
| [workspace-sandbox-shell.md](workspace-sandbox-shell.md) | 셸 | 지속 셸 · 래퍼 · 락 · 트레일러 · 명령 종류 · 취소 · 백그라운드 · 출력 상한 · 훅 실행 |
| [workspace-sandbox-filesystem.md](workspace-sandbox-filesystem.md) | 파일 시스템 | 파일 배치 · 파일 도구 규칙 · 스테이징 · 동시 수정 · artifact · worktree · `/shared` 협업 |
| [workspace-sandbox-shell-engine.md](workspace-sandbox-shell-engine.md) | 셸 엔진 | 코어 셸 엔진(PTC) 지원 — runtimes · 런타임 파일 시스템 · 비밀 실행 파일 · kill 원인 |
| [workspace-sandbox-lifecycle.md](workspace-sandbox-lifecycle.md) | 수명 | connect 와 프로비저닝 · seed · idle · 프로바이더 만료 · janitor 와 조정 · 워크스페이스 종료 |
| [workspace-sandbox-notices.md](workspace-sandbox-notices.md) | notice | 환경/명령 notice · 큐 · 워크스페이스 전환 · generation · 미전달 초기화 |
| [workspace-sandbox-management.md](workspace-sandbox-management.md) | 관리 | 호스트 배선 · 관리형 워크스페이스 · 세션 옵션 API · 운영자 API · 파일 API · 수명 이벤트 |
| [workspace-sandbox-provider.md](workspace-sandbox-provider.md) | 프로바이더 | 프로바이더 SPI 계약 · 멱등 생성과 라벨 · 공유 볼륨 · capability |
| [workspace-sandbox-opensandbox.md](workspace-sandbox-opensandbox.md) | OpenSandbox | OpenSandbox 프로바이더 — capability 대응 · 서버 동작 · 에러 분류 · 설정 · 업스트림 이슈 |
| [workspace-sandbox-security.md](workspace-sandbox-security.md) | 보안 | 보안 층 · 닫힌 실패 · egress · 자격 증명 · 훅 경계 · 엔드포인트 배치 · 쿼터 · OOM |
| [workspace-sandbox-configuration.md](workspace-sandbox-configuration.md) | 설정 | 프로파일 · 배포 설정 · 기동 검사 · 이미지 계약 · 운영자 준비물 |
| [workspace-sandbox-testing.md](workspace-sandbox-testing.md) | 테스트 | 계약 스위트 · 테스트용 프로바이더 · 테스트 계층과 CI · 멀티 노드 검증 · 필수 시나리오 |
| [workspace-sandbox-hook-identity.md](workspace-sandbox-hook-identity.md) | (기록) | 정책 훅을 에이전트와 다른 uid 로 돌리는 일의 기록. 구현 전이고 설계 결정이 아니다 |

가이드(한국어): [사용자 가이드](../guide/user-guide.md) · [OpenSandbox 를 Kubernetes 에 설치하기](../guide/opensandbox-kubernetes.md) ·
[aimon-core 와 연동하기](../guide/aimon-core-integration.md).

aimon-core 문서:

- [실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md) — 이 모듈이 구현하는 SPI
- [scope-model](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/scope-model.md) — 수명과 소멸 책임
- [glossary](https://github.com/kangwoo/aimon-core/blob/main/docs/overview/glossary.md) §4 — 턴 · iteration · execution
- [session/routing](https://github.com/kangwoo/aimon-core/blob/main/docs/design/session/routing.md) — 세션의 노드 배치와 lease
- [workflow](https://github.com/kangwoo/aimon-core/blob/main/docs/design/workflow/workflow.md) — 격리 브랜치와 `WorktreeToolEnvironmentFactory`
- [artifact](https://github.com/kangwoo/aimon-core/blob/main/docs/design/agent-execution/artifact.md) — `ArtifactCollector`
- [tool-development-guide](https://github.com/kangwoo/aimon-core/blob/main/docs/features/tool/tool-development-guide.md) — 도구 규칙
