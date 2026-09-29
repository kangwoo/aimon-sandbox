package at.aimon.sandbox.workspace;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-workspace limits, copied from the settings into the record when it is created so a configuration change does
 * not shake running workspaces (docs/design/workspace-sandbox.md §5.1, §12.2). Implementation step 3 records them;
 * enforcement arrives with multiple slots (step 5).
 */
public final class WorkspaceQuota {

    private static final WorkspaceQuota DEFAULTS = new WorkspaceQuota(6, 3, null, null);

    private final int maxSlots;
    private final int maxRunning;
    private final String maxCpu;
    private final String maxMemory;

    private WorkspaceQuota(int maxSlots, int maxRunning, String maxCpu, String maxMemory) {
        if (maxSlots < 1 || maxRunning < 1) {
            throw new IllegalArgumentException("maxSlots and maxRunning must be >= 1");
        }
        this.maxSlots = maxSlots;
        this.maxRunning = maxRunning;
        this.maxCpu = maxCpu;
        this.maxMemory = maxMemory;
    }

    /** @return {@code maxSlots = 6}, {@code maxRunning = 3}, no CPU or memory cap */
    public static WorkspaceQuota defaults() {
        return DEFAULTS;
    }

    /**
     * @param maxSlots
     *            the most slots
     * @param maxRunning
     *            the most running slots
     * @param maxCpu
     *            the CPU cap, or {@code null}
     * @param maxMemory
     *            the memory cap, or {@code null}
     * @return the quota
     */
    public static WorkspaceQuota of(int maxSlots, int maxRunning, String maxCpu, String maxMemory) {
        return new WorkspaceQuota(maxSlots, maxRunning, maxCpu, maxMemory);
    }

    /** @return the most slots */
    public int maxSlots() {
        return maxSlots;
    }

    /** @return the most running slots */
    public int maxRunning() {
        return maxRunning;
    }

    /** @return the CPU cap */
    public Optional<String> maxCpu() {
        return Optional.ofNullable(maxCpu);
    }

    /** @return the memory cap */
    public Optional<String> maxMemory() {
        return Optional.ofNullable(maxMemory);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkspaceQuota that)) {
            return false;
        }
        return maxSlots == that.maxSlots && maxRunning == that.maxRunning && Objects.equals(maxCpu, that.maxCpu)
                && Objects.equals(maxMemory, that.maxMemory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxSlots, maxRunning, maxCpu, maxMemory);
    }
}
