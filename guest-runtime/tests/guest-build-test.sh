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
python3 - "$ROOT/schemas/build-manifest-v1.schema.json" <<'PY' || \
  fail "build manifest schema does not require one exact artifact of each name"
import json
import sys

schema = json.load(open(sys.argv[1], encoding="utf-8"))
definitions = [
    item["$ref"].rsplit("/", 1)[-1]
    for item in schema["properties"]["artifacts"]["prefixItems"]
]
names = [
    schema["$defs"][definition]["allOf"][1]["properties"]["name"]["const"]
    for definition in definitions
]
assert names == ["initramfs.cpio.gz", "system.img", "vmlinuz"]
assert "name" in schema["$defs"]["artifact"]["properties"]
PY
/bin/sh -n "$ROOT/init" || fail "purpose-built /init is not valid POSIX shell"
grep -F 'SYSTEM_CARRIER_BYTES=4023808' "$ROOT/init" >/dev/null ||
  fail "/init does not lock the sector-aligned carrier byte count"
grep -F 'SYSTEM_PAYLOAD_BYTES=4023732' "$ROOT/init" >/dev/null ||
  fail "/init does not lock the system payload byte count separately"
grep -F '3028c1fc05a52383ed0cddc535f320a285005e54ec9cd34961e94da0fc059c66' \
  "$ROOT/init" >/dev/null || fail "/init does not lock the padded carrier digest"
grep -F 'f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259' \
  "$ROOT/init" >/dev/null || fail "/init does not lock the system payload digest"
grep -F 'dd if=/dev/vda of="$SYSTEM_CARRIER" bs="$SYSTEM_CARRIER_BYTES" count=1' \
  "$ROOT/init" >/dev/null || fail "/init does not read the complete aligned carrier"
grep -F 'dd if="$SYSTEM_CARRIER" of="$SYSTEM_PAYLOAD" bs="$SYSTEM_PAYLOAD_BYTES" count=1' \
  "$ROOT/init" >/dev/null || fail "/init does not separate the authenticated payload"
grep -qx 'system_carrier_bytes=4023808' "$ROOT/guest.config" &&
  grep -qx 'system_payload_bytes=4023732' "$ROOT/guest.config" ||
  fail "guest config does not separate carrier and payload sizes"
grep -F 'mount -t tmpfs -o size=128m,nosuid,nodev tmpfs "$NEW_ROOT/workspace"' \
  "$ROOT/init" >/dev/null || fail "/workspace is not explicitly ephemeral and bounded"
grep -F 'org.phonecode.guest.0' "$ROOT/init" >/dev/null ||
  fail "/init does not bind the dedicated virtio-serial control port"
grep -F 'exec 3<>"$NEW_ROOT$CONTROL_PORT"' "$ROOT/init" >/dev/null ||
  fail "/init does not open the control port exactly once"
grep -F '<&3 >&3 2>"$NEW_ROOT/dev/console"' "$ROOT/init" >/dev/null ||
  fail "guest daemon diagnostics are not isolated on the console"
if grep -F 'exec "$BUSYBOX" sh' "$ROOT/init" >/dev/null; then
  fail "release panic path exposes a rescue shell"
fi
grep -F '"$BUSYBOX" poweroff -f' "$ROOT/init" >/dev/null ||
  fail "release panic path does not fail closed"
grep -F '|alpine-apk-rsa:alpine-devel@lists.alpinelinux.org-616ae350' \
  "$ROOT/sources.lock" >/dev/null ||
  fail "BusyBox APK does not use Alpine APK RSA authentication"
awk -F'|' '
  /^[^#].*[|]/ && $4 != "UNRETAINED" {exit 1}
' "$ROOT/packages.lock" ||
  fail "blocked package lock mislabels binary hashes as corresponding source"
grep -F 'same process group' "$ROOT/protocol-v1.md" >/dev/null &&
  grep -F 'new session' "$ROOT/protocol-v1.md" >/dev/null ||
  fail "protocol overclaims descendant process containment"

# The checked-in locks must be complete and authenticated before any producing
# command is reachable.
"$BUILDER" --check

FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT HUP INT TERM

"$BUILDER" --make-test-fixture "$FIXTURE/valid"
"$BUILDER" --validate-release-tree "$FIXTURE/valid"
fixture_digest=$(shasum -a 256 "$FIXTURE/valid/sources/fixture.txt" | awk '{print $1}')
grep -qx "phonecode-guestd|0.5.1|fixture|$fixture_digest" \
  "$FIXTURE/valid/PACKAGES.lock" ||
  fail "phonecode-guestd package contract is not release 0.5.1"
mkdir -p "$FIXTURE/cache/sha256"
cp "$FIXTURE/valid/sources/fixture.txt" "$FIXTURE/cache/sha256/$fixture_digest"
"$BUILDER" --verify-cache "$FIXTURE/valid" "$FIXTURE/cache"
printf 'corrupt\n' >"$FIXTURE/cache/sha256/$fixture_digest"
expect_rejected "content-addressed cache digest mismatch" \
  "$BUILDER" --verify-cache "$FIXTURE/valid" "$FIXTURE/cache"
cp "$FIXTURE/valid/sources/fixture.txt" "$FIXTURE/cache/sha256/$fixture_digest"
cp -pR "$FIXTURE/valid" "$FIXTURE/package-cache-miss"
sed -i.bak \
  's/^busybox[|]1.37.0-r31[|]fixture[|].*/busybox|1.37.0-r31|fixture|0000000000000000000000000000000000000000000000000000000000000000/' \
  "$FIXTURE/package-cache-miss/PACKAGES.lock"
rm "$FIXTURE/package-cache-miss/PACKAGES.lock.bak"
expect_rejected "content-addressed cache is missing 0000000000000000000000000000000000000000000000000000000000000000" \
  "$BUILDER" --verify-cache "$FIXTURE/package-cache-miss" "$FIXTURE/cache"

cp -pR "$FIXTURE/valid" "$FIXTURE/invalid-status"
sed -i.bak 's/^status=ready$/status=garbage/' "$FIXTURE/invalid-status/SOURCES.lock"
rm "$FIXTURE/invalid-status/SOURCES.lock.bak"
expect_rejected "release lock status must be exactly ready" \
  "$BUILDER" --validate-release-tree "$FIXTURE/invalid-status"

cp -pR "$FIXTURE/valid" "$FIXTURE/invalid-sources-schema"
sed -i.bak 's/^schema=phonecode-guest-sources-lock-v1$/schema=wrong/' \
  "$FIXTURE/invalid-sources-schema/SOURCES.lock"
rm "$FIXTURE/invalid-sources-schema/SOURCES.lock.bak"
expect_rejected "SOURCES.lock schema is not v1" \
  "$BUILDER" --validate-release-tree "$FIXTURE/invalid-sources-schema"

cp -pR "$FIXTURE/valid" "$FIXTURE/invalid-packages-schema"
sed -i.bak 's/^schema=phonecode-guest-packages-lock-v1$/schema=wrong/' \
  "$FIXTURE/invalid-packages-schema/PACKAGES.lock"
rm "$FIXTURE/invalid-packages-schema/PACKAGES.lock.bak"
expect_rejected "PACKAGES.lock schema is not v1" \
  "$BUILDER" --validate-release-tree "$FIXTURE/invalid-packages-schema"

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

cp -pR "$FIXTURE/valid" "$FIXTURE/missing-package"
sed -i.bak '/^phonecode-guestd[|]/d' "$FIXTURE/missing-package/PACKAGES.lock"
rm "$FIXTURE/missing-package/PACKAGES.lock.bak"
expect_rejected "package inventory is not the exact release allowlist" \
  "$BUILDER" --validate-release-tree "$FIXTURE/missing-package"

cp -pR "$FIXTURE/valid" "$FIXTURE/wrong-package-version"
sed -i.bak 's/^busybox[|]1.37.0-r31[|]/busybox|wrong|/' \
  "$FIXTURE/wrong-package-version/PACKAGES.lock"
rm "$FIXTURE/wrong-package-version/PACKAGES.lock.bak"
expect_rejected "package version does not match release contract" \
  "$BUILDER" --validate-release-tree "$FIXTURE/wrong-package-version"

cp -pR "$FIXTURE/valid" "$FIXTURE/missing-package-source"
sed -i.bak 's/^busybox[|]1.37.0-r31[|]fixture[|]/busybox|1.37.0-r31|missing-source|/' \
  "$FIXTURE/missing-package-source/PACKAGES.lock"
rm "$FIXTURE/missing-package-source/PACKAGES.lock.bak"
expect_rejected "package source is not retained in SOURCES.lock" \
  "$BUILDER" --validate-release-tree "$FIXTURE/missing-package-source"

cp -pR "$FIXTURE/valid" "$FIXTURE/unproved-signature"
sed -i.bak \
  's/[|]sha256$/|alpine-apk-rsa:alpine-devel@lists.alpinelinux.org-616ae350/' \
  "$FIXTURE/unproved-signature/SOURCES.lock"
rm "$FIXTURE/unproved-signature/SOURCES.lock.bak"
expect_rejected "release authentication proof is not retained" \
  "$BUILDER" --validate-release-tree "$FIXTURE/unproved-signature"

cp -pR "$FIXTURE/valid" "$FIXTURE/missing-build-manifest"
rm -f "$FIXTURE/missing-build-manifest/build-manifest.json"
expect_rejected "missing build manifest" \
  "$BUILDER" --validate-release-tree "$FIXTURE/missing-build-manifest"

cp -pR "$FIXTURE/valid" "$FIXTURE/duplicate-manifest-artifact"
sed -i.bak 's/"name": "system.img"/"name": "vmlinuz"/' \
  "$FIXTURE/duplicate-manifest-artifact/build-manifest.json"
rm "$FIXTURE/duplicate-manifest-artifact/build-manifest.json.bak"
expect_rejected "build manifest is invalid" \
  "$BUILDER" --validate-release-tree "$FIXTURE/duplicate-manifest-artifact"

cp -pR "$FIXTURE/valid" "$FIXTURE/missing-sbom"
rm -f "$FIXTURE/missing-sbom/SBOM.cdx.json"
expect_rejected "missing CycloneDX SBOM" \
  "$BUILDER" --validate-release-tree "$FIXTURE/missing-sbom"

cp -pR "$FIXTURE/valid" "$FIXTURE/wrong-sbom"
sed -i.bak 's/"name":"phonecode-guestd"/"name":"wrong-component"/' \
  "$FIXTURE/wrong-sbom/SBOM.cdx.json"
rm "$FIXTURE/wrong-sbom/SBOM.cdx.json.bak"
expect_rejected "CycloneDX SBOM is invalid" \
  "$BUILDER" --validate-release-tree "$FIXTURE/wrong-sbom"

cp -pR "$FIXTURE/valid" "$FIXTURE/incomplete-notice"
sed -i.bak '/^Package: phonecode-guestd 0.5.1$/d' \
  "$FIXTURE/incomplete-notice/licenses/NOTICE"
rm "$FIXTURE/incomplete-notice/licenses/NOTICE.bak"
expect_rejected "NOTICE package coverage is not exact" \
  "$BUILDER" --validate-release-tree "$FIXTURE/incomplete-notice"

cp -pR "$FIXTURE/valid" "$FIXTURE/missing-required-license"
rm -f "$FIXTURE/missing-required-license/licenses/MIT.txt"
expect_rejected "missing license: MIT" \
  "$BUILDER" --validate-release-tree "$FIXTURE/missing-required-license"

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

cp -pR "$FIXTURE/valid" "$FIXTURE/noncanonical-gzip"
python3 - "$FIXTURE/noncanonical-gzip/artifacts/initramfs.cpio.gz" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[9] = (data[9] + 1) % 256
path.write_bytes(data)
PY
expect_rejected "nondeterministic archive" \
  "$BUILDER" --validate-release-tree "$FIXTURE/noncanonical-gzip"

cp -pR "$FIXTURE/valid" "$FIXTURE/noncanonical-newc-padding"
python3 - "$FIXTURE/noncanonical-newc-padding/artifacts/initramfs.cpio.gz" <<'PY'
from pathlib import Path
import gzip
import io
import sys

path = Path(sys.argv[1])
data = bytearray(gzip.decompress(path.read_bytes()))
offset = 0
while True:
    header = data[offset:offset + 110]
    size = int(header[54:62], 16)
    namesize = int(header[94:102], 16)
    name_end = offset + 110 + namesize
    payload_start = (name_end + 3) & ~3
    if payload_start > name_end:
        data[name_end] = 1
        break
    payload_end = payload_start + size
    next_offset = (payload_end + 3) & ~3
    if next_offset > payload_end:
        data[payload_end] = 1
        break
    offset = next_offset
raw = io.BytesIO()
with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=1784592000) as stream:
    stream.write(data)
path.write_bytes(raw.getvalue())
PY
expect_rejected "nondeterministic archive" \
  "$BUILDER" --validate-release-tree "$FIXTURE/noncanonical-newc-padding"

cp -pR "$FIXTURE/valid" "$FIXTURE/substituted-archive-payload"
cp -pR "$FIXTURE/valid/initramfs-root" "$FIXTURE/substituted-root"
printf 'substituted daemon\n' >"$FIXTURE/substituted-root/phonecode-guestd"
chmod 0755 "$FIXTURE/substituted-root/phonecode-guestd"
env TZ=UTC touch -t 202607210000.00 "$FIXTURE/substituted-root/phonecode-guestd"
"$NEWC" create "$FIXTURE/substituted-root" \
  "$FIXTURE/substituted-archive-payload/artifacts/initramfs.cpio.gz" 1784592000
expect_rejected "archive payload does not match staging tree" \
  "$BUILDER" --validate-release-tree "$FIXTURE/substituted-archive-payload"

cp -pR "$FIXTURE/valid" "$FIXTURE/clean-a"
cp -pR "$FIXTURE/valid" "$FIXTURE/clean-b"
printf 'different\n' >>"$FIXTURE/clean-b/artifacts/system.img"
expect_rejected "non-identical clean builds" \
  "$BUILDER" --compare-release-trees "$FIXTURE/clean-a" "$FIXTURE/clean-b"

"$BUILDER" --compare-release-trees "$FIXTURE/clean-a" "$FIXTURE/valid"

printf 'guest build contract: PASS\n'
