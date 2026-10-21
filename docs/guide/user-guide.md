# aimon-sandbox 사용자 가이드

AIMON 에이전트가 쓰는 `Bash` · `Read` · `Write` · `Edit` · `Grep` 을 호스트가 아니라 격리된 샌드박스 안에서 돌게 하는
방법을 다룬다. 대상 독자는 aimon-core 로 에이전트 애플리케이션을 만드는 개발자와 그것을 운영하는 사람이다.

같이 읽을 문서:

- [OpenSandbox 를 Kubernetes 에 설치하기](opensandbox-kubernetes.md) — 운영 환경의 샌드박스 서버를 준비한다
- [aimon-core 와 연동하기](aimon-core-integration.md) — 런타임 · aimon-bootstrap · Spring Boot 에 끼우는 코드
- 설계의 근거는 [`docs/design/workspace-sandbox.md`](../design/workspace-sandbox.md) 에 있다. 이 가이드는 그 설계에서
  사용자가 알아야 할 것만 골라 적는다

---

## 1. 무엇을 해 주는가

에이전트가 방금 쓴 코드를 돌려 보거나 빌드 · 테스트를 하려면 명령을 실행할 곳이 필요하다. 그곳이 애플리케이션 서버
자신이면 모델이 만든 명령이 호스트에서 돈다. 워크스페이스 샌드박스는 **도구를 새로 만들지 않고** 이 문제를 푼다.

- 모델은 평소의 `Bash` · `Read` · `Write` · `Edit` · `Grep` 을 그대로 쓴다. 샌드박스를 고르거나 만드는 도구는 없다
- 샌드박스를 켜면 이 도구들이 [OpenSandbox](https://github.com/alibaba/OpenSandbox) 컨테이너 안에서 돈다
- 어떤 샌드박스를 쓸지, 언제 만들고 언제 지울지는 **플랫폼이 정한다.** 모델은 정하지 못한다

### 1.1 용어

| 용어 | 뜻 |
|---|---|
| **워크스페이스** | 샌드박스가 붙는 논리 단위. 레코드로 저장되고 소유자 · 테넌트 · 상태를 가진다. 세션 하나마다 자동으로 생기는 `ws:{sessionId}` 와 애플리케이션이 미리 만드는 관리형 `wsm:{uuid}` 가 있다 |
| **슬롯** | 워크스페이스 안의 샌드박스 자리. 지금은 `primary` 하나뿐이다 |
| **세대(generation)** | 슬롯에 샌드박스가 새로 만들어질 때마다 오르는 번호. 샌드박스를 잃고 다시 만들면 세대가 오르고, 모델은 "다시 만들어졌다"는 알림을 받는다 |
| **프로파일** | 샌드박스의 이미지 · 자원 · 네트워크 · 수명 규칙을 묶은 운영자 설정. 모델은 프로파일을 만들거나 고칠 수 없다 |
| **프로바이더** | 실제 샌드박스를 만드는 구현. 운영용은 `OpenSandboxProvider` 하나다 |
| **janitor** | 주기적으로 도는 정리 작업. idle 샌드박스를 끝내고, 레코드 없는 샌드박스(고아)를 지우고, 사라진 샌드박스를 찾아낸다 |

### 1.2 수명 한눈에 보기

```
세션의 첫 명령 ──▶ 워크스페이스 ws:{sessionId} 생성 ──▶ primary 슬롯 프로비저닝(샌드박스 생성 + seed 검사)
                                                         │
                       명령 · 파일 도구가 이 샌드박스 안에서 돈다 ◀┘
                                                         │
             idle 이 프로파일의 terminate-after 를 넘음 ──▶ 샌드박스 종료(파일도 사라진다)
                                                         │
                            다음 명령 ──▶ 새 세대로 다시 만든다 + 모델에게 "recreated" 알림
                                                         │
                                세션 삭제 · close-after ──▶ 워크스페이스 종료
```

- **게으르게 만든다.** 명령을 한 번도 치지 않는 턴은 샌드박스를 만들지 않는다. 시스템 프롬프트의 작업 디렉터리 ·
  플랫폼 정보는 프로파일의 선언 값으로 채운다
- **파일은 아직 남지 않는다.** 샌드박스가 끝나면 `/workspace` 도 사라지고, 다음 사용은 빈 `/workspace` 에서
  시작한다. 영속 파일은 공유 볼륨(구현 5단계)과 함께 들어온다

---

## 2. 모듈과 버전

| 모듈 | 좌표 | 쓰는 곳 |
|---|---|---|
| `aimon-sandbox` | `at.aimon.sandbox:aimon-sandbox` | 워크스페이스 · 바인딩 · janitor · aimon-core 의 실행 환경 구현. 항상 필요하다 |
| `aimon-sandbox-opensandbox` | `at.aimon.sandbox:aimon-sandbox-opensandbox` | 운영용 프로바이더(OpenSandbox REST API) |
| `aimon-sandbox-testkit` | `at.aimon.sandbox:aimon-sandbox-testkit` | 테스트 전용. 계약 스위트, `LocalProcessSandboxProvider`(**격리 없음 — 운영 금지**), 장애 주입 |

```kotlin
dependencies {
    implementation("at.aimon.sandbox:aimon-sandbox:<version>")
    implementation("at.aimon.sandbox:aimon-sandbox-opensandbox:<version>")
    testImplementation("at.aimon.sandbox:aimon-sandbox-testkit:<version>")
}
```

| aimon-sandbox | 필요한 aimon-core |
|---|---|
| `0.1.0` | `0.3.1` |
| `main`(다음 릴리스) | `0.3.3` — 세션 소유자 기록, 세션 삭제 리스너, 요청 속성, 모든 도구 결과의 notice, 훅 실행 디렉터리를 쓴다. 코어 0.3.3 은 0.3.2 와 호환되지 않으므로(자동 압축 buffer · ratio 멤버 제거 kangwoo/aimon-core#285, 세션 로그 기본 v2 — 버전이 섞인 클러스터는 `v1` 고정 kangwoo/aimon-core#286) 마이너 업그레이드처럼 올린다([코어 0.3.3 릴리스 노트](https://github.com/kangwoo/aimon-core/releases/tag/v0.3.3)) |

이 가이드는 `main` 을 기준으로 쓴다. `0.1.0` 에는 `sessionRecords(...)` · `sessionLifecycleListener()` · 요청 속성이
없다. 바뀐 점은 [`CHANGELOG.md`](../../CHANGELOG.md) 에 있다.

---

## 3. 빠른 시작 — 로컬 Docker 로 돌려 보기

운영 전에 노트북에서 동작을 확인하는 경로다. **Docker 런타임은 격리 경계를 세울 수 없으므로 로컬 개발 전용이다.**

### 3.1 OpenSandbox 서버 띄우기

이 저장소의 docker 테스트 계층이 쓰는 설정을 그대로 쓴다. 아래 `config.toml` 은
[`server-config.toml`](../../modules/aimon-sandbox-opensandbox/src/test/resources/at/aimon/sandbox/opensandbox/server-config.toml)
의 자리표시자를 채운 것이다(`drop_capabilities` 등 컨테이너를 조이는 줄도 빼지 않는다).

```toml
# ~/opensandbox/config.toml
[server]
host = "0.0.0.0"
port = 8090
api_key = "dev-key"
max_sandbox_timeout_seconds = 7200      # 프로바이더의 maxExpiry 와 같은 값이어야 한다

[proxy]
resolve_internal = false

[log]
level = "INFO"

[runtime]
type = "docker"
execd_image = "opensandbox/execd:v1.1.0"

[storage]
allowed_host_paths = []

[store]
type = "sqlite"
path = "/tmp/opensandbox.db"

[docker]
network_mode = "bridge"                 # egress dns+nft 를 쓰려면 bridge 여야 한다
host_ip = "host.docker.internal"
drop_capabilities = ["AUDIT_WRITE", "MKNOD", "NET_ADMIN", "NET_RAW", "SYS_ADMIN", "SYS_MODULE", "SYS_PTRACE", "SYS_TIME", "SYS_TTY_CONFIG"]
no_new_privileges = true
apparmor_profile = ""
pids_limit = 4096
seccomp_profile = ""

[ingress]
mode = "direct"

[egress]
image = "opensandbox/egress:v1.1.7"
mode = "dns+nft"
readiness_timeout_seconds = 30.0
```

```bash
docker run -d --name opensandbox -p 8090:8090 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host host.docker.internal:host-gateway \
  -v ~/opensandbox/config.toml:/etc/opensandbox/config.toml:ro \
  opensandbox/server:release-1.1.0

curl -s localhost:8090/health
```

테스트 계층은 서버 · execd · egress 이미지를 digest 로 고정해 쓴다
([`OpenSandboxTestServer.java`](../../modules/aimon-sandbox-opensandbox/src/test/java/at/aimon/sandbox/opensandbox/OpenSandboxTestServer.java)).
같은 동작을 원하면 거기 적힌 digest 를 쓴다.

> 서버는 호스트의 Docker 소켓을 쓰고, 샌드박스의 execd 포트는 호스트 인터페이스에 열린다. 개발 머신에서만 돌린다.

### 3.2 샌드박스 이미지 만들기

이미지는 [이미지 계약](#7-샌드박스-이미지-계약)을 지켜야 한다. 로컬에서는 테스트 계층의 이미지로 충분하다.

```bash
docker build -t aimon-sandbox-dev modules/aimon-sandbox-opensandbox/src/test/docker
```

### 3.3 코드로 조립하기

```java
OpenSandboxProvider provider = new OpenSandboxProvider(OpenSandboxProviderConfig.builder()
        .endpoint(URI.create("http://localhost:8090"))
        .apiKey(() -> "dev-key")
        .runtime(OpenSandboxProviderConfig.Runtime.DOCKER)
        .maxExpiry(Duration.ofHours(2))                    // = max_sandbox_timeout_seconds
        .useServerProxy(true)                              // execd 를 서버의 프록시 경로로 부른다
        .egressEnforcement(OpenSandboxProviderConfig.EgressEnforcement.DNS_NFT)
        .build());

SandboxProfile dev = SandboxProfile.builder()
        .name("dev")
        .image("aimon-sandbox-dev")
        .platform("linux")
        .resources(ResourceSpec.of("1", "1Gi", null, null))
        .egress(List.of("github.com", "repo.maven.apache.org"))   // 빈 목록이면 전부 차단
        .terminateAfter(Duration.ofMinutes(30))
        // Docker 런타임은 이 둘을 줄 수 없다. 로컬 개발에서만 일부러 푼다
        .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION))
        .build();

WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
        .settings(SandboxSettings.builder()
                .deployment("dev-laptop")
                .profiles(List.of(dev))
                .defaultProfile("dev")
                .build())
        .provider(provider)
        .ownProvider(true)                                 // sandbox.close() 가 provider 도 닫는다
        .build();
sandbox.janitor().start();
```

이제 `sandbox.environmentProvider()` 를 aimon-core 에 넘기면 된다. 넘기는 방법은
[aimon-core 와 연동하기](aimon-core-integration.md) 에 있다. 첫 `Bash` 호출에서 샌드박스가 만들어지고, 이후 같은
세션의 명령은 모두 그 안에서 돈다.

---

## 4. 설정

설정은 세 객체로 나뉜다. 모두 빌더로 만들고, **기동 시 한꺼번에 검사한다** — 어긴 것이 하나라도 있으면
`WorkspaceSandbox.Builder.build()` 나 `OpenSandboxProviderConfig.Builder.build()` 가 위반을 모두 모아
`SandboxConfigurationException` 하나로 던진다.

| 객체 | 범위 |
|---|---|
| `SandboxSettings` | 배포 전체: 주체 규칙, 수명 · janitor 주기, 쿼터, 프로파일 목록 |
| `SandboxProfile` | 샌드박스 하나의 모양: 이미지, 자원, egress, 자격 증명, idle 시간 |
| `OpenSandboxProviderConfig` | OpenSandbox 서버와의 연결과, 서버가 실제로 무엇을 집행하는지에 대한 운영자의 선언 |

> 이 저장소에는 Spring 속성 바인딩이 없다. 설계 문서 §13.2 의 `aimon.sandbox.*` YAML 키는 **제안된 이름**이고, 각
> 빌더 메서드가 그 키에 대응한다(`provision-timeout` → `provisionTimeout(...)`). Spring 에서 쓰려면 직접
> `@ConfigurationProperties` 로 받아 빌더에 옮긴다.

### 4.1 `SandboxSettings`

| 키 / 빌더 메서드 | 기본값 | 뜻 |
|---|---|---|
| `deployment` | **필수** | 같은 저장소를 나눠 쓰는 노드 묶음의 이름. 샌드박스 라벨에 들어가고, janitor 는 이 값이 같은 샌드박스만 정리한다. K8s 라벨 규칙을 지켜야 한다 |
| `require-principal` | `false` | 켜면 USER/GROUP 주체(또는 `allowed-system-principals`)만 샌드박스를 쓴다. 멀티테넌트에서 켠다. `SandboxTenantResolver` 와 세션 소유자 출처가 필요하다 |
| `workspace-access` | `PRINCIPAL` | 소유자 말고 누가 워크스페이스를 쓸 수 있는가. `PRINCIPAL`: 테넌트와 주체가 모두 같아야 한다. `TENANT`: 테넌트만 같으면 된다 |
| `allowed-system-principals` | 없음 | 샌드박스를 써도 되는 SYSTEM/SERVICE 주체 id |
| `unattached` | `PER_SESSION` | 어디에도 붙지 않은 세션의 처리. `PER_SESSION`: `ws:{sessionId}` 를 만든다. `REJECT`: 샌드박스를 주지 않는다 |
| `allow-shared-workspaces` | `false` | `PRINCIPAL` 배포에서 `access: tenant` 인 관리형 워크스페이스를 허용할지 |
| `max-managed-per-owner` | `20` | 소유자 하나가 가질 수 있는 관리형 워크스페이스 수. 끄려면 `SandboxSettings.UNLIMITED` 를 명시한다 |
| `admission.max-running-per-tenant` | `10` | 테넌트별 동시 실행 샌드박스 상한. 끄려면 `UNLIMITED` 를 명시한다 |
| `provision-timeout` | `5m` | 프로비저닝 한 번의 상한 |
| `orphan-grace` | `10m` | 레코드 없는 샌드박스를 지우기 전 기다리는 시간. `provision-timeout` 보다 길어야 한다 |
| `failure-backoff` · `max-failure-backoff` | `1m` · `15m` | 일시 장애 재시도 간격(두 배씩) |
| `activity-write-interval` | `30s` | 활동 기록 · 만료 연장 · heartbeat 주기 |
| `cas-retries` | `5` | 워크스페이스 레코드의 CAS 충돌 재시도 횟수 |
| `lost-confirm-after` | `90s` | RUNNING 슬롯의 샌드박스가 이만큼 안 보이면 LOST 로 판정한다 |
| `close-wait` | `2m` | 워크스페이스를 닫을 때 샌드박스가 사라지기를 기다리는 시간 |
| `close-resume-after` | `5m` | CLOSING 에 멈춘 워크스페이스를 janitor 가 이어서 닫기까지의 시간 |
| `closed-retention` | `7d` | 닫힌 워크스페이스의 레코드를 남기는 기간 |
| `exec-shell-idle` | `10m` | 쓰지 않는 `exec:` 셸 디렉터리를 지우기까지의 시간 |
| `shell-lock-wait` | `10s` | 명령이 셸 락을 기다리는 시간 |
| `janitor.interval` | `30s` | janitor 주기 |
| `workspace.close-after` | `24h` | 워크스페이스를 닫기까지의 idle 시간 |
| `workspace.max-slots` · `workspace.max-running` | `WorkspaceQuota.defaults()` | 새 워크스페이스 레코드에 복사되는 쿼터(`quota(...)`) |
| `default-profile` · `profiles` | — | 프로파일 목록과 기본 프로파일 |
| `node-id` | `{hostname}-{uuid}` | 노드 식별자. JVM 마다 새로 뽑는다. 보통 두지 않는다 |

### 4.2 `SandboxProfile`

| 키 / 빌더 메서드 | 예 / 기본 | 뜻 |
|---|---|---|
| `name` | `standard` | 프로파일 이름 |
| `image` | `ghcr.io/acme/aimon-sandbox-runtime:1` | 샌드박스 이미지. [이미지 계약](#7-샌드박스-이미지-계약)을 지켜야 한다 |
| `platform` · `osVersion` · `shellName` | `linux` · `Linux 6.x` · `bash` | 시스템 프롬프트에 들어가는 선언 값. seed 단계가 `uname` 과 대조한다. `osVersion` 은 glob 으로 맞춘다(`Linux 6.x` 는 `Linux 6.*`) |
| `resources` | `ResourceSpec.of("2", "4Gi", null, null)` | cpu · memory · disk · pids. 없으면 프로바이더의 `default-resources`. `disk` · `pids` 는 OpenSandbox 요청 필드가 없어 서버 설정으로만 걸린다 |
| `runtimeClass` | `kata` | 서버에 설정한 RuntimeClass 이름과 **같아야** 한다. OpenSandbox 는 요청마다 class 를 받지 않는다 |
| `egress` | 예: `[]` · 기본: 없음 | 나갈 수 있는 호스트 목록. **빈 목록은 전부 차단**이다. 아예 두지 않으면(기본) egress 정책을 보내지 않아 전부 열린다. 두면 `EGRESS_POLICY` 가 필요하다 |
| `credentials` | `[github-acme-rw]` | 프로바이더에 정의한 자격 증명 바인딩 이름. 그 호스트를 `egress` 가 명시적으로 허용해야 한다 |
| `environment` | `{LANG: C.UTF-8}` | 정적 환경 변수. **비밀을 넣지 않는다** — 비밀은 `credentials` 로 |
| `terminateAfter` | `2h` | **필수.** 이만큼 idle 이면 샌드박스를 끝낸다. 프로바이더의 `max-expiry` 이하, `3 × activity-write-interval` 이상 |
| `backgroundHeartbeatLimit` | `1h` | 백그라운드 명령이 샌드박스를 깨워 둘 수 있는 상한 |
| `backgroundCommandTimeout` | `24h` | 백그라운드 명령이 돌 수 있는 상한. 넘으면 셸이 멈춘다 |
| `insecureAllow` | `[]` | 일부러 포기하는 격리 capability. `HARDENED_SECURITY_CONTEXT` · `RUNTIME_CLASS` · `NETWORK_ISOLATION` 만 가능. 로컬 개발 전용, 기동 시 경고 |
| `pauseAfter` · `sharedAccess` · `seed` | — | **아직 구현되지 않았다.** 쓰면 기동이 거부된다 |

프로파일은 다음 capability 를 요구하고, 프로바이더가 광고하지 않으면 기동이 실패한다.

- 항상: `EXEC` · `FILES` · `EXPIRY` · `HARDENED_SECURITY_CONTEXT` · `NETWORK_ISOLATION`(`insecureAllow` 로 뺄 수 있다)
- `egress` 가 있으면 `EGRESS_POLICY`, `credentials` 가 있으면 `CREDENTIAL_INJECTION`, `runtimeClass` 가 있으면 `RUNTIME_CLASS`

### 4.3 `OpenSandboxProviderConfig`

전체 표는 [프로바이더 README](../../modules/aimon-sandbox-opensandbox/README.md) 에 있다. 자주 만지는 것만 적는다.

| 빌더 메서드 | 뜻 |
|---|---|
| `endpoint` · `apiKey` | 서버 주소와 API 키. 키는 공급자(`Supplier`)로 받아 요청마다 읽는다 |
| `runtime` | `DOCKER` · `KUBERNETES`. 어떤 선언이 합법인지 정한다 |
| `maxExpiry` | 서버의 `max_sandbox_timeout_seconds` 와 **같은 값.** API 로 읽을 수 없으니 손으로 맞춘다. 더 크면 `create` 가 400 이다 |
| `egressEnforcement` | `NONE` · `DNS` · `DNS_NFT`. `DNS_NFT` 만 `EGRESS_POLICY` 를 광고한다 |
| `networkIsolation(DECLARED)` | 운영자가 NetworkPolicy 로 east-west · 제어면 차단을 걸었다는 선언(K8s 전용) |
| `hardenedSecurityContext(DECLARED)` | 서버의 샌드박스 템플릿을 강화했다는 선언(K8s 전용) |
| `controlPlaneProbes` | seed 단계에서 샌드박스가 **연결에 실패해야 하는** 주소. 샌드박스가 보는 서버 주소를 적는다 |
| `runtimeClass(RuntimeClass.of(name, kind))` | 서버의 RuntimeClass. gVisor 와 `DNS_NFT` 는 함께 쓸 수 없다 |
| `credentials` | 이름 → `CredentialDefinition`. `DNS_NFT` 가 필요하다 |
| `useServerProxy` | execd 를 서버의 프록시 경로로 부른다. AIMON 이 샌드박스 파드에 직접 닿지 못할 때 켠다 |

**선언은 확인 없이 믿지 않는다.** 첫 프로비저닝에서 프로바이더의 `verify` 가 바깥에서 egress 집행 모드와 vault
바인딩을 대조하고(step `egress` · `credentials`), 이어서 seed 가 샌드박스 안에서 root 가 아닌지(`root`), ServiceAccount
토큰이 없는지(`service-account-token`), `controlPlaneProbes` 에 연결되지 않는지(`network-isolation`)를 본다. 어긋나면
슬롯을 FAILED(영구)로 만든다. seed 가 보는 것은 여기까지다 — 템플릿에 `drop: [ALL]` · `allowPrivilegeEscalation:
false` · seccomp 가 빠져도 root 만 아니면 통과하므로, 템플릿은 운영자가 책임진다.

### 4.4 자격 증명 — 비밀을 샌드박스에 넣지 않고 쓰게 하기

토큰을 환경 변수로 넣으면 모델이 `env` 로 읽는다. 대신 OpenSandbox egress 사이드카의 vault 에 바인딩을 걸면, 사이드카가
허용한 호스트 · 메서드 · 경로로 나가는 요청에만 헤더를 끼워 넣는다. 샌드박스 안에는 비밀이 없다.

```java
.credentials(Map.of("github-acme-app-rw", CredentialDefinition.of(
        CredentialScope.builder()
                .hosts(Set.of("github.com"))
                .methods(Set.of("GET", "POST"))
                .paths(List.of("/acme/app*"))
                .build(),
        CredentialDefinition.Auth.bearer(),                 // basic() · apiKey("X-Api-Key") 도 있다
        () -> System.getenv("GITHUB_ACME_APP_TOKEN"))))
```

프로파일에는 `egress(List.of("github.com"))` 와 `credentials(List.of("github-acme-app-rw"))` 를 함께 준다.

- HTTPS 주입은 샌드박스가 사이드카의 CA 를 믿어야 동작한다. execd 가 CA 번들을 `SSL_CERT_FILE` 로 알리고, 셸 래퍼가
  git 에 `GIT_SSL_CAINFO` 로 넘긴다. 이 변수를 읽지 않는 도구는 TLS 검증에 실패한다. 이미지에 `ca-certificates` 를
  넣어 둔다
- 한 프로파일의 바인딩끼리 범위가 겹치면 기동이 거부된다

---

## 5. 워크스페이스 고르기 — 어느 실행이 어느 샌드박스를 쓰는가

아무 설정도 하지 않으면 세션의 첫 명령이 그 세션 전용 `ws:{sessionId}` 를 만든다. 여러 세션이 한 샌드박스를 함께
쓰게 하거나, 에이전트가 돌기 전에 샌드박스를 준비해 두려면 관리형 워크스페이스를 쓴다.

루트 실행의 워크스페이스는 다음 중 **먼저 해당하는 것**으로 정한다.

1. 요청 속성 `environment.sandbox.workspace` — 그 실행 하나만
2. 세션 옵션 — `setSessionOptions(...)` 로 붙인 것
3. 에이전트 정의의 기본값 — `attributes: { sandbox: { workspace: wsm:…, unattached: reject } }`
4. unattached 규칙 — `ws:{sessionId}` 를 만들거나 거부한다

`unattached` 는 배포 설정 → 에이전트 → 세션 → 요청 순으로 **조이기만** 할 수 있다. 포크(서브에이전트)는 부모의
워크스페이스를 물려받는다.

### 5.1 관리형 워크스페이스

```java
SandboxWorkspaces workspaces = sandbox.workspaces();

WorkspaceView ws = workspaces.create(CreateWorkspace.named("ticket-4711"), alice);  // 돌아올 때 primary 는 RUNNING
workspaces.setSessionOptions(sessionId, SessionSandboxOptions.Update.attach(ws.id()), alice);
// 이후 그 세션의 모든 턴과 포크가 ws 에서 돈다. 모델은 "옮겨졌다"는 알림을 한 번 받는다

workspaces.clearSessionOptions(sessionId, alice);  // 세션을 떼어 낸다
workspaces.close(ws.id(), alice);                  // 샌드박스를 끝낸다. reopen 으로 다시 열 수 있다
workspaces.purge(ws.id(), alice);                  // 레코드까지 지운다. id 는 재사용하지 않는다
```

- **id 는 생성된다**(`wsm:{uuid}`). `name` 은 사람용이고 모델에게 보이지 않는다
- 첫 프로비저닝이 실패해도 워크스페이스는 남고 `WorkspaceProvisioningException` 이 id 와 함께 나온다.
  `provision(id)` 로 다시 하거나 `purge` 한다
- **쓰기와 관리는 다른 권리다.** 접근 규칙이 허락하면 누구나 쓰지만, close · reopen · purge 는 소유자만 한다
- **세션 옵션에는 세션 소유자 출처가 필요하다.** [연동 가이드 §3](aimon-core-integration.md#3-세션-소유자) 를 본다

### 5.2 한 턴만 옮기기 — 요청 속성

```java
live.submit(prompt, SubmitOptions.builder()
        .executionAttributes(SandboxRequestAttributes.workspace(ws.id())).build());
```

세션 옵션을 쓰지 않고 그 실행 하나만 바인딩한다. 실행하는 주체가 그 워크스페이스를 쓸 권한이 있어야 한다.
`environment.sandbox.unattached: reject`(`SandboxRequestAttributes.unattached(...)`)는 다른 바인딩이 없을 때 그 실행을
거부한다 — 조이기만 할 수 있다. `environment.sandbox.*` 의 이 둘 밖의 키는 거부된다. **모델 출력으로 이 값을 채우지
않는다.**

### 5.3 공유 워크스페이스는 하나의 신뢰 도메인이다

한 워크스페이스에 붙은 모든 세션은 서로의 파일을 읽고 고칠 수 있다. 각 명령이 source 하는 다른 세션의 셸 상태
(`/workspace/.aimon-shell/`)도 마찬가지이고, 프로파일의 자격 증명도 함께 쓴다. 그래서 `PRINCIPAL` 배포에서 주체를
넘나드는 공유는 `allow-shared-workspaces: true` 로 명시해야 한다.

### 5.4 파일 탐색기 — `SandboxWorkspaceFiles`

```java
SandboxWorkspaceFiles files = sandbox.workspaces().files();

WorkspaceFileListing top = files.list(ws.id(), "repo", 500, alice);        // 한 단계만. 디렉터리 먼저, 이름순
Optional<WorkspaceFile> log = files.stat(ws.id(), "repo/build.log", alice);
try (InputStream in = files.read(ws.id(), "repo/build.log", 0, 512 * 1024, alice)) { ... }  // 미리보기는 앞부분만
files.write(ws.id(), "repo/input.csv", upload, size, false, alice);        // 있으면 FileAlreadyExistsException
files.move(ws.id(), "repo/a.txt", "repo/b.txt", false, alice);              // 이름 바꾸기도 move
files.delete(ws.id(), "repo/tmp", true, alice);
```

사람이 쓰는 파일 탐색기의 백엔드다. 실행 중인 `primary` 샌드박스의 `/workspace` 아래를 보고 고친다
([설계](../design/workspace-sandbox-files.md)).

- **샌드박스를 띄우지 않는다.** RUNNING 이 아니면 `WorkspaceFilesUnavailableException` 이 나오고 `reason()` 이
  `NOT_RUNNING` · `CLOSED` · `CLOSING` · `NOT_FOUND` · `UNREACHABLE` 중 하나다. 앞의 넷은 화면에 "실행 중인 샌드박스
  없음" 같은 상태로 보여 주고, 다시 시도하는 루프를 돌리지 않는다. `UNREACHABLE`(프로바이더나 저장소 장애)만 재시도할
  만하다. 열어 보는 것만으로 프로비저닝 · idle reopen · reset 이 일어나지 않으니
  모델이 엉뚱한 reset 알림을 받는 일도 없다. 띄우려면 `workspaces.provision(id, caller)` 를 따로 부른다
- **경로는 `/workspace` 기준 상대 경로다.** `""` 가 `/workspace` 자체다. `..` 로 벗어나는 경로, `/` 로 시작하는
  경로는 `InvalidPathException` 이다. 에이전트 파일 도구의 `repo/x` 와 여기의 `repo/x` 는 기본 root 에서 같은 파일이다
- **`/workspace` 자체와 `.aimon-*` 항목은 읽기 전용이다**(`WorkspaceFile.readOnly()`). 목록과 읽기는 되고, 쓰기 ·
  삭제 · 이동은 `FileAccessDeniedException` 이다. `.aimon-shell` 에는 세션마다 cwd 와 export 가 있고 모든 명령이
  그것을 source 하기 때문이다. 실수를 막는 장치일 뿐 보안 경계는 아니다(§5.3)
- **권한은 워크스페이스 사용 권리다.** 세션을 붙일 수 있는 사람이면 파일도 본다. 세션이 만든 `ws:{sessionId}` 는
  그 세션의 소유자가 본다. 권한이 없는 워크스페이스는 없는 워크스페이스와 똑같이 `NOT_FOUND` 로 답한다
- **변경은 활동으로 친다.** 쓰기 · mkdir · move · delete 는 idle close 와 프로바이더 만료를 미루고
  `SandboxEvent.Type.FILES_CHANGED` 를 행위자와 함께 남긴다. 읽기는 활동이 아니다 — 탐색기를 열어 둬도 샌드박스가
  idle 로 닫힌다
- **모델에게는 알리지 않는다.** 대신 core 의 `Edit` · `Write` 가 "File changed since it was read" 로 거부하므로,
  사람이 고친 파일을 모델이 파일 도구로 모르고 덮어쓰지는 않는다. 셸(`sed -i`, `>`)에는 이런 검사가 없다
- **읽기는 4 MiB 씩 나눠 받는다.** 큰 파일을 내려받아도 호스트 메모리에 통째로 올리지 않는다. 중간에 실패하면
  스트림의 `read()` 에서 `WorkspaceFilesUnavailableException` 이 나온다
- `move` 는 대상의 없는 상위 디렉터리를 만든다. 쓰기가 끝난 뒤의 실패(타임아웃 등)는 변경이 이미 반영됐을 수 있고,
  그때도 `FILES_CHANGED` 는 남는다
- **업로드 크기 제한은 호스트가 한다.** `write` 의 `length` 는 모르면 `-1`
- 목록은 `limit`(최대 10 000)을 넘으면 `truncated()` 가 true 이고, 검색은 10만 항목이 넘는 트리에서 실패한다.
  디렉터리 전체 사용량 요약은 없다

---

## 6. 모델이 보는 것

- **시스템 프롬프트**에 작업 디렉터리(`/workspace/repo`), 플랫폼, `isolated sandbox (profile 'standard')`, "셸 상태는
  cwd 와 export 한 변수만 이어진다"가 들어간다. 이 값들은 원격 호출 없이 프로파일의 선언으로 채운다
- **셸 상태**: `cd` 와 `export` 는 다음 명령으로 이어진다. 별칭 · 함수 · `set` 옵션은 이어지지 않는다
- **상대 경로의 기준이 다르다.** 파일 도구는 늘 `/workspace/repo` 를, 셸은 마지막으로 `cd` 한 곳을 기준으로 삼는다.
  `cd src` 뒤의 `Read Foo.java` 는 `src/Foo.java` 가 아니다. 프롬프트가 절대 경로를 쓰라고 안내한다
- **환경 알림**: 샌드박스가 초기화되거나, 다시 만들어지거나, 다른 워크스페이스로 옮겨지면 **다음 도구 결과의
  맨 앞**에 `[environment]` 줄로 한 번 알린다. `Read` 결과에도 붙는다
- **백그라운드 명령**은 `KillShell` 로 멈출 수 있고, 아무도 멈추지 않으면 `backgroundCommandTimeout` 에서 끝난다

### 6.1 샌드박스 안의 배치

```
/workspace
  repo/                               기본 작업 디렉터리
  .aimon-staged/<name>/<contentKey>/  스킬 파일의 사본(필요할 때만). 파일 도구에는 읽기 전용
  .aimon-shell/<h(shellKey)>/         셸 상태 · 락 · heartbeat. 파일 도구에는 읽기 전용
  .aimon-shell/.run.<hex>/            훅 실행 하나의 비공개 디렉터리. 실행이 끝나면 지운다
```

에이전트의 VFS(스킬 · 메모리 파일)는 샌드박스의 파일 도구에 보이지 않는다. 스킬이 스크립트를 돌려야 하면 그
스킬의 파일만 `.aimon-staged/` 로 올린다.

### 6.2 샌드박스를 쓸 수 없을 때

바인딩이 거부되거나, 프로비저닝이 실패하거나, 서버에 닿지 못하면 실행 환경은 "unavailable" 이 되고 도구 호출이
이유와 함께 실패한다. 호스트로 몰래 돌아가지 않는다.

- 스킬의 `preTool` 셸 훅이 지키는 도구 호출은 **막힌다.** `onStart` 셸 가드가 있는 스킬 포크는 **시작하지 않는다**
- 관찰만 하는 훅에는 `failOpen: true` 를 준다

---

## 7. 샌드박스 이미지 계약

샌드박스 이미지는 다음을 갖춰야 한다. 어기면 첫 프로비저닝의 seed 단계(`image-contract`)가 슬롯을 FAILED 로 만든다.

- `bash`
- **GNU** coreutils — `sha256sum` 과 `base64` 가 **`/usr/bin`** 에 있어야 한다. 훅 파일을 풀고 다이제스트를 확인하는 데
  쓴다. BusyBox 의 `sha256sum` 은 `--strict` 를 몰라 seed 의 `image-contract` 검사에서 실패한다
- util-linux `flock`, `git`, `rg`(ripgrep)
- uid 1000 사용자 `sandbox` 와 그 사용자가 쓸 수 있는 `/workspace`
- 권장: `ca-certificates`(자격 증명 주입), `procps`

execd 는 OpenSandbox 가 주입하므로 이미지에 넣지 않는다. 운영용 Dockerfile 예:

```dockerfile
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash coreutils util-linux git ripgrep procps ca-certificates curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 1000 --create-home --shell /bin/bash sandbox \
 && mkdir -p /workspace && chown 1000:1000 /workspace
USER 1000
WORKDIR /workspace
CMD ["tail", "-f", "/dev/null"]
```

**실행 uid 는 execd 의 uid 다.** "uid 1000 으로 돈다"는 이미지만으로는 지켜지지 않고, 서버의 샌드박스 템플릿이
`runAsUser: 1000` 을 걸어야 한다([K8s 설치 가이드 §5](opensandbox-kubernetes.md#5-샌드박스-템플릿-강화)). root 로
도는 샌드박스는 `insecureAllow` 없이는 FAILED 가 된다.

---

## 8. 운영

### 8.1 운영자 API — `SandboxAdministration`

```java
SandboxAdministration admin = SandboxAdministration.of(sandbox);
admin.sandboxes().forEach(view -> log.info("{} {}", view.refHash(), view.verdict()));
admin.terminateSlot(workspaceId, "primary", operator);   // 다음 사용 때 새 세대로 만든다
```

모든 워크스페이스와 붙은 세션, 프로바이더가 돌리는 모든 샌드박스와 janitor 의 판정(`OK` · `IN_FLIGHT` · `ORPHAN` ·
`STALE` …)을 본다. 대신 만들기 · 붙이기 · 떼기 · terminate · close · reopen · purge 를 할 수 있다.

- **소유자 · 테넌트를 검사하지 않는다.** 반드시 애플리케이션의 운영자 인가 뒤에 두고, 일반 호출자에게 넘기지 않는다
- 모든 쓰기는 `SandboxEvent.actor()` 에 행위자를 남긴다
- `admin.files()` 는 아무 워크스페이스의 파일 탐색기다(§5.4). 행위자 principal 이 필수이고, 역시 샌드박스를 띄우지
  않는다

### 8.2 이벤트와 관측

`WorkspaceSandbox.builder().eventListener(...)` 로 수명 이벤트(`SandboxEvent`)를 받는다. 감사 로그와 지표를 여기서
만든다. 설계 §14 의 tracing span · 속성(`aimon.sandbox.workspace` 등)은 아직 구현되지 않았다.

### 8.3 여러 노드

**지금의 저장소(`InMemorySandboxWorkspaceStore` · `InMemorySessionOptionsStore`)는 한 노드 전용이다.** 여러 노드가 각자
InMemory 저장소로 같은 `deployment` 를 쓰면 janitor 가 서로의 샌드박스를 고아로 보고 지운다. 영속 저장소(6단계)가 오기
전에는 다음 중 하나로 한다.

- AIMON 노드를 하나만 둔다
- 노드마다 `deployment` 를 다르게 주고, 세션이 늘 같은 노드로 가게 라우팅한다

### 8.4 보안 체크리스트

- [ ] OpenSandbox 서버의 엔드포인트는 AIMON 노드만 닿는 네트워크에 있다. 서버 프록시 경로와 execd 는 인증하지 않는다 —
      샌드박스 id 를 알면 명령을 실행할 수 있다
- [ ] Docker 런타임은 운영에 쓰지 않는다
- [ ] K8s 에서 NetworkPolicy 와 강화한 템플릿을 걸고, 그때만 `DECLARED` 를 준다
- [ ] 프로파일의 `environment` 에 비밀이 없다. 비밀은 `credentials` 로
- [ ] `insecureAllow` 를 쓰는 프로파일이 운영에 없다
- [ ] 멀티테넌트면 `require-principal: true` 와 `SandboxTenantResolver` 를 둔다
- [ ] `SandboxAdministration` 은 운영자 인가 뒤에만 있다
- [ ] 에이전트에 맞서야 하는 가드 훅은 `"shell": "host"` 로 호스트에서 돌리고, stdin 의 도구 입력만 판단한다

---

## 9. 문제 해결

| 증상 | 원인과 조치 |
|---|---|
| 기동 시 `SandboxConfigurationException` | 메시지에 위반이 모두 나열된다. 흔한 것: `deployment` 없음, `terminate-after` 없음, `orphan-grace ≤ provision-timeout`, 프로바이더가 광고하지 않는 capability(Docker 런타임에서 `insecureAllow` 누락), 프로파일 `runtimeClass` 와 서버 class 불일치 |
| 첫 명령이 FAILED, step `image-contract` | 이미지에 `git` · `rg` · `flock` · GNU `sha256sum` · `base64` 중 하나가 없다. BusyBox `sha256sum` 도 여기서 걸린다 |
| 첫 명령이 FAILED, step `workspace` · `root-directory` | `/workspace` 가 없거나 uid 1000 이 쓸 수 없다 |
| 첫 명령이 FAILED, step `platform` · `os-version` | 프로파일의 `platform` · `osVersion` 이 이미지의 `uname` 과 다르다 |
| 첫 명령이 FAILED, step `root` | 샌드박스가 root 로 돈다. 서버 템플릿에 `runAsUser: 1000` 을 건다 |
| 첫 명령이 FAILED, step `service-account-token` | 샌드박스에 ServiceAccount 토큰이 마운트됐다. 템플릿의 `automountServiceAccountToken: false` 를 확인한다 |
| 첫 명령이 FAILED, step `network-isolation` | 샌드박스가 `control-plane-probes` 의 주소에 연결됐다. NetworkPolicy 가 없거나 CNI 가 집행하지 않는다 |
| 첫 명령이 FAILED, step `egress` · `credentials` | 서버의 egress 모드가 `dns+nft` 가 아니거나 vault 바인딩이 설정과 다르다 |
| Docker 에서 첫 `create` 가 400 | `dns+nft` 인데 서버가 `network_mode = "bridge"` 가 아니다 |
| `create` 가 400 (만료) | `maxExpiry` 가 서버의 `max_sandbox_timeout_seconds` 보다 크다 |
| HTTPS 요청이 인증서 오류 | 자격 증명 주입 중이다. 도구가 `SSL_CERT_FILE` 을 읽게 하거나 이미지에 `ca-certificates` 를 넣는다 |
| 모든 턴이 "not permitted" / unavailable | 세션 소유자를 모른다. `SubmitRequest.owner(...)` 를 주거나 `SessionOwnerLookup` 을 둔다([연동 가이드 §3](aimon-core-integration.md#3-세션-소유자)) |
| 세션을 지우고 같은 id 로 다시 만들었더니 "being removed; retry shortly" | 옛 샌드박스를 지우는 중이다. 보통 `close-wait` 안에 풀린다 |
| 파일이 사라졌다 | `terminate-after` 동안 idle 이었다. 아직 파일은 영속되지 않는다 |

---

## 10. 아직 없는 것

- `primary` 밖의 슬롯, 공유 볼륨 `/shared`, seed(git clone), 오케스트레이터 도구 — 5단계
- 여러 노드를 위한 영속 저장소(`aimon-sandbox-store-jdbc`) — 6단계
- pause/resume, worktree 격리 — 7단계

이 기능을 쓰는 설정은 조용히 무시하지 않고 기동 시 거부한다.
