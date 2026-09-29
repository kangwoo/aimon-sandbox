package at.aimon.sandbox;

import java.io.Serial;
import java.util.List;

/**
 * The sandbox configuration violates a startup rule (docs/design/workspace-sandbox.md §13.2). Carries every violation
 * found, not just the first, so one failed start shows the whole list; nothing is started.
 */
public class SandboxConfigurationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 6624173205837204112L;

    private final List<String> violations;

    /**
     * @param violations
     *            what is wrong, one entry per rule broken
     */
    public SandboxConfigurationException(List<String> violations) {
        super("Invalid workspace sandbox configuration:\n  - " + String.join("\n  - ", violations));
        this.violations = List.copyOf(violations);
    }

    /** @return every violation found */
    public List<String> violations() {
        return violations;
    }
}
