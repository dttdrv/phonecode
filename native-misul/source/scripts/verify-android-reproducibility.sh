#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/Users/dttdrv/.codex/toolchains/zig-0.16.0/zig}
ndk=${ANDROID_NDK_HOME:-/Users/dttdrv/Library/Android/sdk/ndk/28.2.13676358}
temporary=$(mktemp -d "${TMPDIR:-/tmp}/misul-android-repro.XXXXXX")
trap 'rm -rf "$temporary"' EXIT INT TERM

cd "$root"
ANDROID_NDK_HOME="$ndk" ZIG="$zig" ./scripts/build-android-arm64.sh "$temporary/first"
ANDROID_NDK_HOME="$ndk" ZIG="$zig" ./scripts/build-android-arm64.sh "$temporary/second"

cmp "$temporary/first/arm64-v8a/libmisul.so" "$temporary/second/arm64-v8a/libmisul.so"
cmp "$temporary/first/arm64-v8a/MANIFEST.sha256" "$temporary/second/arm64-v8a/MANIFEST.sha256"
cmp "$temporary/first/SOURCE-MANIFEST.sha256" "$temporary/second/SOURCE-MANIFEST.sha256"

tools="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for name in first second; do
    artifact="$temporary/$name/arm64-v8a/libmisul.so"
    "$tools/llvm-nm" -D --defined-only "$artifact" | awk '{ print $3 }' | sort > "$temporary/$name.exports"
    "$tools/llvm-readelf" -d -W "$artifact" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | sort > "$temporary/$name.needed"
    "$tools/llvm-readelf" -n -W "$artifact" | sed -n 's/.*Build ID: //p' > "$temporary/$name.build-id"
done

cmp "$temporary/first.exports" "$temporary/second.exports"
cmp "$temporary/first.needed" "$temporary/second.needed"
cmp "$temporary/first.build-id" "$temporary/second.build-id"

artifact_sha=$(shasum -a 256 "$temporary/first/arm64-v8a/libmisul.so" | awk '{print $1}')
manifest_sha=$(shasum -a 256 "$temporary/first/arm64-v8a/MANIFEST.sha256" | awk '{print $1}')
source_sha=$(shasum -a 256 "$temporary/first/SOURCE-MANIFEST.sha256" | awk '{print $1}')
build_id=$(cat "$temporary/first.build-id")
echo "android-reproducibility-ok artifact=$artifact_sha manifest=$manifest_sha source=$source_sha build_id=$build_id"
