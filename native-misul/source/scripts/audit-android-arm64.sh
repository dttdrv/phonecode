#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
delivery=${1:-}
lock=${2:-$root/android-runtime.lock}
ndk=${ANDROID_NDK_HOME:-/Users/dttdrv/Library/Android/sdk/ndk/28.2.13676358}
tools="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
readelf="$tools/llvm-readelf"
nm="$tools/llvm-nm"

fail() {
    echo "android-audit-error: $1" >&2
    exit 1
}

[ -d "$delivery" ] || fail "delivery directory is missing"
[ -f "$lock" ] || fail "runtime lock is missing"
[ -x "$readelf" ] || fail "NDK readelf is missing"
[ -x "$nm" ] || fail "NDK nm is missing"

expected_keys='abi android_api android_ndk artifact exports schema target zig zig_archive_sha256'
actual_keys=$(awk -F= 'NF != 2 || $1 == "" || $2 == "" { exit 2 } { print $1 }' "$lock" | sort) || fail "runtime lock is malformed"
[ "$actual_keys" = "$(printf '%s\n' $expected_keys | sort)" ] || fail "runtime lock keys do not match the contract"
[ "$(awk -F= '{ count[$1]++ } END { for (key in count) if (count[key] != 1) exit 1 }' "$lock"; echo $?)" = 0 ] || fail "runtime lock contains duplicate keys"

value() {
    awk -F= -v key="$1" '$1 == key { print substr($0, length(key) + 2) }' "$lock"
}

[ "$(value schema)" = misul-android-runtime-lock-v1 ] || fail "runtime lock schema mismatch"
[ "$(value zig)" = 0.16.0 ] || fail "Zig lock mismatch"
[ "$(value android_ndk)" = 28.2.13676358 ] || fail "NDK lock mismatch"
[ "$(value android_api)" = 26 ] || fail "Android API lock mismatch"
[ "$(value target)" = aarch64-linux-android.26 ] || fail "target lock mismatch"
[ "$(value abi)" = arm64-v8a ] || fail "ABI lock mismatch"
[ "$(value artifact)" = libmisul.so ] || fail "artifact lock mismatch"

files=$(find "$delivery" -maxdepth 1 -type f -exec basename {} \; | sort)
[ "$files" = "MANIFEST.sha256
libmisul.so" ] || fail "delivery contains missing or unexpected files"
artifact="$delivery/libmisul.so"
manifest="$delivery/MANIFEST.sha256"

manifest_keys='abi android_api android_ndk artifact bytes schema sha256 source_manifest_sha256 zig'
actual_manifest_keys=$(awk -F= 'NF != 2 || $1 == "" || $2 == "" { exit 2 } { print $1 }' "$manifest" | sort) || fail "manifest is malformed"
[ "$actual_manifest_keys" = "$(printf '%s\n' $manifest_keys | sort)" ] || fail "manifest keys do not match the contract"
[ "$(awk -F= '{ count[$1]++ } END { for (key in count) if (count[key] != 1) exit 1 }' "$manifest"; echo $?)" = 0 ] || fail "manifest contains duplicate keys"

manifest_value() {
    awk -F= -v key="$1" '$1 == key { print substr($0, length(key) + 2) }' "$manifest"
}

[ "$(manifest_value schema)" = misul-android-runtime-manifest-v1 ] || fail "manifest schema mismatch"
[ "$(manifest_value artifact)" = libmisul.so ] || fail "manifest artifact mismatch"
[ "$(manifest_value zig)" = "$(value zig)" ] || fail "manifest Zig mismatch"
[ "$(manifest_value android_ndk)" = "$(value android_ndk)" ] || fail "manifest NDK mismatch"
[ "$(manifest_value android_api)" = "$(value android_api)" ] || fail "manifest API mismatch"
[ "$(manifest_value abi)" = "$(value abi)" ] || fail "manifest ABI mismatch"
[ "$(manifest_value sha256)" = "$(shasum -a 256 "$artifact" | awk '{print $1}')" ] || fail "artifact SHA-256 mismatch"
[ "$(manifest_value bytes)" = "$(stat -f '%z' "$artifact")" ] || fail "artifact byte size mismatch"
printf '%s' "$(manifest_value source_manifest_sha256)" | grep -Eq '^[0-9a-f]{64}$' || fail "source manifest digest is invalid"

header=$($readelf -h -W "$artifact" 2>/dev/null) || fail "artifact is not ELF"
printf '%s\n' "$header" | grep -q 'Class:.*ELF64' || fail "artifact is not ELF64"
printf '%s\n' "$header" | grep -q 'Type:.*DYN' || fail "artifact is not ET_DYN"
printf '%s\n' "$header" | grep -q 'Machine:.*AArch64' || fail "artifact is not AArch64"

program=$($readelf -l -W "$artifact" 2>/dev/null) || fail "program headers are unreadable"
printf '%s\n' "$program" | grep -q 'GNU_RELRO' || fail "GNU RELRO is missing"
stack=$(printf '%s\n' "$program" | awk '$1 == "GNU_STACK" { print }')
[ -n "$stack" ] || fail "GNU stack header is missing"
printf '%s\n' "$stack" | grep -Eq '[[:space:]]R?W[[:space:]]' || fail "GNU stack is not writable data"
printf '%s\n' "$stack" | grep -Eq '[[:space:]]E[[:space:]]' && fail "GNU stack is executable"
printf '%s\n' "$program" | awk '$1 == "LOAD" { alignment = $NF; sub(/^0x/, "", alignment); if (("0x" alignment) + 0 < 16384) exit 1; count++ } END { if (count == 0) exit 1 }' || fail "PT_LOAD alignment is below 16 KiB"

dynamic=$($readelf -d -W "$artifact" 2>/dev/null) || fail "dynamic section is unreadable"
printf '%s\n' "$dynamic" | grep -q 'BIND_NOW' || fail "immediate binding is missing"
printf '%s\n' "$dynamic" | grep -q 'TEXTREL' && fail "text relocations are present"
needed=$(printf '%s\n' "$dynamic" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | sort)
[ "$needed" = "libc.so
libdl.so" ] || fail "dynamic dependencies do not match the allowlist"

notes=$($readelf -n -W "$artifact" 2>/dev/null) || fail "ELF notes are unreadable"
printf '%s\n' "$notes" | grep -q 'Build ID:' || fail "GNU build ID is missing"
printf '%s\n' "$notes" | grep -q 'Android' || fail "Android identification note is missing"
printf '%s\n' "$notes" | grep -q '1a 00 00 00' || fail "Android API note is not 26"

actual_exports=$($nm -D --defined-only "$artifact" 2>/dev/null | awk '{ print $3 }' | sort | paste -sd, -) || fail "dynamic symbols are unreadable"
[ "$actual_exports" = "$(value exports)" ] || fail "exported symbols do not match the allowlist"

if strings "$artifact" | grep -Eq '/Users/|/private/|\.misul-android-build\.'; then
    fail "delivered bytes contain a local build path"
fi

echo android-runtime-audit-ok
