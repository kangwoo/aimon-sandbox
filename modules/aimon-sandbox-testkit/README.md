# AIMON Sandbox Testkit

워크스페이스 샌드박스의 구현을 검증하는 도구 모음이다. 설계는
[`docs/design/workspace-sandbox.md`](../../docs/design/workspace-sandbox.md) §16 에 있다.

- **`SandboxProviderContract`** — 모든 `SandboxProvider` 가 통과해야 하는 계약 스위트(JUnit 5 추상 클래스).
  `createProvider()` 만 구현하면 된다. 이 저장소 밖의 프로바이더도 이것을 통과해야 한다. 출력은 줄로 정규화될 수 있으므로
  (WS §6.1) 줄바꿈으로 끝나는 출력으로 비교한다. 공유 서버에 대고 돌 때는 `deployment()` 를 실행마다 다른 값으로 바꾼다
  (`aimon-sandbox-opensandbox` 의 docker 계층은 `ci-{uuid}`).
- **`SandboxWorkspaceStoreContract`** — 모든 `SandboxWorkspaceStore` 의 계약 스위트. `InMemory` 가 통과하고, JDBC 저장소
  (6단계)도 같은 스위트를 통과해야 한다.
- **`LocalProcessSandboxProvider`** — 임시 디렉터리와 로컬 프로세스로 만든 프로바이더. **격리가 없으므로 테스트
  전용이다. 운영에 쓰지 않는다.** `/workspace` 경로를 글자로 바꿔 흉내 내며, 호스트에 `flock`·`rg` 가 없으면 자기
  `PATH` 에만 대역을 둔다(`rg` 대역은 검색하지 않고 실패한다 — `hostHasRipgrep()` 으로 건너뛸 수 있다). 실제 exec 서버처럼
  없는 작업 디렉터리는 거부하고, destroy 한 샌드박스는 이미 연 연결로도 not found 이며, destroy·close 는 명령이 남긴
  백그라운드 프로세스까지 끝낸다.
- **`FaultInjectingSandboxProvider`** — 아무 프로바이더나 감싸 호출마다 지연 · 실패 · 타임아웃 · not found · 응답 유실 ·
  노드 종료를 주입하고 호출 수를 센다(4단계의 `verify` 도 `Operation.VERIFY` 로 감싼다). 한 번짜리 규칙(`injectOnce` · `injectAt`)이 상시 규칙(`inject`)보다 먼저이고,
  `injectAt` 은 마지막 `resetCounts()` 부터 센다.
- **`ManualClock` · `ManualScheduler`** — idle · heartbeat · backoff 를 실제 시간을 기다리지 않고 돌린다.
- **`SandboxTestProfiles`** — 로컬 프로바이더에 맞는 프로파일과 설정.

계약 스위트가 JUnit 과 AssertJ 로 쓰였으므로 이 모듈은 둘을 `api` 로 싣는다 — 스위트를 상속하는 쪽이 스위트가 쓰인 버전을
받는다.
