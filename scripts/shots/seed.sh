#!/usr/bin/env bash
# seed.sh — populate a running dev server with sample feeds so the screenshot
# screens (list, reader, feeds) are non-empty. Part of ticket #75 tooling.
#
# Serves the local RSS fixtures in scripts/shots/fixtures/ over HTTP (the server
# fetches feed URLs server-side), then POSTs them to /v1/feeds. Adding a feed
# fetches its initial articles, so no separate refresh call is needed.
#
# Usage: scripts/shots/seed.sh [--server URL] [--user U] [--pass P]
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
FIXTURES="$ROOT/scripts/shots/fixtures"

SERVER="http://localhost:3000"
USER="admin"
PASS="admin"
FIXTURE_PORT=6790

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --user) USER="$2"; shift 2 ;;
    --pass) PASS="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# Protected routes authenticate with the `session` cookie set by /v1/auth/login,
# not a Bearer header — capture it in a cookie jar and reuse it.
JAR="$(mktemp -t feed-seed-cookies.XXXXXX)"
trap 'rm -f "$JAR"; kill "${PY_PID:-}" 2>/dev/null || true' EXIT

if ! curl -sf "$SERVER/v1/auth/login" -o /dev/null -c "$JAR" -X POST \
     -H 'Content-Type: application/json' \
     -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" 2>/dev/null; then
  echo "✗ Cannot reach the server at $SERVER (or bad credentials)." >&2
  echo "  Start it first:  ( cd server && cargo run )" >&2
  exit 1
fi

if ! grep -q "session" "$JAR"; then echo "✗ login set no session cookie" >&2; exit 1; fi

# Serve fixtures so the server can fetch them.
python3 -m http.server "$FIXTURE_PORT" -d "$FIXTURES" >/dev/null 2>&1 &
PY_PID=$!
sleep 1

add_feed() {
  local url="$1"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "$SERVER/v1/feeds" -X POST \
    -b "$JAR" -H 'Content-Type: application/json' \
    -d "{\"url\":\"$url\"}")"
  echo "  $code  $url"
}

echo "Seeding feeds into $SERVER ..."
for f in "$FIXTURES"/*.xml; do
  add_feed "http://localhost:$FIXTURE_PORT/$(basename "$f")"
done
echo "Done."
