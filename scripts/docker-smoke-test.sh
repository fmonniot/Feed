#!/usr/bin/env bash
# Smoke-test a built feed-server image: start it with a minimal config and check that it
# actually serves HTTP and reports the expected version.
#
# Usage: scripts/docker-smoke-test.sh <image> [expected-version]
#
# This exists because a Docker build can "succeed" while shipping a useless binary (the
# v0.11.6 image contained the Dockerfile's dependency-caching placeholder `fn main() {}`,
# which exits 0 immediately with no output). Nothing short of running the image catches it.
set -euo pipefail

IMAGE="${1:?usage: $0 <image> [expected-version]}"
EXPECTED_VERSION="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WORKDIR="$(mktemp -d)"
NAME="feed-smoke-$$"
cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

# The container runs as uid 1001; make the config readable regardless of who we are.
cp "$ROOT/server/config.docker.example.toml" "$WORKDIR/config.toml"
chmod 644 "$WORKDIR/config.toml"

docker run -d --name "$NAME" \
  -p 127.0.0.1::3000 \
  -v "$WORKDIR/config.toml:/app/config.toml:ro" \
  -e FEED_JWT_SECRET="smoke-test-secret-smoke-test-secret-0123456789" \
  "$IMAGE" >/dev/null

fail() {
  echo "SMOKE TEST FAILED: $1" >&2
  echo "--- container state: $(docker inspect -f '{{.State.Status}} (exit {{.State.ExitCode}})' "$NAME" 2>&1)" >&2
  echo "--- container logs:" >&2
  docker logs "$NAME" >&2 2>&1 || true
  exit 1
}

running() { [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = "true" ]; }

# Poll /v1/health for up to ~30s. Bail out early if the container has exited, which is
# exactly what a placeholder or crashing binary does.
BASE=""
for _ in $(seq 1 30); do
  running || fail "container exited before becoming healthy"
  if [ -z "$BASE" ]; then
    PORT="$(docker port "$NAME" 3000/tcp 2>/dev/null | head -n1 | sed 's/.*://' || true)"
    [ -n "$PORT" ] && BASE="http://127.0.0.1:${PORT}"
  fi
  if [ -n "$BASE" ] && curl -fsS "$BASE/v1/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
[ -n "$BASE" ] && curl -fsS "$BASE/v1/health" >/dev/null 2>&1 || fail "GET /v1/health did not succeed within 30s"

VERSION_JSON="$(curl -fsS "$BASE/v1/version")" || fail "GET /v1/version failed"
echo "version endpoint: $VERSION_JSON"
if [ -n "$EXPECTED_VERSION" ] && ! grep -q "\"$EXPECTED_VERSION\"" <<<"$VERSION_JSON"; then
  fail "expected version $EXPECTED_VERSION, got $VERSION_JSON"
fi

# The web client is baked into the image; make sure the SPA is actually served.
curl -fsS "$BASE/" | grep -qi '<html' || fail "GET / did not return the web client"

echo "Smoke test passed for $IMAGE"
