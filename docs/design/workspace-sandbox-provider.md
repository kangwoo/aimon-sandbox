# 워크스페이스 샌드박스 — 프로바이더 SPI

프로바이더가 지켜야 하는 계약을 정한다. 이 계약은 어느 프로바이더든 같고, OpenSandbox 구현이 이 계약을 어떻게 지키는지는
[OpenSandbox](workspace-sandbox-opensandbox.md) 문서가 다룬다.

> 관련: [개요](workspace-sandbox.md) · [OpenSandbox](workspace-sandbox-opensandbox.md) · [셸](workspace-sandbox-shell.md) · [수명](workspace-sandbox-lifecycle.md) · [설정](workspace-sandbox-configuration.md) · [테스트](workspace-sandbox-testing.md)

## 1. SPI

프로바이더는 실제 샌드박스를 만들고 끝내는 인프라다([개요 §3](workspace-sandbox.md)). 백엔드는 OpenSandbox 하나지만 SPI 를
둔다. 테스트용 로컬 프로바이더([테스트 §2](workspace-sandbox-testing.md))와 앞으로 생길 다른 인프라(예:
`kubernetes-sigs/agent-sandbox`)가 같은 매니저 위에 올라오게 하기 위해서다. 매니저 · 셸 · 파일 시스템은 이 SPI 만 보고
OpenSandbox 를 모른다.

```java
public interface SandboxProvider extends AutoCloseable {
    ProviderCapabilities capabilities();

    ProviderSandboxRef create(CreateSpec spec);                  // best-effort idempotent on spec.key() (§4)
    Optional<ProviderSandbox> status(ProviderSandboxRef ref);    // empty when absent
    void pause(ProviderSandboxRef ref);                          // requires PAUSE_RESUME
    void resume(ProviderSandboxRef ref);                         // SandboxNotFoundException when absent
    void extendExpiry(ProviderSandboxRef ref, Instant until);    // forward only, capped at maxExpiry
    void destroy(ProviderSandboxRef ref);                        // idempotent: absent is success
    List<ProviderSandbox> list(Map<String, String> labels);      // every page

    Optional<SharedVolumes> sharedVolumes();                     // present iff SHARED_VOLUME (§5)

    SandboxConnection connect(ProviderSandboxRef ref);

    default List<VerificationFailure> verify(ProviderSandboxRef ref, Set<Capability> required) { return List.of(); }
}

public interface SharedVolumes {
    List<ProviderVolume> list(Map<String, String> labels);       // ref + labels + whether still mounted
    void delete(VolumeRef ref);                                  // idempotent; VolumeInUseException while mounted
}

public interface SandboxConnection extends AutoCloseable {
    RunningCommand run(ExecSpec spec, OutputSink sink);          // one process group per call (§2)
    SandboxFiles files();                                        // §3
}

public interface RunningCommand {
    ExecOutcome await(Duration timeout) throws InterruptedException;   // past the timeout: kill() and timedOut
    void kill();                                                       // SIGTERM to the group, SIGKILL after grace
}

public interface OutputSink {                                     // output as it arrives (§2.2)
    void accept(Stream stream, byte[] bytes, int offset, int length);
}

public final class ExecOutcome {          // exitCode (128 + n on signal n), stdout, stderr (each ≤ maxCaptureBytes),
                                          // stdoutTruncated, stderrTruncated, timedOut, Optional<ExecKillCause> killCause
}

public final class ProviderCapabilities { // declared provider configuration the manager checks against (§1.3)
    Set<Capability> advertised();
    Optional<Duration> maxExpiry();
    Optional<String> runtimeClass();
    Map<String, CredentialScope> credentialScopes();
    List<HostPort> controlPlaneEndpoints();
}

public final class ProviderSandbox { ref, state, labels, Optional<Instant> expiresAt, Optional<Instant> createdAt }
```

SPI 패키지(`at.aimon.sandbox.provider`)의 타입은 코어 타입에 기대지 않는다. SPI 는 셸이 아닌 exec(seed, 내용 검색)도 쓰고,
프로바이더 구현자가 코어의 셸 어휘에 묶이지 않아야 하기 때문이다. 이 제약 때문에 kill 원인도 SPI 자신의 열거형이다(§2.5).

### 1.1 메서드 계약

- `create` 는 `CreateSpec` 대로 샌드박스를 만들고 참조를 돌려준다. 같은 키의 샌드박스가 이미 있으면 그것을 돌려준다(§4).
- `status` 는 상태 · 라벨 · 만료 시각 · 생성 시각을 돌려주고, 샌드박스가 없으면 빈 값이다. 상태는 `PENDING` · `RUNNING` ·
  `PAUSED` · `TERMINATED` · `FAILED` 다섯이다. `createdAt` 은 조정이 고아의 유예를 샌드박스의 나이로 재는 데 쓴다
  ([수명 §6.2](workspace-sandbox-lifecycle.md)).
- `extendExpiry` 는 앞으로만 늘리고 `maxExpiry` 를 넘기지 않는다. 서버가 이 둘을 지키지 않아도 **프로바이더가** 지킨다. 이
  규칙을 언제 부르는지는 [수명 §5](workspace-sandbox-lifecycle.md)가 정한다.
- `destroy` 는 멱등이다 — 없는 샌드박스의 삭제는 성공이다.
- `list` 는 라벨이 모두 맞는 샌드박스를 모든 페이지에 걸쳐 돌려준다. 조정에는 개수가 아니라 목록이 필요하다.
- `connect` 는 샌드박스 하나에 대한 노드 로컬 핸들을 준다. `SandboxConnectionCache` 가 참조마다 캐시하고, 워크스페이스
  레코드에는 저장하지 않는다. `close()` 는 노드의 자원만 놓고 샌드박스를 건드리지 않는다.
- `pause` · `resume` 은 `PAUSE_RESUME` 을 광고한 프로바이더만 받는다(쓰는 쪽은 계획 — 구현 단계 7).

프로바이더는 "있으면 재사용" 을 판단하지 않는다. 그 판단은 워크스페이스 레코드가 하고, 프로바이더는 레코드가 준 키로 만들기만
한다. 중복은 조정이 치운다. 만료도 같다 — 만료 판단은 워크스페이스 레코드와 프로바이더 만료의 일이고, 프로바이더에게 만료된
것을 거두라고 부탁하는 메서드는 없다.

### 1.2 `ExecSpec` 와 `CreateSpec`

`ExecSpec` 은 명령 하나다.

| 필드 | 뜻 |
|---|---|
| `command` | `bash -c` 로 도는 스크립트. 호출마다 자기 프로세스 그룹에서, 샌드박스의 실행 사용자로 돈다 |
| `workingDirectory` | 작업 디렉터리 |
| `environment` | 프로파일의 정적 `env` 뿐이다. 명령별 변수는 스크립트가 export 한다([셸 §2](workspace-sandbox-shell.md)). 예외는 스테이징 검증이 `PATH=/usr/bin:/bin` 을 고정하는 것 하나다([파일 시스템 §4](workspace-sandbox-filesystem.md)) |
| `timeout` | 프로바이더 쪽 최후 방어선. 지나면 프로바이더가 프로세스 그룹을 끝내고 `timedOut` 을 보고한다 |
| `maxCaptureBytes` | stdout 과 stderr 각각의 캡처 상한(§2.4) |
| `transientOutput` | 끝난 뒤 샌드박스 안에 출력 사본을 남기지 않는다(§2.6) |

`CreateSpec` 은 샌드박스 하나다: 키(§4.1) · 이미지 · `platform` · 자원(`cpu` · `memory` · `disk` · `pids`) · `runtimeClass` ·
egress 허용 목록(있으면 빈 목록도 정책이다) · 자격 증명 바인딩 이름 · 환경 · 라벨(§4.3) · 만료 시각 · 볼륨 마운트(§5).
프로바이더가 받지 못하는 필드를 어떻게 다루는지는 프로바이더 문서가 적는다([OpenSandbox §4.1](workspace-sandbox-opensandbox.md)).

### 1.3 `ProviderCapabilities` — 선언된 설정

`ProviderCapabilities` 는 프로바이더가 광고하는 capability(§6)와, 매니저가 검사해야 하는 프로바이더 설정을 **데이터로** 낸다.

| 필드 | 뜻 | 읽는 쪽 |
|---|---|---|
| `advertised` | 광고한 capability | 기동 검사, 매니저 |
| `maxExpiry` | 서버의 최대 만료 | 기동 검사 `terminate-after ≤ max-expiry` |
| `runtimeClass` | 서버가 모든 샌드박스에 쓰는 runtime class 하나 | 기동 검사 — 프로파일의 `runtimeClass` 는 이것과 같아야 한다 |
| `credentialScopes` | 자격 증명 바인딩 이름 → 범위. 비밀은 담지 않는다 | 기동 검사(정의 · 겹침 · egress 덮음, [보안 §5](workspace-sandbox-security.md)) |
| `controlPlaneEndpoints` | 샌드박스가 닿으면 안 되는 주소 | seed 의 제어면 탐침([수명 §3](workspace-sandbox-lifecycle.md)) |

메서드가 아니라 데이터로 두는 이유: 이것들은 기동 때 한 번 읽는 선언이고, 검사를 매니저의 기동 검사(`SandboxStartupValidator`)
한 곳에 두어 프로바이더 없이 시험할 수 있게 하기 위해서다. `maxExpiry` 는 서버 설정이라 매니저가 볼 통로가 SPI 뿐이다.

`CredentialScope` 는 `schemes`(기본 `https`) · `hosts`(필수, 정확한 이름 또는 `*.suffix`) · `methods`(기본 GET · POST · PUT ·
PATCH · DELETE) · `paths`(기본 `/*`, 끝의 `*` 는 접두어)로 이루어지고 `overlaps` 로 겹침을 판정한다. port 는 담지 않는다 —
이유는 [보안 §5](workspace-sandbox-security.md).

프로파일 검증 훅을 프로바이더에 두지 않는다. 자격 증명 범위 · runtime class 같은 선언은 `ProviderCapabilities` 의 데이터로
내고, 검사는 매니저의 기동 검사가 한 곳에서 한다. 프로바이더에 검증 훅을 두면 SPI 가 프로파일 타입에 묶이고 프로바이더마다
보안 검사를 다시 구현하게 된다.

### 1.4 `verify`

`verify` 는 살아 있는 샌드박스에서만 프로바이더가 볼 수 있는 것 — 선언으로 광고한 capability 가 실제로 걸렸는지 — 을
확인한다. seed 단계에서 세대마다 한 번, seed 스크립트 앞에 불린다(순서와 실패 처리는 [수명 §3](workspace-sandbox-lifecycle.md)).
`required` 는 그 프로파일이 요구하는 capability(`insecure-allow` 를 뺀 것)다.

- 멱등이다.
- 돌려주는 실패 하나하나는 그 프로파일에 대해 영구다. 모두 성립하거나 확인할 것이 없으면 빈 목록이다(기본 구현).
- 샌드박스가 없으면 `SandboxNotFoundException`, 확인 자체를 못 돌렸으면 transient `SandboxProviderException` 이다.

OpenSandbox 가 무엇을 확인하는지는 [OpenSandbox §4.9](workspace-sandbox-opensandbox.md).

### 1.5 실패

프로바이더 호출의 실패는 `SandboxProviderException` 이고, 그 `kind`(`TRANSIENT` · `PERMANENT`)가 프로비저닝의 실패 분류다
([수명 §2.2](workspace-sandbox-lifecycle.md)). 샌드박스가 없으면 `SandboxNotFoundException` 이다. 광고하지 않은 capability 의
호출은 `UnsupportedOperationException` 이다.

## 2. 명령 계약

### 2.1 프로세스 그룹과 kill

`run` 은 호출마다 프로세스 그룹 하나를 띄우고 `RunningCommand` 를 돌려준다. `kill()` 은 그 그룹에 SIGTERM 을, 유예 뒤
SIGKILL 을 보내고 **그 호출의 그룹만** 끝낸다. 명령이 시작되기 전에 부른 `kill()` 도 잃지 않는다 — 명령이 시작되는 대로
끝낸다. `await(timeout)` 이 시간을 넘기면 `kill()` 하고 `timedOut` 으로 돌려준다.

`run` 이 `RunningCommand` 를 돌려주고 `kill()` 을 갖는 이유: 코어 `VirtualShell` 계약(데드라인 · 인터럽트 때 프로세스를
끝낸다)을 원격에서 지키려면 취소 채널이 필요하다. 그룹 밖으로 빠져나간 자손을 어떻게 보는지는 [셸 §6](workspace-sandbox-shell.md),
지속 셸 세션이 SPI 에 없고 셸이 그 위에서 상태를 이어 붙이는 방식은 [셸 §1](workspace-sandbox-shell.md).

### 2.2 출력 전달

프로바이더는 stdout 과 stderr 를 **구분해서** 돌려준다. 출력은 두 길로 간다.

- **`OutputSink`** — 출력을 도착하는 대로 받는다. 프로바이더 스레드에서 불리므로 구현은 막으면 안 되고, 넘겨받은 버퍼는 호출
  동안만 유효하다. 셸은 이것으로 stream 실행의 줄을 리스너에 흘리고 트레일러를 지켜본다([셸 §5](workspace-sandbox-shell.md),
  [셸 §9](workspace-sandbox-shell.md)). 출력이 필요 없는 호출(seed, 내용 검색)은 버리는 sink 를 준다.
- **`ExecOutcome`** — 끝난 뒤의 결과. 스트림마다 `maxCaptureBytes` 까지 담고 잘림 여부를 따로 표시한다. 종료 코드는 신호
  `n` 으로 죽었으면 `128 + n` 이다.

### 2.3 출력 정규화

**출력은 줄로 정규화될 수 있다 — SPI 의 계약이다.** 출력을 줄 단위로 받는 프로바이더는 다음처럼 돌려줄 수 있다.

- 줄마다 `\n` 을 붙인다. 비어 있지 않은 출력의 마지막 줄바꿈은 늘 있다.
- `\r` 은 버려지거나 줄바꿈이 된다(`\r\n` 은 줄바꿈 하나, 홀로 선 `\r` 은 줄바꿈).
- 잘못된 UTF-8 은 U+FFFD 가 된다. U+FFFD 는 3바이트이므로 스트림이 원래의 세 배까지 커질 수 있다.

`maxCaptureBytes` 는 정규화 **뒤의** 바이트(UTF-8 로 인코딩한 뒤)로 센다. 바이트 수가 정확해야 하는 쪽은 그 수를 출력 안에
싣는다 — 셸의 트레일러가 그렇다([셸 §4](workspace-sandbox-shell.md)). 정규화와 U+FFFD 팽창을 견디는 셸 쪽 캡처 상한
(`3 × max + 1 KiB`)은 [셸 §8](workspace-sandbox-shell.md)이 정한다.

정규화를 허용으로 남기는 이유: 출력을 줄로만 받는 프로바이더(옛 execd 를 쓰는 OpenSandbox 가 그랬다)도 SPI 를 구현할 수 있어야
한다. 계약 스위트도 줄바꿈으로 끝나는 출력만 비교한다. 지원 버전의 OpenSandbox 는 바이트 그대로를 돌려준다
([OpenSandbox §3.2](workspace-sandbox-opensandbox.md)). 다만 잘못된 UTF-8 은 여전히 U+FFFD 가 되므로 셸의 캡처 상한은 그대로다.

줄 정규화하는 프로바이더에서 바이트 그대로의 출력이 필요한 소비자가 생기면, 래퍼가 출력을 파일에 쓰고 files API 로 읽는 길이
있다. 명령마다 HTTP 왕복이 둘 늘어서 기본으로 쓰지 않는다.

### 2.4 캡처 상한과 줄 상한

`maxCaptureBytes` 를 넘는 출력은 버리고 잘림만 표시하며 **명령은 끝내지 않는다.** 출력을 많이 냈다는 이유로 종료 코드가
바뀌면 안 되고, 로컬 프로바이더와 같은 결과여야 한다. 멈추지 않는 출력은 명령 타임아웃이 끝낸다. 넘친 출력은 세고 버릴 뿐
버퍼에 쌓지 않는다.

예외는 **한 줄**이다. 출력을 줄 단위 이벤트로 받는 프로바이더는 이벤트 줄 하나에 상한을 두고, 줄 하나가 그 상한을 넘으면
명령을 끝낸다. 상한까지 버리며 읽으면 메모리는 지키지만 그 줄이 끝나야 다음 이벤트로 갈 수 있어, 끝나지 않는 줄에서는 영원히
읽기 때문이다. 이렇게 끝낸 명령은 두 스트림을 모두 잘림으로 표시하고 kill 원인을 싣는다(§2.5). OpenSandbox 프로바이더의 줄
상한은 받은 JSON 바이트로 256 KiB 다 — 절차는 [OpenSandbox §4.4](workspace-sandbox-opensandbox.md). 이 상한이 stream 실행에
주는 결과는 [셸 §8](workspace-sandbox-shell.md), 셸 엔진에 주는 결과는 [셸 엔진 §5](workspace-sandbox-shell-engine.md).

### 2.5 kill 원인

프로바이더가 스스로 명령을 끝냈으면 `ExecOutcome.killCause()` 에 원인(`ExecKillCause`)을 싣는다. 지금 원인은
`LINE_LIMIT_EXCEEDED` 하나다.

- 원인을 실은 결과는 두 스트림을 모두 잘림으로 표시한다.
- `timedOut` 과 `killCause` 는 함께 서지 않는다. 먼저 명령을 끝낸 쪽 하나만 보고한다. `ExecOutcome` 의 빌더는 둘을 함께
  받으면 거절한다 — 이 불변식을 어기는 프로바이더가 조용히 지나가지 않게 하기 위해서다.

kill 원인을 코어의 `ShellKillCause` 가 아닌 자기 열거형으로 두는 이유는 §1 의 의존 제약이다. 셸이 빠짐없는 `switch` 하나로 코어
쪽으로 옮긴다. 원인의 의미와 셸이 돌려주는 결과의 모양은 [셸 엔진 §5](workspace-sandbox-shell-engine.md)가 정한다.

### 2.6 `transientOutput`

`ExecSpec.transientOutput()` 이 켜진 명령은 끝난 뒤(완료 · 실패 · 타임아웃 · kill 모두) 프로바이더가 샌드박스 안에 남긴 출력
사본을 지운다. best effort 이고 실패는 로그만 남긴다. 사본을 남기지 않는 프로바이더(로컬 프로바이더)는 아무것도 하지 않는다.

셸이 이것을 켜는 실행은 [셸 §5.1](workspace-sandbox-shell.md), 그 이유는 [셸 엔진 §6](workspace-sandbox-shell-engine.md),
OpenSandbox 의 삭제 절차는 [OpenSandbox §4.5](workspace-sandbox-opensandbox.md) 에 있다.

## 3. 파일 계약

`SandboxFiles` 는 샌드박스 하나의 files API 다. 모든 연산이 샌드박스 안 **절대 경로**를 받는다.

| 연산 | 계약 |
|---|---|
| `read(path, offset, length)` | 바이트 범위를 읽는다. `length` 가 음수면 끝까지 |
| `write(path, content, length, mode)` | 없는 부모 디렉터리를 만든다. `WriteMode.CREATE_OR_REPLACE` 는 덮어쓴다. `CREATE_NEW` 는 원자적이고, 이미 있으면 `FileAlreadyExistsException` 이다 |
| `stat(path)` | 없으면 빈 값. 변경 감지 계약(§3.1)을 지킨다 |
| `list(dir, recursive, limit)` | 이름이 아니라 항목마다의 `stat`(디렉터리 여부 포함)을 절대 경로와 함께 돌려준다 |
| `createDirectories(path)` | 멱등. 있는 디렉터리는 성공 |
| `delete(path, recursive)` | 내용이 있는 디렉터리는 `recursive` 일 때만 |
| `move(from, to, overwrite)` | `overwrite` 가 아니면 있는 대상을 덮지 않는다 |

`list` 가 `stat` 을 주는 이유: VFS 목록은 디렉터리를 가려야 하는데, 이름만 주면 항목마다 `stat` 호출이 하나씩 더 든다.

실패는 코어 VFS 예외(`FileNotFoundException` · `FileAlreadyExistsException` · `VirtualFileSystemException`)다. 파일 도구가 다른
파일 시스템의 실패와 같은 모양으로 보고하게 하기 위해서다. 샌드박스가 없으면 `SandboxNotFoundException` 이다.

### 3.1 `stat` 변경 감지

`stat` 은 코어 `FileMetadata` 의 변경 감지 계약을 지킨다 — 내용이 바뀌면 mtime(밀리초 이상 해상도) 또는 etag 가 반드시
바뀐다. 해상도가 초 단위인 프로바이더는 etag 를 줘야 한다. 코어의 동시 수정 검사(stamp)가 이 계약에 기댄다
([파일 시스템 §5](workspace-sandbox-filesystem.md)).

## 4. 멱등 생성과 라벨

### 4.1 sandbox-key

`CreateSpec.key` 는 `"{deployment}/{workspaceId}/{incarnation}/{slot}/{generation}"` 이다. 프로바이더는 이 키로 라벨
(`aimon.at/sandbox-key`)을 붙이고, 같은 키로 이미 만들어진 샌드박스가 있으면 새로 만들지 않고 그것을 돌려준다. generation 과
incarnation 이 키에 들어가므로, 같은 워크스페이스 id 로 다시 만들어진 워크스페이스에서도 종료된 옛 샌드박스를 잘못 되살리지
않는다.

### 4.2 최선 노력

**이 멱등은 최선 노력이다.** 생성 API 에 멱등 키가 없는 프로바이더(OpenSandbox 가 그렇다)는 "라벨로 조회한 뒤 생성" 할 수밖에
없고, 둘 사이는 원자적이지 않다. 두 노드가 같은 키로 동시에 `create` 하면 샌드박스가 둘 생길 수 있다. 레코드 CAS 가 슬롯당
PROVISIONING 소유자를 하나로 만들므로 이 경합은 프로비저닝 인계 때만 생긴다 — 원래 노드가 느렸을 뿐 살아 있던 경우다
([수명 §2.4](workspace-sandbox-lifecycle.md)).

정확성은 레코드가 지킨다. 레코드의 `providerRef` 하나만 쓰이고, 같은 키의 다른 샌드박스는 조정이 DUPLICATE 로 회수한다
([수명 §6.2](workspace-sandbox-lifecycle.md)).

`create` 는 제때 Running 이 되지 않은 샌드박스를 지우지 않고 transient 실패로 끝낸다. 다른 노드의 인계가 키 조회로 그
샌드박스를 이미 채택했을 수 있기 때문이다. 남은 샌드박스는 조정이 레코드를 다시 읽은 뒤 FAILED_LEFTOVER 로 회수한다.

### 4.3 라벨과 인코딩

**라벨 값은 인코딩한다.** 라벨 값은 K8s 라벨 규칙(영숫자와 `-_.`, 63자 이하)을 따라야 한다. `workspaceId`(`ws:{sessionId}`)와
키에는 `:` 와 `/` 가 들어가므로 그대로 쓰면 첫 `create` 가 거부되고, 잘라 쓰면 다른 워크스페이스와 겹친다. 그래서 식별 라벨은
원문의 SHA-256 을 base32 로 적은 앞 32자(160비트) — 아래 `h(…)` — 를 쓴다. 원문은 레코드에만 둔다. 조정의 `list(labels)` 필터도
같은 인코딩을 쓴다.

| 라벨 | 값 |
|------|-----|
| `aimon.at/managed` | `true` |
| `aimon.at/deployment` | deployment 이름(설정에서 라벨 규칙으로 검증) |
| `aimon.at/workspace` | `h(workspaceId)` |
| `aimon.at/sandbox-key` | `h(key)` |
| `aimon.at/incarnation` | 워크스페이스의 incarnation. 무작위 영숫자라 그대로 쓴다 |
| `aimon.at/slot` · `aimon.at/generation` | 슬롯 이름(슬롯 이름 규칙이 라벨 규칙을 만족한다) · 정수 |
| `aimon.at/owner` | `h(tenantId)` — principal id 같은 개인 식별 정보를 공유 인프라에 평문으로 남기지 않는다 |

라벨 계산과 대조는 매니저 쪽 일이다(`SandboxLabels`). 프로바이더는 자기 목적의 라벨을 더할 수 있지만 그 라벨은 대조 대상이
아니다(예: [OpenSandbox §4.1](workspace-sandbox-opensandbox.md) 의 `aimon.at/credentials`).

### 4.4 대조 실패

**돌려받은 샌드박스는 대조한 뒤에만 쓴다.** `create` 가 기존 샌드박스를 돌려주거나 `status` 로 다시 찾은 경우, 매니저는 그
샌드박스의 `workspace` · `sandbox-key` · `generation` · `owner` 라벨이 레코드와 같은지 확인한다. 다르면 쓰지 않고 슬롯을
FAILED(`permanent`, 단계 `labels`)로 둔다. 인코딩 버그나 라벨 충돌이 남의 샌드박스를 넘겨주는 경로를 막기 위해서다. 대조에
실패한 샌드박스는 남의 것일 수 있으므로 지우지도 않는다. 이 대조가 변조 방지는 아니다 — 서버의 API 키를 가진 쪽은 라벨을 바꿀 수
있다([보안 §7](workspace-sandbox-security.md)).

### 4.5 deployment

`deployment` 는 한 워크스페이스 저장소를 공유하는 AIMON 노드 집합의 이름이다([개요 §3](workspace-sandbox.md)). 설정 필수이고
기본값이 없다([설정 §2](workspace-sandbox-configuration.md)). 프로바이더 서버 하나를 여러 애플리케이션이나 여러 개발자 머신이
같이 쓰면 `managed=true` 만으로는 남의 샌드박스와 내 고아를 구분할 수 없다. 그래서 janitor 는 **자기 deployment 라벨이 붙은
샌드박스만** 조정한다. 기본값을 두지 않는 이유는, 두 배포가 같은 기본값을 쓰면 라벨이 없는 것과 같기 때문이다.

## 5. 공유 볼륨

**(계획 — 구현 단계 5)** 공유 볼륨을 쓰는 프로파일(`sharedAccess` 가 `none` 이 아닌 것)은 지금 기동 시 거부된다. 아래 SPI 와
이름 규칙은 이미 있고, 그것을 쓰는 협업은 [파일 시스템 §8](workspace-sandbox-filesystem.md), 볼륨 조정은
[수명 §6.3](workspace-sandbox-lifecycle.md)에서 다룬다.

**공유 볼륨은 따로 만들지 않는다.** `CreateSpec` 이 볼륨 마운트(이름 · `rw`/`ro` · 크기 · storage class · access mode · 없으면
만든다)를 선언하고, 워크스페이스의 첫 샌드박스 생성이 볼륨을 만든다. 삭제만 `SharedVolumes` 가 맡는다 — 샌드박스 생성과 달리
볼륨 삭제는 어느 샌드박스 요청에도 딸려 있지 않기 때문이다. `SharedVolumes.delete` 는 멱등이고, 아직 마운트된 볼륨에는
`VolumeInUseException` 이다. 볼륨을 지울 길이 없는 프로바이더는 `SHARED_VOLUME` 을 광고하지 않는다. OpenSandbox 에는 볼륨 삭제
API 가 없어서, 주입한 `VolumeReclaimer` 가 곧 `SharedVolumes` 가 된다([OpenSandbox §2](workspace-sandbox-opensandbox.md)).

**공유 볼륨은 이름으로 식별한다.** 볼륨에는 라벨을 믿고 붙일 수 없다 — OpenSandbox 의 볼륨 선언에는 라벨 필드가 없고, 만들어진
PVC 에도 라벨이나 ownerRef 가 없으며, Docker 의 named volume 은 만든 뒤 라벨을 바꿀 수 없다. 그래서 볼륨은 이름 규칙
`aimon-{h(deployment)[:8]}-{h(workspaceId)[:16]}-{incarnation}`(DNS 라벨 63자 이내)으로 식별하고, `SharedVolumes.list` 는
`aimon-{h(deployment)[:8]}-` 접두어로 찾는다. 실패한 생성이 자동으로 남긴 볼륨도 같은 접두어로 찾는다. 보존 여부 같은 상태는
볼륨이 아니라 레코드에 둔다([수명 §7](workspace-sandbox-lifecycle.md)). 볼륨을 지우는 구현은 이 접두어 밖의 볼륨을 보지 않는다.

**서버의 볼륨 자동 삭제는 쓰지 않는다.** OpenSandbox 의 `deleteOnSandboxTermination` 을 켜면 볼륨을 만든 첫 샌드박스가 소유자가
되어, 다른 샌드박스가 아직 쓰는 중에 지우려다 실패하면 다시 시도하지 않는다(1.1.x 에서 잼, 1.2.0 미확인). 볼륨의 수명은
워크스페이스의 것이므로 삭제는 워크스페이스를 아는 쪽이 한다.

## 6. capability 와 요구

| capability | 뜻 |
|-----------|-----|
| `EXEC` | 명령 실행: cwd · env · stdout/stderr 구분 · 프로세스 그룹 kill |
| `FILES` | 파일 API(§3) |
| `PAUSE_RESUME` | 일시 정지와 재개 |
| `EXPIRY` | 프로바이더 측 절대 만료 시각과 연장 |
| `SHARED_VOLUME` | 여러 샌드박스에 RWX 볼륨 마운트, 샌드박스별 읽기 전용 마운트, 볼륨 삭제 |
| `EGRESS_POLICY` | 목적지 허용 목록. 명시하지 않은 목적지는 차단 |
| `CREDENTIAL_INJECTION` | 샌드박스에 비밀을 노출하지 않고 egress 에서 주입. host · 경로 · 메서드로 범위를 좁힘 |
| `NETWORK_ISOLATION` | 샌드박스 사이(east-west)와 샌드박스 → 프로바이더 서버 · K8s API · 클러스터 내부 트래픽 차단 |
| `RUNTIME_CLASS` | 요청한 runtime class(gVisor · Kata)로 실제 실행 |
| `HARDENED_SECURITY_CONTEXT` | 비루트 · 권한 상승 금지 · capability 제거 · 서비스 계정 토큰 미마운트 |
| `SNAPSHOT` · `FORK` | 스냅숏 · 복제. 이것을 쓰는 기능은 **(계획 — 구현 단계 8)** 이고 지금 광고하는 프로바이더가 없다 |

각 capability 가 OpenSandbox 에서 무엇에 대응하고 언제 광고되는지는 [OpenSandbox §2](workspace-sandbox-opensandbox.md).

**확인되지 않은 capability 는 광고하지 않는다.** 그리고 capability 는 프로바이더 설정의 선언에서 유도하고, 기동 시 서버를
탐침하지 않는다. 서버는 샌드박스가 생기기 전에는 결정에 필요한 사실(egress 모드 같은 것)을 주지 않기 때문이다. 살아 있는
샌드박스에서 볼 수 있는 것은 seed 의 `verify`(§1.4)가 세대마다 확인한다 — 선언이 틀린 배포가 조용히 열린 채 돌지 않고 슬롯이
영구 실패하게 하기 위해서다.

**요구한 capability 를 광고하지 않으면 기동 시 거부한다.** egress 를 막으라는 프로파일이 egress 정책 없는 프로바이더 위에서
조용히 열린 채로 도는 일은 없어야 하기 때문이다. 프로파일이 요구하는 capability 는 설정에서 유도한다.

| 프로파일 설정 | 요구 |
|---|---|
| `egress` 가 있다(빈 목록 포함) | `EGRESS_POLICY` |
| `credentials` 가 있다 | `CREDENTIAL_INJECTION` |
| `runtimeClass` 가 있다 | `RUNTIME_CLASS` |
| `sharedAccess` 가 `none` 이 아니다 (계획 — 구현 단계 5) | `SHARED_VOLUME` |
| `pauseAfter` 가 있다 (계획 — 구현 단계 7) | `PAUSE_RESUME` |
| 모든 프로파일 | `EXPIRY` · `HARDENED_SECURITY_CONTEXT` · `NETWORK_ISOLATION` |

로컬 개발처럼 격리를 일부러 풀려면 프로파일에 `insecure-allow: [HARDENED_SECURITY_CONTEXT, …]` 를 적어야 하고, 기동 로그에
경고가 남는다. `insecure-allow` 는 `HARDENED_SECURITY_CONTEXT` · `RUNTIME_CLASS` · `NETWORK_ISOLATION` 만 면제한다. 기동 검사의
전체 목록은 [설정 §3](workspace-sandbox-configuration.md).

샌드박스 자원 사용량은 capability(`METRICS`)로 올리지 않는다. 프로바이더 서버와 K8s 가 이미 내고 §4.3 의 라벨로 이어 붙이면
된다. capability 로 올리면 SPI 가 특정 SDK 의 메트릭 API 에 묶인다.

## 7. warm pool

warm pool(미리 띄워 둔 샌드박스 풀)은 SPI 에 올리지 않는다. 풀은 `create` 의 구현 세부이고, 공개 계약을 실험적 API 에 묶으면
그 API 가 바뀔 때마다 SPI 가 흔들리기 때문이다. 그래서 SPI 의 호출자는 풀을 요청할 수 없다. 프로바이더 안에서 풀을 쓰는 공개 풀
지원은 **(계획 — 구현 단계 7)** 이다([개요 §8](workspace-sandbox.md)). 풀을 쓰는 OpenSandbox 서버의
동작은 [OpenSandbox §3.8](workspace-sandbox-opensandbox.md)에 기록만 둔다.
