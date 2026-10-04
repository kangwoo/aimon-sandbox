package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.WorkspaceSandbox;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ResourceSpec;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.testkit.ManualClock;
import at.aimon.sandbox.testkit.ManualScheduler;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;

/**
 * {@code WorkspaceSandbox} end to end on a real OpenSandbox server (Docker runtime) — the implementation-step-4 rows of
 * docs/design/workspace-sandbox.md §16 that need the real server: labels accepted for a {@code ws:{uuid}} workspace,
 * shell state across calls, timeout kill, cancellation of a running background command, reconciliation of a restarted
 * node's orphan, resource limits, and the line normalization of output.
 */
@Tag("docker")
class OpenSandboxWorkspaceIT {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("opensandbox-it");
    private static final Principal ALICE = Principal.user("alice");

    private final ManualClock clock = new ManualClock(Instant.now());
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final List<SandboxEvent> events = new ArrayList<>();
    private final List<WorkspaceSandbox> assemblies = new ArrayList<>();
    private OpenSandboxProvider provider;

    @BeforeEach
    void startProvider() {
        provider = new OpenSandboxProvider(OpenSandboxTestServer.dnsNft().config().build());
    }

    @AfterEach
    void close() {
        assemblies.forEach(assembly -> {
            assembly.store().scan(at.aimon.sandbox.workspace.WorkspaceScan.builder().build())
                    .forEach(record -> assembly.manager().close(record.id(), ALICE));
            assembly.close();
        });
        provider.close();
    }

    private static SandboxProfile.Builder profile() {
        return SandboxProfile.builder().name("it").image(OpenSandboxTestServer.sandboxImage()).platform("linux")
                .terminateAfter(Duration.ofHours(1))
                .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION));
    }

    private WorkspaceSandbox assembly(SandboxProfile profile, SandboxWorkspaceStore store) {
        final SandboxSettings settings = SandboxSettings.builder().deployment(OpenSandboxTestServer.DEPLOYMENT)
                .nodeId("it-" + UUID.randomUUID()).profiles(List.of(profile)).defaultProfile(profile.name())
                .closeWait(Duration.ofSeconds(20)).build();
        final WorkspaceSandbox assembly = WorkspaceSandbox.builder().settings(settings).provider(provider).store(store)
                .clock(clock).scheduler(scheduler).eventListener(events::add).build();
        assemblies.add(assembly);
        return assembly;
    }

    private static ExecutionEnvironment mainTurn(WorkspaceSandbox assembly, SessionId session) {
        return ExecutionEnvironments.resolveOrUnavailable(assembly.environmentProvider(),
                EnvironmentRequest.builder().agentRuntimeId(RUNTIME).sessionId(session).principal(ALICE).build());
    }

    private static ShellCommandResult bash(ExecutionEnvironment env, String command, Duration timeout)
            throws Exception {
        return env.shell().execute(() -> command, ExecutionOptions.builder().timeout(timeout).build());
    }

    private static ShellCommandResult bash(ExecutionEnvironment env, String command) throws Exception {
        return bash(env, command, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("§16: a ws:{uuid} workspace provisions on the real server; Write then Bash sees the file")
    void writeThenBashOnARealSandbox() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = mainTurn(assembly, session);

        env.fileSystem().write("/workspace/hello.txt", "hello from the file tool");
        final ShellCommandResult cat = bash(env, "cat /workspace/hello.txt");

        assertThat(cat.stdout()).isEqualTo("hello from the file tool\n");
        assertThat(events).extracting(SandboxEvent::type).contains(SandboxEvent.Type.PROVISIONED);
        final ProviderSandboxRef ref = assembly.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();
        assertThat(ref.provider()).isEqualTo(OpenSandboxProvider.NAME);
    }

    @Test
    @DisplayName("§16: cd/export persist across calls, and a timeout kill keeps the state from before")
    void shellStatePersistsAndATimeoutKillKeepsIt() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final ExecutionEnvironment env = mainTurn(assembly, SessionId.generate());

        bash(env, "mkdir -p /workspace/src && cd /workspace/src && export KEEP=kept");
        assertThatThrownBy(() -> bash(env, "cd /tmp; export LOST=1; sleep 30", Duration.ofSeconds(2)))
                .isInstanceOf(ShellTimeoutException.class);
        final ShellCommandResult next = bash(env, "pwd; echo \"KEEP=$KEEP LOST=$LOST\"");

        assertThat(next.stdout()).isEqualTo("/workspace/src\nKEEP=kept LOST=\n");
    }

    @Test
    @DisplayName("§16: cancelling a running background command ends it in the real sandbox (DELETE /command)")
    void cancellingABackgroundCommandEndsItInTheSandbox() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final ExecutionEnvironment env = mainTurn(assembly, SessionId.generate());
        bash(env, "true");
        final ShellCancellationSource source = ShellCancellationSource.create();
        final CompletableFuture<Object> background = CompletableFuture.supplyAsync(() -> {
            try {
                return env.shell().execute(() -> "echo before; sleep 300 & echo \"$$ $!\" > /workspace/pids; wait",
                        ExecutionOptions.builder().timeout(Duration.ofMinutes(5)).background(true)
                                .cancellation(source.token()).build());
            } catch (ShellExecutionException e) {
                return e;
            }
        });
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (bash(env, "cat /workspace/pids 2>/dev/null").stdout().isBlank() && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        final String pids = bash(env, "cat /workspace/pids").stdout().strip();

        source.cancel();
        final Object outcome = background.get(30, TimeUnit.SECONDS);
        // execd sends SIGKILL three seconds after its SIGTERM; both processes take the SIGTERM.
        Thread.sleep(500);
        final ShellCommandResult alive = bash(env, "for p in " + pids + "; do kill -0 $p 2>/dev/null && echo $p; done");

        assertThat(env.shell().supports(ShellFeature.CANCELLATION)).isTrue();
        assertThat(pids.split(" ")).hasSize(2);
        assertThat(outcome).isInstanceOfSatisfying(ShellCancelledException.class,
                e -> assertThat(e.stdout()).isEqualTo("before\n"));
        assertThat(alive.stdout()).as("the command and its child are gone").isEmpty();
    }

    @Test
    @DisplayName("§10.4: a node restarted with an empty InMemory store reclaims its old sandbox after orphan-grace")
    void reconciliationReclaimsTheOrphanOfARestartedNode() throws Exception {
        final WorkspaceSandbox before = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        bash(mainTurn(before, session), "true");
        final ProviderSandboxRef orphan = before.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();

        final WorkspaceSandbox after = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        after.janitor().runOnce();
        assertThat(provider.status(orphan)).as("within orphan-grace").isPresent();

        // The manual clock stood still while the server's did not: the age is measured by the later of the two.
        clock.advance(Duration.ofMinutes(11));
        after.janitor().runOnce();
        assertThat(provider.status(orphan)).as("after orphan-grace").isEmpty();
    }

    @Test
    @DisplayName("§12.2: an out-of-memory command dies inside its sandbox; the sandbox and its neighbours answer")
    void resourceLimitsHoldInsideTheSandbox() throws Exception {
        final WorkspaceSandbox assembly = assembly(
                profile().resources(ResourceSpec.of("1", "256Mi", null, null)).build(),
                new InMemorySandboxWorkspaceStore());
        final ExecutionEnvironment env = mainTurn(assembly, SessionId.generate());
        final ExecutionEnvironment neighbour = mainTurn(assembly, SessionId.generate());

        final ShellCommandResult pids = bash(env, "cat /sys/fs/cgroup/pids.max");
        final ShellCommandResult oom = bash(env, "head -c 600000000 /dev/zero | tail -n 1 > /dev/null; echo rc=$?");

        assertThat(pids.stdout().strip()).as("the server's pids_limit").isEqualTo("4096");
        assertThat(oom.stdout()).isEqualTo("rc=137\n");
        assertThat(bash(env, "echo alive").stdout()).isEqualTo("alive\n");
        assertThat(bash(neighbour, "echo neighbour").stdout()).isEqualTo("neighbour\n");
    }

    @Test
    @DisplayName("§6.1: output arrives line-normalized — a lone CR becomes a line break, a final newline is added")
    void outputIsLineNormalized() throws Exception {
        final WorkspaceSandbox assembly = assembly(profile().build(), new InMemorySandboxWorkspaceStore());
        final SessionId session = SessionId.generate();
        bash(mainTurn(assembly, session), "true");
        final ProviderSandboxRef ref = assembly.store().find(SandboxWorkspaceId.of("ws:" + session.value()))
                .orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();

        try (SandboxConnection connection = provider.connect(ref)) {
            final ExecOutcome outcome = connection.run(
                    ExecSpec.builder().command("printf 'x\\ry'; printf 'A\\r\\nB\\n\\nC'; printf '\\377' >&2").build(),
                    OutputSink.DISCARD).await(Duration.ofSeconds(30));

            assertThat(new String(outcome.stdout(), StandardCharsets.UTF_8)).isEqualTo("x\nyA\nB\n\nC\n");
            assertThat(new String(outcome.stderr(), StandardCharsets.UTF_8)).isEqualTo("�\n");
        }
    }
}
