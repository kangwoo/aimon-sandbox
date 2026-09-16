rootProject.name = "aimon-sandbox"

include(
    // The SPI plus the four agent tools that are its only consumer: `SandboxBackend`, `SandboxConfig`,
    // `RunStore`, `SandboxExpiryStore`, `SandboxLock`, `ReaperService`, `OrcaSandboxToolProvider`.
    "aimon-sandbox",
    // Backends. Each is a `SandboxBackend` implementation and nothing else, and each puts the SPI on
    // `api` so a consumer that depends only on a backend still compiles against `Sandbox`, `ExecParams`
    // and friends.
    "aimon-sandbox-docker",
    "aimon-sandbox-kubernetes",
)

project(":aimon-sandbox").projectDir = file("modules/aimon-sandbox")
project(":aimon-sandbox-docker").projectDir = file("modules/aimon-sandbox-docker")
project(":aimon-sandbox-kubernetes").projectDir = file("modules/aimon-sandbox-kubernetes")

// No BOM here, unlike aimon-core and aimon-memory, and that is a decision rather than an omission.
//
// A BOM earns its place when an application repeats one version across many dependency lines. This
// repository publishes three coordinates, two of which already carry the third transitively through
// `api` -- so the realistic dependency block is one line, and a fourth published coordinate whose only
// job is to align a version nobody types twice would cost a `java-platform` subproject, a `verifyBom`
// task and a release step to keep something honest that has no way to drift.
//
// Add one the moment that stops being true: a second SPI, or a backend a consumer takes without the SPI.
