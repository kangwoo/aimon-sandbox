# AIMON Sandbox OpenSandbox Provider

워크스페이스 샌드박스의 운영용 `SandboxProvider` 다. [OpenSandbox](https://github.com/alibaba/OpenSandbox) 의 REST API 를 JDK
`HttpClient` 와 Jackson 으로 직접 부른다 — SDK 는 쓰지 않는다. 설계는
[`docs/design/workspace-sandbox.md`](../../docs/design/workspace-sandbox.md) §6 · §13 에, 이 모듈을 만든 방법과 거기서 벗어난 점은
[`docs/design/workspace-sandbox-step4.md`](../../docs/design/workspace-sandbox-step4.md) 에, 서버의 실제 동작은
[`docs/design/opensandbox-spike.md`](../../docs/design/opensandbox-spike.md) 에 있다.

```java
OpenSandboxProvider provider = new OpenSandboxProvider(OpenSandboxProviderConfig.builder()
        .endpoint(URI.create("http://opensandbox-server.opensandbox-system.svc"))
        .apiKey(() -> System.getenv("OPEN_SANDBOX_API_KEY"))
        .runtime(OpenSandboxProviderConfig.Runtime.KUBERNETES)
        .maxExpiry(Duration.ofHours(24))                 // = the server's max_sandbox_timeout_seconds
        .egressEnforcement(OpenSandboxProviderConfig.EgressEnforcement.DNS_NFT)
        .networkIsolation(OpenSandboxProviderConfig.Declaration.DECLARED)
        .controlPlaneProbes(List.of(HostPort.parse("opensandbox-server.opensandbox-system.svc:80"),
                HostPort.parse("kubernetes.default.svc:443")))
        .hardenedSecurityContext(OpenSandboxProviderConfig.Declaration.DECLARED)
        .build());
WorkspaceSandbox sandbox = WorkspaceSandbox.builder().settings(settings).provider(provider).ownProvider(true).build();
```

## 설정 (`aimon.sandbox.opensandbox.*`, WS §13.2)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `endpoint` | 필수 | 수명 서버. `/v1` 이 없으면 붙인다 |
| `api-key` | 필수 | `OPEN-SANDBOX-API-KEY`. 요청마다 공급자에서 읽고 남기지 않는다 |
| `runtime` | 필수 | `docker` · `kubernetes`. 어떤 선언이 합법인지 정한다 |
| `max-expiry` | 필수, 60s 이상 | 서버의 `max_sandbox_timeout_seconds` 와 **같은 값**. API 로 읽을 수 없다 |
| `egress-enforcement` | `none` | `none` · `dns` · `dns+nft`. `dns+nft` 만 `EGRESS_POLICY` 를 광고한다 |
| `network-isolation` | `undeclared` | 운영자가 east-west · 제어면 차단을 걸었다는 선언. Docker 에서는 거부 |
| `control-plane-probes` | 엔드포인트(+ K8s 면 `kubernetes.default.svc:443`) | seed 가 연결에 실패해야 하는 주소 |
| `hardened-security-context` | `undeclared` | 샌드박스 컨테이너 단위로 강화한 템플릿의 선언. Docker 에서는 거부 |
| `runtime-class` | 없음 | `{name, kind: gvisor \| kata \| other}`. Docker 에서는 거부. gVisor 와 `dns+nft` 는 함께 쓸 수 없다 |
| `credentials` | 없음 | 이름 → `CredentialDefinition`(범위 · 주입 방식 · 비밀 공급자). `dns+nft` 필요 |
| `volume-reclaimer` | 없음 | `VolumeReclaimer` 인스턴스. 주면 `SHARED_VOLUME` 을 광고한다. 내장 구현은 5단계 |
| `use-server-proxy` | `false` | execd · egress 사이드카를 서버 프록시로 부른다(클러스터 밖의 K8s, docker 테스트 계층) |
| `request-timeout` · `create-timeout` | 30s · 90s | 호출 하나 · 생성과 `Running` 대기 |
| `retry` | 3회, 500ms 부터 두 배 | 멱등 호출(상태 · 목록 · 삭제 · 연장 · 엔드포인트 · 파일 조회)만 |
| `max-concurrent-calls` | 32 | 요청/응답 호출의 동시 상한. 명령 스트림은 세지 않는다 |
| `entrypoint` | `[tail, -f, /dev/null]` | 이미지의 CMD 와 상관없이 샌드박스를 살려 둔다 |
| `default-resources` | `cpu 1, memory 1Gi` | 프로파일이 주지 않을 때(K8s 는 없으면 422) |

`OpenSandboxProviderConfig.build()` 는 위반을 모두 모아 `SandboxConfigurationException` 하나로 던진다.

## capability 광고

| capability | 광고 조건 |
|---|---|
| `EXEC` · `FILES` · `EXPIRY` | 항상 |
| `EGRESS_POLICY` | `egress-enforcement: dns+nft` |
| `CREDENTIAL_INJECTION` | `dns+nft` 이고 `credentials` 가 있을 때 |
| `NETWORK_ISOLATION` | `network-isolation: declared` (kubernetes) |
| `HARDENED_SECURITY_CONTEXT` | `hardened-security-context: declared` (kubernetes) |
| `RUNTIME_CLASS` | `runtime-class` 가 있을 때 (kubernetes) |
| `SHARED_VOLUME` | `volume-reclaimer` 가 있을 때 |
| `PAUSE_RESUME` · `SNAPSHOT` · `FORK` | 광고하지 않는다(7 · 8단계) |

선언은 확인 없이 믿지 않는다. seed 단계의 `verify` 가 `enforcementMode == dns+nft` 와 vault 의 바인딩을 대조하고, seed 스크립트가
`control-plane-probes` 로 연결을 시도한다(WS §11.3). 틀린 선언은 슬롯을 FAILED(영구)로 만든다.

## 운영 계약 (WS §13.3)

- **서버 엔드포인트는 AIMON 노드만 닿는 네트워크에 둔다.** 단일 테넌트 서버의 프록시 경로와 execd 는 인증하지 않는다 —
  샌드박스 id 를 알면 root 로 명령을 실행한다. 이 모듈은 샌드박스 id 를 INFO 로그에 남기지 않는다.
- **Docker 런타임은 로컬 개발 전용이다.** 프로파일에 `insecure-allow: [NETWORK_ISOLATION, HARDENED_SECURITY_CONTEXT]` 가 필요하다.
  `egress-enforcement: dns+nft` 를 쓰려면 서버가 `[docker] network_mode = "bridge"` 여야 한다(아니면 첫 `create` 가 400 으로
  FAILED(영구)).
- **K8s 에서 `network-isolation: declared` 이면 `control-plane-probes` 에 서버의 클러스터 안 주소를 적는다.** 기본값은 AIMON 이
  보는 주소라, 샌드박스가 풀 수 없는 이름이면 탐침이 늘 통과한다(빌더가 WARN 을 남긴다).
- **`InMemory` 저장소는 한 노드 전용이다.** 여러 노드가 각자 `InMemory` 로 같은 `deployment` 를 쓰면 janitor 의 조정이 서로의
  샌드박스를 고아로 지운다.
- **자격 증명의 HTTPS 주입은 샌드박스가 egress 사이드카의 CA 를 신뢰해야 동작한다.** execd 가 CA 번들을 `SSL_CERT_FILE` 등으로
  내보내고, 셸 래퍼가 git 에 `GIT_SSL_CAINFO` 로 넘긴다. 이 변수들을 읽지 않는 도구는 검증에 실패한다(WS §20). 이미지는
  `ca-certificates` 를 갖추는 것이 좋다.
- 출력은 줄로 정규화된다(`\r` 은 줄바꿈, 마지막 줄바꿈은 늘 있음, 잘못된 UTF-8 은 U+FFFD — WS §6.1). 프로파일의 `disk` · `pids` 는
  요청 필드가 없어 서버 설정(Docker `pids_limit`)으로만 걸린다.

## 테스트 계층

```bash
./gradlew :aimon-sandbox-opensandbox:test             # fake 서버(JDK HttpServer) — build 에 들어간다
./gradlew :aimon-sandbox-opensandbox:integrationTest  # @Tag("docker") — 실서버, Docker 필요
./gradlew :aimon-sandbox-opensandbox:k8sTest          # @Tag("k8s") — 미리 준비한 클러스터, 수동 · 릴리스 전
```

**docker 계층**은 실행마다 `opensandbox/server`(digest 고정) 컨테이너를 러너의 Docker 소켓으로 띄우고(`OpenSandboxTestServer`),
`src/test/docker/Dockerfile` 로 샌드박스 이미지를 만든 뒤 계약 스위트(`OpenSandboxProviderContractTest`), `WorkspaceSandbox`
종단 테스트(`OpenSandboxWorkspaceIT`), egress 테스트(`OpenSandboxEgressIT`, 인터넷이 없으면 건너뜀)를 돈다. 서버 컨테이너는
업스트림 compose 의 설정을 따른다 — `host_ip = "host.docker.internal"` + `host-gateway`, `resolve_internal = false`, `bridge`.
샌드박스의 execd 포트는 실행 동안 호스트 인터페이스에 열린다(개발 · CI 머신에서만 돌린다). 모든 샌드박스는 deployment
`ci-{uuid}` 를 달고 실행이 끝나면 지운다. 이미 떠 있는 서버를 쓰려면 `OPENSANDBOX_TEST_ENDPOINT` · `OPENSANDBOX_TEST_API_KEY`,
이미지를 바꾸려면 `OPENSANDBOX_TEST_{SERVER,EXECD,EGRESS,SANDBOX}_IMAGE` 를 준다. macOS(Docker Desktop)와 CI 의 Linux 러너
(`.github/workflows/build.yml` 의 `integration` 잡)에서 돈다.

**k8s 계층**은 클러스터를 만들지 않는다. `scripts/k8s-tier.sh up` 이 아래의 구성을 kind 위에 만들고, `scripts/k8s-tier.sh test`
가 서버를 포트포워드해 모든 검사를 켠 채 돌리며, `scripts/k8s-tier.sh down` 이 지운다. 손으로 할 때는 스파이크의 절차([`C-k8s.md`](../../spike/opensandbox/reports/C-k8s.md) §1)와
[`spike/opensandbox/k8s/deploy/`](../../spike/opensandbox/k8s/deploy/) 의 값 — kind, OpenSandbox 차트, 샌드박스 컨테이너 단위로
강화한 템플릿, `netpol-sandbox-isolation.yaml` — 으로 준비한 뒤 `kubectl port-forward` 로 서버를 열고 다음을 준다:
`OPENSANDBOX_K8S_ENDPOINT` · `OPENSANDBOX_K8S_API_KEY` · `OPENSANDBOX_K8S_IMAGE`(클러스터가 받을 수 있는 계약 이미지) ·
`OPENSANDBOX_K8S_CONTROL_PLANE`(샌드박스가 보는 서버 주소, 기본 `opensandbox-server.opensandbox-system.svc:80`) ·
`OPENSANDBOX_K8S_RUNTIME_CLASS`(있으면) · `OPENSANDBOX_K8S_INTERNET=1`(자격 증명 범위 검사). 없으면 모든 테스트를 건너뛴다.
