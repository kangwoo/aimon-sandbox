import java.io.File

import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.vanniktech.maven.publish")
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
}

// Two kinds of thing are published from this build, and what a publication contains depends on which one
// this is. They are mutually exclusive by construction — Gradle refuses `java-platform` alongside
// `java`/`java-library` — so this is a choice, not a merge. It is written with `plugins.withId` rather than
// an `if` so that the order of a module's own `plugins { }` block cannot change the answer.
plugins.withId("java-library") {
    configure<MavenPublishBaseExtension> {
        configure(
            JavaLibrary(
                javadocJar = JavadocJar.Javadoc(),
                sourcesJar = true,
            ),
        )
    }
}

plugins.withId("java-platform") {
    configure<MavenPublishBaseExtension> {
        // A platform has no code, so there is no javadoc and no sources to attach: the POM is the whole
        // artifact. It still has to be signed, which the block above already arranges.
        configure(JavaPlatform())
    }
}

// Gradle writes a checksum next to every file it publishes, and "every file" includes each `.asc`
// signature. Central does not need those, and Sonatype names them specifically as a Gradle-shaped way file
// counts inflate (https://central.sonatype.org/publish/reducing-publishing-usage/). After
// gradle.properties drops SHA256/SHA512 they are still a third of what a module contributes to the bundle:
// 30 files become 20.
//
// No API suppresses them, so they are deleted once the publication has been staged. That works because
// `SonatypeHost.CENTRAL_PORTAL` publishes into a local directory and zips it at the end of the build — the
// deletion lands before the bundle is assembled. The `file` guard is what keeps that true: against a remote
// repository the checksums are already uploaded and there would be nothing left to delete.
tasks.withType<PublishToMavenRepository>()
    // `PublishToMavenLocal` is a subtype with no repository at all, and the local cache gets no checksums.
    .matching { it !is PublishToMavenLocal }
    .configureEach {
        doLast {
            val repositoryUrl = repository.url
            if (!repositoryUrl.scheme.equals("file", ignoreCase = true)) {
                return@doLast
            }

            val moduleDirectory = File(repositoryUrl)
                .resolve(publication.groupId.replace('.', '/'))
                .resolve(publication.artifactId)
                .resolve(publication.version)
            val signatureChecksums = listOf(".asc.md5", ".asc.sha1", ".asc.sha256", ".asc.sha512")
            val removed = moduleDirectory.listFiles()
                .orEmpty()
                .filter { file -> signatureChecksums.any { file.name.endsWith(it) } }
                .count { it.delete() }

            logger.info("Removed {} signature checksums from {}", removed, moduleDirectory)
        }
    }

// Applying this plugin to anything else would produce a publication with nothing in it, and the first
// evidence of that would be an empty artifact on Central. Say it here instead.
afterEvaluate {
    check(plugins.hasPlugin("java-library") || plugins.hasPlugin("java-platform")) {
        "Project '$path' applies aimon.publishable but is neither a java-library nor a java-platform, " +
            "so there is nothing for it to publish."
    }
}
