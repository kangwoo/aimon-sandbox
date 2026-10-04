package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.bash.BackgroundBashManager;
import at.aimon.core.tools.bash.BashOutputTool;
import at.aimon.core.tools.bash.BashTool;
import at.aimon.core.tools.bash.KillShellTool;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.file.WriteTool;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;

/** Core's own file and shell tools on sandbox environments (§16 rows 1 and 4, §15 messages). */
class CoreToolsSandboxIT {

    private final SandboxHarness harness = SandboxHarness.standard();

    @AfterEach
    void close() {
        harness.close();
    }

    private static ToolContext context(ExecutionEnvironment environment) {
        return ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment)
                .put(ReadTool.FILE_STAMPS_KEY, new ConcurrentHashMap<String, FileStamp>()).build();
    }

    private static ToolResult write(ExecutionEnvironment environment, String path, String content) {
        return new WriteTool().execute(ToolInput.of(Map.of("file_path", path, "content", content)),
                context(environment));
    }

    private static ToolResult read(ExecutionEnvironment environment, String path) {
        return new ReadTool().execute(ToolInput.of(Map.of("file_path", path)), context(environment));
    }

    private static ToolResult bash(ExecutionEnvironment environment, String command) {
        return new BashTool().execute(ToolInput.of(Map.of("command", command)), context(environment));
    }

    @Test
    void writeByMainTurnIsReadByFork() {
        final ExecutionEnvironment main = harness.mainTurn(SessionId.generate(), ALICE);
        final ExecutionEnvironment fork = harness.fork(main, ALICE);

        final ToolResult written = write(main, "/workspace/repo/Foo.java", "class Foo {}\n");
        final ToolResult readBack = read(fork, "/workspace/repo/Foo.java");

        assertThat(written.isSuccess()).as(written.getContent()).isTrue();
        assertThat(readBack.isSuccess()).as(readBack.getContent()).isTrue();
        assertThat(readBack.getContent()).contains("class Foo {}");
    }

    @Test
    void fileToolAndShellSeeOneFileSystem() {
        final ExecutionEnvironment env = harness.mainTurn(SessionId.generate(), ALICE);

        write(env, "notes.txt", "same bytes");
        final ToolResult cat = bash(env, "cat /workspace/repo/notes.txt");

        assertThat(cat.isSuccess()).as(cat.getContent()).isTrue();
        assertThat(cat.getContent()).contains("same bytes");
    }

    @Test
    @DisplayName("§16: a background Bash in a sandbox says how to stop it and when it ends; KillShell stops it")
    void killShellStopsASandboxBackgroundCommand() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        final ToolContext context = context(env);
        bash(env, "true");
        try (BackgroundBashManager manager = new BackgroundBashManager()) {
            final ToolResult started = new BashTool(manager).execute(ToolInput.of(
                    Map.of("command", "echo $$ > /workspace/pid.tmp; mv /workspace/pid.tmp /workspace/pid; sleep 60",
                            "run_in_background", true)),
                    context);

            assertThat(started.isSuccess()).as(started.getContent()).isTrue();
            assertThat(started.getContent()).contains("Use KillShell(taskId=\"")
                    .contains("The environment stops it after 24 hours if it is still running.");
            final Matcher id = Pattern.compile("ID: (\\S+)").matcher(started.getContent());
            assertThat(id.find()).isTrue();
            final String taskId = id.group(1);
            final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!Files.exists(harness.host(session, "/workspace/pid")) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            final long pid = Long.parseLong(harness.hostFile(session, "/workspace/pid").strip());

            final ToolResult killed = new KillShellTool(manager).execute(ToolInput.of(Map.of("taskId", taskId)),
                    context);
            final ToolResult output = new BashOutputTool(manager).execute(ToolInput.of(Map.of("taskId", taskId)),
                    context);

            assertThat(killed.isSuccess()).as(killed.getContent()).isTrue();
            assertThat(killed.getContent()).contains("stopped: the command and the processes it was running");
            assertThat(output.getContent()).contains("Status: Killed");
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

    @Test
    void shellChangeAfterReadIsCaughtByTheStampCheck() {
        final ExecutionEnvironment env = harness.mainTurn(SessionId.generate(), ALICE);
        final ToolContext context = context(env);
        new WriteTool().execute(ToolInput.of(Map.of("file_path", "a.txt", "content", "one")), context);
        new ReadTool().execute(ToolInput.of(Map.of("file_path", "a.txt")), context);
        bash(env, "printf two > /workspace/repo/a.txt");

        final ToolResult stale = new WriteTool().execute(ToolInput.of(Map.of("file_path", "a.txt", "content", "three")),
                context);

        assertThat(stale.isSuccess()).isFalse();
    }

    @Test
    void bindingRejectionReachesTheModelThroughTheTools() {
        final ExecutionEnvironment unbound = harness.resolve(at.aimon.core.environment.EnvironmentRequest.builder()
                .agentRuntimeId(SandboxHarness.RUNTIME).principal(ALICE).build());

        final ToolResult readResult = read(unbound, "a.txt");
        final ToolResult bashResult = bash(unbound, "echo hi");

        assertThat(readResult.isSuccess()).isFalse();
        assertThat(readResult.getContent()).contains("neither a session nor an execution id");
        assertThat(bashResult.isSuccess()).isFalse();
        assertThat(bashResult.getContent()).contains("neither a session nor an execution id");
    }

    @Test
    void sandboxUnavailabilityReachesTheModelAsTheSection15Message() {
        final SessionId session = SessionId.generate();
        harness.mainTurn(session, ALICE);
        final ExecutionEnvironment intruder = harness.mainTurn(session, Principal.user("mallory"));
        write(harness.mainTurn(session, ALICE), "a.txt", "x");

        final ToolResult readResult = read(intruder, "a.txt");
        final ToolResult bashResult = bash(intruder, "cat a.txt");

        assertThat(readResult.getContent()).contains("not permitted");
        assertThat(bashResult.getContent()).contains("not permitted");
    }

    @Test
    void provisioningFailureIsReportedNotFallenBackToTheHost() {
        harness.faults.inject(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.TRANSIENT));
        final ExecutionEnvironment env = harness.mainTurn(SessionId.generate(), ALICE);

        final ToolResult result = bash(env, "echo host");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).contains("could not be provisioned").doesNotContain("host\n");
    }
}
