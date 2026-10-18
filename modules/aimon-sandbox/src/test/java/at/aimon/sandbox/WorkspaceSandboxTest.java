package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.sandbox.binding.SandboxTenantResolver;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.CloseCause;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceOwner;

class WorkspaceSandboxTest {

    @Test
    void theAssemblyDefaultsToAnInMemoryStoreAndCloseLeavesABorrowedProviderOpen() {
        final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
        try {
            final WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
                    .settings(SandboxTestProfiles.settings(SandboxTestProfiles.local("p").build()).build())
                    .provider(provider).build();

            assertThat(sandbox.store()).isInstanceOf(InMemorySandboxWorkspaceStore.class);
            assertThat(sandbox.environmentProvider()).isNotNull();
            assertThat(sandbox.profiles().find("p")).isPresent();
            assertThat(sandbox.settings().deployment()).isEqualTo("test");
            assertThat(sandbox.connections()).isNotNull();
            sandbox.janitor().start();
            sandbox.close();

            assertThat(provider.baseDirectory()).exists();
        } finally {
            provider.close();
        }
    }

    @Test
    void anOwnedProviderIsClosedWithTheAssembly() {
        final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
        final WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
                .settings(SandboxTestProfiles.settings(SandboxTestProfiles.local("p").build()).build())
                .provider(provider).ownProvider(true).build();

        sandbox.close();

        assertThat(provider.baseDirectory()).doesNotExist();
    }

    @Test
    void theJanitorRunsOnItsOwnThreadSoHeartbeatsNeverQueueBehindIt() throws Exception {
        final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
        final CountDownLatch release = new CountDownLatch(1);
        try (WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
                .settings(SandboxTestProfiles.settings(SandboxTestProfiles.local("p").build()).build())
                .provider(provider).build()) {
            // A janitor pass waiting (close-wait on a slow provider), and one heartbeat stuck on a slow call too.
            final Runnable blocked = () -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            sandbox.janitorScheduler().scheduleAtFixedRate(blocked, Duration.ofMillis(10));
            sandbox.heartbeatScheduler().scheduleAtFixedRate(blocked, Duration.ofMillis(10));
            final CountDownLatch heartbeat = new CountDownLatch(1);

            sandbox.heartbeatScheduler().scheduleAtFixedRate(heartbeat::countDown, Duration.ofMillis(10));

            assertThat(sandbox.janitorScheduler()).isNotSameAs(sandbox.heartbeatScheduler());
            assertThat(heartbeat.await(5, TimeUnit.SECONDS)).as("a heartbeat still runs").isTrue();
        } finally {
            release.countDown();
            provider.close();
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // core 0.3.3 (docs/design/workspace-sandbox-core-033.md §3.5, §5.2)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("core-033 §3.5: the default background executor is the assembly's and close() shuts it down")
    void theDefaultBackgroundExecutorIsOwned() {
        final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
        try {
            final WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
                    .settings(SandboxTestProfiles.settings(SandboxTestProfiles.local("p").build()).build())
                    .provider(provider).build();
            final ExecutorService background = (ExecutorService) sandbox.backgroundExecutor();

            sandbox.close();

            assertThat(background.isShutdown()).isTrue();
        } finally {
            provider.close();
        }
    }

    @Test
    void aSuppliedBackgroundExecutorIsBorrowed() {
        final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
        final ExecutorService supplied = Executors.newSingleThreadExecutor();
        try {
            WorkspaceSandbox.builder()
                    .settings(SandboxTestProfiles.settings(SandboxTestProfiles.local("p").build()).build())
                    .provider(provider).backgroundExecutor(supplied).build().close();

            assertThat(supplied.isShutdown()).isFalse();
        } finally {
            supplied.shutdownNow();
            provider.close();
        }
    }

    @Test
    @DisplayName("core-033 §7: a refused background task is logged; the deletion stays marked for the janitor")
    void aRefusedBackgroundTaskIsDropped() throws Exception {
        final QueueingExecutor refusing = new QueueingExecutor();
        refusing.refuse();
        final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
        try (SandboxHarness harness = SandboxHarness.builder().sessionRecords(records).background(refusing).build()) {
            final SessionId session = SessionId.generate();
            records.provision(session, "agent", ALICE);
            bash(harness.mainTurn(session, ALICE), "true");

            assertThatCode(() -> harness.sessionDeleted(session, ALICE)).doesNotThrowAnyException();

            assertThat(harness.record(session).closeCause()).contains(CloseCause.SESSION_DELETED);
        }
    }

    @Test
    @DisplayName("core-033 §5.2: require-principal is satisfied by sessionRecords(...) alone, and refused with neither")
    void requirePrincipalAcceptsCoresStoreAsTheOwnerSource() {
        final LocalProcessSandboxProvider provider = LocalProcessSandboxProvider.builder().build();
        try {
            final WorkspaceSandbox.Builder builder = WorkspaceSandbox.builder()
                    .settings(SandboxTestProfiles.settings(SandboxTestProfiles.local("p").build())
                            .requirePrincipal(true).build())
                    .provider(provider).tenantResolver(SandboxTenantResolver.SINGLE_TENANT);

            assertThatThrownBy(builder::build).isInstanceOf(SandboxConfigurationException.class)
                    .hasMessageContaining("require-principal needs a SessionOwnerLookup or sessionRecords(...)");
            builder.sessionRecords(new InMemorySessionRecordStore()).build().close();
        } finally {
            provider.close();
        }
    }

    @Test
    @DisplayName("core-033 §5.2: ws:{sid} is owned by the owner core recorded, whoever binds first (WS §8.3)")
    void theRecordedOwnerOwnsTheSessionsWorkspace() throws Exception {
        final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
        final Principal bob = Principal.user("bob");
        try (SandboxHarness harness = SandboxHarness.builder().sessionRecords(records).build()) {
            final SessionId session = SessionId.generate();
            records.provision(session, "agent", ALICE);

            assertThatThrownBy(() -> bash(harness.mainTurn(session, bob), "true"))
                    .hasMessageContaining("not permitted");
            assertThat(harness.record(session).owner()).isEqualTo(WorkspaceOwner.of(TenantId.DEFAULT, ALICE));
            assertThat(bash(harness.mainTurn(session, ALICE), "echo mine").stdout()).isEqualTo("mine\n");

            final SessionId ownerless = SessionId.generate();
            records.provision(ownerless, "agent");
            final ExecutionEnvironment refused = harness.mainTurn(ownerless, ALICE);
            assertThat(refused).isInstanceOf(UnavailableExecutionEnvironment.class);
            assertThat(((UnavailableExecutionEnvironment) refused).message())
                    .contains("set SubmitRequest.owner(...) on the submit that creates the session");
        }
    }

    @Test
    void aHostLookupAnswersForSessionsCoreRecordedNoOwnerFor() throws Exception {
        final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
        try (SandboxHarness harness = SandboxHarness.builder().sessionRecords(records)
                .sessionOwnerLookup(id -> Optional.of(ALICE)).build()) {
            final SessionId older = SessionId.generate();
            records.provision(older, "agent");

            assertThat(bash(harness.mainTurn(older, ALICE), "echo fallback").stdout()).isEqualTo("fallback\n");
            harness.sandbox.workspaces().setSessionOptions(older,
                    SessionSandboxOptions.Update.rule(UnattachedRule.REJECT), ALICE);
        }
    }

    @Test
    @DisplayName("core-033 §6.2: session options follow core's owner — set by it, refused to another principal")
    void sessionOptionsUseTheRecordedOwner() {
        final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
        try (SandboxHarness harness = SandboxHarness.builder().sessionRecords(records).build()) {
            final SessionId session = SessionId.generate();
            records.provision(session, "agent", ALICE);
            final SessionSandboxOptions.Update reject = SessionSandboxOptions.Update.rule(UnattachedRule.REJECT);

            harness.sandbox.workspaces().setSessionOptions(session, reject, ALICE);
            assertThatThrownBy(
                    () -> harness.sandbox.workspaces().setSessionOptions(session, reject, Principal.user("bob")))
                    .hasMessageContaining("only the owner");
            assertThatThrownBy(
                    () -> harness.sandbox.workspaces().setSessionOptions(SessionId.generate(), reject, ALICE))
                    .as("not provisioned yet (Q8)").hasMessageContaining("unknown session");
            assertThat(harness.store.find(SandboxWorkspaceId.of("ws:" + session.value()))).isEmpty();
        }
    }
}
