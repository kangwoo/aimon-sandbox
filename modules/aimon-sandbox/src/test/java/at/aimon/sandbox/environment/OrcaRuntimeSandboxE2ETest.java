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
import at.aimon.core.base.UserLocale;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
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
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.tools.bash.BashTool;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;
import at.aimon.sandbox.workspace.WorkspaceScan;

/**
 * The sandbox behind a real {@link OrcaAgentExecutor} (§16 rows 14 and 15): the prompt describes the declared
 * profile without provisioning anything, and a slash-command skill's {@code Bash} runs in the sandbox, not on the host
 * — inline in the session's turn, and in a forked subagent.
 */
class OrcaRuntimeSandboxE2ETest {

    @TempDir
    Path tempDir;

    private final SandboxHarness harness = SandboxHarness.standard();
    private ScriptedLlmClient llm;
    private DefaultSubagentExecutionManager subagents;
    private OrcaAgentExecutor executor;
    private final MapSkillRegistry skills = new MapSkillRegistry();
    private final MapSubagentRegistry subagentRegistry = new MapSubagentRegistry();

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
        final LocalFileSystem control = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        control.initialize();
        final DefaultCommandRegistry commands = new DefaultCommandRegistry(List.of(), skills, control,
                ".aimon/commands");
        commands.initialize();
        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new BashTool());
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("sandboxed").maxIterations(3).systemPrompt("You are a test agent")
                        .model(LlmModel.builder().name("m").build()).build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry()).commandRegistry(commands)
                .subagentRegistry(subagentRegistry).skillRegistry(skills).controlFileSystem(control)
                .userLocale(UserLocale.createDefault())
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
        // The fork shares the session's workspace and slot (§8.2): the marker is in the same sandbox. Core's skill-fork
        // path forwards the caller's principal (core PR #200), so the fork acts as that caller, not "not permitted".
        assertThat(llm.toolResults).noneMatch(text -> text.contains("not permitted"));
        assertThat(harness.hostFile(session, "/workspace/repo/fork-marker.txt")).isEqualTo("forked\n");
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).hasSize(1);
        assertThat(Files.exists(Path.of("fork-marker.txt"))).as("nothing on the host's cwd").isFalse();
        assertThat(Files.exists(tempDir.resolve("fork-marker.txt"))).isFalse();
    }

    /** Answers from a queue and records the prompts and tool results it was sent. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();
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
                message.getToolUseResults().forEach(r -> toolResults.add(String.valueOf(r.getContent())));
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
