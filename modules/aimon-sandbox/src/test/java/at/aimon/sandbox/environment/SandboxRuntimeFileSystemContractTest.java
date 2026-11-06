package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.EnumSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.RuntimeFileSystemContract;

/**
 * {@link SandboxRuntimeFileSystem} over the testkit's local provider (docs/design/ptc-shell-provider.md §6.2): the
 * published contract, plus what only this tier can count — refusals make no provider call, asking for the runtime file
 * system provisions nothing, and a sandbox that cannot be had is an {@link IOException}.
 */
class SandboxRuntimeFileSystemContractTest extends RuntimeFileSystemContract {

    private static final EnumSet<Operation> PROVIDER_CALLS = EnumSet.of(Operation.RUN, Operation.FILES_READ,
            Operation.FILES_WRITE, Operation.FILES_STAT, Operation.FILES_LIST, Operation.FILES_MKDIR,
            Operation.FILES_DELETE, Operation.FILES_MOVE);

    private final RecordingProvider[] recording = new RecordingProvider[1];
    private final SandboxHarness harness = SandboxHarness.builder()
            .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build();

    @Override
    protected ExecutionEnvironment environment() {
        return harness.mainTurn(SessionId.generate(), ALICE);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    private int providerCalls() {
        return PROVIDER_CALLS.stream().mapToInt(harness.faults::calls).sum();
    }

    @Test
    @DisplayName("9 (unit tier): a refused path or maxBytes makes no provider call")
    void refusalsMakeNoProviderCall() {
        final RuntimeFileSystem files = files();
        harness.faults.resetCounts();

        for (String path : refusedPaths()) {
            assertThatThrownBy(() -> files.writeNew(path, new byte[]{1})).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> files.read(path, 1)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> files.delete(path)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> files.createDirectories(path)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> files.read(dir() + "/x", SandboxRuntimeFileSystem.MAX_READ + 1))
                .isInstanceOf(IOException.class);

        assertThat(providerCalls()).isZero();
    }

    @Test
    @DisplayName("11: asking for the runtime file system and the descriptor provisions nothing")
    void askingProvisionsNothing() {
        final int created = harness.faults.calls(Operation.CREATE);
        final ExecutionEnvironment fresh = harness.mainTurn(SessionId.generate(), ALICE);

        assertThat(fresh.runtimeFileSystem()).isPresent();
        assertThat(fresh.descriptor().runtimes()).isNotNull();

        assertThat(harness.faults.calls(Operation.CREATE)).isEqualTo(created);
    }

    @Test
    @DisplayName("7d: a sandbox that cannot be provisioned is an IOException, never a runtime exception")
    void aSandboxThatCannotBeHadIsAnIoException() {
        final ExecutionEnvironment fresh = harness.mainTurn(SessionId.generate(), ALICE);
        final RuntimeFileSystem files = fresh.runtimeFileSystem().orElseThrow();
        harness.faults.inject(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.PERMANENT));

        assertThatThrownBy(() -> files.writeNew("/workspace/x", new byte[]{1})).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> files.read("/workspace/x", 1)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> files.delete("/workspace/x")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> files.createDirectories("/workspace/x")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a sandbox found gone during a call is an IOException, and the next call re-creates it")
    void aLostSandboxIsAnIoException() throws Exception {
        final RuntimeFileSystem files = files();
        harness.faults.injectOnce(Operation.FILES_WRITE, Fault.notFound());

        assertThatThrownBy(() -> files.writeNew(dir() + "/lost", new byte[]{1})).isInstanceOf(IOException.class)
                .hasMessageContaining("lost");

        files.createDirectories(dir());
        files.writeNew(dir() + "/after", new byte[]{1});
        assertThat(files.read(dir() + "/after", 1)).containsExactly(1);
    }

    @Test
    @DisplayName("a refused write whose existence check fails is that failure, never a FileAlreadyExistsException")
    void aFailedExistenceCheckIsAnIoException() throws Exception {
        final RuntimeFileSystem files = files();
        files.createDirectories(dir() + "/taken");
        // Only the existence check ends "then exit 0; fi"; it now exits 2, as a helper that failed would.
        recording[0].rewrite(spec -> spec.command().contains("then exit 0; fi")
                ? ExecSpec.builder().command("exit 2").workingDirectory(spec.workingDirectory().orElse(null))
                        .environment(spec.environment()).timeout(spec.timeout()).maxCaptureBytes(spec.maxCaptureBytes())
                        .transientOutput(spec.transientOutput()).build()
                : spec);

        assertThatThrownBy(() -> files.writeNew(dir() + "/taken", new byte[]{1})).isInstanceOf(IOException.class)
                .isNotInstanceOf(FileAlreadyExistsException.class).hasMessageContaining("cannot check")
                .hasMessageContaining("(exit 2)").satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }
}
