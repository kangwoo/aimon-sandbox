package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.sandbox.SandboxAdministration;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.ViewStrings;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ProviderSandboxState;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.SandboxReconciler.Verdict;

/**
 * {@code SandboxAdministration.sandboxes()} (management design §6.8): every listed sandbox with the reconciler's
 * verdict, on the {@link SandboxReconcilerTest} fixtures — and nothing destroyed, no raw sandbox id shown.
 */
class AdminSandboxesTest {

    private SandboxReconcilerTest.PhantomProvider phantom;
    private final SandboxHarness harness = SandboxHarness.builder()
            .profile(SandboxTestProfiles.local("standard").build())
            .decorate((local, clock) -> phantom = new SandboxReconcilerTest.PhantomProvider(local)).build();

    @AfterEach
    void close() {
        harness.close();
    }

    private SandboxAdministration admin() {
        return SandboxAdministration.of(harness.sandbox);
    }

    private SessionId started() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");
        return session;
    }

    private SandboxView view(ProviderSandboxRef ref) {
        final String hash = SandboxLabels.h(ref.sandboxId());
        return admin().sandboxes().stream().filter(v -> v.refHash().equals(hash)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("§6.8: a running slot's own sandbox is OK, resolved to its workspace, slot and generation")
    void theRecordsCurrentSandboxIsOk() throws Exception {
        final SessionId session = started();
        final SandboxSlot slot = harness.primary(session);

        final SandboxView view = view(slot.providerRef().orElseThrow());

        assertThat(view.verdict()).isEqualTo(Verdict.OK);
        assertThat(view.workspaceId()).contains(harness.record(session).id());
        assertThat(view.slot()).contains("primary");
        assertThat(view.generation()).contains(1L);
        assertThat(view.providerState()).isEqualTo(ProviderSandboxState.RUNNING);
        assertThat(view.pastGrace()).isEmpty();
    }

    @Test
    @DisplayName("§6.8: a newer generation and a PROVISIONING generation are IN_FLIGHT")
    void inFlightSandboxes() throws Exception {
        final SessionId session = started();
        final ProviderSandbox ahead = phantom.add("ws:" + session.value(), harness.record(session).incarnation(), 5,
                null);
        final SandboxWorkspace provisioning = harness.store.createIfAbsent(SandboxWorkspace.builder()
                .id(SandboxWorkspaceId.of("ws:provisioning")).owner(WorkspaceOwner.of(TenantId.DEFAULT, ALICE))
                .incarnation("prov0001").stateSince(harness.clock.instant()).createdAt(harness.clock.instant())
                .lastActivityAt(harness.clock.instant())
                .slots(Map.of("primary",
                        SandboxSlot.builder().name("primary").profile("standard").profileHash("h")
                                .state(SlotState.PROVISIONING).generation(3)
                                .provisioning(ProvisioningClaim.of(harness.clock.instant(), "node"))
                                .lastActivityAt(harness.clock.instant()).build()))
                .build());
        final ProviderSandbox claimed = phantom.add(provisioning.id().value(), "prov0001", 3, null);

        assertThat(view(ahead.ref()).verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(view(claimed.ref()).verdict()).isEqualTo(Verdict.IN_FLIGHT);
    }

    @Test
    @DisplayName("§6.8: another incarnation or no record is ORPHAN; pastGrace only when the provider reports createdAt")
    void orphansAndTheirGrace() throws Exception {
        final SessionId session = started();
        final ProviderSandbox mismatch = phantom.add("ws:" + session.value(), "otherinc", 1,
                harness.clock.instant().minus(Duration.ofMinutes(11)));
        final ProviderSandbox young = phantom.add("ws:gone", "inc00001", 1, harness.clock.instant());
        final ProviderSandbox undated = phantom.add("ws:gone", "inc00001", 2, null);

        assertThat(view(mismatch.ref()).verdict()).isEqualTo(Verdict.ORPHAN);
        assertThat(view(mismatch.ref()).pastGrace()).contains(true);
        assertThat(view(young.ref()).verdict()).isEqualTo(Verdict.ORPHAN);
        assertThat(view(young.ref()).pastGrace()).contains(false);
        assertThat(view(young.ref()).workspaceId()).as("no record to resolve to").isEmpty();
        assertThat(view(undated.ref()).pastGrace()).isEmpty();
        assertThat(phantom.destroyed).as("informational only").isEmpty();
    }

    @Test
    @DisplayName("§6.8: an older generation than the record's is STALE")
    void anOlderGenerationIsStale() throws Exception {
        final SessionId session = started();
        final ProviderSandbox older = phantom.add("ws:" + session.value(), harness.record(session).incarnation(), 1,
                null);
        harness.store.update(harness.record(session).id(), harness.record(session).version(),
                harness.record(session).withSlot(harness.primary(session).toBuilder().generation(2).build()));

        assertThat(view(older.ref()).verdict()).isEqualTo(Verdict.STALE);
        assertThat(view(older.ref()).generation()).contains(1L);
    }

    @Test
    @DisplayName("§6.8: unparseable labels and a failed label comparison are SKIP")
    void labelProblemsAreSkipped() throws Exception {
        final SessionId session = started();
        final Map<String, String> broken = new HashMap<>(SandboxLabels.labels(harness.sandbox.settings().deployment(),
                "ws:" + session.value(), harness.record(session).incarnation(), "primary", 1, "default"));
        broken.remove(SandboxLabels.GENERATION);
        final ProviderSandbox unparseable = ProviderSandbox.of(ProviderSandboxRef.of("local", "broken"),
                ProviderSandboxState.RUNNING, broken, null);
        phantom.phantoms.add(unparseable);
        final SandboxSlot slot = harness.primary(session);
        harness.store.update(harness.record(session).id(), harness.record(session).version(),
                harness.record(session)
                        .withSlot(slot.toBuilder().state(SlotState.FAILED)
                                .failure(SlotFailure.builder().at(harness.clock.instant())
                                        .kind(SlotFailure.Kind.PERMANENT).step("labels").reason("mismatch")
                                        .profileHash("h").build())
                                .build()));

        assertThat(view(unparseable.ref()).verdict()).isEqualTo(Verdict.SKIP);
        assertThat(view(slot.providerRef().orElseThrow()).verdict()).isEqualTo(Verdict.SKIP);
    }

    @Test
    @DisplayName("§5.4: no sandbox view carries a raw provider sandbox id")
    void noViewCarriesARawSandboxId() throws Exception {
        final SessionId session = started();
        phantom.add("ws:gone", "inc00001", 1, null);
        final List<SandboxView> views = admin().sandboxes();
        final List<String> ids = phantom.phantoms.stream().map(p -> p.ref().sandboxId()).toList();
        final String running = harness.primary(session).providerRef().orElseThrow().sandboxId();

        assertThat(views).hasSize(2);
        assertThat(ViewStrings.of(views)).noneMatch(s -> s.contains(running) || ids.stream().anyMatch(s::contains));
        assertThat(views.toString()).doesNotContain(running);
    }

    @Test
    @DisplayName("§6.8: a failing provider listing fails the call — no partial view")
    void aFailingListingFailsTheCall() throws Exception {
        started();
        harness.faults.inject(Operation.LIST, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

        assertThatThrownBy(() -> admin().sandboxes()).isInstanceOf(SandboxProviderException.class);
        assertThat(Optional.of(harness.faults.calls(Operation.LIST))).contains(1);
    }
}
