package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;

import at.aimon.sandbox.provider.VolumeRef;

/**
 * A shared volume of an earlier incarnation kept until a deadline after an idle reopen
 * (docs/design/workspace-sandbox.md §5.1, §10.4). Part of the record's schema; implementation step 3 never writes one
 * (no shared volumes until step 5).
 */
public final class RetainedVolume {

    private final String incarnation;
    private final VolumeRef volume;
    private final Instant until;

    private RetainedVolume(String incarnation, VolumeRef volume, Instant until) {
        this.incarnation = Objects.requireNonNull(incarnation, "incarnation must not be null");
        this.volume = Objects.requireNonNull(volume, "volume must not be null");
        this.until = Objects.requireNonNull(until, "until must not be null");
    }

    /**
     * @param incarnation
     *            the incarnation the volume belongs to
     * @param volume
     *            the volume
     * @param until
     *            how long it is kept
     * @return the entry
     */
    public static RetainedVolume of(String incarnation, VolumeRef volume, Instant until) {
        return new RetainedVolume(incarnation, volume, until);
    }

    /** @return the incarnation the volume belongs to */
    public String incarnation() {
        return incarnation;
    }

    /** @return the volume */
    public VolumeRef volume() {
        return volume;
    }

    /** @return how long it is kept */
    public Instant until() {
        return until;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RetainedVolume that)) {
            return false;
        }
        return incarnation.equals(that.incarnation) && volume.equals(that.volume) && until.equals(that.until);
    }

    @Override
    public int hashCode() {
        return Objects.hash(incarnation, volume, until);
    }
}
