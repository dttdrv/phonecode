#!/bin/sh
set -eu

input=${1:-}
lock=${2:-}
destination=${3:-}
ndk=${ANDROID_NDK_HOME:-/Users/dttdrv/Library/Android/sdk/ndk/28.2.13676358}
tools="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"

fail() {
    echo "misul-stage-error: $1" >&2
    exit 1
}

case "$input" in /*) ;; *) fail "input directory must be absolute" ;; esac
case "$lock" in /*) ;; *) fail "source lock must be absolute" ;; esac
case "$destination" in /*) ;; *) fail "destination directory must be absolute" ;; esac
[ -d "$input/arm64-v8a" ] || fail "artifact input is missing"
[ -f "$input/SOURCE-MANIFEST.sha256" ] || fail "source manifest is missing"
[ -f "$lock" ] || fail "source lock is missing"
[ -x "$tools/llvm-readelf" ] || fail "NDK readelf is missing"
[ -x "$tools/llvm-nm" ] || fail "NDK nm is missing"
[ ! -L "$destination" ] || fail "destination must not be a symbolic link"
if [ -e "$destination" ] && [ ! -f "$destination/.misul-generated" ]; then
    [ -d "$destination" ] && [ -z "$(find "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
        fail "existing destination is not an owned generated directory"
fi

expected_lock_keys='abi android_api artifact artifact_bytes artifact_sha256 exports schema source_manifest_sha256'
unsorted_lock_keys=$(awk -F= 'NF != 2 || $1 == "" || $2 == "" { exit 2 } { print $1 }' "$lock") || fail "source lock is malformed"
actual_lock_keys=$(printf '%s\n' "$unsorted_lock_keys" | sort)
[ "$actual_lock_keys" = "$(printf '%s\n' $expected_lock_keys | sort)" ] || fail "source lock keys do not match the contract"
awk -F= '{ count[$1]++ } END { for (key in count) if (count[key] != 1) exit 1 }' "$lock" || fail "source lock contains duplicate keys"

locked() {
    awk -F= -v key="$1" '$1 == key { print substr($0, length(key) + 2) }' "$lock"
}

[ "$(locked schema)" = phonecode-misul-source-lock-v1 ] || fail "source lock schema mismatch"
[ "$(locked artifact)" = libmisul.so ] || fail "artifact name mismatch"
[ "$(locked abi)" = arm64-v8a ] || fail "ABI mismatch"
[ "$(locked android_api)" = 26 ] || fail "Android API mismatch"

delivery="$input/arm64-v8a"
delivery_files=$(find "$delivery" -maxdepth 1 -type f -exec basename {} \; | sort)
[ "$delivery_files" = "MANIFEST.sha256
libmisul.so" ] || fail "artifact input contains missing or unexpected files"
artifact="$delivery/libmisul.so"
manifest="$delivery/MANIFEST.sha256"
[ "$(shasum -a 256 "$artifact" | awk '{print $1}')" = "$(locked artifact_sha256)" ] || fail "artifact SHA-256 does not match the source lock"
[ "$(stat -f '%z' "$artifact")" = "$(locked artifact_bytes)" ] || fail "artifact byte size does not match the source lock"
[ "$(shasum -a 256 "$input/SOURCE-MANIFEST.sha256" | awk '{print $1}')" = "$(locked source_manifest_sha256)" ] || fail "source manifest is stale"
grep -qx "source_manifest_sha256=$(locked source_manifest_sha256)" "$manifest" || fail "producer manifest source digest mismatch"
grep -qx "sha256=$(locked artifact_sha256)" "$manifest" || fail "producer manifest artifact digest mismatch"
grep -qx "bytes=$(locked artifact_bytes)" "$manifest" || fail "producer manifest byte size mismatch"
grep -qx 'android_api=26' "$manifest" || fail "producer manifest API mismatch"
grep -qx 'abi=arm64-v8a' "$manifest" || fail "producer manifest ABI mismatch"

header=$($tools/llvm-readelf -h -W "$artifact" 2>/dev/null) || fail "artifact is not ELF"
printf '%s\n' "$header" | grep -q 'Class:.*ELF64' || fail "artifact is not ELF64"
printf '%s\n' "$header" | grep -q 'Type:.*DYN' || fail "artifact is not ET_DYN"
printf '%s\n' "$header" | grep -q 'Machine:.*AArch64' || fail "artifact is not AArch64"
exports=$($tools/llvm-nm -D --defined-only "$artifact" 2>/dev/null | awk '{ print $3 }' | sort | paste -sd, -) || fail "artifact exports are unreadable"
[ "$exports" = "$(locked exports)" ] || fail "artifact exports do not match the source lock"

parent=$(dirname "$destination")
mkdir -p "$parent"
temporary=$(mktemp -d "$parent/.misul-stage.XXXXXX")
cleanup() {
    rm -rf "$temporary"
}
trap cleanup EXIT INT TERM
mkdir -p "$temporary/jniLibs/arm64-v8a" "$temporary/assets/misul-runtime"
cp "$artifact" "$temporary/jniLibs/arm64-v8a/libmisul.so"
cp "$manifest" "$temporary/assets/misul-runtime/MANIFEST.sha256"
cp "$input/SOURCE-MANIFEST.sha256" "$temporary/assets/misul-runtime/SOURCE-MANIFEST.sha256"
cp "$lock" "$temporary/assets/misul-runtime/SOURCES.lock"
touch "$temporary/.misul-generated"

if [ -e "$destination" ]; then
    rm -rf "$destination"
fi
mv "$temporary" "$destination"
trap - EXIT INT TERM
echo "misul-stage-ok sha256=$(locked artifact_sha256)"
