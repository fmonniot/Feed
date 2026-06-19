#!/usr/bin/env bash
# shot-ref.sh — capture the design-reference artboards from the story board
# (ticket #75) into build/.shots/ref/, matching the live-app shot names.
#
# One-time setup: ( cd scripts/shots && npm install && npx playwright install chromium )
#
# Usage: scripts/shot-ref.sh
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
SHOTS="$ROOT/scripts/shots"
PORT=6789

[ -d "$SHOTS/node_modules/playwright" ] \
  || { echo "✗ Playwright not installed — run ( cd scripts/shots && npm install && npx playwright install chromium )" >&2; exit 1; }

# Serve the story board for the duration of the capture.
python3 -m http.server "$PORT" -d "$ROOT/spec/story-board" >/dev/null 2>&1 &
PY_PID=$!
trap 'kill "$PY_PID" 2>/dev/null || true' EXIT
sleep 1

( cd "$SHOTS" && node ref-shots.mjs --base "http://localhost:$PORT" --out "$ROOT/build/.shots/ref" )
