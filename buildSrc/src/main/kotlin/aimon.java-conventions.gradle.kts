import com.diffplug.gradle.spotless.SpotlessExtension
// Imported rather than fully qualified: inside a .gradle.kts, `java` resolves to the JavaPluginExtension
// accessor and shadows the package root, so `java.util.Properties` does not compile.
import java.math.BigDecimal
import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    checkstyle
    jacoco
    id("com.diffplug.spotless")
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    isFailOnError = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Xlint:unchecked",
            "-Xlint:deprecation",
        ),
    )
    // Same clash the Test block below pins against, on the other side of the build. A worker daemon inherits
    // JAVA_TOOL_OPTIONS from the shell, and Gradle then passes its own smaller -Xmx on the command line — which
    // overrides the inherited -Xmx but NOT the inherited -Xms. `JAVA_TOOL_OPTIONS=-Xmx4g -Xms1g` therefore lands
    // as "-Xms1g with a max well below 1g" and the worker dies before javac starts:
    //     Error occurred during initialization of VM
    //     Initial heap size set to a larger value than the maximum heap size
    // Nothing is wrong with the source when this happens, which is what makes it expensive to diagnose. CI never
    // sees it (no such env var there), so it only ever hits a contributor's machine.
    options.isFork = true
    options.forkOptions.memoryInitialSize = "256m"
    options.forkOptions.memoryMaximumSize = "2g"
}

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    // The gate is severity-based: checkstyle.xml sets severity=error, so any (non-baselined) violation fails via
    // maxErrors=0. maxWarnings is not a reliable knob in this Gradle version, so it is left at its default.
    isIgnoreFailures = false
}

tasks.named<Checkstyle>("checkstyleMain") {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Test sources are exempt from Checkstyle.
tasks.named<Checkstyle>("checkstyleTest") {
    enabled = false
}

configure<SpotlessExtension> {
    java {
        target("src/**/*.java")
        eclipse().configFile(rootProject.file("config/eclipse/eclipse-formatter.xml"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org", "com", "")
        toggleOffOn()
    }

    format("misc") {
        target("*.gradle.kts", "*.md", ".gitignore")
        trimTrailingWhitespace()
        indentWithSpaces(2)
        endWithNewline()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Pin the test JVM heap so JAVA_TOOL_OPTIONS=-Xms… inherited from the user's shell does not
    // clash with a smaller Gradle-default Xmx.
    minHeapSize = "256m"
    maxHeapSize = "2g"
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    reports {
        html.required.set(true)
        junitXml.required.set(true)
    }
}

// Docker/Testcontainers-backed tests are annotated `@Tag("docker")`. The default `test` task — run by
// `build` / `check` — excludes them so unit tests stay fast and need no Docker daemon; the separate
// `integrationTest` task runs exactly those. Modules with no docker-tagged tests simply run nothing in
// `integrationTest` — which, today, is every module here. See the IMPORTANT below.
//
// `@Tag("packaging")` is a third tier with the same shape and a different reason. Those tests build a fat jar
// and launch it in a child JVM, so they cost tens of seconds — which does not belong in the loop a developer runs
// on every save. Excluded from `test` for the same reason `docker` is, and given its own task for the same reason
// too. Repeated `useJUnitPlatform { }` calls accumulate into one options set, so both exclusions apply.
//
// IMPORTANT: neither tier has a subject in this repository today. Nothing here carries `@Tag("docker")` or
// `@Tag("packaging")`, so both tasks match no test class, report NO-SOURCE and go green in under a second.
// They are registered anyway, so that a test tagged tomorrow lands in a tier that already exists and so that
// this build and aimon-core's keep the same shape. But a tier nothing runs cannot be told apart from a
// passing one: do not read a green `integrationTest` or `packagingTest` here as verification of anything.
//
// The first subject is planned: docs/design/workspace-sandbox.md §16 runs the provider contract suite against
// an OpenSandbox server started by Testcontainers under `@Tag("docker")`.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("docker")
        excludeTags("packaging")
    }
}

val testSourceSet = the<SourceSetContainer>()["test"]
tasks.register<Test>("integrationTest") {
    description = "Runs Docker/Testcontainers integration tests (JUnit @Tag(\"docker\"))."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("docker")
    }
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("packagingTest") {
    description = "Runs fat-jar packaging tests (JUnit @Tag(\"packaging\")); builds and launches a real jar."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("packaging")
    }
    // A child JVM's stdout is the evidence these tests read (a WARN that must be emitted, a skill list that must
    // be complete). The parent's own streams are shown so a failure is diagnosable from the console alone.
    testLogging {
        showStandardStreams = true
    }
    shouldRunAfter(tasks.named("test"))
}

// The report reads every tier's execution data, not just `test`'s.
//
// The plugin's default is `test.exec` alone, which in aimon-core made seven published modules measure between
// 0.0% and 12.9% line -- every one of them a module whose tests are @Tag("docker") and therefore absent from
// `test`. A number that low reads as "untested" when the truth is "measured with the tests excluded", and it is
// the number any coverage floor would have been set against. No module here is in that position today, because
// no test here is tagged at all; the configuration is kept so that the first tagged test does not silently get
// measured out of its own module's floor.
//
// Deliberately `mustRunAfter` and not `dependsOn` for the tiers outside `test`: generating a report must not start
// requiring a Docker daemon or a fat jar. Ordering-only means `./gradlew test jacocoTestReport` still works with
// neither, and still reports 0.0% for those modules — correctly, because nothing measured them in that invocation
// — while `./gradlew test integrationTest jacocoTestReport` reports what the docker tier actually covers. Gradle 9
// requires the relationship to be declared either way: reading a file another task produces without saying so
// fails the build with "Declare an explicit dependency".
//
// One consequence worth knowing: exec data left over from an earlier run is folded in as well, so a report can
// describe a tier that did not run in this invocation; delete `build/jacoco/*.exec` when that matters.
// (aimon-core has a second consequence this repository does not yet have -- its tiers run in separate CI jobs
// with separate workspaces, so a third job restores both archives before generating the report. If a docker
// tier lands here and gets its own job, that arrangement is the one to copy.)
// JacocoReportBase, not JacocoReport: the coverage *verification* task is a sibling of the report, not a
// subtype of it, and it reads execution data the same way. Configuring only the report would have left
// `jacocoTestCoverageVerification` on the plugin's default of `test.exec` alone — measuring a tagged module
// with its tests excluded, which is the exact number the floor below exists to stop anyone from freezing.
tasks.withType<JacocoReportBase>().configureEach {
    dependsOn(tasks.named("test"))
    mustRunAfter(tasks.named("integrationTest"), tasks.named("packagingTest"))
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
}

tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// The coverage floor. Values live in gradle/coverage-baselines.properties — data as data, so that moving a
// number is a one-line diff a reviewer can read, and so the whole frozen set is visible on one page rather
// than scattered across the twenty build files that carry a floor.
//
// A module with no entry gets no rule rather than a floor of zero. Zero would be a rule that always passes,
// which reads as "verified" in the task list and verifies nothing; absence at least tells the truth. No module
// has an entry today because none has sources yet (see the properties file); once one does, absence means
// someone added code and did not add a floor.
val coverageBaselines = Properties().apply {
    val file = rootProject.file("gradle/coverage-baselines.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

coverageBaselines.getProperty(project.name)?.let { floor ->
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        // Spelled out in the task list because the failure message cannot say it. A module whose tests are all
        // @Tag("docker") measures near zero when only `test` has run, and JaCoCo reports that as "ratio is 0.00,
        // but expected minimum is 0.88" — which reads as a collapse rather than as a tier that was never run.
        //
        // The description names no tier by name, which is the lesson aimon-core learned the expensive way: its
        // version said `test integrationTest playwrightTest`, and when a module's floor came to depend on a
        // fourth tier the sentence handed to the person the failure had just stopped was wrong.
        description = "Fails if line coverage dropped below gradle/coverage-baselines.properties. Needs every " +
            "tier's execution data, so run `test` and any tagged tier together — a module measured with one of " +
            "its tiers missing reports a collapse rather than the tier that did not run."
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal(floor.trim()).divide(BigDecimal(100))
                }
            }
        }
    }
}

dependencies {
    "compileOnly"(libs.findLibrary("jetbrains-annotations").get())
    "compileOnly"(libs.findLibrary("lombok").get())
    "annotationProcessor"(libs.findLibrary("lombok").get())

    // The platform is what versions `junit-jupiter` and `junit-platform-launcher`, which the catalog
    // declares without one. aimon-core gets those versions incidentally, from the `spring-boot-starter-test`
    // it puts on every module's test classpath; nothing here needs Spring, so the version comes from the
    // BOM that owns it instead of from a dependency carried for a side effect.
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findBundle("testing").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}
