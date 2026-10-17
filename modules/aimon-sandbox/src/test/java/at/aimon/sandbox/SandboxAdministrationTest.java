package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.binding.BindingRejectedException;
import at.aimon.sandbox.binding.SandboxTenantResolver;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.workspace.AdminWorkspaceQuery;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.Page;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionOptionsView;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.SlotState;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceDetail;
import at.aimon.sandbox.workspace.WorkspaceOrigin;
import at.aimon.sandbox.workspace.WorkspaceOwner;
import at.aimon.sandbox.workspace.WorkspaceScan;
import at.aimon.sandbox.workspace.WorkspaceState;
import at.aimon.sandbox.workspace.WorkspaceView;

/** {@link SandboxAdministration} (management design §5.5, §6.7): listings, detail, and the operator's writes. */
class SandboxAdministrationTest {

    private static final Principal ALICE = Principal.user("t1-alice");
    private static final Principal BOB = Principal.user("t2-bob");
    /** An operator from no tenant the resolver knows. */
    private static final Principal OPERATOR = Principal.service("ops-console", "Operations console");
    private static final SandboxTenantResolver BY_PREFIX = principal -> principal.getId().contains("-")
            && !principal.getId().startsWith("ops")
                    ? TenantId.of(principal.getId().substring(0, principal.getId().indexOf('-')))
                    : null;

    private SandboxHarness harness = harness(new InMemorySandboxWorkspaceStore());

    private static SandboxHarness harness(at.aimon.sandbox.workspace.SandboxWorkspaceStore store) {
        return SandboxHarness.builder().store(store).tenantResolver(BY_PREFIX)
                .sessionOwnerLookup(id -> Optional.of(ALICE))
                .settings(s -> s.maxRunningPerTenant(SandboxSettings.UNLIMITED)).build();
    }

    @AfterEach
    void close() {
        harness.close();
    }

    private SandboxAdministration admin() {
        return SandboxAdministration.of(harness.sandbox);
    }

    private SandboxWorkspaceId create(String name, Principal owner) {
        return harness.sandbox.workspaces().create(CreateWorkspace.named(name), owner).id();
    }

    private List<SandboxEvent> events(SandboxEvent.Type type) {
        return harness.events.stream().filter(e -> e.type() == type).toList();
    }

    @Test
    @DisplayName("§5.5: the listing filters by state, origin, name, tenant and owner, and pages by id")
    void listingFiltersAndPages() throws Exception {
        final List<SandboxWorkspaceId> alices = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            alices.add(create("alice-" + i, ALICE));
        }
        final SandboxWorkspaceId bobs = create("bob-0", BOB);
        bash(harness.mainTurn(SessionId.generate(), ALICE), "true");
        harness.sandbox.workspaces().close(alices.get(0), ALICE);

        final List<SandboxWorkspaceId> paged = new ArrayList<>();
        Optional<SandboxWorkspaceId> after = Optional.empty();
        do {
            final Page<WorkspaceView, SandboxWorkspaceId> page = admin().workspaces(AdminWorkspaceQuery.builder()
                    .origin(WorkspaceOrigin.MANAGED).after(after.orElse(null)).limit(2).build());
            page.items().forEach(view -> paged.add(view.id()));
            after = page.next();
        } while (after.isPresent());

        final List<SandboxWorkspaceId> managed = new ArrayList<>(alices);
        managed.add(bobs);
        assertThat(paged).containsExactlyElementsOf(managed.stream().sorted().toList());
        assertThat(ids(AdminWorkspaceQuery.builder().build())).hasSize(7);
        assertThat(ids(AdminWorkspaceQuery.builder().tenant(TenantId.of("t2")).build())).containsExactly(bobs);
        assertThat(ids(AdminWorkspaceQuery.builder().owner(WorkspaceOwner.of(TenantId.of("t1"), ALICE))
                .origin(WorkspaceOrigin.DERIVED).build())).hasSize(1);
        assertThat(ids(AdminWorkspaceQuery.builder().namePrefix("bob").build())).containsExactly(bobs);
        assertThat(ids(AdminWorkspaceQuery.builder().states(Set.of(WorkspaceState.CLOSED)).build()))
                .containsExactly(alices.get(0));
        assertThatThrownBy(() -> AdminWorkspaceQuery.builder().limit(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<SandboxWorkspaceId> ids(AdminWorkspaceQuery query) {
        return admin().workspaces(query).items().stream().map(WorkspaceView::id).toList();
    }

    @Test
    @DisplayName("§5.5: a store that ignores the tenant, owner and origin filters cannot mislead the listing")
    void theListingReChecksWhatTheStoreReturns() {
        harness.close();
        harness = harness(new DelegatingStore(new InMemorySandboxWorkspaceStore()) {
            @Override
            public List<SandboxWorkspace> scan(WorkspaceScan scan) {
                return delegate.scan(WorkspaceScan.builder().states(scan.states()).afterId(scan.afterId().orElse(null))
                        .limit(scan.limit()).build());
            }
        });
        create("alice", ALICE);
        final SandboxWorkspaceId bobs = create("bob", BOB);

        assertThat(ids(AdminWorkspaceQuery.builder().tenant(TenantId.of("t2")).build())).containsExactly(bobs);
        assertThat(ids(AdminWorkspaceQuery.builder().owner(WorkspaceOwner.of(TenantId.of("t2"), BOB)).build()))
                .containsExactly(bobs);
        assertThat(ids(AdminWorkspaceQuery.builder().origin(WorkspaceOrigin.DERIVED).build()))
                .as("the store ignores origin too").isEmpty();
        assertThat(ids(AdminWorkspaceQuery.builder().tenant(TenantId.of("t9")).build())).isEmpty();
    }

    @Test
    @DisplayName("§5.5: a workspace's detail lists its attached sessions, which page by session id")
    void detailAndSessions() {
        final SandboxWorkspaceId id = create("shared", ALICE);
        final List<SessionId> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final SessionId session = SessionId.of(String.format("s%02d", i));
            sessions.add(session);
            harness.sandbox.workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), ALICE);
        }

        final WorkspaceDetail detail = admin().workspace(id).orElseThrow();
        assertThat(detail.workspace().id()).isEqualTo(id);
        assertThat(detail.sessions().items()).extracting(SessionOptionsView::session)
                .containsExactlyElementsOf(sessions);

        final Page<SessionOptionsView, SessionId> first = admin().sessions(id, Optional.empty(), 3);
        final Page<SessionOptionsView, SessionId> second = admin().sessions(id, first.next(), 3);
        assertThat(first.items()).hasSize(3);
        assertThat(second.items()).extracting(SessionOptionsView::session).containsExactly(sessions.get(3),
                sessions.get(4));
        assertThat(second.next()).isEmpty();
        assertThat(admin().workspace(SandboxWorkspaceId.of("wsm:none"))).isEmpty();
    }

    @Test
    @DisplayName("§5.5: create on behalf of runs the owner's gate and limits, and records the actor")
    void createOnBehalfOf() {
        harness.close();
        harness = SandboxHarness.builder().tenantResolver(BY_PREFIX).settings(s -> s.maxManagedPerOwner(1)).build();

        final WorkspaceView created = admin().create(CreateWorkspace.named("for alice"), ALICE, OPERATOR);

        assertThat(created.owner()).isEqualTo(WorkspaceOwner.of(TenantId.of("t1"), ALICE));
        assertThat(created.slot("primary").orElseThrow().state()).isEqualTo(SlotState.RUNNING);
        assertThat(events(SandboxEvent.Type.WORKSPACE_CREATED)).singleElement()
                .satisfies(e -> assertThat(e.actor()).contains("SERVICE:ops-console"));
        assertThatThrownBy(() -> admin().create(CreateWorkspace.named("second"), ALICE, OPERATOR))
                .hasMessageContaining("max-managed-per-owner");
        assertThatThrownBy(() -> admin().create(CreateWorkspace.named("nobody's"), OPERATOR, OPERATOR))
                .as("the owner passes the principal gate").isInstanceOf(BindingRejectedException.class);
    }

    @Test
    @DisplayName("§6.2: the operator sets any session's options, but the lookup and the consistency check hold")
    void theAdministratorsSessionOptions() throws Exception {
        final SandboxWorkspaceId alices = create("alice's", ALICE);
        final SandboxWorkspaceId bobs = create("bob's", BOB);
        final SessionId session = SessionId.generate();

        admin().setSessionOptions(session, SessionSandboxOptions.Update.attach(alices), OPERATOR);

        assertThat(harness.sessionOptions.find(session).orElseThrow().workspace()).contains(alices);
        assertThat(harness.sessionOptions.find(session).orElseThrow().setBy().principal())
                .isEqualTo("SERVICE:ops-console");
        assertThat(bash(harness.mainTurn(session, ALICE), "echo attached").stdout()).isEqualTo("attached\n");
        assertThat(events(SandboxEvent.Type.SESSION_OPTIONS_SET)).singleElement()
                .satisfies(e -> assertThat(e.actor()).contains("SERVICE:ops-console"));
        assertThatThrownBy(
                () -> admin().setSessionOptions(session, SessionSandboxOptions.Update.attach(bobs), OPERATOR))
                .isInstanceOf(BindingRejectedException.class).hasMessageContaining("not permitted to use");

        admin().clearSessionOptions(session, OPERATOR);
        assertThat(harness.sessionOptions.find(session).orElseThrow().workspace()).isEmpty();
        assertThat(events(SandboxEvent.Type.SESSION_OPTIONS_CLEARED)).singleElement()
                .satisfies(e -> assertThat(e.actor()).contains("SERVICE:ops-console"));

        try (SandboxHarness noLookup = SandboxHarness.standard()) {
            assertThatThrownBy(() -> SandboxAdministration.of(noLookup.sandbox).setSessionOptions(session,
                    SessionSandboxOptions.Update.rule(UnattachedRule.REJECT), OPERATOR))
                    .hasMessageContaining("session owner lookup");
        }
    }

    @Test
    @DisplayName("§6.7: terminate ends the slot's sandbox; the workspace stays OPEN and the next use re-provisions")
    void terminateSlot() throws Exception {
        final SandboxWorkspaceId id = create("terminated", ALICE);
        final SessionId session = SessionId.generate();
        harness.sandbox.workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), ALICE);
        bash(harness.mainTurn(session, ALICE), "echo before > /workspace/repo/f");

        admin().terminateSlot(id, "primary", OPERATOR);
        admin().terminateSlot(id, "primary", OPERATOR);

        assertThat(harness.store.find(id).orElseThrow().state()).isEqualTo(WorkspaceState.OPEN);
        assertThat(harness.store.find(id).orElseThrow().slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.TERMINATED);
        assertThat(events(SandboxEvent.Type.TERMINATED)).singleElement().satisfies(e -> {
            assertThat(e.cause()).isEqualTo("terminated by administrator");
            assertThat(e.actor()).contains("SERVICE:ops-console");
        });
        assertThat(bash(harness.mainTurn(session, ALICE), "ls /workspace/repo").notices())
                .anyMatch(notice -> notice.contains("recreated (generation 2)"));
        assertThatThrownBy(() -> admin().terminateSlot(SandboxWorkspaceId.of("wsm:none"), "primary", OPERATOR))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("§5.5: close, reopen and purge need no owner right, and their events carry the actor")
    void closeReopenAndPurgeAsTheOperator() {
        final SandboxWorkspaceId id = create("operated", ALICE);

        admin().close(id, OPERATOR);
        assertThat(harness.store.find(id).orElseThrow().state()).isEqualTo(WorkspaceState.CLOSED);
        final WorkspaceView reopened = admin().reopen(id, OPERATOR);
        assertThat(reopened.slot("primary").orElseThrow().state()).isEqualTo(SlotState.RUNNING);
        admin().close(id, OPERATOR);
        admin().purge(id, OPERATOR);
        assertThat(harness.store.find(id)).isEmpty();

        for (SandboxEvent.Type type : List.of(SandboxEvent.Type.WORKSPACE_CLOSED, SandboxEvent.Type.WORKSPACE_REOPENED,
                SandboxEvent.Type.WORKSPACE_PURGED)) {
            assertThat(events(type)).isNotEmpty()
                    .allSatisfy(e -> assertThat(e.actor()).contains("SERVICE:ops-console"));
        }
        assertThat(events(SandboxEvent.Type.TERMINATED)).as("the close's own terminations carry the actor too")
                .isNotEmpty().allSatisfy(e -> assertThat(e.actor()).contains("SERVICE:ops-console"));
    }

    @Test
    void theOwnersOwnCloseRecordsTheOwnerAsActor() {
        final SandboxWorkspaceId id = create("owned", ALICE);

        harness.sandbox.workspaces().close(id, ALICE);

        assertThat(events(SandboxEvent.Type.WORKSPACE_CLOSED)).singleElement()
                .satisfies(e -> assertThat(e.actor()).contains("t1/USER:t1-alice"));
    }

    @Test
    @DisplayName("§6.6: the janitor's own transitions carry no actor")
    void theJanitorRecordsNoActor() throws Exception {
        bash(harness.mainTurn(SessionId.generate(), ALICE), "true");
        harness.clock.advance(java.time.Duration.ofHours(3));
        harness.sandbox.janitor().runOnce();

        assertThat(events(SandboxEvent.Type.TERMINATED)).isNotEmpty().allSatisfy(e -> assertThat(e.actor()).isEmpty());
    }

    @Test
    @DisplayName("review m1: a PROVISIONING slot is refused; a FAILED one is terminated and retried on next use")
    void terminateRefusesAProvisioningSlotAndRetriesAFailedOne() throws Exception {
        final java.time.Instant now = harness.clock.instant();
        final SandboxWorkspace provisioning = harness.store.createIfAbsent(
                SandboxWorkspace.builder().id(SandboxWorkspaceId.of("wsm:00000000-0000-0000-0000-00000000000a"))
                        .owner(WorkspaceOwner.of(TenantId.of("t1"), ALICE)).origin(WorkspaceOrigin.MANAGED)
                        .incarnation("prov0001").stateSince(now).createdAt(now).lastActivityAt(now)
                        .slots(java.util.Map.of("primary",
                                at.aimon.sandbox.workspace.SandboxSlot.builder().name("primary").profile("standard")
                                        .profileHash("h").state(SlotState.PROVISIONING).generation(1)
                                        .provisioning(at.aimon.sandbox.workspace.ProvisioningClaim.of(now, "node"))
                                        .lastActivityAt(now).build()))
                        .build());

        assertThatThrownBy(() -> admin().terminateSlot(provisioning.id(), "primary", OPERATOR))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("is provisioning");
        assertThat(harness.store.find(provisioning.id()).orElseThrow().slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.PROVISIONING);

        harness.faults.injectOnce(at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation.CREATE,
                at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault
                        .fail(at.aimon.sandbox.provider.SandboxProviderException.Kind.TRANSIENT));
        final SandboxWorkspaceId failed = org.assertj.core.api.Assertions
                .catchThrowableOfType(at.aimon.sandbox.workspace.WorkspaceProvisioningException.class,
                        () -> harness.sandbox.workspaces().create(CreateWorkspace.named("failed"), ALICE))
                .workspaceId();
        assertThatThrownBy(() -> harness.sandbox.workspaces().provision(failed, ALICE))
                .hasMessageContaining("next attempt");

        admin().terminateSlot(failed, "primary", OPERATOR);

        assertThat(harness.sandbox.workspaces().provision(failed, ALICE).slot("primary").orElseThrow().state())
                .as("the backoff went with the failure").isEqualTo(SlotState.RUNNING);
    }

    @Test
    @DisplayName("review m6: a slot the workspace does not have is an error, not a silent success")
    void terminateOfAnAbsentSlotIsRefused() {
        final SandboxWorkspaceId id = create("typo", ALICE);

        assertThatThrownBy(() -> admin().terminateSlot(id, "primray", OPERATOR))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("has no slot 'primray'");
        assertThat(events(SandboxEvent.Type.TERMINATED)).isEmpty();
    }

    @Test
    @DisplayName("review m2: setBy is the operator in the tenant its resolver knows")
    void setByUsesTheOperatorsOwnTenant() {
        final Principal tenantOperator = Principal.user("t9-ops");
        final SandboxWorkspaceId id = create("attach", ALICE);
        final SessionId session = SessionId.generate();

        admin().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), tenantOperator);

        assertThat(harness.sessionOptions.find(session).orElseThrow().setBy())
                .isEqualTo(WorkspaceOwner.of(TenantId.of("t9"), tenantOperator));
        assertThat(events(SandboxEvent.Type.SESSION_OPTIONS_SET)).singleElement()
                .satisfies(e -> assertThat(e.actor()).contains("t9/USER:t9-ops"));
    }

    @Test
    @DisplayName("review m4: reopen runs the owner through today's principal gate before anything changes")
    void reopenRefusesAnOwnerTheGateNowRefuses() {
        harness.close();
        harness = SandboxHarness.builder().tenantResolver(principal -> TenantId.of("t1"))
                .sessionOwnerLookup(id -> Optional.of(ALICE))
                .settings(s -> s.requirePrincipal(true).allowedSystemPrincipals(Set.of("system"))).build();
        final SandboxWorkspaceId id = harness.sandbox.workspaces()
                .create(CreateWorkspace.named("nightly"), Principal.system()).id();
        harness.sandbox.workspaces().close(id, Principal.system());

        // Shares the store and the local provider, so only its own assembly is closed: closing the harness would
        // close the shared provider too.
        final SandboxHarness redeployed = SandboxHarness.builder().store(harness.store).local(harness.local)
                .tenantResolver(principal -> TenantId.of("t1")).sessionOwnerLookup(s -> Optional.of(ALICE))
                .settings(s -> s.requirePrincipal(true)).build();
        try {
            assertThatThrownBy(() -> SandboxAdministration.of(redeployed.sandbox).reopen(id, OPERATOR))
                    .isInstanceOf(BindingRejectedException.class).hasMessageContaining("allowed-system-principals");
            assertThat(redeployed.store.find(id).orElseThrow().state()).isEqualTo(WorkspaceState.CLOSED);
        } finally {
            redeployed.sandbox.close();
        }
        assertThat(admin().reopen(id, OPERATOR).state()).as("where the gate admits it").isEqualTo(WorkspaceState.OPEN);
    }

    @Test
    @DisplayName("review N2: the operator deletes any session's options record outright")
    void theOperatorDeletesSessionOptions() {
        final SandboxWorkspaceId id = create("left over", ALICE);
        final SessionId session = SessionId.generate();
        harness.sandbox.workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(id), ALICE);
        harness.sandbox.workspaces().clearSessionOptions(session, ALICE);

        admin().deleteSessionOptions(session, OPERATOR);

        assertThat(harness.sessionOptions.find(session)).isEmpty();
        assertThat(events(SandboxEvent.Type.SESSION_OPTIONS_CLEARED)).last()
                .satisfies(e -> assertThat(e.actor()).contains("SERVICE:ops-console"));
    }
}
