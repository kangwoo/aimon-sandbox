package at.aimon.sandbox.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.LocalSandboxLock;
import at.aimon.sandbox.run.InMemoryRunStore;

class OrcaSandboxToolProviderTest {

    @Test
    void registerTools_RegistersFourTools() {
        SandboxBackend backend = mock(SandboxBackend.class);
        SandboxConfig config = SandboxConfig.builder().build();
        OrcaSandboxToolProvider provider = new OrcaSandboxToolProvider(backend, config, new InMemoryRunStore(),
                new LocalSandboxLock());

        ToolRegistry registry = new at.aimon.core.agent.tool.DefaultToolRegistry();
        OrcaToolProviderContext context = mock(OrcaToolProviderContext.class);

        provider.registerTools(registry, context);

        assertThat(registry.findByName("RunSandbox")).isPresent();
        assertThat(registry.findByName("DeleteSandbox")).isPresent();
        assertThat(registry.findByName("RestartSandbox")).isPresent();
        assertThat(registry.findByName("CopyToSandbox")).isPresent();
        assertThat(registry.size()).isEqualTo(4);
    }
}
