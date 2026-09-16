plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Expose the root project's `libs` version catalog (gradle/libs.versions.toml) inside
// pre-compiled script plugins under buildSrc/src/main/kotlin. Without this, convention
// plugins cannot reference `libs.foo.bar` typesafe accessors.
// See: https://github.com/gradle/gradle/issues/15383
dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    // Plugin marker artifacts so convention plugins can apply them via `plugins { id(...) }`.
    // Versions are sourced from the root version catalog to keep a single source of truth.
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:${libs.versions.spotless.get()}")
    implementation(
        "com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:" +
            libs.versions.maven.publish.get(),
    )
}
