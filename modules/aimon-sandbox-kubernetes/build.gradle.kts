plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    // api() so consumers depending only on this module still see the Sandbox abstractions on
    // their compile classpath.
    api(project(":aimon-sandbox"))

    // Kubernetes Java Client
    implementation(libs.kubernetes.client.java)
    implementation(libs.kubernetes.client.java.extended)

    // Logging
    implementation(libs.slf4j.api)
}

// Checkstyle baseline: locks the existing warning count so new violations fail the build.
// To reduce, fix warnings then lower this number.
checkstyle {
    maxWarnings = 19
}
