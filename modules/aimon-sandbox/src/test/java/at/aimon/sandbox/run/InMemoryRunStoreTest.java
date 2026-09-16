package at.aimon.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.model.RunState;
import at.aimon.sandbox.model.SandboxRun;

class InMemoryRunStoreTest {

    private InMemoryRunStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRunStore();
    }

    private SandboxRun createRun(String runId) {
        return SandboxRun.builder().runId(runId).identifier("test").sandboxId("container-1").state(RunState.QUEUED)
                .createdAt(Instant.now()).build();
    }

    @Test
    void save_And_FindById() {
        SandboxRun run = createRun("run-1");
        store.save(run);

        Optional<SandboxRun> found = store.findById("run-1");
        assertThat(found).isPresent();
        assertThat(found.get().getRunId()).isEqualTo("run-1");
    }

    @Test
    void findById_NotFound_ReturnsEmpty() {
        assertThat(store.findById("nonexistent")).isEmpty();
    }

    @Test
    void update_ExistingRun_ReturnsUpdated() {
        store.save(createRun("run-1"));

        SandboxRun updated = store.update("run-1", run -> run.withState(RunState.RUNNING));

        assertThat(updated.getState()).isEqualTo(RunState.RUNNING);
        assertThat(store.findById("run-1").get().getState()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void update_NonexistentRun_ThrowsException() {
        assertThatThrownBy(() -> store.update("nonexistent", run -> run.withState(RunState.RUNNING)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Run not found");
    }

    @Test
    void save_DuplicateRunId_ThrowsException() {
        SandboxRun run = createRun("run-1");
        store.save(run);

        assertThatThrownBy(() -> store.save(createRun("run-1"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Run already exists");
    }

    @Test
    void save_ExceedsMaxCapacity_EvictsOldestEntries() {
        InMemoryRunStore boundedStore = new InMemoryRunStore(3);

        boundedStore.save(createRun("run-1"));
        boundedStore.save(createRun("run-2"));
        boundedStore.save(createRun("run-3"));
        boundedStore.save(createRun("run-4"));

        assertThat(boundedStore.findById("run-1")).isEmpty();
        assertThat(boundedStore.findById("run-2")).isPresent();
        assertThat(boundedStore.findById("run-3")).isPresent();
        assertThat(boundedStore.findById("run-4")).isPresent();
    }

    @Test
    void constructor_InvalidMaxCapacity_ThrowsException() {
        assertThatThrownBy(() -> new InMemoryRunStore(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCapacity must be >= 1");
    }

    @Test
    void concurrentSaveAndUpdate_MaintainsConsistency() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // Pre-populate runs so updates don't fail
        for (int i = 0; i < threadCount; i++) {
            store.save(createRun("run-" + i));
        }

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    // Each thread saves a new run and updates its pre-populated run
                    store.save(createRun("new-run-" + index));
                    store.update("run-" + index, run -> run.withState(RunState.RUNNING));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertThat(errors.get()).isZero();

        // Verify all runs exist and have correct state
        for (int i = 0; i < threadCount; i++) {
            assertThat(store.findById("run-" + i)).isPresent();
            assertThat(store.findById("run-" + i).get().getState()).isEqualTo(RunState.RUNNING);
            assertThat(store.findById("new-run-" + i)).isPresent();
        }
    }
}
