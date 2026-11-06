# PTC 셸 엔진의 선행 작업 (S1–S9)

> **Status**: S1 · S2 · S7 · S9 구현. S3 은 문서 초안. S4 · S5 · S6 · S8 은 코어 SPI(조각 D1)가 배포된 뒤의 구현 방향
> 메모 — 지금은 코드를 바꾸지 않는다.
>
> 근거 설계: `aimon-core` 의 `docs/design/tool/programmatic-tool-calling-shell.md`(이하 **셸 엔진 설계**, 작성 시점에 커밋되지
> 않음) §7, §12, §13, §15. 관련: [`workspace-sandbox-hook-identity.md`](workspace-sandbox-hook-identity.md)(EE-113, 같은
> 업스트림 블로커). OpenSandbox 소스는 `docker/execd/v1.1.0`, `release-1.1.0`, `release-1.1.1-rc.1`, `release-1.1.1`, `main`
> 태그·브랜치에서 읽었다. 경로는 `components/execd/` 아래다.

## 1. 범위

| # | 셸 엔진 설계 §13 | 코어 SPI 에 기대는가 | 이 문서 |
|---|---|---|---|
| S1 | isolated·hook·스트리밍 실행 뒤 execd 스풀 삭제 | 아니다 | **구현** (§3) |
| S2 | SSE 리더가 줄을 물질화하기 전 상한, 넘으면 명령 종료 | 아니다 | **구현** (§4) |
| S3 | 이미지 계약에 `python3` 선택 항목, 프로필 선언, seed 확인 | 서술자 부분은 기댄다 | 초안 (§6) |
| S4 | `RunDirectory` 비밀 파일 표지 | 기댄다 | 방향 (§7) |
| S5 | `RuntimeFileSystem` 구현 | 기댄다 | 방향 (§8) |
| S6 | 서술자 `isolation()` · 런타임 능력 | 기댄다 | 방향 (§9) |
| S7 | isolated 스트리밍이 포그라운드가 아님을 계약 테스트로 | 아니다 | **구현** (§5) |
| S8 | 제공자가 명령을 끝낸 원인을 결과에 싣기 | 기댄다 (`ShellCommandResult`) | 질문 (§10 Q2) |
| S9 | `.aimon-ptc/` 를 모델의 파일 도구에게 `DENY` | 아니다 | **구현** (§8 경로 규칙) |

## 2. 셸 엔진 설계와 실제 코드·업스트림이 어긋나는 점

셸 엔진 설계는 바꾸지 않았다. 아래는 그쪽에서 판단할 사실이다.

### 2.1 execd 스풀의 위치와 삭제 — 버전에 따라 다르다

셸 엔진 설계 §7 과 §13 S1 은 스풀을 `$TMPDIR/opensandbox-execd/<id>.{stdout,stderr}` 라고 하고 "지금은 남는다" 고 한다.
두 말은 **서로 다른 execd 버전의 사실이다.**

| execd | 위치 | 권한 | 삭제 |
|---|---|---|---|
| `docker/execd/v1.1.0` (이 저장소 docker 계층의 고정 버전, `OpenSandboxTestServer`) | `os.TempDir()/<id>.stdout`·`.stderr` — 하위 디렉터리 **없음** | 파일 `os.ModePerm &^ umask` | **안 지운다** (`pkg/runtime/command_common.go:65-104`) |
| `release-1.1.0` 이상 (`release-1.1.1-rc.1` = K8s 차트 기본, `release-1.1.1`, `release-1.2.0-rc.1`, `main`) | `os.TempDir()/opensandbox-execd/<id>.stdout`·`.stderr` | 디렉터리 execd euid 소유 `0700`(아니면 거절, 심볼릭 링크 거절), 파일 `0600` `O_EXCL` (`command_output_unix.go`) | **execd 가 지운다.** 포그라운드 `runCommand` 가 반환할 때 `defer removeCommandOutputFiles(...)` — 성공·실패·타임아웃·interrupt 모두(`command.go:226-232`). 그 밖에 한 시간마다 24시간 지난 스풀을 — 옛 위치(`$TMPDIR` 바로 아래, 이름이 32자리 hex 인 것)까지 — 치우는 janitor(`StartCommandOutputJanitor`). 옛 위치의 것도 24시간이 지나야 지운다 |

결과:

- **R4(수동 노출)는 `release-1.1.0` 이상에서 이미 "실행 중" 으로 줄어 있다.** S1 이 실제로 바꾸는 것은 `v1.1.0` 이하의 execd
  뿐이다. 그래도 S1 은 두 위치를 모두 지운다(§3) — 새 execd 에서는 없는 파일의 삭제(성공으로 답한다)라 해가 없다.
- **조건 ⑤(셸 엔진 설계 §12.3)와 훅 신원의 조건 5 에 유리한 사실.** `release-1.1.0` 이상의 스풀 디렉터리는 execd euid 소유
  `0700` 이므로, 훅 신원 토폴로지(root execd, 명령은 `uid=A`)에서는 A 가 스풀을 읽지도 덧붙이지도 못한다. 같은 uid 인 지금
  배치에서는 아무 효과가 없다.
- **`$TMPDIR` 은 execd 프로세스의 것이다**(Go `os.TempDir()`: `TMPDIR` 이 비어 있지 않으면 그것, 아니면 `/tmp`). 명령의
  환경(`EXECD_ENVS`, 요청 `envs`)과 다를 수 있다. files API 는 경로의 `$VAR` 를 펼치지만 정의되지 않은 변수는 오류로
  거절하므로(`pkg/util/pathutil/path.go`), 프로바이더는 `$TMPDIR` 을 `/tmp` 로 가정한다(§3). execd 를 `TMPDIR` 을 둔 채
  띄우는 배치에서는 v1.1.0 의 스풀 삭제가 빗나간다 — 이미지 계약 문장 후보(§6.1).

### 2.2 SSE 리더 구조 — 설계의 서술이 맞다

`SseEvents.read` 는 `BufferedReader.readLine()` 으로 한 줄을 통째로 읽었다. 셸 엔진 설계 §13 S2 의 서술 그대로다. 덧붙일 사실:

- execd 자신도 줄 하나를 상한 없이 모은다(`commandOutputTail.pending`, `release-1.1.1` 의 `command_common.go:240-280`). JVM 쪽
  상한은 JVM 의 메모리만 지키고, execd 의 메모리와 샌드박스 디스크는 지키지 않는다.
- 상한은 **받은 JSON 바이트**로 센다. 출력 한 줄이 N 바이트면 이벤트 줄은 N 이상이다(이스케이프로 최대 6배).
- 셸 엔진 설계 §7 의 통로 줄 상한(64 KiB 미만)은 리더 상한(256 KiB)보다 작으므로 둘은 충돌하지 않는다. 다만 리스너에
  닿기 전에 `SandboxShell` 의 `StreamLines` 가 개행 없이 64 KiB(`MAX_LINE_BYTES`)에 이른 줄을 잘라 **별도의 줄로** 넘긴다
  (UTF-8 문자 경계에서 자른다). 그래서 64 KiB 이상의 통로 줄은 호스트에 두 조각으로 닿고 둘 다 MAC 이 맞지 않는다 — 결과는 같은 위반이지만,
  호스트가 "줄이 너무 길다" 를 따로 진단하려면 이 분할을 알아야 한다.

### 2.3 기존 결정 D8 과의 관계

[단계 4 구현 설계](workspace-sandbox-step4.md) D8 은 "프로바이더는 `maxCaptureBytes` 를 넘는 출력을 버릴 뿐 명령을 끝내지 않는다"
이다. S2 는 그 예외다 — **한 줄**이 상한을 넘을 때만 끝낸다. 상한까지 버리며 읽는 대안은 그 줄을 끝까지 읽어야 다음 이벤트로
갈 수 있어 메모리는 지키지만 줄이 끝나지 않으면 영원히 읽는다. 셸 엔진 설계가 "끝낸다" 를 골랐으므로 그대로 따랐다.
§6.1 에 예외로 적었다.

### 2.4 S4 의 절반은 이미 성립한다

셸 엔진 설계 §3 · §13 S4 의 "업로드 원본(지금은 `/workspace/.aimon-shell/` 아래 blob)을 배치 직후 지운다" 는 이미 그렇다:
래퍼가 `"$r.manifest"` · `"$r.blob"` 을 비공개 디렉터리로 `cat` 한 **바로 다음 줄**에서 `rm -f` 하고(다이제스트 검사보다 앞),
비공개 디렉터리의 `.blob` 도 풀자마자 지운다(`ShellWrapper` 의 run-files 단계). JVM 은 래퍼가 그 전에 죽었을 때 지운다
(`SandboxShell.cleanUp`). S4 에서 새로 필요한 것은 "비밀 파일은 인라인하지 않는다" 뿐이다(§7).

### 2.5 S5 의 `writeNew` 는 이미 있다

`SandboxFiles.write(path, …, WriteMode.CREATE_NEW)` 가 정확히 "임시 이름 업로드 + `ln`" 이다(`OpenSandboxFiles.write`). 다른 점은
§8 에 적는다.

## 3. S1 — stream 실행의 스풀 삭제 (구현)

- **SPI**: `ExecSpec.transientOutput()` (기본 `false`). 뜻은 제공자 중립이다 — "샌드박스 안에 출력 사본을 남기는 제공자는 명령이
  끝난 뒤(완료·실패·타임아웃·kill) 그것을 지운다. best effort, 실패는 로그만". 남기지 않는 제공자(testkit 의 로컬 프로바이더)는
  아무것도 하지 않는다.
- **`SandboxShell`**: stream 실행 — 훅(`isHook`), 격리(`isIsolated`), 스트리밍(`ShellLineListener` 로 부른 것, 백그라운드
  모니터 포함) — 에 켠다. 이들의 출력은 exec 자신의 스트림이라, execd 스풀이 샌드박스 안에 남는 유일한 사본이다. 모델의
  포그라운드·백그라운드 명령은 끄고 둔다: 출력은 래퍼의 `run.out` 으로 가고 그 파일은 래퍼가 지우며, 스풀에는 트레일러 정도만
  남는다. 셸 엔진 설계 §13 S1 이 꼽는 범위(isolated·hook·스트리밍 실행) 그대로다.
- **OpenSandbox 프로바이더**(`OpenSandboxRunningCommand`): `init` 이벤트의 command id 로, 명령의 끝이 정해진 뒤
  `DELETE /files?path=…` **한 번**에 네 경로를 지운다 — `/tmp/opensandbox-execd/<id>.{stdout,stderr}` 와
  `/tmp/<id>.{stdout,stderr}`. execd 의 `RemoveFiles` 는 `path` 를 여럿 받고, 없는 파일은 성공으로 친다
  (`DeleteFile` 의 `os.Stat` 이 `ErrNotExist` 면 `nil`). 다만 **처음 실패한 경로에서 멈추고** 나머지는 손대지 않는다 — 경로가
  디렉터리이거나, 같은 uid 의 명령이 `/tmp/opensandbox-execd` 를 보통 파일로 만들어 두면(`ENOTDIR`) 그 뒤의 `v1.1.0` 경로가
  남는다. 이때 execd 는 500 `RUNTIME_ERROR` 로 답한다(`controller/filesystem.go` 의 `RemoveFiles`). 그래서 한 번의 호출을
  execd 가 **오류 응답으로 거절하면** 경로마다 한 번씩 다시 부른다(그때만 HTTP 네 번이 더 든다). execd 가 호출을 처리하지
  않은 경우에는 다시 부르지 않는다: 전송 실패·요청 타임아웃, 502, 503·504. 502 는 `release-1.1.0` 서버 프록시
  (`server/opensandbox_server/api/proxy.py` 의 `_proxy_http_request`)가 execd 에 **연결하지 못했을 때** 내는 답이다
  (`httpx.ConnectError`·`ConnectTimeout` → 502 `BACKEND_CONNECTION_FAILED`). 연결이 거절되면 곧바로, 연결 타임아웃이면 프록시의
  타임아웃(180초) 뒤에 온다. 프록시는 503·504 를 스스로 내지 않는다. 503 은 runtime init 전의 execd(`release-1.1.0` 의
  `runtimeInitGate`)나 앞단 게이트웨이가, 504 는 앞단 게이트웨이가 낸다. 어느 경우든 execd 는 경로를 하나도 시도하지 않았다.
  그래서 지울 수 없는 경로가 있다는 근거가 없고, 경로별 호출 넷은 첫 호출을 받지 못한 같은 execd 에 닿을 뿐이다 — 잘해야 빠른
  실패 넷, 나쁘면 넷이 각각 타임아웃을 기다린다. 그 밖의 오류 응답(500 을 포함한 4xx·5xx)은 execd 가 경로를 거절한 것으로 친다.
  - **받아들인 대가**: 처리되지 않은 배치 호출은 다시 부르지 않으므로, 풀의 연결이 한 번 끊기는 것 같은 **일시적 I/O 오류
    한 번**에도 그 실행의 스풀은 그대로 남는다(warn 로그 하나). 훅·격리 실행 출력의 유일한 사본이 실행보다 오래 남을 수
    있다는 뜻이다. 다시 부르면 이 경우는 줄지만, execd 가 정말 답하지 않을 때 실행마다 기다림이 배로 는다. S1 은 best effort
    이므로 이 쪽을 골랐다.
  - **언제**: 종료 이벤트(`execution_complete`·`error`)를 받았거나 스트림이 끊긴 뒤 status 로 종료 코드를 읽었을 때, `await` 가
    결과를 돌려주기 **전에** 지운다. 그래서 `await` 가 끝나면 스풀도 없다(호출 하나에 HTTP 한 번이 더 든다). `await` 가 kill 에도
    답이 없는 명령을 포기한 경우에는 결과가 먼저 나가고, 리더 스레드가 스트림을 닫은 뒤 지운다.
  - 경로는 명령의 출력으로 움직일 수 없다: id 는 **첫** `init` 이벤트의 것만 쓴다(execd 가 출력을 JSON 문자열로 실으므로 출력이
    이벤트를 만들 수 없고, 혹시 두 번째 `init` 이 와도 무시한다). id 가 `[A-Za-z0-9_-]{1,128}` 이 아니면 경로를 만들지 않는다
    (execd 의 id 는 32자리 hex). `init` 전에 끝난 명령은 지울 것이 없다. 샌드박스가 사라진 경우(`SandboxNotFoundException`)에는
    시도하지 않는다.
  - 삭제는 `await` 의 시간 안에 든다. 명령이 시간 안에 끝났는데 삭제 때문에 결과가 늦으면, `await` 는 kill 하지 않고 타임아웃으로
    보고하지도 않으며 삭제를 요청 타임아웃만큼 더 기다린다. 그래도 끝나지 않으면 — 경로별 호출 넷이 각각 느리거나, 동시 호출
    상한(`max-concurrent-calls`)에 막혀 줄을 서는 경우 — 삭제 전에 기록해 둔 **명령의 실제 결과**(종료 코드, `timedOut=false`,
    캡처한 출력)를 보고하고, 삭제는 제공자 스레드에서 마저 돈다. 이 경우에만 `await` 가 돌아온 뒤에도 스풀이 잠깐 남는다.
    (끝나기 전의 status 읽기 실패처럼 명령이 실패로 정해졌다면 그 실패를 던진다.)
  - `await` 의 시간이 다 되어 kill 한 명령도 같다. kill 유예(`KILL_GRACE`, 8초) 안에 끝났지만 — kill 때문이든, 타임아웃 직후
    스스로든 — 삭제가 유예보다 오래 걸리면, 137 이 아니라 기록해 둔 실제 종료 코드를 `timedOut=true` 로 보고한다. 유예 안에
    삭제까지 끝난 경우와 같은 보고다: 호출자의 기다림은 명령이 끝나기 전에 이미 다했다. 137 은 유예 안에 끝나지 않은 명령에만
    남는다.
  - 삭제가 도는 동안 들어온 `kill()` 은 보내지 않는다(이미 끝난 명령에 대한 `DELETE /command` 는 500).
- **시험**: `OpenSandboxProviderTest` — 두 위치 모두 `await` 반환 시점에 없음, 비-transient 는 건드리지 않음, 타임아웃 kill ·
  끊긴 스트림 · 포기한 명령에서도 지움, 삭제 실패가 명령의 실패가 되지 않음, 지울 수 없는 경로 하나가 나머지를 남기지 않음,
  느린 삭제가 시간 안에 끝난 명령을 타임아웃으로 만들지 않음, 기다림보다 오래 걸리는 삭제에서도 실제 종료 코드·
  `timedOut=false` 를 보고하고 그때 삭제는 아직 시작하지 못함(호출 허가 하나를 서버가 붙든 create 가 쥐어, 삭제가 시계와
  상관없이 기다린다), kill 유예 안에 끝났지만 삭제가 유예보다 오래 걸린 명령도 137 이 아닌 실제 종료 코드를 보고함, 처리되지
  않은 삭제(요청 타임아웃, 502, 503)는 경로별로 다시 부르지 않음, 두 번째 `init` 이 스풀 경로를 바꾸지 않음, `SPOOL_ID` 에 맞지 않는 id 로는 아무 경로도
  지우지 않음. `SandboxShellIT` — 어느 실행이 transient 인가.
  docker 계층(`OpenSandboxWorkspaceIT.streamRunsLeaveNoSpoolAndAHugeLineIsRefused`, 실제 `execd:v1.1.0`) — 훅·격리·스트리밍·
  타임아웃 실행 뒤 `$TMPDIR` 아래 어느 파일에도 출력 표지가 없음. 대조군으로, 같은 샌드박스에서 transient 가 아닌 날 실행
  (`connection.run`)의 표지는 같은 검사에 **잡힌다** — 그래서 "없음" 이 execd 가 다른 곳에 스풀했기 때문이 아님을 보인다
  (`v1.1.0` 에서만 성립한다. `release-1.1.0` 이상은 스스로 지우므로 고정 버전을 올리면 대조군을 고친다).
  - **실행 기록**: docker 계층은 CI(`.github/workflows/build.yml` 의 `integration` 작업, `./gradlew --no-daemon
    integrationTest`, 태그 `docker`)에서 돈다. PR #45 의 head `2d67bd30` 에서 run 42147893077 이 42개 통과·1개 건너뜀
    (`OpenSandboxProviderContractTest.sharedVolumeDeletionIsIdempotentAndRefusedWhileMounted`, 의도된 건너뜀)으로 끝났고,
    이 IT 도 통과했다(대조군을 더하기 전). 다만 그때의 grep 은 패턴이 비어 있었다 — `"$m" + id` 가 셸에서 `$m<id>` 라는 정의되지
    않은 변수 하나로 펼쳐졌다. 그래서 그 "0" 은 "표지가 없다" 가 아니라 "`/tmp` 아래 비어 있지 않은 파일이 없다" 는 뜻이었다
    (더 엄격한 조건이라 결과는 여전히 성립한다). `0d617b6` 에서 `${m}` 으로 고쳤다. PR #46 의 head `ec7729d` 에서 run
    42157359684 의 `integration` 작업이 대조군을 포함해 통과했다(42개 통과·1개 건너뜀, 건너뜀은 위와 같다). 대조군은 같은 시험
    메서드 안에서 건너뛸 조건 없이 표지 검사보다 먼저 돌므로, 시험이 통과했다는 것은 대조군도 돌고 맞았다는 뜻이다. 경로별 재시도와 execd 가 처음 실패한 경로에서 멈추는 동작, `release-1.1.0` 이상의
    배치는 실제 execd 로 시험하지 않았다(IT 가 경로를 지울 수 없게 만들지 않고, docker 계층은 `v1.1.0` 고정).

## 4. S2 — SSE 이벤트 줄의 상한 (구현)

- `SseEvents` 는 `readLine()` 대신 8 KiB 청크를 직접 훑어 줄을 만든다. 줄이 `MAX_LINE_BYTES`(256 KiB)를 넘는 순간
  `LineTooLongException` 을 던진다 — 그 줄은 최대 256 KiB 만 메모리에 있었다. 줄 구분은 예전과 같다(`\n`, `\r`, `\r\n`. 빈 줄과
  JSON 이 아닌 줄은 건너뛴다. 끝의 개행 없는 줄도 이벤트다).
- `OpenSandboxRunningCommand` 는 그 예외에서 경고 로그를 남기고, stdout·stderr 를 **둘 다** 잘림으로 표시하고(어느 스트림의
  줄이었는지는 읽지 않은 부분에 있다), `kill()` 로 `DELETE /command?id=` 를 보낸 뒤, 종료 코드를 `/command/status/{id}` 에서
  읽는다. kill 은 SIGTERM 뒤 3초에 SIGKILL 이므로 보통 137 이 된다. S1 의 삭제도 그 뒤에 돈다.
- `SandboxShell` 쪽에서는 트레일러가 오지 않은 stream 실행이므로 `ShellExecutionException("the sandbox shell wrapper failed (exit
  137)…")` 이 된다. **원인(줄 상한)이 메시지에 실리지 않는다** — `ExecOutcome` 에 그런 필드가 없다. PTC 엔진이 이 경우를
  `PROTOCOL_VIOLATION` 으로 따로 다루려면 SPI 에 "제공자가 끊었다" 표지가 필요하다(§10 Q2).
- **출력을 숨기는 데 쓸 수 없다.** 명령이 일부러 긴 줄을 내면 그 뒤의 출력을 잃지만, 결과는 조용한 성공이 아니라 잘림 표시와
  137 이다. 같은 uid 의 공존 프로세스가 스풀에 긴 줄을 덧붙여 실행을 끝내게 하는 것은 셸 엔진 설계 §12.1 의 R7(실행 방해, DoS)과
  같은 무게이고, 그 프로세스는 어차피 명령을 kill 할 수 있다.
- **동작 변화**: 줄 하나가 256 KiB(JSON 바이트)를 넘는 출력을 내는 훅·격리·스트리밍 명령은 이제 끝까지 돌지 못하고 실패한다.
  예전에는 JVM 이 그 줄 전체를 메모리에 올린 뒤 캡처 상한에서 잘랐다. 모델의 포그라운드·백그라운드 명령은 출력이 `run.out` 으로
  가므로 영향이 없다.
- **`Grep` 도 영향을 받는다.** `SandboxContentSearch` 는 `rg --json` 을 보통 `ExecSpec` 으로 돌리고 그 출력을 exec 스트림으로
  받는다. 그래서 일치한 줄이 256 KiB(JSON 바이트 — Go 의 `encoding/json` 은 `<`·`>`·`&` 를 6바이트 `\u00XX` 로 적으므로 HTML
  같은 내용은 훨씬 짧은 원문에서 닿는다) 이상이면 — 압축된 번들, 한 줄짜리 JSON, `-a` 로 훑는 바이너리 — `rg` 가 kill 되고
  두 스트림이 잘림으로 표시되어 `search` 가 실패한다. 코어의 `GrepTool` 은 그때 파일 시스템 순회로 물러서므로 결과는 맞고
  느려질 뿐이다. 예전에는 그 줄이 결과로 왔다. (`rg --max-columns` 로 줄을 줄이는 길도 있으나 지금은 바꾸지 않았다.)
- **시험**: `SseEventsTest`(줄 구분, 상한 경계, 끝없는 줄이 `상한 + 청크` 이상 읽히지 않음, 읽기 경계가 줄·`\r\n`·멀티바이트
  문자 가운데에 떨어져도 같은 결과, 여러 번의 읽기에 걸친 줄의 상한을 바이트로 셈), `OpenSandboxProviderTest`(kill,
  잘림 표시, 그 줄이 sink 에 가지 않음), docker 계층(약 300 KiB 한 줄 → 타임아웃이 아닌 실패, 20초 안에).

## 5. S7 — isolated 스트리밍은 포그라운드가 아니다 (구현)

`SandboxShell` 은 이미 "포그라운드 = `!background && !hook && !isolated`" 로 동작한다(`runCommand` 의 스트리밍 거절 조건과
`plan` 의 `Mode`). `SandboxShellIT` 에 계약 테스트 둘을 더했다:

- `isolatedStreamingIsNotForeground` — `isolated(true)` 만(훅·백그라운드 아님)으로 리스너를 주면 거절되지 않고, stdout·stderr 가
  리스너로 오고, 모델의 포그라운드 명령이 셸 잠금을 쥐고 있어도 기다리지 않고, 세션의 cwd·export 를 읽지도 저장하지도 않으며,
  종료 코드를 돌려준다.
- `isolatedStreamingWithARunDirectory` — 셸 엔진 설계 §5 의 모양(`isolated(true)` + `runDirectory` + 리스너 + 취소). 줄이 실행
  도중에 오고, 취소가 `ShellCancelledException` 이 되고, 실행 파일이 남지 않는다.

반대쪽(`background·hook·isolated` 가 모두 거짓인 스트리밍은 거절)은 기존 `foregroundStreamingIsRefused` 가 지킨다.

## 6. S3 — `python3` 선택 항목 (문서 초안)

### 6.1 이미지 계약에 넣을 문장 (초안 — `workspace-sandbox.md` §13.3 에 들어갈 것)

> **선택: `python3` (3.8 이상).** 프로필이 `python3` 런타임을 선언한 경우에만 요구한다. 선언하지 않은 이미지는 지금처럼 유효하다.
> 선언하면 seed 가 확인하고(step `runtime-python3`), 서술자의 런타임 능력에 실려 코어의 셸 엔진이 `ExecuteCode` 를 낼 수 있게
> 된다. 요구하는 것: `PATH` 에서 찾히는 `python3`, 버전 3.8 이상, `-I`(격리 모드)로 표준 라이브러리가 import 될 것 — 셸 엔진
> 설계 §6 의 런타임은 표준 라이브러리(`asyncio`, `hmac`, `hashlib`, `json`, `base64`, `tempfile`, `os`, 그리고 Linux 에서
> `prctl` 을 부를 수단)만 쓴다는 전제다. 런타임이 코어에서 확정되면 이 목록을 그쪽에 맞춘다. `site-packages` 는 쓰지 않는다(`-I` 가 끈다). 런타임은 실행 디렉터리(`/workspace/.aimon-shell/.run.*`)에서 돌므로
> `/tmp` 가 쓰기 가능할 필요는 없지만, 모델 코드 출력의 캡처 파일을 `tempfile.mkdtemp()` 로 만들므로 **`TMPDIR` 또는 `/tmp` 가
> 샌드박스 사용자에게 쓰기 가능해야 한다.**
>
> execd 는 `TMPDIR` 을 두지 않은 채 띄운다(§6.1 의 스풀 삭제가 `/tmp` 를 가정한다 — [PTC 선행 작업](ptc-shell-prerequisites.md) §2.1).

마지막 문장은 python 과 무관하지만 S1 이 기대는 가정이라 같은 자리에 둔다.

### 6.2 프로필 선언 (형태는 코어 SPI 확정 뒤)

- 후보: `SandboxProfile.runtimes(Set<String>)` 또는 `runtimeCapabilities`, 값은 코어가 정할 능력 이름(`python3`). 속성 키
  `aimon.sandbox.profiles[n].runtimes: [python3]`. `contentHash` 에 들어간다(선언이 바뀌면 다시 seed 한다).
- 코어 서술자의 런타임 능력 타입(문자열 집합인지, 버전을 실은 값인지)이 정해지면 그 모양을 따른다. 버전까지 실린다면 seed 가
  읽은 실제 버전(`3.11.4`)을 서술자에 싣는 것도 가능하지만, 셸 엔진 설계는 서술자를 "프로필에서 정해지는 정적 값" 으로 두므로
  선언값(`python3`)만 싣는다.

### 6.3 seed 확인 방식 (제안)

선언된 경우에만 seed 스크립트에 다음을 붙인다. 다른 검사처럼 영구 실패(FAILED, step `runtime-python3`)다 — 이미지가 계약을
어긴 것이므로 다시 시도해도 같다.

```sh
command -v python3 >/dev/null 2>&1 || fail runtime-python3 'the profile declares python3 but the image lacks it'
python3 -I -c 'import sys, asyncio, hmac, hashlib, json, base64, tempfile; sys.exit(0 if sys.version_info >= (3, 8) else 3)' \
  >/dev/null 2>&1; r=$?
[ "$r" = 3 ] && fail runtime-python3 "python3 is older than 3.8 ($(python3 -I -c 'import platform; print(platform.python_version())' 2>&1))"
[ "$r" = 0 ] || fail runtime-python3 'python3 -I cannot import its standard library'
```

- 셸 엔진 설계 §4 의 "탐침"(환경 객체당 첫 `ExecuteCode` 에서 `python3` 3.8+ 확인)은 그대로 둔다. seed 확인은 프로비저닝 때 한 번,
  탐침은 환경 객체마다 한 번이다. seed 를 통과한 이미지에서 탐침이 실패하는 경우는 샌드박스 안에서 누가 `python3` 을 지웠을
  때뿐이다.
- 선언하지 않은 프로필에서는 아무것도 확인하지 않는다 — 이미지에 python 이 있어도 서술자에 싣지 않는다. 셸 엔진 설계 §10 의
  "선언하지 않은 샌드박스에서 `auto` 는 `ExecuteCode` 를 내지 않고 그 이유를 운영자에게 알린다" 는 코어의 일이다.

## 7. S4 — 비밀 파일 표지 (방향, 코어 배포 뒤)

코어가 `RunDirectory` 에 파일 단위 `secret` 표지를 더하면:

- `SandboxShell.plan` 에서 표지된 파일이 하나라도 있으면 실행 파일 **전부를** 업로드로 보낸다(`filesUploaded = true`). 지금도
  업로드는 파일 수와 상관없이 manifest 와 blob 두 개이므로 분리할 이유가 없다. 표지된 파일만 따로 올리는 길은 업로드가 셋이 되고
  래퍼의 단계가 늘어난다.
- 그러면 스크립트(= execd 의 `bash -c` 인자 = `/proc/<pid>/cmdline`)에는 비밀의 base64 대신 manifest 와 blob 의 SHA-256 만
  남는다. 32바이트 무작위 키의 SHA-256 은 키를 드러내지 않는다.
- 업로드 원본 삭제는 이미 된다(§2.4). 비밀이 업로드되어 래퍼가 `cat` 하고 `rm` 하기까지의 창은 남는다(셸 엔진 설계 §16 Q2).
  업로드 경로(`/workspace/.aimon-shell/<shell>/run-<hex>.blob`)는 예측하기 어렵지 않으므로 이름을 숨기는 것은 방어가 아니다.
- 실행 파일 합계가 `ARGUMENT_BUDGET` 이하라는 이유로 인라인하던 경우의 "업로드 실패 → `ShellExecutionException`" 경로가 비밀 파일
  때문에 새로 열린다. 테스트: 작은 비밀 파일이 인라인되지 않음(`RecordingProvider` 로 `ExecSpec.command()` 에 base64 가 없음),
  docker 계층에서 실행 중 어떤 `/proc/*/cmdline` 에도 키가 없음(셸 엔진 설계 §14).

## 8. S5 — `RuntimeFileSystem` (방향, 코어 배포 뒤)

`SandboxExecutionEnvironment.runtimeFileSystem()` 이 `Optional.of(new SandboxRuntimeFileSystem(...))` 를 돌려준다. 연결은
`SandboxFileSystem` 처럼 첫 호출에서 한다(제안 시점에 프로비저닝하지 않는다 — 셸 엔진 설계 §4).

| 연산 | 구현 | 주의 |
|---|---|---|
| `writeNew(path, bytes)` | `SandboxFiles.write(abs, …, CREATE_NEW)` — 임시 이름 업로드 + `ln`. `FileAlreadyExistsException` 을 코어의 "이미 있음" 으로 옮긴다 | `ln` 은 `/command` 도우미다. 그 도우미의 스풀은 출력이 없으므로 `transientOutput` 은 필요 없다. 임시 파일(`.<name>.aimon-tmp-<uuid>`)은 대상 옆에 있어 같은 uid 가 `ln` 전까지 읽을 수 있다(R2 와 같은 무게). 부모 경로 성분의 심볼릭 링크는 따라간다 — 응답 디렉터리를 같은 uid 가 링크로 바꿔치면 다른 곳에 쓴다. HMAC 이 무결성을 지키므로 기밀성(R2)만의 문제다 |
| `read(path, maxBytes)` | 도우미 한 번: `[ -L "$1" ] && exit 40; [ -f "$1" ] || exit 41; head -c "$2" -- "$1"` 처럼 링크와 비정규 파일을 거절하고 상한까지 읽는다 | execd 의 `/files/download` 는 `os.Open` 으로 링크를 따라가고 `/files/info` 도 lstat 인지 확인되지 않았다. 도우미 안의 검사도 TOCTOU 다 — "링크를 따라가지 않음" 을 엄밀히 하려면 `python3`/`dd` 의 `O_NOFOLLOW` 가 필요하다. 출력이 stdout 으로 가므로 이 도우미는 `transientOutput(true)` 여야 한다 |
| `delete(path)` | `SandboxFiles.delete(abs, recursive)` (`/files` 또는 `/directories` 의 DELETE) | `/directories` 는 Go `os.RemoveAll` — 링크를 따라 들어가지 않는다 |
| `createDirectories(path)` | `SandboxFiles.createDirectories` | |

- **루트**: `/workspace` 로 둔다. `.aimon-ptc/<128비트>/` 는 `/workspace/.aimon-ptc/...` 가 된다. `/tmp` 는 이미지 계약 밖이고,
  testkit 의 files API 가 `/workspace` 만 번역한다([실행 디렉터리 설계](workspace-sandbox-run-directory.md) 의 `PRIVATE_BASE`
  기각 이유와 같다). 런타임 FS 의 상대 경로가 루트를 벗어나면(`..`, 절대 경로, NUL) 거절한다.
- **경로 규칙 (S9, 구현)**: 셸 엔진 설계 §5 는 `.aimon-ptc/` 를 모델 파일 도구에게 `DENY` 로 둔다.
  `SandboxFileSystem.RULES` 에 `PathRule.deny("workspace/.aimon-ptc")` 를 더했다(코어 0.3.4 의 `PathRule.deny`). 읽기·쓰기·목록에서
  보이지 않고, 셸은 그대로 읽고 쓴다(`SandboxFileSystemTest`). 운영자용 `SandboxWorkspaceFiles` 는 최상위 `.aimon-*` 를 이미 읽기
  전용으로 둔다. **`Grep` 은 막지 않는다**: 코어의 `GrepTool` 은 환경의 `contentSearch()` 가 있으면 경로 규칙을 거치지 않고 그
  결과를 쓰고, `SandboxContentSearch` 는 샌드박스 안에서 `rg --hidden --no-ignore` 를 돌린다. `.aimon-staged`·`.aimon-shell` 도
  지금 같은 처지다. 무결성은 HMAC 에 기대므로(셸 엔진 설계 §5) 막을 일은 아니지만, 응답 내용(R2)이 `Grep` 결과에 나올 수 있다 —
  §10 Q4.
- **탐침**(셸 엔진 설계 §4): 런타임 FS 로 쓴 프로브 파일을 셸의 `test -f` 로 본다 — 같은 `/workspace` 를 보므로 성립한다.
  testkit 에서도 성립한다(`LocalConnection` 이 `/workspace` 를 번역한다).
- **정리**: 엔진이 환경 객체당 첫 실행에서 `.aimon-ptc/` 아래 오래된 항목을 지운다(셸 엔진 설계 §8). 샌드박스 쪽 스윕은 더하지
  않는다 — seed 의 `rm -rf /workspace/.tmp-*` 와 같은 것을 `.aimon-ptc` 에 둘지는 §10 Q3.
- 계약 스위트: 코어의 `RuntimeFileSystem` 계약 스위트(로컬 구현용)를 testkit 프로바이더와 docker 계층에서 돌린다.

## 9. S6 — 서술자 (방향, 코어 배포 뒤)

- `SandboxExecutionEnvironmentProvider` 가 서술자를 만들 때 `isolation(SANDBOXED)` 를 늘 싣는다. 조건이 없는 상수다 — 이 환경의
  셸·파일은 언제나 샌드박스 안이다.
- 런타임 능력은 프로필의 선언(§6.2)에서 온다. seed 결과에서 오지 않는다 — 서술자는 제안 시점에 프로비저닝 없이 정해져야
  한다(셸 엔진 설계 §4). seed 가 선언을 확인하므로, 선언했는데 이미지에 없는 경우는 첫 셸·파일 호출에서 FAILED 로 드러난다.
- `ShellFeature` 선언(`STREAMING`·`RUN_DIRECTORY`·`ISOLATED_EXECUTION`)은 이미 있다.
- `EnvironmentDescriptor.unavailable(...)` 경로(샌드박스를 줄 수 없는 경우)에는 `SANDBOXED` 를 싣지 않는다 — 코어가 그 환경에
  셸 엔진을 제안하지 않게.

## 10. 열린 질문

- **Q1 — execd 의 `TMPDIR`.** 프로바이더가 `/tmp` 를 가정한다(§2.1). 운영자가 execd 에 `TMPDIR` 을 줄 수 있다면 프로바이더 설정
  (`execd-temp-directory`)으로 받을지, 이미지 계약 문장(§6.1)으로 막을지. `release-1.1.0` 이상만 지원하게 되면 S1 의 삭제 자체가
  필요 없어진다(execd 가 지운다) — docker 계층의 고정 버전을 올리는 일과 함께 정한다.
- **Q2 — 제공자가 끊은 실행의 표지.** S2 로 끝난 실행은 셸에서 "래퍼 실패(exit 137)" 로 보인다. PTC 엔진이 이것을 위반으로 다루려면
  `ExecOutcome` 에 사유(`aborted: LINE_TOO_LONG`)를 싣고 코어 셸 계약에 그 결과의 모양을 정해야 한다. 셸 엔진 설계 §3 의 "스트리밍
  제공자는 줄을 물질화하기 전에 상한을 건다" 를 코어 셸 계약에 올릴 때 함께 정할 것.
- **Q3 — `.aimon-ptc` 의 샌드박스 쪽 스윕.** 엔진의 스윕은 환경 객체의 첫 실행에서만 돈다. 오래 쓰지 않은 워크스페이스에는 응답
  파일이 남는다(HMAC 된 도구 결과 — R2 의 잔여물). seed 에서 지울지.
- **Q4 — `Grep` 과 경로 규칙.** 코어가 `contentSearch()` 결과에도 경로 규칙을 적용할지(코어 쪽 한 곳), 이 저장소가 `rg` 에
  `DENY` 접두어를 `--glob '!…'` 로 넘길지(사용자 glob 과의 순서를 정해야 한다). 셸 엔진이 들어오기 전에 정한다(§8).
