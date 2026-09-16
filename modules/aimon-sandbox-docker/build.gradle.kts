plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // api() so consumers depending only on this module still see the Sandbox abstractions on
    // their compile classpath.
    api(project(":aimon-sandbox"))

    // Docker Java SDK
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)

    // Logging
    implementation(libs.slf4j.api)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 14
}
