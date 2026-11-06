package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellLineListener;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.sandbox.FailingFiles;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.SandboxFiles;

/**
 * S4 (docs/design/ptc-shell-provider.md §3.3): a run directory with a file marked secret is uploaded whole, however
 * small, so the secret's bytes are in no exec argument — the script is the exec's argument, readable in
 * {@code /proc/<pid>/cmdline}. Through the local provider, recording what the exec server would receive.
 */
class SandboxShellSecretRunFileIT {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RecordingProvider[] recording = new RecordingProvider[1];
    private final List<String> writes = new CopyOnWriteArrayList<>();
    private final SandboxHarness harness = SandboxHarness.builder().decorate((provider, clock) -> {
        recording[0] = new RecordingProvider(provider);
        recording[0].files(files -> recordingWrites(files, writes));
        return recording[0];
    }).build();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /** A files API that records the path of every write. */
    private static SandboxFiles recordingWrites(SandboxFiles files, List<String> writes) {
        return (SandboxFiles) Proxy.newProxyInstance(SandboxFiles.class.getClassLoader(),
                new Class<?>[]{SandboxFiles.class}, (proxy, method, args) -> {
                    if (method.getName().equals("write")) {
                        writes.add((String) args[0]);
                    }
                    try {
                        return method.invoke(files, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** The engine's call shape: isolated, with a run directory, streamed. */
    private static ExecutionOptions engineShaped(RunDirectory run) {
        return ExecutionOptions.builder().isolated(true).runDirectory(run).timeout(Duration.ofSeconds(20)).build();
    }

    private static byte[] key() {
        final byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    private List<String> scripts() {
        return recording[0].runs.stream().map(ExecSpec::command).toList();
    }

    @Test
    @DisplayName("a 32-byte secret and a small file are uploaded as manifest and blob; no script holds the secret")
    void aSmallSecretIsUploaded() throws Exception {
        final ExecutionEnvironment env = harness.mainTurn(SessionId.generate(), ALICE);
        bash(env, "true");
        recording[0].runs.clear();
        writes.clear();
        final byte[] key = key();
        final RunDirectory run = RunDirectory.builder().variable("AIMON_RUN_DIR").secretFile("key", key)
                .file("config.json", "{}".getBytes(StandardCharsets.UTF_8)).build();

        final ShellCommandResult result = env.shell().execute(
                () -> "wc -c < \"$AIMON_RUN_DIR/key\"; od -An -tx1 \"$AIMON_RUN_DIR/key\" | tr -d ' \\n'; echo;"
                        + " cat \"$AIMON_RUN_DIR/config.json\"",
                engineShaped(run), new CollectingListener());

        final String hex = HexFormat.of().formatHex(key);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().replace(" ", "")).isEqualTo("32\n" + hex + "\n{}");
        assertThat(writes).anyMatch(path -> path.endsWith(".manifest")).anyMatch(path -> path.endsWith(".blob"));
        assertThat(scripts()).isNotEmpty().allSatisfy(script -> assertThat(script)
                .doesNotContain(Base64.getEncoder().encodeToString(key)).doesNotContain(hex));
    }

    @Test
    @DisplayName("control: the same files without the secret mark go inline, and the script carries them")
    void withoutTheMarkTheFilesGoInline() throws Exception {
        final ExecutionEnvironment env = harness.mainTurn(SessionId.generate(), ALICE);
        bash(env, "true");
        recording[0].runs.clear();
        writes.clear();
        final byte[] key = key();
        final RunDirectory run = RunDirectory.builder().variable("AIMON_RUN_DIR").file("key", key)
                .file("config.json", "{}".getBytes(StandardCharsets.UTF_8)).build();

        final ShellCommandResult result = env.shell().execute(() -> "wc -c < \"$AIMON_RUN_DIR/key\"", engineShaped(run),
                new CollectingListener());

        assertThat(result.stdout().strip()).isEqualTo("32");
        assertThat(writes).noneMatch(path -> path.endsWith(".manifest") || path.endsWith(".blob"));
        assertThat(scripts()).anyMatch(script -> script.contains(Base64.getEncoder().encodeToString(key)));
    }

    @Test
    @DisplayName("an upload that fails for a small secret set is a ShellExecutionException; nothing runs")
    void aFailedUploadOfASecretFails() throws Exception {
        try (SandboxHarness failing = SandboxHarness.builder().decorate((provider, clock) -> {
            final RecordingProvider wrapped = new RecordingProvider(provider);
            final Predicate<String> blob = path -> path.endsWith(".blob");
            wrapped.files(files -> new FailingFiles(files, blob));
            return wrapped;
        }).build()) {
            final SessionId session = SessionId.generate();
            final ExecutionEnvironment env = failing.mainTurn(session, ALICE);
            final RunDirectory run = RunDirectory.builder().variable("AIMON_RUN_DIR").secretFile("key", key()).build();

            assertThatThrownBy(() -> env.shell().execute(() -> "touch /workspace/ran", engineShaped(run),
                    new CollectingListener())).isExactlyInstanceOf(ShellExecutionException.class)
                    .hasMessageContaining("could not pass");
            assertThat(failing.host(session, "/workspace/ran")).doesNotExist();
        }
    }

    /** A listener that keeps nothing: the engine always streams. */
    private static final class CollectingListener implements ShellLineListener {
        @Override
        public void onStdoutLine(String line) {
            // the result keeps stdout as well
        }

        @Override
        public void onStderrLine(String line) {
            // nothing
        }
    }
}
