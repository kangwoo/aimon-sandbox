package at.aimon.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.profile.SeedSpec;
import at.aimon.sandbox.profile.SharedAccess;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.ProviderCapabilities;
import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/** One case per startup rule (§13.2, and implementation step 3's own refusals). */
class SandboxStartupValidatorTest {

    private static final ProviderCapabilities LOCAL = ProviderCapabilities
            .of(EnumSet.of(Capability.EXEC, Capability.FILES, Capability.EXPIRY), Duration.ofHours(24));

    private static List<String> violations(SandboxSettings settings, ProviderCapabilities capabilities, boolean tenants,
            boolean owners) {
        try {
            SandboxStartupValidator.validate(settings,
                    new SandboxStartupValidator.Wiring(capabilities, tenants, owners));
            return List.of();
        } catch (SandboxConfigurationException e) {
            return e.violations();
        }
    }

    private static List<String> violations(SandboxProfile profile) {
        return violations(SandboxTestProfiles.settings(profile).build(), LOCAL, false, false);
    }

    private static SandboxProfile.Builder local() {
        return SandboxTestProfiles.local("p");
    }

    @Test
    void aValidConfigurationPasses() {
        assertThatNoException().isThrownBy(
                () -> SandboxStartupValidator.validate(SandboxTestProfiles.settings(local().build()).build(),
                        new SandboxStartupValidator.Wiring(LOCAL, false, false)));
    }

    @Test
    void deploymentIsRequiredAndLabelSafe() {
        assertThat(
                violations(SandboxTestProfiles.settings(local().build()).deployment(null).build(), LOCAL, false, false))
                .anyMatch(v -> v.contains("deployment is required"));
        assertThat(violations(SandboxTestProfiles.settings(local().build()).deployment("Acme Prod").build(), LOCAL,
                false, false)).anyMatch(v -> v.contains("not label-safe"));
    }

    @Test
    void terminateAfterIsRequiredAndWithinTheProvidersMaxExpiry() {
        assertThat(violations(local().terminateAfter(null).build()))
                .anyMatch(v -> v.contains("terminate-after is " + "required"));
        assertThat(violations(local().terminateAfter(Duration.ofHours(25)).build()))
                .anyMatch(v -> v.contains("exceeds the provider's max-expiry"));
        assertThat(violations(local().terminateAfter(Duration.ZERO).build()))
                .anyMatch(v -> v.contains("terminate-after must be positive"));
    }

    @Test
    void pauseAfterIsRejectedUntilStepSeven() {
        assertThat(violations(local().pauseAfter(Duration.ofMinutes(5)).build()))
                .anyMatch(v -> v.contains("pause-after requires PAUSE_RESUME"));
    }

    @Test
    void sharedAccessSeedAndCredentialsAreRejectedInStepThree() {
        assertThat(violations(local().sharedAccess(SharedAccess.RW).build()))
                .anyMatch(v -> v.contains("shared-access rw"));
        assertThat(violations(local().seed(SeedSpec.git("https://x/r.git", null, null)).build()))
                .anyMatch(v -> v.contains("seed is not supported"));
        assertThat(violations(local().credentials(List.of("gh")).build()))
                .anyMatch(v -> v.contains("credential bindings arrive with implementation step 4"));
    }

    @Test
    void requiredCapabilitiesMustBeAdvertised() {
        final List<String> found = violations(
                SandboxProfile.builder().name("p").image("i").terminateAfter(Duration.ofHours(1)).build());

        assertThat(found)
                .anyMatch(v -> v.contains("does not advertise [NETWORK_ISOLATION, " + "HARDENED_SECURITY_CONTEXT]")
                        || v.contains("does not advertise [HARDENED_SECURITY_CONTEXT, " + "NETWORK_ISOLATION]"));
    }

    @Test
    void insecureAllowCannotWaiveNonIsolationCapabilities() {
        assertThat(violations(local().insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT,
                Capability.NETWORK_ISOLATION, Capability.CREDENTIAL_INJECTION)).build()))
                .anyMatch(v -> v.contains("insecure-allow may only waive"));
    }

    @Test
    void requirePrincipalNeedsATenantResolverAndASessionOwnerLookup() {
        final SandboxSettings settings = SandboxTestProfiles.settings(local().build()).requirePrincipal(true).build();

        assertThat(violations(settings, LOCAL, false, false)).anyMatch(v -> v.contains("SandboxTenantResolver"))
                .anyMatch(v -> v.contains("SessionOwnerLookup"));
        assertThat(violations(settings, LOCAL, true, true)).isEmpty();
    }

    @Test
    void theDefaultProfileMustBeDefined() {
        assertThat(violations(SandboxTestProfiles.settings(local().build()).defaultProfile("missing").build(), LOCAL,
                false, false)).anyMatch(v -> v.contains("default-profile 'missing'"));
        assertThat(violations(SandboxTestProfiles.settings().build(), LOCAL, false, false))
                .anyMatch(v -> v.contains("default-profile is required"));
    }

    @Test
    void admissionIsNeverAllowAllByAccident() {
        assertThat(violations(SandboxTestProfiles.settings(local().build()).maxRunningPerTenant(0).build(), LOCAL,
                false, false)).anyMatch(v -> v.contains("max-running-per-tenant"));
        assertThat(violations(
                SandboxTestProfiles.settings(local().build()).maxRunningPerTenant(SandboxSettings.UNLIMITED).build(),
                LOCAL, false, false)).isEmpty();
    }

    @Test
    void timingsMustBePositiveAndProfilesUnique() {
        assertThat(violations(SandboxTestProfiles.settings(local().build(), local().build())
                .shellLockWait(Duration.ZERO).casRetries(-1).build(), LOCAL, false, false))
                .anyMatch(v -> v.contains("shell-lock-wait must be positive")).anyMatch(v -> v.contains("cas-retries"))
                .anyMatch(v -> v.contains("defined twice"));
    }

    @Test
    void everyViolationIsReportedAtOnce() {
        try (LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build()) {
            assertThatThrownBy(() -> WorkspaceSandbox.builder()
                    .settings(SandboxTestProfiles
                            .settings(local().pauseAfter(Duration.ofMinutes(1)).credentials(List.of("c")).build())
                            .deployment("BAD").build())
                    .provider(provider).build()).isInstanceOfSatisfying(SandboxConfigurationException.class,
                            e -> assertThat(e.violations()).hasSizeGreaterThanOrEqualTo(3));
        }
    }

    @Test
    void terminateAfterMustSpanSeveralActivityWrites() {
        final SandboxSettings tooShort = SandboxTestProfiles
                .settings(local().terminateAfter(Duration.ofSeconds(60)).build())
                .activityWriteInterval(Duration.ofSeconds(30)).build();
        final SandboxSettings enough = SandboxTestProfiles
                .settings(local().terminateAfter(Duration.ofSeconds(90)).build())
                .activityWriteInterval(Duration.ofSeconds(30)).build();

        assertThat(violations(tooShort, LOCAL, false, false))
                .anyMatch(v -> v.contains("terminate-after PT1M must be at least 3 × activity-write-interval"));
        assertThat(violations(enough, LOCAL, false, false)).isEmpty();
    }
}
