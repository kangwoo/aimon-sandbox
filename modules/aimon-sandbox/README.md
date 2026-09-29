# AIMON Sandbox

에이전트가 이미 가진 `Bash` · `Read` · `Write` · `Edit` · `Grep` 을 격리된 OpenSandbox 환경에서 돌게 하는
워크스페이스 샌드박스 모듈이다. 설계는 [`docs/design/workspace-sandbox.md`](../../docs/design/workspace-sandbox.md)
에 있다.

## 현재 상태

**소스가 없다.** identifier 기반 도구 넷(`RunSandbox` · `CopyToSandbox` · `RestartSandbox` · `DeleteSandbox`)과
`SandboxBackend` · `RunStore` · `SandboxLock` · `ReaperService` · tar 전송은 새 설계로 대체되어 지웠다. 옛 것과
새 것의 대응은 설계 §17 에 있다.

이 모듈은 설계 §18 의 3단계에서 채워진다 — 워크스페이스 레코드와 저장소, `SandboxWorkspaceManager`,
`SandboxProvider` SPI, 바인딩 정책, `SandboxExecutionEnvironmentProvider`, janitor. 운영에 쓸 프로바이더
(`aimon-sandbox-opensandbox`)는 4단계에서 별도 모듈로 들어오고, 두 단계는 한 릴리스로 낸다.

옛 구현이 필요하면 `at.aimon.core:aimon-sandbox{,-docker,-kubernetes}:0.2.4` 가 Maven Central 에 그대로 있다.
