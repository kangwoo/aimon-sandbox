package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What an owner or operator sees of one slot (docs/design/workspace-sandbox-management.md §5.4): state and generation,
 * never the provider's sandbox id — with network reach, that id is root in the sandbox (WS §13.3).
 */
public final class SlotView {

    private final String name;
    private final String profile;
    private final SlotState state;
    private final long generation;
    private final boolean seeded;
    private final Failure failure;
    private final Instant lastActivityAt;

    private SlotView(SandboxSlot slot) {
        this.name = slot.name();
        this.profile = slot.profile();
        this.state = slot.state();
        this.generation = slot.generation();
        this.seeded = slot.seeded();
        this.failure = slot.failure().map(Failure::new).orElse(null);
        this.lastActivityAt = slot.lastActivityAt();
    }

    /**
     * <b>Internal.</b> Public only for use across this library's packages: not supported API, and it may change in any
     * release.
     *
     * @param slot
     *            the slot
     * @return its view
     */
    public static SlotView of(SandboxSlot slot) {
        return new SlotView(Objects.requireNonNull(slot, "slot must not be null"));
    }

    /** @return the slot name */
    public String name() {
        return name;
    }

    /** @return the profile the slot was created with */
    public String profile() {
        return profile;
    }

    /** @return the slot state */
    public SlotState state() {
        return state;
    }

    /** @return the generation: a new one after every termination */
    public long generation() {
        return generation;
    }

    /** @return whether the current generation is seeded and verified */
    public boolean seeded() {
        return seeded;
    }

    /** @return the recorded provisioning failure */
    public Optional<Failure> failure() {
        return Optional.ofNullable(failure);
    }

    /** @return the last recorded activity */
    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    @Override
    public String toString() {
        return "SlotView{" + name + "#" + generation + ", " + state + ", profile=" + profile
                + (failure != null ? ", failure=" + failure : "") + '}';
    }

    /** A recorded provisioning failure: its kind, the step that failed and why. */
    public static final class Failure {

        private final SlotFailure.Kind kind;
        private final String step;
        private final String message;

        private Failure(SlotFailure failure) {
            this.kind = failure.kind();
            this.step = failure.step();
            this.message = failure.reason();
        }

        /** @return transient (retried after backoff) or permanent (until the profile changes) */
        public SlotFailure.Kind kind() {
            return kind;
        }

        /** @return the step that failed */
        public String step() {
            return step;
        }

        /** @return why */
        public String message() {
            return message;
        }

        @Override
        public String toString() {
            return kind + " at " + step + ": " + message;
        }
    }
}
