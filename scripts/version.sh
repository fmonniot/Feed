#!/usr/bin/env sh
# Derives the app version from the nearest v* git tag.
# Usage: export FEED_VERSION=$(./scripts/version.sh)
set -e

TAG=$(git describe --tags --match "v*" --long 2>/dev/null || true)
if [ -z "$TAG" ]; then
    echo "0.0.0-dev"
    exit 0
fi

# "v1.2.3-0-gabcdef" → "1.2.3"  (exact tag)
# "v1.2.3-5-gabcdef" → "1.2.3-5-gabcdef"  (dev build)
S="${TAG#v}"
VERSION=$(echo "$S" | cut -d'-' -f1)
N=$(echo "$S" | cut -d'-' -f2)
HASH=$(echo "$S" | cut -d'-' -f3)

if [ "$N" = "0" ]; then
    echo "$VERSION"
else
    echo "${VERSION}-${N}-${HASH}"
fi
