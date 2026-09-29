package at.aimon.sandbox.testkit;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.Capability;

/**
 * Profiles and settings that fit {@link LocalProcessSandboxProvider} on the machine running the tests: the host's
 * platform, isolation waived ({@code HARDENED_SECURITY_CONTEXT}, {@code NETWORK_ISOLATION} — the local provider
 * advertises neither), and no runtime class, egress, credentials or seed.
 */
public final class SandboxTestProfiles {

    private SandboxTestProfiles() {
    }

    /** @return {@code uname -s} of this host, lower-cased: what the seed will compare the declared platform with */
    public static String hostPlatform() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return os.contains("linux") ? "linux" : os;
    }

    /**
     * @param name
     *            the profile name
     * @return a builder for a local profile ({@code terminateAfter} two hours)
     */
    public static SandboxProfile.Builder local(String name) {
        return SandboxProfile.builder().name(name).image("local").platform(hostPlatform())
                .terminateAfter(Duration.ofHours(2))
                .insecureAllow(Set.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION));
    }

    /**
     * @param profiles
     *            the profiles; the first is the default
     * @return settings with {@code deployment = test} and a fixed node id
     */
    public static SandboxSettings.Builder settings(SandboxProfile... profiles) {
        return SandboxSettings.builder().deployment("test").nodeId("test-node").profiles(List.of(profiles))
                .defaultProfile(profiles.length > 0 ? profiles[0].name() : null);
    }
}
