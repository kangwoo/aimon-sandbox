package at.aimon.sandbox.provider;

import java.util.Objects;

/**
 * Where a sandbox lives: the provider's name and the provider's own id for it. The workspace record stores this and
 * nothing else about the remote object (docs/design/workspace-sandbox.md §5.2).
 */
public final class ProviderSandboxRef {

    private final String provider;
    private final String sandboxId;

    private ProviderSandboxRef(String provider, String sandboxId) {
        this.provider = requireText(provider, "provider");
        this.sandboxId = requireText(sandboxId, "sandboxId");
    }

    /**
     * @param provider
     *            the provider's name
     * @param sandboxId
     *            the provider's id for the sandbox
     * @return the reference
     */
    public static ProviderSandboxRef of(String provider, String sandboxId) {
        return new ProviderSandboxRef(provider, sandboxId);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** @return the provider's name */
    public String provider() {
        return provider;
    }

    /** @return the provider's id for the sandbox */
    public String sandboxId() {
        return sandboxId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderSandboxRef that)) {
            return false;
        }
        return provider.equals(that.provider) && sandboxId.equals(that.sandboxId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, sandboxId);
    }

    @Override
    public String toString() {
        return provider + ":" + sandboxId;
    }
}
