package at.aimon.sandbox.testkit;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.sandbox.WorkspaceSandbox;

/**
 * Runs the runtime file system contract from the testkit itself, so a broken suite fails here first: a workspace
 * sandbox's main-turn environment over the local provider, with a manual clock and scheduler so nothing runs in the
 * background.
 */
class LocalRuntimeFileSystemContractTest extends RuntimeFileSystemContract {

    private final ManualClock clock = new ManualClock();
    private final LocalProcessSandboxProvider local = LocalProcessSandboxProvider.builder().clock(clock).build();
    private final WorkspaceSandbox sandbox = WorkspaceSandbox.builder()
            .settings(SandboxTestProfiles.settings(SandboxTestProfiles.local("standard").build())
                    .closeWait(Duration.ofSeconds(2)).build())
            .provider(local).clock(clock).scheduler(new ManualScheduler(clock)).backgroundExecutor(Runnable::run)
            .build();

    @Override
    protected ExecutionEnvironment environment() {
        return ExecutionEnvironments.resolveOrUnavailable(sandbox.environmentProvider(),
                EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("test-agent"))
                        .sessionId(SessionId.generate()).principal(Principal.user("alice")).build());
    }

    @AfterEach
    void close() {
        sandbox.close();
        local.close();
    }
}
