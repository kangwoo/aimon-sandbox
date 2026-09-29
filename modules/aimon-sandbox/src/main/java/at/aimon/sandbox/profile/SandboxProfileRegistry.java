package at.aimon.sandbox.profile;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The configured profiles and which one a new slot gets when its binding requires none
 * (docs/design/workspace-sandbox.md §8.2, §13.2). A slot keeps the profile it was created with even when a redeploy
 * changes the default (§8.3).
 */
public final class SandboxProfileRegistry {

    private final Map<String, SandboxProfile> profiles;
    private final String defaultProfile;

    /**
     * @param profiles
     *            the profiles
     * @param defaultProfile
     *            the default profile's name (validated at startup, not here)
     */
    public SandboxProfileRegistry(Collection<SandboxProfile> profiles, String defaultProfile) {
        final Map<String, SandboxProfile> byName = new LinkedHashMap<>();
        for (SandboxProfile profile : Objects.requireNonNull(profiles, "profiles must not be null")) {
            if (byName.put(profile.name(), profile) != null) {
                throw new IllegalArgumentException("duplicate profile name: " + profile.name());
            }
        }
        this.profiles = Map.copyOf(byName);
        this.defaultProfile = Objects.requireNonNull(defaultProfile, "defaultProfile must not be null");
    }

    /**
     * @param name
     *            the profile name
     * @return the profile, when configured
     */
    public Optional<SandboxProfile> find(String name) {
        return Optional.ofNullable(profiles.get(name));
    }

    /** @return the default profile's name */
    public String defaultProfileName() {
        return defaultProfile;
    }

    /** @return every profile by name */
    public Map<String, SandboxProfile> all() {
        return profiles;
    }

    /**
     * The longest {@code terminateAfter} among the configured profiles — what the janitor applies to a slot whose
     * profile was removed, so it is never terminated earlier than any live profile would be.
     *
     * @return the longest {@code terminateAfter}
     */
    public Optional<Duration> longestTerminateAfter() {
        return profiles.values().stream().flatMap(p -> p.terminateAfter().stream()).max(Duration::compareTo);
    }
}
