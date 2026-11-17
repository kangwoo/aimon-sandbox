# 워크스페이스 샌드박스 — 설정

운영자가 정하는 것을 다룬다: 프로파일, 배포 설정, 기동 검사, 이미지 계약, 운영자가 미리 준비할 것. 각 값의 의미와 이유는 그
값을 쓰는 주제 문서가 갖고, 이 문서는 필드 · 기본값 · 검사 · 계약을 갖는다.

> 관련: [개요](workspace-sandbox.md) · [보안](workspace-sandbox-security.md) · [OpenSandbox](workspace-sandbox-opensandbox.md) ·
> [수명](workspace-sandbox-lifecycle.md) · [셸 엔진](workspace-sandbox-shell-engine.md) ·
> [OpenSandbox 를 Kubernetes 에 설치하기](../guide/opensandbox-kubernetes.md)

---

## 1. 프로파일

프로파일(`SandboxProfile`)은 샌드박스를 어떻게 만들지 정한 **운영자 설정**이다. 모델은 프로파일을 고를 수만 있고, 만들거나
고칠 수 없다. 고르는 경로는 오케스트레이터 도구의 허용 목록 안에서 자기 프로파일보다 넓지 않은 것만이다
([바인딩 §7](workspace-sandbox-binding.md), **계획 — 구현 단계 5**).

### 1.1 필드

| 필드 | 기본 / 예 | 뜻 |
|------|----------|-----|
| `name` | 필수. 예 `standard` | 프로파일 이름. 한 배포 안에서 겹치지 않는다 |
| `image` | 예 `ghcr.io/kangwoo/aimon-sandbox-runtime:1` | 샌드박스 이미지. §4 의 이미지 계약을 지켜야 한다 |
| `platform` · `osVersion` · `shellName` | `linux` · 없음 · `bash` | 서술자에 그대로 들어가는 선언값([개요 §6.2](workspace-sandbox.md)). seed 가 실제 이미지와 대조한다 |
| `cpu` · `memory` · `disk` · `pids` | 없음. 예 `2` · `4Gi` · `20Gi` · `512` | 자원 상한. 주지 않으면 프로바이더의 기본 자원을 보낸다(§2.2) |
| `runtimeClass` | 없음. 예 `kata` | 런타임 클래스. 프로바이더가 선언한 서버의 클래스와 같아야 한다(§3) |
| `egress` | 없음 | egress 허용 목록. **없음**(정책을 요구하지 않음)과 **빈 목록**(전부 차단)이 다르다. 빈 목록도 `EGRESS_POLICY` 를 요구한다 — [보안 §4](workspace-sandbox-security.md) |
| `credentials` | `[]` | 자격 증명 바인딩 이름 목록. vault 쪽 정의를 가리킨다 — [보안 §5](workspace-sandbox-security.md) |
| `env` | `{}` | 정적 환경 변수. **비밀을 넣지 않는다** — [보안 §5](workspace-sandbox-security.md) |
| `terminateAfter` | **필수** | 슬롯이 활동 없이 이만큼 지나면 샌드박스를 끝낸다. 프로바이더 만료의 기준이기도 하다 — [수명 §4](workspace-sandbox-lifecycle.md), [수명 §5](workspace-sandbox-lifecycle.md) |
| `pauseAfter` | 없음 | **(계획 — 구현 단계 7)** 슬롯을 멈추는 idle 시간. `PAUSE_RESUME` 을 요구한다. 지금은 기동 시 거부한다 |
| `backgroundHeartbeatLimit` | `1h` | heartbeat 상한 — 백그라운드 명령이 샌드박스를 깨워 두는 상한 — [셸 §7](workspace-sandbox-shell.md) |
| `backgroundCommandTimeout` | `24h` | 백그라운드 상한 — 백그라운드 명령이 돌 수 있는 상한. 환경이 코어에 이 값을 돌려준다. `backgroundHeartbeatLimit` 과 독립이다 — [셸 §7](workspace-sandbox-shell.md) |
| `sharedAccess` | `none` | `/shared` 마운트 모드 `rw` · `ro` · `none`. **(계획 — 구현 단계 5)** `none` 밖의 값은 지금 기동 시 거부한다 — [파일 시스템 §8](workspace-sandbox-filesystem.md) |
| `seed` | 없음 | **(계획 — 구현 단계 5)** git 시드(원격 URL · ref · 읽기 전용 seed 자격 증명 이름). 지금은 기동 시 거부한다 — [수명 §3](workspace-sandbox-lifecycle.md) |
| `insecureAllow` | `[]` | 일부러 면제하는 격리 capability. `HARDENED_SECURITY_CONTEXT` · `RUNTIME_CLASS` · `NETWORK_ISOLATION` 만 면제할 수 있다. 로컬 개발 전용이고 기동 때 면제마다 경고를 남긴다 — [프로바이더 §6](workspace-sandbox-provider.md) |
| `runtimes` | `[]` | 이미지가 이미지 계약 밖에 갖춘 언어 런타임. 지금은 `python3` 만 쓸 수 있다. 서술자의 `runtimes()` 에 실리고 seed 가 확인한다 — [셸 엔진 §1](workspace-sandbox-shell-engine.md) |

프로파일이 프로바이더에 요구하는 capability 는 이 필드에서 유도한다. 유도 규칙은 [프로바이더 §6](workspace-sandbox-provider.md)
이 정본이다.

### 1.2 프로파일 해시

슬롯은 자기를 만든 프로파일의 내용 해시(`contentHash`)를 기록하고, 영구 실패한 슬롯은 프로파일 내용이 바뀌었을 때 정확히 한 번
다시 시도한다([도메인 §2.1](workspace-sandbox-domain.md), [수명 §2.2](workspace-sandbox-lifecycle.md)). 그래서 해시의 정규형에는
결과를 바꿀 수 있는 필드가 모두 들어간다. 두 가지를 정해 둔다.

- `backgroundCommandTimeout` 은 적용되는 값(설정하지 않았으면 기본 24시간)으로 들어간다
- `runtimes` 는 선언했을 때만 들어간다. 선언하지 않은 프로파일의 해시는 `runtimes` 의 영향을 받지 않으므로, 모듈을 올린 것만으로
  영구 실패 슬롯이 다시 시도되지 않는다

---

## 2. 배포 설정

배포 설정은 `SandboxSettings`(이 모듈)와 `OpenSandboxProviderConfig`(OpenSandbox 프로바이더 모듈)의 필드로 정의한다. 둘 다 빌더로
만든다. 키를 읽는 단계가 들어올 때 그 필드를 더한다 — 읽지 않는 키는 아무도 검사하지 않아 썩기 때문이다. 그래서 공유 볼륨 ·
worktree · 오케스트레이터 도구의 설정은 아직 필드가 없다.

### 2.1 `SandboxSettings`

| 필드 | 기본 | 뜻 |
|------|------|-----|
| `deployment` | **필수, 기본값 없음** | 한 저장소를 공유하는 노드 집합의 이름. 라벨과 janitor 조정의 범위를 정한다 — [프로바이더 §4](workspace-sandbox-provider.md) |
| `requirePrincipal` | `false` | USER/GROUP principal(또는 허용한 시스템 principal)만 샌드박스를 쓴다. 멀티 테넌트 배포에서 켠다 — [바인딩 §4](workspace-sandbox-binding.md) |
| `workspaceAccess` | `PRINCIPAL` | 배포 기본 접근 모드 `PRINCIPAL` · `TENANT` — [바인딩 §5](workspace-sandbox-binding.md) |
| `allowedSystemPrincipals` | `{}` | 샌드박스를 쓸 수 있는 SYSTEM/SERVICE principal id — [바인딩 §4](workspace-sandbox-binding.md) |
| `unattached` | `PER_SESSION` | unattached 규칙의 바닥. 정의와 세션 옵션은 이것을 좁히기만 한다 — [바인딩 §2](workspace-sandbox-binding.md) |
| `allowSharedWorkspaces` | `false` | `workspaceAccess: PRINCIPAL` 배포에서 관리형 워크스페이스를 `access: tenant` 로 만들 수 있는가 — [관리 §2](workspace-sandbox-management.md) |
| `maxManagedPerOwner` | `20` | owner 하나가 가질 수 있는 관리형 워크스페이스 수. 끄려면 `UNLIMITED` 를 명시한다 — [관리 §2](workspace-sandbox-management.md) |
| `defaultProfile` | **필수** | 바인딩이 프로파일을 요구하지 않을 때 쓰는 프로파일 |
| `profiles` | `[]` | 프로파일 목록(§1) |
| `provisionTimeout` | `5m` | 프로비저닝 하나의 상한. 넘으면 다른 노드가 이어받는다 — [수명 §2.4](workspace-sandbox-lifecycle.md) |
| `failureBackoff` · `maxFailureBackoff` | `1m` · `15m` | 일시 실패 뒤 첫 재시도 지연과 그 상한. 지연은 두 배씩 는다 — [수명 §2.2](workspace-sandbox-lifecycle.md) |
| `activityWriteInterval` | `30s` | 활동 기록 · 만료 연장 · heartbeat 의 주기 — [도메인 §6](workspace-sandbox-domain.md), [수명 §5](workspace-sandbox-lifecycle.md) |
| `casRetries` | `5` | 일반 저장소 쓰기의 CAS 재시도 상한. 활동 기록은 따로 정한 큰 상한을 쓴다 — [도메인 §4.3](workspace-sandbox-domain.md) |
| `closeWait` | `2m` | close 가 샌드박스가 사라지기를 기다리는 시간 — [수명 §7.1](workspace-sandbox-lifecycle.md) |
| `closeResumeAfter` | `5m` | 이만큼 CLOSING 에 멈춘 close 를 janitor 가 이어받는다 — [수명 §7.1](workspace-sandbox-lifecycle.md) |
| `closedRetention` | `7d` | CLOSED 레코드를 남기는 기간 — [수명 §7.2](workspace-sandbox-lifecycle.md) |
| `closeAfter` | `24h` | 살아 있는 슬롯 없이 이만큼 지난 워크스페이스를 idle close 한다 — [수명 §4](workspace-sandbox-lifecycle.md) |
| `execShellIdle` | `10m` | 쓰이지 않는 `exec:` 셸 디렉터리를 지우는 시간 — [셸 §10.3](workspace-sandbox-shell.md) |
| `shellLockWait` | `10s` | 셸 락 대기 — [셸 §3](workspace-sandbox-shell.md) |
| `janitorInterval` | `30s` | janitor 순회 주기 — [수명 §6](workspace-sandbox-lifecycle.md) |
| `orphanGrace` | `10m` | 레코드에 없는 샌드박스와 중복을 지우기 전 기다리는 나이 — [수명 §6.2](workspace-sandbox-lifecycle.md) |
| `lostConfirmAfter` | `90s` | RUNNING 슬롯의 샌드박스가 이만큼 계속 보이지 않으면 LOST 로 확인한다 — [수명 §6.2](workspace-sandbox-lifecycle.md) |
| `maxRunningPerTenant` | `10` | 기본 admission 의 테넌트별 상한. 끄려면 `UNLIMITED` 를 명시한다 — [보안 §8](workspace-sandbox-security.md) |
| `quota` | `maxSlots` 6 · `maxRunning` 3 · CPU·메모리 상한 없음 | 새 레코드마다 복사하는 워크스페이스 쿼터. 지금은 기록만 하고 집행은 **(계획 — 구현 단계 5)** — [보안 §8](workspace-sandbox-security.md) |
| `nodeId` | `{hostname}-{uuid}` | 이 노드의 id. JVM 마다 새로 뽑아, 재시작한 노드가 옛 명령을 자기 것으로 착각하지 않게 한다 — [셸 §3](workspace-sandbox-shell.md) |

`UNLIMITED` 는 명시해야만 상한이 꺼진다. 값을 빠뜨려서 전부 허용이 되는 경로는 없다(§3).

### 2.2 `OpenSandboxProviderConfig`

OpenSandbox 설정은 프로바이더 모듈이 소유한다. `aimon-sandbox` 는 OpenSandbox 를 모르고 `ProviderCapabilities` 만 보기
때문이다([개요 §5.1](workspace-sandbox.md)). capability 는 이 선언에서 유도하고 기동 때 탐침하지 않는다. 각 필드의 의미, 서버
설정과의 짝, capability 유도는 [OpenSandbox §5](workspace-sandbox-opensandbox.md) 가 정본이다. 여기는 필수 여부와 기본값만 적는다.

| 필드 | 기본 |
|------|------|
| `endpoint` | **필수** — 절대 http(s) URI. `/v1` 이 없으면 붙인다 |
| `apiKey` | **필수** — 요청마다 읽는 `Supplier` |
| `runtime` | **필수, 기본값 없음** — `DOCKER` · `KUBERNETES`. 어떤 선언이 합법인지를 정한다 |
| `maxExpiry` | **필수** — 서버의 `max_sandbox_timeout_seconds` 와 같은 값. 60초 이상 |
| `egressEnforcement` | `NONE` — `NONE` · `DNS` · `DNS_NFT` |
| `networkIsolation` · `hardenedSecurityContext` | `UNDECLARED` — 운영자가 준비했을 때만 `DECLARED`(§5) |
| `runtimeClass` | 없음 — 서버의 런타임 클래스 이름과 종류(`GVISOR` · `KATA` · `OTHER`) |
| `credentials` | `{}` — 자격 증명 바인딩 이름 → `CredentialDefinition`(범위 · 주입 방식 · 비밀 `Supplier`) |
| `controlPlaneProbes` | `endpoint` 의 host:port, Kubernetes 면 `kubernetes.default.svc:443` 을 더함 |
| `volumeReclaimer` | 없음 — 없으면 `SHARED_VOLUME` 을 광고하지 않는다. 내장 구현은 **(계획 — 구현 단계 5)** |
| `useServerProxy` | `false` |
| `requestTimeout` · `createTimeout` | `30s` · `90s` |
| `retry` | 최대 3회, 첫 지연 `500ms` 에서 두 배씩 — 멱등 호출만 |
| `maxConcurrentCalls` | `32` |
| `entrypoint` | `tail -f /dev/null` |
| `defaultResources` | cpu `1` · memory `1Gi` — 프로파일이 자원을 주지 않을 때 보낸다 |

### 2.3 속성 키 (제안)

이 저장소에는 Spring 속성 바인딩이 없다. 아래 YAML 키는 Spring 스타터가 생기면 쓸 **제안된 이름**이고, 각 빌더 메서드가 그 키에
대응한다(`provision-timeout` → `provisionTimeout(...)`). 지금 Spring 에서 쓰려면 호스트가 `@ConfigurationProperties` 로 받아 빌더에
옮긴다([aimon-core 와 연동하기](../guide/aimon-core-integration.md)). 스타터는 §6 의 열린 질문이다.

```yaml
aimon:
  sandbox:
    deployment: acme-prod            # SandboxSettings.deployment
    require-principal: true
    workspace-access: principal
    max-managed-per-owner: 20
    admission:
      max-running-per-tenant: 10
    default-profile: standard
    profiles:
      standard:
        image: ghcr.io/kangwoo/aimon-sandbox-runtime:1
        runtime-class: kata
        egress: [github.com, repo.maven.apache.org]
        terminate-after: 2h
    opensandbox:                     # OpenSandboxProviderConfig
      endpoint: ${OPEN_SANDBOX_ENDPOINT}
      runtime: kubernetes
      max-expiry: 24h
      egress-enforcement: dns+nft
```

---

## 3. 기동 검사

`WorkspaceSandbox.builder().build()` 는 설정 전체를 한 번에 검사한다. 위반을 모두 모아 `SandboxConfigurationException` 하나로
던지고, 하나라도 있으면 아무것도 시작하지 않는다(어셈블리가 소유한 프로바이더도 닫는다). 한 번 실패한 기동이 문제를 전부 보여
주게 하기 위해서다. `OpenSandboxProviderConfig` 의 빌더도 같은 방식으로 자기 위반을 모아 던진다.

### 3.1 `SandboxSettings` 와 프로파일

배포 설정:

- `deployment` 가 있고 라벨 값 규칙 `[a-z0-9]([-a-z0-9_.]{0,61}[a-z0-9])?` 를 만족한다. 기본값을 두지 않는다 — 다른 배포의
  샌드박스를 조정 대상으로 보지 않게 하는 범위이기 때문이다([프로바이더 §4](workspace-sandbox-provider.md))
- 시간 값(`provisionTimeout` · `failureBackoff` · `maxFailureBackoff` · `activityWriteInterval` · `closeWait` ·
  `closeResumeAfter` · `closedRetention` · `execShellIdle` · `shellLockWait` · `janitorInterval` · `orphanGrace` ·
  `lostConfirmAfter` · `closeAfter`)은 모두 양수다. `casRetries` 는 음수가 아니다
- `orphanGrace > provisionTimeout` — 레코드 쓰기가 늦은 느린 프로비저닝의 샌드박스를 고아로 지우지 않게. 유예는 샌드박스가 생긴
  때부터 재고, 프로비저닝은 `provisionTimeout` 안에 끝나거나 다른 노드에 넘어간다([수명 §6.2](workspace-sandbox-lifecycle.md))
- `defaultProfile` 이 있고 정의된 프로파일이다. 같은 이름의 프로파일이 둘 있으면 거부한다
- `requirePrincipal` 이면 `SandboxTenantResolver` 가 있고, 세션 소유자의 출처 — `sessionRecords(...)`(코어가 기록한 소유자),
  `SessionOwnerLookup`, 또는 둘 다 — 가 있다([바인딩 §4](workspace-sandbox-binding.md)). 세션 옵션을 쓸 때 필요한 소유자
  출처는 기동이 아니라 옵션을 쓸 때 검사한다 — 옵션을 쓰지 않는 배포에는 필요 없기 때문이다
- `maxRunningPerTenant` 와 `maxManagedPerOwner` 는 1 이상이거나 명시한 `UNLIMITED` 다

프로파일마다:

- `terminateAfter` 가 있고 양수이며, 프로바이더의 `maxExpiry` 를 넘지 않는다. 프로바이더 만료를 걸 값이 없으면 최후 방어선이
  사라지기 때문이다([수명 §4](workspace-sandbox-lifecycle.md))
- `terminateAfter ≥ 3 × activityWriteInterval` — heartbeat 기록 하나가 늦거나 실패해도 도는 명령의 샌드박스가 두 기록 사이에
  만료되지 않게
- `backgroundHeartbeatLimit` 은 양수다. `backgroundCommandTimeout` 은 설정했다면 양수다. 상한은 없고, `terminateAfter` 와의 관계도
  강제하지 않으며, `backgroundHeartbeatLimit` 보다 커도 합법이다 — 그 경우의 의미는 [셸 §7](workspace-sandbox-shell.md)
- 자격 증명 바인딩은 모두 프로바이더에 정의되어 있고, 서로 겹치지 않으며, 모든 host 를 프로파일의 `egress` 가 명시적으로 허용한다
  (`egress` 가 없으면 거부). 규칙과 이유는 [보안 §5](workspace-sandbox-security.md)
- `runtimeClass` 는 프로바이더가 선언한 서버의 클래스와 같다. OpenSandbox 서버는 모든 샌드박스에 클래스 하나를 쓰고 요청마다 받지
  않는다. 예외로, 클래스를 선언하지 않은 프로바이더에 대해서는 프로파일이 `insecureAllow` 에 `RUNTIME_CLASS` 를 두면 통과한다 —
  운영과 공유하는 프로파일을 로컬에서 그대로 쓰기 위해서다
- `insecureAllow` 는 `HARDENED_SECURITY_CONTEXT` · `RUNTIME_CLASS` · `NETWORK_ISOLATION` 만 면제한다
- `runtimes` 에는 seed 가 확인할 수 있는 이름(`python3`)만 쓴다. 확인할 방법이 없는 이름은 무효값이고, 무효값은 기동 검사가
  거부한다
- 프로파일이 요구하는 capability 를 프로바이더가 모두 광고한다. 유도 규칙과 이유는 [프로바이더 §6](workspace-sandbox-provider.md)

아직 구현되지 않은 기능을 쓰는 프로파일은 조용히 무시하지 않고 **거부한다** — `pauseAfter`(구현 단계 7), `sharedAccess` 가 `none`
이 아닌 것(구현 단계 5), `seed`(구현 단계 5). 전체 구현 상태는 [개요 §8](workspace-sandbox.md).

검사를 통과해도 경고는 남긴다 — `insecureAllow` 가 있는 프로파일마다, 그리고 `requirePrincipal` 인데 `maxRunningPerTenant` 가
`UNLIMITED` 일 때.

**(계획 — 구현 단계 5 · 7)** 공유 볼륨이 들어오면 `retainFor ≤ closedRetention` 을, 오케스트레이터 도구가 들어오면 그 허용
프로파일이 모두 정의되어 있는지를(구현 단계 5), pause 가 들어오면 `pauseAfter < terminateAfter` 를(구현 단계 7) 검사한다.

### 3.2 `OpenSandboxProviderConfig`

- `endpoint` 가 절대 http(s) URI 이고 `apiKey` 가 있다
- `runtime` 이 있다. 기본값을 두지 않는다 — 어떤 선언이 합법인지를 정하는 값이기 때문이다
- `maxExpiry` 가 있고 60초 이상이다. 서버 API 가 `max_sandbox_timeout_seconds` 를 드러내지 않으므로 운영자가 적어야 한다
- Docker 런타임에는 `networkIsolation` · `hardenedSecurityContext` 선언과 `runtimeClass` 를 둘 수 없다. 거기서는 execd 가 다른
  모든 샌드박스에 열려 있고, 샌드박스가 root 와 쓰기 가능한 루트 파일 시스템으로 돌기 때문이다
- gVisor 런타임 클래스와 `egressEnforcement: DNS_NFT` 를 함께 쓸 수 없다. 서버가 gVisor 아래의 모든 `networkPolicy` 를 거부한다
- `credentials` 는 `DNS_NFT` 를 요구한다. 서버가 그 밖에서는 자격 증명 프록시를 거부한다. 바인딩 이름은 비어 있지 않다
- `networkIsolation` 을 선언했으면 `controlPlaneProbes` 가 비어 있지 않다. 비어 있으면 seed 의 탐침이 아무것도 확인하지 않는다
- `requestTimeout` · `createTimeout` · 재시도 지연은 양수, 재시도 횟수와 `maxConcurrentCalls` 는 1 이상, `entrypoint` 는 비어 있지
  않고, 기본 자원에는 cpu 와 memory 가 모두 있다

경고만 남기는 것 — `egressEnforcement: DNS`(`EGRESS_POLICY` 를 광고하지 않는다. 이름만 거르고 주소를 막지 못하기 때문이다),
Kubernetes 에서 `networkIsolation` 을 선언하고 `controlPlaneProbes` 를 유도값으로 둔 경우(§5.1).

---

## 4. 이미지 계약

샌드박스 이미지가 갖춰야 하는 것:

- `bash`
- **GNU** coreutils — `sha256sum` 과 `base64` 가 `/usr/bin` 에 있어야 한다. 훅의 실행 디렉터리를 풀고 확인할 때 두 도구를 절대
  경로로 부르기 때문이다([셸 §9](workspace-sandbox-shell.md)). BusyBox 의 `sha256sum` 은 `--strict` 를 몰라 status 1 로 끝나고,
  래퍼는 status 1 을 변조로 읽으므로 모든 훅이 `HOOK_FILES_TAMPERED` 가 된다
- util-linux `flock` — 셸 락과 seed 락
- `git`
- `rg`(ripgrep) — `Grep` 이 샌드박스 안에서 `rg --json` 한 번으로 돈다([개요 §6.1](workspace-sandbox.md))
- uid 1000 `sandbox` 사용자와 그 사용자 소유의 쓰기 가능한 `/workspace`

execd 는 이미지에 넣지 않는다. OpenSandbox 가 주입한다 — Docker 에서는 컨테이너에 복사하고 Kubernetes 에서는 init 컨테이너가
넣는다.

**계약은 첫 프로비저닝의 seed 가 확인한다.** `/workspace` 가 없거나 쓸 수 없거나 그 안의 seed 락을 열 수 없으면(step
`workspace`), 도구가 없거나 `/usr/bin` 에 없거나 `sha256sum -c --strict -` 를 받지 않으면(step `image-contract`) 슬롯이 영구
실패한다. 늦게 발견하면 `Grep` 같은 도구가 원인 모를 에러를 내기 때문이다. 확인 순서와 실패 처리는
[수명 §3](workspace-sandbox-lifecycle.md) 이 정본이다.

**선택: `python3`.** 프로파일이 `runtimes: [python3]` 를 선언한 경우에만 요구한다. 요구 내용(버전, `-I` 로 import 되어야 하는
모듈)과 seed 확인은 [셸 엔진 §2](workspace-sandbox-shell-engine.md) 에 있다. k8s 테스트 계층의 기본 이미지
(`ghcr.io/kangwoo/aimon-sandbox-runtime:1`)와 그 프로파일은 `python3` 을 선언하지 않는다.

**실행 uid 는 execd 의 uid 다.** OpenSandbox 의 files API 와 명령은 execd 프로세스의 사용자로 돈다. 이 모듈은 `/command` 의 uid
인자를 쓰지 않는다. files API 가 execd 의 uid 로 파일을 쓰므로, 명령을 다른 uid 로 돌리면 파일 도구가 쓴 파일에 명령이 쓰지
못하기 때문이다(확인 필요: OpenSandbox 1.2.0 의 files API 가 uid 를 받는지 — 이 이유는 execd v1.1.0 에서 확인했다. 서버 쪽 사실은
[OpenSandbox §3.2](workspace-sandbox-opensandbox.md)). 그래서 "uid 1000
으로 돈다" 는 이미지와 SecurityContext(`runAsUser: 1000`, `runAsNonRoot`)가 함께 지켜야 하고, seed 의 `id -u` 검사가 그 결과를
확인한다. root 로 도는 샌드박스는 `insecureAllow` 에 `HARDENED_SECURITY_CONTEXT` 가 없으면 영구 실패한다.

**execd 는 `TMPDIR` 없이 띄운다.** execd 는 명령 출력을 `$TMPDIR/opensandbox-execd/` 에 스풀하고, 프로바이더는 stream 실행이 끝나면
그 스풀을 `/tmp/opensandbox-execd/` 로 보고 지운다 — files API 는 설정되지 않은 변수를 펼칠 수 없다. 프로바이더가 스풀을 지우는
이유는 [셸 엔진 §6](workspace-sandbox-shell-engine.md) 에 있다. seed 는 execd 의 `TMPDIR` 을 확인하지 않는다.

---

## 5. 운영자 준비물

설계가 기대는 운영 계약을 적는다. 설치 절차와 매니페스트 원문은
[OpenSandbox 를 Kubernetes 에 설치하기](../guide/opensandbox-kubernetes.md) 가 정본이다.

### 5.1 Kubernetes 런타임

- **샌드박스 컨테이너 단위로 강화한 BatchSandbox 템플릿** — uid 1000, `allowPrivilegeEscalation: false`, capability 제거. 파드
  단위로 강화하면 egress 의 init 컨테이너를 깨뜨린다. 준비하면 `hardenedSecurityContext: DECLARED`
  ([가이드 §5](../guide/opensandbox-kubernetes.md))
- **샌드박스 네임스페이스의 NetworkPolicy** — OpenSandbox 는 NetworkPolicy 를 만들지 않는다. east-west(다른 샌드박스의 execd)와
  제어면(OpenSandbox 서버 API, K8s API, kubelet, 클라우드 메타데이터, 클러스터 내부 서비스)을 막는 운영자 정책이 필요하고, 그것으로
  충분하다. NetworkPolicy 를 집행하는 CNI 가 있어야 한다. 준비하면 `networkIsolation: DECLARED` 와 함께 `controlPlaneProbes` 에
  서버의 **클러스터 안 주소**(샌드박스 쪽에서 본 이름)를 적는다. 유도값은 AIMON 이 서버에 닿는 주소라서, 포트 포워드나 NAT 를
  거치면 샌드박스 안에서 풀리지 않는 이름을 탐침해 탐침이 늘 통과한다. seed 는 나가는 연결만 확인하므로 들어오는 쪽(ingress) 규칙은
  운영자가 책임진다([가이드 §6](../guide/opensandbox-kubernetes.md), [수명 §3](workspace-sandbox-lifecycle.md))
- **OpenSandbox 서버의 egress 모드 `dns+nft`** — `EGRESS_POLICY` 는 이 모드에서만 광고된다([보안 §4](workspace-sandbox-security.md))
- **`execd_run_as_init`** — 풀 모드에서 task-executor 가 파드의 PID 1 이면 `execd_run_as_init = true` 가 필요하다. 그 배치에는 고아
  프로세스를 거둘 프로세스가 없기 때문이다. 템플릿 모드는 샌드박스의 PID 1 이 거둔다. 고아가 남지 않아야 하는 이유는
  [셸 엔진 §7](workspace-sandbox-shell-engine.md)
- **`maxExpiry` = 서버의 `max_sandbox_timeout_seconds`** — 더 크면 서버가 `create` 를 400 으로 거부한다
- **서버 엔드포인트는 AIMON 노드만 닿는 네트워크에 둔다** — 이유는 [보안 §7](workspace-sandbox-security.md)
- 런타임 클래스(gVisor · Kata)는 선택이다. 쓰면 서버 설정과 `runtimeClass` 선언을 짝지운다([가이드 §7](../guide/opensandbox-kubernetes.md))
- **(계획 — 구현 단계 5)** RWX StorageClass 와, `VolumeReclaimer` 가 PVC 를 지울 수 있는 서비스 계정. 공유 볼륨을 쓰기 전에는 필요
  없다

### 5.2 Docker 런타임

- **로컬 개발 전용이다.** Docker 런타임에서는 east-west 와 서버 엔드포인트의 경계를 세울 수 없고 컨테이너 강화를 선언할 수 없다
  (§3.2). 그래서 프로파일이 `insecureAllow: [NETWORK_ISOLATION, HARDENED_SECURITY_CONTEXT]` 로 면제해야 뜬다
- **`dns+nft` 를 쓰려면 서버가 `[docker] network_mode = "bridge"` 여야 한다.** 기본값 `host` 와 사용자 정의 네트워크에서는 서버가
  `networkPolicy` 를 400 으로 거부한다. 프로바이더는 서버의 이 설정을 볼 수 없으므로, 어긋나면 첫 `create` 가 영구 실패(`create`)로
  드러난다

### 5.3 노드와 저장소

- 기본 `InMemory` 저장소는 한 노드 전용이다. 노드가 여럿이면 영속 저장소가 필요하다 — [도메인 §4.4](workspace-sandbox-domain.md)
  (**계획 — 구현 단계 6**)
- docker · k8s 테스트 계층을 돌리는 방법은 [테스트 §3](workspace-sandbox-testing.md) 과 OpenSandbox 프로바이더 모듈 README 에 있다

---

## 6. 열린 질문

- **Spring 스타터와 속성 바인딩.** 이 저장소에는 `aimon.sandbox.*` 속성 바인딩이 없다(§2.3). `OpenSandboxProviderConfig` 는
  `apiKey` 와 자격 증명 비밀을 `Supplier` 로, `volumeReclaimer` 를 인스턴스로 받는다. 스타터가 생기면 속성을 빌더로 옮기는 방법 —
  비밀의 출처, 빈 이름 — 을 그때 정한다
- **워크스페이스 단위 seed 매개변수** *(구현 단계 6 의 영속 저장소 스키마를 굳히기 전에 닫는다)* — `seed`(원격 · ref · 자격 증명
  이름)는 프로파일에만 있다. 그래서 저장소나 브랜치가 티켓마다 다르면 프로파일을 저장소 수만큼 만들어야 하고, 테넌트마다 다른
  자격 증명을 쓸 방법이 없다. 병렬 해법 탐색(구현 단계 8)도 모든 실험 슬롯이 같은 기준 커밋에서 출발해야 하는데 `SandboxStart`
  에는 ref 인자가 없다. 방법은 둘이다 — 워크스페이스 레코드에 생성 시점의 seed 매개변수를 두고 바인딩 정책이 채우거나, 프로파일의
  `seed` 를 템플릿으로 두고 워크스페이스가 값만 채운다. 어느 쪽이든 모델이 원격이나 자격 증명을 고르는 경로는 열지 않는다. 레코드
  스키마를 바꾸는 결정이므로 영속 저장소보다 먼저 닫는다([도메인 §4.4](workspace-sandbox-domain.md))
