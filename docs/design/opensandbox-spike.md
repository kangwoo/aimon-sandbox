# OpenSandbox 스파이크 — 2단계 결과

> Status: **DONE** — [`workspace-sandbox.md`](workspace-sandbox.md) §18 의 2단계. §6.4 의 "구현 시 확인" 칸을 실서버에
> 대고 닫았다. 결론과 설계 변경은 `workspace-sandbox.md` 에 옮겼고(§6.1 · §6.4 · §9 · §10.2 · §12.1 · §20), 이 문서는
> 그 근거와 4단계 구현이 알아야 할 서버 동작을 모은다. SPI 의 모양(메서드와 타입)은 바뀌지 않았다 — 바뀐 것은 계약의
> 설명과 capability 를 광고하는 조건이다. 출력의 바이트 보존(§4-3)만 4단계가 정할 문제로 남는다.

대상: OpenSandbox `3738975`(2026-09-29 main). Docker 런타임은 소스에서 띄운 서버(`uv run opensandbox-server`) +
`opensandbox/execd:v1.1.0` · `opensandbox/egress:v1.1.7`, K8s 런타임은 kind(k8s v1.37, kindnet) 위의 차트 `1.1.1-rc.1` +
`opensandbox/*:release-1.1.1-rc.1` 이다. 실측은 macOS arm64(Docker Desktop)에서 했다. execd 이미지가 소스 HEAD 보다
오래되었으므로 동작은 **이미지의** 동작이다.

근거는 [`spike/opensandbox/`](../../spike/opensandbox/) 에 있다 — 보고서 셋(`reports/A-source.md` 는 소스 정적 분석, 주장마다
file:line · `B-docker.md` · `C-k8s.md` 는 실측), REST 를 직접 부르는 프로브 스크립트, 원본 출력(`docker/evidence/`,
`k8s/probes/out/`), 서버 설정과 K8s 배포 값. 아래 "(A §Q5)" 같은 표기는 그 보고서의 절이다.

---

## 1. §6.4 표 — 확인 결과

| capability | Docker 런타임 | K8s 런타임 | 광고 조건 |
|-----------|--------------|-----------|----------|
| `EXEC` | 쓸 수 있다. 제약은 §3 | 같다 | 항상 |
| `FILES` | 쓸 수 있다. `stat` 의 mtime 이 **나노초**(같은 크기 재작성 18 ms 간격을 구분), 해시·etag 없음 | 같다(execd 가 같다) | 항상. mtime 만으로 §6.1 의 변경 감지 계약을 지킨다 — 공유 볼륨(NFS)의 해상도는 5단계에서 다시 본다 |
| `PAUSE_RESUME` | `docker pause`(cgroup 동결). 프로세스와 파일이 남는다 | 루트 파일 시스템을 레지스트리 이미지로 커밋하고 **파드를 지운다.** resume 은 같은 이름 · 새 UID · 새 IP 로 다시 띄운다. 파일은 남고 **프로세스는 사라진다.** 스냅숏 레지스트리가 필요하고 커밋한 이미지는 지워지지 않는다 | 두 런타임 모두 **만료 시각이 pause 중에도 흐른다.** K8s 에서 pause 중 renew 는 샌드박스를 망가뜨린다(§4). 7단계에서 광고한다 |
| `EXPIRY` | `timeout`(상대 초, 최소 60). 만료 = **즉시 삭제**(상태가 남지 않고 404). 서버가 내려가 있던 사이의 만료는 재기동 때 집행 | 같다(초 단위로 정확) | 항상. 단, 앞으로만 · 상한은 프로바이더가 지킨다(§4) |
| `SHARED_VOLUME` | `volumes[].pvc` 가 named volume 이 된다. 한쪽 `rw` · 다른 쪽 `readOnly` 가 명령과 files API 모두에 걸린다. 크기·class·access mode 는 무시 | RWX StorageClass 가 있으면 된다(NFS 로 확인, kind 의 local-path 는 RWO 뿐). PVC 에 **라벨도 ownerRef 도 없다** | 두 런타임 모두 삭제 API 가 없다 — `VolumeReclaimer`(Docker 볼륨 API · K8s API)를 설정해야 광고한다. `deleteOnSandboxTermination` 은 쓰지 않는다(§5) |
| `EGRESS_POLICY` | `dns` 모드는 이름만 막고 **IP 로는 샌다.** `dns+nft` 는 IP · 직접 DNS(8.8.8.8) 까지 막는다 | 같다(`dns+nft` 로 확인) | `dns+nft` 일 때만. 서버가 모드를 알려 준다(`GET /sandboxes/{id}/networkpolicy` 의 `enforcementMode`) — 선언에 더해 seed 가 대조한다(§6). gVisor 와는 같이 쓸 수 없다(서버가 400) |
| `CREDENTIAL_INJECTION` | 소스로만 확인(`dns+nft` 필요) | 실측. 범위는 **scheme · host · port · method · path(glob)**. 샌드박스 환경에 비밀이 없다 | `dns+nft` 이고 vault 가 켜져 있을 때. 바인딩은 생성 뒤 사이드카 API 에 서버 프록시로 넣는다 |
| `NETWORK_ISOLATION` | **없다.** execd 가 토큰 없이 모든 인터페이스에 열리고, 옆 샌드박스에서 root 명령을 실행했다. egress 정책은 들어오는 쪽을 막지 않는다 | 컨트롤러가 NetworkPolicy 를 만들지 않는다. 운영자 NetworkPolicy(§7)는 kindnet 에서 동작하고 서버→샌드박스 프록시는 살아 있다 | 운영자 선언(`network-isolation: declared`)일 때만 — 설계대로. **Docker 런타임은 선언하지 않는다**(§7) |
| `RUNTIME_CLASS` | 광고하지 않는다 | 요청마다 지정할 **필드가 없다.** 서버 설정(`[secure_runtime].k8s_runtime_class`)이나 서버의 BatchSandbox 템플릿으로만 정해진다. 없는 class 는 서버 기동 실패(설정) 또는 60초 뒤 504(템플릿) | 프로바이더 설정이 서버의 class 를 선언하고, 프로파일의 `runtimeClass` 는 그 값과 같아야 한다(기동 시 검사) |
| `HARDENED_SECURITY_CONTEXT` | **불가.** root 로 돌고 루트 파일 시스템이 쓰기 가능하며 요청에 user 필드가 없다(모르는 필드는 조용히 무시). 서버 설정의 cap drop · no-new-privileges · seccomp · pids 는 걸린다 | 기본값은 **강화되지 않았다**(uid 0, 기본 cap, seccomp 없음, NNP 0). SA 토큰만 늘 빠진다. 서버의 템플릿으로 **컨테이너 단위** 강화가 된다(uid 1000, cap 0, NNP 1) | K8s 에서 운영자가 강화 템플릿을 선언할 때만. seed 가 결과를 대조한다(§11.3, 이미 있음). Docker 는 `insecure-allow` 로만 쓴다 |
| `SNAPSHOT` · `FORK` | API 가 있다(`docker commit`, 루트 파일 시스템만) | `SandboxSnapshot` CR | 8단계. 이번에 실측하지 않았다 |

## 2. 수명 · 라벨 · 생성

- **API 모양.** 경로는 `/v1/...`, 인증 헤더는 `OPEN-SANDBOX-API-KEY`. 생성은 `202`. 상태는 `Pending` · `Running` ·
  `Pausing` · `Paused` · `Resuming` · `Stopping` · `Terminated` · `Failed` 여덟이다. 에러 코드는 런타임 접두어가
  붙는다(`DOCKER::SANDBOX_NOT_FOUND`, K8s 에서도 `DOCKER::INVALID_EXPIRATION` 이 나온다) — 프로바이더는 **HTTP 상태로**
  분류하고 코드 문자열에 기대지 않는다 (B §a, C §h)
- **K8s 의 생성은 블로킹이다.** Running 과 IP 가 생길 때까지 최대 60초 붙잡고, 넘으면 `504 KUBERNETES::POD_READY_TIMEOUT`
  와 함께 BatchSandbox 를 지운다(롤백). K8s 는 `resourceLimits` 가 없으면 422 다. Docker 는 곧바로 돌아온다 (C §1)
- **라벨.** `aimon.at/*` 키가 받아들여지고, `opensandbox.io/` 는 예약이다. 값 규칙은 K8s 라벨 규칙이다(`:` · `/` ·
  64자 → 400 `SANDBOX::INVALID_METADATA_LABEL`). §6.3 의 32자 base32 는 통과한다. 필터는 **대소문자를 구분**하므로 소문자로
  보낸다 — `SandboxLabels` 는 이미 소문자다. K8s 에서는 CR 과 파드 모두에 라벨이 붙지만, `PATCH /metadata` 는 CR 만 바꾸고
  pause 중에는 파드가 없다 — 조정은 파드가 아니라 API(또는 CR)를 본다 (B §a, C §a)
- **목록.** 필터는 `metadata` 파라미터 **하나**에 `k=v&k=v` 를 담는다(반복하면 마지막 것만 쓰인다). AND 다. 페이지 번호
  방식(한 페이지 최대 200, 커서 없음, 최신순)이라 목록 도중의 생성이 페이지를 민다 — 조정은 중복과 누락을 견디고 다시
  읽는다. 끝난 샌드박스는 목록에 남지 않는다 (B §a)
- **멱등 키가 없다.** 같은 라벨로 두 번 만들면 둘이 생긴다 — §6.3 의 "라벨로 조회한 뒤 생성" 그대로다
- **`destroy`.** 없는 샌드박스의 `DELETE` 는 404 다 — 프로바이더가 성공으로 바꾼다
- **라벨은 바꿀 수 있다.** API 키를 가진 누구든 `PATCH /metadata` 로 `aimon.at/*` 를 고칠 수 있다. §6.3 의 대조는 인코딩
  버그와 충돌을 막는 장치이지 변조 방지가 아니다 — API 키가 곧 신뢰 경계다

## 3. 명령 실행(execd `/command`)

- **도달 경로.** execd 는 `GET /v1/sandboxes/{id}/endpoints/44772` 가 준 주소로 부른다. Docker 는
  `{hostIP}:{port}/proxy/44772`, K8s 는 파드 IP 다(클러스터 밖에서는 `?use_server_proxy=true` 로 서버 프록시). 응답에
  `headers` 가 있으면 함께 보낸다. **K8s 에서 resume 뒤 주소가 바뀐다** — 연결은 주소를 캐시하되, 연결 실패 한 번에 다시
  해석한다 (B §i, C §d)
- **명령은 `bash -c <문자열>` 의 인자 하나다.** 128 KiB(`MAX_ARG_STRLEN`)를 넘으면 "argument list too long" 이고, 환경 변수
  값도 하나하나 같은 한도다. §9 의 "64 KiB 넘으면 `run.cmd` 를 files API 로 올린다" 와 "명령별 환경은 32 KiB 로 거부" 가
  이 한도에 맞다. 요청 본문 자체의 한도는 없다 (B §d)
- **없는 작업 디렉터리는 400 이다.** 대체 경로가 없다 — 3단계가 로컬 프로바이더를 이렇게 바꾼 것(follow-up 37)이 맞았다
- **stdout 과 stderr 는 따로 오지만 바이트 그대로가 아니다.** 이벤트는 줄 단위다 — `\r` 도 줄바꿈으로 쪼개져 사라지고,
  마지막 줄의 줄바꿈 유무가 보이지 않으며, 빈 줄은 `"\n"` 이벤트로, 잘못된 UTF-8 은 U+FFFD 로 온다. 두 스트림 사이의
  순서는 없다. 이것은 §4 의 결정 사항이다 (B §d)
- **출력 상한이 없다.** 50 MB 가 끝까지 왔고, execd 는 출력을 샌드박스의 `/tmp` 에 파일로 쌓는다(죽인 명령의 파일도
  남는다). 상한은 §9 래퍼의 `head -c` 가 걸고, 프로바이더도 `maxCaptureBytes` 를 넘는 출력을 버리고 명령을 끝낸다
- **kill.** `DELETE /command?id={init 이벤트의 id}` 가 프로세스 그룹에 SIGTERM, 3초 뒤 SIGKILL 을 보낸다. `timeout` 은 곧바로
  SIGKILL 이다. **`setsid` 로 빠져나간 자손은 살아남는다** — execd 는 세션이나 cgroup 단위로 끝내지 않는다. §9 의 "타임아웃
  kill 은 최선 노력" 이 그대로 남는다 (B §d, A §Q5)
- **연결을 끊어도 명령은 죽지 않는다.** 명령의 컨텍스트가 HTTP 요청과 무관하다. `RunningCommand.kill()` 과
  `await(timeout)` 의 초과는 반드시 `DELETE` 를 보낸다. SSE 가 도중에 끊기면 `GET /command/status/{id}`(24시간 보존)로
  종료 코드를 읽을 수 있다
- **종료 코드**는 `error.evalue`(문자열)이고, 신호로 끝나면 `-1` 이다. 0 이면 `execution_complete` 이벤트만 온다
- **백그라운드 모드는 쓰지 않는다** — stdout 과 stderr 를 합친다. §9 의 백그라운드 명령도 포그라운드 `/command` 로 돈다
- **uid.** 명령과 execd 는 이미지의 기본 사용자로 돈다(보통 root). files API 로 쓴 파일은 execd 의 uid 소유이고 기본
  모드가 **755** 다 — 프로바이더는 `write` 에 모드를 명시한다. `/command` 의 `uid` 를 쓰면 files API 가 쓴 파일에 쓰지
  못한다(실측) — §6.1 의 "uid 인자를 쓰지 않는다" 가 맞았다

## 4. 설계를 바꾸는 발견

1. **`extendExpiry` 의 "앞으로만" 과 상한은 서버가 지키지 않는다.** renew 는 절대 시각을 받고 미래인지만 본다 — 줄일
   수도 있고 `max_sandbox_timeout_seconds` 를 넘길 수도 있다(상한은 생성 때만 검사). 그리고 **상한을 알려 주는 API 가
   없다.** → 프로바이더가 현재 `expiresAt` 을 읽어 뒤로 가는 요청을 무시하고, `maxExpiry` 는 프로바이더 설정(서버 설정과
   같은 값)에서 온다. `timeout` 을 빼면 영원히 살므로 프로바이더는 **항상** `timeout` 을 보낸다. (§6.1, §6.4)
2. **pause 는 만료를 멈추지 않고, K8s 에서는 pause 중 renew 가 샌드박스를 망가뜨린다.** 멈춘 샌드박스도 `expiresAt` 에
   지워진다. K8s 에서 멈춘 샌드박스를 renew 하면 컨트롤러가 **원래 이미지로** 파드를 다시 만들고 `Failed` 가 되며
   resume 은 409 다(스냅숏 상태를 잃는다 — 업스트림 버그). → pause 하기 **전에** 만료를 `pausedUntil` 너머로 늘리고,
   멈춘 샌드박스에는 `extendExpiry` 를 부르지 않는다. 프로바이더는 `Paused` 상태의 `extendExpiry` 를 거부한다. pause 중의
   execd 호출은 실패하지 않고 **멈춘다**(Docker) — 모든 execd 호출에 클라이언트 타임아웃을 둔다. (§10.2, 7단계)
3. **출력은 줄 단위로 온다.** SPI 의 `ExecOutcome` 은 바이트를 약속하지만 SSE 로는 바이트를 되살릴 수 없다. 프로바이더는
   이벤트를 줄로 이어 붙이고(`"\n"` 이벤트는 빈 줄) 줄마다 `\n` 을 붙인다. 그 결과 `\r` 은 줄바꿈이 되고, 마지막 줄바꿈은
   늘 있으며, 잘못된 UTF-8 은 U+FFFD 다. §9 는 이것으로 충분하다 — 트레일러는 줄 하나이고 앞에 `\n` 이 있으며, 잘림은
   받은 바이트가 아니라 트레일러의 `out=` · `err=` 로 판단한다. 모델에게 가는 출력이 CR 없이 줄로 펴지는 것은 받아들인다.
   바이트가 필요하면(없다, 지금은) 래퍼가 `run.out` 을 files API 로 읽는 길이 있다. **계약 스위트 하나가 이것에
   걸린다** — `execTruncatesStdoutAndStderrSeparately` 는 끝 줄바꿈이 없는 `bbbbb` 를 그대로 기대하므로 OpenSandbox 에서는
   `bbbbb\n` 이 되어 실패한다. SPI 에 "출력은 줄 단위로 정규화될 수 있다" 를 적고 그 검사를 줄바꿈으로 끝나는 출력으로 바꿀지,
   프로바이더가 출력을 파일로 돌려 받아 바이트를 지킬지(명령마다 HTTP 왕복 둘)는 4단계가 정한다 — 권고는 앞쪽이다(§20).
   (§6.1)
4. **files API 의 쓰기는 제자리에서 자르고 쓴다(원자적이지 않다), 새 파일 전용 모드도 덮어쓰는 이동도 없다.** → 프로바이더의
   `write` 는 같은 디렉터리의 임시 이름에 올린 뒤 `/command` 의 `mv`(덮어쓰기) 또는 `/files/mv`(덮어쓰지 않음)로 옮긴다.
   `/files/mv` 는 "이미 있음" 을 stat 후 rename 으로 검사하므로 원자적이지 않다 — `CREATE_NEW` 는 `ln`(link(2), 3단계의
   로컬 프로바이더와 같은 방법)을 `/command` 로 부른다. "이미 있음" 은 500 `RUNTIME_ERROR` 의 메시지로만 구분된다. (§6.1)
5. **Docker 런타임에는 인증 경계가 없다.** execd 는 토큰을 검사하지 않고(`X-EXECD-ACCESS-TOKEN` 에 아무 값이나 통과) 모든
   인터페이스에 열린다. 서버의 프록시 경로 `/v1/sandboxes/{id}/proxy/{port}` 는 단일 테넌트 모드에서 **API 키 검사를
   건너뛴다** — 샌드박스 id 만 알면 누구든 root 로 명령을 실행한다(실측). K8s 도 단일 테넌트면 프록시 경로가 같다. →
   OpenSandbox 서버의 엔드포인트는 AIMON 노드만 닿는 네트워크에 둔다(운영 계약). 샌드박스 id 를 INFO 로그에 남기지
   않는다. Docker 런타임은 로컬 개발 전용이다. (§12.1, §13.3)
6. **egress 모드는 선언만이 아니라 대조할 수 있다.** `GET /sandboxes/{id}/networkpolicy` 가 `enforcementMode` 를 준다. →
   기동 시에는 여전히 선언(`egress-enforcement: dns+nft`)으로 광고하고, egress 가 있는 프로파일의 seed 가 첫 프로비저닝에서
   실제 모드를 대조해 다르면 FAILED(영구, step `egress`)로 둔다. 선언이 틀린 배포가 조용히 열린 채 돌지 않는다. (§6.4, §11.3)
7. **K8s 강화는 컨테이너 단위여야 한다.** 파드 단위 `runAsUser`/`runAsNonRoot` 는 권한이 필요한 `execd-installer` init
   컨테이너와 egress 사이드카에도 걸려, egress 정책이 있으면 생성이 500 `InitContainerFailed` 다. 그리고 egress 정책이
   있으면 서버가 샌드박스 컨테이너의 `capabilities.drop` 을 `[NET_ADMIN]` 으로 **덮어쓴다**(템플릿의 `[ALL]` 이 사라진다 —
   비루트 + NNP 라 실효 cap 은 0 이었다). → §13.3 의 배포 계약에 "강화는 샌드박스 컨테이너의 securityContext 로" 를 적고,
   seed 의 uid 검사가 실제 결과를 본다

## 5. 볼륨

- 생성: `volumes[].pvc{claimName, createIfNotExists, storageClass, storage, accessModes}` + 마운트마다 `readOnly`.
  `claimName` 은 DNS 라벨 규칙(§6.3 의 이름 규칙이 맞는다)
- **삭제 경로는 우리 몫이다.** Docker 는 `docker volume rm`(마운트 중이면 "volume is in use" → `VolumeInUseException`),
  K8s 는 PVC 삭제(마운트 중이면 `pvc-protection` 이 마지막 마운트가 사라질 때까지 붙잡는다 — 그동안 그 PVC 를 쓰는 새
  샌드박스는 60초 뒤 504). K8s 의 `VolumeReclaimer` 는 PVC 가 `Terminating` 이면 "마운트 중" 으로 본다
- `deleteOnSandboxTermination` 은 쓰지 않는다. 볼륨을 만든 첫 샌드박스가 소유자가 되어, 다른 샌드박스가 아직 쓰는 중에
  지우려다 409 로 실패하고 **다시 시도하지 않는다**(Docker 에서 새는 것을 확인)
- PVC 에 라벨이 없으므로 §6.3 의 이름 규칙(접두어)만이 식별 수단이다 — 설계대로다. 실패한 생성이 자동 생성 PVC 를 남기는
  경우(K8s, RWX 불가 스토리지)도 접두어로 찾아 지운다. 서버가 egress 샌드박스마다 만드는 `opensandbox-runtime-{id}` 볼륨은
  접두어 밖이다

## 6. egress 와 자격 증명

- `networkPolicy{defaultAction: deny, egress: []}` 는 전부 막는다. 정책이 **없으면** 전부 허용이다 — §6.4 의 "egress 를
  생략하지 않는다" 가 맞다
- `dns` 모드: 이름 허용 목록만. 서버의 호스트 IP · 옆 샌드박스 · `1.1.1.1` 에 IP 로 닿는다. `dns+nft`: 허용한 이름이
  돌려준 IP 만 열린다. 직접 DNS(8.8.8.8)도 사이드카가 가로챈다
- 샌드박스 안에서 자기 정책을 바꿀 수 없다(사이드카 API 가 토큰을 요구, 401)
- 자격 증명 vault: 바인딩 `{schemes, hosts, methods, paths}` — GET `/headers` 에만 키가 붙고 POST · 다른 경로에는 붙지
  않았다. 사이드카의 `OPENSANDBOX_EGRESS_CREDENTIAL_VAULT_REQUIRE_SCOPED_MATCH` 가 method · path 지정을 강제한다. §13.2 의
  겹침 검사가 쓸 정의가 이 모양이다. 샌드박스가 MITM CA 를 신뢰하는지는 확인하지 않았다(4단계)

## 7. 네트워크 격리 — 운영자 계약

- **Docker**: 막을 방법이 OpenSandbox 안에 없다. 정책 없는 샌드박스는 서버 · 모든 샌드박스 · 인터넷에 닿고, `dns+nft`
  정책은 나가는 쪽만 막아 들어오는 명령은 그대로다. 모든 샌드박스에 deny 정책을 걸고 `publish_host` 를 루프백으로 묶고 호스트
  방화벽을 거는 조합이 있지만, 그것을 AIMON 이 확인할 수 없다 — Docker 런타임은 `NETWORK_ISOLATION` 을 선언하지 않는다
- **K8s**: 운영자 NetworkPolicy 가 필요하고 충분하다. 확인한 정책(`spike/opensandbox/k8s/deploy/netpol-sandbox-isolation.yaml`):
  샌드박스 네임스페이스의 모든 파드에 대해 ingress 는 서버 파드에서만, egress 는 kube-dns 와 사설 대역을 뺀 인터넷만.
  이것으로 옆 샌드박스 · 서버 · K8s API · kubelet 이 막히고, 서버→샌드박스 프록시와 NFS 마운트(kubelet 이 노드에서
  마운트)는 산다
- seed 의 "서버 엔드포인트에 닿지 않는다" 자가 점검은 두 메커니즘 모두에서 통과한다. 들어오는 쪽(옆 샌드박스→나)은 이
  점검이 보지 못한다 — 그래서 선언이 필요하다

## 8. SDK

Java 21 프로바이더는 **REST 를 직접** 부른다(JDK `HttpClient` + Jackson). Java SDK 는 없고, Kotlin SDK
(`com.alibaba.opensandbox:sandbox:1.1.0`, OkHttp 4)는 `run()` 이 출력을 상한 없이 모아 블로킹하고, `kill()` 에 필요한 명령
id 가 콜백으로만 오며, 내부가 Kotlin `internal` 이다. REST 표면은 작다 — 수명 API 여덟 · execd 의 `/command` ·
`/command/status` · 파일 API 몇 개. multipart 업로드는 `metadata` 파트를 **파일 파트**(filename 포함)로 보내야 한다(아니면 400).
(A §Q12, B §e)

## 9. CI

- **Docker 런타임(`@Tag("docker")`)**: `ubuntu-latest` 의 Docker 로 충분하다. 이미지 `opensandbox/execd:v1.1.0`(171 MB) ·
  샌드박스 이미지 · egress 테스트에만 `opensandbox/egress:v1.1.7`(560 MB). 서버는 소스 고정 커밋 + `uv` 또는
  `opensandbox/server` 이미지에 Docker 소켓을 마운트한다(DinD 불필요). 기동 약 1초, 샌드박스 생성 약 1초. Linux 에서는
  `resolve_internal = true`(기본)가 동작하고, `publish_host = "127.0.0.1"` 로 execd 포트를 러너 밖에 열지 않는다. 계약 스위트
  대부분이 이것으로 돈다. amd64 의 execd 이미지와 러너 커널의 `dns+nft` 는 **아직 러너에서 돌려 보지 않았다**
- **K8s 런타임(`@Tag("k8s")`)**: kind + helm, 준비까지 3–4분. 차트의 기본 레지스트리(Aliyun)를 Docker Hub 로 바꾸고,
  네임스페이스를 helm 소유 메타데이터와 함께 미리 만들고(차트가 Namespace 를 렌더링해 `--create-namespace` 와 충돌), 서버
  `api_key` 를 준다(비우면 기동 거부). pause 테스트에만 로컬 레지스트리와 containerd `config_path` 패치, RWX 테스트에만 NFS
  provisioner. 테스트 JVM 은 `port-forward` + 서버 프록시로 닿는다. 릴리스 전과 수동 실행용이다
- 서버 버전 고정: 차트 `1.1.1-rc.1` 은 RC 다. 4단계 릴리스는 정식 태그에 고정한다

## 10. 업스트림에 올릴 것

- K8s: 멈춘 샌드박스의 renew-expiration 이 원래 이미지로 파드를 다시 만들고 `Failed` 로 만든다(§4-2)
- renew-expiration 이 만료를 줄이고 `max_sandbox_timeout_seconds` 를 넘기는 것을 허용한다 — 스펙은 연장이라고 적는다
- 단일 테넌트 모드의 프록시 경로가 API 키를 건너뛴다 — 의도된 동작이지만 문서에 경고가 없다
- 차트가 Namespace 를 렌더링해 `--create-namespace` 와 충돌한다
