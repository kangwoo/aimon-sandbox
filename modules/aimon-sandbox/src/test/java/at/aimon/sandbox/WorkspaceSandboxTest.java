package at.aimon.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.skill.parser.SkillHookSetParser;
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
    void theSkillParsersRefuseShellHooks() {
        final SkillHookSetParser parser = WorkspaceSandbox.skillHookSetParser();

        assertThatThrownBy(() -> parser.parse("s",
                Map.of("postTool",
                        java.util.List.of(Map.of("action", Map.of("type", "shell", "command", "touch /tmp/x"))))))
                .hasMessageContaining("shell hooks are not supported");
        assertThat(WorkspaceSandbox.markdownSkillParser()).isNotNull();
    }
}
