plugins {
    java
}

allprojects {
    group = findProperty("GROUP") as String
    version = findProperty("VERSION_NAME") as String

    repositories {
        mavenCentral()
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

// `test` is each module's own test task, which excludes `@Tag("docker")` and `@Tag("packaging")` (see the
// aimon.java-conventions plugin). No test in this repository carries either tag today, so `integrationTest`
// and `packagingTest` currently match nothing -- see the tier comment in that plugin, which says what that
// costs.
tasks.register("checkAll") {
    description = "Run all code quality checks (Spotless + Checkstyle + unit tests)"
    group = "verification"
    dependsOn("checkFormat", "checkStyle")
    dependsOn(subprojects.map { it.tasks.named("test") })
}
