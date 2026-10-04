package at.aimon.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;

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
}
