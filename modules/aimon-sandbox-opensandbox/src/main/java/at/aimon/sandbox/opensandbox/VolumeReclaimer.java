package at.aimon.sandbox.opensandbox;

import at.aimon.sandbox.provider.SharedVolumes;

/**
 * The deletion path of shared volumes, which OpenSandbox does not have (docs/design/workspace-sandbox.md §6.4,
 * docs/design/opensandbox-spike.md §5): the operator supplies one that talks to the Docker volume API or to
 * Kubernetes PVCs. Supplying one advertises {@code SHARED_VOLUME} and becomes {@code sharedVolumes()}; a volume is
 * identified by its name prefix, since OpenSandbox puts no labels on it. Implementation step 4 ships none — built-in
 * reclaimers arrive with the shared volumes of step 5.
 */
public interface VolumeReclaimer extends SharedVolumes {
}
