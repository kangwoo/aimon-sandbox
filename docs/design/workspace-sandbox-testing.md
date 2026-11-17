# 워크스페이스 샌드박스 — 테스트

이 문서는 워크스페이스 샌드박스의 설계를 어떤 테스트로 지키는지 정한다. 다루는 것은 계약 스위트, 테스트용 프로바이더,
세 테스트 계층과 CI, 멀티 노드 검증 전략, 반드시 통과해야 하는 시나리오, 셸 엔진의 남은 프로세스 검사가 보장하는 범위다.
시나리오가 확인하는 규칙의 정본은 각 주제 문서에 있고, 여기서는 시나리오와 기대 결과만 적는다.

> 관련: [개요](workspace-sandbox.md) · [도메인](workspace-sandbox-domain.md) · [프로바이더](workspace-sandbox-provider.md) ·
> [셸](workspace-sandbox-shell.md) · [셸 엔진](workspace-sandbox-shell-engine.md) · [OpenSandbox](workspace-sandbox-opensandbox.md) ·
> [설정](workspace-sandbox-configuration.md) · [Kubernetes 설치 가이드](../guide/opensandbox-kubernetes.md)

## 1. 계약 스위트

SPI 마다 계약 스위트를 하나씩 둔다. 계약 스위트는 그 SPI 를 구현한 모든 구현이 통과해야 하는 JUnit 5 추상 클래스이며,
구현 쪽 테스트가 상속해서 돌린다.

**`aimon-sandbox-testkit` 은 발행한다.** 이 저장소 밖에서 만든 프로바이더와 저장소 구현도 같은 계약 스위트를 통과해야 하기
때문이다. 스위트가 JUnit 5 추상 클래스이므로 testkit 은 JUnit · AssertJ 를 `api` 의존성으로 싣는다. 스위트를 상속하는 쪽은
스위트를 작성할 때 쓴 버전을 그대로 받는다.

| 스위트 | 대상 SPI | 정본 계약 |
|---|---|---|
| `SandboxProviderContract` | `SandboxProvider` · `SandboxConnection` · `SharedVolumes` | [프로바이더 §1–§6](workspace-sandbox-provider.md) |
| `SandboxWorkspaceStoreContract` | `SandboxWorkspaceStore` | [도메인 §4](workspace-sandbox-domain.md) |
| `SessionOptionsStoreContract` | `SessionOptionsStore` | [도메인 §5](workspace-sandbox-domain.md) |
| `RuntimeFileSystemContract` | 코어 `RuntimeFileSystem` 의 샌드박스 구현 | [셸 엔진 §3](workspace-sandbox-shell-engine.md) |

### 1.1 프로바이더 계약 스위트

`SandboxProviderContract` 는 다음을 확인한다.

- 같은 키로 차례로 `create` 하면 샌드박스는 하나다. `destroy` 는 멱등이다.
- exec 가 stdout 과 stderr 를 **구분해서** 돌려주고 종료 코드를 돌려준다. 작업 디렉터리와 환경 변수가 전달된다.
- 출력 상한을 넘으면 stdout · stderr 각각 잘림으로 표시된다. 출력은 `OutputSink` 로도 전달된다.
- `kill()` 은 그 호출의 프로세스 그룹만 끝내고, 그 그룹에 남은 백그라운드 자손도 끝낸다.
- files 의 `read` · `write` · `stat` · `list` · `move` 가 동작한다. `stat` 의 변경 감지 계약이 성립한다 — 같은 크기로 1초 안에
  다시 써도 mtime 이나 etag 가 바뀐다.
- `list(labels)` 는 모든 페이지를 돌려준다. `status` 는 라벨을 돌려주고, `EXPIRY` 를 광고하면 만료 시각도 돌려준다. `status`
  의 `createdAt` 은 미래가 아니다.
- 없는 샌드박스에 대한 `extendExpiry` · `resume`, 그리고 `destroy` 전에 연 연결의 exec · files 호출은
  `SandboxNotFoundException` 이다.
- `SharedVolumes.delete` 는 멱등이고, 마운트 중이면 `VolumeInUseException` 이다.
- capability 로 광고한 기능만 동작한다.

출력 비교는 줄바꿈으로 끝나는 출력을 쓴다. SPI 가 출력의 줄 정규화를 허용하기 때문이다(허용을 남기는 이유와 지원 서버의 동작은
[프로바이더 §2.3](workspace-sandbox-provider.md)).

공유 서버에서 도는 프로바이더 테스트는 `deployment()` 를 실행마다 다른 값으로 바꾼다. 그래야 동시에 도는 실행이 서로의
샌드박스를 나열하거나 조정하지 않는다.

셸 상태(cwd · export)는 SPI 의 일이 아니라 `SandboxShell` 의 일이므로 프로바이더 계약 스위트가 아니라 매니저 통합 테스트가
덮는다.

### 1.2 저장소 계약 스위트

`SandboxWorkspaceStoreContract` 는 다음을 확인한다.

- 동시 `createIfAbsent` 는 하나만 이긴다.
- stale version 의 `update`, 지운 뒤의 `update` 는 `StaleVersionException` 이다.
- 지웠다가 다시 만든 레코드는 옛 version 을 다시 쓰지 않는다(ABA 방지).
- `update` 는 `next` 에 든 version 을 무시하고, 다른 id 의 레코드를 거부한다.
- 돌려준 레코드와 `find` 가 같다. 시각은 밀리초 이상의 정밀도로 같다.
- scan 필터와, 걸러진 레코드가 끼어 있는 페이징이 동작한다. owner · origin 필터는 페이지를 넘어 유지된다.
- CLOSED 레코드(툼스톤 포함)는 보존되고 지울 수 있다.

version 규칙과 "돌려준 것이 저장된 것" 규칙의 정본은 [도메인 §4.2](workspace-sandbox-domain.md)다.

모든 저장소 구현은 같은 계약 스위트를 통과한다. **(계획 — 구현 단계 6)** JDBC 구현도 이 스위트를 통과해야 한다. 스위트가
영속 구현보다 먼저 있어야 `InMemory` 구현과 영속 구현의 의미가 갈리지 않는다.

### 1.3 세션 옵션 저장소 계약 스위트

`SessionOptionsStoreContract` 는 다음을 확인한다.

- 만들기 전용 put 과 CAS put 이 동작한다. 지운 뒤 다시 만든 레코드는 version 을 재사용하지 않는다.
- 돌려준 레코드와 `find` 가 같다. `unannouncedReset` 도 포함한다 — 있음 · 없음 · 교체 · 지움이 왕복하고, 그 `at` 은 밀리초로
  비교한다.
- `byWorkspace` · `workspaces` · `sessions` 의 페이징이 안정적이다. `sessions` 는 워크스페이스가 없는 레코드(예:
  `unannouncedReset` 만 든 레코드)도 돌려주고, 페이지 사이에 지운 레코드가 있어도 나머지를 빠뜨리지 않는다.
- `deleteByWorkspace` 는 멱등이고, 레코드의 다른 필드(`unannouncedReset` 포함)는 남긴다.

커스텀 `SessionOptionsStore` 가 지켜야 할 의무는 [notice §7](workspace-sandbox-notices.md)에 있다.

### 1.4 런타임 파일 시스템 계약 스위트

코어는 `RuntimeFileSystem` 계약 스위트를 발행하지 않는다. 그래서 이 저장소의 testkit 에 `RuntimeFileSystemContract` 를 두고
발행한다. docker 계층과 제3자 프로바이더도 같은 계약을 돌려야 하기 때문이다. 이 스위트를 코어로 옮길지는
[셸 엔진 §8](workspace-sandbox-shell-engine.md)의 열린 질문이다.

스위트는 기본 계층(로컬 프로바이더)과 docker 계층(OpenSandbox)에서 돈다. 덮는 범위는 이미 있는 대상(파일 · 디렉터리 · 링크 ·
끊긴 링크)에 대한 `writeNew` 거절, 동시 `writeNew` 중 하나만 성공, 바이너리 바이트 왕복(줄 정규화를 잡는다), `read` 의 상한,
링크를 따라가지 않는 `read` 와 `delete`, 거절하는 경로 모양, 셸에서의 가시성(`writeNew` 한 파일을 셸이 `test -f` 로 본다)이다.
기본 계층에서는 묻는 것만으로 프로비저닝하지 않는다는 것과 연결할 수 없는 샌드박스에서의 호출이 `IOException` 이라는 것도
확인한다.

### 1.5 코어의 엔진 계약 스위트

코어는 셸 엔진의 계약 스위트(`AbstractCodeExecutionEngineContractTest`)를 testkit 으로 발행한다. 이 저장소는 그 스위트를
상속해 계층마다 빠짐없이 돌린다(§3).

## 2. 테스트용 프로바이더

### 2.1 `LocalProcessSandboxProvider`

testkit 에만 있는 프로바이더다. 샌드박스 하나를 호스트의 임시 디렉터리 하나와 로컬 프로세스로 흉내 낸다. 매니저 · 바인딩 ·
환경 제공자 · 코어 도구 통합 테스트를 Docker 없이 돌리기 위해 둔다.

**운영용이 아니다.** 격리가 없으므로 이름과 javadoc 에 그렇게 적는다. `HARDENED_SECURITY_CONTEXT` 같은 보안 capability 를
광고하지 않으므로 이 프로바이더를 쓰는 테스트 프로파일은 `insecureAllow` 로 그 요구를 면제한다. 광고하는 capability 는
`EXEC` · `FILES` · `EXPIRY` 이다. 만료는 주입한 시계로 기록하고, 만료된 샌드박스는 다음 `status` · `connect` · `list` 에서
지운다.

**경로를 글자로 바꿔 흉내 낸다.** 명령 · 작업 디렉터리 · 환경 값의 `/workspace` 와 `/usr/bin/sha256sum` 을 호스트 경로로 바꾸고,
출력은 거꾸로 바꾼다. files API 는 모든 절대 경로를 샌드박스 디렉터리 아래로 매핑한다. SPI 에 샌드박스별 경로 root 를 묻는
메서드를 두지 않고 글자를 치환하는 이유는 둘이다. 테스트만의 관심사를 운영 SPI 에 올리지 않기 위해서이고, 서술자의
`workingDirectory` 는 연결이 생기기 전인 `resolve()` 에서 정해야 하기 때문이다.

이 흉내에는 한계가 있고 javadoc 에 적는다.

- 실행 중에 조립된 경로는 바뀌지 않고 호스트에 닿는다. 실행 디렉터리 파일의 **바이트**도 번역하지 않는다(§3.1 의 엔진
  스위트 장식자가 그 때문에 있다).
- 가장 깊은 기존 조상이 심볼릭 링크를 통해 샌드박스 밖으로 풀리는 경로는 거부한다. 명령이 그런 링크를 심을 수 있기 때문이다.

실제 exec 서버와 같게 동작하도록 맞춘 점은 다음과 같다.

- 명령은 자기 프로세스 그룹에서 돌고, `kill()` 은 그 그룹에 SIGTERM 을, 유예 뒤 SIGKILL 을 보낸다.
- 없는 작업 디렉터리는 다른 곳으로 옮겨 돌리지 않고 거부한다.
- destroy 된 샌드박스는 이미 열린 연결로도 not found 다. destroy 는 그 샌드박스의 명령이 남긴 프로세스 그룹까지 끝낸다.
- 덮어쓰지 않는 쓰기와 이동은 원자적이다. 심볼릭 링크를 덮어쓰지 않고 옮기면 링크 자체가 옮겨진다.
- `create` 는 `sandbox-key` 라벨로 멱등이다. `stat` 은 etag(SHA-256)를 돌려주므로 어떤 파일 시스템에서도 변경 감지 계약이
  성립한다.

호스트에 `flock` 이나 `rg` 가 없으면 이 프로바이더는 자기 `PATH` 에만 대역을 둔다. `rg` 대역은 검색하는 대신 "설치되지 않았다"
고 말하고 크게 실패한다 — "결과 없음"(rg 의 종료 코드 1)으로 돌아오면 검색 테스트가 잘못 통과하기 때문이다. 대역은 seed 의
이미지 계약 검사를 통과시키므로 프로비저닝은 된다. 실제 `rg --json` 이 필요한 테스트는 호스트에 `rg` 가 있는지 확인하고
없으면 건너뛴다. 운영 이미지 계약([설정 §4](workspace-sandbox-configuration.md))은 이 대역과 상관없다.

### 2.2 `FaultInjectingSandboxProvider`

어떤 프로바이더든 감싸는 장애 주입 래퍼다. 호출 종류(`CREATE` · `STATUS` · `EXTEND_EXPIRY` · `DESTROY` · `LIST` · `CONNECT` ·
`RUN` · `VERIFY` · files 연산 …)와 호출 순번별로 지연 · 일시/영구 실패 · 타임아웃 · not found · "생성은 됐지만 응답 유실" ·
노드 종료를 주입하고 호출 수를 센다.

노드 종료는 `Error` 를 던져 호출 경로를 그 자리에서 멈춘다. 다른 장애는 모두 `RuntimeException` 이고 close 절차는 그것을
견디도록 설계되어 있으므로, `RuntimeException` 으로는 워크스페이스를 CLOSING 에 멈춰 둘 수 없다. "close 도중 노드 종료"
시나리오(§5.6)에 이것이 필요하다.

한 호출에 규칙이 여럿 맞으면 한 번짜리 규칙(`injectOnce` · `injectAt`)이 상시 규칙(`inject`)보다 먼저다.

### 2.3 `ManualClock` · `ManualScheduler`

idle · heartbeat · backoff · janitor 시나리오를 실제 시간을 기다리지 않고 돈다. 매니저와 janitor 는 시계와 스케줄러를 주입받는다.

## 3. 테스트 계층과 CI

테스트는 세 계층으로 나눈다. 계층은 JUnit 태그로 가른다.

| 계층 | 실행 | 태그 | 무엇을 덮는가 | 어디서 도는가 |
|---|---|---|---|---|
| 기본 계층 | `test` (`checkAll`) | 없음 | 매니저 · 바인딩 · 셸 · 수명 논리, 코어 도구와 실제 코어 루프, 프로바이더의 wire 와 에러 경로 | CI `build` 잡, 로컬 |
| docker 계층 | `integrationTest` | `docker` | 실제 OpenSandbox 서버(Docker 런타임) 위의 계약 스위트와 시나리오 | CI `integration` 잡, 릴리스 게이트 |
| k8s 계층 | `k8sTest` | `k8s` | Kubernetes 런타임에서만 드러나는 격리 · 강화 · 셸 엔진 검사 | 미리 준비한 클러스터에서 수동, 릴리스 전 |

`test` 는 `docker` · `k8s` 태그를 빼고, `integrationTest` 는 `k8s` 태그를 뺀다. 두 태그를 함께 단 테스트가 엉뚱한 서버에서
돌지 않게 하기 위해서다.

### 3.1 기본 계층

로컬 프로바이더 · 장애 주입 래퍼 · 수동 시계로 매니저 논리를 돈다. 프로바이더 계약 스위트와 저장소 계약 스위트도 로컬
프로바이더와 `InMemory` 저장소로 여기서 돈다.

- **종단 테스트.** 실제 코어 `OrcaAgentExecutor` 와 스크립트한 LLM 으로 프롬프트 · 슬래시 커맨드 · 코어 도구 · 훅이 샌드박스에서
  도는지 확인한다(`OrcaRuntimeSandboxE2ETest`).
- **OpenSandbox 프로바이더의 기본 계층.** JDK `HttpServer` 위에 만든 fake 서버(`FakeOpenSandboxServer`)로 요청 모양, 에러 분류,
  재시도, SSE 파싱, 상태 매핑을 확인한다. 계약 스위트는 fake 서버에서 돌리지 않는다. fake 는 명령을 실행하지도 파일 시스템을
  유지하지도 못하고 응답을 스크립트할 뿐이기 때문이다. 계약 스위트는 실서버(docker 계층)에서 돌고, fake 는 실서버가 원할 때
  만들어 낼 수 없는 wire 동작과 에러 경로를 덮는다.
- **사람용 파일 API**는 기본 계층에서 테스트한다. 파일 API 가 쓰는 프로바이더 호출은 모두 프로바이더 계약 스위트가
  OpenSandbox 로 덮으므로 docker 계층 테스트를 따로 두지 않는다.
- **셸 엔진 계약 스위트(기본 계층).** 로컬 프로바이더 위에서 코어 스위트를 상속해 돈다. 코어 런타임은 응답 디렉터리를 실행
  디렉터리 파일(`reply_dir`, 절대 `/workspace` 경로)에서 읽는데 로컬 프로바이더는 파일 바이트를 번역하지 않으므로, 시험 전용
  환경 장식자가 그 파일 하나를 호스트 경로로 바꿔 넣는다. 코어 런타임 내부 지식을 발행하는 testkit 에 퍼뜨리지 않으려고
  장식자를 테스트 코드에 가둔다. 코어가 그 파일을 그만 쓰면 응답이 오지 않아 스위트가 크게 실패한다. 호스트에 `python3` 3.8
  이상이 없으면 건너뛴다. 권위 있는 계층은 docker 다. 코어 쪽 변경으로 장식자를 없애는 길은
  [셸 엔진 §8](workspace-sandbox-shell-engine.md)의 열린 질문이다.

**커버리지 하한.** 모듈마다 라인 커버리지 하한을 둔다(`gradle/coverage-baselines.properties`). 하한은 측정한 커버리지를 정수
%로 내린 값이고, 그것을 측정하는 테스트와 같은 변경에서 정한다 — 빌드가 이미 만족하지 않는 하한을 두지 않는다. 커버리지가
오르면 하한을 올리고, 변경을 통과시키려고 내리지 않는다. 모든 하한은 기본 계층만으로 채운다. 프로바이더 모듈의 하한도 fake
서버 테스트만으로 채우므로 `build` 는 Docker 없이 하한을 지킨다.

### 3.2 docker 계층

docker 계층은 실행마다 OpenSandbox 서버를 Testcontainers 컨테이너로 띄우고 이미지는 digest 로 고정한다. 서버 버전은 프로바이더가
요구하는 1.2.0 이다([개요 §6.3](workspace-sandbox.md), [OpenSandbox §1](workspace-sandbox-opensandbox.md)). 러너의 Docker 소켓을
서버 컨테이너에 바인드하면 충분하므로 DinD 가 필요 없고, 서버 기동은 약 1초다.

**서버 구성.**

- 서버는 JVM 과 egress 모드마다 하나 띄워 한 실행의 테스트 클래스가 함께 쓴다. `dns` 모드 서버는 `verify` 의 음성 사례를 위한
  두 번째 컨테이너이고 egress 테스트만 띄운다.
- 실행마다 `ci-{uuid}` deployment 를 쓰고, 종료 훅이 그 라벨로 남은 샌드박스를 지운 뒤 컨테이너를 멈춘다.
- 외부 서버를 쓰려면 `OPENSANDBOX_TEST_ENDPOINT` · `OPENSANDBOX_TEST_API_KEY` 로 덮어쓴다.
- egress 시나리오는 egress 사이드카에 `NET_ADMIN`/nft 권한이 필요하다.

**도달 설정.** 서버가 컨테이너 안에 있으므로 업스트림의 참조 compose 와 같은 조합을 쓴다 — `[docker] host_ip =
"host.docker.internal"` 과 `extra_hosts: host.docker.internal:host-gateway`, `[proxy] resolve_internal = false`,
`network_mode = "bridge"`. 샌드박스는 execd 를 호스트의 모든 인터페이스에 publish 하고, 서버는 `host.docker.internal:{port}`
로 닿는다. 루프백에만 바인드하면 Linux 에서 서버 컨테이너의 네트워크 네임스페이스가 host gateway 로 그 포트에 닿지 못하기
때문이다. 테스트 JVM 은 서버의 매핑된 포트에만 닿고 execd 는 서버 프록시(`useServerProxy`)로 부른다. 실행 동안 테스트
호스트의 인터페이스에 execd 포트가 열리는 것은 개발 · CI 머신에서 받아들인다.

**샌드박스 이미지.** `src/test/docker/Dockerfile` 로 만든 계약 이미지다. 이미지 계약([설정 §4](workspace-sandbox-configuration.md))
에 선택 항목 `python3` 까지 포함하므로 셸 엔진을 이 계층에서 돌릴 수 있다.

**무엇이 도는가.**

- `OpenSandboxProvider` 에 대한 프로바이더 계약 스위트 전체.
- `WorkspaceSandbox` 종단 시나리오(파일 도구와 셸, 셸 상태, 타임아웃 kill, 취소, 실행 디렉터리, 라벨 인코딩, 조정, 자원 한도),
  egress 시나리오, 관리형 워크스페이스 시나리오.
- 런타임 파일 시스템 계약 스위트, 셸 엔진 계약 스위트와 가용성 테스트, 남은 프로세스 검사와 그 음성 대조(§6).

**docker 계층이 덮지 않는 것.**

- "root 로 도는 이미지". Docker 런타임에서 root 이미지는 `HARDENED_SECURITY_CONTEXT` 를 면제해야 기동되고, 면제하면 seed 가
  uid 를 검사하지 않는다. 기본 계층의 seed 테스트와 k8s 계층이 덮는다.
- 제어면 네트워크 탐침. 탐침은 `NETWORK_ISOLATION` 을 요구하고 면제하지 않을 때만 도는데, `runtime: docker` 는 그 capability
  를 광고할 수 없다. 기본 계층(로컬 프로바이더가 실제 seed 스크립트를 돈다)과 k8s 계층이 덮는다.
- pids 한도는 소진하지 않고 `pids.max` 가 서버의 `pids_limit` 인지 읽는다. 메모리는 256 Mi 한도에서 메모리 초과 명령을
  돌리고(종료 코드 137) 그 샌드박스와 이웃 샌드박스가 계속 답하는지 본다.
- egress 테스트는 제한 없는 샌드박스가 인터넷에 닿을 때만 돈다. 그 대조 없이는 "막혔다" 가 아무것도 증명하지 않기 때문이다.
  자격 증명 주입 자체는 응답을 되돌려 주는 HTTPS 엔드포인트와 CA 문제가 있어 k8s 계층에서 확인한다.

**CI.** docker 계층은 PR 마다 Linux(amd64) 러너의 `integration` 잡에서 돈다(`.github/workflows/build.yml`). 기본 계층은 같은
워크플로의 `build` 잡이 `checkAll`(포맷 · checkstyle · 테스트)로 돈다. `coverage` 잡이 두 계층의 실행 데이터를 모아 커버리지
리포트를 만들고 하한을 검사한다. 릴리스 스크립트는 CI 잡이 도는 태스크를 모두 게이트로 돈다.

### 3.3 k8s 계층

k8s 계층은 Kubernetes 런타임에서만 드러나는 항목을 OpenSandbox K8s 런타임으로 확인한다.

- **클러스터는 테스트가 만들지 않는다.** 미리 준비한 클러스터를 쓰고, `OPENSANDBOX_K8S_ENDPOINT` 가 없으면 모든 테스트가
  건너뛴다. 준비 절차(kind · 차트 · 강화 템플릿 · 네트워크 정책 · 계약 이미지)는
  [Kubernetes 설치 가이드 §9](../guide/opensandbox-kubernetes.md)에 있다. 테스트 JVM 은 port-forward 와 서버 프록시로 서버에
  닿는다.
- **릴리스 전과 수동으로만 돈다.** 클러스터 준비에 몇 분이 걸리므로 CI 잡도 게이트 태스크도 아니다. 대신 릴리스 스크립트는
  운영자가 이 계층이 그 커밋에서 통과했다고 밝히는 `--k8s-verified` 없이는 릴리스하지 않는다. 건너뛰기를 누락이 아니라 결정으로
  만들기 위해서다.
- **건너뜀은 통과로 세지 않는다.** 클러스터 없는 머신에서 `k8sTest` 가 초록이면 모든 테스트가 건너뛴 것이다.

**검사 항목.**

- 템플릿 모드: CR 의 라벨, 샌드박스 안에서 관찰한 강화(uid 1000, no-new-privileges, capability 없음, 서비스 계정 토큰 없음),
  면제 없는 프로파일의 seed 통과(제어면 탐침 포함), east-west 차단(이웃 샌드박스의 execd), runtime class, `dns+nft` 의
  `verify`, vault 와 자격 증명 범위, TLS 검증을 켠 git 주입.
- 풀 모드(`OpenSandboxK8sPoolIT`): 라벨, 풀 파드의 강화, 면제 없는 seed, east-west 격리. 서버가 풀 샌드박스에 네트워크 정책을
  거부하고 풀 파드에는 runtime class 가 붙지 않으므로, runtime class · `dns+nft` · vault · git 주입 검사는 이유를 밝히고
  건너뛴다.
- 셸 엔진: 코어 엔진 계약 스위트, 남은 프로세스 · PID 1 · 좀비 회수 검사를 템플릿 모드와 풀 모드에서 계약 이미지로 돈다
  (`ShellEngineContractK8sTemplateIT` · `ShellEngineContractK8sPoolIT`). 결론은 §6.
- **(계획 — 구현 단계 5)** RWX 볼륨과 샌드박스별 `ro` 마운트, PVC 삭제와 finalizer.
- **(계획 — 구현 단계 7)** pause 시 파드가 다시 만들어지는지.

풀 모드 테스트는 프로바이더의 패키지 전용 설정(생성 본문의 `extensions`)으로 풀을 요청한다. 공개 API 는 바뀌지 않고 "warm
pool 은 SPI 에 올리지 않는다"([프로바이더 §7](workspace-sandbox-provider.md))도 그대로다. 공개 pool-ref 설정은 풀의
의미론(egress · 볼륨 · 자격 증명 없음, 라벨, 조정)을 갖춘 기능 작업이라 공개 풀 지원과 함께 다룬다.

**k8s 계층이 덮지 않는 것.** 런타임은 runc 뿐이다(gVisor · Kata 없음). 멀티 노드 클러스터도 kind 의 것이라 모든 노드가 한
Docker 호스트의 컨테이너다.

### 3.4 시나리오가 도는 계층

§5 의 시나리오는 다음 기준으로 계층에 놓인다.

| 시나리오의 성격 | 계층 |
|---|---|
| 매니저 · 바인딩 · janitor · notice 논리(장애 주입 · 수동 시계로 재현) | 기본 계층 |
| 코어 도구 · 실제 코어 루프를 지나야 드러나는 것 | 기본 계층(종단 테스트) |
| 실제 execd 의 동작에 기대는 것(스트림, 스풀, 프로세스, 라벨 인코딩, 자원 한도, egress) | docker 계층 |
| Kubernetes 에서만 드러나는 것(강화 템플릿, east-west, runtime class, root 이미지, 자격 증명 주입) | k8s 계층 |

조정과 janitor 시나리오는 기본 계층에서 돌고, 노드 재시작 뒤의 고아 회수는 docker 계층에서 실서버로 한 번 더 돈다. 라벨 인코딩
(`ws:{sessionId}` 처럼 `:` · `/` 가 든 id)은 계약 스위트와 docker 계층이 확인한다. `networkIsolation` 미선언 배포의 기동 거부와
제어면에 닿는 샌드박스의 FAILED(seed 단계 `network-isolation`)는 기본 계층이, 선언한 `dns+nft` 와 서버의 `dns` 불일치는 기본 계층과 docker
계층이 함께 확인한다.

## 4. 멀티 노드 검증

멀티 노드 경합은 두 단계로 검증한다.

1. **한 JVM 안의 시뮬레이션.** 매니저 둘이 각자 다른 `nodeId` · 연결 캐시 · 노드 로컬 락을 갖고 한 `InMemory` 저장소(그리고
   한 세션 옵션 저장소 · 한 프로바이더)를 함께 쓴다. 경합 논리를 영속 저장소보다 먼저 검증하기 위해서다. 두 노드의 동시
   `connect`, seed 도중 노드 종료, 두 노드의 동시 seed, 미전달 초기화의 노드 간 replay 가 여기서 돈다(§5.6, §5.8).
2. **(계획 — 구현 단계 6)** 실제 프로세스 여럿과 JDBC 저장소로 같은 시나리오를 다시 돈다(§5.12).

## 5. 필수 시나리오

아래 시나리오는 모두 통과해야 한다. "규칙" 열은 그 기대의 정본 규칙이 있는 절이다. 계획 단계의 행과 묶음에는
`(계획 — 구현 단계 N)` 표지를 달았다.

### 5.1 파일 시스템 · 셸 상태 · 셸 락

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 에이전트 A `Write Foo.java` → 같은 슬롯의 에이전트 B `Read Foo.java` | 즉시 보인다 | [파일 시스템 §1](workspace-sandbox-filesystem.md) |
| A `export FOO=A` → B `echo $FOO` | 비어 있다 (셸 격리) | [셸 §1](workspace-sandbox-shell.md) |
| 같은 shellKey 에서 `cd src && export X=1` → 다음 `Bash` | cwd 가 `src`, `X=1`. export 안 한 변수는 없다 | [셸 §1](workspace-sandbox-shell.md) |
| `Write` → `Bash cat` 같은 파일 | 같은 내용 (한 파일 시스템) | [파일 시스템 §1](workspace-sandbox-filesystem.md) |
| 명령 타임아웃으로 kill → 다음 명령 | 직전 셸 상태로 돈다, notice | [셸 §6](workspace-sandbox-shell.md) |
| 명령이 `sleep 600 &` 같은 자손을 남기고 끝남 → 같은 shellKey 의 다음 명령 | 락 대기 없이 바로 돈다 | [셸 §3](workspace-sandbox-shell.md) |
| 명령 타임아웃 5초, 앞 명령이 락을 3초 더 쥠 | 락 대기는 타임아웃에 들어가지 않는다 | [셸 §3](workspace-sandbox-shell.md) |
| 백그라운드 명령 실행 중 같은 세션의 다음 `Bash` | 기다리지 않고 바로 돈다 | [셸 §5](workspace-sandbox-shell.md) |
| `cd src && export X=1 BASH_ENV=…` → 격리 명령 → 다음 `Bash` | 격리 명령은 root 에서 돌고 `X` · `BASH_ENV` 가 없다. 다음 `Bash` 는 여전히 `src`, `X=1` (격리 명령은 상태를 저장하지 않는다) | [셸 §5](workspace-sandbox-shell.md) |
| 포그라운드 명령이 셸 락을 쥔 동안 격리 명령 | 기다리지 않고 바로 돈다 | [셸 §5](workspace-sandbox-shell.md) |
| 격리 스트리밍(훅도 백그라운드도 아닌 `isolated` + 리스너) | 거절되지 않고 출력이 리스너로 온다. 셸 락을 기다리지 않고 세션의 cwd · export 를 읽지도 저장하지도 않는다. 포그라운드 스트리밍은 거절된다 | [셸 §5](workspace-sandbox-shell.md) |
| 백그라운드 명령이 `backgroundHeartbeatLimit` 넘게 돎 | 그 뒤 idle 정책대로 전이. 백그라운드 명령은 셸 락 heartbeat 를 갱신하지 않는다 | [셸 §7](workspace-sandbox-shell.md) |

### 5.2 취소 · 백그라운드 상한 · 런타임 바인딩

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 도는 백그라운드 명령에 취소 신호 | 명령과 그룹에 남은 자식이 끝나고, 그때까지의 출력을 실은 `ShellCancelledException` | [셸 §6](workspace-sandbox-shell.md) |
| SIGTERM 을 무시하는 명령에 취소 신호 | 뒤따르는 SIGKILL 로 끝나고, 취소로 보고한다 | [셸 §6](workspace-sandbox-shell.md) |
| 도는 포그라운드 명령에 취소 신호 | 같은 예외와 notice. 다음 명령은 직전 셸 상태로, 락 대기 없이 돈다 | [셸 §6](workspace-sandbox-shell.md) |
| 이미 걸린 신호로 `execute` | 같은 예외. 샌드박스도 레코드도 exec 도 없다 | [셸 §6](workspace-sandbox-shell.md) |
| 백그라운드 명령을 위해 프로비저닝하는 동안 신호가 걸림 | exec 직전 확인에서 멈춰 명령을 띄우지 않는다 | [셸 §6](workspace-sandbox-shell.md) |
| 셸 락을 기다리는 동안 신호가 걸림 | 락을 얻은 뒤 명령을 띄우지 않는다. 실행 파일도 올리지 않는다 | [셸 §6](workspace-sandbox-shell.md) |
| exec 가 시작되는 순간 신호가 걸림 | `run` 뒤의 보충 kill 이 명령을 끝낸다 | [셸 §6](workspace-sandbox-shell.md) |
| 명령이 끝난 뒤에 신호가 걸림 · 명령의 끝과 신호가 겹침(트레일러 있음) | 정상 결과. 끝난 뒤의 신호는 kill 을 부르지 않는다 | [셸 §6](workspace-sandbox-shell.md) |
| 취소한 명령의 kill 이 닿지 못하고 타임아웃이 명령을 끝냄 | 타임아웃이 아니라 취소로 보고한다. 백그라운드 명령이 exec 대기 최후 방어선에서 끝나도 같다 | [셸 §6](workspace-sandbox-shell.md) |
| 취소한 명령의 kill 이 닿지 못하고 기다리던 스레드가 인터럽트됨 | 인터럽트 실패가 아니라 부분 출력을 실은 취소로 보고하고, 인터럽트 플래그는 남는다 | [셸 §6](workspace-sandbox-shell.md) |
| 프로바이더의 `kill()` 이 예외를 던짐 | `execute` 가 그 예외를 그대로 던지지 않고 명령을 기다려 끝까지 판정한다 | [셸 §6](workspace-sandbox-shell.md) |
| 백그라운드 `Bash` → `KillShell` → `BashOutput` (코어 도구 그대로) | 시작 응답이 `KillShell` 과 환경이 명령을 멈추는 시점을 알리고, `KillShell` 이 성공하고, 상태는 `Killed` | [셸 §7](workspace-sandbox-shell.md) |
| 환경의 `backgroundCommandTimeout()` | 선언한 프로파일의 값. 기본은 24시간, 포크는 자기가 선언한 프로파일의 값 | [셸 §7](workspace-sandbox-shell.md) |
| 백그라운드 명령이 그 상한을 타임아웃으로 받음 | 상한에서 kill 되고 부분 출력과 함께 타임아웃 | [셸 §7](workspace-sandbox-shell.md) |
| 런타임 A · B 가 각자 백그라운드 명령을 돌리는 중 A 의 바인딩을 닫음(두 번) | 두 명령 모두 끝까지 돌고, 두 환경 모두 새 명령을 받는다. 같은 id 의 다른 바인딩도 영향 없음. 바인딩하지 않은 id 의 `resolve` 도 답한다 | [개요 §6.4](workspace-sandbox.md) |

### 5.3 바인딩 · 포크 · 프로비저닝 시점

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 세션 없는 루틴 → 서브에이전트 포크 → 손자 포크 | 셋 다 같은 워크스페이스 · 슬롯 | [바인딩 §3](workspace-sandbox-binding.md) |
| 포크 → 손자 포크, 부모 환경이 사용 불가 | 둘 다 사용 불가, 부모의 원인을 싣는다 | [바인딩 §3](workspace-sandbox-binding.md) |
| 부모 환경 없는 런타임 범위 워크플로 러너의 단계(`fork()` 있음, `parent` 없음) | 사용 불가 → 새 워크스페이스를 만들지 않는다 | [바인딩 §3](workspace-sandbox-binding.md) |
| 요청 속성 `sandbox.workspace` 가 든 메인 턴 · 루틴 → 그 턴의 포크 | 이름 붙인 관리형 워크스페이스에서 돌고, 포크도 같은 워크스페이스다. 포크 요청에 다른 요청 속성이 있어도 부모의 워크스페이스를 쓴다 | [바인딩 §2](workspace-sandbox-binding.md) |
| 요청 속성 · 세션 옵션 · 정의 속성 · 설정 정책이 함께 있음 | 앞의 층이 이긴다. unattached 규칙은 네 층 가운데 가장 엄격한 것이다 — 어느 층의 `per-session` 도 다른 층의 `reject` 를 풀지 못한다 | [바인딩 §2](workspace-sandbox-binding.md) |
| `terminateAfter` 보다 긴 명령, 그동안 도구 호출 없음, 다른 실행이 계속 레코드를 갱신 | 명령 도중 terminate 되지 않음, 프로바이더 만료도 밀림 | [수명 §5](workspace-sandbox-lifecycle.md) |
| 첫 턴에 명령을 치지 않는 세션 | 프로비저닝 없음. 프롬프트의 환경 블록은 프로파일 선언값이다 | [수명 §2.3](workspace-sandbox-lifecycle.md) |
| 슬래시 커맨드(`/skill`)로 부른 스킬의 `Bash` (INLINE · FORK 모두) | 샌드박스에서 돈다 (호스트 아님). FORK 는 같은 워크스페이스 | [바인딩 §3](workspace-sandbox-binding.md) |

### 5.4 스테이징과 경로 규칙

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| `Write /workspace/.aimon-staged/…` · `.aimon-shell/…` | "Access denied" 에러. 같은 경로에 `Bash` 로 쓰는 것은 된다 | [파일 시스템 §3](workspace-sandbox-filesystem.md) |
| 셸로 `.aimon-staged/{name}/{contentKey}/` 에 다른 스크립트와 마커를 심은 뒤 스킬 활성화 | 해시 불일치로 다시 복사, 심은 내용은 돌지 않음 | [파일 시스템 §4](workspace-sandbox-filesystem.md) |

### 5.5 훅과 실행 디렉터리

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 코어 파서로 만든 스킬 `preTool` 셸 가드, 실제 코어 루프 | 샌드박스에서 돌고 종료 코드 2 로 도구를 막는다. 샌드박스가 사용 불가면 막는다(`failOpen` 이면 통과) | [보안 §6](workspace-sandbox-security.md) |
| 훅 실행 중 모델의 셸 상태 | 훅은 셸 락을 잡지 않고 모델의 cwd · export 를 바꾸지 않는다. `hooks.json` 의 훅은 그것을 읽지도 않는다 | [셸 §9](workspace-sandbox-shell.md) |
| `hooks.json` 가드 `bash "$AIMON_HOOK_DIR/guard.sh"` 가 exit 2 | 샌드박스에서 돌고 도구를 막는다. `AIMON_HOOK_DIR` 은 `.aimon-shell/.run.*/d` 이고 실행 뒤 없다 | [셸 §9](workspace-sandbox-shell.md) |
| 훅 실행이 업로드한 실행 파일 · manifest · stdin 을 래퍼가 확인하기 전에 고쳐 씀 (docker 계층: 같은 uid 의 백그라운드 명령이 blob 을 계속 다시 씀) | `RunDirectoryIntegrityException`, 명령은 돌지 않고 비공개 디렉터리도 남지 않는다 | [셸 §9](workspace-sandbox-shell.md) |
| 작은 파일 1 500개로 manifest 가 인자 예산을 넘는 실행 디렉터리 | manifest · blob · stdin 세 번의 업로드로 돈다 | [셸 §9](workspace-sandbox-shell.md) |
| 백그라운드 명령이 `.aimon-shell/*/run-*.out` 을 계속 고쳐 씀 → 격리 명령 | 격리 명령의 stdout 과 종료 코드는 그대로 — 고칠 run 파일이 없다 | [셸 §5](workspace-sandbox-shell.md) |
| 훅이 `(sleep 2; touch late) & echo done` | `done` 으로 곧바로 돌아오고, `late` 는 생기지 않는다 (트레일러 뒤 kill) | [셸 §9](workspace-sandbox-shell.md) |
| 훅이 여러 줄로 약 230 KiB 를 출력하고 바로 끝남 (docker 계층) | 출력이 잘리지 않고 다 온다 (kill 전 drain 유예) | [셸 §9](workspace-sandbox-shell.md) |
| 격리 훅의 타임아웃 (docker 계층) | 시계로 판정한 타임아웃으로 보고한다 | [셸 §4](workspace-sandbox-shell.md) |

### 5.6 close · idle · reopen · notice · 세션 삭제

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 툼스톤(명시적 close 의 CLOSED)이 있는 워크스페이스로 `connect` | "the sandbox workspace is closed; the application has to reopen it …". `reopen` 뒤에는 새 generation 으로 동작 | [수명 §7.2](workspace-sandbox-lifecycle.md) |
| close 도중 다른 실행의 `connect` | CLOSED 뒤에 남는 슬롯 없음 | [수명 §7.1](workspace-sandbox-lifecycle.md) |
| close 도중 노드 종료 | janitor 가 이어서 CLOSED 까지 | [수명 §7.1](workspace-sandbox-lifecycle.md) |
| `closeAfter` 로 닫힌 뒤 같은 세션의 다음 턴 | 같은 워크스페이스를 새 incarnation 으로 자동 reopen 해 빈 `/workspace` 에서 실행, "/workspace 초기화" notice (막지 않는 CLOSED 레코드) | [수명 §7.2](workspace-sandbox-lifecycle.md) |
| 그 턴이 `Read` 만 쓴다 | 첫 도구 결과 머리에 `[environment]` notice 가 한 번, 둘째에는 없다 | [notice §2](workspace-sandbox-notices.md) |
| 그 턴이 텍스트만 답하고 `onStop` 훅의 명령만 샌드박스를 쓴다(초기화를 꺼내는 drain 이 없다) → 다음 턴 | 다음 턴의 첫 도구 결과에 "/workspace was reset at …" notice 가 한 번, 그 뒤로는 없다. 같은 턴에 꺼낸 초기화는 다음 턴에 다시 나오지 않는다. 훅 대신 스테이징이 연결해도, 훅의 재프로비저닝이 실패해도 같다 | [notice §5](workspace-sandbox-notices.md) |
| 초기화를 모델에게 전달한 턴의 ack 가 다음 턴 전에 저장되지 않음 | 다음 턴에 replay 가 정확히 한 번 (알려진 실패 창). ack 가 저장되었으면 replay 없음 | [notice §5.2](workspace-sandbox-notices.md) |
| 포크의 초기화 | 세션의 미전달 초기화를 쓰지 않는다 | [notice §5.2](workspace-sandbox-notices.md) |
| 노드 A 의 훅만 쓴 턴이 mark → 노드 B 의 다음 턴 | B 에서 replay 가 한 번. B 가 더 새로운 mark 를 남긴 뒤 도착한 A 의 늦은 ack 는 B 의 값을 지우지 않는다 | [notice §5.3](workspace-sandbox-notices.md) |
| 포크가 샌드박스를 재생성 → 부모의 다음 호출 | 포크의 큐에 재생성 notice, 부모는 다음 `connect` 에서 "이 실행이 마지막으로 쓴 뒤 재생성되었다" notice 한 번 | [notice §4](workspace-sandbox-notices.md) |
| 세션 옵션이 워크스페이스를 옮긴 세션의 `Read` 만 쓴 턴 | 첫 `Read` 에만 전환 notice, `lastWorkspaceId` 가 기록되어 다음 턴에는 없다 | [notice §3](workspace-sandbox-notices.md) |
| 전환 notice 를 전달한 뒤, 큐에 든 기록 쓰기보다 `clearSessionOptions` 가 먼저 실행됨 | 레코드는 떠난 워크스페이스를 `lastWorkspaceId` 로 남기고, 다음 턴이 되돌아가는 이동을 알린다 | [notice §3](workspace-sandbox-notices.md) |
| 훅 명령의 connect 가 만든 전환 notice | 큐에 남아 다음 `drainNotices()` 가 전달한다 | [notice §2](workspace-sandbox-notices.md) |
| 세션 삭제(`onDeleted`) → 같은 id 의 새 세션 | 옵션이 지워지고 `ws:{sid}` 는 즉시 CLOSING. 호출 스레드에서는 프로바이더 호출이 없다. 새 세션은 "being removed", 배경 작업 뒤 새 incarnation | [수명 §7.4](workspace-sandbox-lifecycle.md) |
| 세션 삭제의 배경 작업이 유실됨 | janitor 의 재개와 `CLOSED(SESSION_DELETED)` 레코드 삭제가 같은 끝 상태에 이른다 | [수명 §7.4](workspace-sandbox-lifecycle.md) |
| 세션 삭제 뒤 큐에 남은 ack · mark 쓰기 | 아무 레코드도 만들지 않는다 | [notice §5.2](workspace-sandbox-notices.md) |
| `defaultProfile` 을 바꿔 재배포 → 진행 중인 워크스페이스 | 기존 `primary` 슬롯을 계속 쓴다 | [도메인 §2](workspace-sandbox-domain.md) |

### 5.7 테넌트 · 주체 · 권한 · admission

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 다른 테넌트 principal 로 같은 workspaceId (`connect` · `close` · `reopen`) | 모두 거부 | [바인딩 §5](workspace-sandbox-binding.md) |
| **(계획 — 구현 단계 5)** 다른 테넌트 워크스페이스에 대한 `SandboxStop` · `SandboxList` | 거부 | [바인딩 §7](workspace-sandbox-binding.md) |
| 한 테넌트가 세션을 대량으로 열어 `maxRunningPerTenant` 초과 | admission 거부 | [보안 §8](workspace-sandbox-security.md) |
| 같은 테넌트 다른 사용자, `workspaceAccess: PRINCIPAL` | 거부 | [바인딩 §5](workspace-sandbox-binding.md) |
| `Principal.system()` 요청 + `requirePrincipal` | 거부 | [바인딩 §4](workspace-sandbox-binding.md) |
| 레코드 유실 뒤 세션 소유자가 아닌 주체가 먼저 `connect` | owner 는 세션 소유자, 그 주체는 거부 | [바인딩 §4](workspace-sandbox-binding.md) |
| 관리 연산(옵션 set · clear · 조회, describe, close, reopen, purge, 요청 속성 바인딩) × 주체(owner · 같은 테넌트 · 다른 테넌트 · 익명) × 배포 접근 모드 × 레코드 접근 모드 | 사용 권한 · 관리 권한 규칙대로 허용 · 거부한다. 요청 속성 바인딩의 거부는 `connect` 의 "not permitted" 이고 아무것도 만들지 않는다 | [바인딩 §5](workspace-sandbox-binding.md) |
| 직렬화한 `WorkspaceView` · `SandboxView` | 프로바이더 id 가 없다 | [관리 §5](workspace-sandbox-management.md) |

### 5.8 장애 주입 · janitor · 멀티 노드 시뮬레이션 · seed

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 장애 주입: `create` 응답 유실 → 재시도 | 레코드에는 하나, 나머지는 DUPLICATE · ORPHAN 으로 회수 | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| 장애 주입: `extendExpiry` not found (명령 도중) | 명령 에러, 슬롯 LOST, 다음 호출이 재생성 | [수명 §5](workspace-sandbox-lifecycle.md) |
| **(계획 — 구현 단계 7)** 장애 주입: resume not found | 재생성 후 실행, notice | [수명 §4](workspace-sandbox-lifecycle.md) |
| 장애 주입: `create` 성공 뒤 RUNNING CAS 실패 | 다시 판단, 쓰이지 않는 샌드박스는 janitor 가 회수 | [수명 §2.1](workspace-sandbox-lifecycle.md) |
| FAILED(transient) 슬롯의 재시도 | generation+1, 옛 잔재는 STALE 로 destroy | [수명 §2.2](workspace-sandbox-lifecycle.md) |
| root 로 도는 이미지 | FAILED(permanent), 샌드박스를 다시 만들지 않음 | [수명 §3](workspace-sandbox-lifecycle.md) |
| janitor: `list` 결과에서 한 번 빠짐(페이징 · 지연 반영) | LOST 로 확정하지 않음 | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| janitor: 두 노드가 같은 주기에 각각 못 봄 | `lostConfirmAfter` 전에는 LOST 가 아님 | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| janitor: scan 뒤 다른 노드가 generation+1 을 프로비저닝 | 새 샌드박스를 STALE 로 destroy 하지 않음 (IN_FLIGHT) | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| 두 노드가 같은 슬롯 동시 `connect` | 레코드에 샌드박스 하나 | [수명 §2.4](workspace-sandbox-lifecycle.md) |
| 인계: 원래 노드가 느렸을 뿐 살아 있음 | 샌드박스 둘이 생기면 하나는 DUPLICATE 로 회수 | [수명 §2.4](workspace-sandbox-lifecycle.md) |
| 노드 재시작 (`InMemory` 저장소) | 새 샌드박스. 옛 샌드박스는 `orphanGrace` 뒤 회수 | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| 레코드 삭제 후 janitor | 고아 destroy | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| 다른 `deployment` 라벨의 샌드박스 | janitor 가 건드리지 않음 | [수명 §6.2](workspace-sandbox-lifecycle.md) |
| `ws:{sessionId}` 처럼 `:` · `/` 가 들어간 id 로 생성 | OpenSandbox 가 받아들인다 (라벨 인코딩) | [프로바이더 §4](workspace-sandbox-provider.md) |
| **(계획 — 구현 단계 7)** pause 된 슬롯에서 다음 `Bash` | resume 후 실행된다. 셸 상태가 이어진다 | [수명 §4](workspace-sandbox-lifecycle.md) |
| 명령 사이에 샌드박스 소실(LOST) → 다음 호출 | generation+1 로 재생성, notice, 스킬 스테이징을 다시 복사 | [수명 §2](workspace-sandbox-lifecycle.md) |
| seed 도중 노드 종료 → 다른 노드의 다음 호출 | `.tmp-*` 잔재를 치우고 seed 를 다시 돌아 완료 | [수명 §3](workspace-sandbox-lifecycle.md) |
| 같은 슬롯을 인계받은 두 노드가 동시에 seed | seed 락으로 차례로 돌고, 둘 다 성공 | [수명 §3](workspace-sandbox-lifecycle.md) |

### 5.9 보안과 자원

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 자원 한도 | pids 폭주 · 메모리 초과가 그 샌드박스 안에서 끝나고 다른 슬롯에 번지지 않음 | [보안 §9](workspace-sandbox-security.md) |
| 보안: 호스트 | 호스트 파일 · Docker 소켓 · K8s API · 서비스 계정 토큰 · 실제 자격 증명 조회 불가, uid ≠ 0 | [보안 §2](workspace-sandbox-security.md) |
| 보안: egress | 허용 목록 밖 도메인 · `169.254.169.254` · 허용 목록 밖 DNS 해석 · OpenSandbox 서버 API 불가 | [보안 §4](workspace-sandbox-security.md) |
| 보안: east-west | 다른 워크스페이스 샌드박스의 IP · execd 포트 접근 불가 | [보안 §2](workspace-sandbox-security.md) |
| `networkIsolation` 을 선언하지 않은 배포 | 기동 거부 | [설정 §3](workspace-sandbox-configuration.md) |
| 보안: 자격 증명 범위 | 허용한 저장소 경로 밖으로의 push 에 토큰이 주입되지 않음 | [보안 §5](workspace-sandbox-security.md) |

### 5.10 셸 엔진

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 서술자와 정적 가용성 — 코어 자신의 제안 사실 추출로 뽑은 사실 | 엔진의 정적 가용성이 참이다(isolation `SANDBOXED`, 런타임 `python3`, 필요한 셸 기능, 런타임 파일 시스템). 사실을 뽑는 것만으로 아무것도 프로비저닝하지 않는다. 포크도 같다 | [셸 엔진 §1](workspace-sandbox-shell-engine.md) |
| `python3` 을 선언하지 않은 프로파일 · 사용 불가 환경 | 엔진이 빠지고 이유를 단다("does not declare the python3 runtime" · "not sandboxed") | [셸 엔진 §1](workspace-sandbox-shell-engine.md) |
| 엔진의 탐침 | 통과하고, 탐침 파일이 남지 않는다 | [셸 엔진 §1](workspace-sandbox-shell-engine.md) |
| 셸을 묻는 것(`shell()` · `supports`) | 프로바이더 호출이 없다 | [셸 엔진 §1](workspace-sandbox-shell-engine.md) |
| `python3` 을 선언한 프로파일, `python3` 이 없는 이미지 (docker 계층) | `runtime-python3` 단계에서 FAILED(permanent). 엔진은 `python3` 을 이유로 사용 불가 | [셸 엔진 §2](workspace-sandbox-shell-engine.md) |
| seed 의 `python3` 확인 조각 | 선언했을 때만 돈다. 없음 · 3.8 미만 · import 실패를 각각 영구 실패로 보고한다 | [셸 엔진 §2](workspace-sandbox-shell-engine.md) |
| 비밀 표지가 든 실행이 도는 동안 (docker 계층) | 어떤 `/proc/*/cmdline` 에도 비밀의 base64 · hex 가 없다. 따로 돌린, 비밀 표지 없는 대조 실행의 인라인 파일은 같은 검사에 잡힌다 | [셸 엔진 §4](workspace-sandbox-shell-engine.md) |
| 비밀 파일 + 작은 파일로 된 실행 디렉터리 | 모두 업로드로 간다. 업로드가 실패하면 `ShellExecutionException` | [셸 엔진 §4](workspace-sandbox-shell-engine.md) |
| 격리 · 비훅 실행의 출력 줄이 프로바이더의 줄 상한을 넘음 (docker 계층) | 20초 안에 kill 원인을 실은 결과로 돌아온다(엔진은 `PROTOCOL_VIOLATION` 으로 읽는다). 훅이면 원인을 말하는 예외다 | [셸 엔진 §5](workspace-sandbox-shell-engine.md) |
| 줄 상한을 넘는 출력 줄 (docker 계층) | 타임아웃이 아닌 실패로 끝나고 잘림으로 표시된다 | [프로바이더 §2](workspace-sandbox-provider.md) |
| stream 실행(훅 · 격리 · 스트리밍 · 타임아웃) 뒤의 execd 스풀 | `await` 가 돌아올 때 출력 사본이 없다. 삭제의 실패 · 지연은 명령 결과를 바꾸지 않는다 | [셸 엔진 §6](workspace-sandbox-shell-engine.md) |
| 바인딩 루트 아래 `.aimon-ptc` | 파일 도구의 읽기 · 쓰기 · 목록에서 보이지 않고, 셸은 읽고 쓴다 | [파일 시스템 §3](workspace-sandbox-filesystem.md) |

엔진 수준에서 "넘친 통로 → `PROTOCOL_VIOLATION`" 을 확인하는 통합 테스트는 두지 않는다. 모델 코드는 런타임이 fd 1 에서 옮긴
통로 fd 에 닿을 수 없으므로, 그 경로는 `SandboxShell` 경계와 코어 자신의 테스트가 고정한다.

### 5.11 멀티 슬롯 · /shared · 오케스트레이터 · 볼륨 (계획 — 구현 단계 5)

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 슬롯 X `touch /workspace/a` → 슬롯 Y | 없다 | [도메인 §2.3](workspace-sandbox-domain.md) |
| 슬롯 X(`rw`) `/shared/r.json` 쓰기 → 슬롯 Y | 보인다 | [파일 시스템 §8](workspace-sandbox-filesystem.md) |
| 슬롯 Y(`ro`) `/shared` 쓰기 | 읽기 전용 에러 | [파일 시스템 §8](workspace-sandbox-filesystem.md) |
| 개발 슬롯 push → egress 차단 리뷰 슬롯(`ro`) fetch | 성공 | [파일 시스템 §8](workspace-sandbox-filesystem.md) |
| `ro` 슬롯이 `repo.git/hooks` 에 훅 쓰기 시도 → 개발 슬롯 push | 쓰기 실패, 개발 슬롯에서 아무것도 실행되지 않음 | [파일 시스템 §8](workspace-sandbox-filesystem.md) |
| 두 슬롯이 동시에 첫 seed | bare 저장소 하나, 둘 다 완전한 clone | [파일 시스템 §8](workspace-sandbox-filesystem.md) |
| 오케스트레이터가 `SandboxStart(review, standard)` 뒤 reviewer(`sandbox.profile: review`) 실행 | reviewer 는 사용 불가, 넓은 프로파일에서 돌지 않음 | [바인딩 §7](workspace-sandbox-binding.md) |
| `SandboxStart(exp-a, experiment)` 뒤 `sandbox.slot: exp-a` 만 적은 서브에이전트 | 그 슬롯에서 돈다 | [바인딩 §7](workspace-sandbox-binding.md) |
| 볼륨 보존을 켠 워크스페이스 close(명시적 · idle) → `retainFor` 경과 | 그 전까지 볼륨이 남고, 지나면 삭제. idle close 뒤 같은 세션은 자동 reopen | [수명 §6.3](workspace-sandbox-lifecycle.md) |
| `review` 슬롯의 오케스트레이터가 `SandboxStart(x, standard)` | 거부 (호출자보다 넓음) | [바인딩 §7](workspace-sandbox-binding.md) |
| close → 볼륨 | 샌드박스가 사라진 뒤 볼륨 삭제. 마운트 중이면 janitor 가 나중에 삭제 | [수명 §6.3](workspace-sandbox-lifecycle.md) |
| `SHARED_VOLUME` 없는 프로바이더 + `sharedAccess: ro` 프로파일 | 기동 거부 | [프로바이더 §6](workspace-sandbox-provider.md) |

### 5.12 영속 저장소 · 멀티 노드 (계획 — 구현 단계 6)

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| 노드 재시작 → 같은 세션의 다음 턴 (영속 저장소) | 같은 샌드박스, 같은 cwd | [도메인 §4.4](workspace-sandbox-domain.md) |
| lease 가 옮겨 간 뒤 옛 노드의 명령이 아직 돎 → 새 노드의 `Bash` | "다른 노드의 명령에 쓰이고 있다" 에러. 옛 노드가 죽었으면 정리 후 실행 | [셸 §3](workspace-sandbox-shell.md) |

### 5.13 worktree (계획 — 구현 단계 7)

| 시나리오 | 기대 | 규칙 |
|---|---|---|
| seed 없는 프로파일에서 `isolate()` | 비어 있음 → 코어가 단계를 거부 | [파일 시스템 §7](workspace-sandbox-filesystem.md) |
| 격리 브랜치 두 개 동시 생성 → `WorktreeMerge.promote` / `SandboxWorktrees.merge` | 둘 다 만들어짐, 둘 다 `repo/` 에 반영. 후자는 커밋 이력이 남고 브랜치를 지운다 | [파일 시스템 §7](workspace-sandbox-filesystem.md) |
| 같은 branchKey 로 워크플로 재실행 | worktree 를 새로 만든다 | [파일 시스템 §7](workspace-sandbox-filesystem.md) |
| 같은 branchKey 를 다른 실행이 쓰는 중에 `isolate()` | 에러, 쓰는 중인 worktree 는 지우지 않음 | [파일 시스템 §7](workspace-sandbox-filesystem.md) |
| 격리 브랜치 도중 LOST | 파생 환경은 에러로 끝남, 빈 트리로 이어 가지 않음 | [파일 시스템 §7](workspace-sandbox-filesystem.md) |

## 6. 셸 엔진의 남은 프로세스 검사

시간 초과나 취소로 끝난 셸 엔진 실행이 샌드박스에 아무것도 남기지 않는다는 주장의 정본은
[셸 엔진 §7](workspace-sandbox-shell-engine.md)이다. 그 주장은 코어 런타임의 앵커 · 워치독 토폴로지와 샌드박스 PID 1 의 좀비
회수에 기댄다. 이 검사는 그 두 전제가 실제로 성립하는지를 확인한다.

**보장하는 것.**

- **토폴로지.** 시간 초과된 실행에서 앵커와 런타임이 서로 다른 프로세스 그룹에 있고, 런타임 그룹 안에 워치독이 있음을 확인한다.
  그래서 이 저장소가 하는 프로세스 그룹 kill 로 충분하다는 전제를 실행마다 다시 본다.
- **귀속.** 테스트마다 앞뒤 스냅숏을 비교해, 남은 런타임 · 자식 · 좀비는 그것을 만든 테스트의 실패로 보고된다. 다른 테스트가 남긴
  것을 이번 테스트의 것으로 잘못 세지 않는다.
- **PID 1 회수.** 샌드박스 PID 1 이 입양된 고아 좀비를 거두는지를 엔진과 무관하게 직접 확인한다 — 기다리지 않는 부모 아래에
  좀비를 만들고, 부모를 죽인 뒤 좀비가 사라지는지 본다. 템플릿 모드는 PID 1 이 `bootstrap.sh` 또는 `execd --init` 임을 확인하고,
  풀 모드는 PID 1 이 task-executor 가 아니거나 `execd --init` 이 subreaper 로 도는지 확인한다.
- **계층.** 이 검사는 docker 계층(클래스마다 함께 쓰는 샌드박스, 테스트마다 검사)과 k8s 계층의 템플릿 모드 · 풀 모드에서 같은
  내용으로 돈다. 기본 계층은 테스트마다 그 샌드박스의 런타임이 호스트에 남지 않았는지 본다. 세 곳 모두 코어 엔진 계약 스위트를
  빠짐없이 돌며, 두 K8s 모드에서 남은 것도 좀비도 없이 통과한다.
- **검출기의 퇴행.** docker 계층의 음성 대조가 검출기 자체를 확인한다. 시간 초과된 실행이 새 세션으로 탈출시킨 자식과, 살아
  있는 부모 아래의 거두지 않은 좀비를 검출기가 표지와 부모 이름을 들어 **보고하는지** 본다. 이 대조는 검출기가 맞는 결과를 내면
  통과하므로 CI 에서 초록이고, 필터가 맞지 않게 되는 식으로 검출기가 퇴행하면 실패한다.

**보장하지 않는 것.** 음성 대조는 검출기가 탈출한 프로세스와 좀비를 잡는다는 것을 보일 뿐, 앵커 · 워치독 이전의 코어가
실패한다는 것을 보이지는 않는다. 그 코어 산출물을 고정할 수 없고, 이 저장소는 코어를 바꾸지 않기 때문이다. 런타임은 runc 만
확인했다.

**운영 요구로 이어지는 결론.** 풀 모드에서 task-executor 가 PID 1 이면 고아를 거둘 프로세스가 없으므로 서버의
`execd_run_as_init = true` 가 필요하다. 템플릿 모드는 샌드박스 PID 1 이 거둔다. 운영자 준비물은
[설정 §5](workspace-sandbox-configuration.md)에 있다.
