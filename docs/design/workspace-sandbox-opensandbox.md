# 워크스페이스 샌드박스 — OpenSandbox 프로바이더

OpenSandbox 를 어떻게 부르고, 서버가 실제로 어떻게 동작하며, 그 동작이 설계를 어떻게 제약하는지 다룬다. 프로바이더가 지키는
계약 자체는 [프로바이더](workspace-sandbox-provider.md) 문서가 정하고, 이 문서는 OpenSandbox 가 그 계약을 지키는 방식을 적는다.

> 관련: [프로바이더](workspace-sandbox-provider.md) · [설정](workspace-sandbox-configuration.md) · [보안](workspace-sandbox-security.md) · [수명](workspace-sandbox-lifecycle.md) · [OpenSandbox 를 Kubernetes 에 설치하기](../guide/opensandbox-kubernetes.md)

서버 동작 사실의 표기: 지원 버전(1.2.0)에서 확인한 것은 현재형으로 쓴다. 1.1.x 에서만 잰 사실은 설계를 이해하는 데 필요한
것만 옮기고 **(1.1.x 에서 잼, 1.2.0 미확인)** 표지를 단다. 1.1.x 실측 원자료(보고서 · 프로브 · 원본 출력)는
[`spike/opensandbox/`](../../spike/opensandbox/) 에 있다.

## 1. 지원 버전과 접근 방식

**지원 버전은 OpenSandbox 1.2.0 이다** — 서버 · execd · egress 사이드카 모두. docker 테스트 계층은 이 버전의 이미지를 digest 로
고정해 PR 마다 돌고, k8s 테스트 계층도 같은 버전의 차트와 이미지를 쓴다([테스트 §3](workspace-sandbox-testing.md)). 1.2.0 보다
오래된 서버에는 자격 증명 vault 경로가 없으므로, 자격 증명을 쓰는 `create` 는 서버가 1.2.0 보다 오래되었다고 말하며 영구
실패한다.

**OpenSandbox SDK 를 쓰지 않고 REST API 를 JDK `HttpClient` 와 Jackson 으로 직접 부른다.** Kotlin SDK 는 명령 출력을 상한 없이
모아 블로킹하고, `kill()` 에 필요한 명령 id 를 콜백으로만 주며, OkHttp 와 Kotlin 표준 라이브러리를 끌고 온다. 쓰는 REST 표면은
수명 API 몇 개와 execd 의 `/command` · `/command/status` · 파일 API 뿐이다.

클라이언트는 둘이다. 수명 클라이언트는 서버의 `/v1/sandboxes…` 를 `OPEN-SANDBOX-API-KEY` 헤더로 부른다. execd 클라이언트는
연결마다 하나이고, 서버의 엔드포인트 API 가 준 주소와 헤더로 샌드박스 안 execd 를 부른다(§3.2). 자격 증명 vault 는 서버 경로로
부른다(§3.5).

모듈(`aimon-sandbox-opensandbox`, Java 17)의 공개 표면은 `OpenSandboxProvider` · `OpenSandboxProviderConfig` ·
`CredentialDefinition` · `VolumeReclaimer` 뿐이고, HTTP 를 말하는 나머지(수명 · execd · vault 클라이언트, SSE 파서, 에러 분류,
재시도)는 패키지 안에 둔다. OpenSandbox 설정 키도 이 모듈이 소유한다(§5). `aimon-sandbox` 는 OpenSandbox 를 모르고
`ProviderCapabilities` 만 본다 — 모듈 경계는 [개요 §5.1](workspace-sandbox.md).

## 2. capability 대응

| capability | 광고 조건 | OpenSandbox 대응 |
|-----------|----------|-----------------|
| `EXEC` | 늘 | execd `/command`(cwd · envs). 세션 API(`RunInSession`)는 쓰지 않는다 — 이유는 [셸 §1](workspace-sandbox-shell.md). 백그라운드 모드도 쓰지 않는다(§3.2) |
| `FILES` | 늘 | execd files API. `stat` 의 mtime 은 나노초이고 해시 · etag 는 없다 — mtime 만으로 변경 감지 계약([프로바이더 §3.1](workspace-sandbox-provider.md))을 지킨다. 쓰기는 임시 이름에 올린 뒤 옮긴다(§4.6). 공유 볼륨(NFS)의 mtime 해상도는 공유 볼륨과 함께 다시 본다(계획 — 구현 단계 5) |
| `EXPIRY` | 늘 | 생성의 `timeout`(상대 초, 최소 60)과 `renew-expiration`(절대 시각). 서버 상한 `max_sandbox_timeout_seconds` 는 API 로 읽을 수 없어 `maxExpiry` 로 선언한다. 만료는 즉시 삭제이고 idle 타임아웃이 아니다. 서버가 지키지 않는 규칙은 프로바이더가 지킨다(§3.1, §4.3) |
| `PAUSE_RESUME` | 광고하지 않는다 (계획 — 구현 단계 7) | pause/resume API. `pause` · `resume` 은 지금 `UnsupportedOperationException` 이다. 서버 동작은 §3.1 |
| `SHARED_VOLUME` | `volumeReclaimer` 를 주입했을 때 | 생성은 `volumes[].pvc.createIfNotExists`(기본 access mode 가 `ReadWriteOnce` 이므로 RWX 를 명시). 서버에 삭제 API 가 없어 `VolumeReclaimer`(K8s API 또는 Docker 볼륨 API)가 `SharedVolumes` 가 된다. 내장 구현은 **(계획 — 구현 단계 5)**. 서버 동작은 §3.4 |
| `EGRESS_POLICY` | `egressEnforcement: DNS_NFT` 이고 runtime class 가 gVisor 가 아닐 때 | `networkPolicy` + `defaultAction: deny`. `dns` 모드는 IP 로 새므로 받되 광고하지 않는다. gVisor 와 `dns+nft` 의 조합은 설정 오류다(§5). 기동 시점에는 모드를 알 수 없어 선언으로 광고하고, 샌드박스가 생긴 뒤 `verify` 가 실제 모드를 대조한다(§4.9). Docker 런타임은 서버가 `network_mode = "bridge"` 여야 정책을 받는다([설정 §5](workspace-sandbox-configuration.md)) |
| `CREDENTIAL_INJECTION` | `dns+nft` 이고 `credentials` 가 비어 있지 않을 때 | credential vault. 범위는 scheme · host · method · path(glob)다. port 는 겹침 검사에서 무시한다 — 이유는 [보안 §5](workspace-sandbox-security.md). 바인딩은 생성 뒤 서버 경로로 **프로파일이 이름으로 부른 것만** 넣는다(§3.5, §4.1) |
| `NETWORK_ISOLATION` | `networkIsolation: DECLARED` (kubernetes 런타임만) | OpenSandbox 는 강제하지 않는다. 운영자가 NetworkPolicy 를 걸고 선언한다. seed 가 `controlPlaneEndpoints` 로의 연결이 실패하는지 탐침한다([수명 §3](workspace-sandbox-lifecycle.md)). Docker 런타임은 선언할 수 없다(§3.6) |
| `RUNTIME_CLASS` | `runtimeClass` 를 설정했을 때 (kubernetes 런타임만) | 요청마다 지정할 필드가 없다. 서버 설정(`[secure_runtime].k8s_runtime_class`)이나 서버의 BatchSandbox 템플릿으로만 정해진다. 그래서 프로바이더 설정이 서버의 class 를 선언하고, 프로파일의 `runtimeClass` 는 그 이름과 같아야 한다(기동 시 검사, [설정 §3](workspace-sandbox-configuration.md)). 없는 class 는 서버 기동 실패(설정)나 60초 뒤 504(템플릿)로 드러난다 |
| `HARDENED_SECURITY_CONTEXT` | `hardenedSecurityContext: DECLARED` (kubernetes 런타임만) | 요청으로는 지정할 수 없다. 서버의 BatchSandbox 템플릿이 **샌드박스 컨테이너의** securityContext 로 건다(§3.7). seed 가 결과(uid)를 다시 검사한다. Docker 런타임은 root 로만 돌아 선언할 수 없다 |
| `SNAPSHOT` · `FORK` | 광고하지 않는다 (계획 — 구현 단계 8) | snapshot API(fork 는 snapshot 위에 조립). 1.1.x 에서도 실측하지 않았다 |

## 3. 서버 동작

### 3.1 수명 · 라벨 · 생성

- **API 모양.** 경로는 `/v1/...`, 인증 헤더는 `OPEN-SANDBOX-API-KEY`, 생성은 `202` 다. 상태는 `Pending` · `Running` ·
  `Pausing` · `Paused` · `Resuming` · `Stopping` · `Terminated` · `Failed` 여덟이다. 에러 코드에는 런타임 접두어가 붙고
  (`DOCKER::SANDBOX_NOT_FOUND`), 다른 런타임에서도 그 접두어가 나온다 — 그래서 프로바이더는 HTTP 상태로 분류한다(§4.7).
- **생성.** K8s 런타임의 생성은 블로킹이다. 파드가 Running 이 되고 IP 를 받을 때까지 최대 60초 붙잡고, 넘으면
  `504 KUBERNETES::POD_READY_TIMEOUT` 과 함께 BatchSandbox 를 지운다(롤백). Docker 런타임은 `Pending` 상태로 곧바로 돌아온다.
  `resourceLimits` 가 없는 요청은 런타임과 상관없이(풀 모드 제외) 422 로 거부된다(1.2.0 소스로 확인) — 프로바이더는 늘 보낸다(§4.1).
- **라벨.** `aimon.at/*` 키를 받아들이고 `opensandbox.io/` 는 예약이다. 값은 K8s 라벨 규칙으로 검사한다(`:` · `/` · 64자 →
  400 `SANDBOX::INVALID_METADATA_LABEL`). [프로바이더 §4.3](workspace-sandbox-provider.md) 의 32자 base32 는 통과한다. 목록
  필터는 대소문자를 구분하므로 소문자로 보낸다. K8s 에서 `PATCH /metadata` 는 CR 의 라벨만 바꾸고 pause 중에는 파드가 없다 —
  그래서 조정은 파드가 아니라 API 를 본다(1.2.0 소스로 확인).
- **목록.** 필터는 `metadata` 파라미터 **하나**에 `k=v&k=v` 를 담는다(반복하면 마지막 것만 쓰인다). 조건은 AND 다. 페이지 번호
  방식(한 페이지 최대 200, 커서 없음, 최신순)이라 목록 도중의 생성이 페이지를 민다 — 조정은 중복과 누락을 견딘다
  ([수명 §6.2](workspace-sandbox-lifecycle.md)). K8s 런타임은 지운 샌드박스를 파드가 사라질 때까지 `Stopping` 으로 나열한다.
- **멱등 키가 없다.** 같은 라벨로 두 번 만들면 둘이 생긴다. 그래서 생성은 최선 노력 멱등이다
  ([프로바이더 §4.2](workspace-sandbox-provider.md)).
- **삭제.** 없는 샌드박스의 `DELETE` 는 404 다.
- **만료.** 만료에 이른 샌드박스는 상태를 남기지 않고 즉시 지워진다(CR 과 파드가 초 단위로). `timeout` 을 빼면 샌드박스는
  만료되지 않는다. Docker 런타임에서 서버가 내려가 있던 사이의 만료는 재기동 때 집행된다. K8s 런타임에서는 컨트롤러가 집행한다
  (1.2.0 소스로 확인). 서버는 만료 시각을
  마이크로초까지만 저장한다.
- **renew.** `renew-expiration` 은 절대 시각을 받아 미래인지만 보고, 만료를 줄이거나 서버 상한을 넘기는 요청도 받으며
  (상한은 생성 때만 검사), 상한을 알려 주는 API 가 없다(1.2.0 소스로 확인). 그래서 프로바이더가 앞으로만 · 상한을
  지킨다(§4.3).
- **pause (계획 — 구현 단계 7).** 두 런타임 모두 일시 정지 중에도 만료 시각이 흐른다. Docker 는 컨테이너 동결(프로세스와
  파일이 남는다)이다. K8s 는 루트 파일 시스템을 레지스트리 이미지로 커밋하고 파드를 지웠다가, resume 때 같은 이름 · 새 IP 의 새
  파드로 띄운다 — 파일은 남고 프로세스는 사라지며, 스냅숏 레지스트리가 필요하다(여기까지 1.2.0 소스로 확인). 1.1.x 의 K8s 에서는
  멈춘 샌드박스를 renew 하면 컨트롤러가 원래 이미지로 파드를 다시 만들어 샌드박스가 `Failed` 가 되고 resume 은 409 였다(업스트림
  버그). 1.2.0 의 컨트롤러는 `Paused` 인 샌드박스의 renew 를 새 pause 요청으로 보지 않고 파드도 만들지 않는다(1.2.0 소스로 확인).
  Docker 에서 pause 중의 execd 호출은 실패하지 않고 멈춘다(1.1.x 에서 잼, 1.2.0 미확인). 이 사실에서 나온 규칙 — pause 전에 만료를
  민다, 멈춘 샌드박스에 `extendExpiry` 를 부르지 않는다 — 은 [수명 §5](workspace-sandbox-lifecycle.md)가 소유한다(확인 필요: 1.2.0
  에서 멈춘 샌드박스의 renew 가 파드를 다시 만들지 않으므로 두 규칙을 다시 볼지).

### 3.2 명령 실행

- **도달 경로.** execd 는 `GET /v1/sandboxes/{id}/endpoints/44772` 가 준 주소로 부르고, 응답에 `headers` 가 있으면 함께 보낸다.
  클러스터 밖에서는 `?use_server_proxy=true` 로 서버 프록시를 거친다. K8s 에서 resume 뒤에는 주소가 바뀐다 — 연결은 주소를
  캐시하되 연결 실패 한 번에 다시 해석한다(§4.4).
- **명령.** 명령 문자열은 `bash -c` 의 인자 하나로 돈다. 인자 하나의 한도와 그에 맞춘 래퍼의 경계는
  [셸 §2](workspace-sandbox-shell.md)가 다룬다. 없는 작업 디렉터리는 400 이다(1.2.0 소스로 확인).
- **출력 이벤트.** 출력은 줄 하나에 이벤트 하나로 온다. 1.2.0 의 execd 는 이벤트에 그 줄의 줄 끝(`\n`, `\r`, 또는 명령이 끝맺지
  않은 마지막 줄이면 없음)을 그대로 실으므로, 이벤트를 이어 붙이면 **바이트 그대로의 출력**이다. 진행 표시의 `\r` 도 남는다.
  옛 execd 는 줄 끝을 떼어 보내고 빈 줄을 `"\n"` 이벤트로 보냈다. 프로바이더는 어느 쪽인지 이벤트에서 읽어 옛 execd 의 줄에는
  `\n` 을 되살린다(§4.4). 잘못된 UTF-8 은 U+FFFD 로 온다 — execd 가 출력을 JSON 텍스트로 싣기 때문이고, 1.2.0 에서도 그렇다.
  그래서 SPI 의 정규화 허용과 셸의 캡처 상한이 필요하다([프로바이더 §2.3](workspace-sandbox-provider.md)).
- **줄에 상한이 없다.** execd 는 줄 하나를 상한 없이 모아 이벤트 하나로 보낸다. 이벤트 줄은 JSON 이스케이프 때문에 출력 줄보다
  길다(최대 6배). 그래서 프로바이더가 받은 JSON 바이트로 줄 상한을 둔다([프로바이더 §2.4](workspace-sandbox-provider.md), §4.4).
- **스풀.** execd 는 출력에 상한을 두지 않고, 명령의 출력을 샌드박스 안 `$TMPDIR/opensandbox-execd/<id>.stdout` · `.stderr` 에
  쌓는다. 디렉터리는 execd 의 euid 소유 `0700`, 파일은 `0600` 이다. 1.2.0 의 execd 는 명령이 끝나면(성공 · 실패 · 타임아웃 ·
  interrupt 모두) 그 스풀을 스스로 지우고, 그 밖에 한 시간마다 24시간 지난 스풀을 치운다. `0700` 디렉터리는 훅을 다른 uid 로
  돌릴 때 유리한 사실이다([훅 신원 기록](workspace-sandbox-hook-identity.md)). 프로바이더가 이 스풀을 다시 지우는 이유는 §4.5.
- **`$TMPDIR`.** 스풀 위치의 `$TMPDIR` 은 execd 프로세스의 것이고(비어 있으면 `/tmp`), 명령의 환경과 다를 수 있다. files API 는
  경로의 `$VAR` 를 펼치지만 정의되지 않은 변수는 거절하므로, 프로바이더는 `$TMPDIR` 을 `/tmp` 로 가정한다. execd 를 `TMPDIR`
  없이 띄우는 것은 배포 계약이다([설정 §4](workspace-sandbox-configuration.md)).
- **kill.** `DELETE /command?id={init 이벤트의 id}` 가 프로세스 그룹에 SIGTERM 을, 3초 뒤 SIGKILL 을 보낸다. 명령의 `timeout` 은
  곧바로 SIGKILL 이다(1.2.0 소스로 확인). execd 는 프로세스 그룹까지만 끝내고 세션이나 cgroup
  단위로 끝내지 않는다 — 그룹 밖 자손의 처리는 [셸 §6](workspace-sandbox-shell.md).
- **연결을 끊어도 명령은 죽지 않는다.** 명령의 수명이 HTTP 요청과 무관하다. 그래서 프로바이더의 `kill()` 과 `await` 초과는 반드시
  `DELETE` 를 보낸다(§4.4). 스트림이 도중에 끊기면 `GET /command/status/{id}` 로 종료 코드를 읽는다. status 는 종료 코드를 24시간
  보존한다(1.2.0 소스로 확인).
- **종료 코드.** 0 이면 `execution_complete` 이벤트만 온다. 그 밖에는 `error` 이벤트다. 1.2.0 의 execd 는 숫자
  `error.exit_code` 를 싣는다. 옛 execd 는 문자열 `error.evalue` 만 실었고, 신호로 끝나면 `-1` 이었다. 1.2.0 의 execd 도 신호로
  끝난 명령의 `error.exit_code` 를 `-1` 로 싣는다 — 신호 이름은 `traceback` 의 `signal: killed` 같은 문구에만 남는다(1.2.0 소스로
  확인).
- **백그라운드 모드는 쓰지 않는다.** `/command` 의 백그라운드 모드는 stdout 과 stderr 를 합친다. 셸의 백그라운드 명령도
  포그라운드 `/command` 로 돈다.
- **실행 uid.** 명령과 files API 는 execd 프로세스의 사용자로 돈다. files API 에 uid 인자가 없던 execd v1.1.0 에서는 `/command` 의
  uid 로 돈 명령이 files API 가 쓴 파일에 쓰지 못했다. 그래서 프로바이더는 `/command` 의 uid 인자를 쓰지 않는다 — 결정과 이유,
  "uid 1000 으로 돈다" 를 지키는 방법은 [설정 §4](workspace-sandbox-configuration.md). 1.2.0 의 execd 는 `/v1/filesystem/{uid}/{gid}/…`
  경로로 files API 도 지정한 uid 로 돌린다(1.2.0 소스로 확인. 1.1.1 부터라는 기록은 [보안 §10](workspace-sandbox-security.md)) —
  그래서 이 결정의 이유는 1.2.0 에서 약해졌다(확인 필요: 결정을 다시 볼지).

### 3.3 파일

- **쓰기는 제자리에서 자르고 쓴다** — 원자적이지 않다. 새 파일 전용 모드도, 덮어쓰는 이동도 없다.
- **`/files/mv` 는 덮어쓰지 않지만 원자적이지 않다.** "이미 있음" 을 stat 한 뒤 rename 으로 검사한다. "이미 있음" 은 500
  `RUNTIME_ERROR` 의 메시지로만 구분된다.
- **`stat`** 의 mtime 은 나노초이고 해시 · etag 는 없다.
- **업로드**는 multipart 이고, `metadata` 파트를 파일 파트(filename 포함)로 보내야 한다(아니면 400 — 1.2.0 소스로
  확인).
- **다운로드**는 `GET /files/download` 에 `Range` 헤더로 범위를 받는다. 디렉터리를 다운로드하면 연결이 끊기므로(1.2.0 소스로 확인) 먼저 stat 한다.
- **재귀 목록**(`/directories/list`)은 깊이 `-1` 을 거부한다. 그래서 프로바이더는 깊이 64 로 묻고, 그보다 깊은 항목은 재귀 목록에
  나오지 않는다.
- 파일을 지나는 경로(`a.txt/b`, `ENOTDIR`)의 stat 에 execd 는 404 가 아니라 500 `RUNTIME_ERROR` 로 답한다(1.2.0 소스로 확인).
  프로바이더는 404 만 "없음" 으로 본다.

이 사실들에 대한 프로바이더의 대응은 §4.6 이다. 계약 스위트의 파일 검사가 1.2.0 docker 계층에서 돈다.

### 3.4 볼륨

(계획 — 구현 단계 5. 서버가 정하는 것은 1.2.0 소스로 확인했다. Docker · K8s 가 정하는 삭제 동작(마지막 항목)은 1.1.x 에서 잼,
1.2.0 미확인.)

- 생성은 `volumes[].pvc{claimName, createIfNotExists, storageClass, storage, accessModes}` 와 마운트마다의 `readOnly` 다.
  `claimName` 은 DNS 라벨 규칙을 따른다 — [프로바이더 §5](workspace-sandbox-provider.md) 의 이름 규칙이 맞는다.
- Docker 런타임에서 `pvc` 는 named volume 이 되고, 한쪽 `rw` · 다른 쪽 `readOnly` 가 명령과 files API 모두에 걸린다. 크기 ·
  class · access mode 는 무시된다. K8s 런타임은 RWX StorageClass 가 있으면 된다. `deleteOnSandboxTermination` 을 켜지 않으면
  만들어진 PVC 에는 라벨도 ownerRef 도 없다.
- **삭제 경로는 우리 몫이다.** Docker 는 `docker volume rm` 이고 마운트 중이면 "volume is in use" 다(`VolumeInUseException`).
  K8s 는 PVC 삭제이고, 마운트 중이면 `pvc-protection` 이 마지막 마운트가 사라질 때까지 붙잡는다 — 그동안 그 PVC 를 쓰는 새
  샌드박스는 60초 뒤 504 다. 그래서 K8s 의 `VolumeReclaimer` 는 `Terminating` 인 PVC 를 "마운트 중" 으로 본다. 이 규칙의 쓰임은
  [수명 §6.3](workspace-sandbox-lifecycle.md).

### 3.5 egress 와 vault

- **정책이 없으면 전부 허용이다.** `networkPolicy{defaultAction: deny, egress: []}` 는 전부 막는다. 그래서 프로파일의
  `egress: []` 를 생략하지 않고 deny 로 보낸다 — 규칙은 [보안 §4](workspace-sandbox-security.md).
- **모드.** `dns` 모드는 이름 허용 목록만 보고, 서버의 호스트 IP · 옆 샌드박스 · 공개 IP 에 IP 로 닿는다. `dns+nft` 모드는
  허용한 이름이 돌려준 IP 만 열고, 직접 DNS 질의도 사이드카가 가로챈다. 서버가 적용한 모드는 `GET /sandboxes/{id}/networkpolicy`
  의 `enforcementMode` 로 읽을 수 있다.
- **gVisor 와 함께 쓸 수 없다.** 실효 런타임이 gVisor 면 서버가 모든 `networkPolicy` 를 400 으로 거부한다 — gVisor 의 netstack 에
  iptables nat 테이블이 없기 때문이다.
- **Docker 의 `network_mode`.** Docker 런타임의 서버는 `[docker] network_mode = "bridge"` 일 때만 `networkPolicy` 를 받는다. 기본값
  `host` 와 사용자 정의 네트워크에서는 400 이다. 프로바이더는 이 서버 설정을 볼 수 없다([설정 §5](workspace-sandbox-configuration.md)).
- **샌드박스는 자기 정책을 바꿀 수 없다.** 사이드카 API 가 토큰을 요구한다(401)(1.2.0 소스로 확인).
- **사이드카는 샌드박스보다 늦게 뜬다.** `Running` 직후 잠시 `GET /networkpolicy` 와 vault 가 서버 프록시를 통해 실패한다(1.1.x
  에서 500 으로 잼, 1.2.0 미확인). 1.2.0 서버의 프록시는 사이드카에 연결하지 못하면 502 로 답한다(1.2.0 소스로 확인). 자격 증명
  프록시(`credentialProxy.enabled`)를 켜면 execd 의 bootstrap 이 사이드카의 CA 를 기다린 뒤에 execd 를 띄우므로(1.2.0 소스로 확인),
  `Running` 직후의 첫 명령이 502 를 받을 수 있다. 프로바이더는 둘 다 기다린다(§4.1). 그 CA 를 git 이
  신뢰하게 하는 방법은 [셸 §10](workspace-sandbox-shell.md).
- **vault.** 1.2.0 서버는 `/v1/sandboxes/{id}/credential-vault` 경로를 연다. Docker 와 K8s 런타임에서 서버가 사이드카 자신의
  `/credential-vault`(포트 18080)를 사이드카의 인증 토큰과 함께 프록시하므로 의미는 사이드카의 것이다. 프로바이더는 사이드카의
  주소도 토큰도 필요 없고, `useServerProxy` 와 상관없이 늘 서버를 거친다. 바인딩은 `{schemes, hosts, methods, paths}` 이고,
  범위 밖 요청에는 키가 붙지 않는다 — 자격 증명 규칙은 [보안 §5](workspace-sandbox-security.md). 샌드박스 환경에는 비밀이 없다.

### 3.6 네트워크 격리

- **K8s.** 컨트롤러는 NetworkPolicy 를 만들지 않는다. 정책이 없으면 샌드박스가 옆 샌드박스 · 서버 · K8s API · kubelet 에 닿는다.
  운영자가 건 NetworkPolicy 는 동작하고, 서버 → 샌드박스 프록시는 그 정책 아래에서도 산다. 운영자 계약은
  [설정 §5](workspace-sandbox-configuration.md), 정책 원문은 [가이드 §6](../guide/opensandbox-kubernetes.md).
- **Docker.** OpenSandbox 안에는 막을 방법이 없다. execd 가 토큰 없이 모든 인터페이스에 열리고, 옆 샌드박스에서 root 명령을
  실행할 수 있었다. egress 정책은 나가는 쪽만 막고 들어오는 명령은 그대로다. 모든 샌드박스에 deny 정책을 걸고 `publish_host` 를
  루프백으로 묶고 호스트 방화벽을 거는 조합이 있지만, AIMON 은 그것을 확인할 수 없다. (execd 에 토큰이 없고 포트가 기본
  `0.0.0.0` 에 열리는 것은 1.2.0 소스로 확인.) 그래서
  Docker 런타임은 `NETWORK_ISOLATION` 을 선언할 수 없다 — 설정 오류다(§5).
- **인증 경계.** execd 는 토큰을 검사하지 않는다. 단일 테넌트 모드의 서버 프록시 경로 `/v1/sandboxes/{id}/proxy/{port}` 는 API
  키를 검사하지 않는다. 서버나 execd 포트에 닿는 누구든 샌드박스 id 만 알면 명령을 실행한다. 1.2.0 K8s 런타임에서 확인했고,
  Docker 런타임의 같은 사실은 1.1.x 에서 쟀다. 이 사실에서 나온 배치 규칙 — 서버를 AIMON 만 닿는 네트워크에 두고 샌드박스 id 를
  INFO 로그에 남기지 않는다 — 은 [보안 §7](workspace-sandbox-security.md)이 소유한다.

### 3.7 보안 컨텍스트

- **K8s.** 기본 템플릿은 강화되어 있지 않다(uid 0, 기본 capability, seccomp unconfined, no-new-privileges 꺼짐).
  ServiceAccount 토큰만 늘 마운트되지 않는다. 강화는 서버 전체에 적용되는 BatchSandbox 템플릿으로만 할 수 있고, **컨테이너
  단위**여야 한다. 파드 단위 `runAsUser` · `runAsNonRoot` 는 권한이 필요한 `execd-installer` init 컨테이너와 egress 사이드카에도
  걸려, egress 정책이 있는 생성이 `InitContainerFailed` 로 실패한다. egress 정책이 있으면 서버가 샌드박스 컨테이너의
  `capabilities.drop` 을 `[NET_ADMIN]` 으로 덮어쓴다(템플릿의 `[ALL]` 이 사라진다). 템플릿 원문은
  [가이드 §5](../guide/opensandbox-kubernetes.md).
- **Docker.** 샌드박스는 root 로 돌고, 루트 파일 시스템이 쓰기 가능하며, 요청에 user 필드가 없다(모르는 필드는 조용히 무시한다).
  서버 설정의 cap drop · no-new-privileges · seccomp · pids 는 걸린다(1.2.0 소스로 확인). 그래서 Docker 런타임은
  `HARDENED_SECURITY_CONTEXT` 를 선언할 수 없고 `insecureAllow` 로만 쓴다.
- execd 는 OpenSandbox 가 넣는다 — Docker 에서는 컨테이너에 복사되고 K8s 에서는 init 컨테이너가 넣는다. 그래서 샌드박스 이미지에
  execd 가 필요 없다([설정 §4](workspace-sandbox-configuration.md)).
- **PID 1.** Docker 런타임과 K8s 템플릿 모드에서 샌드박스의 PID 1 은 `bootstrap.sh` 의 `sh` 이고, 진입점을 기다리는 `wait` 가
  입양된 고아도 거둔다. 서버가 `execd_run_as_init = true` 로 뜨면 그 자리에 `execd --init` 이 오고, 이것도 고아를 거둔다. 풀 모드는
  다르다(§3.8). 셸 엔진에 대한 귀결은 [셸 엔진 §7](workspace-sandbox-shell-engine.md).

### 3.8 풀 모드 (계획 — 구현 단계 7)

SPI 는 풀을 요청하지 않는다([프로바이더 §7](workspace-sandbox-provider.md)). 아래는 공개 풀 지원을 설계할 때의 재료다. 풀 선택은
`OpenSandboxProviderConfig` 의 공개 설정이 아니고, k8s 테스트 계층의 풀 모드 시험만 패키지 안에서 생성 본문의 `extensions` 를
채운다.

- 서버는 생성 본문의 `extensions.poolRef` 로 풀 모드를 고른다. 풀 모드에서는 `networkPolicy` · `credentialProxy` · `volumes` 를
  거부하고, `image` · `resourceLimits` 를 무시하며(이미지는 `Pool` CR 의 것), 라벨은 BatchSandbox 메타데이터에 붙는다(1.2.0
  소스로 확인). 그래서 풀은 egress 정책 · 공유 볼륨 · 자격 증명이 없는 프로파일에만 쓸 수 있다.
- 풀 파드의 PID 1 은 task-executor 이고, task-executor 는 자기 자식만 기다리며 고아를 거두지 않는다. PID 1 이 아닌
  `execd --init` 은 subreaper 가 되어 고아를 거둔다. 이 사실에서 나온 운영자 요구(`execd_run_as_init = true`)는
  [설정 §5](workspace-sandbox-configuration.md), 셸 엔진에 대한 귀결은 [셸 엔진 §7](workspace-sandbox-shell-engine.md).
- 풀 모드에서는 컨트롤러가 task-executor(포트 5758)에 작업을 전달하므로, 샌드박스 격리 NetworkPolicy 에 더해 컨트롤러에서
  task-executor 로 오는 연결을 여는 두 번째 정책이 필요하다.

## 4. 프로바이더 동작

프로바이더는 서버가 지키지 않는 것을 지킨다: 생성은 늘 `timeout` 을 보내고, `extendExpiry` 는 앞으로만 · 상한까지만 늘리며,
`destroy` 는 404 를 성공으로 바꾸고, 캡처 상한은 클라이언트에서 건다. capability 는 설정에서 유도하고(§2, §5) 살아 있는
샌드박스에서만 볼 수 있는 것은 `verify` 로 확인한다.

### 4.1 생성

`create` 의 예산은 `createTimeout` 하나다. POST 와 그 뒤의 모든 기다림이 이 안에 든다 — K8s 에서는 POST 만으로도 60초 가까이
막힐 수 있다.

1. **기존 샌드박스 조회.** `managed` · `deployment` · `sandbox-key` 라벨로 목록을 읽어, TERMINATED · FAILED 가 아니고
   자격 증명 라벨(아래)이 같은 샌드박스가 있으면 그것을 쓴다. 여럿이면(인계 경합) `createdAt` 이 가장 오래된 것을 쓰고, 나머지는
   조정이 DUPLICATE 로 회수한다. FAILED 를 빼는 이유: 다시 써 봐야 `createTimeout` 을 기다릴 뿐이다.
2. **POST.** 없으면 `POST /v1/sandboxes` 를 보낸다. 재시도하지 않는다(§4.8).
3. **Running 대기.** Docker 는 `Pending` 으로 답하므로 `Running` 까지 폴링한다. `Failed` 이거나 시간 안에 `Running` 이 되지
   않으면 transient 실패이고, 샌드박스는 **지우지 않는다**([프로바이더 §4.2](workspace-sandbox-provider.md)).
4. **egress 사이드카 대기.** egress 가 있으면 `GET /networkpolicy` 가 성공할 때까지 기다린다. vault 와 `verify` 가 사이드카를
   쓰므로, seed 의 `verify` 가 아직 뜨지 않은 사이드카를 만나지 않게 하기 위해서다.
5. **execd 대기.** execd 가 `/ping` 에 답할 때까지 기다린다. `Running` 이 execd 가 듣고 있다는 뜻은 아니기 때문이다(§3.5).
6. **vault.** 자격 증명이 있으면 vault 의 바인딩이 정확히 이 생성의 바인딩인지 보고, 다르면 지우고 다시 넣는다. 비밀은 이때
   `CredentialDefinition` 의 공급자에서 읽는다 — 비밀 교체는 다음 generation 부터 적용된다. vault 에는 프로파일이 이름으로 부른
   바인딩만 넣는다. 설정된 다른 비밀은 샌드박스의 사이드카에 닿지 않는다. 실패는 400 · 403 이면 영구, 그 밖에는 transient 이고,
   샌드박스는 조정의 FAILED_LEFTOVER 로 남긴다. 비밀의 로그 · redact 규칙은 [보안 §5](workspace-sandbox-security.md).

생성 본문:

- `timeout` 은 `ceil((expiresAt − now) / 1초)` 를 60 이상 `maxExpiry` 이하로 맞춰 **늘** 보낸다(§3.1 — 빼면 만료되지 않는다).
- `entrypoint` 는 설정값(기본 `[tail, -f, /dev/null]`)을 늘 보낸다.
- `resourceLimits` 는 프로파일의 cpu · memory, 없으면 `defaultResources` 다.
- `metadata` 는 `CreateSpec` 의 라벨에 자격 증명 라벨을 더한 것이다.
- `networkPolicy` 는 egress 가 있으면 빈 목록이라도 `defaultAction: deny` 와 함께 보낸다(§3.5).
- 자격 증명이 있으면 `credentialProxy.enabled` 를 켠다. 볼륨 마운트는 `volumes[].pvc` 로 보낸다.
- `runtimeClass` 와 `platform` 은 보내지 않는다 — 서버에 요청마다의 class 가 없다. `CreateSpec.runtimeClass` 가 선언한 class 와
  다르면 영구 실패다. 프로파일의 `disk` · `pids` 에 대응하는 요청 필드도 없다. 거부하지 않고, 자원 명세마다 한 번 "서버 설정
  (Docker `pids_limit`)으로만 집행된다" 고 경고한다. 거부하면 `pids` 를 적은 프로파일이 OpenSandbox 위에서 뜨지 못한다.

**심층 방어.** 기동 검사가 이미 거부하는 것도 `create` 가 다시 거부한다(영구): `EGRESS_POLICY` 없이 egress 를 요청한 경우,
`CREDENTIAL_INJECTION` 없이 자격 증명을 요청한 경우, 선언과 다른 runtime class.

**자격 증명 라벨.** 프로바이더는 자기 라벨 `aimon.at/credentials` 에 vault 에 넣은 바인딩 이름(정렬)의 해시를 단다. `verify` 는
참조만 받으므로, 이 라벨이 있어야 프로파일 없이 어느 노드에서든 vault 를 대조할 수 있다. 매니저의 대조 대상 라벨은 아니다
([프로바이더 §4.3](workspace-sandbox-provider.md)).

### 4.2 상태 매핑

| OpenSandbox | `ProviderSandboxState` |
|---|---|
| `Pending`, `Resuming` | `PENDING` |
| `Running` | `RUNNING` |
| `Pausing`, `Paused` | `PAUSED` |
| `Stopping`, `Terminated` | `TERMINATED` |
| `Failed` | `FAILED` |

`GET` 의 404 는 빈 값이다. 라벨은 `metadata`, `expiresAt` · `createdAt` 은 RFC 3339 로 읽는다. `Stopping` 을 TERMINATED 로 읽는
이유: K8s 런타임은 지운 샌드박스를 파드가 사라질 때까지 `Stopping` 으로 나열하고(§3.1), 그 샌드박스는 다시 쓸 수 없다.

### 4.3 만료와 삭제

`extendExpiry` 는 현재 `expiresAt` 을 읽어 뒤로 가는 요청을 무시하고, `now + maxExpiry` 로 자르며, `Paused` 상태의 샌드박스는
영구 실패로 거부한다(§3.1 — K8s 에서 멈춘 샌드박스의 renew 는 샌드박스를 망가뜨린다. 쓰이는 것은 계획 — 구현 단계 7). 목표는
마이크로초로 올림해 보낸다 — 서버가 마이크로초 아래를 버리므로, 나노초 시계(Linux)에서는 그대로 보내면 요청보다 조금 이른 만료가
남는다. 두 노드가 동시에 연장하면 읽기와 renew 사이가 원자적이지 않아 경합하지만, 둘 다 대략 `now + terminateAfter` 를 쓰므로
잃는 것은 몇 초다.

`destroy` 는 404 를 성공으로 바꾼다.

### 4.4 명령

- **실행.** `run` 은 `POST /command {command, cwd, envs, timeout}` 의 이벤트 스트림을 프로바이더 스레드에서 읽는다. 첫 `init`
  이벤트의 text 가 명령 id 다. `stdout` · `stderr` 이벤트는 sink 로 가고, 스트림마다 UTF-8 로 인코딩한 바이트로
  `maxCaptureBytes` 까지 담는다. 넘는 것은 세고 버리며 명령은 계속 돈다. `execution_complete` 는 종료 코드 0 이고, `ping` 은
  무시한다. 스트림 전에 400 이 오면 서버 메시지와 함께 영구 실패다.
- **줄 끝.** 프로바이더는 명령마다 execd 가 줄 끝을 싣는지 이벤트에서 판단한다. 줄 끝(`\n` · `\r`)으로 끝나는 `"\n"` 이 아닌
  이벤트가 오거나 종료 이벤트에 `exit_code` 가 있으면 줄 끝을 싣는 execd 이고, 출력은 그대로 전달된다. 줄 끝 없는 줄 뒤에 다른
  출력이 오면 줄 끝을 떼는 옛 execd 이고, 줄마다 `\n` 을 붙인다. 판단이 서기 전의 줄 끝 없는 줄은 붙잡아 두므로, 옛 execd 에서는
  첫 줄이 다음 이벤트가 와야 sink 에 닿는다.
- **kill.** `kill()` 과 `await` 의 시간 초과는 늘 `DELETE /command?id=` 를 보낸다(§3.2 — 연결을 끊어도 명령은 죽지 않는다). id 가
  오기 전의 kill 은 걸어 두었다가 `init` 이 오는 대로 보낸다. kill 한 명령의 스트림은 유예(8초) 동안 기다린 뒤 그것 없이
  보고한다.
- **끊긴 스트림.** 종료 이벤트 없이 스트림이 끝나면 `await` 의 기한 안에서 `GET /command/status/{id}` 를 폴링해 종료 코드를
  읽는다.
- **종료 코드.** `error.exit_code`(0 이상)를 먼저 쓰고, 없거나 음수면 `error.evalue` 를 쓴다. `-1`(신호)은 traceback 의 신호
  이름으로 `128 + n` 이 되고(`terminated` 143, `killed` 137), 모르는 이름은 137 이다. 숫자가 아닌 `evalue`(예: 인자 목록이 너무
  긺)는 126 이다.
- **사라진 샌드박스.** execd 연결이 실패하면 엔드포인트를 한 번 다시 해석한다(§3.2). 해석이 404 이거나 `status` 가 비면
  `SandboxNotFoundException`, 그 밖에는 transient 다.
- **줄 상한.** 이벤트 줄을 물질화하기 전에 256 KiB(받은 JSON 바이트)로 막는다 — 그 줄은 최대 256 KiB 만 메모리에 있었다. 넘으면
  경고를 남기고, 두 스트림을 모두 잘림으로 표시하고(어느 스트림의 줄이었는지는 읽지 않은 부분에 있다), `DELETE /command` 로
  끝낸 뒤 status 에서 종료 코드를 읽는다(SIGTERM 뒤 SIGKILL 이므로 보통 137). 그다음 스풀을 지운다(§4.5).
- **kill 원인의 승자.** 줄 상한으로 끝낸 명령은 `killCause = LINE_LIMIT_EXCEEDED` 를 싣는다. 단 아직 kill 이 요청되지 않았고 종료가
  정해지지 않았을 때만 기록한다 — 타임아웃이나 호출자의 kill 이 이미 가는 중이면 그쪽이 보고된다. 일단 기록하면 kill 의 종료가
  spec 의 타임아웃을 지나 오거나 `await` 가 시간을 다 써도 `timedOut` 은 거짓이다. `init` 전에 긴 줄이 오면 결과를 만들지 못한다 —
  리더가 그 줄에서 멈춰 `init` 을 보지 못하고, status 폴링이 "명령이 시작되기 전에 끝났다" 로 실패한다.
- **스레드.** 스트림 리더는 상한 없는 데몬 스레드 풀에서 돈다. 상한을 두면 N+1 번째 동시 명령을 거부하게 되고, 명령 하나하나는
  이미 자기 타임아웃으로 묶여 있기 때문이다. 모든 execd 호출에는 클라이언트 타임아웃이 있다 — pause 중의 execd 호출이 실패하지
  않고 멈추기 때문이다(§3.1).

### 4.5 스풀 삭제 (`transientOutput`)

1.2.0 의 execd 는 끝난 명령의 스풀을 스스로 지운다(§3.2). 그래도 `transientOutput` 이 켜진 명령의 스풀은 프로바이더가 다시
지운다 — 이중 방어다. stream 실행의 출력 사본이 사라지는 것을 execd 버전의 정리 동작에 기대지 않기 위해서다. 없는 파일의 삭제는
execd 가 성공으로 답하므로 이미 지워졌으면 해가 없다.

- **경로.** `/tmp/opensandbox-execd/<id>.stdout` · `.stderr` 를 `DELETE /files` 호출 **한 번**에 지운다(§3.2 의 `/tmp` 가정).
- **거절된 호출.** execd 는 처음 지우지 못한 경로에서 멈추고 500 으로 답한다(경로가 디렉터리이거나, 같은 uid 의 명령이
  `/tmp/opensandbox-execd` 를 보통 파일로 만든 경우). 그래서 오류 응답으로 거절된 호출은 경로마다 다시 부른다 — 지울 수 없는 경로
  하나가 나머지를 남기지 않게.
- **처리되지 않은 호출은 다시 부르지 않는다.** 전송 실패 · 요청 타임아웃 · 502 · 503 · 504 는 execd 가 경로를 하나도 시도하지 않은
  경우다. 502 는 서버 프록시가 execd 에 연결하지 못했을 때의 답이고(연결 거절이면 곧바로, 연결 타임아웃이면 프록시의 타임아웃 —
  180초 — 뒤에), 503 · 504 는 런타임 초기화 전의 execd 나 앞단 게이트웨이의 답이다. 1.2.0 서버의 프록시는 응답 도중 연결이 끊겨도
  502 로, 읽기 · 쓰기가 180초를 넘어도 504 로 답하며, 이때는 execd 가 호출을 받았을 수 있다(1.2.0 소스로 확인. 확인 필요: 이 두
  경우를 처리되지 않은 호출로 묶는 이유를 다시 볼지). 다시 불러도 첫 호출을 받지 못한 같은 execd 에 닿을 뿐이다. 일시적 오류 한 번에 그 실행의 스풀이 남을 수 있지만,
  execd 가 정말 답하지 않을 때 실행마다 기다림이 배로 느는 쪽보다 낫다고 본다. 스풀 삭제는 best effort 이고 실패는 경고 로그다.
- **시점.** 종료 이벤트를 받았거나 끊긴 스트림 뒤 status 로 종료 코드를 읽었을 때, `await` 가 결과를 돌려주기 **전에** 지운다.
  그래서 `await` 가 끝나면 스풀도 없다. `await` 가 답 없는 명령을 포기한 경우에는 결과가 먼저 나가고, 리더가 스트림을 닫은 뒤
  지운다. 샌드박스가 사라졌으면 시도하지 않는다.
- **`await` 의 시간.** 삭제는 `await` 의 시간 안에 든다. 명령이 시간 안에 끝났는데 삭제가 늦으면, `await` 는 kill 하지 않고
  타임아웃으로 보고하지도 않으며 삭제를 요청 타임아웃만큼 더 기다린다. 그래도 끝나지 않으면 삭제 전에 기록해 둔 **명령의 실제
  결과**(종료 코드, `timedOut=false`, 캡처한 출력)를 보고하고 삭제는 프로바이더 스레드에서 마저 돈다. 시간이 다 되어 kill 한
  명령이 kill 유예(8초) 안에 끝났는데 삭제가 유예보다 오래 걸리면, 137 이 아니라 기록해 둔 실제 종료 코드를 `timedOut=true` 로
  보고한다. 137 은 유예 안에 끝나지 않은 명령에만 남는다.
- **삭제 중의 kill 은 보내지 않는다.** 이미 끝난 명령에 대한 `DELETE /command` 는 500 이다.
- **경로는 명령의 출력으로 움직일 수 없다.** id 는 **첫** `init` 이벤트의 것만 쓴다. execd 가 출력을 JSON 문자열로 실으므로 출력이
  이벤트를 만들 수 없고, 두 번째 `init` 이 와도 무시한다. id 가 `[A-Za-z0-9_-]{1,128}` 이 아니면 경로를 만들지 않는다(execd 의
  id 는 32자리 hex). `init` 전에 끝난 명령은 지울 것이 없다.

### 4.6 파일

- **쓰기.** 같은 디렉터리의 임시 이름(`.{name}.aimon-tmp-{uuid}`)에 모드 644 로 올린 뒤 옮긴다. `CREATE_OR_REPLACE` 는 `/command`
  의 `mv -f`, `CREATE_NEW` 는 `ln` 뒤 `rm` 이다 — link(2) 가 원자적이므로 "이미 있음" 이 `FileAlreadyExistsException` 이 된다.
  실패하면 임시 파일을 지운다. 이유: files API 의 쓰기는 제자리에서 자르고 쓰며 새 파일 전용 모드가 없고, `/files/mv` 의 검사는
  원자적이지 않다(§3.3). 모드는 서버 기본값에 기대지 않고 늘 명시한다. 있는 디렉터리 위의 `CREATE_OR_REPLACE` 는 거부한다 —
  `mv` 가 파일을 그 안으로 옮기기 때문이다.
- **이동.** `overwrite` 면 대상의 부모를 만들고 `mv -f -T` 를, 아니면 `/files/mv` 를 쓴다. `/files/mv` 의 500 "already exists" 는
  `FileAlreadyExistsException` 이다. 이 경로는 원자적이지 않다.
- **읽기.** 먼저 stat 하고(없으면 `FileNotFoundException`, 디렉터리면 거부) 요청한 범위를 받는다. 응답은 바이트 배열 하나로
  메모리에 받으므로, 큰 파일을 읽는 쪽은 범위를 나눠 읽는다(사람용 파일 API 가 그렇다 — [관리 §6.3](workspace-sandbox-management.md)).
- **실패.** files 호출의 500 은 `VirtualFileSystemException` 이고, 그 밖의 5xx 는 transient 프로바이더 실패다. 예외의 의미는 로컬
  프로바이더와 같고, 공유 계약 스위트가 그것을 확인한다([테스트 §1](workspace-sandbox-testing.md)).

### 4.7 에러 분류

실패는 **HTTP 상태로** 분류하고 서버의 에러 코드 문자열에 기대지 않는다. 코드에 붙는 런타임 접두어가 런타임마다 다르고, 엉뚱한
런타임에서도 나오기 때문이다(§3.1).

| 상태 · 조건 | 분류 |
|---|---|
| 샌드박스 범위 호출의 404 | `SandboxNotFoundException`(`destroy` 는 성공, `status` 는 빈 값) |
| files 호출의 404 | `FileNotFoundException`. 샌드박스가 있는지는 따로 확인한다(아래) |
| 400, 422 | 영구(서버 메시지와 함께 — 라벨 규칙, 상한을 넘는 timeout 등) |
| 401, 403 | 영구("api-key 를 확인하라") |
| 409, 429, 5xx, 504, I/O 실패, 타임아웃 | transient. 멱등 호출은 재시도한다(§4.8) |
| `/files/mv` 의 500 "already exists" | `FileAlreadyExistsException` |

서버 프록시를 거치면 사라진 샌드박스와 없는 파일이 모두 404 다. 그래서 그 404 가 무엇인지는 수명 API 의 `GET` 으로 확인한다.
지름길이 하나 있다 — 본문 코드가 execd 자신의 `FILE_NOT_FOUND`(런타임 접두어 없음)인 404 는 두 번째 호출 없이 없는 파일로 본다.
코드 문자열을 읽는 유일한 곳이고, 호출 하나를 아낄 뿐 다른 404 는 모두 수명 서버에 묻는다. vault 경로의 404 중 서버에 그 경로가
없다는 답(1.2.0 보다 오래된 서버)은 영구 실패로 보고한다 — 다시 시도해도 옛 서버에 경로가 생기지 않는다.

### 4.8 재시도와 동시 호출

**멱등 호출만 재시도한다.** `create` · `/command` · 업로드는 재시도하지 않는다. 이 호출들의 응답이 사라지는 것은 레코드와 조정이
흡수할 장애이고, 다시 보내면 샌드박스나 명령이 둘 생긴다. 재시도 횟수와 간격은 `retry` 설정이다(§5).

`maxConcurrentCalls` 는 요청/응답 호출만 세는 세마포어다. 명령의 이벤트 스트림은 허가를 잡지 않는다 — 20분짜리 빌드가 janitor
의 호출을 굶기면 안 되기 때문이다.

### 4.9 `verify`

seed 단계의 `verify`([프로바이더 §1.4](workspace-sandbox-provider.md))는 요구된 capability 에 따라 다음을 본다.

- `EGRESS_POLICY`: `GET /sandboxes/{id}/networkpolicy` 의 `enforcementMode` 가 `dns+nft` 이고 `defaultAction` 이 `deny` 인가.
- `CREDENTIAL_INJECTION`: vault 가 가진 바인딩 이름이 자격 증명 라벨(§4.1)의 바인딩과 정확히 같은가.

다르면 그 단계의 영구 실패다. 기동 시점에 선언으로 광고한 것이 실제로 걸렸는지를 첫 프로비저닝에서 확인하는 장치다.

### 4.10 엔드포인트와 서버 프록시

`useServerProxy` 에서는 엔드포인트 API 가 돌려준 주소의 경로만 쓰고, 그것을 설정된 서버 엔드포인트에 대해 해석한다. NAT 나
port-forward 처럼 AIMON 이 보는 서버 주소와 요청의 `Host` 가 다른 배치에서도 동작하게 하기 위해서다. 샌드박스 id 는 INFO 로그에
남기지 않는다(§3.6, [보안 §7](workspace-sandbox-security.md)).

## 5. 설정

프로바이더 설정은 `OpenSandboxProviderConfig` 의 빌더 필드로 정의한다. 아래의 키 이름(`aimon.sandbox.opensandbox.*`)은 Spring
스타터가 생기면 쓸 키의 제안이다([설정 §2](workspace-sandbox-configuration.md)).

| 필드 (키) | 기본값 | 뜻 |
|---|---|---|
| `endpoint` | 필수 | 수명 서버 주소. `/v1` 이 없으면 붙인다 |
| `apiKey` (`api-key`) | 필수 | `OPEN-SANDBOX-API-KEY`. 공급자로 받고 로그에 남기지 않는다 |
| `runtime` | **필수, 기본값 없음** | `DOCKER` · `KUBERNETES`. 어떤 선언이 합법인지 정한다 |
| `maxExpiry` (`max-expiry`) | 필수, 60초 이상 | 서버의 `max_sandbox_timeout_seconds` 와 같은 값. `ProviderCapabilities.maxExpiry` 가 된다. 서버보다 크면 `create` 가 400 이다 |
| `egressEnforcement` (`egress-enforcement`) | `NONE` | `NONE` · `DNS` · `DNS_NFT`(서버의 `dns` · `dns+nft` 모드). `DNS_NFT` 일 때만 `EGRESS_POLICY` |
| `networkIsolation` (`network-isolation`) | `UNDECLARED` | `DECLARED` 면 `NETWORK_ISOLATION` |
| `hardenedSecurityContext` (`hardened-security-context`) | `UNDECLARED` | `DECLARED` 면 `HARDENED_SECURITY_CONTEXT` |
| `runtimeClass` (`runtime-class`) | 없음 | `{name, kind: gvisor · kata · other}`. `RUNTIME_CLASS` 와 `runtimeClass()` 가 된다 |
| `credentials` | 비어 있음 | 바인딩 이름 → `CredentialDefinition`. `credentialScopes()` 가 된다 |
| `controlPlaneProbes` (`control-plane-probes`) | 엔드포인트의 host:port, kubernetes 면 `kubernetes.default.svc:443` 도 | `controlPlaneEndpoints()` 가 된다 |
| `volumeReclaimer` (`volume-reclaimer`) | 없음 | 있으면 `SHARED_VOLUME` 과 `sharedVolumes()`. 내장 구현(K8s PVC · Docker 볼륨)은 **(계획 — 구현 단계 5)** |
| `useServerProxy` (`use-server-proxy`) | `false` | execd 를 서버 프록시로 부른다(클러스터 밖의 K8s, docker 테스트 계층). vault 는 이 설정과 상관없이 서버를 거친다 |
| `requestTimeout` (`request-timeout`) | 30초 | 스트림이 아닌 호출 하나의 시간 |
| `createTimeout` (`create-timeout`) | 90초 | `create` 전체의 예산(§4.1) |
| `retry` | 최대 3회 시도, 간격 500 ms 에서 배로 | 멱등 호출의 재시도(§4.8) |
| `maxConcurrentCalls` (`max-concurrent-calls`) | 32 | 요청/응답 호출의 동시 상한(§4.8) |
| `entrypoint` | `[tail, -f, /dev/null]` | 이미지의 CMD 와 상관없이 샌드박스를 살려 둔다. 서버 자신의 스냅숏 기본값과 같다 |
| `defaultResources` (`default-resources`) | cpu 1, memory 1Gi | 프로파일이 자원을 주지 않을 때의 `resourceLimits` |

`build()` 는 위반을 모두 모아 `SandboxConfigurationException` 하나로 던진다 — 매니저의 기동 검사와 같은 "한 번에 전부" 규칙이다.
`build()` 가 거부하는 것:

- **Docker 런타임의 선언** — `networkIsolation` · `hardenedSecurityContext` · `runtimeClass`. Docker 런타임은 그 격리를 세울 수
  없다(§3.6, §3.7).
- **`dns+nft` 와 gVisor `runtimeClass`.** 서버가 gVisor 에서 모든 `networkPolicy` 를 400 으로 거부한다(§3.5). 둘을 함께 광고하면
  `egress` 가 있는 프로파일(`egress: []` 포함)이 기동을 통과한 뒤 첫 `create` 마다 슬롯이 영구 실패한다. `EGRESS_POLICY` 를 조용히
  빼지 않고 거부하는 이유는, 틀린 것이 선언이므로 오류를 선언이 있는 곳에 두기 위해서다 — 빼면 나중에 모든 egress 프로파일에서
  "프로바이더가 `EGRESS_POLICY` 를 광고하지 않는다" 는 엉뚱한 오류로 드러난다. `kind` 가 필수인 이유도 이것이다 — 프로바이더는
  class 이름만으로 그것이 고르는 런타임을 알 수 없다. Kata 는 허용한다.
- **`dns+nft` 없는 `credentials`.** 서버가 그때 credential proxy 를 거부한다. 그래서 `CREDENTIAL_INJECTION` 은 `dns+nft` 를 요구한다.
- **`networkIsolation` 선언과 빈 `controlPlaneProbes`.** 탐침할 곳이 없으면 seed 의 자가 점검이 아무것도 확인하지 않는다.
- 필수 값의 누락, 60초 미만의 `maxExpiry`, 1 미만의 `retry` · `maxConcurrentCalls`, 빈 `entrypoint`, 반쪽짜리
  `defaultResources`.

`networkIsolation` 을 Kubernetes 에서 선언하면서 `controlPlaneProbes` 를 유도된 기본값으로 두면 경고를 남긴다. AIMON 이 서버에
NAT 나 port-forward 로 닿으면 JVM 이 보는 주소가 샌드박스가 보는 주소와 다르므로, 서버의 클러스터 안 주소를 직접 적어야 한다.

`defaultResources` 가 있는 이유: 서버는 `resourceLimits` 가 없는 생성을 거부한다(§3.1, 1.2.0 소스로 확인).

운영자가 서버 쪽에서 준비할 것(egress 모드, `network_mode`, NetworkPolicy, 강화 템플릿, `execd_run_as_init`)은
[설정 §5](workspace-sandbox-configuration.md)와 [가이드](../guide/opensandbox-kubernetes.md).

## 6. 업스트림 이슈

실측에서 드러나 OpenSandbox 업스트림에 올릴 것으로 정리한 넷이다. 초안은
[`spike/opensandbox/upstream-issues.md`](../../spike/opensandbox/upstream-issues.md) 에 있다.

- K8s: 멈춘 샌드박스의 `renew-expiration` 이 원래 이미지로 파드를 다시 만들고 샌드박스를 `Failed` 로 만든다(§3.1).
- `renew-expiration` 이 만료를 줄이고 `max_sandbox_timeout_seconds` 를 넘기는 것을 허용한다 — 스펙은 연장이라고 적는다.
- 단일 테넌트 모드의 프록시 경로가 API 키를 건너뛴다 — 의도된 동작이지만 문서에 경고가 없다(§3.6).
- 문서의 umbrella 차트 설치 명령(`--create-namespace`)이 base 차트가 렌더링하는 Namespace 와 충돌한다. 1.2.0 에서도 충돌한다
  ([가이드 §4.1](../guide/opensandbox-kubernetes.md)).

(확인 필요: 넷을 실제로 올렸는지. 1.2.0 소스로 보면 첫째는 고쳐졌고, 둘째는 API 문서가 의도된 동작으로 적는다.) 프로바이더의 앞 두 대응(§4.3)은 서버가 고쳐져도 해가 없다.
