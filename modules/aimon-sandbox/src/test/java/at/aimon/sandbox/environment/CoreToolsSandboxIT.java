package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.bash.BashTool;
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
