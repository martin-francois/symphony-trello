#!/usr/bin/env bash
# Entry point for the repeatable live bug-bash harness. See docs/live-bugbash.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"

if ! command -v node >/dev/null 2>&1; then
  echo "live-bugbash: Node.js 24 or newer is required (node was not found on PATH)." >&2
  exit 2
fi
node_major="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$node_major" -lt 24 ]; then
  echo "live-bugbash: Node.js 24 or newer is required (found $(node --version))." >&2
  exit 2
fi
if [ ! -d "$repo_root/node_modules/yaml" ]; then
  echo "live-bugbash: script dependencies are missing. Run: pnpm install --frozen-lockfile" >&2
  exit 2
fi

exec node "$repo_root/scripts/live-bugbash/harness.ts" "$@"
