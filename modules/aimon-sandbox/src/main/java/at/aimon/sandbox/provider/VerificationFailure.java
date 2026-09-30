package at.aimon.sandbox.provider;

import java.util.Objects;

/**
 * One check {@link SandboxProvider#verify} found failed on a live sandbox. The manager records it as a permanent
 * failure of the slot with {@link #step()} as the failure step (docs/design/workspace-sandbox.md §11.3).
 */
public final class VerificationFailure {

    private final String step;
    private final String reason;

    private VerificationFailure(String step, String reason) {
        this.step = Objects.requireNonNull(step, "step must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * @param step
     *            the check, as a short failure-step name ({@code egress}, {@code credentials})
     * @param reason
     *            what was found, for operators; never a secret
     * @return the failure
     */
    public static VerificationFailure of(String step, String reason) {
        return new VerificationFailure(step, reason);
    }

    /** @return the check */
    public String step() {
        return step;
    }

    /** @return what was found */
    public String reason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VerificationFailure that)) {
            return false;
        }
        return step.equals(that.step) && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, reason);
    }

    @Override
    public String toString() {
        return step + ": " + reason;
    }
}
