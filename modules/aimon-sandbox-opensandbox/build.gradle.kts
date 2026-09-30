plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // `api`: the provider implements the SPI and its public signatures carry SPI types (docs/design/workspace-sandbox.md
    // §4.1). No OpenSandbox type exists at all -- the REST API is called directly (JDK HttpClient + Jackson), so
    // nothing from an SDK can leak out of this module (§21).
    api(project(":aimon-sandbox"))

    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(project(":aimon-sandbox-testkit"))
    // Only the @Tag("docker") tier uses it: the default tier runs against a fake server on the JDK's HttpServer.
    testImplementation(libs.testcontainers)
}
