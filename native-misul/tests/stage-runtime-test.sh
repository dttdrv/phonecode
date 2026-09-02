#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
artifact=${MISUL_TEST_ARTIFACT:-}
temporary=$(mktemp -d "${TMPDIR:-/tmp}/phonecode-misul-stage.XXXXXX")
trap 'rm -rf "$temporary"' EXIT INT TERM

[ -n "$artifact" ] || {
    echo "stage-test-error: MISUL_TEST_ARTIFACT is required" >&2
    exit 1
}
stage="$root/native-misul/stage-android-arm64.sh"
lock="$root/native-misul/sources.lock"
test -x "$stage"

expect_failure() {
    expected=$1
    shift
    if "$@" >"$temporary/failure.out" 2>&1; then
        echo "stage-test-error: command unexpectedly succeeded: $*" >&2
        exit 1
    fi
    grep -F "$expected" "$temporary/failure.out" >/dev/null || {
        echo "stage-test-error: expected '$expected', got:" >&2
        cat "$temporary/failure.out" >&2
        exit 1
    }
}

replace_lock_value() {
    source=$1
    key=$2
    value=$3
    output=$4
    awk -F= -v key="$key" -v value="$value" '
        $1 == key { print key "=" value; next }
        { print }
    ' "$source" >"$output"
}

copy_artifact() {
    name=$1
    fixture="$temporary/$name"
    mkdir -p "$fixture/arm64-v8a"
    cp "$artifact/arm64-v8a/libmisul.so" "$fixture/arm64-v8a/libmisul.so"
    cp "$artifact/arm64-v8a/MANIFEST.sha256" "$fixture/arm64-v8a/MANIFEST.sha256"
    cp "$artifact/SOURCE-MANIFEST.sha256" "$fixture/SOURCE-MANIFEST.sha256"
    printf '%s\n' "$fixture"
}

expect_failure "artifact input is missing" "$stage" "$temporary/missing" "$lock" "$temporary/missing-stage"

printf '%s\n' 'schema' >"$temporary/malformed.lock"
expect_failure "source lock is malformed" "$stage" "$artifact" "$temporary/malformed.lock" "$temporary/malformed-stage"

replace_lock_value "$lock" artifact_sha256 deadbeef "$temporary/wrong-sha.lock"
expect_failure "artifact SHA-256 does not match the source lock" "$stage" "$artifact" "$temporary/wrong-sha.lock" "$temporary/wrong-sha-stage"

replace_lock_value "$lock" source_manifest_sha256 deadbeef "$temporary/stale-source.lock"
expect_failure "source manifest is stale" "$stage" "$artifact" "$temporary/stale-source.lock" "$temporary/stale-source-stage"

extra=$(copy_artifact extra)
touch "$extra/arm64-v8a/unexpected"
expect_failure "artifact input contains missing or unexpected files" "$stage" "$extra" "$lock" "$temporary/extra-stage"

replace_lock_value "$lock" exports misul_android_open "$temporary/wrong-exports.lock"
expect_failure "artifact exports do not match the source lock" "$stage" "$artifact" "$temporary/wrong-exports.lock" "$temporary/wrong-exports-stage"

wrong_arch=$(copy_artifact wrong-arch)
cp /bin/ls "$wrong_arch/arm64-v8a/libmisul.so"
wrong_arch_sha=$(shasum -a 256 "$wrong_arch/arm64-v8a/libmisul.so" | awk '{print $1}')
wrong_arch_bytes=$(stat -f '%z' "$wrong_arch/arm64-v8a/libmisul.so")
replace_lock_value "$lock" artifact_sha256 "$wrong_arch_sha" "$temporary/wrong-arch-sha.lock"
replace_lock_value "$temporary/wrong-arch-sha.lock" artifact_bytes "$wrong_arch_bytes" "$temporary/wrong-arch.lock"
awk -v sha="$wrong_arch_sha" -v bytes="$wrong_arch_bytes" '
    /^sha256=/ { print "sha256=" sha; next }
    /^bytes=/ { print "bytes=" bytes; next }
    { print }
' "$wrong_arch/arm64-v8a/MANIFEST.sha256" >"$temporary/wrong-arch.manifest"
mv "$temporary/wrong-arch.manifest" "$wrong_arch/arm64-v8a/MANIFEST.sha256"
expect_failure "artifact is not ELF" "$stage" "$wrong_arch" "$temporary/wrong-arch.lock" "$temporary/wrong-arch-stage"

mkdir -p "$temporary/unowned"
touch "$temporary/unowned/user-file"
expect_failure "existing destination is not an owned generated directory" "$stage" "$artifact" "$lock" "$temporary/unowned"
ln -s "$temporary/unowned" "$temporary/symlink-stage"
expect_failure "destination must not be a symbolic link" "$stage" "$artifact" "$lock" "$temporary/symlink-stage"

"$stage" "$artifact" "$lock" "$temporary/staged"
cmp "$artifact/arm64-v8a/libmisul.so" "$temporary/staged/jniLibs/arm64-v8a/libmisul.so"
cmp "$artifact/arm64-v8a/MANIFEST.sha256" "$temporary/staged/assets/misul-runtime/MANIFEST.sha256"
cmp "$artifact/SOURCE-MANIFEST.sha256" "$temporary/staged/assets/misul-runtime/SOURCE-MANIFEST.sha256"
cmp "$lock" "$temporary/staged/assets/misul-runtime/SOURCES.lock"
test -f "$temporary/staged/.misul-generated"

# A second successful stage proves replacement is allowed only for our generated destination.
"$stage" "$artifact" "$lock" "$temporary/staged"
cmp "$artifact/arm64-v8a/libmisul.so" "$temporary/staged/jniLibs/arm64-v8a/libmisul.so"

echo stage-runtime-test-ok
