#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output=${1:-}
zig=${ZIG:-}
ndk=${ANDROID_NDK_HOME:-}

fail() {
    echo "android-build-error: $1" >&2
    exit 1
}

[ -n "$output" ] || fail "missing output directory"
case "$output" in /*) ;; *) fail "output directory must be absolute" ;; esac
[ ! -e "$output" ] || fail "output directory already exists"
case "$zig" in /*) ;; *) fail "ZIG must be an absolute path" ;; esac
case "$ndk" in /*) ;; *) fail "ANDROID_NDK_HOME must be an absolute path" ;; esac
[ -x "$zig" ] || fail "ZIG is not executable"
[ -d "$ndk" ] || fail "Android NDK is missing"
[ "$($zig version)" = "0.16.0" ] || fail "Zig version mismatch"
grep -qx 'Pkg.Revision = 28.2.13676358' "$ndk/source.properties" || fail "Android NDK version mismatch"

prebuilt="$ndk/toolchains/llvm/prebuilt/darwin-x86_64"
sysroot="$prebuilt/sysroot"
strip="$prebuilt/bin/llvm-strip"
[ -x "$strip" ] || fail "NDK strip tool is missing"

parent=$(dirname "$output")
mkdir -p "$parent"
temporary=$(mktemp -d "$parent/.misul-android-build.XXXXXX")
cleanup() {
    rm -rf "$temporary"
}
trap cleanup EXIT INT TERM

libc_config="$temporary/android-libc.txt"
{
    printf 'include_dir=%s\n' "$sysroot/usr/include"
    printf 'sys_include_dir=%s\n' "$sysroot/usr/include/aarch64-linux-android"
    printf 'crt_dir=%s\n' "$sysroot/usr/lib/aarch64-linux-android/26"
    printf 'msvc_lib_dir=\n'
    printf 'kernel32_lib_dir=\n'
    printf 'gcc_dir=\n'
} > "$libc_config"
"$zig" libc "$libc_config"

prefix="$temporary/prefix"
cd "$root"
source_manifest="$temporary/source.sha256"
(
    find . -type f \
        ! -path './.zig-cache/*' \
        ! -path './zig-out/*' \
        -print0 | sort -z | xargs -0 shasum -a 256
) > "$source_manifest"
source_digest=$(shasum -a 256 "$source_manifest" | awk '{print $1}')
"$zig" build android-library \
    -Dtarget=aarch64-linux-android.26 \
    -Doptimize=ReleaseSafe \
    -Dmisul-build-id="$source_digest" \
    --libc "$libc_config" \
    --cache-dir "$temporary/cache" \
    --global-cache-dir "$temporary/global-cache" \
    --prefix "$prefix" \
    --summary all

unstripped="$prefix/lib/libmisul.so"
[ -f "$unstripped" ] || fail "Zig did not produce libmisul.so"
mkdir -p "$temporary/publish/arm64-v8a" "$temporary/publish/symbols/arm64-v8a"
cp "$unstripped" "$temporary/publish/symbols/arm64-v8a/libmisul.so"
cp "$unstripped" "$temporary/publish/arm64-v8a/libmisul.so"
"$strip" --strip-debug "$temporary/publish/arm64-v8a/libmisul.so"

artifact="$temporary/publish/arm64-v8a/libmisul.so"
artifact_digest=$(shasum -a 256 "$artifact" | awk '{print $1}')
artifact_bytes=$(stat -f '%z' "$artifact")
cat > "$temporary/publish/arm64-v8a/MANIFEST.sha256" <<EOF
schema=misul-android-runtime-manifest-v1
artifact=libmisul.so
sha256=$artifact_digest
bytes=$artifact_bytes
source_manifest_sha256=$source_digest
zig=0.16.0
android_ndk=28.2.13676358
android_api=26
abi=arm64-v8a
EOF
cp "$source_manifest" "$temporary/publish/SOURCE-MANIFEST.sha256"

"$root/scripts/audit-android-arm64.sh" "$temporary/publish/arm64-v8a"
mv "$temporary/publish" "$output"
echo "android-build-ok sha256=$artifact_digest bytes=$artifact_bytes source=$source_digest"
