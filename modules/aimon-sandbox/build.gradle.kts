plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // `api`: the workspace sandbox implements aimon-core SPIs (`ExecutionEnvironmentProvider`,
    // `VirtualFileSystem`, `VirtualShell`) and takes core types (`Principal`, `SessionId`) in its own
    // signatures (docs/design/workspace-sandbox.md §4.1).
    api(libs.aimon.core)

    // `implementation`: neither appears on a public signature. aimon-core's published `apiElements` carries
    // no dependencies, so the logging facade and the JSON reader for `rg --json` are declared here rather than
    // borrowed from core's runtime classpath.
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)

    // The contract suites and the local provider live in the testkit, which depends on this module's main
    // source set; this edge is test-only, so there is no cycle in the task graph.
    testImplementation(project(":aimon-sandbox-testkit"))
}
