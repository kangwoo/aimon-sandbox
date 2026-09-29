package at.aimon.sandbox.workspace;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code (since, nodeId)} — which node is provisioning a slot and since when; the takeover rule of
 * docs/design/workspace-sandbox.md §10.1 compares {@code since + provisionTimeout} with now.
 */
public final class ProvisioningClaim {

    private final Instant since;
    private final String nodeId;

    private ProvisioningClaim(Instant since, String nodeId) {
        this.since = Objects.requireNonNull(since, "since must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
    }

    /**
     * @param since
     *            when the claim was taken
     * @param nodeId
     *            the claiming node
     * @return the claim
     */
    public static ProvisioningClaim of(Instant since, String nodeId) {
        return new ProvisioningClaim(since, nodeId);
    }

    /** @return when the claim was taken */
    public Instant since() {
        return since;
    }

    /** @return the claiming node */
    public String nodeId() {
        return nodeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProvisioningClaim that)) {
            return false;
        }
        return since.equals(that.since) && nodeId.equals(that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(since, nodeId);
    }

    @Override
    public String toString() {
        return nodeId + "@" + since;
    }
}
