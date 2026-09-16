// Make the root project's `libs` version catalog visible to buildSrc itself,
// so buildSrc/build.gradle.kts can use `libs.versions.spotless.get()` etc.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
