# aimon-core 와 연동하기

aimon-sandbox 를 aimon-core 애플리케이션에 끼우는 방법을 다룬다. 손으로 조립한 런타임, aimon-bootstrap 의
`AimonStack`, Spring Boot starter 세 경우를 차례로 보고, 그 뒤에 세션 소유자 · 세션 삭제 · 종료 순서 · 훅처럼 어느
경우에나 해당하는 것을 적는다.

- OpenSandbox 서버 준비는 [OpenSandbox 를 Kubernetes 에 설치하기](opensandbox-kubernetes.md)
- 설정 키와 동작은 [사용자 가이드](user-guide.md)
- 설계 근거는 [`workspace-sandbox.md`](../design/workspace-sandbox.md) §7(코어 통합),
  [`workspace-sandbox-core-033.md`](../design/workspace-sandbox-core-033.md)(core 0.3.3 을 따르는 법)

---

## 1. 어떻게 맞물리는가

aimon-core 는 파일 도구와 셸 도구가 **실행마다** 실행 환경을 `ExecutionEnvironmentProvider` 에게 묻는다. aimon-sandbox
는 이 SPI 를 구현한다.

```
에이전트 턴 ──▶ Bash / Read / Write / Edit / Grep
                    │ ExecutionEnvironmentProvider.resolve(EnvironmentRequest)
                    ▼
         SandboxExecutionEnvironmentProvider          ← sandbox.environmentProvider()
                    │ 바인딩 정책: 요청 속성 → 세션 옵션 → 에이전트 기본값 → unattached 규칙
                    ▼
         워크스페이스 · 슬롯 (없으면 게으르게 프로비저닝)
                    │ SandboxProvider
                    ▼
         OpenSandbox 샌드박스 안의 파일 시스템 · 셸
```

- 의존은 한 방향이다. aimon-sandbox 가 Maven Central 의 `at.aimon.core:aimon-core` 를 컴파일 의존으로 쓰고, aimon-core
  는 이 저장소를 모른다
- provider 는 `AgentRuntime` 마다 아무것도 들고 있지 않다. 런타임이 내려가도 명령이나 샌드박스는 그대로이고,
  샌드박스는 워크스페이스만큼 산다
- 필요한 버전: aimon-sandbox `main` 은 aimon-core **0.3.3**(`0.1.0` 은 0.3.1). 이 문서는 0.3.3 기준이다. 0.3.3 이
  릴리스되기 전까지 `main` 은 Central 스냅숏 저장소의 `0.3.3-SNAPSHOT` 을 쓴다

### 1.1 공통 조립

어느 경우에나 `WorkspaceSandbox` 하나를 애플리케이션 범위로 만든다. 이것이 저장소 · 매니저 · janitor · 실행 환경을
묶은 조립체이고, 만든 쪽이 닫는다.

```java
OpenSandboxProvider provider = new OpenSandboxProvider(providerConfig);   // K8s 설치 가이드 §10

WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
        .settings(settings)                     // SandboxSettings — 프로파일 포함
        .provider(provider)
        .ownProvider(true)                      // sandbox.close() 가 provider 도 닫는다
        .sessionRecords(() -> stack.sessionRecordStore())   // core 가 기록한 세션 소유자(§3)
        // .tenantResolver(...)                 // require-principal 이면 필수(§6)
        // .eventListener(...)                  // 감사 로그 · 지표
        .build();                               // 설정 위반이 있으면 SandboxConfigurationException
sandbox.janitor().start();
```

core 에 넘기는 것은 둘이다.

| 무엇 | 어디로 | 왜 |
|---|---|---|
| `sandbox.environmentProvider()` | 런타임의 `executionEnvironmentProvider` | 도구가 샌드박스에서 돌게 한다 |
| `sandbox.sessionLifecycleListener()` | 세션 라우터의 lifecycle listener | 세션을 지우면 그 세션의 옵션과 `ws:{sessionId}` 를 치운다(§4) |

---

## 2. 조립 방법

### 2.1 손으로 조립한 런타임

```java
OrcaAgentRuntime runtime = OrcaAgentRuntime.builder()
        .agent(agent)
        .toolRegistry(tools)
        // ...
        .executionEnvironmentProvider(sandbox.environmentProvider())
        .build();
```

세션 라우터를 직접 만든다면 `SessionRouterBuilder` 에 `sandbox.sessionLifecycleListener()` 를 등록한다.

### 2.2 aimon-bootstrap (`AimonStack`)

```java
AtomicReference<AimonStack> stackRef = new AtomicReference<>();

WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
        .settings(settings).provider(provider).ownProvider(true)
        .sessionRecords(() -> stackRef.get().sessionRecordStore())   // 스택은 sandbox 뒤에 만들어진다
        .build();
sandbox.janitor().start();

AimonStack stack = AimonStack.assembled(AimonStackSpec.builder()   // 아직 시작하지 않는다
        // ...
        .executionEnvironment(ExecutionEnvironmentSpec.shared(sandbox.environmentProvider()))
        .session(SessionSpec.builder()
                // ...
                .lifecycleListener(sandbox.sessionLifecycleListener())
                .build())
        .build());
stackRef.set(stack);   // 턴이 돌기 전에 채운다
stack.start();

// 종료: 스택이 먼저, sandbox 가 나중
stack.close();
sandbox.close();
```

- **`shared(...)` 를 쓴다. `provider(Supplier)` 를 쓰지 않는다.** `provider(Supplier)` 는 스택이 provider 를 만들고
  닫는다. sandbox 는 호스트가 소유하고 호스트가 닫아야 한다. (`ExecutionEnvironmentSpec.factory` 는 core 0.3.1 에서
  없어졌다)
- `sessionRecords(Supplier)` 는 호출할 때마다 공급자를 읽으므로, 스택을 sandbox 뒤에 만들어도 된다. 다만 첫 턴 전에
  `stackRef` 가 채워져 있어야 한다. `AimonStack.from(...)` 은 조립과 함께 **시작까지** 하므로, `assembled(...)` 로
  조립하고 `stackRef` 를 채운 뒤 `start()` 한다

### 2.3 Spring Boot starter

starter 는 애플리케이션의 `ExecutionEnvironmentProvider` 빈이 있으면 로컬 기본값 대신 그것을 모든 런타임에 쓰고
(`ExecutionEnvironmentSpec.shared`), 모든 `SessionLifecycleListener` 빈을 세션 라우터에 등록한다. aimon-sandbox 용
자동 설정은 없으므로 설정 클래스를 하나 둔다.

```java
@Configuration(proxyBeanMethods = false)
class SandboxConfiguration {

    @Bean(destroyMethod = "close")
    WorkspaceSandbox workspaceSandbox(ObjectProvider<AimonStack> stack) {
        OpenSandboxProvider provider = new OpenSandboxProvider(OpenSandboxProviderConfig.builder()
                .endpoint(URI.create("http://opensandbox-server.opensandbox-system.svc"))
                .apiKey(() -> System.getenv("OPEN_SANDBOX_API_KEY"))
                .runtime(OpenSandboxProviderConfig.Runtime.KUBERNETES)
                .maxExpiry(Duration.ofHours(24))
                .egressEnforcement(OpenSandboxProviderConfig.EgressEnforcement.DNS_NFT)
                .networkIsolation(OpenSandboxProviderConfig.Declaration.DECLARED)
                .hardenedSecurityContext(OpenSandboxProviderConfig.Declaration.DECLARED)
                .controlPlaneProbes(List.of(HostPort.parse("opensandbox-server.opensandbox-system.svc:80"),
                        HostPort.parse("kubernetes.default.svc:443")))
                .build());
        WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
                .settings(SandboxSettings.builder()
                        .deployment("acme-prod")
                        .profiles(List.of(SandboxProfile.builder()
                                .name("standard")
                                .image("registry.example.com/aimon/sandbox-runtime:1")
                                .platform("linux")
                                .resources(ResourceSpec.of("2", "4Gi", null, null))
                                .egress(List.of("github.com", "repo.maven.apache.org"))
                                .terminateAfter(Duration.ofHours(2))
                                .build()))
                        .defaultProfile("standard")
                        .build())
                .provider(provider).ownProvider(true)
                // 스택은 이 빈에 기대어 만들어지므로 여기서는 늦게 꺼낸다
                .sessionRecords(() -> stack.getObject().sessionRecordStore())
                .build();
        return sandbox;   // janitor 는 컨텍스트가 다 뜬 뒤 시작한다(아래)
    }

    /** janitor 가 컨텍스트 refresh 도중에 스택을 꺼내지 않게, 애플리케이션이 준비된 뒤 시작한다. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> sandboxJanitorStarter(WorkspaceSandbox sandbox) {
        return event -> sandbox.janitor().start();
    }

    @Bean
    ExecutionEnvironmentProvider sandboxEnvironment(WorkspaceSandbox sandbox) {
        return sandbox.environmentProvider();
    }

    @Bean
    SessionLifecycleListener sandboxSessionCleanup(WorkspaceSandbox sandbox) {
        return sandbox.sessionLifecycleListener();
    }

    /** 스택이 sandbox 보다 먼저 닫히게 한다(§5). */
    @Bean
    static BeanFactoryPostProcessor aimonStackAfterSandbox() {
        return beanFactory -> {
            // aimon.enabled=false 이거나 애플리케이션이 자기 AimonStack 을 다른 이름으로 두면 이 빈이 없다
            if (!beanFactory.containsBeanDefinition("aimonStack")) {
                return;
            }
            BeanDefinition stack = beanFactory.getBeanDefinition("aimonStack");
            String[] dependsOn = stack.getDependsOn() == null ? new String[0] : stack.getDependsOn();
            String[] extended = Arrays.copyOf(dependsOn, dependsOn.length + 1);
            extended[dependsOn.length] = "workspaceSandbox";
            stack.setDependsOn(extended);
        };
    }
}
```

- **종료 순서를 명시한다.** starter 는 `ExecutionEnvironmentProvider` 를 `ObjectProvider` 로 꺼내므로 Spring 이 스택과
  sandbox 사이의 의존 관계를 모른다. 그대로 두면 소멸 순서가 등록 순서에 맡겨진다. 위의 `BeanFactoryPostProcessor` 가
  스택 빈(`aimonStack`)에 `dependsOn("workspaceSandbox")` 를 더해, 스택이 나중에 만들어지고 **먼저 닫히게** 한다
- 애플리케이션이 `AimonStack` 을 직접 정의한다면 위의 `"aimonStack"` 을 그 빈 이름으로 바꾼다
- janitor 는 `ApplicationReadyEvent` 에서 시작한다. 팩토리 메서드 안에서 시작하면 janitor 스레드가 컨텍스트가 뜨는
  도중에 `stack.getObject()` 를 부를 수 있다
- 세션 저장소가 애플리케이션 빈(예: Postgres `SessionRecordStore`)이면 `stack.getObject().sessionRecordStore()` 대신
  그 빈을 바로 넘겨도 된다
- 설정값은 `@ConfigurationProperties` 로 받아 빌더에 옮긴다. 이 저장소에는 `aimon.sandbox.*` 바인딩이 없다(키 이름은
  설계 §13.2 의 제안을 따르면 된다)
- starter 의 `aimon.environment.*`(`control-writable`, `background-command-timeout` 등)는 로컬 provider 에만
  적용된다. 샌드박스에서는 프로파일의 값이 쓰인다

---

## 3. 세션 소유자

세션의 `ws:{sessionId}` 는 **세션 소유자**의 것이고, 세션 옵션도 소유자만 바꾼다. "처음 부른 사람이 주인"인 경로는
없다. 소유자의 출처는 둘이다.

| 출처 | 설정 | 언제 |
|---|---|---|
| core 가 기록한 소유자 | `WorkspaceSandbox.Builder.sessionRecords(...)` | 기본. 세션을 만드는 submit 에 `SubmitRequest.owner(...)` 를 준다 |
| 호스트의 조회 | `WorkspaceSandbox.Builder.sessionOwnerLookup(...)` | 소유자 없이 만든 예전 세션이 있을 때. 기록된 소유자가 없을 때만 묻는다 |

```java
router.submit(SubmitRequest.builder()
        // ... sessionId · userInput · agentRef 등
        .initiator(Principal.user(userId))
        .owner(Principal.user(userId))    // 세션 레코드를 처음 만들 때만 기록된다. 첫 기록이 이긴다
        .build());
```

- **출처를 하나라도 두면, 소유자를 모르는 세션은 샌드박스를 받지 못한다.** `require-principal` 과 상관없다. 그래서
  `sessionRecords(...)` 를 연결하기 전에 `SubmitRequest.owner(...)` 부터 채운다. 그렇지 않으면 모든 메인 턴이
  unavailable 이 된다
- 소유자는 실행 주체와 **타입과 id 로** 비교한다. 턴이 GROUP 이나 SERVICE 주체로 돈다면 그 타입 그대로 돌려준다.
  `Principal.user(id)` 로 돌려주면 `workspace-access: principal` 에서 모든 턴이 "not permitted" 가 된다
- core 는 소유자를 기록만 하고 검사하지 않는다. 누가 그 세션에 submit 할 수 있는지는 호스트가 정한다
- `sessionRecords(...)` 만 쓰면 세션 레코드는 첫 submit 때 생기므로, **그 전에는 세션 옵션을 쓸 수 없다.** 첫 턴부터
  관리형 워크스페이스에 두려면 요청 속성으로 첫 턴을 옮기거나, `SessionRecordStore.provision(id, agentRef, owner)`
  로 레코드를 먼저 만들거나, lookup 을 둔다

---

## 4. 세션 삭제

`sessionLifecycleListener()` 를 등록하면 core 의 세션 라우터가 세션을 지울 때 그 세션의 샌드박스 옵션을 지우고
`ws:{sessionId}` 를 닫고 지운다. 그 세션이 붙어 있던 관리형 워크스페이스는 공유물이므로 남는다.

- 지우는 노드만 이 소식을 듣지만, 지울 것은 모두 공유 저장소에 있으니 충분하다
- 라우터를 거치지 않고 지운 세션은 `SandboxAdministration.deleteSessionOptions` 로 치우거나, `closed-retention` 뒤에
  janitor 가 치운다(`sessionRecords` 를 연결했을 때)
- 지운 직후 같은 id 로 세션을 다시 만들면, 옛 샌드박스가 사라질 때까지 도구가 "being removed; retry shortly" 를 낸다.
  보통 `close-wait` 안에 풀린다
- 관리형 워크스페이스에 남은 세션의 셸 상태(`/workspace/.aimon-shell/session-{id}`)는 지우지 않는다

---

## 5. 종료 순서

**core 스택(또는 런타임)을 먼저 닫고, `WorkspaceSandbox` 를 나중에 닫는다.**

core 는 종료할 때 아직 도는 백그라운드 명령을 셸을 통해 멈춘다. 샌드박스 셸은 `WorkspaceSandbox` 가 들고 있는
연결로 명령에 닿는다. 순서가 뒤집히면 멈춤 요청이 실패하고, 명령은 샌드박스가 만료될 때까지 계속 돈다.

- aimon-bootstrap: `stack.close(); sandbox.close();`
- Spring: §2.3 의 `dependsOn`
- `WorkspaceSandbox.close()` 는 janitor · 백그라운드 작업 · 연결을 닫는다. 샌드박스 자체는 지우지 않는다 — 워크스페이스의
  수명을 따른다. 재시작한 노드는 저장소의 레코드로 이어 가지만, InMemory 저장소는 재시작하면 비므로 남은 샌드박스는
  다음 기동의 janitor 가 `orphan-grace` 뒤에 고아로 지운다

---

## 6. 멀티테넌트

core 의 `Principal` 에는 테넌트가 없다. 테넌트를 나누려면 다음을 둔다.

```java
WorkspaceSandbox.builder()
        .settings(settings.toBuilder().requirePrincipal(true).build())
        .tenantResolver(principal -> tenantOf(principal))      // 모르면 null → 바인딩 거부
        .sessionRecords(() -> stack.sessionRecordStore())
        // .admission(...)                                     // 기본: 테넌트별 동시 실행 max-running-per-tenant
        .build();
```

- `require-principal: true` 이면 USER/GROUP 주체만 쓴다. 루틴 · 시스템 작업이 SERVICE 주체로 돈다면
  `allowed-system-principals` 에 넣는다
- `SandboxTenantResolver` 와 소유자 출처가 없으면 기동이 거부된다
- 기본 `workspace-access: principal` 은 같은 테넌트의 **같은 주체**만 워크스페이스를 쓰게 한다. 테넌트 단위 공유는
  `access: tenant` 인 관리형 워크스페이스와 `allow-shared-workspaces: true` 로 명시한다

---

## 7. 실행 배치 — 요청 속성 · 에이전트 기본값

```java
// 한 턴만
live.submit(prompt, SubmitOptions.builder()
        .executionAttributes(SandboxRequestAttributes.workspace(ws.id())).build());

// 루틴(스케줄 작업) 실행마다
schedulingSpec = schedulingSpec.withRoutineExecutionAttributes((task, executionId) ->
        placementOf(task.getId()).map(SandboxRequestAttributes::workspace).orElse(Map.of()));
```

에이전트 정의의 속성으로 그 에이전트의 모든 세션을 한 워크스페이스에 둘 수 있다.

```yaml
attributes:
  sandbox:
    workspace: wsm:6f1c…     # 다른 곳에 붙지 않은 세션의 기본 워크스페이스
    unattached: reject       # 어디에도 붙지 않은 세션에는 샌드박스를 주지 않는다
```

- 요청 속성은 그 실행만 바인딩하고 세션 옵션을 쓰지 않는다. 실행 주체(루틴은 그 작업의 소유자)가 워크스페이스를 쓸
  권한이 있어야 한다
- 포크는 부모의 워크스페이스를 물려받고 요청 속성을 보지 않는다
- **요청 속성과 정의 속성을 모델 출력으로 채우지 않는다.** `sandbox.workspace` 를 쓸 수 있다는 것은 그 에이전트의 모든
  세션을 다른 곳으로 돌릴 수 있다는 뜻이다

---

## 8. 훅

| 훅 | 어디서 도는가 |
|---|---|
| 스킬이 선언한 셸 훅 | 그 실행의 샌드박스 셸(core 기본 `DefaultShellActionExecutor` 일 때) |
| `hooks.json` 의 실행 중 이벤트(`preTool`, `postTool` …) | 샌드박스 안에서 격리된 명령으로. 세션의 셸 상태를 읽지 않는다 |
| `"shell": "host"` 인 `hooks.json` 핸들러, `"shell"` 없이 `asyncRewake` 를 선언한 핸들러, 실행 없는 발화 | 호스트 |

- 샌드박스를 쓸 수 없으면 스킬의 `preTool` 셸 가드가 지키는 호출은 **막히고**, `onStart` 셸 가드가 있는 스킬 포크는
  **시작하지 않는다.** 관찰만 하는 훅에는 `failOpen: true` 를 준다
- 훅의 파일(`AIMON_HOOK_DIR` · `AIMON_SKILL_DIR`)은 실행마다 비공개 디렉터리에 놓이고 다이제스트로 확인된다. 이미지에
  GNU `sha256sum` · `base64` 가 `/usr/bin` 에 있어야 한다
- 훅이 남긴 `cmd &` 는 훅이 끝나면 함께 죽는다
- **샌드박스 안의 훅은 권한 경계가 아니다.** 에이전트와 같은 uid 로 돌므로 훅이 도는 동안 에이전트의 프로세스가 닿을 수
  있다. 에이전트에 맞서야 하는 가드는 `"shell": "host"` 로 두고 stdin 의 도구 입력만 판단한다. 호스트의 가드는
  샌드박스 파일을 볼 수 없고, 입력을 `eval` 하는 식의 부주의한 가드는 모델이 만든 입력을 컨테이너 밖에서 돌린다
- 스킬 셸 훅을 아예 받지 않으려면 core 의 `SkillHookSetParser` 를 `NoOpShellActionExecutor` 로 만든다

---

## 9. 그 밖에

- **환경 알림**: 초기화 · 재생성 · 이동은 다음 도구 결과의 맨 앞에 `[environment]` 줄로 한 번 알린다. 훅 · 워크플로
  단계 · 스테이징처럼 core 가 알림을 꺼내지 않는 곳에서 생긴 초기화는 세션 옵션에 남겨 다음 도구 결과에 알린다. 그래서
  직접 만든 `SessionOptionsStore` 는 `unannouncedReset` 을 영속해야 한다
- **컨텍스트 제공자**: core 의 `GitStatusContextProvider` 와 `DirectorySummaryContextProvider` 는 매 턴 파일 시스템을
  읽으므로, 명령을 치지 않는 턴에도 샌드박스를 띄운다. 샌드박스 모드에서는 등록하지 않는다
- **백그라운드 명령**: `KillShell` 은 명령과 그 프로세스 그룹을 멈춘다(`setsid` · `set -m` 으로 그룹을 벗어난 작업은
  샌드박스가 끝날 때까지 산다). 아무도 멈추지 않으면 프로파일의 `backgroundCommandTimeout`(기본 24h)에서 끝난다
- **런타임 교체**: provider 가 런타임마다 아무것도 들고 있지 않으므로, 런타임을 내리고 다시 올려도 명령과 샌드박스는
  그대로다

---

## 10. 테스트

```java
// 격리 없는 로컬 프로바이더로 core 와의 연동을 단위 테스트한다 — 운영 금지
SandboxProvider provider = LocalProcessSandboxProvider.builder().build();
```

- `aimon-sandbox-testkit` 의 `LocalProcessSandboxProvider` 는 임시 디렉터리와 로컬 프로세스로 샌드박스를 흉내 낸다.
  격리가 없으므로 테스트 전용이다. `SandboxTestProfiles` 가 여기 맞는 프로파일을 준다
- `FaultInjectingSandboxProvider` 로 지연 · 실패 · not found 를 넣어 "샌드박스를 쓸 수 없을 때"의 동작을 확인한다
- `ManualClock` · `ManualScheduler` 로 idle · heartbeat 를 기다리지 않고 돌린다
- 실제 `OrcaAgentExecutor` 위의 연동 예는
  [`OrcaRuntimeSandboxE2ETest`](../../modules/aimon-sandbox/src/test/java/at/aimon/sandbox/environment/OrcaRuntimeSandboxE2ETest.java)
  에 있다
