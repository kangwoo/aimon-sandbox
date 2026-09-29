plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // The contract suites (`SandboxProviderContract`, `SandboxWorkspaceStoreContract`) are abstract JUnit 5
    // classes a provider or store implementation extends from its own tests, so JUnit and AssertJ are compiled
    // against in `main` and exported on `api` -- the same arrangement as aimon-core's `aimon-memory-testkit`.
    // A consumer that extends a suite gets the versions the suite was written against.
    api(project(":aimon-sandbox"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.assertj.core)

    implementation(libs.slf4j.api)
}
