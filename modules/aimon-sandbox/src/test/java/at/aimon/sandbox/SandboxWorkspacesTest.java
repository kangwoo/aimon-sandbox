package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.binding.BindingRejectedException;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.CloseCause;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.InMemorySessionOptionsStore;
import at.aimon.sandbox.workspace.Page;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;
import at.aimon.sandbox.workspace.SessionOptionsView;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.SlotState;
import at.aimon.sandbox.workspace.StaleVersionException;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOrigin;
import at.aimon.sandbox.workspace.WorkspaceOwner;
import at.aimon.sandbox.workspace.WorkspaceProvisioningException;
import at.aimon.sandbox.workspace.WorkspaceQuery;
import at.aimon.sandbox.workspace.WorkspaceScan;
import at.aimon.sandbox.workspace.WorkspaceState;
import at.aimon.sandbox.workspace.WorkspaceView;

/** {@link SandboxWorkspaces}: create and provision, purge, listings, session options (management design §6.1–§6.6). */
class SandboxWorkspacesTest {

    private static final Principal BOB = Principal.user("bob");
    private static final SessionId UNKNOWN = SessionId.generate();
    private final SessionId session = SessionId.generate();
    private SandboxHarness harness = owned(UnaryOperator.identity());

    @AfterEach
    void close() {
        harness.close();
    }

    /** Every session belongs to ALICE but {@link #UNKNOWN}, whose owner is not known. */
    private static SandboxHarness owned(UnaryOperator<SandboxSettings.Builder> settings) {
        return SandboxHarness.builder().settings(settings).sessionOwnerLookup(SandboxWorkspacesTest::owner).build();
    }

    private static Optional<Principal> owner(SessionId id) {
        return id.equals(UNKNOWN) ? Optional.empty() : Optional.of(ALICE);
    }

    private SandboxWorkspaces workspaces() {
        return harness.sandbox.workspaces();
    }

    private SandboxWorkspace record(SandboxWorkspaceId id) {
        return harness.store.find(id).orElseThrow();
    }

    private List<SandboxWorkspace> records() {
        return harness.store.scan(WorkspaceScan.builder().build());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // create and provision (§6.1)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.1: create provisions primary before returning and records what it was asked for")
    void createProvisionsAndReturnsRunning() {
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("ticket 4711"), ALICE);

        assertThat(view.id().value()).startsWith("wsm:");
        assertThat(view.slot("primary").orElseThrow().state()).isEqualTo(SlotState.RUNNING);
        assertThat(view.slot("primary").orElseThrow().seeded()).isTrue();
        assertThat(view.name()).contains("ticket 4711");
        assertThat(view.origin()).isEqualTo(WorkspaceOrigin.MANAGED);
        assertThat(view.access()).isEqualTo(SandboxSettings.WorkspaceAccess.PRINCIPAL);
        assertThat(view.owner()).isEqualTo(WorkspaceOwner.of(TenantId.DEFAULT, ALICE));
        assertThat(record(view.id()).profile()).contains("standard");
        assertThat(harness.local.sandboxCount()).isOne();
        assertThat(harness.events).filteredOn(e -> e.type() == SandboxEvent.Type.WORKSPACE_CREATED).singleElement()
                .satisfies(e -> {
                    assertThat(e.workspace()).contains(view.id());
                    assertThat(e.actor()).contains("default/USER:alice");
                });
    }

    @Test
    void twoCreatesWithOneNameAreTwoWorkspaces() {
        final WorkspaceView first = workspaces().create(CreateWorkspace.named("same"), ALICE);
        final WorkspaceView second = workspaces().create(CreateWorkspace.named("same"), ALICE);

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(harness.local.sandboxCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("§6.1: max-managed-per-owner refuses before any write, per owner")
    void maxManagedPerOwnerRefusesBeforeAnyWrite() {
        harness.close();
        harness = owned(s -> s.maxManagedPerOwner(2));
        workspaces().create(CreateWorkspace.named("a"), ALICE);
        workspaces().create(CreateWorkspace.named("b"), ALICE);

        assertThatThrownBy(() -> workspaces().create(CreateWorkspace.named("c"), ALICE))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("max-managed-per-owner");
        assertThat(records()).hasSize(2);
        assertThat(workspaces().create(CreateWorkspace.named("bob's"), BOB).origin())
                .isEqualTo(WorkspaceOrigin.MANAGED);
    }

    @Test
    @DisplayName("§6.1: an admission refusal keeps the record, slot absent, and it counts toward the limit")
    void anAdmissionRefusalKeepsTheRecordAndCounts() throws Exception {
        harness.close();
        harness = owned(s -> s.maxRunningPerTenant(1).maxManagedPerOwner(1));
        SandboxHarness.bash(harness.mainTurn(SessionId.generate(), ALICE), "true");

        final WorkspaceProvisioningException refused = catchThrowableOfType(WorkspaceProvisioningException.class,
                () -> workspaces().create(CreateWorkspace.named("refused"), ALICE));

        assertThat(refused.getMessage()).contains("cannot start a sandbox");
        assertThat(refused.workspaceId().value()).startsWith("wsm:");
        assertThat(record(refused.workspaceId()).slots()).isEmpty();
        assertThatThrownBy(() -> workspaces().create(CreateWorkspace.named("next"), ALICE))
                .hasMessageContaining("max-managed-per-owner");
    }

    @Test
    @DisplayName("§6.1: a failed provisioning keeps the record FAILED; provision retries after the backoff")
    void aFailedProvisioningIsRetriedByProvision() {
        harness.faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

        final WorkspaceProvisioningException failed = catchThrowableOfType(WorkspaceProvisioningException.class,
                () -> workspaces().create(CreateWorkspace.named("flaky"), ALICE));
        final SandboxWorkspaceId id = failed.workspaceId();

        assertThat(record(id).slot("primary").orElseThrow().state()).isEqualTo(SlotState.FAILED);
        assertThat(workspaces().describe(id, ALICE).orElseThrow().slot("primary").orElseThrow().failure())
                .hasValueSatisfying(f -> assertThat(f.step()).isEqualTo("create"));
        assertThatThrownBy(() -> workspaces().provision(id, ALICE)).isInstanceOf(WorkspaceProvisioningException.class)
                .hasMessageContaining("next attempt");

        harness.clock.advance(Duration.ofMinutes(1));
        assertThat(workspaces().provision(id, ALICE).slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.RUNNING);
    }

    @Test
    @DisplayName("§6.1: the requested profile survives a refused first provisioning")
    void theRequestedProfileIsKeptWhenNoSlotExistsYet() {
        harness.close();
        harness = SandboxHarness.builder().profile(SandboxTestProfiles.local("standard").build())
                .profile(SandboxTestProfiles.local("big").build()).build();
        harness.faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.TRANSIENT));
        final SandboxWorkspaceId id = catchThrowableOfType(WorkspaceProvisioningException.class,
                () -> workspaces().create(CreateWorkspace.builder().name("big one").profile("big").build(), ALICE))
                .workspaceId();
        harness.store.update(id, record(id).version(), record(id).toBuilder().slots(Map.of()).build());

        assertThat(workspaces().provision(id, ALICE).slot("primary").orElseThrow().profile()).isEqualTo("big");
    }

    @Test
    void createRefusesAnUnknownProfileABadNameAndUnsharedTenantAccessBeforeAnyWrite() {
        assertThatThrownBy(
                () -> workspaces().create(CreateWorkspace.builder().name("x").profile("nope").build(), ALICE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'nope' is not configured");
        assertThatThrownBy(() -> CreateWorkspace.named(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreateWorkspace.named("x".repeat(81))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreateWorkspace.named("a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CreateWorkspace.named("x".repeat(80)).name()).hasSize(80);
        assertThatThrownBy(() -> workspaces().create(
                CreateWorkspace.builder().name("shared").access(SandboxSettings.WorkspaceAccess.TENANT).build(), ALICE))
                .isInstanceOf(SandboxConfigurationException.class).hasMessageContaining("allow-shared-workspaces");
        assertThat(records()).isEmpty();
        assertThat(harness.faults.calls(Operation.CREATE)).isZero();
    }

    @Test
    @DisplayName("§6.5: access tenant needs the opt-in in a principal deployment, and nothing in a tenant one")
    void tenantAccessIsAllowedWithTheOptInOrInATenantDeployment() {
        final CreateWorkspace shared = CreateWorkspace.builder().name("shared")
                .access(SandboxSettings.WorkspaceAccess.TENANT).build();
        harness.close();
        harness = owned(s -> s.allowSharedWorkspaces(true));
        assertThat(workspaces().create(shared, ALICE).access()).isEqualTo(SandboxSettings.WorkspaceAccess.TENANT);

        harness.close();
        harness = owned(s -> s.workspaceAccess(SandboxSettings.WorkspaceAccess.TENANT));
        assertThat(workspaces().create(shared, ALICE).access()).isEqualTo(SandboxSettings.WorkspaceAccess.TENANT);
        assertThat(workspaces().create(
                CreateWorkspace.builder().name("narrow").access(SandboxSettings.WorkspaceAccess.PRINCIPAL).build(),
                ALICE).access()).isEqualTo(SandboxSettings.WorkspaceAccess.PRINCIPAL);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // lifecycle (§6.6)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.6: purge deletes the record, then the sessions' attachments")
    void purgeDeletesTheRecordThenTheOptions() {
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("doomed"), ALICE);
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.of(view.id(), UnattachedRule.REJECT),
                ALICE);

        assertThatThrownBy(() -> workspaces().purge(view.id(), ALICE)).hasMessageContaining("close it before");
        workspaces().close(view.id(), ALICE);
        workspaces().purge(view.id(), ALICE);

        assertThat(harness.store.find(view.id())).isEmpty();
        final SessionOptionsView options = workspaces().sessionOptions(session, ALICE).orElseThrow();
        assertThat(options.workspace()).isEmpty();
        assertThat(options.unattached()).contains(UnattachedRule.REJECT);
        assertThat(harness.events).filteredOn(e -> e.type() == SandboxEvent.Type.WORKSPACE_PURGED).singleElement()
                .satisfies(e -> assertThat(e.actor()).contains("default/USER:alice"));
        workspaces().purge(view.id(), ALICE);
    }

    @Test
    @DisplayName("§6.6: a DERIVED workspace cannot be purged — its tombstone guards its session")
    void aDerivedWorkspaceCannotBePurged() throws Exception {
        final SessionId derived = SessionId.generate();
        SandboxHarness.bash(harness.mainTurn(derived, ALICE), "true");
        final SandboxWorkspaceId id = SandboxWorkspaceId.of("ws:" + derived.value());
        workspaces().close(id, ALICE);

        assertThatThrownBy(() -> workspaces().purge(id, ALICE)).hasMessageContaining("only a managed");
        assertThat(harness.store.find(id)).isPresent();
    }

    @Test
    @DisplayName("§6.6: an idle-closed managed workspace reopens on use; an explicit close needs reopen, which provisions")
    void closedManagedWorkspacesComeBack() {
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("idle"), ALICE);
        harness.clock.advance(Duration.ofHours(2));
        harness.sandbox.janitor().runOnce();
        harness.clock.advance(Duration.ofHours(24));
        harness.sandbox.janitor().runOnce();
        assertThat(record(view.id()).closeCause()).contains(CloseCause.IDLE);

        assertThat(workspaces().provision(view.id(), ALICE).state()).isEqualTo(WorkspaceState.OPEN);

        workspaces().close(view.id(), ALICE);
        assertThatThrownBy(() -> workspaces().provision(view.id(), ALICE)).hasMessageContaining("closed");
        final WorkspaceView reopened = workspaces().reopen(view.id(), ALICE);
        assertThat(reopened.state()).isEqualTo(WorkspaceState.OPEN);
        assertThat(reopened.slot("primary").orElseThrow().state()).isEqualTo(SlotState.RUNNING);
        assertThat(reopened.slot("primary").orElseThrow().generation()).isEqualTo(3);
    }

    @Test
    @DisplayName("§7: purge racing a reopen — the delete's CAS fails, the workspace stays, purge says so")
    void purgeRacingAReopenKeepsTheWorkspace() {
        final SandboxWorkspaceManager[] manager = new SandboxWorkspaceManager[1];
        harness.close();
        harness = SandboxHarness.builder().store(new DelegatingStore(new InMemorySandboxWorkspaceStore()) {
            private boolean raced;

            @Override
            public void delete(SandboxWorkspaceId id, long expectedVersion) {
                if (!raced) {
                    raced = true;
                    manager[0].reopen(id, ALICE);
                }
                delegate.delete(id, expectedVersion);
            }
        }).build();
        manager[0] = harness.sandbox.manager();
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("raced"), ALICE);
        workspaces().close(view.id(), ALICE);

        assertThatThrownBy(() -> workspaces().purge(view.id(), ALICE)).hasMessageContaining("it was kept");
        assertThat(record(view.id()).state()).isEqualTo(WorkspaceState.OPEN);
        assertThat(harness.events).extracting(SandboxEvent::type).doesNotContain(SandboxEvent.Type.WORKSPACE_PURGED);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // listings and views (§5.4)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void listShowsTheCallersOwnWorkspacesFilteredAndPaged() throws Exception {
        final List<SandboxWorkspaceId> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(workspaces().create(CreateWorkspace.named((i % 2 == 0 ? "build-" : "test-") + i), ALICE).id());
        }
        workspaces().create(CreateWorkspace.named("build-bob"), BOB);
        SandboxHarness.bash(harness.mainTurn(SessionId.generate(), ALICE), "true");
        workspaces().close(mine.get(0), ALICE);

        final List<SandboxWorkspaceId> paged = new ArrayList<>();
        Optional<SandboxWorkspaceId> after = Optional.empty();
        do {
            final Page<WorkspaceView, SandboxWorkspaceId> page = workspaces().list(ALICE, WorkspaceQuery.builder()
                    .origin(WorkspaceOrigin.MANAGED).after(after.orElse(null)).limit(2).build());
            page.items().forEach(v -> paged.add(v.id()));
            after = page.next();
        } while (after.isPresent());

        assertThat(paged).containsExactlyElementsOf(mine.stream().sorted().toList());
        assertThat(workspaces().list(ALICE, WorkspaceQuery.builder().build()).items()).hasSize(6);
        assertThat(workspaces().list(ALICE, WorkspaceQuery.builder().namePrefix("build-").build()).items())
                .extracting(v -> v.name().orElseThrow()).containsExactlyInAnyOrder("build-0", "build-2", "build-4");
        assertThat(workspaces().list(ALICE, WorkspaceQuery.builder().namePrefix("build-").limit(1).build()).next())
                .isPresent();
        assertThat(workspaces().list(ALICE, WorkspaceQuery.builder().states(Set.of(WorkspaceState.CLOSED)).build())
                .items()).extracting(WorkspaceView::id).containsExactly(mine.get(0));
        assertThatThrownBy(() -> WorkspaceQuery.builder().limit(501).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("§5.4: no view carries the provider's sandbox id")
    void viewsCarryNoProviderSandboxId() {
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("secret"), ALICE);
        final String sandboxId = record(view.id()).slot("primary").orElseThrow().providerRef().orElseThrow()
                .sandboxId();

        assertThat(ViewStrings.of(view)).isNotEmpty().noneMatch(s -> s.contains(sandboxId));
        assertThat(view.toString()).doesNotContain(sandboxId);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // session options (§6.2)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.2: the session's owner attaches it; clear keeps where it last ran; events carry session and actor")
    void setAndClearSessionOptions() {
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("attach"), ALICE);

        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(view.id()), ALICE);
        final SessionOptionsView set = workspaces().sessionOptions(session, ALICE).orElseThrow();
        assertThat(set.workspace()).contains(view.id());
        assertThat(set.unattached()).isEmpty();
        assertThat(set.setBy()).isEqualTo(WorkspaceOwner.of(TenantId.DEFAULT, ALICE));

        harness.sandbox.manager().recordLastWorkspace(session,
                harness.sessionOptions.find(session).orElseThrow().version(), view.id());
        workspaces().clearSessionOptions(session, ALICE);
        final SessionSandboxOptions cleared = harness.sessionOptions.find(session).orElseThrow();
        assertThat(cleared.workspace()).isEmpty();
        assertThat(cleared.lastWorkspaceId()).contains(view.id());

        assertThat(harness.events).filteredOn(e -> e.type().isSessionType()).hasSize(2).allSatisfy(e -> {
            assertThat(e.session()).contains(session);
            assertThat(e.actor()).contains("default/USER:alice");
            assertThat(e.workspace()).contains(view.id());
        });
    }

    @Test
    void clearingARecordWithNothingLeftDeletesIt() {
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.rule(UnattachedRule.REJECT), ALICE);
        workspaces().clearSessionOptions(session, ALICE);

        assertThat(harness.sessionOptions.find(session)).isEmpty();
        workspaces().clearSessionOptions(session, ALICE);
    }

    @Test
    @DisplayName("§7: session options are refused without a lookup, for an unknown session and for a non-owner")
    void sessionOptionsAreARightOverTheSession() {
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("x"), ALICE);
        final SessionSandboxOptions.Update attach = SessionSandboxOptions.Update.attach(view.id());

        assertThatThrownBy(() -> workspaces().setSessionOptions(UNKNOWN, attach, ALICE))
                .isInstanceOf(BindingRejectedException.class).hasMessageContaining("unknown session");
        assertThatThrownBy(() -> workspaces().setSessionOptions(session, attach, BOB))
                .isInstanceOf(BindingRejectedException.class).hasMessageContaining("only the owner");
        assertThatThrownBy(() -> workspaces().sessionOptions(session, BOB)).hasMessageContaining("only the owner");
        try (SandboxHarness noLookup = SandboxHarness.standard()) {
            assertThatThrownBy(() -> noLookup.sandbox.workspaces().setSessionOptions(session, attach, ALICE))
                    .isInstanceOf(BindingRejectedException.class).hasMessageContaining("session owner lookup");
        }
        assertThat(harness.sessionOptions.find(session)).isEmpty();
    }

    @Test
    @DisplayName("§7: the target must be a usable, OPEN or idle-closed managed workspace")
    void theAttachmentTargetIsChecked() throws Exception {
        final WorkspaceView bobs = workspaces().create(CreateWorkspace.named("bob's"), BOB);
        final WorkspaceView closed = workspaces().create(CreateWorkspace.named("closed"), ALICE);
        workspaces().close(closed.id(), ALICE);
        final SessionId derived = SessionId.generate();
        SandboxHarness.bash(harness.mainTurn(derived, ALICE), "true");

        assertThatThrownBy(() -> attach(bobs.id())).isInstanceOf(BindingRejectedException.class)
                .hasMessageContaining("not permitted to use sandbox workspace");
        assertThatThrownBy(() -> attach(closed.id())).isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("reopened");
        assertThatThrownBy(() -> attach(SandboxWorkspaceId.of("wsm:00000000-0000-0000-0000-000000000000")))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> attach(SandboxWorkspaceId.of("ws:" + derived.value())))
                .isInstanceOf(BindingRejectedException.class).hasMessageContaining("not a valid managed");
        assertThat(harness.sessionOptions.find(session)).isEmpty();
    }

    private void attach(SandboxWorkspaceId id) {
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), ALICE);
    }

    @Test
    @DisplayName("§7: two setSessionOptions race — the loser re-reads and re-applies, the last writer wins, both emit")
    void twoSetSessionOptionsRace() {
        final WorkspaceView first = workspaces().create(CreateWorkspace.named("first"), ALICE);
        final WorkspaceView second = workspaces().create(CreateWorkspace.named("second"), ALICE);
        final SandboxWorkspaces[] facade = new SandboxWorkspaces[1];
        final InMemorySessionOptionsStore backing = new InMemorySessionOptionsStore();
        harness.close();
        harness = SandboxHarness.builder().sessionOwnerLookup(SandboxWorkspacesTest::owner)
                .sessionOptions(new DelegatingOptionsStore(backing) {
                    private boolean raced;

                    @Override
                    public SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expected) {
                        if (!raced && options.workspace().map(first.id()::equals).orElse(false)) {
                            raced = true;
                            facade[0].setSessionOptions(session, SessionSandboxOptions.Update.attach(second.id()),
                                    ALICE);
                        }
                        return delegate.put(options, expected);
                    }
                }).build();
        facade[0] = workspaces();
        harness.store.createIfAbsent(copyOf(first));
        harness.store.createIfAbsent(copyOf(second));

        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(first.id()), ALICE);

        assertThat(backing.find(session).orElseThrow().workspace()).contains(first.id());
        assertThat(harness.events).filteredOn(e -> e.type() == SandboxEvent.Type.SESSION_OPTIONS_SET).hasSize(2);
    }

    private SandboxWorkspace copyOf(WorkspaceView view) {
        return SandboxWorkspace.builder().id(view.id()).owner(view.owner()).origin(WorkspaceOrigin.MANAGED)
                .incarnation("copied01").stateSince(view.createdAt()).createdAt(view.createdAt())
                .lastActivityAt(view.lastActivityAt()).build();
    }

    @Test
    void aStaleOptionsWriteIsRetriedUntilCasRetriesThenReportedBusy() {
        harness.close();
        harness = SandboxHarness.builder().sessionOwnerLookup(SandboxWorkspacesTest::owner)
                .sessionOptions(new DelegatingOptionsStore(new InMemorySessionOptionsStore()) {
                    @Override
                    public SessionSandboxOptions put(SessionSandboxOptions options, OptionalLong expected) {
                        throw new StaleVersionException(options.session(), 0);
                    }
                }).build();

        assertThatThrownBy(() -> workspaces().setSessionOptions(session,
                SessionSandboxOptions.Update.rule(UnattachedRule.REJECT), ALICE)).hasMessageContaining("busy");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // review of PR 1
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("review M1: owner() is always set, workspaceId() keeps its 0.1.0 signature, workspace() may be empty")
    void sessionEventsKeepTheOldAccessors() {
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.rule(UnattachedRule.REJECT), ALICE);

        final SandboxEvent set = harness.events.stream().filter(e -> e.type() == SandboxEvent.Type.SESSION_OPTIONS_SET)
                .findFirst().orElseThrow();
        assertThat(set.owner()).isEqualTo(WorkspaceOwner.of(TenantId.DEFAULT, ALICE));
        assertThat(set.workspace()).isEmpty();
        assertThatThrownBy(set::workspaceId).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("names no workspace");
        assertThatThrownBy(() -> SandboxEvent.builder().type(SandboxEvent.Type.SESSION_OPTIONS_SET).session(session)
                .cause("x").at(harness.clock.instant()).build()).hasMessageContaining("owner");
    }

    @Test
    @DisplayName("review M1: a listener that fails to link does not fail the provisioning it hears of")
    void aMislinkedListenerDoesNotFailTheTransition() {
        harness.close();
        harness = SandboxHarness.builder().listener(event -> {
            throw new NoSuchMethodError("SandboxEvent.owner()Lat/aimon/sandbox/workspace/WorkspaceOwner;");
        }).build();

        final WorkspaceView view = workspaces().create(CreateWorkspace.named("linked"), ALICE);

        assertThat(view.slot("primary").orElseThrow().state()).isEqualTo(SlotState.RUNNING);
        assertThat(harness.events).extracting(SandboxEvent::type).contains(SandboxEvent.Type.PROVISIONED);
    }

    @Test
    @DisplayName("review M2: a store that ignores the owner and origin filters shows nobody else's workspaces")
    void aStoreIgnoringTheNewFiltersCannotLeakWorkspaces() {
        harness.close();
        harness = SandboxHarness.builder().settings(s -> s.maxManagedPerOwner(2))
                .store(new DelegatingStore(new InMemorySandboxWorkspaceStore()) {
                    @Override
                    public List<SandboxWorkspace> scan(WorkspaceScan scan) {
                        // A 0.1.0-era store: states, tenant, times and the page only.
                        return delegate
                                .scan(WorkspaceScan.builder().states(scan.states()).tenant(scan.tenant().orElse(null))
                                        .afterId(scan.afterId().orElse(null)).limit(scan.limit()).build());
                    }
                }).build();
        workspaces().create(CreateWorkspace.named("bob 1"), BOB);
        workspaces().create(CreateWorkspace.named("bob 2"), BOB);
        final WorkspaceView mine = workspaces().create(CreateWorkspace.named("alice"), ALICE);

        assertThat(workspaces().list(ALICE, WorkspaceQuery.builder().build()).items()).extracting(WorkspaceView::id)
                .containsExactly(mine.id());
        assertThat(workspaces().create(CreateWorkspace.named("alice 2"), ALICE).owner())
                .as("bob's workspaces do not count toward alice's limit")
                .isEqualTo(WorkspaceOwner.of(TenantId.DEFAULT, ALICE));
    }

    @Test
    @DisplayName("review m1: an unprovisioned managed workspace's descriptor declares the profile it was created with")
    void theDescriptorFollowsTheCreatedWithProfile() {
        harness.close();
        harness = SandboxHarness.builder().sessionOwnerLookup(SandboxWorkspacesTest::owner)
                .profile(SandboxTestProfiles.local("standard").build())
                .profile(SandboxTestProfiles.local("big").backgroundCommandTimeout(Duration.ofMinutes(7)).build())
                .build();
        harness.faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.TRANSIENT));
        final SandboxWorkspaceId id = catchThrowableOfType(WorkspaceProvisioningException.class,
                () -> workspaces().create(CreateWorkspace.builder().name("big").profile("big").build(), ALICE))
                .workspaceId();
        harness.store.update(id, record(id).version(), record(id).toBuilder().slots(Map.of()).build());
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), ALICE);

        final at.aimon.core.environment.ExecutionEnvironment turn = harness.mainTurn(session, ALICE);
        assertThat(turn.descriptor().notes().orElseThrow()).contains("profile 'big'");
        assertThat(turn.backgroundCommandTimeout()).contains(Duration.ofMinutes(7));
    }

    @Test
    @DisplayName("review m4: deleteSessionOptions removes the record, lastWorkspaceId and all")
    void deleteSessionOptionsRemovesTheRecord() {
        final WorkspaceView view = workspaces().create(CreateWorkspace.named("forget"), ALICE);
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(view.id()), ALICE);
        workspaces().clearSessionOptions(session, ALICE);
        assertThat(harness.sessionOptions.find(session)).isPresent();

        assertThatThrownBy(() -> workspaces().deleteSessionOptions(session, BOB))
                .hasMessageContaining("only the owner");
        workspaces().deleteSessionOptions(session, ALICE);
        workspaces().deleteSessionOptions(session, ALICE);

        assertThat(harness.sessionOptions.find(session)).isEmpty();
    }

    @Test
    @DisplayName("review m6: provision accepts a primary slot that a definition's sandbox.profile built first")
    void provisionAcceptsTheSlotsOwnProfile() throws Exception {
        harness.close();
        harness = SandboxHarness.builder().sessionOwnerLookup(SandboxWorkspacesTest::owner)
                .profile(SandboxTestProfiles.local("standard").build())
                .profile(SandboxTestProfiles.local("other").build()).build();
        harness.faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.TRANSIENT));
        final SandboxWorkspaceId id = catchThrowableOfType(WorkspaceProvisioningException.class,
                () -> workspaces().create(CreateWorkspace.named("first"), ALICE)).workspaceId();
        harness.store.update(id, record(id).version(), record(id).toBuilder().slots(Map.of()).build());
        SandboxHarness.bash(harness.resolve(at.aimon.core.environment.EnvironmentRequest.builder()
                .agentRuntimeId(SandboxHarness.RUNTIME).sessionId(session).principal(ALICE)
                .agent(at.aimon.core.agent.DefaultAgent.builder().name("other").systemPrompt("p")
                        .model(at.aimon.core.llm.LlmModel.builder().name("m").build())
                        .attributes(Map.of(at.aimon.sandbox.binding.SlotChoice.WORKSPACE_ATTRIBUTE, id.value(),
                                at.aimon.sandbox.binding.SlotChoice.PROFILE_ATTRIBUTE, "other"))
                        .build())
                .build()), "true");
        assertThat(record(id).slot("primary").orElseThrow().profile()).isEqualTo("other");

        assertThat(workspaces().provision(id, ALICE).slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.RUNNING);
        workspaces().close(id, ALICE);
        assertThat(workspaces().reopen(id, ALICE).slot("primary").orElseThrow().profile()).isEqualTo("other");
    }

    @Test
    @DisplayName("review m7: a recorded failure that quotes the provider's sandbox id shows only its hash")
    void aRecordedFailureCarriesNoRawSandboxId() {
        final String[] created = new String[1];
        harness.close();
        harness = SandboxHarness.builder().decorate((provider, clock) -> new DelegatingProvider(provider) {
            @Override
            public at.aimon.sandbox.provider.ProviderSandboxRef create(at.aimon.sandbox.provider.CreateSpec spec) {
                final at.aimon.sandbox.provider.ProviderSandboxRef ref = super.create(spec);
                created[0] = ref.sandboxId();
                return ref;
            }

            @Override
            public Optional<at.aimon.sandbox.provider.ProviderSandbox> status(
                    at.aimon.sandbox.provider.ProviderSandboxRef ref) {
                throw new SandboxProviderException("sandbox " + ref.sandboxId() + " is not ready",
                        SandboxProviderException.Kind.TRANSIENT, null);
            }
        }).build();

        final WorkspaceProvisioningException failed = catchThrowableOfType(WorkspaceProvisioningException.class,
                () -> workspaces().create(CreateWorkspace.named("leaky"), ALICE));
        final WorkspaceView view = workspaces().describe(failed.workspaceId(), ALICE).orElseThrow();

        assertThat(view.slot("primary").orElseThrow().failure().orElseThrow().message()).contains("is not ready")
                .doesNotContain(created[0]);
        assertThat(ViewStrings.of(view)).noneMatch(s -> s.contains(created[0]));
        assertThat(failed.getMessage()).doesNotContain(created[0]);
    }

    @Test
    @DisplayName("review m10: an empty update is a clear and keeps no empty record")
    void anEmptyUpdateIsAClear() {
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.rule(UnattachedRule.REJECT), ALICE);

        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.of(null, null), ALICE);

        assertThat(harness.sessionOptions.find(session)).isEmpty();
        workspaces().setSessionOptions(session, SessionSandboxOptions.Update.of(null, null), ALICE);
        assertThat(harness.sessionOptions.find(session)).isEmpty();
    }
}
