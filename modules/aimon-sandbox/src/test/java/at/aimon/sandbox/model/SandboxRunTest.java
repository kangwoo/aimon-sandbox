package at.aimon.sandbox.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SandboxRunTest {

    private SandboxRun createBaseRun() {
        return SandboxRun.builder().runId("run-1").identifier("my-sandbox").sandboxId("container-1")
                .state(RunState.QUEUED).createdAt(Instant.now()).build();
    }

    @Test
    void builder_CreatesImmutableInstance() {
        SandboxRun run = createBaseRun();

        assertThat(run.getRunId()).isEqualTo("run-1");
        assertThat(run.getIdentifier()).isEqualTo("my-sandbox");
        assertThat(run.getState()).isEqualTo(RunState.QUEUED);
        assertThat(run.getCommands()).isEmpty();
        assertThat(run.getArtifactCount()).isZero();
        assertThat(run.getArtifactTotalBytes()).isZero();
    }

    @Test
    void withState_ReturnsNewInstance() {
        SandboxRun run = createBaseRun();
        SandboxRun running = run.withState(RunState.RUNNING);

        assertThat(running.getState()).isEqualTo(RunState.RUNNING);
        assertThat(run.getState()).isEqualTo(RunState.QUEUED);
        assertThat(running.getRunId()).isEqualTo(run.getRunId());
    }

    @Test
    void withStartedAt_ReturnsNewInstance() {
        SandboxRun run = createBaseRun();
        Instant now = Instant.now();
        SandboxRun started = run.withStartedAt(now);

        assertThat(started.getStartedAt()).isEqualTo(now);
        assertThat(run.getStartedAt()).isNull();
    }

    @Test
    void withCommandResult_AppendsCommand() {
        SandboxRun run = createBaseRun();
        CommandResult result = CommandResult.builder().index(0).command("echo hello").exitCode(0).stdout("hello")
                .build();

        SandboxRun updated = run.withCommandResult(result);

        assertThat(updated.getCommands()).hasSize(1);
        assertThat(updated.getCommands().get(0).getCommand()).isEqualTo("echo hello");
        assertThat(run.getCommands()).isEmpty();
    }

    @Test
    void withCommandResult_MultipleAppends() {
        SandboxRun run = createBaseRun();
        CommandResult r1 = CommandResult.builder().index(0).command("cmd1").exitCode(0).build();
        CommandResult r2 = CommandResult.builder().index(1).command("cmd2").exitCode(0).build();

        SandboxRun updated = run.withCommandResult(r1).withCommandResult(r2);

        assertThat(updated.getCommands()).hasSize(2);
    }

    @Test
    void withArtifactSummary_ReturnsNewInstance() {
        SandboxRun run = createBaseRun();

        SandboxRun updated = run.withArtifactSummary(3, 1024);

        assertThat(updated.getArtifactCount()).isEqualTo(3);
        assertThat(updated.getArtifactTotalBytes()).isEqualTo(1024);
        assertThat(run.getArtifactCount()).isZero();
        assertThat(run.getArtifactTotalBytes()).isZero();
    }

    @Test
    void withError_ReturnsNewInstance() {
        SandboxRun run = createBaseRun();
        SandboxRun failed = run.withError("something went wrong");

        assertThat(failed.getError()).isEqualTo("something went wrong");
        assertThat(run.getError()).isNull();
    }
}
