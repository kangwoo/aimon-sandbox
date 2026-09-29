package at.aimon.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.core.environment.ForkDefinition;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.sandbox.binding.SandboxTenantResolver;
import at.aimon.sandbox.binding.SessionOwnerLookup;
import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider;
import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;
import at.aimon.sandbox.testkit.ManualClock;
import at.aimon.sandbox.testkit.ManualScheduler;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.InMemorySandboxWorkspaceStore;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxSlot;
import at.aimon.sandbox.workspace.SandboxWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SandboxWorkspaceStore;

/**
 * A workspace sandbox on the local-process provider, with a manual clock and scheduler, for integration tests. Close it
 * after the test.
 */
public final class SandboxHarness implements AutoCloseable {

    public static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("test-agent");
    public static final Principal ALICE = Principal.user("alice");

    public final ManualClock clock = new ManualClock();
    public final ManualScheduler scheduler = new ManualScheduler(clock);
    public final LocalProcessSandboxProvider local;
    public final FaultInjectingSandboxProvider faults;
    public final SandboxWorkspaceStore store;
    public final List<SandboxEvent> events = new ArrayList<>();
    public final WorkspaceSandbox sandbox;

    private SandboxHarness(Builder builder) {
        this.local = builder.local != null ? builder.local : LocalProcessSandboxProvider.builder().clock(clock).build();
        this.faults = new FaultInjectingSandboxProvider(builder.decorator.apply(local, clock));
        this.store = builder.store != null ? builder.store : new InMemorySandboxWorkspaceStore();
        final SandboxSettings settings = builder.settings.apply(SandboxTestProfiles
                .settings(builder.profiles.toArray(SandboxProfile[]::new)).closeWait(Duration.ofSeconds(2))).build();
        final WorkspaceSandbox.Builder assembly = WorkspaceSandbox.builder().settings(settings).provider(faults)
                .store(store).clock(clock).scheduler(scheduler).eventListener(events::add);
        if (builder.tenantResolver != null) {
            assembly.tenantResolver(builder.tenantResolver);
        }
        if (builder.bindingPolicy != null) {
            assembly.bindingPolicy(builder.bindingPolicy);
        }
        if (builder.sessionOwnerLookup != null) {
            assembly.sessionOwnerLookup(builder.sessionOwnerLookup);
        }
        this.sandbox = assembly.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return a harness with one local profile named {@code standard} */
    public static SandboxHarness standard() {
        return builder().build();
    }

    public ExecutionEnvironment resolve(EnvironmentRequest request) {
        return ExecutionEnvironments.resolveOrUnavailable(sandbox.environmentProvider(), request);
    }

    public ExecutionEnvironment mainTurn(SessionId session, Principal principal) {
        return resolve(
                EnvironmentRequest.builder().agentRuntimeId(RUNTIME).sessionId(session).principal(principal).build());
    }

    public ExecutionEnvironment routine(ExecutionId execution, Principal principal) {
        return resolve(EnvironmentRequest.builder().agentRuntimeId(RUNTIME).executionId(execution).principal(principal)
                .build());
    }

    public ExecutionEnvironment fork(ExecutionEnvironment parent, ExecutionId execution, Principal principal,
            Map<String, String> attributes) {
        return resolve(EnvironmentRequest.builder().agentRuntimeId(RUNTIME).executionId(execution).principal(principal)
                .parent(parent).fork(ForkDefinition.builder().name("helper").attributes(attributes).build()).build());
    }

    public ExecutionEnvironment fork(ExecutionEnvironment parent, Principal principal) {
        return fork(parent, ExecutionId.generate(), principal, Map.of());
    }

    public static ShellCommandResult bash(ExecutionEnvironment environment, String command)
            throws ShellExecutionException {
        return environment.shell().execute(() -> command,
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).build());
    }

    public static ShellCommandResult bash(ExecutionEnvironment environment, String command, ExecutionOptions options)
            throws ShellExecutionException {
        return environment.shell().execute(() -> command, options);
    }

    public SandboxWorkspace record(SessionId session) {
        return store.find(SandboxWorkspaceId.of("ws:" + session.value())).orElseThrow();
    }

    public SandboxSlot primary(SessionId session) {
        return record(session).slot("primary").orElseThrow();
    }

    /** The host path of a sandbox path in the session's current primary sandbox. */
    public Path host(SessionId session, String sandboxPath) {
        final ProviderSandboxRef ref = primary(session).providerRef().orElseThrow();
        return local.hostRoot(ref).resolve(sandboxPath.substring(1));
    }

    public String hostFile(SessionId session, String sandboxPath) throws IOException {
        return Files.readString(host(session, sandboxPath), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        sandbox.close();
        local.close();
    }

    public static final class Builder {
        private final List<SandboxProfile> profiles = new ArrayList<>();
        private UnaryOperator<SandboxSettings.Builder> settings = UnaryOperator.identity();
        private SandboxTenantResolver tenantResolver;
        private SessionOwnerLookup sessionOwnerLookup;
        private SandboxWorkspaceStore store;
        private LocalProcessSandboxProvider local;
        private at.aimon.sandbox.binding.SandboxBindingPolicy bindingPolicy;

        public Builder bindingPolicy(at.aimon.sandbox.binding.SandboxBindingPolicy bindingPolicy) {
            this.bindingPolicy = bindingPolicy;
            return this;
        }

        private BiFunction<SandboxProvider, ManualClock, SandboxProvider> decorator = (provider, clock) -> provider;

        /** Wraps the local provider (inside the fault injector), e.g. to tamper with what it returns. */
        public Builder decorate(BiFunction<SandboxProvider, ManualClock, SandboxProvider> decorator) {
            this.decorator = decorator;
            return this;
        }

        public Builder profile(SandboxProfile profile) {
            profiles.add(profile);
            return this;
        }

        public Builder settings(UnaryOperator<SandboxSettings.Builder> settings) {
            this.settings = settings;
            return this;
        }

        public Builder tenantResolver(SandboxTenantResolver tenantResolver) {
            this.tenantResolver = tenantResolver;
            return this;
        }

        public Builder sessionOwnerLookup(SessionOwnerLookup sessionOwnerLookup) {
            this.sessionOwnerLookup = sessionOwnerLookup;
            return this;
        }

        public Builder store(SandboxWorkspaceStore store) {
            this.store = store;
            return this;
        }

        public Builder local(LocalProcessSandboxProvider local) {
            this.local = local;
            return this;
        }

        public SandboxHarness build() {
            if (profiles.isEmpty()) {
                profiles.add(SandboxTestProfiles.local("standard").build());
            }
            return new SandboxHarness(this);
        }
    }

}
