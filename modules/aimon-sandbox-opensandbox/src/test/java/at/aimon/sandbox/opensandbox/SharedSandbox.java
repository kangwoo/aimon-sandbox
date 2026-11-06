package at.aimon.sandbox.opensandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.WorkspaceSandbox;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.testkit.ManualClock;
import at.aimon.sandbox.testkit.ManualScheduler;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.WorkspaceScan;

/**
 * One workspace sandbox on the docker tier's OpenSandbox server, provisioned and seeded once and shared by every test
 * of a class — the classes of the shell engine (docs/design/ptc-shell-provider.md §5.7) would otherwise create a
 * sandbox per test. Its profile declares {@code python3}, which the tier's image provides.
 */
final class SharedSandbox implements AutoCloseable {

    static final Principal ALICE = Principal.user("alice");
    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("opensandbox-ptc-it");

    final OpenSandboxProvider provider;
    final WorkspaceSandbox assembly;
    final ExecutionEnvironment environment;

    private SharedSandbox(Set<String> runtimes) {
        this.provider = new OpenSandboxProvider(OpenSandboxTestServer.dnsNft().config().build());
        final SandboxProfile profile = SandboxProfile.builder().name("ptc").image(OpenSandboxTestServer.sandboxImage())
                .platform("linux").terminateAfter(Duration.ofHours(1)).runtimes(runtimes)
                .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION)).build();
        final SandboxSettings settings = SandboxSettings.builder().deployment(OpenSandboxTestServer.DEPLOYMENT)
                .nodeId("it-" + UUID.randomUUID()).profiles(List.of(profile)).defaultProfile(profile.name())
                .closeWait(Duration.ofSeconds(20)).build();
        // A manual clock and scheduler, as OpenSandboxWorkspaceIT: no idle policy acts while the class runs.
        final ManualClock clock = new ManualClock(Instant.now());
        this.assembly = WorkspaceSandbox.builder().settings(settings).provider(provider)
                .store(new InMemorySandboxWorkspaceStore()).clock(clock).scheduler(new ManualScheduler(clock)).build();
        this.environment = mainTurn();
    }

    /**
     * @return a shared sandbox whose profile declares {@code python3}, already provisioned and seeded — so its seed
     *         ran the {@code runtime-python3} check, and no test pays the creation inside its own timeout
     */
    static SharedSandbox started() throws Exception {
        final SharedSandbox sandbox = new SharedSandbox(Set.of("python3"));
        try {
            sandbox.bash("true");
        } catch (Exception | Error e) {
            sandbox.close();
            throw e;
        }
        return sandbox;
    }

    /** @return another main-turn environment, of a new session: a new sandbox once it is used */
    ExecutionEnvironment mainTurn() {
        return ExecutionEnvironments.resolveOrUnavailable(assembly.environmentProvider(), EnvironmentRequest.builder()
                .agentRuntimeId(RUNTIME).sessionId(SessionId.generate()).principal(ALICE).build());
    }

    ShellCommandResult bash(String command) throws Exception {
        return environment.shell().execute(() -> command,
                ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).build());
    }

    @Override
    public void close() {
        try {
            assembly.store().scan(WorkspaceScan.builder().build())
                    .forEach(record -> assembly.manager().close(record.id(), ALICE));
            assembly.close();
        } finally {
            provider.close();
        }
    }
}
