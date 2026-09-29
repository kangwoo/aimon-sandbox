package at.aimon.sandbox.provider;

import java.util.Objects;

/**
 * A shared volume, identified by its name (docs/design/workspace-sandbox.md §6.3 — volumes carry no labels, so the
 * name rule is the identity).
 */
public final class VolumeRef {

    private final String name;

    private VolumeRef(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
    }

    /**
     * @param name
     *            the volume name
     * @return the reference
     */
    public static VolumeRef of(String name) {
        return new VolumeRef(name);
    }

    /** @return the volume name */
    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof VolumeRef that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
