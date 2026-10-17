package at.aimon.sandbox.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.workspace.Page;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionOptionsStore;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.StaleVersionException;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * The contract every {@link SessionOptionsStore} must pass (docs/design/workspace-sandbox-management.md §4.5, §5.2):
 * create-only and CAS puts, {@link StaleVersionException} for a stale, absent or deleted CAS, no version reuse after
 * delete, records that read back exactly as returned, stable {@code byWorkspace}, {@code workspaces} and
 * {@code sessions}
 * paging, and an
 * idempotent {@code deleteByWorkspace} that keeps every other field. {@code InMemorySessionOptionsStore} passes it; a
 * persistent store (step 6) must pass the same suite.
 *
 * <p>
 * Extend it and implement {@link #createStore()}; every test gets a fresh, empty store.
 */
public abstract class SessionOptionsStoreContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final WorkspaceOwner OWNER = WorkspaceOwner.of(TenantId.of("t"), Principal.user("alice"));
    private static final SandboxWorkspaceId A = SandboxWorkspaceId.of("wsm:a");
    private static final SandboxWorkspaceId B = SandboxWorkspaceId.of("wsm:b");

    private SessionOptionsStore store;

    /** @return a fresh, empty store */
    protected abstract SessionOptionsStore createStore();

    @BeforeEach
    void freshStore() {
        store = createStore();
    }

    /** @return the store under test */
    protected final SessionOptionsStore store() {
        return store;
    }

    /**
     * @param session
     *            the session id
     * @param workspace
     *            the attachment, or {@code null}
     * @return a record set by {@code alice} at {@code T0}
     */
    protected static SessionSandboxOptions options(String session, SandboxWorkspaceId workspace) {
        return SessionSandboxOptions.builder().session(SessionId.of(session)).setBy(OWNER).workspace(workspace)
                .updatedAt(T0).build();
    }

    private SessionSandboxOptions create(String session, SandboxWorkspaceId workspace) {
        return store.put(options(session, workspace), OptionalLong.empty());
    }

    @Test
    void createStoresVersionOneAndReadsBackAsReturned() {
        final SessionSandboxOptions created = store.put(options("s1", A).toBuilder().unattached(UnattachedRule.REJECT)
                .lastWorkspaceId(B).updatedAt(T0.plusNanos(123_456_789)).build(), OptionalLong.empty());

        assertThat(created.version()).isEqualTo(1);
        assertThat(store.find(SessionId.of("s1"))).contains(created);
        assertThat(created.workspace()).contains(A);
        assertThat(created.unattached()).contains(UnattachedRule.REJECT);
        assertThat(created.lastWorkspaceId()).contains(B);
    }

    @Test
    void createOfAnExistingRecordThrowsStaleAndChangesNothing() {
        final SessionSandboxOptions created = create("s1", A);

        assertThatThrownBy(() -> store.put(options("s1", B), OptionalLong.empty()))
                .isInstanceOf(StaleVersionException.class);
        assertThat(store.find(SessionId.of("s1"))).contains(created);
    }

    @Test
    void putAtTheCurrentVersionStoresTheNextAndAStaleOneThrows() {
        final SessionSandboxOptions created = create("s1", A);

        final SessionSandboxOptions updated = store.put(created.toBuilder().workspace(B).version(99).build(),
                OptionalLong.of(created.version()));

        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(store.find(SessionId.of("s1"))).contains(updated);
        assertThatThrownBy(() -> store.put(created, OptionalLong.of(created.version())))
                .isInstanceOf(StaleVersionException.class);
        assertThat(store.find(SessionId.of("s1"))).contains(updated);
    }

    @Test
    void putOfAnAbsentRecordWithAnExpectedVersionThrowsStaleAndCreatesNothing() {
        assertThatThrownBy(() -> store.put(options("s1", A), OptionalLong.of(1)))
                .isInstanceOf(StaleVersionException.class);
        assertThat(store.find(SessionId.of("s1"))).isEmpty();
    }

    @Test
    void concurrentCreatesHaveOneWinner() throws Exception {
        final int threads = 8;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final SandboxWorkspaceId workspace = SandboxWorkspaceId.of("wsm:" + i);
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        create("s1", workspace);
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
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deleteIsCasGuardedAndAnAbsentRecordIsANoOp() {
        final SessionSandboxOptions created = create("s1", A);
        final SessionSandboxOptions updated = store.put(created.toBuilder().workspace(B).build(),
                OptionalLong.of(created.version()));

        assertThatThrownBy(() -> store.delete(SessionId.of("s1"), created.version()))
                .isInstanceOf(StaleVersionException.class);
        assertThat(store.find(SessionId.of("s1"))).isPresent();

        store.delete(SessionId.of("s1"), updated.version());
        store.delete(SessionId.of("s1"), updated.version());

        assertThat(store.find(SessionId.of("s1"))).isEmpty();
    }

    @Test
    void aRecordCreatedAgainAfterDeleteNeverReusesAnOldVersion() {
        final SessionSandboxOptions first = create("s1", A);
        final SessionSandboxOptions second = store.put(first.toBuilder().workspace(B).build(),
                OptionalLong.of(first.version()));
        store.delete(SessionId.of("s1"), second.version());

        final SessionSandboxOptions recreated = create("s1", A);

        assertThat(recreated.version()).isGreaterThan(second.version());
        for (SessionSandboxOptions stale : List.of(first, second)) {
            assertThatThrownBy(() -> store.put(stale, OptionalLong.of(stale.version())))
                    .isInstanceOf(StaleVersionException.class);
        }
    }

    @Test
    void byWorkspacePagesInSessionOrderWithoutGapsOrRepeats() {
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            final String session = String.format("s%03d", i);
            final SandboxWorkspaceId workspace = i % 3 == 0 ? B : i % 5 == 0 ? null : A;
            create(session, workspace);
            if (A.equals(workspace)) {
                expected.add(session);
            }
        }
        final List<String> seen = new ArrayList<>();
        Optional<SessionId> after = Optional.empty();
        while (true) {
            final Page<SessionSandboxOptions, SessionId> page = store.byWorkspace(A, after, 4);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(4);
            page.items().forEach(options -> seen.add(options.session().value()));
            if (page.next().isEmpty()) {
                break;
            }
            after = page.next();
        }

        assertThat(seen).isEqualTo(expected);
    }

    @Test
    void workspacesListsEveryAttachedIdOnceInOrder() {
        create("s1", B);
        create("s2", A);
        create("s3", A);
        create("s4", null);
        create("s5", SandboxWorkspaceId.of("wsm:c"));

        final Page<SandboxWorkspaceId, SandboxWorkspaceId> first = store.workspaces(Optional.empty(), 2);
        final Page<SandboxWorkspaceId, SandboxWorkspaceId> second = store.workspaces(first.next(), 2);

        assertThat(first.items()).containsExactly(A, B);
        assertThat(second.items()).containsExactly(SandboxWorkspaceId.of("wsm:c"));
        assertThat(second.next()).isEmpty();
    }

    @Test
    void sessionsPagesEveryRecordInOrder() {
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            final String session = String.format("s%03d", i);
            // Attached or not, and a record that only remembers where its session last ran: sessions lists them all.
            final SessionSandboxOptions record = i % 3 == 0
                    ? options(session, null).toBuilder().lastWorkspaceId(A).build()
                    : options(session, i % 2 == 0 ? A : null);
            store.put(record, OptionalLong.empty());
            expected.add(session);
        }
        final List<String> seen = new ArrayList<>();
        Optional<SessionId> after = Optional.empty();
        boolean deleted = false;
        while (true) {
            final Page<SessionSandboxOptions, SessionId> page = store.sessions(after, 4);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(4);
            page.items().forEach(options -> seen.add(options.session().value()));
            if (page.next().isEmpty()) {
                break;
            }
            after = page.next();
            if (!deleted) {
                // A record deleted between pages, after the cursor: it is not listed, and nothing else is skipped.
                final SessionSandboxOptions gone = store.find(SessionId.of("s009")).orElseThrow();
                store.delete(gone.session(), gone.version());
                expected.remove("s009");
                deleted = true;
            }
        }

        assertThat(seen).isEqualTo(expected);
        assertThat(store.sessions(Optional.of(SessionId.of("s010")), 4).items()).isEmpty();
    }

    @Test
    void deleteByWorkspaceClearsTheAttachmentKeepsTheRestAndIsIdempotent() {
        final SessionSandboxOptions attached = store.put(
                options("s1", A).toBuilder().unattached(UnattachedRule.REJECT).lastWorkspaceId(A).build(),
                OptionalLong.empty());
        final SessionSandboxOptions other = create("s2", B);

        assertThat(store.deleteByWorkspace(A)).isEqualTo(1);
        assertThat(store.deleteByWorkspace(A)).isZero();

        final SessionSandboxOptions cleared = store.find(SessionId.of("s1")).orElseThrow();
        assertThat(cleared.workspace()).isEmpty();
        assertThat(cleared.unattached()).contains(UnattachedRule.REJECT);
        assertThat(cleared.lastWorkspaceId()).contains(A);
        assertThat(cleared.version()).as("a CAS holding the attached record must lose")
                .isGreaterThan(attached.version());
        assertThat(store.find(SessionId.of("s2"))).contains(other);
        assertThat(store.byWorkspace(A, Optional.empty(), 10).items()).isEmpty();
        assertThat(store.workspaces(Optional.empty(), 10).items()).containsExactly(B);
    }
}
