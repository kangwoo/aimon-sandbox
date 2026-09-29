package at.aimon.sandbox.provider;

import java.io.Serial;

/**
 * The sandbox does not exist (any more). Thrown by {@link SandboxProvider#extendExpiry}, {@link SandboxProvider#resume}
 * and {@link SandboxProvider#connect}; the manager answers it with the LOST path (docs/design/workspace-sandbox.md
 * §10.1).
 */
public class SandboxNotFoundException extends SandboxProviderException {

    @Serial
    private static final long serialVersionUID = -1693245802718304566L;

    /**
     * @param ref
     *            the sandbox that was not found
     */
    public SandboxNotFoundException(ProviderSandboxRef ref) {
        super("sandbox not found: " + ref, Kind.PERMANENT, null);
    }
}
