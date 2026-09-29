package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.ContentQuery;
import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.ContentSearchResult;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.file.GrepTool;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.sandbox.DelegatingProvider;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.testkit.LocalProcessSandboxProvider;

/** {@code rg --json} inside the sandbox (§7). Real searches need ripgrep on the host; the stub must fail loudly. */
class SandboxContentSearchIT {

    private final SandboxHarness harness = SandboxHarness.standard();
    private final ExecutionEnvironment env = harness.mainTurn(SessionId.generate(), ALICE);

    @AfterEach
    void close() {
        harness.close();
    }

    private ContentSearch search() {
        env.fileSystem().write("src/A.java", "class A {\n  // needle here\n}\n");
        env.fileSystem().write("src/b.txt", "no match\n");
        return env.contentSearch().orElseThrow();
    }

    @Test
    void findsMatchesWithContextRelativeToTheRoot() {
        assumeTrue(LocalProcessSandboxProvider.hostHasRipgrep(), "ripgrep is not installed on this host");

        final ContentSearchResult result = search()
                .search(ContentQuery.builder().pattern("needle").path(".").beforeContext(1).build());

        assertThat(result.getFiles()).hasSize(1);
        assertThat(result.getFiles().get(0).getPath()).isEqualTo("src/A.java");
        assertThat(result.getFiles().get(0).getMatches().get(0).getLineNumber()).isEqualTo(2);
        assertThat(result.getFiles().get(0).getMatches().get(0).getBefore()).containsExactly("class A {");
    }

    @Test
    void withoutRipgrepTheStubFailsLoudlyRatherThanReportingNoMatches() {
        assumeFalse(LocalProcessSandboxProvider.hostHasRipgrep(), "the host has a real ripgrep");
        final ContentSearch search = search();

        assertThatThrownBy(() -> search.search(ContentQuery.builder().pattern("needle").path(".").build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ripgrep is not installed");
    }

    @Test
    void rgJsonOutputIsParsedIntoMatchesWithContext() {
        final String json = String.join("\n",
                "{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"/workspace/repo/src/A.java\"}}}",
                "{\"type\":\"context\",\"data\":{\"path\":{\"text\":\"/workspace/repo/src/A.java\"},"
                        + "\"lines\":{\"text\":\"class A {\\n\"},\"line_number\":1}}",
                "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"/workspace/repo/src/A.java\"},"
                        + "\"lines\":{\"text\":\"  // needle\\r\\n\"},\"line_number\":2}}",
                "{\"type\":\"context\",\"data\":{\"path\":{\"text\":\"/workspace/repo/src/A.java\"},"
                        + "\"lines\":{\"text\":\"}\\n\"},\"line_number\":3}}",
                "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"/workspace/other.txt\"},"
                        + "\"lines\":{\"text\":\"needle\"},\"line_number\":7}}",
                "{\"type\":\"end\",\"data\":{}}", "");
        final List<String> commands = new CopyOnWriteArrayList<>();
        try (SandboxHarness canned = SandboxHarness.builder()
                .decorate((provider, clock) -> new CannedRg(provider, json, commands)).build()) {
            final ExecutionEnvironment cannedEnv = canned.mainTurn(SessionId.generate(), ALICE);
            cannedEnv.fileSystem().exists("warm");

            final ContentSearchResult result = cannedEnv.contentSearch().orElseThrow()
                    .search(ContentQuery.builder().pattern("needle").path("src").caseInsensitive(true).beforeContext(1)
                            .afterContext(1).extensions(List.of(".java")).glob("*.java").build());

            assertThat(result.getFiles()).extracting(ContentSearchResult.FileMatches::getPath)
                    .containsExactly("/workspace/other.txt", "src/A.java");
            final ContentSearchResult.Match match = result.getFiles().get(1).getMatches().get(0);
            assertThat(match.getLine()).isEqualTo("  // needle");
            assertThat(match.getBefore()).containsExactly("class A {");
            assertThat(match.getAfter()).containsExactly("}");
            assertThat(commands).anyMatch(c -> c.contains("'rg' '--json'") && c.contains("'-i'")
                    && c.contains("'-B' '1'") && c.contains("'--iglob' '*.java'") && c.contains("'--glob' '*.java'")
                    && c.contains("'/workspace/repo/src'"));
        }
    }

    @Test
    void anRgExitOfOneMeansNoMatches() {
        try (SandboxHarness canned = SandboxHarness.builder()
                .decorate((provider, clock) -> new CannedRg(provider, null, new ArrayList<>())).build()) {
            final ExecutionEnvironment cannedEnv = canned.mainTurn(SessionId.generate(), ALICE);

            assertThat(cannedEnv.contentSearch().orElseThrow()
                    .search(ContentQuery.builder().pattern("x").path(".").build()).getFiles()).isEmpty();
        }
    }

    /** Answers {@code rg} commands with canned output (exit 1 when {@code json} is null); runs the rest. */
    private static final class CannedRg extends DelegatingProvider {
        private final String json;
        private final List<String> commands;

        CannedRg(SandboxProvider delegate, String json, List<String> commands) {
            super(delegate);
            this.json = json;
            this.commands = commands;
        }

        @Override
        public SandboxConnection connect(ProviderSandboxRef ref) {
            final SandboxConnection real = super.connect(ref);
            return new SandboxConnection() {
                @Override
                public RunningCommand run(ExecSpec spec, OutputSink sink) {
                    if (!spec.command().contains("'rg' '--json'")) {
                        return real.run(spec, sink);
                    }
                    commands.add(spec.command());
                    final ExecOutcome outcome = ExecOutcome.builder().exitCode(json == null ? 1 : 0)
                            .stdout(json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8)).build();
                    return new RunningCommand() {
                        @Override
                        public ExecOutcome await(Duration timeout) {
                            return outcome;
                        }

                        @Override
                        public void kill() {
                            // nothing runs
                        }
                    };
                }

                @Override
                public SandboxFiles files() {
                    return real.files();
                }

                @Override
                public void close() {
                    real.close();
                }
            };
        }
    }

    @Test
    void theSearchPathIsNormalisedBeforeRgSeesIt() {
        final List<String> commands = new CopyOnWriteArrayList<>();
        try (SandboxHarness canned = SandboxHarness.builder()
                .decorate((provider, clock) -> new CannedRg(provider, null, commands)).build()) {
            final ContentSearch search = canned.mainTurn(SessionId.generate(), ALICE).contentSearch().orElseThrow();

            search.search(ContentQuery.builder().pattern("x").path("./src//").build());
            search.search(ContentQuery.builder().pattern("x").path("../repo/src/").build());

            // rg echoes the path as given: only one spelling may reach it, the one listings use.
            assertThat(commands).hasSize(2).allMatch(c -> c.endsWith("'--' '/workspace/repo/src'"));
        }
    }

    @Test
    void aCancelledQueryKillsRgAndStops() throws Exception {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness slow = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final SessionId session = SessionId.generate();
            final ExecutionEnvironment slowEnv = slow.mainTurn(session, ALICE);
            slowEnv.fileSystem().exists("warm");
            // An rg that would run far longer than the test: it records its pid, then sleeps.
            recording[0].rewrite(spec -> spec.command().contains("'rg' '--json'")
                    ? ExecSpec.builder()
                            .command("echo $$ > /workspace/rg.tmp; mv /workspace/rg.tmp /workspace/rg.pid; "
                                    + "exec sleep 60")
                            .timeout(spec.timeout()).maxCaptureBytes(spec.maxCaptureBytes()).build()
                    : spec);
            final java.nio.file.Path pidFile = slow.host(session, "/workspace/rg.pid");
            // Cancelled once rg is surely running.
            final ContentQuery query = ContentQuery.builder().pattern("x").path(".")
                    .cancellation(() -> java.nio.file.Files.exists(pidFile)).build();

            final long started = System.nanoTime();
            assertThatThrownBy(() -> slowEnv.contentSearch().orElseThrow().search(query))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelled");

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            final long pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("rg was killed").isFalse();
        }
    }

    @Test
    void multilineQueriesAreLeftToTheFilesystemWalk() {
        assertThatThrownBy(() -> search().search(ContentQuery.builder().pattern("a").path(".").multiline(true).build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void grepFindsTheMatchEitherWay() {
        search();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, env)
                .put(ReadTool.FILE_STAMPS_KEY, new ConcurrentHashMap<String, FileStamp>()).build();

        final ToolResult result = new GrepTool()
                .execute(ToolInput.of(Map.of("pattern", "needle", "output_mode", "files_with_matches")), context);

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(result.getContent()).contains("src/A.java").doesNotContain("b.txt");
        assertThat(List.of(result.getContent())).isNotEmpty();
    }
}
