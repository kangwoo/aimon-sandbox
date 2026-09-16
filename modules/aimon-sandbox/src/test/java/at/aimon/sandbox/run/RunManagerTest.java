package at.aimon.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.model.CommandResult;
import at.aimon.sandbox.model.RunState;
import at.aimon.sandbox.model.SandboxRun;

class RunManagerTest {

    private RunManager runManager;

    @BeforeEach
    void setUp() {
        runManager = new RunManager(new InMemoryRunStore());
    }

    @Test
    void createRun_InitializesInQueuedState() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");

        assertThat(run.getRunId()).isNotNull();
        assertThat(run.getIdentifier()).isEqualTo("my-sandbox");
        assertThat(run.getSandboxId()).isEqualTo("container-1");
        assertThat(run.getState()).isEqualTo(RunState.QUEUED);
        assertThat(run.getCreatedAt()).isNotNull();
        assertThat(run.getCommands()).isEmpty();
        assertThat(run.getArtifactCount()).isZero();
        assertThat(run.getArtifactTotalBytes()).isZero();
    }

    @Test
    void start_TransitionsToRunning() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");
        SandboxRun started = runManager.start(run.getRunId());

        assertThat(started.getState()).isEqualTo(RunState.RUNNING);
        assertThat(started.getStartedAt()).isNotNull();
    }

    @Test
    void start_FromNonQueuedState_ThrowsException() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");
        runManager.start(run.getRunId());

        assertThatThrownBy(() -> runManager.start(run.getRunId())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected QUEUED").hasMessageContaining("RUNNING");
    }

    @Test
    void getRun_ReturnsExistingRun() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");
        assertThat(runManager.getRun(run.getRunId())).isPresent();
    }

    @Test
    void getRun_NonexistentId_ReturnsEmpty() {
        assertThat(runManager.getRun("nonexistent")).isEmpty();
    }

    @Test
    void addCommandResult_AppendsResult() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");
        runManager.start(run.getRunId());

        CommandResult result = CommandResult.builder().index(0).command("echo hello").exitCode(0).stdout("hello")
                .build();
        SandboxRun updated = runManager.addCommandResult(run.getRunId(), result);

        assertThat(updated.getCommands()).hasSize(1);
        assertThat(updated.getCommands().get(0).getStdout()).isEqualTo("hello");
    }

    @Test
    void complete_TransitionsToCompleted() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");
        runManager.start(run.getRunId());

        SandboxRun completed = runManager.complete(run.getRunId(), 1, 42);

        assertThat(completed.getState()).isEqualTo(RunState.COMPLETED);
        assertThat(completed.getArtifactCount()).isEqualTo(1);
        assertThat(completed.getArtifactTotalBytes()).isEqualTo(42);
        assertThat(completed.getEndedAt()).isNotNull();
    }

    @Test
    void complete_FromNonRunningState_ThrowsException() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");

        assertThatThrownBy(() -> runManager.complete(run.getRunId(), 0, 0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected RUNNING").hasMessageContaining("QUEUED");
    }

    @Test
    void fail_TransitionsToFailed() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");
        runManager.start(run.getRunId());

        SandboxRun failed = runManager.fail(run.getRunId(), "Command execution failed");

        assertThat(failed.getState()).isEqualTo(RunState.FAILED);
        assertThat(failed.getError()).isEqualTo("Command execution failed");
        assertThat(failed.getEndedAt()).isNotNull();
    }

    @Test
    void fail_FromNonRunningState_ThrowsException() {
        SandboxRun run = runManager.createRun("my-sandbox", "container-1");

        assertThatThrownBy(() -> runManager.fail(run.getRunId(), "error")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected RUNNING").hasMessageContaining("QUEUED");
    }
}
