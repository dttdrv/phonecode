#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
REFERENCE=$(mktemp -d "${TMPDIR:-/tmp}/phonecode-native-reference.XXXXXX")
trap 'rm -rf "$REFERENCE"' EXIT

"$ROOT/build-android-arm64.sh"
cp -R "$ROOT/out" "$REFERENCE/out"
"$ROOT/build-android-arm64.sh"

diff -qr "$REFERENCE/out" "$ROOT/out"
printf 'native runtime reproducibility: PASS (%s)\n' "$ROOT/out"
