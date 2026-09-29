package at.aimon.sandbox.environment;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.sandbox.FailingFiles;
import at.aimon.sandbox.RecordingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.workspace.WorkspaceScan;

/** Staging into the sandbox (§7, §11.1): verification in the sandbox, §16 row 18, concurrency, core parity. */
class SandboxStagingIT {

    @TempDir
    Path source;

    private final SandboxHarness harness = SandboxHarness.standard();
    private SessionId session;
    private ExecutionEnvironment env;
    private StagedResource skill;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(source.resolve("demo/lib"));
        Files.writeString(source.resolve("demo/run.sh"), "echo genuine\n");
        Files.writeString(source.resolve("demo/lib/data.txt"), "data\n");
        skill = StagedResource.scan(VirtualFileSystems.readOnlyLocal(source), "demo", "demo");
        session = SessionId.generate();
        env = harness.mainTurn(session, ALICE);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    private String target() {
        return "/workspace/.aimon-staged/demo/" + skill.getContentKey();
    }

    private List<String> stagedFiles() throws Exception {
        final Path root = harness.host(session, target());
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).sorted().toList();
        }
    }

    private List<String> leftovers() throws Exception {
        final Path parent = harness.host(session, "/workspace/.aimon-staged/demo");
        if (!Files.exists(parent)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(parent)) {
            return list.map(p -> p.getFileName().toString()).filter(name -> name.startsWith(".tmp-")).toList();
        }
    }

    @Test
    void stagesACompleteCopyAndSkipsAVerifiedOne() throws Exception {
        final String path = env.stage(skill);
        harness.faults.resetCounts();

        final String again = env.stage(skill);

        assertThat(harness.faults.calls(Operation.FILES_WRITE)).isZero();
        assertThat(harness.faults.calls(Operation.RUN)).as("one verification exec").isEqualTo(1);
        assertThat(path).isEqualTo(target()).isEqualTo(again);
        assertThat(stagedFiles()).containsExactly(".staged", "lib/data.txt", "run.sh");
        assertThat(bash(env, "bash " + path + "/run.sh").stdout()).isEqualTo("genuine\n");
    }

    @Test
    @DisplayName("§16: a script and marker planted in the staged directory fail the check and are recopied")
    void plantedContentFailsKeyCheckAndIsRecopied() throws Exception {
        env.stage(skill);
        bash(env, "echo planted > " + target() + "/run.sh; echo extra > " + target() + "/extra.sh");

        final String path = env.stage(skill);

        assertThat(stagedFiles()).containsExactly(".staged", "lib/data.txt", "run.sh");
        assertThat(bash(env, "bash " + path + "/run.sh").stdout()).isEqualTo("genuine\n");
    }

    @Test
    void aPredictedKeyWithAPlantedMarkerIsNotTrusted() throws Exception {
        bash(env,
                "mkdir -p " + target() + " && echo planted > " + target() + "/run.sh && mkdir -p " + target()
                        + "/lib && echo data > " + target() + "/lib/data.txt && echo " + skill.getContentKey() + " > "
                        + target() + "/.staged");

        env.stage(skill);

        assertThat(bash(env, "bash " + target() + "/run.sh").stdout()).isEqualTo("genuine\n");
    }

    @Test
    @DisplayName("§16 rows 1, 18: two executions of one slot stage one skill at once; both get the complete copy")
    void concurrentStageOfSameResourceReturnsCompleteCopyToBoth() throws Exception {
        final ExecutionEnvironment fork = harness.fork(env, ALICE);
        bash(fork, "true");
        harness.faults.inject(Operation.FILES_WRITE, Fault.delay(Duration.ofMillis(200)));
        harness.faults.resetCounts();
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final List<Future<String>> results = new ArrayList<>();
            for (ExecutionEnvironment environment : List.of(env, fork)) {
                results.add(executor.submit(() -> {
                    go.await();
                    return environment.stage(skill);
                }));
            }
            go.countDown();

            assertThat(results.get(0).get(30, TimeUnit.SECONDS)).isEqualTo(target());
            assertThat(results.get(1).get(30, TimeUnit.SECONDS)).isEqualTo(target());
        } finally {
            executor.shutdownNow();
        }
        assertThat(stagedFiles()).containsExactly(".staged", "lib/data.txt", "run.sh");
        assertThat(harness.hostFile(session, target() + "/lib/data.txt")).isEqualTo("data\n");
        assertThat(harness.faults.calls(Operation.FILES_WRITE)).isEqualTo(skill.getFiles().size() + 1);
        assertThat(leftovers()).isEmpty();

        harness.faults.clear();
        assertThat(env.stage(skill)).isEqualTo(target());
        assertThat(stagedFiles()).containsExactly(".staged", "lib/data.txt", "run.sh");
    }

    @Test
    void aSkillChangedOnDiskAfterLoadingIsRefusedWithCoresWording() throws Exception {
        Files.writeString(source.resolve("demo/run.sh"), "echo changed\n");

        assertThatThrownBy(() -> env.stage(skill)).isInstanceOf(StagingException.class)
                .hasMessageContaining("changed on disk after it was loaded");
        assertThat(Files.exists(harness.host(session, target()))).isFalse();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void shapeViolationsFailBeforeTheSandboxIsTouched() {
        final List<StagedResource> invalid = List.of(
                StagedResource.builder().sourceFileSystem(skill.getSourceFileSystem()).sourceDir("demo").name("a/b")
                        .contentKey(skill.getContentKey()).files(List.of()).build(),
                StagedResource.builder().sourceFileSystem(skill.getSourceFileSystem()).sourceDir("demo").name("demo")
                        .contentKey("../../etc").files(List.of()).build(),
                StagedResource.builder().sourceFileSystem(skill.getSourceFileSystem()).sourceDir("demo").name("demo")
                        .contentKey(skill.getContentKey()).files(List.of("../escape")).build(),
                StagedResource.builder().sourceFileSystem(skill.getSourceFileSystem()).sourceDir("demo").name("demo")
                        .contentKey(skill.getContentKey()).totalBytes(60L * 1024 * 1024).files(List.of()).build());
        final ExecutionEnvironment fresh = harness.mainTurn(SessionId.generate(), ALICE);

        for (StagedResource resource : invalid) {
            assertThatThrownBy(() -> fresh.stage(resource)).isInstanceOf(StagingException.class);
        }
        assertThat(harness.store.scan(WorkspaceScan.builder().build())).isEmpty();
    }

    @Test
    void theSizeLimitMessagePointsAtStageIgnore() {
        final StagedResource large = StagedResource.builder().sourceFileSystem(skill.getSourceFileSystem())
                .sourceDir("demo").name("demo").contentKey(skill.getContentKey()).totalBytes(60L * 1024 * 1024)
                .files(List.of()).build();

        assertThatThrownBy(() -> env.stage(large)).hasMessageContaining(".stageignore");
    }

    @Test
    void aCrashedCopysLeftoverIsRemovedByTheNextStage() throws Exception {
        bash(env, "mkdir -p /workspace/.aimon-staged/demo/.tmp-" + skill.getContentKey() + "-dead/lib");

        env.stage(skill);

        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aResourceInsideThisEnvironmentIsReturnedUncopied() throws Exception {
        env.fileSystem().write("tools/helper.sh", "echo helper");
        final StagedResource inWorkspace = StagedResource.scan(env.fileSystem(), "tools", "tools");

        assertThat(env.stage(inWorkspace)).isEqualTo("/workspace/repo/tools");
        assertThat(Files.exists(harness.host(session, "/workspace/.aimon-staged/tools"))).isFalse();
    }

    @Test
    void anUnreadableSourceFileFailsWithoutATarget() throws Exception {
        Files.delete(source.resolve("demo/lib/data.txt"));

        assertThatThrownBy(() -> env.stage(skill)).isInstanceOf(StagingException.class)
                .hasMessageContaining("could not be read");
        assertThat(Files.exists(harness.host(session, target()))).isFalse();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aSkillWithAPathListTooLargeForOneArgumentIsVerifiedThroughAListFile() throws Exception {
        final Path many = Files.createDirectories(source.resolve("many"));
        for (int i = 0; i < 700; i++) {
            Files.writeString(many.resolve(String.format("file-with-a-rather-long-descriptive-name-%04d.txt", i)),
                    "content " + i + "\n");
        }
        final StagedResource large = StagedResource.scan(VirtualFileSystems.readOnlyLocal(source), "many", "many");
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness recorded = SandboxHarness.builder()
                .decorate((provider, clock) -> recording[0] = new RecordingProvider(provider)).build()) {
            final SessionId other = SessionId.generate();
            final ExecutionEnvironment otherEnv = recorded.mainTurn(other, ALICE);

            final String first = otherEnv.stage(large);
            recorded.faults.resetCounts();
            final String second = otherEnv.stage(large);

            assertThat(second).isEqualTo(first);
            assertThat(recorded.faults.calls(Operation.FILES_MOVE)).as("the verified copy is reused").isZero();
            assertThat(recording[0].runs).allSatisfy(spec -> assertThat(spec.command().length())
                    .as("exec argument length").isLessThan(SandboxStaging.INLINE_PATHS_LIMIT));
            try (Stream<Path> root = Files.list(recorded.host(other, SandboxStaging.STAGING_ROOT))) {
                assertThat(root.map(p -> p.getFileName().toString())).as("the list file is removed")
                        .noneMatch(name -> name.startsWith(".aimon-verify-"));
            }
        }
    }

    @Test
    void aFilesystemErrorWhileCopyingIsAStagingException() {
        final RecordingProvider[] recording = new RecordingProvider[1];
        try (SandboxHarness failing = SandboxHarness.builder().decorate((provider, clock) -> {
            recording[0] = new RecordingProvider(provider);
            recording[0].files(files -> new FailingFiles(files, path -> path.contains("/.tmp-")));
            return recording[0];
        }).build()) {
            final ExecutionEnvironment failingEnv = failing.mainTurn(SessionId.generate(), ALICE);

            // Core's SkillTool reports StagingException; a raw filesystem exception would read as a generic error.
            assertThatThrownBy(() -> failingEnv.stage(skill)).isInstanceOf(StagingException.class)
                    .hasMessageContaining("Cannot stage 'demo'").hasMessageContaining("No space left on device");
        }
    }
}
