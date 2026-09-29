package at.aimon.sandbox.provider;

import java.util.Objects;
import java.util.Optional;

/**
 * The resources one sandbox may use (docs/design/workspace-sandbox.md §13.1). Quantities keep the operator's
 * spelling ({@code 2}, {@code 4Gi}) — the provider translates them, the manager never does arithmetic on them.
 */
public final class ResourceSpec {

    private static final ResourceSpec NONE = new ResourceSpec(null, null, null, null);

    private final String cpu;
    private final String memory;
    private final String disk;
    private final Integer pids;

    private ResourceSpec(String cpu, String memory, String disk, Integer pids) {
        this.cpu = cpu;
        this.memory = memory;
        this.disk = disk;
        this.pids = pids;
    }

    /** @return a spec that asks for nothing in particular */
    public static ResourceSpec none() {
        return NONE;
    }

    /**
     * @param cpu
     *            the CPU quantity, or {@code null}
     * @param memory
     *            the memory quantity, or {@code null}
     * @param disk
     *            the disk quantity, or {@code null}
     * @param pids
     *            the process limit, or {@code null}
     * @return the spec
     */
    public static ResourceSpec of(String cpu, String memory, String disk, Integer pids) {
        return new ResourceSpec(cpu, memory, disk, pids);
    }

    /** @return the CPU quantity */
    public Optional<String> cpu() {
        return Optional.ofNullable(cpu);
    }

    /** @return the memory quantity */
    public Optional<String> memory() {
        return Optional.ofNullable(memory);
    }

    /** @return the disk quantity */
    public Optional<String> disk() {
        return Optional.ofNullable(disk);
    }

    /** @return the process limit */
    public Optional<Integer> pids() {
        return Optional.ofNullable(pids);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResourceSpec that)) {
            return false;
        }
        return Objects.equals(cpu, that.cpu) && Objects.equals(memory, that.memory) && Objects.equals(disk, that.disk)
                && Objects.equals(pids, that.pids);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cpu, memory, disk, pids);
    }

    @Override
    public String toString() {
        return "cpu=" + cpu + ",memory=" + memory + ",disk=" + disk + ",pids=" + pids;
    }
}
