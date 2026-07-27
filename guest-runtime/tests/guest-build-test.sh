#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILDER="$ROOT/build-guest.sh"
NEWC="$ROOT/tools/canonical-newc.py"

fail() {
  printf 'guest build contract: FAIL: %s\n' "$*" >&2
  exit 1
}

expect_rejected() {
  expected=$1
  shift
  log=$(mktemp)
  if "$@" >"$log" 2>&1; then
    rm -f "$log"
    fail "accepted invalid case: $expected"
  fi
  grep -F "$expected" "$log" >/dev/null ||
    {
      sed -n '1,120p' "$log" >&2
      rm -f "$log"
      fail "invalid case did not report: $expected"
    }
  rm -f "$log"
}

[ -x "$BUILDER" ] || fail "build-guest.sh is not executable"
[ -x "$NEWC" ] || fail "missing canonical newc writer"
[ -f "$ROOT/init" ] || fail "missing purpose-built /init"
/bin/sh -n "$ROOT/init" || fail "purpose-built /init is not valid POSIX shell"
grep -F 'SYSTEM_IMAGE_BYTES=4023732' "$ROOT/init" >/dev/null ||
  fail "/init does not lock the system image byte count"
grep -F 'f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259' \
  "$ROOT/init" >/dev/null || fail "/init does not lock the system image digest"
grep -F 'mount -t tmpfs -o size=128m,nosuid,nodev tmpfs "$NEW_ROOT/workspace"' \
  "$ROOT/init" >/dev/null || fail "/workspace is not explicitly ephemeral and bounded"
grep -F 'org.phonecode.guest.0' "$ROOT/init" >/dev/null ||
  fail "/init does not bind the dedicated virtio-serial control port"

# The checked-in locks must be complete and authenticated before any producing
# command is reachable.
"$BUILDER" --check

FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT HUP INT TERM

"$BUILDER" --make-test-fixture "$FIXTURE/valid"
"$BUILDER" --validate-release-tree "$FIXTURE/valid"
fixture_digest=$(shasum -a 256 "$FIXTURE/valid/sources/fixture.txt" | awk '{print $1}')
mkdir -p "$FIXTURE/cache/sha256"
cp "$FIXTURE/valid/sources/fixture.txt" "$FIXTURE/cache/sha256/$fixture_digest"
"$BUILDER" --verify-cache "$FIXTURE/valid" "$FIXTURE/cache"
printf 'corrupt\n' >"$FIXTURE/cache/sha256/$fixture_digest"
expect_rejected "content-addressed cache digest mismatch" \
  "$BUILDER" --verify-cache "$FIXTURE/valid" "$FIXTURE/cache"

cp -pR "$FIXTURE/valid" "$FIXTURE/unresolved"
printf '\nstatus=unresolved\n' >>"$FIXTURE/unresolved/toolchain.lock"
expect_rejected "unresolved lock" \
  "$BUILDER" --validate-release-tree "$FIXTURE/unresolved"

cp -pR "$FIXTURE/valid" "$FIXTURE/unauthenticated"
printf '\nbad|1|http://example.invalid/bad.tar.gz|-\n' \
  >>"$FIXTURE/unauthenticated/SOURCES.lock"
expect_rejected "unauthenticated input" \
  "$BUILDER" --validate-release-tree "$FIXTURE/unauthenticated"

cp -pR "$FIXTURE/valid" "$FIXTURE/unexpected-package"
printf '\nunexpected|1|bad-source|%064d\n' 0 \
  >>"$FIXTURE/unexpected-package/PACKAGES.lock"
expect_rejected "unexpected package" \
  "$BUILDER" --validate-release-tree "$FIXTURE/unexpected-package"

cp -pR "$FIXTURE/valid" "$FIXTURE/missing-license"
rm "$FIXTURE/missing-license/licenses/NOTICE"
expect_rejected "missing license" \
  "$BUILDER" --validate-release-tree "$FIXTURE/missing-license"

cp -pR "$FIXTURE/valid" "$FIXTURE/missing-source-manifest"
rm "$FIXTURE/missing-source-manifest/SOURCE-MANIFEST.sha256"
expect_rejected "missing source manifest" \
  "$BUILDER" --validate-release-tree "$FIXTURE/missing-source-manifest"

cp -pR "$FIXTURE/valid" "$FIXTURE/unexpected-entry"
printf 'untrusted\n' >"$FIXTURE/unexpected-entry/initramfs-root/unexpected"
expect_rejected "unexpected initramfs entry" \
  "$BUILDER" --validate-release-tree "$FIXTURE/unexpected-entry"

cp -pR "$FIXTURE/valid" "$FIXTURE/nondeterministic-metadata"
touch "$FIXTURE/nondeterministic-metadata/initramfs-root/init"
expect_rejected "nondeterministic metadata" \
  "$BUILDER" --validate-release-tree "$FIXTURE/nondeterministic-metadata"

cp -pR "$FIXTURE/valid" "$FIXTURE/nondeterministic-mode"
chmod 0777 "$FIXTURE/nondeterministic-mode/initramfs-root/init"
expect_rejected "nondeterministic metadata" \
  "$BUILDER" --validate-release-tree "$FIXTURE/nondeterministic-mode"

cp -pR "$FIXTURE/valid" "$FIXTURE/nondeterministic-archive"
"$NEWC" create "$FIXTURE/nondeterministic-archive/initramfs-root" \
  "$FIXTURE/nondeterministic-archive/artifacts/initramfs.cpio.gz" 1784592001
expect_rejected "nondeterministic archive" \
  "$BUILDER" --validate-release-tree "$FIXTURE/nondeterministic-archive"

cp -pR "$FIXTURE/valid" "$FIXTURE/clean-a"
cp -pR "$FIXTURE/valid" "$FIXTURE/clean-b"
printf 'different\n' >>"$FIXTURE/clean-b/artifacts/system.img"
expect_rejected "non-identical clean builds" \
  "$BUILDER" --compare-release-trees "$FIXTURE/clean-a" "$FIXTURE/clean-b"

"$BUILDER" --compare-release-trees "$FIXTURE/clean-a" "$FIXTURE/valid"

printf 'guest build contract: PASS\n'
