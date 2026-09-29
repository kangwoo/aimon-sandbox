package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.sandbox.SandboxHarness;

/** The default per-tenant admission (§12.2, §16 row 25). */
class DefaultSandboxAdmissionIT {

    private static final Principal ALICE = Principal.user("t1-alice");
    private static final Principal BOB = Principal.user("t2-bob");

    private final SandboxHarness harness = SandboxHarness.builder()
            .tenantResolver(p -> TenantId.of(p.getId().substring(0, 2))).settings(s -> s.maxRunningPerTenant(2))
            .build();

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    @DisplayName("§16: a tenant over max-running-per-tenant is refused, naming the limit")
    void tenantOverRunningLimitIsRejected() throws Exception {
        final SessionId first = SessionId.generate();
        bash(harness.mainTurn(first, ALICE), "true");
        bash(harness.mainTurn(SessionId.generate(), ALICE), "true");

        assertThatThrownBy(() -> bash(harness.mainTurn(SessionId.generate(), ALICE), "true"))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("max-running-per-tenant = 2");
        assertThat(bash(harness.mainTurn(SessionId.generate(), BOB), "echo other").stdout()).isEqualTo("other\n");

        harness.sandbox.manager().close(SandboxWorkspaceId.of("ws:" + first.value()), ALICE);
        assertThat(bash(harness.mainTurn(SessionId.generate(), ALICE), "echo room").stdout()).isEqualTo("room\n");
    }
}
