package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillHookSetParser;
import at.aimon.core.skill.render.ShellArgumentTokenizer;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.tools.EnvironmentNotices;
import at.aimon.core.tools.bash.BashTool;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.binding.SandboxRequestAttributes;
import at.aimon.sandbox.binding.SlotChoice;
import at.aimon.sandbox.binding.UnattachedRule;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SessionSandboxOptions;
import at.aimon.sandbox.workspace.WorkspaceScan;
import at.aimon.sandbox.workspace.WorkspaceView;

/**
 * The sandbox behind a real {@link OrcaAgentExecutor} (§16 rows 14 and 15): the prompt describes the declared
 * profile without provisioning anything, and a slash-command skill's {@code Bash} runs in the sandbox, not on the host
 * — inline in the session's turn, and in a forked subagent. A skill-declared {@code preTool} shell guard, parsed the
 * way core's bootstrap parses it, runs in the sandbox too and blocks what it guards when the sandbox is unavailable
 * (§12.1).
 */
class OrcaRuntimeSandboxE2ETest {

    @TempDir
    Path tempDir;

    private RecordingProvider recorder;
    private final SandboxHarness harness = SandboxHarness.builder()
            .decorate((provider, clock) -> recorder = new RecordingProvider(provider))
            .sessionOwnerLookup(session -> Optional.of(SandboxHarness.ALICE)).build();
    private ScriptedLlmClient llm;
    private DefaultSubagentExecutionManager subagents;
    private OrcaAgentExecutor executor;
    private final MapSkillRegistry skills = new MapSkillRegistry();
    private final MapSubagentRegistry subagentRegistry = new MapSubagentRegistry();
    private final DefaultHookRegistry hookRegistry = new DefaultHookRegistry();

    @BeforeEach
    void setUp() {
        llm = new ScriptedLlmClient();
        final DefaultToolExecutionManager tools = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hooks = new DefaultHookExecutionManager();
        subagents = new DefaultSubagentExecutionManager(llm, tools, hooks);
        executor = new OrcaAgentExecutor(llm, new DefaultTranscriptManager(new InMemorySessionRecordStore()), tools,
                hooks, new DefaultCommandExecutionManager(llm), subagents);
    }

    @AfterEach
    void close() {
        subagents.close();
        harness.close();
    }

    private OrcaAgentRuntime runtime() {
        return runtime(false);
    }

    private OrcaAgentRuntime runtime(boolean withTaskTool) {
        return runtime(withTaskTool, Map.of());
    }

    private OrcaAgentRuntime runtime(Map<String, String> attributes) {
        return runtime(false, attributes);
    }

    private OrcaAgentRuntime runtime(boolean withTaskTool, Map<String, String> attributes) {
        final LocalFileSystem control = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        control.initialize();
        final DefaultCommandRegistry commands = new DefaultCommandRegistry(List.of(), skills, control,
                ".aimon/commands");
        commands.initialize();
        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new BashTool());
        toolRegistry.register(new ReadTool());
        final LlmModel model = LlmModel.builder().name("m").build();
        if (withTaskTool) {
            toolRegistry.register(new TaskTool(model, subagentRegistry, toolRegistry, hookRegistry, subagents));
        }
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("sandboxed").maxIterations(3).systemPrompt("You are a test agent")
                        .model(model).attributes(attributes).build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commands)
                .subagentRegistry(subagentRegistry).skillRegistry(skills).controlFileSystem(control)
                .executionEnvironmentProvider(harness.sandbox.environmentProvider()).build();
    }

    @Test
    @DisplayName("§16: a turn without commands provisions nothing; the prompt shows the profile's declared values")
    void textOnlyTurnProvisionsNothingAndPromptShowsDeclaredValues() {
        llm.respond(LlmResponse.text("Hello."));

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("hi").sessionId(SessionId.generate()).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(harness.faults.calls(Operation.CREATE)).isZero();
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).isEmpty();
        assertThat(llm.systemPrompts.get(0)).contains("Working directory: /workspace/repo")
                .contains("Platform: " + SandboxTestProfiles.hostPlatform())
                .contains("isolated sandbox (profile 'standard')")
                .contains("shell state persists cwd and exported variables only");
    }

    @Test
    @DisplayName("§16: a slash-command skill's Bash runs in the sandbox, not on the host")
    void slashCommandSkillBashRunsInSandbox() throws Exception {
        skills.add(Skill.builder().name("mark")
                .metadata(SkillMetadata.builder().name("mark").description("writes a marker")
                        .invokePolicy(InvokePolicy.of(true, true)).executionMode(ExecutionMode.INLINE)
                        .allowedTools("Bash").build())
                .content(SkillContent.of("Write the marker file.")).build());
        llm.respond(LlmResponse
                .tools(List.of(ToolUse.of("t1", "Bash", Map.of("command", "echo sandboxed > marker.txt; pwd")))));
        llm.respond(LlmResponse.text("Marker written."));
        final SessionId session = SessionId.generate();

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("/mark").sessionId(session).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(harness.hostFile(session, "/workspace/repo/marker.txt")).isEqualTo("sandboxed\n");
        assertThat(Files.exists(Path.of("marker.txt"))).as("nothing on the host's cwd").isFalse();
        assertThat(Files.exists(tempDir.resolve("marker.txt"))).isFalse();
        assertThat(llm.toolResults).anyMatch(text -> text.contains("/workspace/repo"));
        // Routing, not only the Bash call the script makes anyway: the slash command expanded the skill's body.
        assertThat(llm.userMessages).anyMatch(text -> text.contains("Write the marker file."));
    }

    @Test
    @DisplayName("§16: a slash-command skill run in a forked subagent also runs its Bash in the sandbox")
    void forkedSlashCommandSkillBashRunsInTheSameSandbox() throws Exception {
        subagentRegistry.add(
                Subagent.of("worker", SubagentMetadata.builder().description("does the work").maxIterations(3).build(),
                        SubagentContent.of("You are the worker.")));
        skills.add(Skill.builder().name("forked")
                .metadata(SkillMetadata.builder().name("forked").description("writes a marker in a fork")
                        .invokePolicy(InvokePolicy.of(true, true)).executionMode(ExecutionMode.FORK)
                        .forkAgentName("worker").build())
                .content(SkillContent.of("Write the fork marker file.")).build());
        llm.respond(LlmResponse
                .tools(List.of(ToolUse.of("f1", "Bash", Map.of("command", "echo forked > fork-marker.txt; pwd")))));
        llm.respond(LlmResponse.text("Fork marker written."));
        final SessionId session = SessionId.generate();

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("/forked").sessionId(session).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(llm.userMessages).anyMatch(text -> text.contains("Write the fork marker file."));
        // The fork shares the session's workspace and slot (§8.2): the marker is in the same sandbox. A fork no longer
        // inherits its parent's caller, so this passes only because core's slash-command fork path forwards the
        // caller's principal (core 0.3.1, OrcaAgentExecutor's command context); a dropped principal reads "not
        // permitted".
        assertThat(llm.toolResults).noneMatch(text -> text.contains("not permitted"));
        assertThat(harness.hostFile(session, "/workspace/repo/fork-marker.txt")).isEqualTo("forked\n");
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).hasSize(1);
        assertThat(Files.exists(Path.of("fork-marker.txt"))).as("nothing on the host's cwd").isFalse();
        assertThat(Files.exists(tempDir.resolve("fork-marker.txt"))).isFalse();
    }

    @Test
    @DisplayName("§8.2: a Task subagent's Bash runs in the session's sandbox as the caller, with no fallback")
    void taskSubagentBashRunsInTheSameSandboxAsTheCaller() throws Exception {
        subagentRegistry.add(
                Subagent.of("worker", SubagentMetadata.builder().description("does the work").maxIterations(3).build(),
                        SubagentContent.of("You are the worker.")));
        llm.respond(LlmResponse.tools(List.of(ToolUse.of("t1", "Task",
                Map.of("subagent_name", "worker", "prompt", "Write the task marker file.", "description", "mark")))));
        llm.respond(LlmResponse
                .tools(List.of(ToolUse.of("w1", "Bash", Map.of("command", "echo tasked > task-marker.txt; pwd")))));
        llm.respond(LlmResponse.text("Task marker written."));
        llm.respond(LlmResponse.text("Done."));
        final SessionId session = SessionId.generate();

        final OrcaAgentExecutionResult result = executor.execute(runtime(true), OrcaAgentExecutionRequest.builder()
                .userInput("go").sessionId(session).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        // Core's Task tool forwards the caller's principal into the subagent's environment request (core 0.3.1,
        // TaskTool → DefaultSubagentExecutionManager → DefaultSubagentExecutor); a dropped one reads "not permitted".
        assertThat(llm.toolResults).noneMatch(text -> text.contains("not permitted"));
        assertThat(harness.hostFile(session, "/workspace/repo/task-marker.txt")).isEqualTo("tasked\n");
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).hasSize(1);
    }

    /**
     * Registers the {@code preTool} hooks of a skill parsed with core's shell-capable hook parser — the hook a
     * skill's fork would get, without the fork around it.
     */
    private void guardWith(String hooksYaml) {
        final MarkdownSkillParser parser = new MarkdownSkillParser(new ShellArgumentTokenizer(),
                new SkillHookSetParser(new DefaultShellActionExecutor()));
        final Skill skill = parser.parse("guard",
                "---\nname: guard\ndescription: guards Bash\nhooks:\n  preTool:\n" + hooksYaml + "---\n\nGuard.");
        assertThat(skill.getMetadata().getHooks().getPreToolHooks()).isNotEmpty();
        skill.getMetadata().getHooks().getPreToolHooks()
                .forEach(hook -> hookRegistry.register(HookEventType.PRE_TOOL, hook));
    }

    @Test
    @DisplayName("§12.1: a skill's preTool shell hook runs in the sandbox, not on the host")
    void skillShellHookRunsInTheSandbox() throws Exception {
        guardWith("    - action: { type: shell, command: \"printf '%s' \\\"$AIMON_TOOL_NAME\\\" > hook-ran.txt\" }\n");
        llm.respond(LlmResponse.tools(List.of(ToolUse.of("t1", "Bash", Map.of("command", "echo tool > tool.txt")))));
        llm.respond(LlmResponse.text("Done."));
        final SessionId session = SessionId.generate();

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("go").sessionId(session).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(harness.hostFile(session, "/workspace/repo/hook-ran.txt")).isEqualTo("Bash");
        assertThat(harness.hostFile(session, "/workspace/repo/tool.txt")).isEqualTo("tool\n");
        assertThat(Files.exists(Path.of("hook-ran.txt"))).as("nothing on the host's cwd").isFalse();
        assertThat(Files.exists(tempDir.resolve("hook-ran.txt"))).isFalse();
    }

    @Test
    @DisplayName("§12.1: a skill's preTool shell hook runs outside the model's shell session: no shell lock, no state")
    void skillShellHookRunsOutsideTheModelsShellSession() throws Exception {
        guardWith("    - action: { type: shell, command: \"cd /tmp; export FROM_HOOK=1; echo hook-marker\" }\n");
        llm.respond(LlmResponse.tools(List.of(ToolUse.of("t1", "Bash", Map.of("command", "mkdir -p src; cd src")))));
        llm.respond(LlmResponse
                .tools(List.of(ToolUse.of("t2", "Bash", Map.of("command", "pwd; echo \"FROM_HOOK=$FROM_HOOK\"")))));
        llm.respond(LlmResponse.text("Done."));

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("go").sessionId(SessionId.generate()).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        // Core marks the hook's command (ExecutionOptions.isHook()), so the wrapper it runs in takes no shell lock —
        // the model's own commands do.
        assertThat(recorder.runs).filteredOn(spec -> spec.command().contains("hook-marker")).hasSize(2)
                .allMatch(spec -> !spec.command().contains("flock"));
        assertThat(recorder.runs).filteredOn(spec -> spec.command().contains("FROM_HOOK=$FROM_HOOK")).singleElement()
                .matches(spec -> spec.command().contains("flock"));
        assertThat(llm.toolResults).anyMatch(text -> text.contains("/workspace/repo/src\nFROM_HOOK=\n"));
    }

    @Test
    @DisplayName("§12.1: a skill's preTool shell guard that exits 2 in the sandbox blocks the tool")
    void skillShellGuardVetoBlocksTheTool() throws Exception {
        guardWith("    - action: { type: shell, command: \"echo 'no writes here' >&2; exit 2\" }\n");
        llm.respond(LlmResponse.tools(List.of(ToolUse.of("t1", "Bash", Map.of("command", "echo x > blocked.txt")))));
        llm.respond(LlmResponse.text("Acknowledged."));
        final SessionId session = SessionId.generate();

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("go").sessionId(session).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(llm.toolResults).anyMatch(text -> text.contains("no writes here"));
        assertThat(harness.host(session, "/workspace/repo/blocked.txt")).doesNotExist();
    }

    @Test
    @DisplayName("§12.1: a skill's preTool shell guard blocks the tool when the sandbox is unavailable (fail-closed)")
    void skillShellGuardBlocksWhenTheSandboxIsUnavailable() {
        guardWith("    - action: { type: shell, command: \"exit 0\" }\n");
        harness.faults.inject(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.PERMANENT));
        llm.respond(LlmResponse.tools(List.of(ToolUse.of("t1", "Bash", Map.of("command", "echo x")))));
        llm.respond(LlmResponse.text("Acknowledged."));

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("go").sessionId(SessionId.generate()).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(harness.faults.calls(Operation.CREATE)).isPositive();
        assertThat(llm.toolResults)
                .anyMatch(text -> text.contains("Blocked: guard hook 'guard' (preTool) could not" + " run its command")
                        && text.contains("execution environment unavailable"));
    }

    @Test
    @DisplayName("§12.1: a failOpen observer lets the tool through when the sandbox is unavailable")
    void failOpenObserverDoesNotBlockWhenTheSandboxIsUnavailable() {
        guardWith("    - action: { type: shell, command: \"exit 0\" }\n      failOpen: true\n");
        harness.faults.inject(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.PERMANENT));
        llm.respond(LlmResponse.tools(List.of(ToolUse.of("t1", "Bash", Map.of("command", "echo x")))));
        llm.respond(LlmResponse.text("Acknowledged."));

        final OrcaAgentExecutionResult result = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("go").sessionId(SessionId.generate()).principal(SandboxHarness.ALICE).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(llm.toolResults).isNotEmpty().noneMatch(text -> text.contains("Blocked: guard hook"));
    }

    private OrcaAgentExecutionResult runBash(OrcaAgentRuntime runtime, SessionId session, String command) {
        llm.respond(LlmResponse
                .tools(List.of(ToolUse.of("b" + llm.toolResults.size(), "Bash", Map.of("command", command)))));
        llm.respond(LlmResponse.text("Done."));
        return executor.execute(runtime, OrcaAgentExecutionRequest.builder().userInput("go").sessionId(session)
                .principal(SandboxHarness.ALICE).build());
    }

    @Test
    @DisplayName("management §9.3: an agent's sandbox.workspace runs its Bash in the pre-created sandbox")
    void anAgentDefaultRunsBashInThePreCreatedSandbox() throws Exception {
        final WorkspaceView created = harness.sandbox.workspaces().create(CreateWorkspace.named("pre-created"),
                SandboxHarness.ALICE);
        final SessionId session = SessionId.generate();

        final OrcaAgentExecutionResult result = runBash(
                runtime(Map.of(SlotChoice.WORKSPACE_ATTRIBUTE, created.id().value())), session,
                "echo agent-default > marker.txt");

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(managedFile(created.id(), "/workspace/repo/marker.txt")).isEqualTo("agent-default\n");
        assertThat(harness.store.find(SandboxWorkspaceId.of("ws:" + session.value()))).isEmpty();
        // No options record: the session was never redirected, so it hears of no switch (§6.4).
        assertThat(llm.toolResults).noneMatch(text -> text.contains("now runs in sandbox workspace"));
    }

    @Test
    @DisplayName("management §9.3: a session's options attach it elsewhere, over the agent default")
    void sessionOptionsOverrideTheAgentDefault() throws Exception {
        final WorkspaceView agentDefault = harness.sandbox.workspaces().create(CreateWorkspace.named("default"),
                SandboxHarness.ALICE);
        final WorkspaceView attached = harness.sandbox.workspaces().create(CreateWorkspace.named("attached"),
                SandboxHarness.ALICE);
        final SessionId session = SessionId.generate();
        harness.sandbox.workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(attached.id()),
                SandboxHarness.ALICE);

        final OrcaAgentExecutionResult result = runBash(
                runtime(Map.of(SlotChoice.WORKSPACE_ATTRIBUTE, agentDefault.id().value())), session,
                "echo attached > marker.txt");

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(managedFile(attached.id(), "/workspace/repo/marker.txt")).isEqualTo("attached\n");
        assertThat(Files.exists(managedPath(agentDefault.id(), "/workspace/repo/marker.txt"))).isFalse();
    }

    @Test
    @DisplayName("management §9.3: sandbox.unattached reject — an unattached session's tool call reports it")
    void anUnattachedSessionUnderRejectReportsTheRejection() {
        final SessionId session = SessionId.generate();

        final OrcaAgentExecutionResult result = runBash(runtime(Map.of(SlotChoice.UNATTACHED_ATTRIBUTE, "reject")),
                session, "echo x");

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(llm.toolResults).anyMatch(text -> text.contains("not attached to a sandbox workspace"));
        assertThat(harness.faults.calls(Operation.CREATE)).isZero();
    }

    @Test
    @DisplayName("management §6.3: a session option can tighten the rule, and per-session runs where nothing tightens")
    void theSessionOptionDecidesWithinTheFloorAndTheAgent() throws Exception {
        final SessionId strict = SessionId.generate();
        final SessionId relaxed = SessionId.generate();
        harness.sandbox.workspaces().setSessionOptions(strict, SessionSandboxOptions.Update.rule(UnattachedRule.REJECT),
                SandboxHarness.ALICE);
        harness.sandbox.workspaces().setSessionOptions(relaxed,
                SessionSandboxOptions.Update.rule(UnattachedRule.PER_SESSION), SandboxHarness.ALICE);

        runBash(runtime(), strict, "echo strict");
        assertThat(llm.toolResults).anyMatch(text -> text.contains("not attached to a sandbox workspace"));

        final OrcaAgentExecutionResult result = runBash(runtime(), relaxed, "echo relaxed > relaxed.txt");
        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(harness.hostFile(relaxed, "/workspace/repo/relaxed.txt")).isEqualTo("relaxed\n");

        llm.toolResults.clear();
        runBash(runtime(Map.of(SlotChoice.UNATTACHED_ATTRIBUTE, "reject")), relaxed, "echo again");
        assertThat(llm.toolResults).as("a session option cannot loosen the agent's reject")
                .anyMatch(text -> text.contains("not attached to a sandbox workspace"));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // core 0.3.3 seams through the real loop (docs/design/workspace-sandbox-core-033.md §9)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("core-033 #252: a turn's request attribute runs its Bash, and its Task fork's, in the named workspace")
    void aRequestAttributeTurnAndItsForkRunInTheNamedWorkspace() throws Exception {
        subagentRegistry.add(
                Subagent.of("worker", SubagentMetadata.builder().description("does the work").maxIterations(3).build(),
                        SubagentContent.of("You are the worker.")));
        final WorkspaceView created = harness.sandbox.workspaces().create(CreateWorkspace.named("placed"),
                SandboxHarness.ALICE);
        final SessionId session = SessionId.generate();
        llm.respond(
                LlmResponse.tools(List.of(ToolUse.of("t1", "Bash", Map.of("command", "echo turn > turn-marker.txt")))));
        llm.respond(LlmResponse.tools(List.of(ToolUse.of("t2", "Task",
                Map.of("subagent_name", "worker", "prompt", "Write the fork marker.", "description", "mark")))));
        llm.respond(LlmResponse
                .tools(List.of(ToolUse.of("w1", "Bash", Map.of("command", "cat turn-marker.txt > fork-marker.txt")))));
        llm.respond(LlmResponse.text("Fork done."));
        llm.respond(LlmResponse.text("Done."));

        final OrcaAgentExecutionResult result = executor.execute(runtime(true),
                OrcaAgentExecutionRequest.builder().userInput("go").sessionId(session).principal(SandboxHarness.ALICE)
                        .executionAttributes(SandboxRequestAttributes.workspace(created.id())).build());

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(managedFile(created.id(), "/workspace/repo/fork-marker.txt")).isEqualTo("turn\n");
        assertThat(harness.store.find(SandboxWorkspaceId.of("ws:" + session.value()))).isEmpty();
        assertThat(llm.resultsById.get("t1")).as("delivered by core's drain, once")
                .startsWith(EnvironmentNotices.PREFIX + "this session now runs in sandbox workspace " + created.id());
        assertThat(llm.resultsById.values()).filteredOn(text -> text.contains("now runs in sandbox workspace"))
                .hasSize(1);
        assertThat(harness.sessionOptions.find(session).orElseThrow().lastWorkspaceId()).contains(created.id());
        assertThat(harness.sessionOptions.find(session).orElseThrow().workspace())
                .as("a request attribute writes no session option").isEmpty();
    }

    /** A turn of {@code Read} calls only, each its own tool use. */
    private OrcaAgentExecutionResult runReads(SessionId session, String... ids) {
        for (String id : ids) {
            llm.respond(LlmResponse
                    .tools(List.of(ToolUse.of(id, "Read", Map.of("file_path", "/workspace/repo/missing.txt")))));
        }
        llm.respond(LlmResponse.text("Done."));
        return executor.execute(runtime(), OrcaAgentExecutionRequest.builder().userInput("look").sessionId(session)
                .principal(SandboxHarness.ALICE).build());
    }

    private void idleClose(SessionId session) {
        harness.clock.advance(java.time.Duration.ofHours(3));
        harness.sandbox.janitor().runOnce();
        harness.clock.advance(java.time.Duration.ofHours(25));
        harness.sandbox.janitor().runOnce();
        assertThat(harness.record(session).state()).isEqualTo(at.aimon.sandbox.workspace.WorkspaceState.CLOSED);
    }

    @Test
    @DisplayName("core-033 #254: a Read-only turn hears an idle reset on its first tool result, exactly once")
    void aReadOnlyTurnHearsTheResetExactlyOnce() throws Exception {
        final SessionId session = SessionId.generate();
        runBash(runtime(), session, "echo before > kept.txt");
        idleClose(session);

        final OrcaAgentExecutionResult result = runReads(session, "r1", "r2");

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(llm.resultsById.get("r1")).startsWith(
                EnvironmentNotices.PREFIX + at.aimon.sandbox.workspace.SandboxWorkspaceManager.RESET_NOTICE);
        assertThat(llm.resultsById.get("r2")).doesNotContain(EnvironmentNotices.PREFIX);
    }

    @Test
    @DisplayName("core-033 #254: an attached session's switch reaches its first Read only, and is recorded")
    void aReadOnlyTurnHearsTheSwitchOnceAndRecordsIt() throws Exception {
        final WorkspaceView attached = harness.sandbox.workspaces().create(CreateWorkspace.named("moved"),
                SandboxHarness.ALICE);
        final SessionId session = SessionId.generate();
        harness.sandbox.workspaces().setSessionOptions(session, SessionSandboxOptions.Update.attach(attached.id()),
                SandboxHarness.ALICE);

        runReads(session, "r1", "r2");

        assertThat(llm.resultsById.get("r1")).startsWith(EnvironmentNotices.PREFIX + "this session now runs in");
        assertThat(llm.resultsById.get("r2")).doesNotContain(EnvironmentNotices.PREFIX);
        assertThat(harness.sessionOptions.find(session).orElseThrow().lastWorkspaceId()).contains(attached.id());
        runReads(session, "r3");
        assertThat(llm.resultsById.get("r3")).doesNotContain(EnvironmentNotices.PREFIX);
    }

    @Test
    @DisplayName("core-033 #254: a Bash result carries the drained line once, and not among the command's own")
    void aBashResultCarriesTheResetOnce() throws Exception {
        final SessionId session = SessionId.generate();
        runBash(runtime(), session, "true");
        idleClose(session);

        runBash(runtime(), session, "echo after");

        // The session's transcript replays the earlier turn's result too; this turn's is the one that says "after".
        final String text = llm.resultsById.values().stream().filter(result -> result.contains("after")).findFirst()
                .orElseThrow();
        assertThat(text).startsWith(EnvironmentNotices.PREFIX + "/workspace was reset").contains("after");
        assertThat(text.split("/workspace was reset", -1)).hasSize(2);
    }

    /** Registers the {@code onStop} hooks of a skill parsed with core's shell-capable hook parser. */
    private void onStopWith(String hooksYaml) {
        final MarkdownSkillParser parser = new MarkdownSkillParser(new ShellArgumentTokenizer(),
                new SkillHookSetParser(new DefaultShellActionExecutor()));
        final Skill skill = parser.parse("stopper",
                "---\nname: stopper\ndescription: runs at turn end\nhooks:\n  onStop:\n" + hooksYaml + "---\n\nStop.");
        assertThat(skill.getMetadata().getHooks().getOnStopHooks()).isNotEmpty();
        skill.getMetadata().getHooks().getOnStopHooks()
                .forEach(hook -> hookRegistry.register(HookEventType.ON_STOP, hook));
    }

    @Test
    @DisplayName("#28: a text-only turn whose onStop hook resets the sandbox — the next turn's first Read hears it once")
    void aStopHooksResetReachesTheNextTurnOnce() throws Exception {
        final SessionId session = SessionId.generate();
        runBash(runtime(), session, "echo before > kept.txt");
        idleClose(session);
        onStopWith("    - action: { type: shell, command: \"true\" }\n");

        llm.respond(LlmResponse.text("Nothing to do."));
        final OrcaAgentExecutionResult quiet = executor.execute(runtime(), OrcaAgentExecutionRequest.builder()
                .userInput("hi").sessionId(session).principal(SandboxHarness.ALICE).build());
        assertThat(quiet.isSuccess()).as(quiet.getErrorMessage()).isTrue();
        assertThat(harness.record(session).state()).as("the onStop hook reopened the workspace")
                .isEqualTo(at.aimon.sandbox.workspace.WorkspaceState.OPEN);
        assertThat(harness.sessionOptions.find(session).orElseThrow().unannouncedReset()).isPresent();

        final OrcaAgentExecutionResult result = runReads(session, "r1", "r2");

        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(llm.resultsById.get("r1")).startsWith(EnvironmentNotices.PREFIX + "/workspace was reset at ");
        assertThat(llm.resultsById.get("r1").split("/workspace was reset", -1)).hasSize(2);
        assertThat(llm.resultsById.get("r2")).doesNotContain(EnvironmentNotices.PREFIX);
        assertThat(harness.sessionOptions.find(session)).isEmpty();
        runReads(session, "r3");
        assertThat(llm.resultsById.get("r3")).doesNotContain(EnvironmentNotices.PREFIX);
    }

    private Path managedPath(SandboxWorkspaceId id, String sandboxPath) {
        final ProviderSandboxRef ref = harness.store.find(id).orElseThrow().slot("primary").orElseThrow().providerRef()
                .orElseThrow();
        return harness.local.hostRoot(ref).resolve(sandboxPath.substring(1));
    }

    private String managedFile(SandboxWorkspaceId id, String sandboxPath) throws java.io.IOException {
        return Files.readString(managedPath(id, sandboxPath));
    }

    /** Answers from a queue and records the prompts and tool results it was sent. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();
        /** Each tool result once, by tool-use id: {@link #toolResults} repeats a result with every later request. */
        private final Map<String, String> resultsById = new java.util.LinkedHashMap<>();
        private final List<String> userMessages = new ArrayList<>();

        void respond(LlmResponse response) {
            responses.add(response);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public synchronized LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            systemPrompts.add(systemPrompt);
            for (Message message : messages) {
                message.getToolUseResults().forEach(r -> {
                    toolResults.add(String.valueOf(r.getContent()));
                    resultsById.put(r.getToolUseId(), String.valueOf(r.getContent()));
                });
                userMessages.add(String.valueOf(message.getContent()));
            }
            return responses.isEmpty() ? LlmResponse.text("done") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "scripted";
        }
    }

    private static final class MapSkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new HashMap<>();

        void add(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.ofNullable(skills.get(skillName));
        }

        @Override
        public List<Skill> getAllSkills() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            // nothing to reload
        }

        @Override
        public void reloadAll() {
            // nothing to reload
        }
    }

    private static final class MapSubagentRegistry implements SubagentRegistry {
        private final Map<String, Subagent> subagents = new HashMap<>();

        void add(Subagent subagent) {
            subagents.put(subagent.getName(), subagent);
        }

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return Optional.ofNullable(subagents.get(subagentName));
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return new ArrayList<>(subagents.values());
        }

        @Override
        public void reloadSubagent(String subagentName) {
            // nothing to reload
        }

        @Override
        public void reloadAll() {
            // nothing to reload
        }
    }
}
