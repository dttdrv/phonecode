#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/Users/dttdrv/.codex/toolchains/zig-0.16.0/zig}
ndk=${ANDROID_NDK_HOME:-/Users/dttdrv/Library/Android/sdk/ndk/28.2.13676358}
output=$(mktemp -d "${TMPDIR:-/tmp}/misul-android-contract.XXXXXX")
trap 'rm -rf "$output"' EXIT INT TERM

cd "$root"
test -x scripts/audit-android-arm64.sh
test -x scripts/build-android-arm64.sh
ANDROID_NDK_HOME="$ndk" ZIG="$zig" ./scripts/build-android-arm64.sh "$output/runtime"
./scripts/audit-android-arm64.sh "$output/runtime/arm64-v8a"

expect_failure() {
    name=$1
    expected=$2
    delivery=$3
    lock=${4:-$root/android-runtime.lock}
    if result=$(ANDROID_NDK_HOME="$ndk" ./scripts/audit-android-arm64.sh "$delivery" "$lock" 2>&1); then
        echo "negative fixture unexpectedly passed: $name" >&2
        exit 1
    fi
    printf '%s\n' "$result" | grep -Fq "$expected" || {
        echo "negative fixture failed at the wrong invariant: $name" >&2
        printf '%s\n' "$result" >&2
        exit 1
    }
}

expect_failure missing 'delivery directory is missing' "$output/missing"

printf 'schema=broken\n' > "$output/malformed.lock"
expect_failure malformed-lock 'runtime lock keys do not match the contract' "$output/runtime/arm64-v8a" "$output/malformed.lock"

cp -R "$output/runtime/arm64-v8a" "$output/wrong-hash"
sed 's/^sha256=.*/sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
    "$output/wrong-hash/MANIFEST.sha256" > "$output/wrong-hash/MANIFEST.next"
mv "$output/wrong-hash/MANIFEST.next" "$output/wrong-hash/MANIFEST.sha256"
expect_failure wrong-hash 'artifact SHA-256 mismatch' "$output/wrong-hash"

cp -R "$output/runtime/arm64-v8a" "$output/wrong-architecture"
cp /bin/ls "$output/wrong-architecture/libmisul.so"
wrong_architecture_sha=$(shasum -a 256 "$output/wrong-architecture/libmisul.so" | awk '{print $1}')
wrong_architecture_bytes=$(stat -f '%z' "$output/wrong-architecture/libmisul.so")
sed -e "s/^sha256=.*/sha256=$wrong_architecture_sha/" -e "s/^bytes=.*/bytes=$wrong_architecture_bytes/" \
    "$output/wrong-architecture/MANIFEST.sha256" > "$output/wrong-architecture/MANIFEST.next"
mv "$output/wrong-architecture/MANIFEST.next" "$output/wrong-architecture/MANIFEST.sha256"
expect_failure wrong-architecture 'artifact is not ELF' "$output/wrong-architecture"

cp -R "$output/runtime/arm64-v8a" "$output/extra-file"
touch "$output/extra-file/unexpected"
expect_failure extra-file 'delivery contains missing or unexpected files' "$output/extra-file"

sed 's/^exports=.*/exports=misul_android_abi_version/' "$root/android-runtime.lock" > "$output/wrong-exports.lock"
expect_failure wrong-exports 'exported symbols do not match the allowlist' "$output/runtime/arm64-v8a" "$output/wrong-exports.lock"

echo android-build-contract-ok
