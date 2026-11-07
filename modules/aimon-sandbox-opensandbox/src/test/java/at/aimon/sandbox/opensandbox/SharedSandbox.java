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
import at.aimon.sandbox.provider.ResourceSpec;
import at.aimon.sandbox.testkit.ManualClock;
import at.aimon.sandbox.testkit.ManualScheduler;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.WorkspaceScan;

/**
 * One workspace sandbox on the docker tier's OpenSandbox server, or the k8s tier's in template or pool mode,
 * provisioned and seeded once and shared by every test of a class — the classes of the shell engine
 * (docs/design/ptc-shell-provider.md §5.7) would otherwise create a sandbox per test. Its profile declares
 * {@code python3}, which the tiers' image provides.
 */
final class SharedSandbox implements AutoCloseable {

    static final Principal ALICE = Principal.user("alice");
    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("opensandbox-ptc-it");

    final OpenSandboxProvider provider;
    final WorkspaceSandbox assembly;
    final ExecutionEnvironment environment;

    private SharedSandbox(String image, Set<String> runtimes) {
        this(new OpenSandboxProvider(OpenSandboxTestServer.dnsNft().config().build()), OpenSandboxTestServer.DEPLOYMENT,
                SandboxProfile.builder().name("ptc").image(image).platform("linux").terminateAfter(Duration.ofHours(1))
                        .runtimes(runtimes)
                        .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION))
                        .build());
    }

    private SharedSandbox(OpenSandboxProvider provider, String deployment, SandboxProfile profile) {
        this.provider = provider;
        final SandboxSettings settings = SandboxSettings.builder().deployment(deployment)
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
        return started(new SharedSandbox(OpenSandboxTestServer.sandboxImage(), Set.of("python3")));
    }

    /**
     * @return the k8s tier's template mode (docs/design/workspace-sandbox.md §16): a profile without waivers — deny-all
     *         egress, the declared runtime class, hardened — that declares {@code python3}, started
     */
    static SharedSandbox k8sTemplate() throws Exception {
        return started(new SharedSandbox(new OpenSandboxProvider(K8sTier.config().build()), k8sDeployment(),
                SandboxProfile.builder().name("ptc-k8s").image(K8sTier.image()).platform("linux")
                        .resources(ResourceSpec.of("500m", "512Mi", null, null)).egress(List.of())
                        .runtimeClass(K8sTier.runtimeClass()).terminateAfter(Duration.ofHours(1))
                        .runtimes(Set.of("python3")).build()));
    }

    /**
     * @param pool
     *            the {@code Pool} resource the server takes the sandbox from
     * @return the k8s tier's pool mode: the same profile without egress (the server refuses a network policy with a
     *         pool) and without a runtime class (a pool's pods get none), its image the pool's — started
     */
    static SharedSandbox k8sPool(String pool) throws Exception {
        return started(new SharedSandbox(new OpenSandboxProvider(K8sTier.poolConfig(pool).build()), k8sDeployment(),
                SandboxProfile.builder().name("ptc-k8s-pool").image(K8sTier.image()).platform("linux")
                        .resources(ResourceSpec.of("500m", "512Mi", null, null)).terminateAfter(Duration.ofHours(1))
                        .runtimes(Set.of("python3")).build()));
    }

    private static String k8sDeployment() {
        return "k8s-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static SharedSandbox started(SharedSandbox sandbox) throws Exception {
        try {
            sandbox.bash("true");
        } catch (Exception | Error e) {
            sandbox.close();
            throw e;
        }
        return sandbox;
    }

    /**
     * @param image
     *            the sandbox image
     * @return a sandbox whose profile declares {@code python3} on {@code image}, not provisioned yet: its first use
     *         creates and seeds it
     */
    static SharedSandbox unstarted(String image) {
        return new SharedSandbox(image, Set.of("python3"));
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
