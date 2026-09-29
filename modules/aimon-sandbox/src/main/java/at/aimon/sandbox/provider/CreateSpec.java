package at.aimon.sandbox.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a provider needs to create one sandbox (docs/design/workspace-sandbox.md §6.1, §6.3).
 *
 * <p>
 * {@link #key()} is {@code "{deployment}/{workspaceId}/{incarnation}/{slot}/{generation}"}; a provider makes
 * {@link SandboxProvider#create} best-effort idempotent on it. {@link #labels()} already carries the encoded
 * {@code aimon.at/*} labels ({@link SandboxLabels}). {@link #egress()} distinguishes absent (no policy requested) from
 * empty (deny everything): a provider must send an empty list as an explicit deny, never omit it (§6.4).
 */
public final class CreateSpec {

    private final String key;
    private final String image;
    private final String platform;
    private final ResourceSpec resources;
    private final String runtimeClass;
    private final List<String> egress;
    private final List<String> credentials;
    private final Map<String, String> environment;
    private final Map<String, String> labels;
    private final Instant expiresAt;
    private final List<VolumeMount> volumes;

    private CreateSpec(Builder builder) {
        this.key = Objects.requireNonNull(builder.key, "key must not be null");
        this.image = Objects.requireNonNull(builder.image, "image must not be null");
        this.platform = builder.platform;
        this.resources = Objects.requireNonNull(builder.resources, "resources must not be null");
        this.runtimeClass = builder.runtimeClass;
        this.egress = builder.egress == null ? null : List.copyOf(builder.egress);
        this.credentials = List.copyOf(builder.credentials);
        this.environment = Map.copyOf(builder.environment);
        this.labels = Map.copyOf(builder.labels);
        this.expiresAt = Objects.requireNonNull(builder.expiresAt, "expiresAt must not be null");
        this.volumes = List.copyOf(builder.volumes);
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the idempotency key */
    public String key() {
        return key;
    }

    /** @return the image */
    public String image() {
        return image;
    }

    /** @return the declared platform ({@code linux}), when the profile declares one */
    public Optional<String> platform() {
        return Optional.ofNullable(platform);
    }

    /** @return the resource limits */
    public ResourceSpec resources() {
        return resources;
    }

    /** @return the runtime class, when one is requested */
    public Optional<String> runtimeClass() {
        return Optional.ofNullable(runtimeClass);
    }

    /** @return the egress allow-list; absent means no policy was requested, empty means deny everything */
    public Optional<List<String>> egress() {
        return Optional.ofNullable(egress);
    }

    /** @return the names of the credential bindings to inject */
    public List<String> credentials() {
        return credentials;
    }

    /** @return the static environment of every command (the profile's {@code env}) */
    public Map<String, String> environment() {
        return environment;
    }

    /** @return the labels to put on the sandbox */
    public Map<String, String> labels() {
        return labels;
    }

    /** @return the absolute provider-side expiry */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** @return the shared volumes to mount (and create when missing) */
    public List<VolumeMount> volumes() {
        return volumes;
    }

    @Override
    public String toString() {
        return "CreateSpec{key=" + key + ", image=" + image + ", expiresAt=" + expiresAt + ", labels=" + labels + '}';
    }

    /** Builder for {@link CreateSpec}. */
    public static final class Builder {
        private String key;
        private String image;
        private String platform;
        private ResourceSpec resources = ResourceSpec.none();
        private String runtimeClass;
        private List<String> egress;
        private List<String> credentials = List.of();
        private Map<String, String> environment = Map.of();
        private Map<String, String> labels = Map.of();
        private Instant expiresAt;
        private List<VolumeMount> volumes = List.of();

        private Builder() {
        }

        public Builder key(String key) {
            this.key = key;
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

        public Builder labels(Map<String, String> labels) {
            this.labels = Objects.requireNonNull(labels, "labels must not be null");
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder volumes(List<VolumeMount> volumes) {
            this.volumes = Objects.requireNonNull(volumes, "volumes must not be null");
            return this;
        }

        public CreateSpec build() {
            return new CreateSpec(this);
        }
    }
}
