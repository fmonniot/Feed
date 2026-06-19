#!/usr/bin/env bash
# shot-android.sh — capture a screenshot of the running Android app for design
# verification (ticket #75). Navigation is manual: drive the app to the screen
# you want, then run this with a scenario name from scripts/shots/SCENARIOS.md.
#
# Prerequisites:
#   - An emulator or device is connected (`adb devices`).
#   - The debug app is installed:  ./gradlew :app:installDebug
#
# Usage:
#   scripts/shot-android.sh <scenario>     e.g. scripts/shot-android.sh unread
#   scripts/shot-android.sh --list         list scenarios + Android nav
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
OUT="$ROOT/build/.shots/android"
CATALOG="$ROOT/scripts/shots/SCENARIOS.md"

# Pull the first-column scenario name from every catalog table row (` `name` `).
catalog_names() {
  sed -n 's/^| `\([a-z][a-z-]*\)` |.*/\1/p' "$CATALOG"
}

# List the main-table scenarios with their Android nav. Bounded to the "##
# Scenarios" table (the reference-only states table has a different shape), and
# rows whose Android nav starts with "—" (not capturable on Android) are skipped.
list_scenarios() {
  awk -F'|' '
    /^## Scenarios/ { inmain = 1; next }
    /^### / { inmain = 0 }
    inmain && /^\| `[a-z]/ {
      name = $2; nav = $4;
      gsub(/[` ]/, "", name);
      gsub(/^ +| +$/, "", nav); gsub(/\*\*/, "", nav);
      if (nav !~ /^—/ && nav != "") printf "  %-14s %s\n", name, nav;
    }' "$CATALOG"
}

NAME="${1:-}"
case "$NAME" in
  --list|-l|--help|-h)
    echo "Android-capturable scenarios (from scripts/shots/SCENARIOS.md):"
    list_scenarios
    exit 0 ;;
  "")
    echo "usage: shot-android.sh <scenario>   (see --list)" >&2; exit 2 ;;
esac

if ! catalog_names | grep -qx "$NAME"; then
  echo "! '$NAME' is not a known scenario (see --list) — capturing anyway." >&2
fi

# Resolve adb: PATH, then the standard SDK locations.
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  for cand in "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
              "$HOME/Library/Android/sdk/platform-tools/adb"; do
    [ -x "$cand" ] && { ADB="$cand"; break; }
  done
fi
[ -n "$ADB" ] || { echo "✗ adb not found (set ANDROID_HOME or add platform-tools to PATH)" >&2; exit 1; }

if [ -z "$("$ADB" devices | sed '1d' | grep -w device || true)" ]; then
  echo "✗ No device/emulator connected — start an emulator and ./gradlew :app:installDebug" >&2
  exit 1
fi

mkdir -p "$OUT"
# On multi-display devices `screencap -p` prepends a "[Warning] Multiple displays
# …" line to stdout, corrupting the PNG. Strip everything before the PNG magic so
# the output is valid regardless of device. (Captures the first/default display.)
TMP="$(mktemp -t feed-shot.XXXXXX)"
trap 'rm -f "$TMP"' EXIT
"$ADB" exec-out screencap -p > "$TMP"
python3 - "$TMP" "$OUT/$NAME.png" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
i = data.find(b"\x89PNG\r\n\x1a\n")
if i < 0:
    sys.exit("✗ no PNG header in screencap output: " + data[:80].decode("utf-8", "replace"))
open(sys.argv[2], "wb").write(data[i:])
PY
echo "✓ $OUT/$NAME.png"
