rootProject.name = "aimon-sandbox"

include(
    // The workspace sandbox (docs/design/workspace-sandbox.md §4.1). Empty until implementation step 3
    // (§18): the identifier-based tools, `SandboxBackend` and the Docker/Kubernetes backends it replaces
    // were deleted rather than kept alongside it. `aimon-sandbox-opensandbox`, `aimon-sandbox-testkit` and
    // `aimon-sandbox-store-jdbc` join as their steps land.
    "aimon-sandbox",
)

project(":aimon-sandbox").projectDir = file("modules/aimon-sandbox")

// No BOM, and that is a decision rather than an omission.
//
// A BOM earns its place when an application repeats one version across many dependency lines. This
// repository publishes one coordinate today, and the modules §4.1 adds put `aimon-sandbox` on `api`, so
// the realistic dependency block stays one line.
//
// Add one the moment that stops being true: a module a consumer takes without the SPI.
