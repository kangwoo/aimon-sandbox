# AIMON Sandbox

에이전트가 이미 가진 `Bash` · `Read` · `Write` · `Edit` · `Grep` 을 격리된 OpenSandbox 환경에서 돌게 하는
워크스페이스 샌드박스 모듈이다. 설계는 [`docs/design/workspace-sandbox.md`](../../docs/design/workspace-sandbox.md)
에 있다.

## 현재 상태

**3단계(도메인과 로컬 경로)의 코드가 있다 — 그러나 아직 운영에 쓸 수 없다.** 운영용 프로바이더
(`aimon-sandbox-opensandbox`)는 4단계에서 들어오고, 두 단계는 한 릴리스로 낸다(설계 §18-4). 지금 이 모듈과 함께 돌 수 있는
프로바이더는 테스트 전용인 `aimon-sandbox-testkit` 의 `LocalProcessSandboxProvider` 뿐이다.

들어 있는 것:

- `workspace/` — 워크스페이스 레코드와 CAS 저장소(`InMemorySandboxWorkspaceStore`, 단일 노드 전용), 게으른 프로비저닝 ·
  owner 검사 · close/reopen 을 맡는 `SandboxWorkspaceManager`, 활동 heartbeat, idle 을 집행하는 `SandboxJanitor`, 테넌트별
  기본 admission, 수명 이벤트
- `binding/` — 기본 바인딩 정책과 주체 검사(`CallerResolver`), 테넌트 해석과 세션 소유자 조회의 SPI
- `provider/` — `SandboxProvider` SPI 와 라벨(`SandboxLabels`), 노드 로컬 연결 캐시
- `profile/` — `SandboxProfile` 과 레지스트리
- `environment/` — 코어 `ExecutionEnvironmentProvider` 구현: 선언한 서술자, 경로 규칙을 건 파일 시스템, 샌드박스 안 상태
  파일로 cwd·export 를 잇는 셸, 샌드박스 안에서 검증하는 스테이징, `rg --json` 검색
- `WorkspaceSandbox` — 위를 조립하고 기동 시 설정을 검사하는 어셈블리

아직 없는 것: 운영 프로바이더와 조정(4단계), `primary` 밖의 슬롯 · 공유 볼륨 · seed · 오케스트레이터 도구(5단계), 영속
저장소(6단계), pause/resume · worktree 격리(7단계). 그런 설정은 조용히 무시하지 않고 기동 시 거부한다. 구현 설계와 설계에서
벗어난 점은 [`workspace-sandbox-step3.md`](../../docs/design/workspace-sandbox-step3.md) 에 있다.

aimon-core 는 Maven Central 에 릴리스된 `0.3.1` 을 쓴다(그 전까지는 `mavenLocal()` 의 `0.3.1-SNAPSHOT` 이었다).

옛 구현이 필요하면 `at.aimon.core:aimon-sandbox{,-docker,-kubernetes}:0.2.4` 가 Maven Central 에 그대로 있다.
