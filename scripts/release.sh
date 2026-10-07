#!/usr/bin/env bash
#
# aimon-sandbox release: bump version, run the quality gate, publish to Maven Central, then tag & push.
#
# A port of aimon-core's scripts/release.sh, trimmed to what this repository has. The order of operations, the
# version arithmetic, the credential check and the post-publish git steps are core's unchanged; the comments below
# are only at the differences (the environment refusal in §0, the k8s acknowledgement in §1, the gate in §4).
#
# Usage:
#   scripts/release.sh [patch|minor|major] [--k8s-verified] [--yes] [--dry-run]
#
#   patch|minor|major   semantic bump of VERSION_NAME in gradle.properties (default: patch).
#                       From X.Y.Z-SNAPSHOT the release is the smallest version of that kind at or
#                       above X.Y.Z — so `patch` releases X.Y.Z itself (see §3)
#   --k8s-verified      states that `./gradlew :aimon-sandbox-opensandbox:k8sTest` passed against a provisioned
#                       cluster on this commit. Required for a real release; a dry run only reminds (see §1)
#   --yes, -y           skip the interactive "type the version" confirmation (for automation)
#   --dry-run           run all checks + the quality gate, then stop before any mutation/publish
#
# Order of operations (publish is irreversible, so git history is only pushed AFTER a successful
# publish; on failure the only side effect is an uncommitted gradle.properties bump, easily reverted):
#   environment check → pre-flight → quality gate → confirm → bump (uncommitted) → publish → commit + tag
#   → next -SNAPSHOT commit → push
#
set -euo pipefail

# ── args ────────────────────────────────────────────────────────────────────
BUMP="patch"
ASSUME_YES=0
DRY_RUN=0
K8S_VERIFIED=0
for arg in "$@"; do
    case "$arg" in
        patch | minor | major) BUMP="$arg" ;;
        --k8s-verified) K8S_VERIFIED=1 ;;
        --yes | -y) ASSUME_YES=1 ;;
        --dry-run) DRY_RUN=1 ;;
        *)
            echo "Unknown argument: $arg" >&2
            echo "Usage: scripts/release.sh [patch|minor|major] [--k8s-verified] [--yes] [--dry-run]" >&2
            exit 2
            ;;
    esac
done

log() { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
ok() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*" >&2; }
fail() {
    printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2
    exit 1
}

# ── 0. test-server overrides ────────────────────────────────────────────────
# Core refuses provider API keys here, because a key makes its gate run tests CI does not. This repository has no
# such key; it has the same hazard in a different shape. OPENSANDBOX_TEST_ENDPOINT points the docker tier at an
# external server instead of the one OpenSandboxTestServer starts on the local daemon, and
# OPENSANDBOX_TEST_SANDBOX_IMAGE swaps the sandbox image the tier builds from src/test/docker. Either one makes the
# gate's `integrationTest` measure something other than what CI's `integration` job measures, which is the promise
# §4 is built on. Refused rather than unset for core's reason: unsetting it in here fixes one command and tells the
# operator nothing about the shell they started from. The check asks whether the variable exists and never expands
# its value (an API key may sit beside it). It runs before anything touches the repository, as core's does.
overrides_set=""
for name in OPENSANDBOX_TEST_ENDPOINT OPENSANDBOX_TEST_SANDBOX_IMAGE; do
    if [ -n "${!name+set}" ]; then
        overrides_set="${overrides_set:+$overrides_set }$name"
    fi
done
if [ -n "$overrides_set" ]; then
    printf '%s\n' \
        "Set in this environment: ${overrides_set}. This script never prints their values." \
        "They redirect the docker tier away from the server and image CI tests against, so the release gate" \
        "would no longer be CI's gate. Unset them and re-run:" \
        "    unset ${overrides_set}" >&2
    fail "Refusing to start while a docker-tier override is in the environment: ${overrides_set}"
fi

cd "$(git rev-parse --show-toplevel)"

# JAVA_TOOL_OPTIONS often carries -Xms (e.g. -Xms1g) from the shell, which clashes with the Gradle
# worker daemon's smaller default -Xmx ("Initial heap size set to a larger value than the maximum").
# Pin a max-only override for every Gradle invocation here.
export JAVA_TOOL_OPTIONS="-Xmx3g"
GRADLE="./gradlew --console=plain"

cleanup() {
    local rc=$?
    if [ $rc -ne 0 ] && ! git diff --quiet -- gradle.properties 2>/dev/null; then
        echo "" >&2
        echo "Note: gradle.properties has an uncommitted version bump. To revert: git checkout -- gradle.properties" >&2
    fi
}
trap cleanup EXIT

# ── 1. pre-flight ───────────────────────────────────────────────────────────
log "Pre-flight checks"
[ -f gradle.properties ] || fail "gradle.properties not found — run from the aimon-sandbox repo"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || fail "Releases must be cut from 'main' (currently on '$BRANCH')"

# `if` rather than core's `a && b || fail`: same meaning, without shellcheck's SC2015 note.
if ! git diff --quiet || ! git diff --cached --quiet; then
    fail "Working tree is not clean — commit or stash changes first"
fi

git fetch --quiet origin main
LOCAL_REV="$(git rev-parse @)"
REMOTE_REV="$(git rev-parse '@{u}')"
[ "$LOCAL_REV" = "$REMOTE_REV" ] || fail "Local 'main' is not in sync with origin/main — pull/push first"
ok "Clean working tree on main, in sync with origin"

# A release must not depend on a SNAPSHOT: Central would accept the POM, and the artifact would resolve against
# whatever that snapshot is next week, or against nothing once it is gone. The catalog is where every version lives
# (aimon-core is pinned to a snapshot only between its PR and its release), so that is what is read.
SNAPSHOT_PINS="$(grep -E '^[A-Za-z0-9_-]+ *= *"[^"]*-SNAPSHOT"' gradle/libs.versions.toml || true)"
[ -z "$SNAPSHOT_PINS" ] || fail "gradle/libs.versions.toml pins a SNAPSHOT — release it first and raise the pin: ${SNAPSHOT_PINS//$'\n'/, }"
ok "No SNAPSHOT pins in the version catalog"

# Same reasoning as core: the gate runs `integrationTest`, which needs a daemon, so find out now rather than minutes
# into the gate — and fail rather than skip, or the strictest-looking setup becomes the weakest.
docker info >/dev/null 2>&1 || fail "Docker daemon is not running — the release gate runs integrationTest (@Tag(\"docker\")). Start Docker and re-run."
ok "Docker daemon reachable"

# The k8s tier has no counterpart in core. `k8sTest` (@Tag("k8s")) needs a Kubernetes-runtime OpenSandbox server
# someone provisioned — kind + the chart + the hardened template, minutes of setup
# (modules/aimon-sandbox-opensandbox/README.md) — so it is neither a CI job nor a gate task, and its tests skip
# rather than fail without OPENSANDBOX_K8S_ENDPOINT. Running it from here would therefore prove nothing: a green
# `k8sTest` on a machine with no cluster is every test skipped.
#
# What this can do is make skipping it a decision instead of an omission. A real release needs `--k8s-verified`,
# the operator's statement that the tier passed against a cluster on this commit; a dry run only reminds. A flag
# rather than a prompt so `--yes` automation cannot answer it by accident, and so the statement is visible in the
# command that was run.
if [ "$K8S_VERIFIED" = 1 ]; then
    ok "k8sTest acknowledged as run against a provisioned cluster (--k8s-verified)"
elif [ "$DRY_RUN" = 1 ]; then
    warn "Reminder: the real release needs --k8s-verified — run scripts/k8s-tier.sh up && scripts/k8s-tier.sh test first (modules/aimon-sandbox-opensandbox/README.md)."
else
    fail "k8sTest is manual and not in the gate. Run scripts/k8s-tier.sh up && scripts/k8s-tier.sh test (every test must pass, none skipped; modules/aimon-sandbox-opensandbox/README.md), then re-run with --k8s-verified."
fi

# ── 2. credentials (names only; never print values) ─────────────────────────
log "Verifying Maven Central + signing credentials"
GP="$HOME/.gradle/gradle.properties"
require_cred() {
    local key="$1"
    grep -q "^${key}=" "$GP" 2>/dev/null && return 0
    [ -n "$(printenv "ORG_GRADLE_PROJECT_${key}" 2>/dev/null)" ] && return 0
    fail "Missing publish credential '${key}' — set it in ~/.gradle/gradle.properties or env ORG_GRADLE_PROJECT_${key}"
}
require_cred mavenCentralUsername
require_cred mavenCentralPassword
if ! grep -qE '^signing\.(keyId|secretKeyRingFile)=' "$GP" 2>/dev/null \
    && [ -z "$(printenv ORG_GRADLE_PROJECT_signingInMemoryKey 2>/dev/null)" ]; then
    fail "Missing GPG signing config (signing.keyId / signing.secretKeyRingFile or signingInMemoryKey)"
fi
ok "Credentials present"

# ── 3. compute next version ─────────────────────────────────────────────────
# Core's arithmetic, unchanged. Between releases main carries X.Y.Z-SNAPSHOT, where X.Y.Z is the NEXT patch release,
# so from a snapshot `patch` releases X.Y.Z as-is, `minor` releases X.Y.0 when Z is already 0 and X.(Y+1).0
# otherwise, and likewise for `major`. The first release is therefore `scripts/release.sh patch` from 0.1.0-SNAPSHOT,
# which releases 0.1.0 (so does `minor`). A bare X.Y.Z names a version already released, so every bump moves past it.
CURRENT="$(grep '^VERSION_NAME=' gradle.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')"
[[ "$CURRENT" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-SNAPSHOT)?$ ]] \
    || fail "VERSION_NAME='$CURRENT' is not in X.Y.Z or X.Y.Z-SNAPSHOT form"
MAJ="${BASH_REMATCH[1]}"
MIN="${BASH_REMATCH[2]}"
PAT="${BASH_REMATCH[3]}"
if [ -n "${BASH_REMATCH[4]}" ]; then
    case "$BUMP" in
        major)
            if [ "$MIN" -ne 0 ] || [ "$PAT" -ne 0 ]; then
                MAJ=$((MAJ + 1))
                MIN=0
                PAT=0
            fi
            ;;
        minor)
            if [ "$PAT" -ne 0 ]; then
                MIN=$((MIN + 1))
                PAT=0
            fi
            ;;
        patch) ;;
    esac
else
    case "$BUMP" in
        major)
            MAJ=$((MAJ + 1))
            MIN=0
            PAT=0
            ;;
        minor)
            MIN=$((MIN + 1))
            PAT=0
            ;;
        patch) PAT=$((PAT + 1)) ;;
    esac
fi
NEXT="${MAJ}.${MIN}.${PAT}"
# What main moves to once NEXT is tagged: the next patch, as a snapshot.
DEV_NEXT="${MAJ}.${MIN}.$((PAT + 1))-SNAPSHOT"
TAG="v${NEXT}"
log "Version bump (${BUMP}): ${CURRENT} → ${NEXT}   (tag ${TAG})"
git rev-parse "$TAG" >/dev/null 2>&1 && fail "Tag ${TAG} already exists"

# The GitHub Release body is cut from CHANGELOG.md by .github/workflows/release.yml, which triggers on the tag this
# script pushes. Like core, this warns and does not write the section itself: the script commits gradle.properties
# and nothing else, so finalizing `## [Unreleased]` as `## [X.Y.Z] - YYYY-MM-DD` is a reviewed PR merged to main
# before this runs (README › Releasing).
if ! grep -q "^## \[${NEXT}\]" CHANGELOG.md; then
    warn "CHANGELOG.md has no \"## [${NEXT}]\" section."
    printf '  The GitHub Release will fall back to a pointer instead of real notes.\n' >&2
    printf '  Finalize the [Unreleased] section as [%s] first if you want notes.\n' "$NEXT" >&2
fi

# ── 4. quality gate ─────────────────────────────────────────────────────────
# The same verification tasks CI runs (.github/workflows/build.yml), which splits them across three jobs — `build`
# (checkAll), `integration` (integrationTest) and `coverage` (the floor, from both tiers' execution data) — and adds
# the report-only `jacocoTestReport`. A release must not pass a gate narrower than the one every PR already clears.
# Here everything runs in one workspace, so the floor is checked against both tiers without CI's hand-off.
#
# `integrationTest` (@Tag("docker")) is the only place the OpenSandbox provider meets a real server — its default
# tier is a fake. A publish to Maven Central cannot be taken back, so the machine that publishes runs it. THIS MEANS
# A RELEASE NEEDS A RUNNING DOCKER DAEMON (§1 checks).
#
# Not here, unlike core: `packagingTest` (nothing in this repository carries @Tag("packaging"); the task reports
# NO-SOURCE, and CI does not run it) and `k8sTest` (manual; see §1).
#
# Keep the tasks on ONE `$GRADLE` line directly under the log line. ReleaseGateMatchesCiGateTest
# (modules/aimon-sandbox/src/test/java/at/aimon/sandbox/architecture/) reads the first `$GRADLE` invocation after
# this section marker and compares it against every `./gradlew` task in build.yml; a second line would be
# invisible to it.
log "Quality gate: checkAll + integrationTest + coverage floor"
$GRADLE checkAll integrationTest jacocoTestCoverageVerification
ok "Quality gate passed"

if [ "$DRY_RUN" = 1 ]; then
    echo ""
    ok "Dry run complete. Would: bump to ${NEXT}, publish to Maven Central, commit, tag ${TAG}, move main to ${DEV_NEXT}, push."
    exit 0
fi

# ── 5. confirm (publish is permanent) ───────────────────────────────────────
if [ "$ASSUME_YES" != 1 ]; then
    echo ""
    printf '\033[1;33mPublishing %s to Maven Central is PERMANENT and PUBLIC.\033[0m\n' "$NEXT"
    printf 'Type the version (%s) to confirm: ' "$NEXT"
    read -r reply
    [ "$reply" = "$NEXT" ] || fail "Confirmation did not match — aborted (no changes made)"
fi

# ── 6. bump (uncommitted) → publish → commit + tag → push ───────────────────
log "Writing VERSION_NAME=${NEXT}"
perl -i -pe "s{^VERSION_NAME=.*}{VERSION_NAME=${NEXT}}" gradle.properties

log "Publishing to Maven Central (Central Portal)…"
$GRADLE publishAllPublicationsToMavenCentralRepository

log "Committing + tagging"
git add gradle.properties
git commit -q -m "chore(release): bump version to ${NEXT}"
git tag -a "$TAG" -m "Release ${NEXT}"

# After the tag, so the tagged tree still says NEXT — release.yml cross-checks the two.
log "Moving main to the next development version ${DEV_NEXT}"
perl -i -pe "s{^VERSION_NAME=.*}{VERSION_NAME=${DEV_NEXT}}" gradle.properties
git add gradle.properties
git commit -q -m "chore(release): prepare next development version ${DEV_NEXT}"

log "Pushing commits + tag to origin"
git push origin main
git push origin "$TAG"

echo ""
ok "Released ${NEXT}; main is now ${DEV_NEXT}. Central Portal may take a few minutes to validate and release the deployment."
echo "  The pushed tag triggers .github/workflows/release.yml, which creates the GitHub Release."
