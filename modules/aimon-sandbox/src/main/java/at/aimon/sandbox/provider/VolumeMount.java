package at.aimon.sandbox.provider;

import java.util.Objects;

/**
 * A shared volume declared on {@link CreateSpec}: the first sandbox of a workspace creates it (§6.1). Only providers
 * advertising {@link Capability#SHARED_VOLUME} receive one; implementation step 3 never declares any.
 */
public final class VolumeMount {

    private final VolumeRef volume;
    private final String mountPath;
    private final boolean readOnly;

    private VolumeMount(VolumeRef volume, String mountPath, boolean readOnly) {
        this.volume = Objects.requireNonNull(volume, "volume must not be null");
        this.mountPath = Objects.requireNonNull(mountPath, "mountPath must not be null");
        this.readOnly = readOnly;
    }

    /**
     * @param volume
     *            the volume
     * @param mountPath
     *            where it is mounted inside the sandbox
     * @param readOnly
     *            whether this sandbox mounts it read-only
     * @return the mount
     */
    public static VolumeMount of(VolumeRef volume, String mountPath, boolean readOnly) {
        return new VolumeMount(volume, mountPath, readOnly);
    }

    /** @return the volume */
    public VolumeRef volume() {
        return volume;
    }

    /** @return where it is mounted */
    public String mountPath() {
        return mountPath;
    }

    /** @return whether it is mounted read-only */
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public String toString() {
        return volume + ":" + mountPath + (readOnly ? ":ro" : ":rw");
    }
}
