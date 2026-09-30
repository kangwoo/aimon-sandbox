package at.aimon.sandbox.opensandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.CredentialScope;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxLabels;

/**
 * Egress on the real server (docs/design/workspace-sandbox.md §12.1, §16): {@code dns+nft} with a deny default blocks
 * names, addresses, the metadata address and direct DNS; {@code verify} confirms the mode the server applied, and
 * notices a server that runs {@code dns} while the provider declares {@code dns+nft}; the vault receives the named
 * bindings without the secret reaching the sandbox. Rows that need the internet are assumption-gated on an
 * unrestricted sandbox reaching it first — without that control, "blocked" would prove nothing.
 */
@Tag("docker")
class OpenSandboxEgressIT {

    private static final Duration COMMAND = Duration.ofSeconds(30);

    private final List<OpenSandboxProvider> providers = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void close() {
        cleanups.forEach(Runnable::run);
        providers.forEach(OpenSandboxProvider::close);
    }

    private OpenSandboxProvider provider(OpenSandboxProviderConfig config) {
        final OpenSandboxProvider provider = new OpenSandboxProvider(config);
        providers.add(provider);
        return provider;
    }

    private ProviderSandboxRef create(OpenSandboxProvider provider, List<String> egress, List<String> credentials) {
        final String workspace = "ws:" + UUID.randomUUID();
        final ProviderSandboxRef ref = provider.create(CreateSpec.builder()
                .key(SandboxLabels.key(OpenSandboxTestServer.DEPLOYMENT, workspace, "inc", "primary", 1))
                .image(OpenSandboxTestServer.sandboxImage()).egress(egress).credentials(credentials)
                .labels(SandboxLabels.labels(OpenSandboxTestServer.DEPLOYMENT, workspace, "inc", "primary", 1, "t"))
                .expiresAt(Instant.now().plus(Duration.ofMinutes(20))).build());
        cleanups.add(() -> provider.destroy(ref));
        return ref;
    }

    private static String run(SandboxConnection connection, String command) throws InterruptedException {
        final ExecOutcome outcome = connection
                .run(ExecSpec.builder().command(command).timeout(COMMAND).build(), OutputSink.DISCARD).await(COMMAND);
        return new String(outcome.stdout(), StandardCharsets.UTF_8).strip();
    }

    /** {@code 000} when nothing answered. */
    private static String http(SandboxConnection connection, String url) throws InterruptedException {
        return run(connection, "curl -s -o /dev/null -m 5 -w '%{http_code}' " + url + " || true");
    }

    @Test
    @DisplayName("§12.1: dns+nft deny-all blocks names, addresses, the metadata address and direct DNS")
    void denyAllBlocksEverything() throws Exception {
        final OpenSandboxProvider provider = provider(OpenSandboxTestServer.dnsNft().config().build());
        final ProviderSandboxRef open = create(provider, null, List.of());
        try (SandboxConnection control = provider.connect(open)) {
            assumeTrue(!"000".equals(http(control, "http://1.1.1.1")),
                    "an unrestricted sandbox cannot reach the internet either: the blocked rows prove nothing here");
        }
        final ProviderSandboxRef denied = create(provider, List.of(), List.of());

        assertThat(provider.verify(denied, Set.of(Capability.EGRESS_POLICY))).isEmpty();
        try (SandboxConnection connection = provider.connect(denied)) {
            assertThat(http(connection, "https://example.com")).isEqualTo("000");
            assertThat(http(connection, "http://1.1.1.1")).isEqualTo("000");
            assertThat(http(connection, "http://169.254.169.254/")).isEqualTo("000");
            assertThat(run(connection, "dig +short +time=2 +tries=1 @8.8.8.8 example.com || true"))
                    .doesNotMatch("(?s).*\\d+\\.\\d+\\.\\d+\\.\\d+.*");
        }

    }

    @Test
    @DisplayName("§12.1: an allowed name is reachable, an address it did not resolve to is not")
    void anAllowedNameIsReachableAndNothingElse() throws Exception {
        final OpenSandboxProvider provider = provider(OpenSandboxTestServer.dnsNft().config().build());
        final ProviderSandboxRef open = create(provider, null, List.of());
        try (SandboxConnection control = provider.connect(open)) {
            assumeTrue(!"000".equals(http(control, "https://example.com")), "no internet access from sandboxes");
        }
        final ProviderSandboxRef allowed = create(provider, List.of("example.com"), List.of());
        try (SandboxConnection connection = provider.connect(allowed)) {
            assertThat(http(connection, "https://example.com")).isNotEqualTo("000");
            assertThat(http(connection, "http://1.1.1.1")).isEqualTo("000");
        }
    }

    @Test
    @DisplayName("§6.4: a server enforcing dns while the provider declares dns+nft fails verification (step egress)")
    void verifyNoticesAServerThatOnlyFiltersDns() {
        final OpenSandboxProvider provider = provider(OpenSandboxTestServer.dnsOnly().config().build());
        final ProviderSandboxRef ref = create(provider, List.of(), List.of());

        assertThat(provider.verify(ref, Set.of(Capability.EGRESS_POLICY))).singleElement().satisfies(failure -> {
            assertThat(failure.step()).isEqualTo("egress");
            assertThat(failure.reason()).contains("'dns'");
        });
    }

    @Test
    @DisplayName("§12.1: the vault receives the profile's bindings; the secret never reaches the sandbox")
    void credentialBindingsReachTheVaultNotTheSandbox() throws Exception {
        final String secret = "s3cr3t-" + UUID.randomUUID();
        final OpenSandboxProvider provider = provider(OpenSandboxTestServer.dnsNft().config()
                .credentials(Map.of("example-read",
                        CredentialDefinition.of(
                                CredentialScope.builder().hosts(Set.of("example.com")).methods(Set.of("GET"))
                                        .paths(List.of("/*")).build(),
                                CredentialDefinition.Auth.apiKey("X-Api-Key"), () -> secret),
                        "unused", CredentialDefinition.of(CredentialScope.builder().hosts(Set.of("other.org")).build(),
                                CredentialDefinition.Auth.bearer(), () -> "never-sent")))
                .build());
        final ProviderSandboxRef ref = create(provider, List.of("example.com"), List.of("example-read"));

        assertThat(provider.verify(ref, Set.of(Capability.EGRESS_POLICY, Capability.CREDENTIAL_INJECTION))).isEmpty();
        try (SandboxConnection connection = provider.connect(ref)) {
            assertThat(run(connection, "env; cat /proc/1/environ 2>/dev/null | tr '\\0' '\\n'")).doesNotContain(secret);
        }
    }
}
