package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SlotState;
import at.aimon.sandbox.workspace.WorkspaceFilesUnavailableException;
import at.aimon.sandbox.workspace.WorkspaceFilesUnavailableException.Reason;

/** {@link SandboxWorkspaceFiles} over a failing workspace store: unreachable, never "lost" (PR #40 review). */
class StoreFailureFilesTest {

    private final AtomicBoolean failUpdates = new AtomicBoolean();
    private final AtomicBoolean failReads = new AtomicBoolean();
    private final SandboxHarness harness = SandboxHarness.builder()
            .store(new DelegatingStore(new InMemorySandboxWorkspaceStore()) {
                @Override
                public java.util.Optional<SandboxWorkspace> find(SandboxWorkspaceId id) {
                    if (failReads.get()) {
                        throw new IllegalStateException("store down");
                    }
                    return super.find(id);
                }

                @Override
                public SandboxWorkspace update(SandboxWorkspaceId id, long expectedVersion, SandboxWorkspace next) {
                    if (failUpdates.get()) {
                        throw new IllegalStateException("store down");
                    }
                    return super.update(id, expectedVersion, next);
                }
            }).build();

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    @DisplayName("a store failing the activity write is UNREACHABLE; the slot stays RUNNING and nothing is written")
    void aFailingActivityWriteIsUnreachable() {
        final SandboxWorkspaceId ws = harness.sandbox.workspaces().create(CreateWorkspace.named("s"), ALICE).id();
        harness.clock.advance(Duration.ofMinutes(5));
        failUpdates.set(true);

        final WorkspaceFilesUnavailableException e = catchThrowableOfType(WorkspaceFilesUnavailableException.class,
                () -> harness.sandbox.workspaces().files().write(ws, "repo/x", new ByteArrayInputStream(new byte[1]), 1,
                        false, ALICE));

        assertThat(e.reason()).isEqualTo(Reason.UNREACHABLE);
        failUpdates.set(false);
        assertThat(harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.RUNNING);
        assertThat(harness.sandbox.workspaces().files().stat(ws, "repo/x", ALICE)).isEmpty();
        assertThat(harness.events).noneMatch(event -> event.type() == SandboxEvent.Type.FILES_CHANGED);
    }

    @Test
    @DisplayName("a store failing the record read is UNREACHABLE, not a raw exception")
    void aFailingRecordReadIsUnreachable() {
        final SandboxWorkspaceId ws = harness.sandbox.workspaces().create(CreateWorkspace.named("s"), ALICE).id();
        failReads.set(true);

        final WorkspaceFilesUnavailableException e = catchThrowableOfType(WorkspaceFilesUnavailableException.class,
                () -> harness.sandbox.workspaces().files().list(ws, "", 10, ALICE));

        assertThat(e.reason()).isEqualTo(Reason.UNREACHABLE);
    }
}
