package at.aimon.sandbox.provider;

import java.util.List;
import java.util.Map;

/**
 * Volume deletion and listing, present iff the provider advertises {@link Capability#SHARED_VOLUME}
 * (docs/design/workspace-sandbox.md §6.1). Creation is not here: a {@link CreateSpec} declares the mount and the first
 * sandbox creates the volume.
 */
public interface SharedVolumes {

    /**
     * @param labels
     *            labels every returned volume carries (the provider may match by name prefix instead, §6.3)
     * @return the matching volumes, every page
     */
    List<ProviderVolume> list(Map<String, String> labels);

    /**
     * Idempotent: an absent volume is success.
     *
     * @param ref
     *            the volume
     * @throws VolumeInUseException
     *             while a sandbox still mounts it
     */
    void delete(VolumeRef ref);
}
