# 보안

무엇이 샌드박스의 격리를 지키고, 그중 이 모듈이 맡는 몫과 맡지 않는 몫이 어디까지인지 다룬다. 보안 층, 닫힌 실패의 성립 조건,
egress · 자격 증명 규칙, 훅의 경계, 서버 엔드포인트 배치, 쿼터와 admission, OOM 순서가 여기 있다.

> 관련: [개요](workspace-sandbox.md) · [바인딩](workspace-sandbox-binding.md) · [셸](workspace-sandbox-shell.md) ·
> [프로바이더](workspace-sandbox-provider.md) · [OpenSandbox](workspace-sandbox-opensandbox.md) ·
> [설정](workspace-sandbox-configuration.md) · [훅 신원 기록](workspace-sandbox-hook-identity.md)

## 1. 이 모듈의 몫

이 모듈은 격리의 최종 방어선이 아니다([개요 §1.2](workspace-sandbox.md)). 컨테이너 탈출은 런타임(gVisor / Kata)과 OpenSandbox 가,
네트워크 차단은 OpenSandbox 의 network policy 와 운영자의 네트워크 정책이 막는다. 이 모듈이 맡는 것은 셋이다.

- **닿는 범위를 정한다.** 어느 실행이 어느 워크스페이스 · 슬롯에 어떤 주체로 닿는지를 바인딩 정책과 owner 검사로 정한다
  ([바인딩 §4 · §5](workspace-sandbox-binding.md))
- **격리를 요구하고, 조용히 풀리지 않게 한다.** 프로파일이 요구하는 격리를 프로바이더가 광고하지 않으면 기동을 거부하고(§3),
  seed 가 생성된 샌드박스에서 결과를 다시 검사한다
- **비밀이 샌드박스에 들어가지 않게 한다.** 자격 증명은 egress 에서 주입하고(§5), 샌드박스 id 를 드러내지 않는다(§7)

**명령 블랙리스트(`mount`, `nsenter` …)는 두지 않는다.** 우회가 쉬워 보안 경계가 될 수 없고, 코어의 권한 훅이 이미 정책을 걸 자리를
준다.

## 2. 보안 층

| 층 | 무엇을 막나 | 누가 |
|---|---|---|
| 바인딩 정책 + owner 검사 | 다른 워크스페이스 · 테넌트 · 사용자의 샌드박스에 닿기 | 이 모듈. 매니저의 모든 공개 진입점에서 검사한다([바인딩 §5](workspace-sandbox-binding.md)) |
| 슬롯 프로파일 고정 + `SandboxStart` 권한 부분집합 **(계획 — 구현 단계 5)** | 호출 순서나 인젝션으로 더 넓은 신뢰 도메인에서 돌기 | 이 모듈([바인딩 §6 · §7](workspace-sandbox-binding.md)) |
| 권한 훅(`PreToolUse` · `permissionRequest` 이벤트) | 허용되지 않은 도구 · 명령 | 코어 — 샌드박스와 무관하게 그대로 적용된다 |
| 프로파일: `runtimeClass` | 컨테이너 탈출 | OpenSandbox 를 통한 gVisor / Kata. `RUNTIME_CLASS` 를 광고하지 않으면 기동 거부 |
| 프로파일: egress 허용 목록 | 데이터 유출, 메타데이터 엔드포인트, 내부망 | OpenSandbox network policy(`dns+nft`). **기본 전부 차단**(§4) |
| 프로파일: 자격 증명 바인딩 | 토큰 탈취, 토큰 오남용 | OpenSandbox credential vault — 샌드박스에는 실제 값이 없고 egress 에서 주입한다(§5) |
| 프로파일: 자원 | 자원 고갈 | cpu · memory · disk · pids |
| 비루트 · 권한 상승 금지 · capability 제거 · 서비스 계정 토큰 미마운트 | 권한 상승, K8s API 접근 | 샌드박스 컨테이너의 SecurityContext(OpenSandbox 서버의 템플릿). `HARDENED_SECURITY_CONTEXT` 를 광고하지 않으면 기동 거부하고, seed 가 uid 와 토큰 경로를 다시 검사한다 |
| 샌드박스 사이 · 제어면 트래픽 차단 | 옆 샌드박스의 execd, OpenSandbox 서버 · K8s API 에 닿기 | 운영자의 네트워크 정책. `NETWORK_ISOLATION` 을 광고하지 않으면 기동 거부하고, seed 가 제어면 엔드포인트로의 연결이 실패하는지 점검한다 |
| 프로파일: `sharedAccess` **(계획 — 구현 단계 5)** | `/shared` 를 거친 슬롯 간 영향(bare 저장소의 훅 · ref, 공유 문서) | 볼륨 마운트 모드. **기본 `none`**([파일 시스템 §8](workspace-sandbox-filesystem.md)) |
| admission + 워크스페이스 쿼터(쿼터는 **계획 — 구현 단계 5**) | 워크스페이스 · 테넌트가 샌드박스를 무한히 만들기 | `SandboxAdmission` + `WorkspaceQuota`(§8) |

각 capability 의 뜻과 광고 조건은 [프로바이더 §6](workspace-sandbox-provider.md), OpenSandbox 에서의 대응(Docker 런타임은
`NETWORK_ISOLATION` · `HARDENED_SECURITY_CONTEXT` · `RUNTIME_CLASS` 를 선언할 수 없다는 것 포함)은
[OpenSandbox §2](workspace-sandbox-opensandbox.md), 운영자가 준비할 정책과 템플릿은 [설정 §5](workspace-sandbox-configuration.md),
seed 의 검사는 [수명 §3](workspace-sandbox-lifecycle.md) 이 소유한다. 신뢰 도메인 — 어느 실행들이 한 샌드박스를 함께 써도 되는가 —
은 [바인딩 §6](workspace-sandbox-binding.md) 이 소유한다.

## 3. 닫힌 실패

공통 원칙(도구는 예외를 던지지 않는다, 격리를 못 하면 호스트로 되돌아가지 않는다)은 [개요 §7](workspace-sandbox.md) 이 소유한다. 이
절은 그 원칙이 성립하는 조건을 쓴다.

**호스트 환경이 조용히 남는 경로가 구조적으로 없다.** 샌드박스 제공자의 `resolve()` 가 실패하면 코어가 호출마다 에러를 내는
`UnavailableExecutionEnvironment` 를 넣는다([코어 실행 환경 설계 §5.1](https://github.com/kangwoo/aimon-core/blob/main/docs/design/tool/execution-environment.md)).
환경 키는 한 번만 쓸 수 있고 제공자는 하나다. 프로바이더에 닿지 못해도 마찬가지로 사용 불가 환경이 되고, 호스트로 폴백하지 않는다.

**이 보장은 도구가 도는 모든 경로가 제공자가 해석한 환경을 쓸 때만 성립한다.** 코어에서 `resolve()` 를 부르는 곳은 넷이다 —
`OrcaAgentExecutor.execute()` 의 시작(메인 턴, 그리고 같은 실행의 분기인 슬래시 커맨드 흐름), 포크, 스케줄 루틴, 부모 환경이 없는
워크플로 러너. 슬래시 커맨드가 손으로 조립하는 컨텍스트도 실행 시작에 해석한 환경을 싣는다. 코어에 새 경로가 생겨도 이 성질이
깨지지 않았는지는 이 모듈의 통합 테스트가 슬래시 커맨드 경로를 덮어 확인한다([테스트 §5.3](workspace-sandbox-testing.md)).

**격리를 요구한 프로파일은 격리 없는 프로바이더 위에서 돌지 않는다.** 프로파일이 요구하는 capability 를 프로바이더가 광고하지 않으면
기동 시점에 거부한다. egress 를 막으라는 프로파일이 egress 정책 없는 프로바이더 위에서 열린 채로 돌면 안 되기 때문이다. 요구를
설정에서 유도하는 규칙은 [프로바이더 §6](workspace-sandbox-provider.md), 기동 검사 목록은 [설정 §3](workspace-sandbox-configuration.md)
이 소유한다. 격리를 일부러 풀려면 프로파일에 `insecureAllow` 를 적어야 하고 기동 로그에 경고가 남는다.

## 4. egress

**"전부 차단"의 범위.** egress 허용 목록에 없는 목적지는 다음을 포함해 모두 막힌다.

| 대상 | 차단 방법 |
|---|---|
| 인터넷 도메인 | network policy 허용 목록 + `defaultAction: deny` |
| IP 로 직접 붙기(클라우드 메타데이터 `169.254.169.254` 포함) | `dns+nft` 모드의 IP 규칙. `dns` 모드로는 막히지 않으므로 운영자가 `dns+nft` 를 선언하지 않으면 `EGRESS_POLICY` 를 광고하지 않는다 |
| DNS 로 새기(질의 이름에 데이터 싣기) | 허용 목록 밖 이름의 해석 거부(egress 사이드카의 DNS 필터) |
| 다른 샌드박스 · 다른 테넌트의 execd(east-west) | 샌드박스 사이 트래픽 차단. OpenSandbox 가 강제하지 않으므로 운영자가 건다(K8s 는 네임스페이스 NetworkPolicy). 운영자가 선언하지 않으면 `NETWORK_ISOLATION` 을 광고하지 않아 기동이 거부된다 |
| OpenSandbox 서버 API, K8s API, 클러스터 내부 서비스 | 위와 같다. 샌드박스는 자기 execd 로 들어오는 연결만 받는다 |
| `/shared` NFS 서버 **(계획 — 구현 단계 5)** | 마운트는 노드(kubelet)가 하므로 샌드박스 네트워크에서 닿을 필요가 없다. 차단 대상이다 |

각 항목은 [테스트 §5.9](workspace-sandbox-testing.md) 의 보안 시나리오가 확인한다.

**`egress: []` 는 deny 로 명시해 보낸다.** 프로바이더는 `EGRESS_POLICY` 를 광고했더라도 egress 를 생략하지 않는다. OpenSandbox 는
network policy 가 없거나 비면 전부 허용하므로([OpenSandbox §3.5](workspace-sandbox-opensandbox.md)), 프로파일의 빈 목록은
`defaultAction: deny` 와 빈 허용 목록으로 보내야 "전부 차단" 이 된다. 같은 이유로 프로파일에 `egress` 가 있으면(빈 목록 포함)
`EGRESS_POLICY` 를 요구한다.

## 5. 자격 증명

**범위를 host 만이 아니라 경로 prefix(저장소)와 메서드까지 좁힌다.** `github.com` 전체에 토큰을 주입하면 모델은 그 토큰이 닿는 모든
저장소에 push · force-push 할 수 있고, 허용 도메인 자체가 유출 통로가 된다. 이 범위 지정은 `CREDENTIAL_INJECTION` 의 필수 계약이다 —
vault 가 host 단위 주입만 지원하면 그 프로바이더는 `CREDENTIAL_INJECTION` 을 광고하지 않는다. 범위는 scheme · host · method ·
path(glob)로 정의한다. port 는 범위에 넣지 않는다 — port 는 scheme 을 따르고 vault 에서도 deprecated 라서, 겹침 검사는 port 를
무시하고 "겹친다" 쪽으로 판단한다.

**샌드박스 안에 비밀을 두지 않는다.**

- 프로파일의 `env` 에는 비밀을 넣지 않는다 — 샌드박스 안에서 평문으로 보이고 셸 상태 파일에도 남는다
- 비밀은 샌드박스를 만들 때 공급자에서 읽어 vault 에만 보낸다. 교체는 다음 generation 부터 적용된다. vault 에는 그 샌드박스의
  프로파일이 이름으로 부른 바인딩만 보낸다
- 비밀은 로그와 `toString` 에 남지 않는다(자격 증명 정의와 API 키는 가린다)
- vault 바인딩이 실패하면 생성이 실패한다. 400 · 403 은 영구, 그 밖은 일시 실패이고, 이미 만든 샌드박스는 지우지 않고 조정에
  맡긴다([수명 §6.2](workspace-sandbox-lifecycle.md))

**(계획 — 구현 단계 5)** **git seed 의 자격 증명은 런타임 자격 증명과 따로 두고 읽기 전용으로 만든다.**

**한 프로파일의 바인딩끼리 범위가 겹치면 기동을 거부한다.** vault 가 겹친 요청에 무엇을 주입할지 정해져 있지 않기 때문이다. 그래서
**(계획 — 구현 단계 5)** git seed 가 들어오면 같은 저장소에 쓰기 자격 증명을 가진 슬롯은 seed 도 그 자격 증명으로 받고, 읽기 전용 seed 자격 증명은 런타임 자격 증명이 없는
슬롯에만 쓴다.

**기동 검사.** 바인딩의 정의(범위 · 주입 방식 · 비밀)는 프로바이더 설정에 있고, 매니저는 `ProviderCapabilities` 로 이름과 범위만
본다 — 비밀은 보지 않는다. 범위 같은 선언은 프로바이더가 데이터로 내고 검사는 매니저의 기동 검사가 한 곳에서 한다. 프로파일마다
본다.

1. 프로파일이 부른 바인딩 이름이 모두 정의되어 있는가
2. 두 바인딩이 겹치지 않는가. 겹침은 scheme 과 method 가 겹치고, 어떤 host 쌍이 겹치고(같음, `*.s` 와 `.s` 로 끝나는 host, 한쪽
   접미사가 다른 쪽 접미사로 끝나는 두 와일드카드), 어떤 path 쌍이 겹치는(같은 정확 경로, prefix `p*` 와 `p` 로 시작하는 정확 경로,
   한쪽이 다른 쪽으로 시작하는 두 prefix) 것이다
3. 모든 바인딩 host 를 프로파일의 `egress` 가 **명시적으로** 허용하는가. vault 는 egress 정책이 허용하지 않는 host 의 바인딩을
   거부한다. 1.1.x 의 vault 는 `defaultAction: allow` 를 허용으로 치지 않았지만, 1.2.0 의 vault 는 허용으로 치고 경고만 남긴다(1.2.0
   소스로 확인). 그래서 `credentials` 가 있는데 `egress` 가 없는 프로파일은 거부한다(확인 필요: 이 규칙의 이유를 1.2.0 vault 에 맞춰
   다시 적을지). 덮는 규칙은 같은 항목, 또는 `*.s` 가 `h.s` 를 덮는 것이다. `*.s` 는 `s` 를 덮지 않는다(1.2.0 소스로 확인)

기동 검사 목록 전체는 [설정 §3](workspace-sandbox-configuration.md), vault 의 서버 쪽 동작은
[OpenSandbox §3.5](workspace-sandbox-opensandbox.md) 가 소유한다.

## 6. 훅

**스킬이 선언한 훅의 셸 액션은 실행의 샌드박스에서 돈다.** 코어의 기본 셸 액션 실행기(`DefaultShellActionExecutor`)는 셸을 쥐지
않고, 훅을 일으킨 실행의 `environment.shell()` 에서 액션을 돌린다. 그 실행의 환경이 샌드박스이면 스킬 훅도 샌드박스에서 돌고,
호스트에서는 돌지 않는다(호스트가 다른 실행기를 끼우면 그 실행기가 정한다). 그래서 이 모듈은 셸 액션 훅을 거부하는 파서를 따로 주지
않는다. 스킬 파일이 선언한 셸 코드를 아예 받지 않으려는 배치는 코어의 `NoOpShellActionExecutor` 로 `SkillHookSetParser` 를 만든다 —
코어가 이미 주는 조립이다.

`hooks.json` 의 훅도 실행 중 이벤트이면 코어가 그 실행의 환경에서 격리 실행으로 돌리므로 샌드박스에서 돈다. 호스트 셸에서 도는 것은
`"shell": "host"` 를 준 핸들러, `"shell"` 없이 `asyncRewake` 를 선언한 핸들러(다시 깨울 수 있는 이벤트에서 — 코어가 경고를 남긴다),
실행이 없는 발화뿐이다.

**가드는 fail-closed 다.** 샌드박스가 사용 불가(바인딩 거부, 프로비저닝 실패, 프로바이더 접속 불가)이면 스킬의 `preTool` 셸 가드가
걸린 도구 호출은 이유와 함께 막히고, `onStart` 셸 가드가 있는 스킬 포크는 시작하지 않는다. 관찰만 하는 훅에는 `failOpen: true` 를
준다.

**가드의 판정은 샌드박스에서 나온다.** 훅 명령의 상대 경로는 샌드박스의 루트를 기준으로 하고, 종료 코드 2 는 도구 호출을 막는다.

**훅은 모델의 셸 세션 밖에서 돈다.** 훅 명령은 셸 락을 잡지 않으므로 모델의 명령이 셸을 쥐고 있어도 가드가 "shell is busy" 를 만나
차단으로 읽지 않는다. 스킬 훅은 세션의 cwd 와 환경 변수에서 시작하지만 그 `cd` · `export` 는 남지 않는다. `hooks.json` 의 격리
실행은 모델의 cwd · export 를 읽지도 않는다. 명령 종류별 동작은 [셸 §5.3 · §5.4](workspace-sandbox-shell.md) 가 소유한다.

**훅의 파일을 에이전트의 손에서 치운 것은 좁히기이지 권한 경계가 아니다.** 훅과 격리 실행의 명령 · stdin · 출력 · 타임아웃 표지는
에이전트가 쓸 수 있는 run 파일에 있지 않고, 실행 디렉터리(`AIMON_HOOK_DIR` · `AIMON_SKILL_DIR`)는 실행마다 새로 만들어 다이제스트로
확인하는 비공개 디렉터리다(메커니즘은 [셸 §9](workspace-sandbox-shell.md), 실패의 코어 매핑은 [셸 §11](workspace-sandbox-shell.md)). 그래서 에이전트가 실행 **전에** 심어 두거나 실행
**사이에** 고쳐 둔 파일은 훅에 닿지 않는다. 남는 것:

- **스킬 훅은 세션의 셸 상태에서 시작한다.** 에이전트가 남긴 cwd · export 는 여전히 스킬 훅에 닿는다. `hooks.json` 의 격리 실행은
  그것도 읽지 않는다
- **훅이 도는 동안에는 같은 uid 다.** 에이전트의 프로세스는 그 비공개 디렉터리, `/proc/<pid>/fd`, argv 에 든 트레일러 nonce 에 닿을 수
  있다. 트레일러를 위조하거나, 업로드를 지우거나, 래퍼를 죽여 훅을 실패(코어의 `EXECUTION_FAILED` — `failOpen` 가드는 통과)시킬 수도
  있다. 실행 디렉터리 안의 `.timedout` 표지 대신 JVM 의 시계로 타임아웃을 판정하는 것도 이 때문이다 — 같은 uid 가 닿는 곳의 표지는
  위조할 수 있지만 시계는 위조할 수 없다

에이전트와 훅을 uid 로 가르는 일은 [훅 신원 기록](workspace-sandbox-hook-identity.md) 이 다룬다(§10). 그때까지 에이전트의 손이 도는
중에도 닿으면 안 되는 스크립트에는 `"shell": "host"` 를 준다.

## 7. 서버 엔드포인트 배치

**OpenSandbox 서버의 엔드포인트는 AIMON 노드만 닿는 네트워크에 둔다.** 단일 테넌트 모드의 서버 프록시 경로는 API 키를 검사하지 않고
execd 도 토큰을 검사하지 않는다([OpenSandbox §3](workspace-sandbox-opensandbox.md)). 서버나 execd 포트에 닿는 누구든 샌드박스 id 만
알면 root 로 명령을 실행할 수 있다. 운영 절차는 [OpenSandbox K8s 가이드](../guide/opensandbox-kubernetes.md) 에 있다.

**그래서 프로바이더의 샌드박스 id 를 드러내지 않는다.** id 와 네트워크 도달이 합쳐지면 root 이기 때문이다.

- 샌드박스 id 는 INFO 로그에 남기지 않는다
- 모델이 보는 문자열 · 도구 인자, 관리 뷰, 운영자 목록에 싣지 않는다. 운영자 목록은 id 의 해시로 식별한다
  ([관리 §5](workspace-sandbox-management.md))
- 기록하는 실패 이유에서도 서버 메시지가 인용한 id 를 해시로 바꾼다

**Docker 런타임은 로컬 개발 전용이다.** 이 경계를 세울 수 없다 — execd 가 토큰 없이 열려 있어 옆 샌드박스가 root 명령을 실행할 수
있고, egress 정책은 들어오는 쪽을 막지 않는다. 그래서 Docker 런타임의 프로파일은 `insecureAllow: [NETWORK_ISOLATION,
HARDENED_SECURITY_CONTEXT]` 를 적어야 돈다.

**API 키가 신뢰 경계다.** API 키를 가진 누구든 샌드박스의 라벨(`aimon.at/*`)을 고칠 수 있으므로, 생성 때의 라벨 대조는 인코딩 버그와
충돌을 막는 장치이지 변조 방지가 아니다([프로바이더 §4](workspace-sandbox-provider.md)). API 키로 라벨을 고칠 수 있다는 사실은
1.2.0 소스로 확인했다(`PATCH /sandboxes/{id}/metadata`).

## 8. 쿼터와 admission

워크스페이스는 세션마다 암묵적으로 생기므로 워크스페이스 쿼터는 테넌트 상한이 되지 못한다. 테넌트 단위 상한은 `SandboxAdmission` 이
맡는다.

**admission.** `connect` 는 프로바이더 자원을 늘리는 분기 — 새 프로비저닝, FAILED 재시도, 잃은 샌드박스의 재생성 — 에서 슬롯을
`PROVISIONING` 으로 올리는 CAS 전에 `admit(owner, profile)` 을 부른다. resume(**계획 — 구현 단계 7**)과 `SandboxStart`(**계획 —
구현 단계 5**)도 같은 자리에서 부른다. 거부되면 이유와 함께 사용 불가가 된다.

- **기본 구현은 전부 허용이 아니다.** `DefaultSandboxAdmission` 은 같은 테넌트의 **OPEN 워크스페이스**에 있는 RUNNING · PROVISIONING
  슬롯을 저장소 scan 으로 세어 `maxRunningPerTenant`(기본 10)에 이르면 거부한다. OPEN 만 세는 것은 CLOSING 의 슬롯은 파괴 중이고
  CLOSED 는 슬롯이 없어 프로바이더 자원을 쥐지 않기 때문이다
- **최선 노력의 상한이다.** 셈이 CAS 로 보호되지 않아 경합 시 조금 넘칠 수 있다. 정확한 상한이 필요한 애플리케이션은 자기 저장소로
  `SandboxAdmission` 을 구현해 어셈블리에 넣는다
- **끄려면 명시한다.** 전부 허용은 `maxRunningPerTenant` 를 `unlimited` 로 적었을 때만이다. `requirePrincipal` 이 켜진 다중 테넌트
  배치에서 그렇게 하면 기동 로그에 경고가 남는다. 설정 이름과 기본값은 [설정 §2](workspace-sandbox-configuration.md) 가 소유한다

**워크스페이스 쿼터 (계획 — 구현 단계 5).** `WorkspaceQuota` — `maxSlots`(기본 6) · `maxRunning`(기본 3) · `maxCpu` · `maxMemory`. 레코드를
만들 때 설정에서 복사해 두고(필드는 [도메인 §1](workspace-sandbox-domain.md)), 다중 슬롯과 함께 집행한다. 슬롯을 `PROVISIONING`
으로 올리는 CAS 에서 같이 검사하므로 새 프로비저닝은 경합으로 넘치지 않는다. resume 은 쿼터 확인 → 프로바이더 resume → RUNNING CAS
순서라 두 슬롯이 동시에 resume 하면 잠깐 넘칠 수 있다. 진 쪽은 다시 판단해 쿼터 에러를 내고, 이미 깨운 샌드박스는 레코드가 PAUSED
이므로 조정이 다시 멈춘다 — resume 의 쿼터는 최선 노력이다.

**공유 볼륨 용량 (계획 — 구현 단계 5).** 공유 볼륨의 용량은 볼륨이 강제한다. `rw` 슬롯 하나가 볼륨을 채우면 같은 워크스페이스의 다른
슬롯이 영향을 받지만, `rw` 슬롯들은 이미 한 신뢰 도메인이므로 워크스페이스 밖으로 번지지 않는다.

## 9. OOM 순서

메모리 한도는 샌드박스 cgroup 하나에 걸리고, execd 도 명령과 같은 cgroup 에 있다. OOM killer 가 execd 를 고르면 샌드박스 전체가
응답하지 않는다. 그래서 셸 래퍼는 시작하자마자 자기 `oom_score_adj` 를 500 으로, inner bash 는 1000 으로 올린다. 명령이 띄운 프로세스는
1000 을 물려받으므로 OOM kill 은 명령 → 래퍼 · 워치독 → execd(0) 순서로 고른다.

- 값을 올리는 데는 권한이 필요 없고, `/proc` 이 없는 샌드박스에서는 조용히 건너뛴다
- 한 번의 OOM 뒤 메모리가 돌아오기 전에 두 번째 OOM 이 나면 남은 프로세스 중 가장 큰 것이 죽는다. 이 순서 덕에 두 번째 kill 도 명령의
  inner bash 를 고르고, 그 실행은 출력 없이 137 로 끝나지만 샌드박스와 다음 명령은 살아 있다

## 10. 열린 질문

- **훅을 다른 uid 로 돌리기** — 훅의 파일을 치운 것은 실행 사이의 변조를 좁혔을 뿐 권한 경계가 아니다(§6). 경계는 execd 가 uid 를
  바꿔 exec 하고, 훅 파일과 그 부모 디렉터리가 에이전트의 uid 로 쓸 수 없는 곳(`/workspace` 밖)에 있어야 선다. OpenSandbox execd 는
  `/command` 에 uid 인자를 받고, 1.1.1 부터 files API 도 uid 로 부를 수 있다. 막는 것은 업스트림이다 — OpenSandbox 그대로는 에이전트의
  uid 가 execd 의 root API 에 닿는다. 이 블로커는 1.1.1 에서 확인했고, 1.2.0 에서는 확인하지 않았다. 알려진 것과 막힌 것, 이미지
  계약과 seed 검사 초안은 [훅 신원 기록](workspace-sandbox-hook-identity.md) 에 있다
