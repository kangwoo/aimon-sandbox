package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.WorkspaceSandbox;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.HostPort;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ResourceSpec;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxLabels;

/**
 * The Kubernetes-runtime checks of docs/design/workspace-sandbox.md §16 — manual and pre-release, never part of
 * {@code build}: {@code ./gradlew :aimon-sandbox-opensandbox:k8sTest} against a cluster provisioned as in the module
 * README (kind + the OpenSandbox chart, the hardened container-level template, the sandbox-isolation NetworkPolicy of
 * {@code spike/opensandbox/k8s/deploy/}), reached through {@code kubectl port-forward} and the server proxy. Every test
 * skips when {@code OPENSANDBOX_K8S_ENDPOINT} is unset.
 *
 * <ul>
 * <li>{@code OPENSANDBOX_K8S_ENDPOINT}, {@code OPENSANDBOX_K8S_API_KEY} — the forwarded server;</li>
 * <li>{@code OPENSANDBOX_K8S_IMAGE} — a sandbox image meeting the image contract, pullable by the cluster;</li>
 * <li>{@code OPENSANDBOX_K8S_CONTROL_PLANE} — {@code host:port} of the server as a sandbox would reach it
 * (default {@code opensandbox-server.opensandbox-system.svc:80});</li>
 * <li>{@code OPENSANDBOX_K8S_RUNTIME_CLASS} — the server's runtime class, when one is configured.</li>
 * </ul>
 */
@Tag("k8s")
class OpenSandboxK8sIT {

    private static final Duration COMMAND = Duration.ofSeconds(60);

    private OpenSandboxProvider provider;
    private final List<ProviderSandboxRef> created = new ArrayList<>();
    private final String deployment = "k8s-" + UUID.randomUUID().toString().substring(0, 8);

    private static String env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(v -> !v.isBlank()).orElse(null);
    }

    private static HostPort controlPlane() {
        return HostPort.parse(Optional.ofNullable(env("OPENSANDBOX_K8S_CONTROL_PLANE"))
                .orElse("opensandbox-server.opensandbox-system.svc:80"));
    }

    private static OpenSandboxProviderConfig.Builder config() {
        final OpenSandboxProviderConfig.Builder config = OpenSandboxProviderConfig.builder()
                .endpoint(URI.create(env("OPENSANDBOX_K8S_ENDPOINT")))
                .apiKey(() -> Optional.ofNullable(env("OPENSANDBOX_K8S_API_KEY")).orElse(""))
                .runtime(OpenSandboxProviderConfig.Runtime.KUBERNETES).maxExpiry(Duration.ofHours(1))
                .useServerProxy(true).egressEnforcement(OpenSandboxProviderConfig.EgressEnforcement.DNS_NFT)
                .networkIsolation(OpenSandboxProviderConfig.Declaration.DECLARED)
                .hardenedSecurityContext(OpenSandboxProviderConfig.Declaration.DECLARED)
                .controlPlaneProbes(List.of(controlPlane())).defaultResources("250m", "256Mi");
        Optional.ofNullable(env("OPENSANDBOX_K8S_RUNTIME_CLASS")).ifPresent(name -> config.runtimeClass(
                OpenSandboxProviderConfig.RuntimeClass.of(name, OpenSandboxProviderConfig.RuntimeKind.OTHER)));
        return config;
    }

    private static String image() {
        return Optional.ofNullable(env("OPENSANDBOX_K8S_IMAGE")).orElse("ghcr.io/kangwoo/aimon-sandbox-runtime:1");
    }

    @BeforeEach
    void requireCluster() {
        assumeTrue(env("OPENSANDBOX_K8S_ENDPOINT") != null,
                "OPENSANDBOX_K8S_ENDPOINT is not set: the k8s tier needs a provisioned cluster (see the README)");
        provider = new OpenSandboxProvider(config().build());
    }

    @AfterEach
    void cleanUp() {
        if (provider != null) {
            created.forEach(provider::destroy);
            provider.close();
        }
    }

    private ProviderSandboxRef create(List<String> egress) {
        final String workspace = "ws:" + UUID.randomUUID();
        final ProviderSandboxRef ref = provider
                .create(CreateSpec.builder().key(SandboxLabels.key(deployment, workspace, "inc", "primary", 1))
                        .image(image()).egress(egress).resources(ResourceSpec.of("250m", "256Mi", null, null))
                        .runtimeClass(env("OPENSANDBOX_K8S_RUNTIME_CLASS"))
                        .labels(SandboxLabels.labels(deployment, workspace, "inc", "primary", 1, "t"))
                        .expiresAt(Instant.now().plus(Duration.ofMinutes(20))).build());
        created.add(ref);
        return ref;
    }

    private static String run(SandboxConnection connection, String command) throws InterruptedException {
        final ExecOutcome outcome = connection
                .run(ExecSpec.builder().command(command).timeout(COMMAND).build(), OutputSink.DISCARD).await(COMMAND);
        return new String(outcome.stdout(), StandardCharsets.UTF_8).strip();
    }

    @Test
    @DisplayName("§6.3: the labels land on the sandbox and list finds it by them")
    void labelsAreAcceptedAndListed() {
        final ProviderSandboxRef ref = create(List.of());

        assertThat(provider.list(Map.of(SandboxLabels.DEPLOYMENT, deployment))).extracting(s -> s.ref()).contains(ref);
    }

    @Test
    @DisplayName("§13.3: the hardened template is what the sandbox runs under")
    void theSandboxRunsHardened() throws Exception {
        try (SandboxConnection connection = provider.connect(create(List.of()))) {
            assertThat(run(connection, "id -u")).isNotEqualTo("0");
            assertThat(run(connection, "grep NoNewPrivs /proc/self/status")).endsWith("1");
            assertThat(run(connection, "grep CapEff /proc/self/status")).endsWith("0000000000000000");
            assertThat(run(connection, "test -e /var/run/secrets/kubernetes.io/serviceaccount/token; echo $?"))
                    .isEqualTo("1");
        }
    }

    @Test
    @DisplayName("§11.3: a profile without waivers seeds: not root, no token, the control plane unreachable")
    void aProfileWithoutWaiversSeeds() throws Exception {
        final SandboxProfile profile = SandboxProfile.builder().name("k8s").image(image()).platform("linux")
                .resources(ResourceSpec.of("250m", "256Mi", null, null)).egress(List.of())
                .runtimeClass(env("OPENSANDBOX_K8S_RUNTIME_CLASS")).terminateAfter(Duration.ofMinutes(30)).build();
        try (WorkspaceSandbox sandbox = WorkspaceSandbox.builder().settings(SandboxSettings.builder()
                .deployment(deployment).profiles(List.of(profile)).defaultProfile("k8s").build()).provider(provider)
                .build()) {
            final var env = ExecutionEnvironments.resolveOrUnavailable(sandbox.environmentProvider(),
                    EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("k8s-it"))
                            .sessionId(SessionId.generate()).principal(Principal.user("alice")).build());

            assertThat(env.shell().execute(() -> "echo seeded", ExecutionOptions.builder().timeout(COMMAND).build())
                    .stdout()).isEqualTo("seeded\n");
            sandbox.store().scan(at.aimon.sandbox.workspace.WorkspaceScan.builder().build())
                    .forEach(record -> sandbox.manager().close(record.id(), Principal.user("alice")));
        }
    }

    @Test
    @DisplayName("§12.1: east-west is blocked — a sandbox cannot reach a peer's execd")
    void aPeerIsUnreachable() throws Exception {
        final ProviderSandboxRef peer = create(null);
        final String peerIp;
        try (SandboxConnection connection = provider.connect(peer)) {
            peerIp = run(connection, "hostname -i | cut -d' ' -f1");
        }
        try (SandboxConnection connection = provider.connect(create(List.of()))) {
            assertThat(run(connection,
                    "curl -s -o /dev/null -m 5 -w '%{http_code}' http://" + peerIp + ":44772/ping || true"))
                    .isEqualTo("000");
        }
    }

    @Test
    @DisplayName("§6.4: the runtime class is the one the server declares (needs kubectl on PATH)")
    void theDeclaredRuntimeClassIsUsed() throws Exception {
        final String declared = env("OPENSANDBOX_K8S_RUNTIME_CLASS");
        assumeTrue(declared != null, "OPENSANDBOX_K8S_RUNTIME_CLASS is not set");
        final ProviderSandboxRef ref = create(List.of());
        final Process kubectl;
        try {
            kubectl = new ProcessBuilder("kubectl", "get", "pods", "-A", "-l", "opensandbox.io/id=" + ref.sandboxId(),
                    "-o", "jsonpath={.items[0].spec.runtimeClassName}").redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue(false, "kubectl is not on PATH");
            return;
        }
        assertThat(kubectl.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(new String(kubectl.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip())
                .isEqualTo(declared);
    }

    @Test
    @DisplayName("§6.4: dns+nft is what the server enforces")
    void egressIsEnforcedWithDnsNft() {
        final ProviderSandboxRef ref = create(List.of());

        assertThat(provider.verify(ref, Set.of(at.aimon.sandbox.provider.Capability.EGRESS_POLICY))).isEmpty();
    }

    @Test
    @DisplayName("§12.1: the vault injects only within its scope, and TLS verification stays on (Q4)")
    void credentialsAreInjectedOnlyWithinTheirScope() throws Exception {
        assumeTrue("1".equals(env("OPENSANDBOX_K8S_INTERNET")), "OPENSANDBOX_K8S_INTERNET=1 is not set");
        final String secret = "s3cr3t-" + UUID.randomUUID();
        try (OpenSandboxProvider vaulted = new OpenSandboxProvider(config().credentials(Map.of("httpbin-headers",
                CredentialDefinition.of(
                        at.aimon.sandbox.provider.CredentialScope.builder().hosts(Set.of("httpbin.org"))
                                .methods(Set.of("GET")).paths(List.of("/headers")).build(),
                        CredentialDefinition.Auth.apiKey("X-Api-Key"), () -> secret)))
                .build())) {
            final String workspace = "ws:" + UUID.randomUUID();
            final ProviderSandboxRef ref = vaulted
                    .create(CreateSpec.builder().key(SandboxLabels.key(deployment, workspace, "inc", "primary", 1))
                            .image(image()).egress(List.of("httpbin.org")).credentials(List.of("httpbin-headers"))
                            .resources(ResourceSpec.of("250m", "256Mi", null, null))
                            .labels(SandboxLabels.labels(deployment, workspace, "inc", "primary", 1, "t"))
                            .expiresAt(Instant.now().plus(Duration.ofMinutes(20))).build());
            created.add(ref);
            try (SandboxConnection connection = vaulted.connect(ref)) {
                // curl without -k: the sandbox must trust the egress proxy's CA for injection to work at all.
                assertThat(run(connection, "curl -s -m 20 https://httpbin.org/headers")).contains(secret);
                assertThat(run(connection, "curl -s -m 20 -X POST https://httpbin.org/anything/push"))
                        .doesNotContain(secret);
                assertThat(run(connection, "env")).doesNotContain(secret);
            }
        }
    }

    @Test
    @DisplayName("§12.1: a model's git over HTTPS works where a credential is injected, under the hardened template")
    void gitOverHttpsWorksUnderInjection() throws Exception {
        assumeTrue("1".equals(env("OPENSANDBOX_K8S_INTERNET")), "OPENSANDBOX_K8S_INTERNET=1 is not set");
        final SandboxProfile profile = SandboxProfile.builder().name("k8s").image(image()).platform("linux")
                .resources(ResourceSpec.of("250m", "256Mi", null, null)).egress(List.of("github.com"))
                .credentials(List.of("github-read")).runtimeClass(env("OPENSANDBOX_K8S_RUNTIME_CLASS"))
                .terminateAfter(Duration.ofMinutes(30)).build();
        try (OpenSandboxProvider vaulted = new OpenSandboxProvider(config().credentials(Map.of("github-read",
                CredentialDefinition.of(
                        at.aimon.sandbox.provider.CredentialScope.builder().hosts(Set.of("github.com"))
                                .methods(Set.of("GET", "POST")).paths(List.of("/octocat/*")).build(),
                        CredentialDefinition.Auth.apiKey("X-Probe"), () -> "probe")))
                .build());
                WorkspaceSandbox sandbox = WorkspaceSandbox.builder().settings(SandboxSettings.builder()
                        .deployment(deployment).profiles(List.of(profile)).defaultProfile("k8s").build())
                        .provider(vaulted).build()) {
            final var env = ExecutionEnvironments.resolveOrUnavailable(sandbox.environmentProvider(),
                    EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("k8s-it"))
                            .sessionId(SessionId.generate()).principal(Principal.user("alice")).build());
            try {
                // The egress proxy intercepts TLS, and execd names its CA bundle in SSL_CERT_FILE only. git on
                // GnuTLS (Debian's) ignores that variable; the shell wrapper hands it to git as GIT_SSL_CAINFO.
                assertThat(env.shell()
                        .execute(() -> "git ls-remote https://github.com/octocat/Hello-World.git HEAD 2>&1",
                                ExecutionOptions.builder().timeout(COMMAND).build())
                        .stdout()).matches("[0-9a-f]{40}\\s+HEAD\\s*");
            } finally {
                sandbox.store().scan(at.aimon.sandbox.workspace.WorkspaceScan.builder().build())
                        .forEach(record -> sandbox.manager().close(record.id(), Principal.user("alice")));
            }
        }
    }
}
