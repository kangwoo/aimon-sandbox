package at.aimon.sandbox.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release must not pass a quality gate narrower than the one every pull request already clears.
 *
 * <p>
 * A trimmed port of aimon-core's test of the same name. CI ({@code .github/workflows/build.yml}) and
 * {@code scripts/release.sh} are two hand-maintained statements of one list of Gradle tasks, and in core they drifted
 * twice before a check read both. A publish to Maven Central is permanent, so the release path is the one that must be
 * at least as strict.
 *
 * <h2>What is enforced</h2>
 *
 * <ul>
 * <li>the release gate invokes {@code checkAll}, the aggregate CI's {@code build} job runs;
 * <li>every Gradle task a CI {@code run: ./gradlew …} step names is also in the gate, except the reporting-only tasks
 * in {@link #REPORTING_ONLY_CI_TASKS}. Adding a verification step to CI without touching the script fails here;
 * <li>the gate carries no {@code -x} exclusion;
 * <li>{@code k8sTest} is in neither list. It needs a provisioned cluster and skips without one, so in either place
 * it would go green having tested nothing; the script asks for {@code --k8s-verified} instead;
 * <li>the script refuses to start while a docker-tier override ({@code OPENSANDBOX_TEST_ENDPOINT},
 * {@code OPENSANDBOX_TEST_SANDBOX_IMAGE}) is in its environment, names it, and never prints its value. That is
 * behaviour, so it runs the script — from an empty directory, with a {@code PATH} that reaches no {@code git},
 * {@code docker} or {@code ./gradlew}, so a run nothing refuses is inert.
 * </ul>
 *
 * <h2>What this cannot see</h2>
 *
 * <p>
 * Task names, not task graphs: if {@code checkAll} stopped depending on {@code checkStyle}, both files would still
 * agree. And it compares two lists, so a tier absent from both is invisible to it — which is exactly why
 * {@code k8sTest} is asserted absent by name rather than left to the comparison. Not ported from core: its census of
 * test tags and its check of a {@code /release} skill, which this repository does not have.
 */
@DisplayName("release gate matches CI gate")
class ReleaseGateMatchesCiGateTest {

    private static final String RELEASE_SCRIPT = "scripts/release.sh";

    private static final String CI_WORKFLOW = ".github/workflows/build.yml";

    private static final String AGGREGATE_TASK = "checkAll";

    private static final String GATE_SECTION_MARKER = "4. quality gate";

    /** Produce reports only and cannot fail a build, so the gate need not run them. */
    private static final List<String> REPORTING_ONLY_CI_TASKS = List.of("jacocoTestReport");

    /** Manual by design — see the class javadoc. */
    private static final String MANUAL_TASK = "k8sTest";

    private static final List<String> OVERRIDE_VARIABLES = List.of("OPENSANDBOX_TEST_ENDPOINT",
            "OPENSANDBOX_TEST_SANDBOX_IMAGE");

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    @DisplayName("the release gate runs the same aggregate CI runs")
    void releaseGateRunsTheAggregate() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory");

        assertThat(gradleTasksIn(releaseGateInvocation())).contains(AGGREGATE_TASK);
    }

    @Test
    @DisplayName("every verification task CI runs is also in the release gate")
    void releaseGateCoversEveryCiVerificationTask() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory");

        final List<String> gateTasks = gradleTasksIn(releaseGateInvocation());
        final List<String> ciTasks = ciGradleTasks();

        assertThat(ciTasks)
                .withFailMessage("found no `./gradlew` steps in %s — the scan is broken, not clean", CI_WORKFLOW)
                .isNotEmpty();
        for (final String ciTask : ciTasks) {
            if (REPORTING_ONLY_CI_TASKS.contains(ciTask)) {
                continue;
            }
            assertThat(gateTasks).withFailMessage(
                    "CI runs `%s` (%s) but the release gate in %s does not: %s.%n"
                            + "Either add it to the gate, or — if it only produces reports and cannot fail a build — "
                            + "add it to REPORTING_ONLY_CI_TASKS in this test with a note saying why.",
                    ciTask, CI_WORKFLOW, RELEASE_SCRIPT, gateTasks).contains(ciTask);
        }
    }

    @Test
    @DisplayName("the release gate excludes no module")
    void releaseGateExcludesNoModule() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory");

        assertThat(releaseGateInvocation().split("\\s+")).doesNotContain("-x");
    }

    @Test
    @DisplayName("k8sTest is in neither the release gate nor CI")
    void k8sTierStaysManual() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory");

        assertThat(gradleTasksIn(releaseGateInvocation())).noneMatch(task -> task.endsWith(MANUAL_TASK));
        assertThat(ciGradleTasks()).noneMatch(task -> task.endsWith(MANUAL_TASK));
    }

    @Test
    @DisplayName("the release script refuses a docker-tier override and never prints its value")
    void releaseScriptRefusesDockerTierOverrides(@TempDir Path tempDir) throws IOException, InterruptedException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory");
        final Path bash = Path.of("/bin/bash");
        assumeTrue(Files.isExecutable(bash), "no /bin/bash to run the script with");

        for (final String variable : OVERRIDE_VARIABLES) {
            final String value = "value-that-must-not-be-printed-" + variable.length();
            final ProcessBuilder builder = new ProcessBuilder(bash.toString(),
                    REPOSITORY_ROOT.resolve(RELEASE_SCRIPT).toString(), "--dry-run").directory(tempDir.toFile())
                    .redirectErrorStream(true);
            final Map<String, String> environment = builder.environment();
            environment.clear();
            // An empty directory: nothing the script could reach to start a release.
            environment.put("PATH", tempDir.toString());
            environment.put(variable, value);

            final Process process = builder.start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();

            assertThat(process.exitValue())
                    .withFailMessage("%s set: expected exit 1, got %d:%n%s", variable, process.exitValue(), output)
                    .isEqualTo(1);
            assertThat(output).contains("Refusing to start").contains(variable).doesNotContain(value)
                    .doesNotContain("Pre-flight checks");
        }
    }

    private static String releaseGateInvocation() throws IOException {
        final Path script = REPOSITORY_ROOT.resolve(RELEASE_SCRIPT);
        assertThat(script).withFailMessage("%s not found — this test is pointed at the wrong path", RELEASE_SCRIPT)
                .isRegularFile();

        boolean inGateSection = false;
        for (final String rawLine : Files.readAllLines(script)) {
            final String line = rawLine.strip();
            if (line.startsWith("#") && line.contains(GATE_SECTION_MARKER)) {
                inGateSection = true;
                continue;
            }
            if (inGateSection && line.startsWith("$GRADLE ")) {
                return line;
            }
        }
        throw new AssertionError("no `$GRADLE` invocation found after the '" + GATE_SECTION_MARKER + "' section in "
                + RELEASE_SCRIPT + " — the marker or the gate moved, so this test can no longer see the gate");
    }

    /** Task names from every {@code run: ./gradlew …} step in the CI workflow. */
    private static List<String> ciGradleTasks() throws IOException {
        final Path workflow = REPOSITORY_ROOT.resolve(CI_WORKFLOW);
        assertThat(workflow).withFailMessage("%s not found — this test is pointed at the wrong path", CI_WORKFLOW)
                .isRegularFile();

        final List<String> tasks = new ArrayList<>();
        for (final String rawLine : Files.readAllLines(workflow)) {
            final String line = rawLine.strip();
            if (line.startsWith("run:") && line.contains("./gradlew")) {
                tasks.addAll(gradleTasksIn(line));
            }
        }
        return tasks;
    }

    /** Tokens after the launcher that are not flags; {@code -x} and its argument are dropped. */
    private static List<String> gradleTasksIn(String invocation) {
        final List<String> tasks = new ArrayList<>();
        boolean afterLauncher = false;
        boolean skipNext = false;
        for (final String token : invocation.split("\\s+")) {
            if (!afterLauncher) {
                afterLauncher = token.endsWith("gradlew") || "$GRADLE".equals(token);
                continue;
            }
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if ("-x".equals(token)) {
                skipNext = true;
                continue;
            }
            if (!token.startsWith("-")) {
                tasks.add(token);
            }
        }
        return tasks;
    }

    /** Walks up from the working directory (the module directory under Gradle) to the repository root. */
    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
                    && Files.isDirectory(candidate.resolve("modules"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
