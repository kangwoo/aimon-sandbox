plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // `api`: the workspace sandbox implements aimon-core SPIs (`ExecutionEnvironmentProvider`,
    // `VirtualFileSystem`, `VirtualShell`) and takes core types (`Principal`, `SessionId`) in its own
    // signatures (docs/design/workspace-sandbox.md §4.1). The catalog still pins a core that predates the
    // execution-environment SPI; raising it is part of implementation step 3.
    api(libs.aimon.core)
}
