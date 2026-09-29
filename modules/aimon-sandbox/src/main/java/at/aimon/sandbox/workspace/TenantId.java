package at.aimon.sandbox.workspace;

import java.util.Objects;

/**
 * A tenant. Core's {@code Principal} has no tenant field, so the application maps principals to tenants through
 * {@code SandboxTenantResolver} (docs/design/workspace-sandbox.md §8.3).
 */
public final class TenantId {

    /** The single tenant of a single-tenant deployment. */
    public static final TenantId DEFAULT = new TenantId("default");

    private final String value;

    private TenantId(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
        this.value = value;
    }

    /**
     * @param value
     *            the id
     * @return the tenant id
     */
    public static TenantId of(String value) {
        return new TenantId(value);
    }

    /** @return the id */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof TenantId that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
