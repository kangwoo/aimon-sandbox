package at.aimon.sandbox.provider;

import java.io.Serial;

/** {@link SharedVolumes#delete} refused: a sandbox still mounts the volume. Retry later. */
public class VolumeInUseException extends SandboxProviderException {

    @Serial
    private static final long serialVersionUID = 2289745710203475512L;

    /**
     * @param ref
     *            the volume
     */
    public VolumeInUseException(VolumeRef ref) {
        super("volume still mounted: " + ref, Kind.TRANSIENT, null);
    }
}
