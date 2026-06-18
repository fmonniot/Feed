#!/usr/bin/env bash
# shot-android.sh — capture a screenshot of the running Android app for design
# verification (ticket #75). Navigation is manual: drive the app to the screen
# you want, then run this with a name.
#
# Prerequisites:
#   - An emulator or device is connected (`adb devices`).
#   - The debug app is installed:  ./gradlew :app:installDebug
#
# Usage: scripts/shot-android.sh <name>     e.g. scripts/shot-android.sh unread
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
OUT="$ROOT/build/.shots/android"

NAME="${1:-}"
[ -n "$NAME" ] || { echo "usage: shot-android.sh <name>" >&2; exit 2; }

# Resolve adb: PATH, then the standard SDK locations.
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  for cand in "$ANDROID_HOME/platform-tools/adb" "$ANDROID_SDK_ROOT/platform-tools/adb" \
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
"$ADB" exec-out screencap -p > "$OUT/$NAME.png"
echo "✓ $OUT/$NAME.png"
