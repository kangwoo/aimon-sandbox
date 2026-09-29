package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.binding.SandboxTenantResolver;

/** The owner check at every entry point (§8.3; §16 rows 24, 26, 28). */
class OwnerCheckTest {

    private static final Principal ALICE = Principal.user("t1-alice");
    private static final Principal ALBERT = Principal.user("t1-albert");
    private static final Principal BOB = Principal.user("t2-bob");
    private static final SandboxTenantResolver BY_PREFIX = principal -> TenantId
            .of(principal.getId().substring(0, principal.getId().indexOf('-')));

    private SandboxHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private SandboxHarness harness(SandboxSettings.WorkspaceAccess access) {
        harness = SandboxHarness.builder().tenantResolver(BY_PREFIX).settings(s -> s.workspaceAccess(access)).build();
        return harness;
    }

    private static SandboxWorkspaceId id(SessionId session) {
        return SandboxWorkspaceId.of("ws:" + session.value());
    }

    @Test
    @DisplayName("§16: another tenant's principal is refused at connect, close and reopen")
    void otherTenantIsRejectedAtEveryEntryPoint() throws Exception {
        final SandboxHarness h = harness(SandboxSettings.WorkspaceAccess.TENANT);
        final SessionId session = SessionId.generate();
        bash(h.mainTurn(session, ALICE), "true");

        assertThatThrownBy(() -> bash(h.mainTurn(session, BOB), "true")).isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("not permitted");
        assertThatThrownBy(() -> h.sandbox.manager().close(id(session), BOB)).hasMessageContaining("not permitted");
        assertThat(h.record(session).state()).isEqualTo(WorkspaceState.OPEN);
        h.sandbox.manager().close(id(session), ALICE);
        assertThatThrownBy(() -> h.sandbox.manager().reopen(id(session), BOB)).hasMessageContaining("not permitted");
        assertThat(h.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
    }

    @Test
    @DisplayName("§16: same tenant, other user, workspace-access principal — refused")
    void sameTenantOtherUserIsRejectedUnderPrincipalAccess() throws Exception {
        final SandboxHarness h = harness(SandboxSettings.WorkspaceAccess.PRINCIPAL);
        final SessionId session = SessionId.generate();
        bash(h.mainTurn(session, ALICE), "true");

        assertThatThrownBy(() -> bash(h.mainTurn(session, ALBERT), "true")).hasMessageContaining("not permitted");
        assertThatThrownBy(() -> h.sandbox.manager().close(id(session), ALBERT)).hasMessageContaining("not permitted");
    }

    @Test
    void sameTenantOtherUserIsAdmittedUnderTenantAccess() throws Exception {
        final SandboxHarness h = harness(SandboxSettings.WorkspaceAccess.TENANT);
        final SessionId session = SessionId.generate();
        bash(h.mainTurn(session, ALICE), "echo shared > /workspace/repo/f");

        assertThat(bash(h.mainTurn(session, ALBERT), "cat /workspace/repo/f").stdout()).isEqualTo("shared\n");
    }

    @Test
    @DisplayName("§16: record lost, a non-owner connects first — the owner is still the session's owner")
    void ownerComesFromSessionOwnerNotFirstCaller() throws Exception {
        final SessionId session = SessionId.generate();
        harness = SandboxHarness.builder().tenantResolver(BY_PREFIX)
                .sessionOwnerLookup(id -> id.equals(session) ? Optional.of(ALICE) : Optional.empty())
                .settings(s -> s.requirePrincipal(true)).build();

        assertThatThrownBy(() -> bash(harness.mainTurn(session, BOB), "true")).hasMessageContaining("not permitted");
        assertThat(harness.record(session).owner()).isEqualTo(WorkspaceOwner.of(TenantId.of("t1"), "t1-alice"));
        assertThat(bash(harness.mainTurn(session, ALICE), "echo mine").stdout()).isEqualTo("mine\n");
        assertThat(harness.mainTurn(SessionId.generate(), ALICE).descriptor().notes().orElseThrow())
                .as("an unknown session owner is rejected at bind").contains("is not known");
    }

    @Test
    void aForeignCallerCannotConsumeTheIdleReopen() throws Exception {
        final SandboxHarness h = harness(SandboxSettings.WorkspaceAccess.TENANT);
        final SessionId session = SessionId.generate();
        bash(h.mainTurn(session, ALICE), "true");
        h.clock.advance(Duration.ofHours(2));
        h.sandbox.janitor().runOnce();
        h.clock.advance(Duration.ofHours(24));
        h.sandbox.janitor().runOnce();
        assertThat(h.record(session).closeCause()).contains(CloseCause.IDLE);

        assertThatThrownBy(() -> bash(h.mainTurn(session, BOB), "true")).hasMessageContaining("not permitted");
        assertThat(h.record(session).state()).isEqualTo(WorkspaceState.CLOSED);
        assertThat(bash(h.mainTurn(session, ALICE), "true").notices()).contains(SandboxWorkspaceManager.RESET_NOTICE);
    }
}
