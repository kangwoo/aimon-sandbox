package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Why a slot is FAILED (docs/design/workspace-sandbox.md §5.2, §10.1): {@code (at, kind, step, reason, attempts,
 * profileHash)}. A {@link Kind#PERMANENT} failure is not retried while the profile's hash equals
 * {@link #profileHash()}; a {@link Kind#TRANSIENT} one is retried after a backoff that doubles with
 * {@link #attempts()}.
 */
public final class SlotFailure {

    /** Whether retrying can help. */
    public enum Kind {
        /** Retry after backoff. */
        TRANSIENT,
        /** Retry only after the profile changes. */
        PERMANENT
    }

    private final Instant at;
    private final Kind kind;
    private final String step;
    private final String reason;
    private final int attempts;
    private final String profileHash;

    private SlotFailure(Builder builder) {
        this.at = Objects.requireNonNull(builder.at, "at must not be null");
        this.kind = Objects.requireNonNull(builder.kind, "kind must not be null");
        this.step = Objects.requireNonNull(builder.step, "step must not be null");
        this.reason = Objects.requireNonNull(builder.reason, "reason must not be null");
        this.attempts = builder.attempts;
        this.profileHash = Objects.requireNonNull(builder.profileHash, "profileHash must not be null");
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return when it failed */
    public Instant at() {
        return at;
    }

    /** @return whether retrying can help */
    public Kind kind() {
        return kind;
    }

    /** @return the step that failed ({@code create}, {@code labels}, a seed check) */
    public String step() {
        return step;
    }

    /** @return what went wrong */
    public String reason() {
        return reason;
    }

    /** @return how many consecutive attempts failed */
    public int attempts() {
        return attempts;
    }

    /** @return the hash of the profile the failed attempt used */
    public String profileHash() {
        return profileHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SlotFailure that)) {
            return false;
        }
        return attempts == that.attempts && at.equals(that.at) && kind == that.kind && step.equals(that.step)
                && reason.equals(that.reason) && profileHash.equals(that.profileHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(at, kind, step, reason, attempts, profileHash);
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + " failure at step '" + step + "' (attempt " + attempts + "): "
                + reason;
    }

    /** Builder for {@link SlotFailure}. */
    public static final class Builder {
        private Instant at;
        private Kind kind;
        private String step;
        private String reason;
        private int attempts = 1;
        private String profileHash;

        private Builder() {
        }

        public Builder at(Instant at) {
            this.at = at;
            return this;
        }

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder step(String step) {
            this.step = step;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder attempts(int attempts) {
            this.attempts = attempts;
            return this;
        }

        public Builder profileHash(String profileHash) {
            this.profileHash = profileHash;
            return this;
        }

        public SlotFailure build() {
            return new SlotFailure(this);
        }
    }
}
