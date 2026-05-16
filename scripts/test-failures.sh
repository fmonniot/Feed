#!/usr/bin/env bash
# Print failing tests (with the first line of each failure message) from the
# most recent test run. Gradle targets read JUnit XML; server re-runs
# `cargo test --no-fail-fast` and greps the result.
#
# Usage:
#   scripts/test-failures.sh             # all targets
#   scripts/test-failures.sh android | shared-js | shared-wasmjs | web | server
#
# Exits 0 even when failures are found — this is a reporting tool. It exits
# nonzero only on bad arguments or unreadable inputs.

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

# Parse JUnit XML for testcase elements containing <failure> or <error>.
# Prints "<label>  <classname>.<name>" then the first line of the message.
xml_failures() {
  local label=$1 dir=$2
  if [[ ! -d $dir ]]; then
    printf '%s: (no results)\n' "$label"
    return 0
  fi
  local files
  files=$(find "$dir" -name 'TEST-*.xml')
  if [[ -z $files ]]; then
    printf '%s: (no result files)\n' "$label"
    return 0
  fi
  local out
  out=$(printf '%s\n' "$files" | /usr/bin/python3 -c '
import sys, xml.etree.ElementTree as ET
label = sys.argv[1]
for path in sys.stdin.read().splitlines():
    if not path:
        continue
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    for s in suites:
        cls = s.attrib.get("name", "")
        for tc in s.findall("testcase"):
            name = tc.attrib.get("name", "")
            for kind in ("failure", "error"):
                node = tc.find(kind)
                if node is not None:
                    msg = (node.attrib.get("message") or (node.text or "")).strip().splitlines()
                    first = msg[0] if msg else f"<{kind}>"
                    print(f"{label}  {cls}.{name}\n    {first}")
' "$label")
  if [[ -z $out ]]; then
    printf '%s: no failures\n' "$label"
  else
    printf '%s\n' "$out"
  fi
}

server_failures() {
  if ! command -v cargo >/dev/null; then
    echo "server: (cargo not in PATH)"
    return 0
  fi
  local out
  out=$(cd "$ROOT/server" && cargo test --no-fail-fast 2>&1 || true)
  local failed
  failed=$(grep -E '^test .* FAILED$' <<<"$out" || true)
  if [[ -z $failed ]]; then
    echo "server: no failures"
    return 0
  fi
  while IFS= read -r line; do
    # line: "test mod::path::name ... FAILED"
    local name
    name=$(awk '{print $2}' <<<"$line")
    echo "server  $name"
  done <<<"$failed"
}

run_target() {
  case "$1" in
    android)       xml_failures android       "$ROOT/app/build/test-results/testDebugUnitTest" ;;
    shared-js)     xml_failures shared-js     "$ROOT/shared/build/test-results/jsBrowserTest" ;;
    shared-wasmjs) xml_failures shared-wasmjs "$ROOT/shared/build/test-results/wasmJsBrowserTest" ;;
    web)           xml_failures web           "$ROOT/web/build/test-results/jsBrowserTest" ;;
    server)        server_failures ;;
    *) echo "unknown target: $1" >&2; usage 2 ;;
  esac
}

target=${1:-all}
if [[ $target == all ]]; then
  for t in server android shared-js shared-wasmjs web; do
    run_target "$t"
  done
else
  run_target "$target"
fi
