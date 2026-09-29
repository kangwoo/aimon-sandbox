package at.aimon.sandbox.workspace;

import java.util.Objects;
import java.util.Optional;

/** What {@link SandboxAdmission#admit} answered. */
public final class AdmissionDecision {

    private static final AdmissionDecision ADMITTED = new AdmissionDecision(null);

    private final String rejection;

    private AdmissionDecision(String rejection) {
        this.rejection = rejection;
    }

    /** @return an admission */
    public static AdmissionDecision admitted() {
        return ADMITTED;
    }

    /**
     * @param reason
     *            which limit was hit and its value, in words the model can act on (§15)
     * @return a rejection
     */
    public static AdmissionDecision rejected(String reason) {
        return new AdmissionDecision(Objects.requireNonNull(reason, "reason must not be null"));
    }

    /** @return whether the sandbox may be started */
    public boolean isAdmitted() {
        return rejection == null;
    }

    /** @return why not */
    public Optional<String> rejection() {
        return Optional.ofNullable(rejection);
    }
}
