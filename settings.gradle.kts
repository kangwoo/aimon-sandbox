rootProject.name = "aimon-sandbox"

include(
    // The workspace sandbox (docs/design/workspace-sandbox.md §4.1), filled by implementation step 3 (§18):
    // the identifier-based tools, `SandboxBackend` and the Docker/Kubernetes backends it replaces were deleted
    // rather than kept alongside it. The testkit joined with step 3; `aimon-sandbox-opensandbox` (step 4) and
    // `aimon-sandbox-store-jdbc` (step 6) join as their steps land.
    "aimon-sandbox",
    "aimon-sandbox-testkit",
)

project(":aimon-sandbox").projectDir = file("modules/aimon-sandbox")
project(":aimon-sandbox-testkit").projectDir = file("modules/aimon-sandbox-testkit")

// No BOM, and that is a decision rather than an omission.
//
// A BOM earns its place when an application repeats one version across many dependency lines. This
// repository publishes one coordinate today, and the modules §4.1 adds put `aimon-sandbox` on `api`, so
// the realistic dependency block stays one line.
//
// Add one the moment that stops being true: a module a consumer takes without the SPI.
