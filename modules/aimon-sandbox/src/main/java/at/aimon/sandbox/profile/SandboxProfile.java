package at.aimon.sandbox.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.ResourceSpec;

/**
 * How to build a sandbox: operator configuration the model can choose between but never edit
 * (docs/design/workspace-sandbox.md §13.1).
 *
 * <p>
 * {@link #egress()} distinguishes <i>absent</i> (no egress policy requested) from <i>empty</i> (deny everything); an
 * empty list still requires {@link Capability#EGRESS_POLICY} (§6.4). {@link #insecureAllow()} waives isolation
 * capabilities for local development only, and every waiver is logged at startup.
 */
public final class SandboxProfile {

    /** The capabilities {@link #insecureAllow()} may waive (§13.1). */
    public static final Set<Capability> WAIVABLE = Collections.unmodifiableSet(
            EnumSet.of(Capability.HARDENED_SECURITY_CONTEXT, Capability.RUNTIME_CLASS, Capability.NETWORK_ISOLATION));

    private final String name;
    private final String image;
    private final String platform;
    private final String osVersion;
    private final String shellName;
    private final ResourceSpec resources;
    private final String runtimeClass;
    private final List<String> egress;
    private final List<String> credentials;
    private final Map<String, String> environment;
    private final Duration pauseAfter;
    private final Duration terminateAfter;
    private final Duration backgroundHeartbeatLimit;
    private final SharedAccess sharedAccess;
    private final SeedSpec seed;
    private final Set<Capability> insecureAllow;

    private SandboxProfile(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.image = Objects.requireNonNull(builder.image, "image must not be null");
        this.platform = Objects.requireNonNull(builder.platform, "platform must not be null");
        this.osVersion = builder.osVersion;
        this.shellName = Objects.requireNonNull(builder.shellName, "shellName must not be null");
        this.resources = Objects.requireNonNull(builder.resources, "resources must not be null");
        this.runtimeClass = builder.runtimeClass;
        this.egress = builder.egress == null ? null : List.copyOf(builder.egress);
        this.credentials = List.copyOf(builder.credentials);
        this.environment = Collections.unmodifiableMap(new TreeMap<>(builder.environment));
        this.pauseAfter = builder.pauseAfter;
        this.terminateAfter = builder.terminateAfter;
        this.backgroundHeartbeatLimit = Objects.requireNonNull(builder.backgroundHeartbeatLimit,
                "backgroundHeartbeatLimit must not be null");
        this.sharedAccess = Objects.requireNonNull(builder.sharedAccess, "sharedAccess must not be null");
        this.seed = builder.seed;
        this.insecureAllow = builder.insecureAllow.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(builder.insecureAllow));
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the name */
    public String name() {
        return name;
    }

    /** @return the image */
    public String image() {
        return image;
    }

    /** @return the declared platform ({@code linux}) — shown to the model and checked by the seed */
    public String platform() {
        return platform;
    }

    /** @return the declared OS version ({@code Linux 6.x}), checked against {@code uname -sr} when present */
    public Optional<String> osVersion() {
        return Optional.ofNullable(osVersion);
    }

    /** @return the declared shell ({@code bash}) */
    public String shellName() {
        return shellName;
    }

    /** @return the resource limits */
    public ResourceSpec resources() {
        return resources;
    }

    /** @return the runtime class */
    public Optional<String> runtimeClass() {
        return Optional.ofNullable(runtimeClass);
    }

    /** @return the egress allow-list; absent means no policy, empty means deny everything */
    public Optional<List<String>> egress() {
        return Optional.ofNullable(egress);
    }

    /** @return the credential binding names */
    public List<String> credentials() {
        return credentials;
    }

    /** @return the static environment (never secrets, §12.1) */
    public Map<String, String> environment() {
        return environment;
    }

    /** @return the idle time before pause (requires {@link Capability#PAUSE_RESUME}) */
    public Optional<Duration> pauseAfter() {
        return Optional.ofNullable(pauseAfter);
    }

    /** @return the idle time before terminate — required; also the provider expiry's distance */
    public Optional<Duration> terminateAfter() {
        return Optional.ofNullable(terminateAfter);
    }

    /** @return how long a background command keeps the sandbox awake */
    public Duration backgroundHeartbeatLimit() {
        return backgroundHeartbeatLimit;
    }

    /** @return how {@code /shared} is mounted */
    public SharedAccess sharedAccess() {
        return sharedAccess;
    }

    /** @return the seed */
    public Optional<SeedSpec> seed() {
        return Optional.ofNullable(seed);
    }

    /** @return the waived isolation capabilities */
    public Set<Capability> insecureAllow() {
        return insecureAllow;
    }

    /**
     * The capabilities a provider must advertise to run this profile (§6.4): {@code EXEC}, {@code FILES},
     * {@code EXPIRY}, {@code HARDENED_SECURITY_CONTEXT} and {@code NETWORK_ISOLATION} always, plus what the
     * configuration implies, minus {@link #insecureAllow()}.
     *
     * @return the required capabilities
     */
    public Set<Capability> requiredCapabilities() {
        final EnumSet<Capability> required = EnumSet.of(Capability.EXEC, Capability.FILES, Capability.EXPIRY,
                Capability.HARDENED_SECURITY_CONTEXT, Capability.NETWORK_ISOLATION);
        if (egress != null) {
            required.add(Capability.EGRESS_POLICY);
        }
        if (!credentials.isEmpty()) {
            required.add(Capability.CREDENTIAL_INJECTION);
        }
        if (runtimeClass != null) {
            required.add(Capability.RUNTIME_CLASS);
        }
        if (sharedAccess != SharedAccess.NONE) {
            required.add(Capability.SHARED_VOLUME);
        }
        if (pauseAfter != null) {
            required.add(Capability.PAUSE_RESUME);
        }
        required.removeAll(insecureAllow);
        return Collections.unmodifiableSet(required);
    }

    /**
     * The SHA-256 of the profile's canonical form. A slot records it, so a permanent failure is retried exactly when
     * the profile's content changes (§10.1).
     *
     * @return the hex hash
     */
    public String contentHash() {
        final String canonical = String.join("\n", "name=" + name, "image=" + image, "platform=" + platform,
                "osVersion=" + osVersion, "shellName=" + shellName, "resources=" + resources,
                "runtimeClass=" + runtimeClass, "egress=" + egress, "credentials=" + credentials, "env=" + environment,
                "pauseAfter=" + pauseAfter, "terminateAfter=" + terminateAfter,
                "backgroundHeartbeatLimit=" + backgroundHeartbeatLimit, "sharedAccess=" + sharedAccess, "seed=" + seed,
                "insecureAllow=" + insecureAllow);
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return "SandboxProfile{" + name + ", image=" + image + ", platform=" + platform + ", terminateAfter="
                + terminateAfter + '}';
    }

    /** Builder for {@link SandboxProfile}. */
    public static final class Builder {
        private String name;
        private String image;
        private String platform = "linux";
        private String osVersion;
        private String shellName = "bash";
        private ResourceSpec resources = ResourceSpec.none();
        private String runtimeClass;
        private List<String> egress;
        private List<String> credentials = List.of();
        private Map<String, String> environment = Map.of();
        private Duration pauseAfter;
        private Duration terminateAfter;
        private Duration backgroundHeartbeatLimit = Duration.ofHours(1);
        private SharedAccess sharedAccess = SharedAccess.NONE;
        private SeedSpec seed;
        private Set<Capability> insecureAllow = Set.of();

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder image(String image) {
            this.image = image;
            return this;
        }

        public Builder platform(String platform) {
            this.platform = platform;
            return this;
        }

        public Builder osVersion(String osVersion) {
            this.osVersion = osVersion;
            return this;
        }

        public Builder shellName(String shellName) {
            this.shellName = shellName;
            return this;
        }

        public Builder resources(ResourceSpec resources) {
            this.resources = resources;
            return this;
        }

        public Builder runtimeClass(String runtimeClass) {
            this.runtimeClass = runtimeClass;
            return this;
        }

        public Builder egress(List<String> egress) {
            this.egress = egress;
            return this;
        }

        public Builder credentials(List<String> credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = Objects.requireNonNull(environment, "environment must not be null");
            return this;
        }

        public Builder pauseAfter(Duration pauseAfter) {
            this.pauseAfter = pauseAfter;
            return this;
        }

        public Builder terminateAfter(Duration terminateAfter) {
            this.terminateAfter = terminateAfter;
            return this;
        }

        public Builder backgroundHeartbeatLimit(Duration backgroundHeartbeatLimit) {
            this.backgroundHeartbeatLimit = backgroundHeartbeatLimit;
            return this;
        }

        public Builder sharedAccess(SharedAccess sharedAccess) {
            this.sharedAccess = sharedAccess;
            return this;
        }

        public Builder seed(SeedSpec seed) {
            this.seed = seed;
            return this;
        }

        public Builder insecureAllow(Set<Capability> insecureAllow) {
            this.insecureAllow = Objects.requireNonNull(insecureAllow, "insecureAllow must not be null");
            return this;
        }

        public SandboxProfile build() {
            return new SandboxProfile(this);
        }
    }
}
