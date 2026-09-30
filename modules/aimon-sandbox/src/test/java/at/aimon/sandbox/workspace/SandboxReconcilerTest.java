package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.DelegatingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ProviderSandboxState;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/**
 * {@link SandboxReconciler}: the §10.4 table row by row, and the implementation-step-4 rows of §16 that are manager
 * logic, on the local provider with a manual clock.
 */
class SandboxReconcilerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ProviderSandboxRef X = ProviderSandboxRef.of("p", "x");
    private static final ProviderSandboxRef Y = ProviderSandboxRef.of("p", "y");

    @Nested
    @DisplayName("classify (pure)")
    class Classify {

        private SandboxWorkspace record(SlotState state, long generation, ProviderSandboxRef ref, String failureStep) {
            final SandboxSlot.Builder slot = SandboxSlot.builder().name("primary").profile("standard").profileHash("h")
                    .state(state).generation(generation).providerRef(ref).lastActivityAt(T0);
            if (failureStep != null) {
                slot.failure(SlotFailure.builder().at(T0).kind(SlotFailure.Kind.PERMANENT).step(failureStep).reason("r")
                        .profileHash("h").build());
            }
            if (state == SlotState.PROVISIONING) {
                slot.provisioning(ProvisioningClaim.of(T0, "node"));
            }
            return SandboxWorkspace.builder().id(SandboxWorkspaceId.of("ws:a"))
                    .owner(WorkspaceOwner.of(TenantId.of("t"), Principal.user("u"))).state(WorkspaceState.OPEN)
                    .incarnation("inc").stateSince(T0).quota(WorkspaceQuota.defaults()).createdAt(T0).lastActivityAt(T0)
                    .build().withSlot(slot.build());
        }

        private ProviderSandbox sandbox(ProviderSandboxRef ref, String incarnation, long generation) {
            return ProviderSandbox.of(ref, ProviderSandboxState.RUNNING,
                    SandboxLabels.labels("test", "ws:a", incarnation, "primary", generation, "t"), null);
        }

        private SandboxReconciler.Verdict classify(SandboxWorkspace record, ProviderSandbox sandbox) {
            return SandboxReconciler.classify(record, sandbox);
        }

        @Test
        void unparseableLabelsAreSkipped() {
            final Map<String, String> labels = new HashMap<>(
                    SandboxLabels.labels("test", "ws:a", "inc", "primary", 1, "t"));
            labels.put(SandboxLabels.GENERATION, "one");
            assertThat(classify(null, ProviderSandbox.of(X, ProviderSandboxState.RUNNING, labels, null)))
                    .isEqualTo(SandboxReconciler.Verdict.SKIP);
            labels.remove(SandboxLabels.SLOT);
            labels.put(SandboxLabels.GENERATION, "1");
            assertThat(classify(null, ProviderSandbox.of(X, ProviderSandboxState.RUNNING, labels, null)))
                    .isEqualTo(SandboxReconciler.Verdict.SKIP);
        }

        @Test
        void aSandboxThatFailedTheLabelComparisonIsNeverOurs() {
            assertThat(classify(record(SlotState.FAILED, 2, null, "labels"), sandbox(X, "inc", 2)))
                    .isEqualTo(SandboxReconciler.Verdict.SKIP);
        }

        @Test
        void noRecordAnotherIncarnationOrNoSuchSlotIsAnOrphan() {
            assertThat(classify(null, sandbox(X, "inc", 1))).isEqualTo(SandboxReconciler.Verdict.ORPHAN);
            assertThat(classify(record(SlotState.RUNNING, 1, X, null), sandbox(X, "old", 1)))
                    .isEqualTo(SandboxReconciler.Verdict.ORPHAN);
            final Map<String, String> otherSlot = new HashMap<>(
                    SandboxLabels.labels("test", "ws:a", "inc", "helper", 1, "t"));
            assertThat(classify(record(SlotState.RUNNING, 1, X, null),
                    ProviderSandbox.of(Y, ProviderSandboxState.RUNNING, otherSlot, null)))
                    .isEqualTo(SandboxReconciler.Verdict.ORPHAN);
        }

        @Test
        void generationsDecideInFlightAndStale() {
            assertThat(classify(record(SlotState.RUNNING, 2, X, null), sandbox(Y, "inc", 3)))
                    .isEqualTo(SandboxReconciler.Verdict.IN_FLIGHT);
            assertThat(classify(record(SlotState.RUNNING, 2, X, null), sandbox(Y, "inc", 1)))
                    .isEqualTo(SandboxReconciler.Verdict.STALE);
        }

        @Test
        void theSameGenerationFollowsTheSlotState() {
            assertThat(classify(record(SlotState.PROVISIONING, 2, null, null), sandbox(Y, "inc", 2)))
                    .isEqualTo(SandboxReconciler.Verdict.IN_FLIGHT);
            assertThat(classify(record(SlotState.FAILED, 2, null, "create"), sandbox(Y, "inc", 2)))
                    .isEqualTo(SandboxReconciler.Verdict.FAILED_LEFTOVER);
            assertThat(classify(record(SlotState.TERMINATED, 2, X, null), sandbox(X, "inc", 2)))
                    .isEqualTo(SandboxReconciler.Verdict.ORPHAN);
            assertThat(classify(record(SlotState.RUNNING, 2, X, null), sandbox(Y, "inc", 2)))
                    .isEqualTo(SandboxReconciler.Verdict.DUPLICATE);
            assertThat(classify(record(SlotState.PAUSED, 2, X, null), sandbox(X, "inc", 2)))
                    .isEqualTo(SandboxReconciler.Verdict.OK);
            assertThat(classify(record(SlotState.RUNNING, 2, X, null), sandbox(X, "inc", 2)))
                    .isEqualTo(SandboxReconciler.Verdict.OK);
        }
    }

    /** Adds sandboxes the local provider does not have to what it lists, and can hide real ones from one listing. */
    static final class PhantomProvider extends DelegatingProvider {
        final List<ProviderSandbox> phantoms = new CopyOnWriteArrayList<>();
        final List<ProviderSandboxRef> destroyed = new CopyOnWriteArrayList<>();
        final Set<ProviderSandboxRef> hideOnce = new HashSet<>();
        final Set<ProviderSandboxRef> reportTerminated = new HashSet<>();

        PhantomProvider(SandboxProvider delegate) {
            super(delegate);
        }

        @Override
        public List<ProviderSandbox> list(Map<String, String> labels) {
            final List<ProviderSandbox> listed = new ArrayList<>();
            for (ProviderSandbox sandbox : delegate.list(labels)) {
                if (!hideOnce.remove(sandbox.ref())) {
                    listed.add(reportTerminated.contains(sandbox.ref())
                            ? ProviderSandbox.of(sandbox.ref(), ProviderSandboxState.TERMINATED, sandbox.labels(), null)
                            : sandbox);
                }
            }
            phantoms.stream().filter(p -> p.labels().entrySet().containsAll(labels.entrySet())).forEach(listed::add);
            return listed;
        }

        @Override
        public Optional<ProviderSandbox> status(ProviderSandboxRef ref) {
            final Optional<ProviderSandbox> phantom = phantoms.stream().filter(p -> p.ref().equals(ref)).findFirst();
            return phantom.isPresent() ? phantom : delegate.status(ref);
        }

        @Override
        public void destroy(ProviderSandboxRef ref) {
            destroyed.add(ref);
            if (!phantoms.removeIf(p -> p.ref().equals(ref))) {
                delegate.destroy(ref);
            }
        }

        ProviderSandbox add(String workspaceId, String incarnation, long generation, Instant createdAt) {
            final ProviderSandbox phantom = ProviderSandbox.of(
                    ProviderSandboxRef.of("local", "phantom-" + phantoms.size() + "-" + generation),
                    ProviderSandboxState.RUNNING,
                    SandboxLabels.labels("test", workspaceId, incarnation, "primary", generation, "default"), null,
                    createdAt);
            phantoms.add(phantom);
            return phantom;
        }
    }

    @Nested
    @DisplayName("scenarios (§16, step 4)")
    class Scenarios {

        private PhantomProvider phantom;
        private final SandboxHarness harness = SandboxHarness.builder()
                .profile(SandboxTestProfiles.local("standard").build())
                .decorate((local, clock) -> phantom = new PhantomProvider(local)).build();

        @AfterEach
        void close() {
            harness.close();
        }

        private SessionId started() throws Exception {
            final SessionId session = SessionId.generate();
            bash(harness.mainTurn(session, ALICE), "true");
            return session;
        }

        private String workspaceId(SessionId session) {
            return "ws:" + session.value();
        }

        private void janitor() {
            harness.sandbox.janitor().runOnce();
        }

        private List<SandboxEvent.Type> events() {
            return harness.events.stream().map(SandboxEvent::type).toList();
        }

        @Test
        void aRunningSlotsOwnSandboxIsLeftAlone() throws Exception {
            final SessionId session = started();
            harness.clock.advance(Duration.ofHours(1));

            janitor();

            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
            assertThat(phantom.destroyed).isEmpty();
        }

        @Test
        void aDeletedRecordsSandboxIsDestroyedOnceTheGraceHasPassed() throws Exception {
            final SessionId session = started();
            final SandboxWorkspace record = harness.record(session);
            harness.store.delete(record.id(), record.version());

            janitor();
            harness.clock.advance(Duration.ofMinutes(9));
            janitor();
            assertThat(harness.local.sandboxCount()).isOne();

            harness.clock.advance(Duration.ofMinutes(1));
            janitor();
            assertThat(harness.local.sandboxCount()).isZero();
        }

        @Test
        void aNodeRestartedWithAnEmptyInMemoryStoreReclaimsWhatItForgot() throws Exception {
            final SessionId session = started();
            try (SandboxHarness restarted = SandboxHarness.builder().local(harness.local).build()) {
                restarted.clock.set(harness.clock.instant());
                assertThat(bash(restarted.mainTurn(session, ALICE), "echo fresh").stdout()).isEqualTo("fresh\n");
                assertThat(harness.local.sandboxCount()).isEqualTo(2);

                restarted.sandbox.janitor().runOnce();
                restarted.clock.advance(Duration.ofMinutes(10));
                restarted.sandbox.janitor().runOnce();

                // The forgotten sandbox carries another incarnation: an orphan once the grace has passed.
                assertThat(harness.local.sandboxCount()).isOne();
                assertThat(restarted.primary(session).state()).isEqualTo(SlotState.RUNNING);
                assertThat(bash(restarted.mainTurn(session, ALICE), "echo still").stdout()).isEqualTo("still\n");
            }
        }

        @Test
        void aCreateWhoseResponseWasLostLeavesAFailedLeftoverThatIsDestroyedAtOnce() throws Exception {
            harness.faults.injectOnce(Operation.CREATE, Fault.succeedButLoseResponse());
            final SessionId session = SessionId.generate();
            assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                    .hasMessageContaining("could not be provisioned");
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.FAILED);
            assertThat(harness.local.sandboxCount()).isOne();

            janitor();

            assertThat(harness.local.sandboxCount()).isZero();
            assertThat(harness.events).anyMatch(
                    e -> e.type() == SandboxEvent.Type.ORPHAN_DESTROYED && e.cause().startsWith("failed-leftover"));
        }

        @Test
        void aTransientRetryLeavesTheOlderGenerationStale() throws Exception {
            final SessionId session = started();
            final String incarnation = harness.record(session).incarnation();
            final ProviderSandbox older = phantom.add(workspaceId(session), incarnation, 1, null);
            // The record moved on to generation 2 (a retry); the listed generation-1 sandbox is stale.
            harness.store.update(harness.record(session).id(), harness.record(session).version(),
                    harness.record(session).withSlot(harness.primary(session).toBuilder().generation(2).build()));

            janitor();

            assertThat(phantom.destroyed).contains(older.ref());
            assertThat(harness.events)
                    .anyMatch(e -> e.type() == SandboxEvent.Type.ORPHAN_DESTROYED && e.cause().startsWith("stale"));
        }

        @Test
        void aDuplicateFromATakeoverRaceIsDestroyedAfterTheGrace() throws Exception {
            final SessionId session = started();
            final ProviderSandbox duplicate = phantom.add(workspaceId(session), harness.record(session).incarnation(),
                    1, null);

            janitor();
            assertThat(phantom.destroyed).isEmpty();

            harness.clock.advance(Duration.ofMinutes(10));
            janitor();
            assertThat(phantom.destroyed).containsExactly(duplicate.ref());
            assertThat(events()).contains(SandboxEvent.Type.DUPLICATE_DESTROYED);
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
        }

        @Test
        void aNewerGenerationThanTheScannedRecordIsInFlightUntilTheGraceEnds() throws Exception {
            final SessionId session = started();
            final ProviderSandbox ahead = phantom.add(workspaceId(session), harness.record(session).incarnation(), 5,
                    null);

            janitor();
            harness.clock.advance(Duration.ofMinutes(9));
            janitor();
            assertThat(phantom.destroyed).isEmpty();

            harness.clock.advance(Duration.ofMinutes(1));
            janitor();
            assertThat(phantom.destroyed).containsExactly(ahead.ref());
        }

        @Test
        void aServerClockAheadCannotHastenADestroy() throws Exception {
            final SessionId session = started();
            // The provider claims the sandbox is a day old; this node has only just seen it.
            final ProviderSandbox duplicate = phantom.add(workspaceId(session), harness.record(session).incarnation(),
                    1, harness.clock.instant().minus(Duration.ofDays(1)));

            janitor();
            assertThat(phantom.destroyed).isEmpty();
            harness.clock.advance(Duration.ofMinutes(10));
            janitor();
            assertThat(phantom.destroyed).containsExactly(duplicate.ref());
        }

        @Test
        void sandboxesWithUnparseableLabelsOrOfAnotherDeploymentAreUntouched() throws Exception {
            started();
            final Map<String, String> broken = new HashMap<>(
                    SandboxLabels.labels("test", "ws:x", "inc", "primary", 1, "t"));
            broken.remove(SandboxLabels.GENERATION);
            phantom.phantoms.add(ProviderSandbox.of(ProviderSandboxRef.of("local", "broken"),
                    ProviderSandboxState.RUNNING, broken, null, T0));
            phantom.phantoms
                    .add(ProviderSandbox.of(ProviderSandboxRef.of("local", "foreign"), ProviderSandboxState.RUNNING,
                            SandboxLabels.labels("elsewhere", "ws:x", "inc", "primary", 1, "t"), null, T0));

            janitor();
            harness.clock.advance(Duration.ofHours(1));
            janitor();

            assertThat(phantom.destroyed).isEmpty();
        }

        @Test
        void aSandboxListedOnceWithoutItIsNotLost() throws Exception {
            final SessionId session = started();
            phantom.hideOnce.add(harness.primary(session).providerRef().orElseThrow());

            janitor();

            // status still finds it: a paging miss, not a loss.
            assertThat(harness.primary(session).missingSince()).isEmpty();
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
        }

        @Test
        void aMissingSandboxIsLostOnlyAfterLostConfirmAfterAndTheNextCallRecreatesIt() throws Exception {
            final SessionId session = started();
            harness.local.destroy(harness.primary(session).providerRef().orElseThrow());

            janitor();
            assertThat(harness.primary(session).missingSince()).contains(harness.clock.instant());
            harness.clock.advance(Duration.ofSeconds(60));
            janitor();
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);

            harness.clock.advance(Duration.ofSeconds(30));
            janitor();
            final SandboxSlot lost = harness.primary(session);
            assertThat(lost.state()).isEqualTo(SlotState.TERMINATED);
            assertThat(lost.lostAt()).contains(harness.clock.instant());
            assertThat(lost.missingSince()).isEmpty();
            assertThat(events()).contains(SandboxEvent.Type.LOST);

            final String next = bash(harness.mainTurn(session, ALICE), "echo back").stdout();
            assertThat(next).isEqualTo("back\n");
            assertThat(harness.primary(session).generation()).isEqualTo(2);
        }

        @Test
        void aLostConfirmationTheRecordNoLongerSupportsLeavesTheConnectionAndEmitsNothing() throws Exception {
            final SessionId session = started();
            final SandboxSlot slot = harness.primary(session);
            final ProviderSandboxRef ref = slot.providerRef().orElseThrow();
            final SandboxConnection before = harness.sandbox.manager().connections().get(ref);

            // Another pass saw the sandbox again and cleared the mark: this caller's snapshot is out of date.
            assertThat(harness.sandbox.manager().confirmLost(harness.record(session).id(),
                    slot.toBuilder().missingSince(harness.clock.instant().minusSeconds(3600)).build(),
                    harness.clock.instant())).isFalse();

            assertThat(harness.sandbox.manager().connections().get(ref)).isSameAs(before);
            assertThat(events()).doesNotContain(SandboxEvent.Type.LOST);
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
        }

        @Test
        void aSandboxMissingFromOneListingOrListedTerminatedIsNotLostWhileStatusFindsIt() throws Exception {
            final SessionId session = started();
            final ProviderSandboxRef ref = harness.primary(session).providerRef().orElseThrow();
            phantom.hideOnce.add(ref);
            phantom.reportTerminated.add(ref);

            janitor();
            janitor();
            // Listed TERMINATED counts as missing, but status finds it alive: nothing is recorded.
            assertThat(harness.primary(session).missingSince()).isEmpty();
            assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
        }

        @Test
        void aSandboxSeenAgainClearsItsMissingMark() throws Exception {
            final SessionId session = started();
            final SandboxSlot slot = harness.primary(session);
            harness.sandbox.manager().markMissing(harness.record(session).id(), slot, harness.clock.instant());
            assertThat(harness.primary(session).missingSince()).isPresent();

            janitor();

            assertThat(harness.primary(session).missingSince()).isEmpty();
        }

        @Test
        void aFailedListingEndsThePassWithoutInferringAnything() throws Exception {
            final SessionId session = started();
            harness.local.destroy(harness.primary(session).providerRef().orElseThrow());
            harness.faults.inject(Operation.LIST, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

            janitor();

            assertThat(harness.primary(session).missingSince()).isEmpty();
        }
    }
}
