plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // `implementation`, not `api`: aimon-core types appear in this module's own signatures
    // (`OrcaSandboxToolProvider implements OrcaToolProvider`, the four tools extend `AbstractTool` /
    // `GenericTool`, `CopyToSandboxTool` takes a `VirtualFileSystem`), but a consumer that wants to
    // register those tools already depends on aimon-core to have an agent to register them with. Keeping
    // it off `api` is aimon-core's own module rule, carried over with the code: an implementation module
    // does not re-export the framework.
    implementation(libs.aimon.core)

    // Logging
    implementation(libs.slf4j.api)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 68
}
