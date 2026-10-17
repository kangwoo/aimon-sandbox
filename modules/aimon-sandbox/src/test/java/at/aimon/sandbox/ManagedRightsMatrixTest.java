package at.aimon.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.sandbox.SandboxSettings.WorkspaceAccess;
import at.aimon.sandbox.binding.SandboxTenantResolver;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.TenantId;
import at.aimon.sandbox.workspace.WorkspaceState;

/**
 * The rights matrix of management design §9.1: {setSessionOptions, clearSessionOptions, sessionOptions, describe,
 * provision, connect, close, reopen, purge} × {owner, tenant-mate, other tenant, anonymous} × deployment {principal,
 * tenant} × record
 * access {absent, principal, tenant}. The expectations are the rules of §6.2 and §6.5 restated, not read back from the
 * code: use follows the effective access, manage and the right over a session are the owner principal's.
 */
class ManagedRightsMatrixTest {

    private static final Principal ALICE = Principal.user("t1-alice");
    private static final Principal ALBERT = Principal.user("t1-albert");
    private static final Principal BOB = Principal.user("t2-bob");
    private static final SandboxTenantResolver BY_PREFIX = principal -> TenantId
            .of(principal.getId().substring(0, principal.getId().indexOf('-')));

    private enum Caller {
        OWNER(ALICE), TENANT_MATE(ALBERT), OTHER_TENANT(BOB), ANONYMOUS(null);

        private final Principal principal;

        Caller(Principal principal) {
            this.principal = principal;
        }
    }

    private enum Operation {
        SET_SESSION_OPTIONS, CLEAR_SESSION_OPTIONS, SESSION_OPTIONS, DESCRIBE, PROVISION, CONNECT, CLOSE, REOPEN, PURGE
    }

    static Stream<Arguments> deploymentsAndAccess() {
        final List<Arguments> out = new ArrayList<>();
        for (WorkspaceAccess deployment : WorkspaceAccess.values()) {
            for (WorkspaceAccess access : new WorkspaceAccess[]{null, WorkspaceAccess.PRINCIPAL,
                    WorkspaceAccess.TENANT}) {
                out.add(Arguments.of(deployment, Optional.ofNullable(access)));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "deployment {0}, record access {1}")
    @MethodSource("deploymentsAndAccess")
    void everyOperationFollowsItsRule(WorkspaceAccess deployment, Optional<WorkspaceAccess> access) {
        final SessionId session = SessionId.generate();
        try (SandboxHarness h = SandboxHarness.builder().tenantResolver(BY_PREFIX)
                .sessionOwnerLookup(id -> Optional.of(ALICE))
                .settings(s -> s.workspaceAccess(deployment).allowSharedWorkspaces(true)
                        .maxManagedPerOwner(SandboxSettings.UNLIMITED).maxRunningPerTenant(SandboxSettings.UNLIMITED))
                .build()) {
            final SandboxWorkspaces workspaces = h.sandbox.workspaces();
            final Function<WorkspaceState, SandboxWorkspaceId> fresh = state -> {
                final SandboxWorkspaceId id = workspaces
                        .create(CreateWorkspace.builder().name("m").access(access.orElse(null)).build(), ALICE).id();
                if (state == WorkspaceState.CLOSED) {
                    workspaces.close(id, ALICE);
                }
                return id;
            };
            final List<String> mismatches = new ArrayList<>();
            for (Operation operation : Operation.values()) {
                for (Caller caller : Caller.values()) {
                    final boolean expected = permitted(operation, caller, deployment, access);
                    final SandboxWorkspaceId id = fresh
                            .apply(operation == Operation.REOPEN || operation == Operation.PURGE
                                    ? WorkspaceState.CLOSED
                                    : WorkspaceState.OPEN);
                    final boolean actual = attempt(h, workspaces, operation, caller.principal, session, id);
                    if (actual != expected) {
                        mismatches
                                .add(operation + " by " + caller + ": expected " + (expected ? "allowed" : "refused"));
                    }
                }
            }
            assertThat(mismatches).isEmpty();
        }
    }

    /** §6.2 and §6.5, restated. */
    private static boolean permitted(Operation operation, Caller caller, WorkspaceAccess deployment,
            Optional<WorkspaceAccess> access) {
        switch (operation) {
            case DESCRIBE :
            case PROVISION :
            case CONNECT :
                final boolean sameTenant = caller == Caller.OWNER || caller == Caller.TENANT_MATE;
                return sameTenant && (caller == Caller.OWNER || access.orElse(deployment) == WorkspaceAccess.TENANT);
            case SET_SESSION_OPTIONS :
            case CLEAR_SESSION_OPTIONS :
            case SESSION_OPTIONS :
            case CLOSE :
            case REOPEN :
            case PURGE :
            default :
                return caller == Caller.OWNER;
        }
    }

    private static boolean attempt(SandboxHarness harness, SandboxWorkspaces workspaces, Operation operation,
            Principal caller, SessionId session, SandboxWorkspaceId id) {
        if (operation == Operation.DESCRIBE) {
            return workspaces.describe(id, caller).isPresent();
        }
        if (operation == Operation.CONNECT) {
            // A session of the owner's, attached by the owner; the turn runs as the caller (§6.2).
            final SessionId attached = SessionId.generate();
            workspaces.setSessionOptions(attached, SessionSandboxOptions.Update.attach(id), ALICE);
            final Throwable refused = catchThrowable(
                    () -> SandboxHarness.bash(harness.mainTurn(attached, caller), "true"));
            if (refused != null) {
                assertThat(refused).isInstanceOf(ExecutionEnvironmentUnavailableException.class)
                        .hasMessageContaining("not permitted");
            }
            return refused == null;
        }
        final Throwable thrown = catchThrowable(() -> {
            switch (operation) {
                case SET_SESSION_OPTIONS :
                    workspaces.setSessionOptions(session, SessionSandboxOptions.Update.attach(id), caller);
                    break;
                case CLEAR_SESSION_OPTIONS :
                    workspaces.clearSessionOptions(session, caller);
                    break;
                case SESSION_OPTIONS :
                    workspaces.sessionOptions(session, caller);
                    break;
                case PROVISION :
                    workspaces.provision(id, caller);
                    break;
                case CLOSE :
                    workspaces.close(id, caller);
                    break;
                case REOPEN :
                    workspaces.reopen(id, caller);
                    break;
                case PURGE :
                default :
                    workspaces.purge(id, caller);
                    break;
            }
        });
        if (thrown != null) {
            assertThat(thrown).isInstanceOf(ExecutionEnvironmentUnavailableException.class)
                    .hasMessageMatching("(?s).*(not permitted|only the owner).*");
        }
        return thrown == null;
    }
}
