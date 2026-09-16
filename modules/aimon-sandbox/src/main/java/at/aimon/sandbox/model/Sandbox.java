package at.aimon.sandbox.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a persistent sandbox environment (container or pod).
 *
 * <p>
 * Immutable value object.
 */
public final class Sandbox {

    private final String identifier;
    private final String sandboxId;
    private final String name;
    private final String image;
    private final Instant createdAt;
    private final Instant expiresAt;

    private Sandbox(Builder builder) {
        this.identifier = Objects.requireNonNull(builder.identifier, "Identifier cannot be null");
        this.sandboxId = Objects.requireNonNull(builder.sandboxId, "Sandbox ID cannot be null");
        this.name = Objects.requireNonNull(builder.name, "Name cannot be null");
        this.image = Objects.requireNonNull(builder.image, "Image cannot be null");
        this.createdAt = Objects.requireNonNull(builder.createdAt, "CreatedAt cannot be null");
        this.expiresAt = Objects.requireNonNull(builder.expiresAt, "ExpiresAt cannot be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public String getName() {
        return name;
    }

    public String getImage() {
        return image;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Sandbox sandbox = (Sandbox) o;
        return identifier.equals(sandbox.identifier) && sandboxId.equals(sandbox.sandboxId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, sandboxId);
    }

    @Override
    public String toString() {
        return "Sandbox{" + "identifier='" + identifier + "', sandboxId='" + sandboxId + "', name='" + name + "'}";
    }

    /** Builder for Sandbox. */
    public static final class Builder {

        private String identifier;
        private String sandboxId;
        private String name;
        private String image;
        private Instant createdAt;
        private Instant expiresAt;

        private Builder() {
        }

        public Builder identifier(String identifier) {
            this.identifier = identifier;
            return this;
        }

        public Builder sandboxId(String sandboxId) {
            this.sandboxId = sandboxId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder image(String image) {
            this.image = image;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Sandbox build() {
            return new Sandbox(this);
        }
    }
}
