#!/usr/bin/env bash
# Run tests for a target (or all) and print a compact one-line summary per
# target at the end. Full output is written to a logfile under
# build/.test-logs/<target>.log so failures can be inspected without
# re-running.
#
# Usage:
#   scripts/test-run.sh           # same as 'all'
#   scripts/test-run.sh all
#   scripts/test-run.sh server | android | shared | web
#
# Exit code: 0 if every requested target passes, 1 otherwise.

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

LOG_DIR="$ROOT/build/.test-logs"
mkdir -p "$LOG_DIR"

usage() {
  sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

case "${1:-all}" in
  -h|--help) usage 0 ;;
esac

# Run a command, capture output to a logfile, then summarize via test-counts.
# args: <label> <log-name> <count-target> <cmd...>
run_one() {
  local label=$1 log=$2 count_target=$3
  shift 3
  local logfile="$LOG_DIR/$log.log"
  printf '… %s\n' "$label"
  if "$@" >"$logfile" 2>&1; then
    local status=0
  else
    local status=$?
  fi
  # Always summarize — even on nonzero status the XML/test-result lines may
  # still tell us how many tests ran.
  local summary
  if ! summary=$("$ROOT/scripts/test-counts.sh" "$count_target" 2>/dev/null); then
    : # propagate via $status
  fi
  if [[ $status -eq 0 ]]; then
    printf '✓ %s  (log: %s)\n' "$summary" "$logfile"
    return 0
  else
    printf '✗ %s  (exit %d, log: %s)\n' "$summary" "$status" "$logfile"
    return 1
  fi
}

run_server()  { run_one "server (cargo test)"          server  server  bash -c '(cd "'"$ROOT"'/server" && cargo test)'; }
run_android() { run_one "android (testDebugUnitTest)"  android android ./gradlew :app:testDebugUnitTest; }
run_shared()  {
  run_one "shared js (jsBrowserTest)"        shared-js     shared-js     ./gradlew :shared:jsBrowserTest
}
run_web()     { run_one "web (jsTest)"                  web     web     ./gradlew :web:jsTest; }

target=${1:-all}
rc=0
case "$target" in
  all)
    run_server  || rc=1
    run_android || rc=1
    run_shared  || rc=1
    run_web     || rc=1
    ;;
  server)  run_server  || rc=1 ;;
  android) run_android || rc=1 ;;
  shared)  run_shared  || rc=1 ;;
  web)     run_web     || rc=1 ;;
  *) echo "unknown target: $target" >&2; usage 2 ;;
esac

exit $rc
