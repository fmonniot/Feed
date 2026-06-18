#!/usr/bin/env bash
# shot-web.sh — capture web-client screenshots for design verification (ticket #75).
#
# Prerequisites (start these yourself; this script does not spawn long-running
# servers — it checks they're up and fails clearly if not):
#   1. Rust server:     ( cd server && cargo run )
#   2. Web dev server:  ./gradlew :web:jsBrowserDevelopmentRun   (serves :8080, proxies /v1 -> :3000)
#   3. One-time setup:  ( cd scripts/shots && npm install && npx playwright install chromium )
#
# Usage: scripts/shot-web.sh [--no-seed] [--base URL]
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
SHOTS="$ROOT/scripts/shots"
BASE="http://localhost:8080"
SEED=1

while [ $# -gt 0 ]; do
  case "$1" in
    --no-seed) SEED=0; shift ;;
    --base) BASE="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "✗ $1" >&2; exit 1; }

curl -sf "http://localhost:3000/v1/auth/login" -o /dev/null -X POST \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"x"}' >/dev/null 2>&1 \
  || [ $? -eq 22 ] \
  || fail "Rust server not reachable on :3000 — run ( cd server && cargo run )"

curl -sf "$BASE" -o /dev/null 2>&1 \
  || fail "Web dev server not reachable on $BASE — run ./gradlew :web:jsBrowserDevelopmentRun"

[ -d "$SHOTS/node_modules/playwright" ] \
  || fail "Playwright not installed — run ( cd scripts/shots && npm install && npx playwright install chromium )"

if [ "$SEED" -eq 1 ]; then
  echo "== Seeding sample data =="
  "$SHOTS/seed.sh" || fail "seeding failed"
fi

echo "== Capturing web screenshots =="
( cd "$SHOTS" && node web-shots.mjs --base "$BASE" --out "$ROOT/build/.shots/web" )
