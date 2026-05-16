#!/usr/bin/env bash
# Print test counts (passed / failed / skipped) for one or all targets.
#
# Reads JUnit XML files under <module>/build/test-results/<task>/TEST-*.xml.
# For 'server', runs `cargo test` and sums its "test result:" lines.
#
# Usage:
#   scripts/test-counts.sh            # same as 'all'
#   scripts/test-counts.sh all
#   scripts/test-counts.sh android | shared-js | shared-wasmjs | web | server
#
# Exit code is 0 if all reported targets have zero failures, 1 otherwise.
# A target with no test-results directory prints "(not run)" and does not
# contribute a failure.

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

usage() {
  sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

case "${1:-all}" in
  -h|--help) usage 0 ;;
esac

# Sum tests/failures/skipped across TEST-*.xml under the given directory.
# Prints: "<label>: <passed> passed, <failed> failed, <skipped> skipped"
# Returns nonzero exit if <failed> > 0.
count_xml() {
  local label=$1 dir=$2
  if [[ ! -d $dir ]]; then
    printf '%-16s (not run)\n' "$label"
    return 0
  fi
  local tests=0 fails=0 errs=0 skips=0
  while IFS= read -r line; do
    # Each testsuite header has tests="N" failures="N" errors="N" skipped="N"
    local t f e s
    t=$(grep -oE 'tests="[0-9]+"' <<<"$line" | grep -oE '[0-9]+' || echo 0)
    f=$(grep -oE 'failures="[0-9]+"' <<<"$line" | grep -oE '[0-9]+' || echo 0)
    e=$(grep -oE 'errors="[0-9]+"' <<<"$line" | grep -oE '[0-9]+' || echo 0)
    s=$(grep -oE 'skipped="[0-9]+"' <<<"$line" | grep -oE '[0-9]+' || echo 0)
    tests=$((tests + t))
    fails=$((fails + f))
    errs=$((errs + e))
    skips=$((skips + s))
  done < <(find "$dir" -name 'TEST-*.xml' -exec grep -h '<testsuite ' {} +)
  local bad=$((fails + errs))
  local passed=$((tests - bad - skips))
  printf '%-16s %d passed, %d failed, %d skipped\n' "$label" "$passed" "$bad" "$skips"
  [[ $bad -eq 0 ]]
}

count_server() {
  local label="server"
  if ! command -v cargo >/dev/null; then
    printf '%-16s (cargo not in PATH)\n' "$label"
    return 0
  fi
  # cargo test prints one or more "test result: ok. N passed; N failed; N ignored; ..."
  local out
  if ! out=$(cd "$ROOT/server" && cargo test --quiet 2>&1); then
    : # keep going; we still parse what we have
  fi
  local passed=0 failed=0 ignored=0
  while IFS= read -r line; do
    local p f i
    p=$(grep -oE '[0-9]+ passed' <<<"$line" | grep -oE '[0-9]+' || echo 0)
    f=$(grep -oE '[0-9]+ failed' <<<"$line" | grep -oE '[0-9]+' || echo 0)
    i=$(grep -oE '[0-9]+ ignored' <<<"$line" | grep -oE '[0-9]+' || echo 0)
    passed=$((passed + p))
    failed=$((failed + f))
    ignored=$((ignored + i))
  done < <(grep -E '^test result:' <<<"$out" || true)
  if [[ $passed -eq 0 && $failed -eq 0 && $ignored -eq 0 ]]; then
    printf '%-16s (no results — cargo test failed to run)\n' "$label"
    return 1
  fi
  printf '%-16s %d passed, %d failed, %d ignored\n' "$label" "$passed" "$failed" "$ignored"
  [[ $failed -eq 0 ]]
}

run_target() {
  case "$1" in
    android)       count_xml android       "$ROOT/app/build/test-results/testDebugUnitTest" ;;
    shared-js)     count_xml shared-js     "$ROOT/shared/build/test-results/jsBrowserTest" ;;
    shared-wasmjs) count_xml shared-wasmjs "$ROOT/shared/build/test-results/wasmJsBrowserTest" ;;
    web)           count_xml web           "$ROOT/web/build/test-results/jsBrowserTest" ;;
    server)        count_server ;;
    *) echo "unknown target: $1" >&2; usage 2 ;;
  esac
}

target=${1:-all}
if [[ $target == all ]]; then
  rc=0
  for t in server android shared-js shared-wasmjs web; do
    run_target "$t" || rc=1
  done
  exit $rc
else
  run_target "$target"
fi
