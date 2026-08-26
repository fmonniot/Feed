#!/usr/bin/env bash
# Guard against BUG-64: an unanchored .gitignore rule (bare `name/` with no
# leading slash and no interior slash) matches a directory at ANY depth, not
# just the repo root. `data/` was written to ignore the root `data/` docker
# test volume but also silently swallowed new files under the `data` Kotlin
# packages in shared/ and web/ — twice, during BUG-63 parts 1 and 2, both
# worked around with `git add -f`.
#
# This script checks that a probe path in each real source `data` package is
# NOT ignored, and that the intended docker volume path still IS ignored.
# Run it whenever .gitignore or one of the probed source roots changes.
#
# Usage:
#   scripts/gitignore-guard.sh
#
# Exit code: 0 if all checks pass, 1 otherwise.

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

case "${1:-}" in
  -h|--help)
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
esac

status=0

# Probe paths that must NOT be ignored (real source roots). These files
# don't need to exist on disk — `git check-ignore` only tests pattern
# matching against the path.
must_not_be_ignored=(
  "shared/src/commonMain/kotlin/eu/monniot/feed/shared/data/__probe__.kt"
  "shared/src/commonTest/kotlin/eu/monniot/feed/shared/data/__probe__.kt"
  "web/src/jsMain/kotlin/eu/monniot/feed/web/data/__probe__.kt"
  "web/src/jsTest/kotlin/eu/monniot/feed/web/data/__probe__.kt"
)

for p in "${must_not_be_ignored[@]}"; do
  if git check-ignore -q "$p"; then
    printf '✗ %s is unexpectedly ignored — check .gitignore for an unanchored rule (see BUG-64)\n' "$p" >&2
    git check-ignore -v "$p" >&2 || true
    status=1
  else
    printf '✓ %s is not ignored\n' "$p"
  fi
done

# Positive check: the original intent of the `data/` rule (repo-root docker
# test volume) must still be honored, so a fix doesn't over-correct.
must_be_ignored="data/whatever"
if git check-ignore -q "$must_be_ignored"; then
  printf '✓ %s is still ignored (root docker volume)\n' "$must_be_ignored"
else
  printf '✗ %s is no longer ignored — the /data/ rule was over-corrected\n' "$must_be_ignored" >&2
  status=1
fi

if [[ $status -eq 0 ]]; then
  printf '✓ gitignore-guard passed\n'
else
  printf '✗ gitignore-guard failed\n' >&2
fi

exit $status
