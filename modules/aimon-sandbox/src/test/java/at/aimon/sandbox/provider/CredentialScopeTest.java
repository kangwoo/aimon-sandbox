package at.aimon.sandbox.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** {@link CredentialScope}'s overlap rule (§13.2), and the value types that carry provider declarations. */
class CredentialScopeTest {

    private static CredentialScope scope(String host, String method, String path) {
        return CredentialScope.builder().hosts(Set.of(host)).methods(Set.of(method)).paths(List.of(path)).build();
    }

    @Test
    void defaultsFollowTheVault() {
        final CredentialScope scope = CredentialScope.builder().hosts(Set.of("GitHub.com")).build();

        assertThat(scope.schemes()).containsExactly("https");
        assertThat(scope.hosts()).containsExactly("github.com");
        assertThat(scope.methods()).containsExactlyInAnyOrderElementsOf(CredentialScope.DEFAULT_METHODS);
        assertThat(scope.paths()).containsExactly("/*");
        assertThatThrownBy(() -> CredentialScope.builder().build()).hasMessageContaining("at least one host");
        assertThatThrownBy(() -> CredentialScope.builder().hosts(Set.of("h")).paths(List.of("x")).build())
                .hasMessageContaining("must start with /");
    }

    @Test
    void scopesOverlapOnlyWhenEveryDimensionDoes() {
        assertThat(scope("github.com", "GET", "/repos/*").overlaps(scope("github.com", "get", "/repos/a"))).isTrue();
        assertThat(scope("github.com", "GET", "/repos/*").overlaps(scope("github.com", "POST", "/repos/*"))).isFalse();
        assertThat(scope("github.com", "GET", "/repos/*").overlaps(scope("gitlab.com", "GET", "/repos/*"))).isFalse();
        assertThat(scope("github.com", "GET", "/repos/*").overlaps(scope("github.com", "GET", "/user"))).isFalse();
        final CredentialScope http = CredentialScope.builder().schemes(Set.of("http")).hosts(Set.of("github.com"))
                .build();
        assertThat(http.overlaps(CredentialScope.builder().hosts(Set.of("github.com")).build())).isFalse();
    }

    @Test
    void wildcardHostsOverlapTheHostsUnderThem() {
        assertThat(CredentialScope.hostsOverlap("*.example.com", "api.example.com")).isTrue();
        assertThat(CredentialScope.hostsOverlap("api.example.com", "*.example.com")).isTrue();
        assertThat(CredentialScope.hostsOverlap("*.example.com", "example.com")).isFalse();
        assertThat(CredentialScope.hostsOverlap("*.example.com", "*.a.example.com")).isTrue();
        assertThat(CredentialScope.hostsOverlap("*.a.example.com", "*.example.com")).isTrue();
        assertThat(CredentialScope.hostsOverlap("*.a.com", "*.b.com")).isFalse();
    }

    @Test
    void prefixPathsOverlapWhatStartsWithThem() {
        assertThat(CredentialScope.pathsOverlap("/a/*", "/a/b")).isTrue();
        assertThat(CredentialScope.pathsOverlap("/a/b", "/a/*")).isTrue();
        assertThat(CredentialScope.pathsOverlap("/a/*", "/a/b/*")).isTrue();
        assertThat(CredentialScope.pathsOverlap("/a/*", "/b/*")).isFalse();
        assertThat(CredentialScope.pathsOverlap("/a", "/a")).isTrue();
        assertThat(CredentialScope.pathsOverlap("/a", "/ab")).isFalse();
    }

    @Test
    void hostPortParsesAndPrints() {
        assertThat(HostPort.parse("svc.ns.svc:80")).isEqualTo(HostPort.of("svc.ns.svc", 80));
        assertThat(HostPort.parse("[::1]:8090").host()).isEqualTo("::1");
        assertThat(HostPort.of("::1", 8090)).hasToString("[::1]:8090");
        assertThatThrownBy(() -> HostPort.parse("no-port")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPort.parse("h:x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPort.of("h", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capabilitiesCarryTheDeclarationsAndCompareByValue() {
        final ProviderCapabilities built = ProviderCapabilities.builder().advertised(Set.of(Capability.EXEC))
                .maxExpiry(Duration.ofHours(1)).runtimeClass("kata")
                .credentialScopes(Map.of("gh", scope("github.com", "GET", "/*")))
                .controlPlaneEndpoints(List.of(HostPort.of("h", 1))).build();

        assertThat(built.runtimeClass()).contains("kata");
        assertThat(built.credentialScopes()).containsOnlyKeys("gh");
        assertThat(built.controlPlaneEndpoints()).containsExactly(HostPort.of("h", 1));
        assertThat(built).isEqualTo(
                ProviderCapabilities.builder().advertised(Set.of(Capability.EXEC)).maxExpiry(Duration.ofHours(1))
                        .runtimeClass("kata").credentialScopes(Map.of("gh", scope("github.com", "GET", "/*")))
                        .controlPlaneEndpoints(List.of(HostPort.of("h", 1))).build())
                .hasSameHashCodeAs(built);
        assertThat(built.toString()).contains("kata").contains("[gh]").doesNotContain("github.com");
        assertThat(ProviderCapabilities.of(Set.of(), null).runtimeClass()).isEmpty();
        assertThat(VerificationFailure.of("egress", "dns")).hasToString("egress: dns")
                .isEqualTo(VerificationFailure.of("egress", "dns"));
    }
}
