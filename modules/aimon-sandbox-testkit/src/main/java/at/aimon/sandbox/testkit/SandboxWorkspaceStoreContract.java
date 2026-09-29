package at.aimon.sandbox.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.workspace.CloseCause;
import at.aimon.sandbox.workspace.ProvisioningClaim;
import at.aimon.sandbox.workspace.SandboxSlot;
import at.aimon.sandbox.workspace.SandboxWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SlotState;
import at.aimon.sandbox.workspace.StaleVersionException;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOwner;
import at.aimon.sandbox.workspace.WorkspaceScan;
import at.aimon.sandbox.workspace.WorkspaceState;

/**
 * The contract every {@link SandboxWorkspaceStore} must pass (docs/design/workspace-sandbox.md §16): one winner for
 * concurrent {@code createIfAbsent}, {@link StaleVersionException} for a stale, absent or deleted CAS, the version the
 * store assigns (never the caller's, never one a deleted record had), records that read back exactly as returned,
 * scan filters and keyset paging, and tombstones kept until a CAS-guarded delete. {@code InMemorySandboxWorkspaceStore}
 * passes it from
 * implementation step 3; the JDBC store (step 6) must pass the same suite, so the two cannot drift apart.
 *
 * <p>
 * Extend it and implement {@link #createStore()}; every test gets a fresh, empty store.
 */
public abstract class SandboxWorkspaceStoreContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private SandboxWorkspaceStore store;

    /** @return a fresh, empty store */
    protected abstract SandboxWorkspaceStore createStore();

    @BeforeEach
    void freshStore() {
        store = createStore();
    }

    /** @return the store under test */
    protected final SandboxWorkspaceStore store() {
        return store;
    }

    /**
     * @param id
     *            the workspace id
     * @param tenant
     *            the owner's tenant
     * @return an OPEN record created at {@code T0}
     */
    protected static SandboxWorkspace workspace(String id, String tenant) {
        return SandboxWorkspace.builder().id(SandboxWorkspaceId.of(id))
                .owner(WorkspaceOwner.of(TenantId.of(tenant), Principal.user("user-" + id))).state(WorkspaceState.OPEN)
                .incarnation("abcd1234").stateSince(T0).createdAt(T0).lastActivityAt(T0).build();
    }

    private static SandboxSlot slot(long generation) {
        return SandboxSlot.builder().name("primary").profile("standard").profileHash("hash").state(SlotState.RUNNING)
                .generation(generation).providerRef(ProviderSandboxRef.of("test", "sandbox-" + generation)).seeded(true)
                .lastActivityAt(T0).build();
    }

    @Test
    void createIfAbsentStoresVersionOneAndKeepsTheFirstRecord() {
        final SandboxWorkspace first = store.createIfAbsent(workspace("ws:a", "t1"));
        final SandboxWorkspace second = store.createIfAbsent(workspace("ws:a", "t2"));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(store.find(SandboxWorkspaceId.of("ws:a")).orElseThrow().owner().tenant())
                .isEqualTo(TenantId.of("t1"));
    }

    @Test
    void concurrentCreateIfAbsentHasOneWinner() throws Exception {
        final int threads = 16;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<SandboxWorkspace>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String tenant = "t" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    return store.createIfAbsent(workspace("ws:race", tenant));
                }));
            }
            start.countDown();
            final Set<TenantId> winners = new HashSet<>();
            for (Future<SandboxWorkspace> result : results) {
                winners.add(result.get(10, TimeUnit.SECONDS).owner().tenant());
            }
            assertThat(winners).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void updateAtTheCurrentVersionStoresTheNextVersion() {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));

        final SandboxWorkspace updated = store.update(created.id(), 1, created.withSlot(slot(1)));

        assertThat(updated.version()).isEqualTo(2);
        assertThat(store.find(created.id()).orElseThrow()).isEqualTo(updated);
        assertThat(updated.slot("primary").orElseThrow().providerRef())
                .contains(ProviderSandboxRef.of("test", "sandbox-1"));
    }

    @Test
    void updateAtAStaleVersionThrowsAndChangesNothing() {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));
        store.update(created.id(), 1, created.withSlot(slot(1)));

        assertThatThrownBy(() -> store.update(created.id(), 1, created.withSlot(slot(2))))
                .isInstanceOf(StaleVersionException.class);
        assertThat(store.find(created.id()).orElseThrow().slot("primary").orElseThrow().generation()).isEqualTo(1);
    }

    @Test
    void updateOfAnAbsentRecordThrowsStale() {
        assertThatThrownBy(() -> store.update(SandboxWorkspaceId.of("ws:none"), 1, workspace("ws:none", "t")))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void concurrentUpdatesAtOneVersionHaveOneWinner() throws Exception {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));
        final int threads = 8;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final long generation = i + 1;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        store.update(created.id(), 1, created.withSlot(slot(generation)));
                        return true;
                    } catch (StaleVersionException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int wins = 0;
            for (Future<Boolean> result : results) {
                wins += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(wins).isEqualTo(1);
            assertThat(store.find(created.id()).orElseThrow().version()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scanFiltersByStateTenantAndTime() {
        store.createIfAbsent(workspace("ws:open-t1", "t1"));
        store.createIfAbsent(
                workspace("ws:open-t2", "t2").toBuilder().lastActivityAt(T0.plus(Duration.ofHours(2))).build());
        store.createIfAbsent(workspace("ws:closed-t1", "t1").toBuilder().state(WorkspaceState.CLOSED)
                .closeCause(CloseCause.EXPLICIT).stateSince(T0.plus(Duration.ofHours(1))).build());

        assertThat(ids(WorkspaceScan.builder().states(Set.of(WorkspaceState.OPEN)).build()))
                .containsExactly("ws:open-t1", "ws:open-t2");
        assertThat(ids(WorkspaceScan.builder().tenant(TenantId.of("t1")).build())).containsExactly("ws:closed-t1",
                "ws:open-t1");
        assertThat(ids(WorkspaceScan.builder().lastActivityBefore(T0.plus(Duration.ofHours(1))).build()))
                .containsExactly("ws:closed-t1", "ws:open-t1");
        assertThat(ids(WorkspaceScan.builder().stateSinceBefore(T0.plus(Duration.ofMinutes(30))).build()))
                .containsExactly("ws:open-t1", "ws:open-t2");
        assertThat(ids(WorkspaceScan.builder().states(Set.of(WorkspaceState.CLOSED))
                .stateSinceBefore(T0.plus(Duration.ofHours(2))).build())).containsExactly("ws:closed-t1");
    }

    @Test
    void scanPagesInIdOrderWithoutGapsOrRepeats() {
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            final String id = String.format("ws:%03d", i);
            expected.add(id);
            store.createIfAbsent(workspace(id, "t"));
        }
        final List<String> seen = new ArrayList<>();
        WorkspaceScan scan = WorkspaceScan.builder().limit(10).build();
        while (true) {
            final List<SandboxWorkspace> page = store.scan(scan);
            page.forEach(workspace -> seen.add(workspace.id().value()));
            if (page.size() < scan.limit()) {
                break;
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }

        assertThat(seen).isEqualTo(expected);
    }

    @Test
    void closedRecordIsKeptUntilDeleted() {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));
        final SandboxWorkspace closed = store.update(created.id(), 1, created.toBuilder().state(WorkspaceState.CLOSED)
                .closeCause(CloseCause.EXPLICIT).stateSince(T0.plus(Duration.ofMinutes(5))).build());

        assertThat(store.find(created.id())).contains(closed);
        assertThat(ids(WorkspaceScan.builder().states(Set.of(WorkspaceState.CLOSED)).build())).containsExactly("ws:a");

        store.delete(created.id(), closed.version());

        assertThat(store.find(created.id())).isEmpty();
    }

    @Test
    void deleteAtAStaleVersionThrowsAndKeepsTheRecord() {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));
        store.update(created.id(), 1, created.withSlot(slot(1)));

        assertThatThrownBy(() -> store.delete(created.id(), 1)).isInstanceOf(StaleVersionException.class);
        assertThat(store.find(created.id())).isPresent();
    }

    @Test
    void deleteOfAnAbsentRecordIsANoOp() {
        store.delete(SandboxWorkspaceId.of("ws:none"), 7);

        assertThat(store.find(SandboxWorkspaceId.of("ws:none"))).isEmpty();
    }

    @Test
    void aRecordCreatedAgainAfterDeleteNeverReusesAnOldVersion() {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));
        final SandboxWorkspace second = store.update(created.id(), created.version(), created.withSlot(slot(1)));
        final SandboxWorkspace third = store.update(created.id(), second.version(), second.withSlot(slot(2)));
        store.delete(created.id(), third.version());

        final SandboxWorkspace recreated = store.createIfAbsent(workspace("ws:a", "t2"));

        // A caller still holding the deleted record at any of its versions must not CAS the new one (ABA).
        assertThat(recreated.version()).isGreaterThan(third.version());
        for (SandboxWorkspace stale : List.of(created, second, third)) {
            assertThatThrownBy(() -> store.update(created.id(), stale.version(), stale.withSlot(slot(9))))
                    .isInstanceOf(StaleVersionException.class);
        }
        assertThat(store.find(created.id()).orElseThrow().owner().tenant()).isEqualTo(TenantId.of("t2"));
    }

    @Test
    void updateAssignsTheVersionWhateverTheRecordCarries() {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));

        final SandboxWorkspace updated = store.update(created.id(), created.version(),
                created.toBuilder().version(99).build().withSlot(slot(1)));

        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(store.find(created.id()).orElseThrow().version()).isEqualTo(created.version() + 1);
    }

    @Test
    void updateWithAnotherRecordsIdIsRefusedAndChangesNothing() {
        final SandboxWorkspace a = store.createIfAbsent(workspace("ws:a", "t"));
        final SandboxWorkspace b = store.createIfAbsent(workspace("ws:b", "t"));

        assertThatThrownBy(() -> store.update(a.id(), a.version(), b.withSlot(slot(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(a.id())).contains(a);
        assertThat(store.find(b.id())).contains(b);
    }

    @Test
    void updateAfterDeleteThrowsStaleAndCreatesNothing() {
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t"));
        store.delete(created.id(), created.version());

        assertThatThrownBy(() -> store.update(created.id(), created.version(), created.withSlot(slot(1))))
                .isInstanceOf(StaleVersionException.class);
        assertThat(store.find(created.id())).isEmpty();
    }

    @Test
    void recordsReadBackExactlyAsTheStoreReturnedThem() {
        // Nanoseconds that a store may round: whatever it keeps, find must answer the same as the write returned —
        // the manager compares a claim it wrote with the stored one, and a mismatch reads as a lost CAS.
        final Instant precise = T0.plusNanos(123_456_789);
        final SandboxWorkspace created = store.createIfAbsent(workspace("ws:a", "t").toBuilder().createdAt(precise)
                .stateSince(precise).lastActivityAt(precise).build());
        final SandboxSlot claimed = SandboxSlot.builder().name("primary").profile("standard").profileHash("hash")
                .state(SlotState.PROVISIONING).generation(1)
                .provisioning(ProvisioningClaim.of(precise.plusNanos(1), "node-a")).lastActivityAt(precise)
                .lastActiveAt(precise).build();

        final SandboxWorkspace updated = store.update(created.id(), created.version(), created.withSlot(claimed));

        assertThat(store.find(created.id())).contains(updated);
        assertThat(store.find(created.id()).orElseThrow().slot("primary")).isEqualTo(updated.slot("primary"));
        final Instant kept = updated.slot("primary").orElseThrow().provisioning().orElseThrow().since();
        assertThat(Duration.between(kept, precise.plusNanos(1)).abs()).as("kept to at least millisecond precision")
                .isLessThan(Duration.ofMillis(1));
        assertThat(Duration.between(updated.createdAt(), precise).abs()).isLessThan(Duration.ofMillis(1));
    }

    @Test
    void scanPagesOverFilteredOutRecordsWithoutGapsOrRepeats() {
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            final String id = String.format("ws:%03d", i);
            final boolean open = i % 3 != 1;
            store.createIfAbsent(
                    workspace(id, "t").toBuilder().state(open ? WorkspaceState.OPEN : WorkspaceState.CLOSED)
                            .closeCause(open ? null : CloseCause.IDLE).build());
            if (open) {
                expected.add(id);
            }
        }
        final List<String> seen = new ArrayList<>();
        WorkspaceScan scan = WorkspaceScan.builder().states(Set.of(WorkspaceState.OPEN)).limit(7).build();
        while (true) {
            final List<SandboxWorkspace> page = store.scan(scan);
            page.forEach(workspace -> seen.add(workspace.id().value()));
            if (page.size() < scan.limit()) {
                break;
            }
            scan = scan.next(page.get(page.size() - 1).id());
        }

        assertThat(seen).isEqualTo(expected);
    }

    private List<String> ids(WorkspaceScan scan) {
        return store.scan(scan).stream().map(workspace -> workspace.id().value()).toList();
    }
}
