package at.aimon.sandbox.provider;

import java.util.Map;
import java.util.Objects;

/** What {@link SharedVolumes#list} reports about one volume. */
public final class ProviderVolume {

    private final VolumeRef ref;
    private final Map<String, String> labels;
    private final boolean mounted;

    private ProviderVolume(VolumeRef ref, Map<String, String> labels, boolean mounted) {
        this.ref = Objects.requireNonNull(ref, "ref must not be null");
        this.labels = Map.copyOf(Objects.requireNonNull(labels, "labels must not be null"));
        this.mounted = mounted;
    }

    /**
     * @param ref
     *            the volume
     * @param labels
     *            its labels, when the provider has any
     * @param mounted
     *            whether a sandbox still mounts it
     * @return the description
     */
    public static ProviderVolume of(VolumeRef ref, Map<String, String> labels, boolean mounted) {
        return new ProviderVolume(ref, labels, mounted);
    }

    /** @return the volume */
    public VolumeRef ref() {
        return ref;
    }

    /** @return its labels */
    public Map<String, String> labels() {
        return labels;
    }

    /** @return whether a sandbox still mounts it */
    public boolean mounted() {
        return mounted;
    }
}
