#!/usr/bin/env bash
# Build the Rust server (debug profile) and print a concise status line.
# Mirrors the prerequisite step that `:app:buildServerBinary` performs for
# the Android JVM tests; useful when invoking tests from outside gradle.
#
# Usage:
#   scripts/server-build.sh           # debug build
#   scripts/server-build.sh --release # release build
#
# Exit code follows cargo build.

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)

case "${1:-}" in
  -h|--help)
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
esac

profile=debug
extra=()
if [[ "${1:-}" == "--release" ]]; then
  profile=release
  extra=(--release)
fi

cd "$ROOT/server"
if cargo build "${extra[@]}" --quiet; then
  binary="$ROOT/server/target/$profile/server"
  if [[ -x $binary ]]; then
    printf '✓ server built (%s) — %s\n' "$profile" "$binary"
  else
    printf '✓ server built (%s) but binary not at expected path: %s\n' "$profile" "$binary" >&2
    exit 1
  fi
else
  rc=$?
  printf '✗ cargo build failed (exit %d)\n' "$rc" >&2
  exit $rc
fi
