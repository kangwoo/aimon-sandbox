#!/usr/bin/env bash
# Starts the OpenSandbox server (Docker runtime) from a source checkout with this directory's config.toml.
# Usage: OPENSANDBOX_SRC=/path/to/OpenSandbox ./start-server.sh   (the spike used commit 3738975)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "${OPENSANDBOX_SRC:?set OPENSANDBOX_SRC to an OpenSandbox checkout}/server"
mkdir -p state
export SANDBOX_CONFIG_PATH="$here/config.toml"
exec uv run opensandbox-server
