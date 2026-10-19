package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.TamperingFiles;

/**
 * {@link ShellFeature#RUN_DIRECTORY} and the stream runs of hooks and isolated commands (kangwoo/aimon-sandbox#33,
 * docs/design/workspace-sandbox.md §9), through the local provider. Numbers in the display names follow the design
 * record's test list (docs/design/workspace-sandbox-run-directory.md §7).
 */
class SandboxShellRunDirectoryIT {

    private static final String PRIVATE = "/workspace/.aimon-shell/.run.";

    private final RecordingProvider[] recording = new RecordingProvider[1];
    private final SandboxHarness harness = SandboxHarness.builder()
            .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private SessionId session;
    private ExecutionEnvironment env;

    @BeforeEach
    void setUp() throws Exception {
        session = SessionId.generate();
        env = harness.mainTurn(session, ALICE);
        bash(env, "true");
        recording[0].runs.clear();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        harness.close();
    }

    private static ExecutionOptions.Builder hook() {
        return ExecutionOptions.builder().timeout(Duration.ofSeconds(20)).hook(true);
    }

    private static ExecutionOptions.Builder isolated() {
        // As core's hooks.json path sets it: an isolated exec is also a hook's command.
        return hook().isolated(true);
    }

    private static RunDirectory files(String variable, Object... pathsAndContents) {
        final RunDirectory.Builder builder = RunDirectory.builder().variable(variable);
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            final Object content = pathsAndContents[i + 1];
            builder.file((String) pathsAndContents[i],
                    content instanceof byte[] bytes ? bytes : ((String) content).getBytes(StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static RunDirectory hookFiles(Object... pathsAndContents) {
        return files("AIMON_HOOK_DIR", pathsAndContents);
    }

    /** The private directories left in the shell root. */
    private List<String> leftovers() throws IOException {
        return entries(harness, session, ".run.");
    }

    /** Run files and uploads left in any shell directory. */
    private List<String> runFiles() throws IOException {
        return entries(harness, session, "run-");
    }

    private boolean uploaded(Predicate<String> path) {
        return recording[0].runs.stream().anyMatch(spec -> path.test(spec.command()));
    }

    @Test
    @DisplayName("1: the sandbox shell declares RUN_DIRECTORY")
    void supportsRunDirectory() {
        assertThat(env.shell().supports(ShellFeature.RUN_DIRECTORY)).isTrue();
    }

    @Test
    @DisplayName("2: inline files are placed under a fresh private directory, exported, and removed after the run")
    void inlineFilesArePlacedExportedAndRemoved() throws Exception {
        final ShellCommandResult result = bash(env,
                "cat \"$AIMON_HOOK_DIR/a\"; cat \"$AIMON_HOOK_DIR/lib/b\"; echo; echo \"$AIMON_HOOK_DIR\"",
                isolated().runDirectory(hookFiles("a", "first\n", "lib/b", "second")).build());

        final String[] lines = result.stdout().split("\n");
        assertThat(result.exitCode()).isZero();
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("first");
        assertThat(lines[1]).isEqualTo("second");
        assertThat(lines[2]).startsWith(PRIVATE).endsWith("/d");
        assertThat(Files.exists(harness.host(session, lines[2]))).isFalse();
        assertThat(leftovers()).isEmpty();
        assertThat(uploaded(script -> script.contains(".files/"))).as("inline: nothing uploaded").isFalse();
    }

    @Test
    @DisplayName("3/8: files past the inline limit are uploaded, copied in and checked; the upload is gone after")
    void largeFilesAreUploadedAndCopied() throws Exception {
        final String large = "x".repeat(40 * 1024);

        final ShellCommandResult result = bash(env, "wc -c < \"$AIMON_HOOK_DIR/big\"; cat \"$AIMON_HOOK_DIR/스크립트.sh\"",
                hook().runDirectory(hookFiles("big", large, "스크립트.sh", "한글")).build());

        assertThat(result.stdout().replace(" ", "")).isEqualTo(large.length() + "\n한글");
        assertThat(uploaded(script -> script.contains("cat -- \"$r.blob\""))).isTrue();
        assertThat(runFiles()).isEmpty();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("8: a non-ASCII file name round-trips inline")
    void aNonAsciiNameRoundTripsInline() throws Exception {
        final ShellCommandResult result = bash(env, "cat \"$AIMON_HOOK_DIR/스크립트.sh\"",
                hook().runDirectory(hookFiles("스크립트.sh", "한글")).build());

        assertThat(result.stdout()).isEqualTo("한글");
    }

    @Test
    @DisplayName("4: an uploaded blob rewritten before the wrapper copies it is an integrity failure; nothing runs")
    void aTamperedUploadIsAnIntegrityFailure() throws Exception {
        try (SandboxHarness tampering = tampering(path -> path.endsWith(".blob"))) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment tampered = tampering.mainTurn(other, ALICE);

            assertThatThrownBy(() -> bash(tampered, "touch /workspace/ran",
                    isolated().runDirectory(hookFiles("a", "y".repeat(40 * 1024))).build()))
                    .isExactlyInstanceOf(RunDirectoryIntegrityException.class).hasMessageNotContaining("yyyy")
                    .hasMessageNotContaining("forged");
            assertThat(Files.exists(tampering.host(other, "/workspace/ran"))).isFalse();
            assertThat(entries(tampering, other, ".run.")).isEmpty();
            assertThat(entries(tampering, other, "run-")).isEmpty();
        }
    }

    /** A harness whose files API rewrites what is written to matching paths, from the first connection on. */
    private static SandboxHarness tampering(Predicate<String> paths) {
        return SandboxHarness.builder().decorate((provider, clock) -> {
            final RecordingProvider tampering = new RecordingProvider(provider);
            tampering.files(files -> new TamperingFiles(files, paths, "forged".getBytes(StandardCharsets.UTF_8)));
            return tampering;
        }).build();
    }

    private static List<String> entries(SandboxHarness in, SessionId of, String prefix) throws IOException {
        try (Stream<Path> entries = Files.walk(in.host(of, ShellWrapper.SHELL_ROOT), 2)) {
            return entries.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(prefix)).toList();
        }
    }

    @Test
    @DisplayName("5: the run directory's variable wins over the same name among the command's variables")
    void theVariableWinsOverThePerCommandEnvironment() throws Exception {
        final ShellCommandResult result = bash(env, "echo \"$AIMON_HOOK_DIR\"",
                hook().environment(Map.of("AIMON_HOOK_DIR", "/planted")).runDirectory(hookFiles("a", "1")).build());

        assertThat(result.stdout()).startsWith(PRIVATE).doesNotContain("/planted");
    }

    @Test
    @DisplayName("6: the private directory is removed after a failure, a timeout (JVM side) and a cancel")
    void thePrivateDirectoryIsRemovedOnEveryOutcome() throws Exception {
        assertThat(bash(env, "exit 3", hook().runDirectory(hookFiles("a", "1")).build()).exitCode()).isEqualTo(3);
        assertThat(leftovers()).isEmpty();

        // The watchdog kills the outer before its EXIT trap: only the JVM's cleanUp removes the directory.
        assertThatThrownBy(() -> bash(env, "echo partial; sleep 30",
                isolated().timeout(Duration.ofSeconds(1)).runDirectory(hookFiles("a", "1")).build()))
                .isInstanceOfSatisfying(ShellTimeoutException.class,
                        e -> assertThat(e.stdout()).isEqualTo("partial\n"));
        assertThat(leftovers()).isEmpty();

        final ShellCancellationSource source = ShellCancellationSource.create();
        final Future<ShellCommandResult> cancelled = executor.submit(() -> bash(env, ": > /workspace/started; sleep 30",
                hook().cancellation(source.token()).runDirectory(hookFiles("a", "1")).build()));
        final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(harness.host(session, "/workspace/started")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        source.cancel();
        assertThatThrownBy(() -> cancelled.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(ShellCancelledException.class);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("7: case-distinct files are placed apart, or refused as one file where the filesystem merges them")
    void caseDistinctFiles() throws Exception {
        bash(env, "rm -f /workspace/case-probe /workspace/CASE-PROBE; : > /workspace/case-probe");
        final boolean insensitive = Files.exists(harness.host(session, "/workspace/CASE-PROBE"));
        final ExecutionOptions options = hook().runDirectory(hookFiles("Run.sh", "upper", "run.sh", "lower")).build();

        if (insensitive) {
            // Contract rule 2: an error, the command not run — and not reported as tampering.
            assertThatThrownBy(() -> bash(env, "touch /workspace/ran", options))
                    .isExactlyInstanceOf(ShellExecutionException.class).hasMessageContaining("cannot place");
            assertThat(Files.exists(harness.host(session, "/workspace/ran"))).isFalse();
        } else {
            assertThat(bash(env, "cat \"$AIMON_HOOK_DIR/Run.sh\" \"$AIMON_HOOK_DIR/run.sh\"", options).stdout())
                    .isEqualTo("upperlower");
        }
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("22: a file and a directory of the same name cannot be built; core's builder refuses them (core #283)")
    void aFileAndADirectoryOfTheSameNameAreRefused() {
        assertThatThrownBy(() -> hookFiles("lib", "file", "lib/x", "child"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("9: exactly the inline limit goes inline, one byte more is uploaded")
    void theInlineUploadBoundary() throws Exception {
        // One file "a": quoted base64 (4·⌈n/3⌉ + 2) plus the quoted manifest line (64 + 2 + 1 + 1, + 2) = 16 KiB.
        final int atLimit = 3 * ((ShellWrapper.INLINE_RUN_FILES_LIMIT - 2 - 70) / 4);

        assertThat(bash(env, "wc -c < \"$AIMON_HOOK_DIR/a\"",
                hook().runDirectory(hookFiles("a", "z".repeat(atLimit))).build()).stdout().strip())
                .isEqualTo(String.valueOf(atLimit));
        assertThat(uploaded(script -> script.contains("\"$r.blob\""))).as("at the limit: inline").isFalse();

        assertThat(bash(env, "wc -c < \"$AIMON_HOOK_DIR/a\"",
                hook().runDirectory(hookFiles("a", "z".repeat(atLimit + 1))).build()).stdout().strip())
                .isEqualTo(String.valueOf(atLimit + 1));
        assertThat(uploaded(script -> script.contains("\"$r.blob\""))).as("one byte over: uploaded").isTrue();
    }

    @Test
    @DisplayName("10/18: a newline in a path or a hook command over 64 KiB is refused before anything is provisioned")
    void badArgumentsAreRefusedBeforeConnecting() {
        final SessionId fresh = SessionId.generate();
        final ExecutionEnvironment unprovisioned = harness.mainTurn(fresh, ALICE);

        // A newline or carriage return in a path never reaches the shell: core's builder refuses it (core #283).
        assertThatThrownBy(() -> hookFiles("a\nb", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hookFiles("a\rb", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> bash(unprovisioned, "echo " + "x".repeat(ShellWrapper.INLINE_COMMAND_LIMIT), isolated().build()))
                .isExactlyInstanceOf(ShellExecutionException.class)
                .hasMessageContaining("a hook command over 64 KiB is not supported");
        assertThat(recording[0].runs).isEmpty();
        assertThatThrownBy(() -> harness.record(fresh)).as("no workspace was created")
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("many small files, whose manifest alone exceeds the argument budget, travel as two uploads")
    void manyFilesTravelAsTwoUploads() throws Exception {
        // About 1 500 names of 60 bytes: the manifest is ~190 KiB, far past the script's budget, the files ~1.5 KiB.
        final RunDirectory.Builder many = RunDirectory.builder().variable("AIMON_HOOK_DIR");
        for (int i = 0; i < 1500; i++) {
            many.file(String.format("scripts/%04d-%s.sh", i, "n".repeat(40)), new byte[]{(byte) ('a' + i % 26)});
        }
        final List<String> writes = new CopyOnWriteArrayList<>();
        // Rewrites nothing: it only records the paths the shell writes through the files API.
        try (SandboxHarness counting = tampering(path -> writes.add(path) && false)) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment counted = counting.mainTurn(other, ALICE);
            bash(counted, "true");
            writes.clear();

            final ShellCommandResult result = bash(counted,
                    "find \"$AIMON_HOOK_DIR\" -type f | wc -l | tr -d ' '; cat \"$AIMON_HOOK_DIR\"/scripts/1499-*.sh",
                    hook().stdin("s".repeat(200 * 1024)).runDirectory(many.build()).build());

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).isEqualTo("1500\n" + (char) ('a' + 1499 % 26));
            assertThat(writes).as("the manifest, the blob and the stdin: not one upload per file").hasSize(3)
                    .anyMatch(path -> path.endsWith(".manifest")).anyMatch(path -> path.endsWith(".blob"))
                    .anyMatch(path -> path.endsWith(".in"));
            assertThat(entries(counting, other, ".run.")).isEmpty();
            assertThat(entries(counting, other, "run-")).isEmpty();
        }
    }

    @Test
    @DisplayName("4: an uploaded manifest rewritten before the wrapper copies it is an integrity failure")
    void aTamperedManifestIsAnIntegrityFailure() throws Exception {
        try (SandboxHarness tampering = tampering(path -> path.endsWith(".manifest"))) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment tampered = tampering.mainTurn(other, ALICE);

            assertThatThrownBy(() -> bash(tampered, "touch /workspace/ran",
                    isolated().runDirectory(hookFiles("a", "y".repeat(40 * 1024))).build()))
                    .isExactlyInstanceOf(RunDirectoryIntegrityException.class);
            assertThat(Files.exists(tampering.host(other, "/workspace/ran"))).isFalse();
            assertThat(entries(tampering, other, ".run.")).isEmpty();
            assertThat(entries(tampering, other, "run-")).isEmpty();
        }
    }

    @Test
    @DisplayName("21: an empty run directory is created, exported and removed")
    void anEmptyRunDirectory() throws Exception {
        final ShellCommandResult result = bash(env,
                "[ -d \"$AIMON_HOOK_DIR\" ] && ls -A \"$AIMON_HOOK_DIR\" | wc -l | tr -d ' '; echo \"$AIMON_HOOK_DIR\"",
                hook().runDirectory(RunDirectory.builder().variable("AIMON_HOOK_DIR").build()).build());

        assertThat(result.stdout()).startsWith("0\n" + PRIVATE);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("Q-G: a model's own command can carry a run directory too, and keeps its run files")
    void aForegroundCommandWithARunDirectory() throws Exception {
        final ShellCommandResult result = bash(env, "cat \"$SKILL/s.sh\"; ls /workspace/.aimon-shell/*/ | grep -c run-",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(20))
                        .runDirectory(files("SKILL", "s.sh", "echo hi\n")).build());

        assertThat(result.stdout()).as("its run files are there while it runs").matches("echo hi\n[1-9]\\d*\n");
        assertThat(leftovers()).isEmpty();
        assertThat(runFiles()).isEmpty();
    }

    @Test
    @DisplayName("11: isolated and hook runs leave no run-* under the shell directory while they run")
    void streamRunsHaveNoRunFiles() throws Exception {
        final String probe = "ls -A /workspace/.aimon-shell/*/ 2>/dev/null | grep run- || true";

        assertThat(bash(env, probe, isolated().runDirectory(hookFiles("a", "1")).build()).stdout()).isEmpty();
        assertThat(bash(env, probe, hook().stdin("{\"x\":1}").build()).stdout()).isEmpty();
        assertThat(bash(env, probe).stdout()).as("the probe sees a foreground run's own files").contains("run-");
    }

    @Test
    @DisplayName("12: a background writer forging run-*.out cannot change an isolated command's output or exit code")
    void aForgingWriterCannotChangeAnIsolatedResult() throws Exception {
        // A model-facing background command: its call holds the thread until it ends, as core's manager runs it.
        executor.submit(() -> bash(env,
                ": > /workspace/forging; while :; do for f in /workspace/.aimon-shell/*/run-*.out;"
                        + " do echo forged > \"$f\" 2>/dev/null; done; done",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).background(true).build()));
        final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(harness.host(session, "/workspace/forging")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        for (int i = 0; i < 5; i++) {
            final ShellCommandResult result = bash(env, "echo genuine; exit 2", isolated().build());
            assertThat(result.stdout()).isEqualTo("genuine\n");
            assertThat(result.exitCode()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("13: output over the cap is truncated in the JVM; under it, exact — with and without merged streams")
    void outputIsCappedInTheJvm() throws Exception {
        final ShellCommandResult over = bash(env, "printf '%0200d' 0; printf '%0300d' 0 >&2",
                isolated().maxCaptureBytes(100).build());
        assertThat(over.stdout()).hasSize(100);
        assertThat(over.stderr()).hasSize(100);
        assertThat(over.outputTruncated()).isTrue();

        final ShellCommandResult under = bash(env, "printf out; printf err >&2", isolated().build());
        assertThat(under.stdout()).isEqualTo("out");
        assertThat(under.stderr()).isEqualTo("err");
        assertThat(under.outputTruncated()).isFalse();

        final ShellCommandResult merged = bash(env, "printf out; printf err >&2",
                isolated().redirectErrorStream(true).build());
        assertThat(merged.stdout()).isEqualTo("outerr");
        assertThat(merged.stderr()).isEmpty();
    }

    @Test
    @DisplayName("13a: stderr past the provider's capture cap still yields the hook's own result")
    void stderrPastTheBackstopKeepsTheTrailer() throws Exception {
        final long max = 4096;
        final long flood = SandboxShell.captureBackstop(max) + 64 * 1024;

        final ShellCommandResult blocked = bash(env, "head -c " + flood + " /dev/zero | tr '\\0' e >&2; exit 2",
                hook().maxCaptureBytes(max).build());
        assertThat(blocked.exitCode()).isEqualTo(2);
        assertThat(blocked.outputTruncated()).isTrue();
        assertThat(blocked.stderr()).hasSize((int) max);

        final ShellCommandResult loud = bash(env, "head -c " + flood + " /dev/zero | tr '\\0' o; exit 2",
                hook().maxCaptureBytes(max).build());
        assertThat(loud.exitCode()).isEqualTo(2);
        assertThat(loud.stdout()).hasSize((int) max);
        assertThat(loud.outputTruncated()).isTrue();
    }

    @Test
    @DisplayName("13b: a removed shell root is re-created by a hook run, and a guard is not locked out")
    void aRemovedShellRootDoesNotBreakHookRuns() throws Exception {
        // The removing command loses its own output, and says so (§16); what matters is the runs after it.
        assertThatThrownBy(() -> bash(env, "rm -rf /workspace/.aimon-shell"))
                .hasMessageContaining("so its output is lost");

        assertThat(bash(env, "echo iso", isolated().build()).stdout()).isEqualTo("iso\n");
        assertThat(bash(env, "cat \"$AIMON_HOOK_DIR/a\"", hook().runDirectory(hookFiles("a", "hook")).build()).stdout())
                .isEqualTo("hook");
        assertThat(bash(env, "echo fg").stdout()).isEqualTo("fg\n");
    }

    @Test
    @DisplayName("14: a hook's background descendant is killed when the hook ends, and does not hold the call")
    void aHooksBackgroundDescendantIsKilledOnTheTrailer() throws Exception {
        final long started = System.nanoTime();

        final ShellCommandResult result = bash(env, "(sleep 2; touch /workspace/late) & echo done", hook().build());

        assertThat(result.stdout()).isEqualTo("done\n");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1900));
        Thread.sleep(3000);
        assertThat(Files.exists(harness.host(session, "/workspace/late"))).isFalse();
    }

    @Test
    @DisplayName("16/17: a large stdin is uploaded and checked, a NUL and a non-UTF-8 charset arrive as bytes")
    void stdinArrivesAsItsBytes() throws Exception {
        final String large = "s".repeat(1024 * 1024);
        assertThat(bash(env, "wc -c", hook().stdin(large).build()).stdout().strip())
                .isEqualTo(String.valueOf(large.length()));
        assertThat(uploaded(script -> script.contains("<\"$p/.stdin\""))).isTrue();

        final String nul = "a\0b";
        assertThat(bash(env, "tr '\\0' N", hook().stdin(nul).build()).stdout()).isEqualTo("aNb");
        assertThat(bash(env, "tr '\\0' N", hook().stdin(nul + "x".repeat(200 * 1024)).build()).stdout())
                .startsWith("aNbx");
        assertThat(
                bash(env, "od -An -tx1 | tr -d ' \\n'", hook().stdin("é").charset(StandardCharsets.ISO_8859_1).build())
                        .stdout())
                .isEqualTo("e9");
    }

    @Test
    @DisplayName("16: a large stdin rewritten before the wrapper copies it is an integrity failure")
    void aTamperedStdinIsAnIntegrityFailure() throws Exception {
        try (SandboxHarness tampering = tampering(path -> path.endsWith(".in"))) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment tampered = tampering.mainTurn(other, ALICE);

            assertThatThrownBy(() -> bash(tampered, "cat > /dev/null; touch /workspace/ran",
                    hook().stdin("p".repeat(1024 * 1024)).build()))
                    .isExactlyInstanceOf(RunDirectoryIntegrityException.class);
            assertThat(Files.exists(tampering.host(other, "/workspace/ran"))).isFalse();
            assertThat(entries(tampering, other, ".run.")).isEmpty();
            assertThat(entries(tampering, other, "run-")).isEmpty();
        }
    }

    @Test
    @DisplayName("19: an isolated command whose root is gone runs in /workspace and says so, without a .cwd run file")
    void anIsolatedCommandReportsTheCwdFallbackThroughTheTrailer() throws Exception {
        bash(env, "rm -rf /workspace/repo");

        final ShellCommandResult result = bash(env, "pwd", isolated().build());

        assertThat(result.stdout()).isEqualTo("/workspace\n");
        assertThat(result.notices()).singleElement().asString().contains("no longer exist")
                .contains("the command ran in /workspace");
        assertThat(runFiles()).isEmpty();
    }
}
