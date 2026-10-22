plugins {
    java
}

allprojects {
    group = findProperty("GROUP") as String
    version = findProperty("VERSION_NAME") as String

    repositories {
        mavenCentral()
        // Only for the aimon-core SNAPSHOT pinned in gradle/libs.versions.toml. Filtered to that group and to
        // snapshots so no other coordinate, and no release, can be served from here instead of from Central.
        // Delete this block together with the SNAPSHOT pin -- keeping it is a release blocker.
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            name = "centralSnapshots"
            content {
                includeGroup("at.aimon.core")
            }
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}

// Module-wide quality, formatting and publishing config lives in pre-compiled script plugins under
// `buildSrc/src/main/kotlin/`:
//   - aimon.java-conventions  (Java 17, Spotless, Checkstyle, JaCoCo, JUnit, common deps)
//   - aimon.publishable       (Maven Central publishing via vanniktech)
//
// Both are ports of the files of the same name in aimon-core, trimmed to what this repository resolves.
// Keeping the names and the shape is deliberate: someone who has read one build should not have to read
// the other from scratch. Where they differ, the comment at the difference says why.
//
// Every subproject here has Java sources -- there is no `java-platform` -- so the aggregators below
// address subprojects directly. aimon-core filters platforms out; that filter would be a no-op here and
// is left out rather than carried as decoration.

tasks.register("format") {
    description = "Format all Java code using Spotless"
    group = "formatting"
    dependsOn(subprojects.map { it.tasks.named("spotlessApply") })
}

tasks.register("checkFormat") {
    description = "Check Java code formatting using Spotless"
    group = "verification"
    dependsOn(subprojects.map { it.tasks.named("spotlessCheck") })
}

tasks.register("checkStyle") {
    description = "Run Checkstyle on all modules"
    group = "verification"
    dependsOn(subprojects.map { it.tasks.named("checkstyleMain") })
}

// `test` is each module's own test task, which excludes `@Tag("docker")`, `@Tag("packaging")` and `@Tag("k8s")`
// (see the aimon.java-conventions plugin). Only `aimon-sandbox-opensandbox` has tests in those tiers
// (`integrationTest`, `k8sTest`); `checkAll` runs none of them -- see the tier comment in that plugin.
tasks.register("checkAll") {
    description = "Run all code quality checks (Spotless + Checkstyle + unit tests)"
    group = "verification"
    dependsOn("checkFormat", "checkStyle")
    dependsOn(subprojects.map { it.tasks.named("test") })
}
