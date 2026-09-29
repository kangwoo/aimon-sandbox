package at.aimon.sandbox.workspace;

import java.util.Objects;

/**
 * An opaque workspace id (docs/design/workspace-sandbox.md §5.1). The default binding policy derives it
 * deterministically — {@code ws:{sessionId}} or {@code ws:{executionId}} (§8.2) — so no mapping store is needed.
 */
public final class SandboxWorkspaceId implements Comparable<SandboxWorkspaceId> {

    private final String value;

    private SandboxWorkspaceId(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("workspace id must not be blank");
        }
        this.value = value;
    }

    /**
     * @param value
     *            the id
     * @return the workspace id
     */
    public static SandboxWorkspaceId of(String value) {
        return new SandboxWorkspaceId(value);
    }

    /** @return the id */
    public String value() {
        return value;
    }

    @Override
    public int compareTo(SandboxWorkspaceId other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof SandboxWorkspaceId that && value.equals(that.value);
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
