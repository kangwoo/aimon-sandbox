package at.aimon.sandbox.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The infrastructure that creates sandboxes (docs/design/workspace-sandbox.md §6.1). The production implementation is
 * OpenSandbox ({@code aimon-sandbox-opensandbox}, implementation step 4); {@code aimon-sandbox-testkit} holds a
 * local-process one for tests and a contract suite every implementation must pass.
 *
 * <p>
 * A provider only does what the workspace record decided: "reuse if present" is the record's call, not
 * {@link #create}'s. Calls for a capability the provider does not {@linkplain #capabilities() advertise} throw
 * {@link UnsupportedOperationException}.
 */
public interface SandboxProvider extends AutoCloseable {

    /** @return what this provider verified it can do */
    ProviderCapabilities capabilities();

    /**
     * Best-effort idempotent on {@link CreateSpec#key()} (§6.3): when a sandbox with that key exists it is returned
     * instead of creating another.
     *
     * @param spec
     *            the sandbox to create
     * @return the created or found sandbox
     */
    ProviderSandboxRef create(CreateSpec spec);

    /**
     * @param ref
     *            the sandbox
     * @return its state and labels, or empty when it does not exist
     */
    Optional<ProviderSandbox> status(ProviderSandboxRef ref);

    /**
     * Requires {@link Capability#PAUSE_RESUME}.
     *
     * @param ref
     *            the sandbox
     */
    void pause(ProviderSandboxRef ref);

    /**
     * Requires {@link Capability#PAUSE_RESUME}.
     *
     * @param ref
     *            the sandbox
     * @throws SandboxNotFoundException
     *             when it does not exist
     */
    void resume(ProviderSandboxRef ref);

    /**
     * Moves the provider-side expiry forward; never backwards.
     *
     * @param ref
     *            the sandbox
     * @param until
     *            the new expiry
     * @throws SandboxNotFoundException
     *             when it does not exist
     */
    void extendExpiry(ProviderSandboxRef ref, Instant until);

    /**
     * Idempotent: an absent sandbox is success.
     *
     * @param ref
     *            the sandbox
     */
    void destroy(ProviderSandboxRef ref);

    /**
     * @param labels
     *            labels every returned sandbox carries
     * @return every matching sandbox, every page
     */
    List<ProviderSandbox> list(Map<String, String> labels);

    /** @return volume management, present iff {@link Capability#SHARED_VOLUME} is advertised */
    Optional<SharedVolumes> sharedVolumes();

    /**
     * @param ref
     *            the sandbox
     * @return a connection to it
     * @throws SandboxNotFoundException
     *             when it does not exist
     */
    SandboxConnection connect(ProviderSandboxRef ref);

    /** Releases client resources. Never destroys sandboxes. */
    @Override
    void close();
}
