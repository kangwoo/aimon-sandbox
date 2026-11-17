# 파일 시스템

에이전트의 파일 도구와 셸이 샌드박스에서 보는 파일 시스템의 배치와 규칙을 정한다. 경로 규칙 · 스테이징 · 동시 수정 · artifact 와,
계획 단계의 worktree 와 `/shared` 협업이 이 문서의 몫이다. 사람이 쓰는 워크스페이스 파일 API 는
[관리 §6](workspace-sandbox-management.md) 이 정한다.

> 관련: [개요](workspace-sandbox.md) · [셸](workspace-sandbox-shell.md) · [셸 엔진](workspace-sandbox-shell-engine.md) ·
> [바인딩](workspace-sandbox-binding.md) · [보안](workspace-sandbox-security.md) · [프로바이더](workspace-sandbox-provider.md) ·
> [수명](workspace-sandbox-lifecycle.md) ·
> [코어 실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)

## 1. 한 파일 시스템

파일 도구는 샌드박스 환경의 `SandboxFileSystem` 하나만 본다. 셸이 보는 것과 파일 도구가 보는 것이 구조적으로 같다 — 에이전트가
`Write` 로 고친 파일을 다음 `Bash` 가 그대로 본다.

- **경로 라우터를 두지 않는다.** `/shared` 도 샌드박스 **안에** 마운트되므로 라우팅할 대상이 하나뿐이다. 라우터를 두면 셸과 파일
  도구가 `/shared` 를 서로 다른 경로로 보게 될 여지가 생긴다.
- **VFS ↔ 샌드박스 전체 동기화를 하지 않는다.** 명령마다 동기화하면 큰 저장소에서 느리고, 동시 수정을 합칠 규칙이 없다. 호환
  모드로도 두지 않는다.
- **에이전트 VFS(스킬 · 메모리 파일)는 샌드박스 모드의 파일 도구에서 보이지 않는다.** 에이전트 · 스킬 정의, 태스크 출력, artifact
  보관은 코어의 `controlFileSystem` 에 있다. 스킬이 스크립트를 실행해야 하면 그 스킬의 파일만 스테이징한다(§4).
- 한 실행의 환경은 하나다. 한 실행이 여러 슬롯에서 명령을 돌리지 않는다 — 파일 도구와 셸이 같은 것을 본다는 불변식이 실행 단위로
  성립한다. 다른 슬롯의 일은 그 슬롯에 바인딩된 서브에이전트 포크에 맡긴다([바인딩 §3](workspace-sandbox-binding.md)).

## 2. 배치

```text
/workspace                          샌드박스 전용(컨테이너 파일 시스템 또는 샌드박스별 볼륨)
  repo/                             기본 작업 root
  .worktrees/<key>/                 한 샌드박스 안의 병렬 작업용 git worktree — (계획 — 구현 단계 7), §7
  .aimon-staged/<name>/<contentKey>/  스테이징한 스킬 파일(필요할 때) — 파일 도구에 읽기 전용, §4
  .aimon-shell/<h(shellKey)>/       셸 상태 · 락 · owner · 락 heartbeat 파일 · run 파일 — 셸 §2.1
  .aimon-shell/.run.<hex>/          훅 실행 하나의 비공개 디렉터리, 실행 뒤 지움 — 셸 §9.2
  .aimon-ptc/<run>/                 코어 셸 엔진의 응답 파일 — 파일 도구에서 숨김, 셸 엔진 §3
  <root>/.aimon-ptc/<run>/          코어가 실제로 두는 곳: 서술자의 작업 디렉터리 아래(숨김)
/shared                             워크스페이스 볼륨, 프로파일마다 마운트(sharedAccess: rw | ro | none) — (계획 — 구현 단계 5), §8
  git/repo.git                      슬롯 사이에 커밋을 주고받는 bare 저장소
  context/ plans/ artifacts/ results/
```

셸 디렉터리와 비공개 디렉터리의 내용은 [셸 §2.1](workspace-sandbox-shell.md) 이, `.aimon-ptc` 의 내용은
[셸 엔진 §3](workspace-sandbox-shell-engine.md) 이 정한다. 이 문서는 파일 도구가 그곳을 어떻게 보는지만 정한다(§3).

바인딩의 `root`(기본 `/workspace/repo`)는 늘 존재한다. seed 가 없는 프로파일도 seed 단계에서 `mkdir -p {root}` 를 한다
([수명 §3](workspace-sandbox-lifecycle.md)). **(계획 — 구현 단계 7)** `isolate()` 는 worktree 를 만든 뒤에 환경을 돌려준다.

## 3. 파일 도구 규칙

### 3.1 상대 경로의 기준은 root 다

같은 파일 시스템이어도 상대 경로의 기준은 다르다. 파일 도구는 바인딩의 `root` 를, 셸은 모델이 `cd` 한 세션 cwd 를 기준으로 삼는다.
`cd src` 뒤의 `Read Foo.java` 는 `src/Foo.java` 가 아니다. 파일 도구의 기준을 셸 cwd 에 맞추면 같은 `Read` 가 직전 `cd` 에 따라 다른
파일을 읽으므로, 기준은 `root` 로 고정한다. 서술자 notes 가 이 사실을 모델에게 알린다(문구는 [개요 §6.2](workspace-sandbox.md)).

### 3.2 세 층

파일 도구의 파일 시스템은 세 층이다.

1. 바깥: 바인딩의 `root` 기준으로 상대 경로를 푸는 `SandboxFileSystem`.
2. 가운데: 코어의 공개 팩토리 `VirtualFileSystems.withPathRules` 가 `/` 를 기준으로 경로 규칙(§3.3)을 건다.
3. 안쪽: `ProviderFileSystem` 이 작업 디렉터리 `/` 로 프로바이더의 `SandboxFiles` 를 감싼다.

코어의 경로 규칙은 감싼 파일 시스템의 작업 디렉터리 밖을 전부 거부한다. 작업 디렉터리를 `/workspace/repo` 로 둔 채 감싸면
`/workspace/.aimon-staged` · `/shared` · `/tmp` 가 읽히지 않는다. 세 층으로 나누면 코어를 바꾸지 않고 같은 규칙을 쓴다.

- 각 호출은 `connect` 로 시작한다. 첫 파일 호출이 프로비저닝을 일으킬 수 있다([수명 §2](workspace-sandbox-lifecycle.md)).
- 쓰기 · stat 은 프로바이더의 파일 계약을 따른다([프로바이더 §3](workspace-sandbox-provider.md)). `copy` 는 읽기 + 쓰기다.
- 100 000 항목을 넘는 목록은 잘린 트리 대신 실패한다. `Glob` · `Grep` 이 조용히 일부만 보지 않게 하려는 것이다.

### 3.3 경로 제한과 그 예외

**파일 도구에 보안용 경로 제한을 두지 않는다.** 샌드박스 밖으로 나가는 경로는 애초에 없고, 샌드박스 안에서는 셸이 같은 uid 로 이미
무엇이든 할 수 있다. 파일 도구만 막으면 모델이 `cat` 으로 돌아갈 뿐이다.

예외는 실수 방지용 규칙 셋이다. 어느 것도 보안 경계가 아니다 — 셸은 여전히 읽고 쓴다. 코어의 경로 규칙(`PathRule`)을 그대로 쓴다.
대소문자 · 유니코드 별칭 처리를 이 모듈에 다시 적지 않으려는 것이다.

| 영역 | 파일 도구 | 이유 |
|---|---|---|
| `/workspace/.aimon-staged/` | 읽기 전용(`PathRule.readOnly`) | 코어 스테이징 계약. 모델이 `Write` 로 스킬 스크립트 사본을 무심코 고치면, 같은 `contentKey` 경로를 믿는 다른 실행이 고친 내용을 돌린다 |
| `/workspace/.aimon-shell/` | 읽기 전용 | 셸 상태 · 락 · run 파일을 파일 도구가 깨지 않게 |
| `/workspace/.aimon-ptc/`, `{root}/.aimon-ptc/` | 숨김(`PathRule.deny`) — 읽기 · 쓰기 · 목록에서 보이지 않음 | 코어 셸 엔진의 응답 파일. 응답의 무결성은 코어의 HMAC 이 지킨다. 코어가 그 디렉터리를 서술자의 작업 디렉터리, 곧 `root` 아래에 두므로 `root` 가 `/workspace` 가 아니면 `{root}/.aimon-ptc` 도 숨긴다. 그래서 실행이 도는 동안에는 응답 파일이 모델의 작업 트리 안에 있다([셸 엔진 §8](workspace-sandbox-shell-engine.md)) |

- 스테이징 경로를 권한 훅의 허용 목록(예: `Bash(/workspace/.aimon-staged/**)`)에 넣으면 안 된다. 셸로 심은 내용이 허용된 명령으로
  돈다. 셸이 스테이징 영역에 쓸 수 있는 것은 코어가 받아들인 한계다.
- 이름을 코어 로컬 제공자와 같은 `.aimon-staged` 로 둔다. 코어에서 `.aimon/` 은 "제어 저장소(`DENY`)" 를 뜻하므로, 샌드박스 안에서
  같은 이름이 스테이징 사본을 뜻하면 두 규칙이 헷갈린다.
- 셸 상태 영역 규칙은 읽기 전용이지 거부가 아니다. 그래서 훅이 도는 동안 파일 도구가 `.aimon-shell/.run.*` 을 읽을 수 있다
  ([셸 §12](workspace-sandbox-shell.md)).
- 사람용 파일 API 의 읽기 전용 범위(`/workspace` 자체와 최상위 `.aimon-*`)는 이것보다 넓다. 의도된 차이이고
  [관리 §6.4](workspace-sandbox-management.md) 가 정한다.

### 3.4 내용 검색(`Grep`)

환경의 `contentSearch()` 는 샌드박스 안에서 `rg --json` 을 한 번 돌린다. 이미지 계약이 ripgrep 을 요구하는 이유다
([설정 §4](workspace-sandbox-configuration.md)).

- 코어 로컬 검색과 같이 숨김 파일과 무시된 파일도 찾는다(`--hidden --no-ignore`).
- `rg` 에는 절대 · 정규화 경로를 준다. 그래서 `./src//` 와 `../repo/src` 가 모두 `src/…` 로 읽힌다. exec 는 root 로 `cd` 하지 않고
  `/workspace` 에서 돈다 — root 가 지워져도 모든 검색이 코어의 파일별 순회로 물러서지 않는다.
- `rg` 는 병렬로 찾아 출력 순서가 실행마다 다르므로 결과를 경로로 정렬한다. 같은 질의는 늘 같은 결과를 읽는다.
- 취소되면 `rg` 를 끝낸다([셸 §6.5](workspace-sandbox-shell.md)).
- **`Grep` 은 경로 규칙을 거치지 않는다.** 코어의 `GrepTool` 은 `contentSearch()` 결과를 경로 규칙 없이 쓴다. 그래서 `.aimon-ptc` 의
  응답 내용과 `.aimon-staged` · `.aimon-shell` 이 `Grep` 결과에 나올 수 있다(§10).

### 3.5 컨텍스트 제공자를 등록하지 않기를 권한다

코어는 컨텍스트 제공자에게 실행 환경의 `fileSystem()` 을 준다. 옵트인 제공자인 `GitStatusContextProvider`(`.git/HEAD`)와
`DirectorySummaryContextProvider`(루트 목록)는 매 턴 그것을 읽으므로, 샌드박스 모드에서는 명령을 한 번도 치지 않는 턴도 샌드박스를
띄운다. 게으른 프로비저닝([수명 §2.3](workspace-sandbox-lifecycle.md))이 무의미해진다. **(계획 — 구현 단계 7)** pause 가 생기면 일시
정지된 슬롯을 매 턴 resume 하게 된다. 샌드박스 어셈블리는 이 두 제공자를 등록하지 않기를 권한다. 모델에게 필요한 작업 디렉터리 ·
플랫폼은 서술자가 원격 호출 없이 준다.

## 4. 스테이징

스킬이 스크립트를 실행해야 하면 코어가 스킬을 렌더하는 시점에 `stage()` 를 부르고, 이 모듈은 그 스킬의 파일만
`/workspace/.aimon-staged/{name}/{contentKey}/` 로 materialize 한다. 스킬 전체를 기동 시점에 복사하지 않는다. 규칙의 정본은 코어
[실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md) §4.4 이고, 이 모듈이 지킬
것은 아래와 같다.

- **렌더하는 모든 경로에서 불린다.** `Skill` 도구만이 아니라 스킬 포크와 스킬 기반 슬래시 커맨드도 같은 경로로 `stage()` 를 부른다.
  활성화 시점을 이 모듈이 따로 가정하지 않는다.
- **이미 이 환경에 있는 리소스는 복사하지 않는다.** 소스 파일 시스템이 이 환경의 `SandboxFileSystem` 인스턴스일 때만 그렇게 본다.
  규칙 층과 원시 층은 호출마다 새 객체라 동일성으로 비교할 수 없다.
- **생략은 샌드박스 안의 사본으로 판단한다.** 복사를 마친 뒤 마지막에 `.staged` 마커를 쓴다. 다음 `stage()` 는 마커가 있으면 샌드박스
  안에서 exec 한 번으로 `StagedResource` 의 파일 목록 순서대로 코어와 같은 알고리즘(`relPath\0bytes\0` 의 SHA-256)으로 content key 를
  다시 계산하고 사본의 파일 목록을 받는다. 그 exec 는 셸 상태 없이 `PATH` 를 고정하고 `/usr/bin/sha256sum` 절대 경로로 돈다 — 모델이
  export 한 `PATH` 가 가짜 `sha256sum` 을 부르지 않게. 키가 `contentKey` 와 같고 파일 집합이 정확히 목록 + 마커일 때만(파일이 더
  있어도 안 된다) 건너뛰고, 다르면 지우고 다시 복사한다.
  - 마커만 보지 않는 것은, 셸로 `.aimon-staged/` 에 쓸 수 있는 실행이 `contentKey` 를 예측해 스크립트와 마커를 미리 심을 수 있기
    때문이다. 다른 에이전트가 그 스킬을 활성화할 때 심어 둔 내용이 돈다.
  - JVM 쪽에 파일별 해시를 두지 않는다. `StagedResource` 가 그것을 들고 있지 않아 `stage()` 마다 스킬을 다시 읽어야 하기 때문이다.
  - 이 검사는 코어의 content key 만큼 강하고 파일별 해시보다는 약하다. 코어 키의 틀에서는 한 파일의 바이트가
    `\0{다음 relPath}\0` 를 담으면 파일 경계를 넘어 옮긴 내용이 같은 스트림을 만들 수 있고, 파일 집합 검사가 그 여지를 좁힌다.
    코어 자신이 믿는 키이고 위험은 이론적이다.
  - 파일 목록이 32 KiB 를 넘으면 스크립트에 넣지 않고 NUL 로 구분한 파일로 `.aimon-staged` 아래에 올린 뒤 지운다. 인자 한도 때문이다
    ([셸 §2.3](workspace-sandbox-shell.md)).
  - "이 generation 에 이미 올렸다" 를 노드 메모리나 레코드에 기억하지 않는다. 소실 후 재생성이나 다른 노드에서 온 요청이 그 기록을
    믿으면 없는 경로를 모델에게 준다.
- **동시 활성화에도 완성된 사본만 보인다.** 한 슬롯의 두 실행(메인 턴과 포크, 병렬 워크플로 단계)이 같은 스킬을 처음 활성화하면 둘 다
  마커를 못 본다. 그래서 검사와 복사는 `(providerRef, target)` 마다의 노드 로컬 락 안에서 하고, 복사는 `.tmp-{contentKey}-{nonce}`
  임시 디렉터리에 받은 뒤 마커를 마지막에 쓰고 제자리로 옮긴다(`move`). 락을 기다린 쪽은 락 안에서 다시 검사해 먼저 끝난 사본을
  돌려준다. 대상은 늘 완성된 채로만 나타나고, 중단된 복사의 임시 디렉터리는 다음 `stage()` 가 지운다. **(계획 — 구현 단계 6)** 다른
  노드의 동시 복사는 `move` 의 "이미 있음" 을 받고 대상을 다시 검사하는 것으로 충분하다.
- 노드 로컬 스테이징 락은 연결이 퇴출될 때 쥐거나 기다리는 스레드가 없는 것만 지운다. 돌고 있는 `stage()` 의 락이 바뀌지 않게 하려는
  것이다([셸 §3.1](workspace-sandbox-shell.md)).
- **읽힌 뒤 바뀐 스킬은 올리지 않는다.** 복사하며 읽은 바이트로 content key 를 다시 계산하고, `contentKey` 와 다르면 임시 디렉터리를
  지우고 코어와 같은 "changed on disk after it was loaded" `StagingException` 을 낸다. 그렇지 않으면 바뀐 내용이 옛 키의 경로로
  올라가고, 이후의 모든 `stage()` 가 키 검사에 실패해 다시 복사하며 바뀐 내용을 돌린다. 이름 · 키 · 파일 경로의 모양과 크기
  상한(코어의 50 MiB)도 코어와 같게, 샌드박스에 닿기 전에 검사한다.
- 검증에 실패한 대상을 지우는 단계는, 성공한 검증 뒤에 변조된 파일만 지울 수 있다.
- **(계획 — 구현 단계 7)** `.aimon-staged/` 는 `repo/` 와 `.worktrees/` 밖에 있으므로 `isolate()` 가 만든 환경도 같은 사본을 쓰고, git
  병합에 섞이지 않는다.

## 5. 동시 수정

같은 슬롯을 여러 실행이 공유할 때 동시 수정을 막는 장치는 코어의 파일 stamp 검사(읽은 뒤 바뀐 파일에 쓰기 거부) 하나다.

- stamp 검사는 다른 실행이 파일 도구로 바꾼 경우와 **셸이 바꾼 경우를 모두 잡는다.** 실제 파일 상태와 비교하기 때문이다. 그래서
  프로바이더의 `files().stat()` 은 변경 감지 계약(밀리초 이상 mtime 또는 etag)을 지켜야 하고, 프로바이더 계약 스위트가 그것을
  검사한다([프로바이더 §3](workspace-sandbox-provider.md)).
- 리비전 번호로 동시 수정을 막지 않는다. 리비전은 파일 도구 경로만 추적해 셸이 바꾼 파일을 놓치기 때문이다.
- 경로 락(TTL · heartbeat)과 리비전 DB 를 두지 않는다. 셸이 둘 다 우회하므로 락이 보장하는 것은 "파일 도구끼리는 안 부딪힌다" 뿐인데,
  그것은 stamp 검사가 이미 준다. 진짜 격리가 필요하면 파일 단위 조율이 아니라 git 브랜치와 슬롯 분리를 쓴다.
- stamp 검사는 확인과 쓰기 사이에 틈이 있는 최선 노력 장치다 — 원자적 비교-후-쓰기가 아니다.

공유 파일은 VCS 가 아니다. 이 모듈은 공유 파일의 이력이나 병합을 다루지 않는다.

## 6. artifact

artifact 는 샌드박스보다 오래 살아야 한다. 샌드박스 파일을 가리키는 경로를 `ArtifactCollector` 에 등록하면 샌드박스가 종료될 때
artifact 가 끊어진다. 그래서 등록 시점에 제어 저장소로 복사해야 한다.

**복사는 이 모듈이 하지 않는다.** 환경은 `durable() == false` 를 답하고, 코어의 artifact-aware 도구가 그것을 보고 `controlFileSystem`
으로 복사한다. 복사 경로와 상한의 정본은 코어 [실행 환경 설계](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)
§9.3 이다. 이 모듈에 artifact 전용 경로나 전송 상한 클래스를 두지 않는다. 두면 같은 정책이 두 저장소에서 따로 바뀐다.

## 7. 한 샌드박스 안의 병렬 — worktree (계획 — 구현 단계 7)

지금 `isolate(branchKey)` 는 늘 비어 있음을 돌려주고, 코어는 그 워크플로 격리 단계를 거부한다.

같은 샌드박스에서 여러 실행이 동시에 코드를 바꿔야 하면 `git worktree add /workspace/.worktrees/{key}` 를 만들고 그 환경의 `root` 를
거기로 둔다. 워크플로 격리 브랜치는 샌드박스 모드에서 이 경로를 탄다 — 코어의 워크플로 러너가 부모 환경의 `isolate(branchKey)` 를
부르고, 이 모듈은 worktree 를 만든 뒤 `root` 만 바꾼 파생 환경을 돌려준다. 브랜치 이름은 `aimon/{branchKey}` 이고 부모의 현재
HEAD 에서 갈라진다. `root` 가 git 저장소가 아니면(seed 없는 프로파일) 비어 있음을 돌려주고 코어가 그 단계를 거부한다.

worktree 의 수명 규칙:

- **생성은 슬롯 안에서 직렬화한다.** 병렬 브랜치의 `git worktree add` 는 같은 `.git` 의 config · ref 락에서 경합해 실패할 수 있다.
  `isolate()` 는 샌드박스 안의 `flock /workspace/.worktrees/.lock` 을 쥔 채 worktree 를 만든다.
- **같은 branchKey 로 다시 만들면 새로 만든다 — 다른 실행이 쓰는 중이 아니면.** worktree 마다 `.aimon-owner`(만든 실행의
  executionId)를 두고, `SandboxShell` 은 그 root 에서 명령을 돌릴 때마다 이 파일의 mtime 을 갱신한다. 경로나 브랜치가 이미 있고
  `.aimon-owner` 가 `worktreeRetention` 보다 오래 갱신되지 않았으면 `git worktree remove --force` 와 `git branch -D` 로 지운 뒤 부모의
  현재 HEAD 에서 다시 만든다. 최근에 쓰였으면 지우지 않고 "branchKey 가 다른 실행에 쓰이고 있다" 에러를 돌려준다. 새 `isolate()` 는
  새 브랜치 실행을 뜻하고, 옛 브랜치의 결과를 이어받는 경로는 코어에 없다.
- **정리는 병합한 쪽이 한다.** 코어 `ExecutionEnvironment` 에는 닫는 훅이 없으므로 이 모듈이 끝을 알 수 없다.
  `SandboxWorktrees.remove(parent, branchKeys)` 가 worktree 와 브랜치를 지우고, `SandboxWorktrees.merge` 는 성공한 브랜치를 스스로
  지운다. `WorktreeMerge.promote` 를 쓰는 조립 코드는 promote 뒤에 `remove` 를 부른다. 놓친 것은 다음 `isolate()` 가 같은 락 안에서
  `git worktree prune` 과, `.aimon-owner` 가 `worktreeRetention`(기본 24시간)보다 오래 갱신되지 않은 `.worktrees/*` 삭제로 치운다 —
  디스크를 채우지 않게 하려는 것이다.
- **generation 이 바뀌면 파생 환경은 에러로 끝난다.** 파생 환경은 `isolate()` 시점의 generation 을 기억한다. 샌드박스가 소실되어 다시
  만들어지면 `.worktrees/{key}/` 는 없다. 그 뒤의 호출은 빈 디렉터리를 만들어 이어 가지 않고 "격리 브랜치의 작업 트리를 잃었다"
  에러를 돌려준다. 빈 트리에서 계속하면 `WorktreeMerge.promote` 가 빈 결과를 `repo/` 에 반영할 수 있다.
- 격리 브랜치 안에서 다시 격리하는 것(중첩 워크플로)은 코어 로컬 제공자처럼 지원하지 않고 비어 있음을 돌려준다.

**병합은 코어가 하지 않는다.** 코어에서 병합은 명시적이다 — 러너는 병합하지 않고, 조립 코드가 `WorktreeMerge.promote(parent, branches,
policy)` 를 부른다. 그래서 두 방법이 모두 열려 있다.

- `WorktreeMerge.promote` — 파일 시스템만 쓰므로 샌드박스 환경에서도 그대로 동작한다(`.worktrees/{key}/` 에서 `repo/` 로 파일을
  복사한다). 커밋 이력은 남지 않는다.
- `SandboxWorktrees.merge(parent, branches, strategy)` — 이 모듈의 공개 API. 부모 환경의 셸에서 각 브랜치를 `git merge --no-ff
  aimon/{key}`(또는 cherry-pick)로 합치고, 충돌은 `MergeReport` 와 같은 모양의 결과로 돌려준다.

코어 `ExecutionEnvironment` 에 병합 메서드를 더하지 않는다. 코어에서 병합은 명시적이라 러너가 부르지 않으므로, 호출자가 고르는 일에
SPI 를 늘릴 이유가 없다. 모델이 직접 합치는 것도 된다 — 브랜치는 보통의 git 브랜치이므로 부모 실행의 `Bash` 에서 `git merge
aimon/{key}` 를 치면 된다.

worktree 는 **샌드박스 안의 병렬성**을, 슬롯 분리는 **런타임 자체의 격리**를 푼다. 둘은 함께 쓸 수 있다.

## 8. 슬롯 사이의 협업 — /shared (계획 — 구현 단계 5)

지금은 `sharedAccess` 가 `none` 이 아닌 프로파일과 `seed` 를 가진 프로파일을 기동 시 거부한다([설정 §3](workspace-sandbox-configuration.md)).

### 8.1 bare 저장소

슬롯은 `/workspace` 를 공유하지 않는다. 커밋을 주고받는 통로는 `/shared/git/repo.git` 이다. seed 가 bare 저장소와 작업 트리를 받는
절차는 [수명 §3](workspace-sandbox-lifecycle.md) 이 정한다.

```text
개발 슬롯:  git push origin HEAD:refs/heads/dev/<topic>
리뷰 슬롯:  git fetch origin && git checkout dev/<topic>        (외부 네트워크가 필요 없다)
```

- 외부 원격(GitHub)과 통신하는 것은 그 권한을 프로파일로 받은 슬롯뿐이다. `review` · `test` 프로파일은 egress 를 전부 막아도 개발
  슬롯의 커밋을 받을 수 있다.
- 공유 PVC 위의 **작업 트리** `.git` 을 여러 샌드박스가 동시에 만지는 방식은 쓰지 않는다. bare 저장소에 대한 push/fetch 는 git 이
  ref 락으로 직렬화한다.
- 워크스페이스 저장 모드 enum(`SHARED`/`ISOLATED`/`HYBRID`)을 두지 않는다. 이 구성(슬롯마다의 `/workspace` + 공유 bare 저장소) 하나만
  둔다. 모든 샌드박스가 한 볼륨을 작업 트리로 쓰는 방식은 `.git` 동시 변경과 신뢰 경계 약화를 부르고, 공유하지 않는 방식은
  `sharedAccess: none` 으로 표현된다.

### 8.2 sharedAccess

**bare 저장소는 슬롯 사이의 코드 실행 통로가 될 수 있다.** 로컬 경로로 `git push` 하면 `receive-pack` 이 **push 하는 쪽 샌드박스
안에서** `repo.git/hooks/*` 와 `repo.git/config` 를 읽어 실행한다. `/shared` 에 쓸 수 있는 슬롯은 누구나 훅을 심을 수 있고, 그 훅은
다음에 push 하는 슬롯 — 대개 egress 와 자격 증명을 가진 개발 슬롯 — 에서 돈다. 그래서:

- 프로파일의 `sharedAccess` 로 `/shared` 마운트를 `rw` · `ro` · `none` 중에서 고른다. **경계는 `sharedAccess` 다**
  ([보안 §2](workspace-sandbox-security.md)).
- 기본은 `none` 이다. 슬롯이 하나인 기본 구성에는 `/shared` 가 필요 없고, 기본이 `ro` 면 bare 저장소를 만들 `rw` 슬롯이 없어 seed 가
  원격을 받지 못하고 공유 볼륨이 없는 배포가 기동하지 못한다. 여러 슬롯이 협업하는 배포만 `rw`/`ro` 를 적는다.
- 모든 슬롯에 `rw` 를 주지 않는다. `rw` 를 받은 슬롯들은 서로 한 신뢰 도메인이 되므로([바인딩 §6](workspace-sandbox-binding.md)),
  신뢰가 다른 슬롯에 `rw` 를 함께 주지 않는다.
- `ro` 슬롯은 fetch 만 할 수 있다. 리뷰 결과처럼 되돌려 줄 것이 있으면 코드가 아니라 도구 결과(모델이 읽는 텍스트)나 artifact 로
  돌려준다.
- seed 는 bare 저장소를 만든 직후 `hooks/` 를 비우고, seed 자신의 git 호출은 `-c core.hooksPath=/dev/null` 로 돈다. 모델이 치는 git
  명령까지 막을 수는 없으므로 이것은 방어가 아니라 기본값 정리다.

### 8.3 공유 볼륨이 없을 때

`SHARED_VOLUME` capability 를 갖추지 못한 프로바이더에서는 모든 프로파일이 `sharedAccess: none` 이어야 기동한다
([프로바이더 §6](workspace-sandbox-provider.md)). 슬롯은 여럿 만들 수 있지만 bare 저장소가 없으므로 슬롯 사이에 커밋을 주고받을 수
없고, 각 슬롯의 seed 는 원격에서 직접 clone 한다(그 슬롯에 egress 가 있어야 한다).

워크스페이스의 첫 bare 저장소는 `sharedAccess: rw` 이면서 원격에 닿는 슬롯만 만들 수 있다. 그런 슬롯이 아직 없는데 `ro` 슬롯이 먼저
뜨면, seed 는 `/workspace/repo` 에 `git init` 을 하고 `git remote add origin /shared/git/repo.git` 까지 해 둔 뒤 notice 로 알린다. 빈
디렉터리만 두면 "`/workspace/repo` 가 있으면 건너뛴다" 때문에 bare 저장소가 나중에 생겨도 그 슬롯은 generation 이 바뀔 때까지 빈 채로
남는다. origin 을 걸어 두면 bare 저장소가 생긴 뒤 `git fetch origin` 이 그대로 동작한다.

### 8.4 공유 파일의 동시 수정

`/shared` 의 계획 · 설계 문서를 여러 에이전트(`sharedAccess: rw` 슬롯)가 고칠 때 쓰는 장치도 stamp 검사다(§5). 공유 볼륨이 NFS 계열
RWX 볼륨이면 속성 캐시 때문에 mtime 이 늦게 보일 수 있으므로, `SandboxFileSystem` 은 `/shared` 아래 경로에 대해 stamp 를 위해
etag(내용 해시)를 준다. 프로바이더의 files API 가 해시를 주지 않으면 stat 마다 샌드박스 안에서 해시를 계산해야 한다
(확인 필요: 그 비용과, OpenSandbox files API 가 해시를 주는지 — 지금 프로바이더의 stat 은 mtime 만 준다).

## 9. 실패

| 상황 | 결과 |
|---|---|
| 스테이징 영역 · 셸 상태 영역에 `Write`/`Edit` | 코어 경로 규칙의 "Access denied" 에러 |
| 읽은 뒤 파일이 바뀜 | `Edit`/`Write` 에러 — 다시 읽으라고 안내(§5) |
| 목록이 100 000 항목을 넘음 | 잘린 트리 대신 실패(§3.2) |
| 일치한 줄이 프로바이더의 줄 상한을 넘음(압축된 번들, 한 줄짜리 JSON, `-a` 로 훑는 바이너리) | `rg` 가 kill 되고 두 스트림이 잘림으로 표시되어 `search` 가 실패한다. 코어의 `GrepTool` 은 파일 시스템 순회로 물러서므로 결과는 맞고 느려질 뿐이다. 줄 상한은 받은 JSON 바이트로 재므로 HTML 같은 내용은 더 짧은 원문에서 닿는다. `rg --max-columns` 는 쓰지 않는다 |
| 스테이징 복사 중단 | 대상은 나타나지 않는다. 남은 임시 디렉터리는 다음 `stage()` 가 지운다(§4) |
| 동시 `stage()` | 락을 기다린 쪽이 먼저 끝난 사본을 돌려준다(§4) |
| 읽힌 뒤 바뀐 스킬 | "changed on disk after it was loaded" `StagingException`(§4) |
| 사본의 content key · 파일 집합이 맞지 않음 | 지우고 다시 복사한다(§4) |
| 격리 브랜치의 작업 트리를 잃음(generation 변경) **(계획 — 구현 단계 7)** | 에러 — 파생 환경은 이어 가지 않는다(§7) |
| 같은 branchKey 의 worktree 를 다른 실행이 최근에 씀 **(계획 — 구현 단계 7)** | `isolate()` 에러(§7) |
| `sharedAccess: ro` 슬롯의 `/shared` 쓰기 **(계획 — 구현 단계 5)** | 파일 시스템의 읽기 전용 에러를 그대로 돌려준다 |

## 10. 열린 질문

- **`Grep` 은 경로 규칙을 거치지 않는다** — 코어의 `GrepTool` 은 `contentSearch()` 결과를 경로 규칙 없이 쓰고, 이 모듈의 검색은
  `rg --hidden --no-ignore` 를 돌린다. 그래서 `/workspace/.aimon-ptc` 와 `{root}/.aimon-ptc` 의 응답 내용, `.aimon-staged` ·
  `.aimon-shell` 이 `Grep` 결과에 나올 수 있다. 코어가 `contentSearch()` 에도 규칙을 적용할지(코어 쪽 한 곳), 이 모듈이 `rg` 에 거부
  접두어를 `--glob '!…'` 로 넘길지(사용자 glob 과의 순서를 정해야 한다) 정하지 않았다.
- **`sharedAccess: none` 슬롯의 git 직접 clone** **(계획 — 구현 단계 5)** — git seed 와 함께 들어간다. 그때까지 `seed` 를 가진 프로파일은 기동
  시 거부한다. 구현되지 않은 경로가 조용히 아무것도 하지 않는 것보다 낫기 때문이다. 넣을 때는 bare 저장소 seed 와 함께 seed 에
  넣는다.
- 슬롯 여럿과 `/shared` 를 한 시점으로 묶는 워크스페이스 체크포인트는 [수명 §9](workspace-sandbox-lifecycle.md).
