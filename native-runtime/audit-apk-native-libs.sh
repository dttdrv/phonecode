#!/usr/bin/env bash
set -euo pipefail

ARTIFACT=${1:?usage: audit-apk-native-libs.sh APK_OR_AAB}
NDK_VERSION=28.2.13676358
NDK=${ANDROID_NDK_HOME:-"${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/$NDK_VERSION"}

fail() {
  printf 'APK native audit: %s\n' "$*" >&2
  exit 1
}

if [[ -z "${READELF:-}" ]]; then
  case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) fail "unsupported host $(uname -s)" ;;
  esac
  READELF="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf"
fi

[[ -f "$ARTIFACT" ]] || fail "artifact not found: $ARTIFACT"
[[ -x "$READELF" ]] || fail "llvm-readelf not found at $READELF"
case "$ARTIFACT" in
  *.apk) entries=$(unzip -Z1 "$ARTIFACT" 'lib/*/*.so'); strict=false ;;
  *.aab) entries=$(unzip -Z1 "$ARTIFACT" 'base/lib/*/*.so'); strict=true ;;
  *) fail "expected an .apk or .aab artifact" ;;
esac
[[ -n "$entries" ]] || fail "artifact contains no native libraries"
TEMP=$(mktemp -d "${TMPDIR:-/tmp}/phonecode-apk-native.XXXXXX")
trap 'find "$TEMP" -type f -delete; rmdir "$TEMP"' EXIT

while IFS= read -r entry; do
  [[ "$entry" == lib/arm64-v8a/*.so || "$entry" == base/lib/arm64-v8a/*.so ]] || \
    fail "unexpected delivered ABI: $entry"
  binary="$TEMP/${entry//\//_}"
  unzip -p "$ARTIFACT" "$entry" > "$binary"
  header=$($READELF -h "$binary")
  dynamic=$($READELF -d "$binary")
  program_headers=$($READELF -lW "$binary")

  grep -q 'Machine:.*AArch64' <<<"$header" || fail "$entry is not AArch64"
  ! grep -q 'TEXTREL' <<<"$dynamic" || fail "$entry contains text relocations"
  ! grep -Eq 'GNU_STACK.*RWE' <<<"$program_headers" || fail "$entry requests an executable stack"
  load_count=$(awk '$1 == "LOAD" { count++ } END { print count + 0 }' <<<"$program_headers")
  [[ "$load_count" -gt 0 ]] || fail "$entry has no load segments"
  awk '$1 == "LOAD" && $NF != "0x4000" { exit 1 }' <<<"$program_headers" || \
    fail "$entry has a load segment that is not 16 KiB aligned"
  if [[ "$strict" == true ]]; then
    notes=$($READELF --notes "$binary")
    grep -q 'Type:.*DYN' <<<"$header" || fail "$entry is not position independent"
    grep -q 'Build ID:' <<<"$notes" || fail "$entry has no GNU build ID"
    grep -q 'GNU_RELRO' <<<"$program_headers" || fail "$entry has no GNU_RELRO segment"
    grep -q 'BIND_NOW' <<<"$dynamic" || fail "$entry does not use immediate binding"
  fi
done <<<"$entries"

printf 'Android artifact native audit: PASS (%s)\n' "$ARTIFACT"
