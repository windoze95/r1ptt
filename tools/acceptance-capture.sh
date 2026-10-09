#!/usr/bin/env bash
# The workload is performed by a human; this tool runs only verified read-only ADB commands.
set -euo pipefail
TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
NODE_BIN="$(command -v node || true)"
if [ -z "$NODE_BIN" ] && [ -x /opt/homebrew/bin/node ]; then NODE_BIN=/opt/homebrew/bin/node; fi
if [ -z "$NODE_BIN" ]; then printf 'Node.js is required; no dependencies are installed by this tool.\n' >&2; exit 1; fi
exec "$NODE_BIN" "$TOOL_DIR/acceptance.mjs" capture "$@"
