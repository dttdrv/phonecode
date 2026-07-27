#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NATIVE_ROOT="$ROOT/native-runtime"
TEMP=$(mktemp -d "${TMPDIR:-/tmp}/phonecode-release-host-test.XXXXXX")
trap 'rm -rf "$TEMP"' EXIT

EVIDENCE="$TEMP/evidence"
STAGE="$TEMP/stage"

"$NATIVE_ROOT/prepare-release-host-evidence.sh" "$EVIDENCE"

test -f "$EVIDENCE/SBOM.cdx.json"
test -f "$EVIDENCE/SOURCE-MANIFEST.sha256"
test -d "$EVIDENCE/sources"
cmp "$NATIVE_ROOT/sources.lock" "$EVIDENCE/SOURCES.lock"

(
  cd "$EVIDENCE/sources"
  shasum -a 256 -c ../SOURCE-MANIFEST.sha256 >/dev/null
)

python3 - "$EVIDENCE/SBOM.cdx.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    sbom = json.load(handle)

assert sbom["bomFormat"] == "CycloneDX"
assert sbom["specVersion"] == "1.6"
components = sbom["components"]
assert [component["name"] for component in components] == [
    "dtc",
    "glib",
    "libffi",
    "libiconv",
    "pcre2",
    "proxy-libintl",
    "qemu",
]
for component in components:
    assert component["version"]
    assert component["hashes"]
    assert component["licenses"]
PY

for name in ninja pkgconf dtc; do
  git bundle verify "$EVIDENCE/sources/git/$name.bundle" >/dev/null
done

"$NATIVE_ROOT/stage-release-host-runtime.sh" "$STAGE" "$EVIDENCE"

for name in libphonecode_qemu.so libglib-2.0.so libiconv.so libpcre2-8.so; do
  cmp "$NATIVE_ROOT/out/arm64-v8a/$name" "$STAGE/jniLibs/arm64-v8a/$name"
done

for name in \
  QEMU-GPL-2.0.txt QEMU-LGPL-2.1.txt GLib-LGPL-2.1.txt libiconv-LGPL.txt \
  PCRE2.txt libffi.txt proxy-libintl.txt dtc-GPL-2.0.txt dtc-BSD-2-Clause.txt; do
  cmp "$NATIVE_ROOT/out/licenses/$name" "$STAGE/assets/licenses/vm-host/$name"
done

cmp "$EVIDENCE/SBOM.cdx.json" "$STAGE/assets/licenses/vm-host/SBOM.cdx.json"
cmp "$EVIDENCE/SOURCES.lock" "$STAGE/assets/licenses/vm-host/SOURCES.lock"
cmp "$EVIDENCE/SOURCE-MANIFEST.sha256" "$STAGE/assets/licenses/vm-host/SOURCE-MANIFEST.sha256"

touch "$STAGE/jniLibs/arm64-v8a/stale.so"
"$NATIVE_ROOT/stage-release-host-runtime.sh" "$STAGE" "$EVIDENCE"
test ! -e "$STAGE/jniLibs/arm64-v8a/stale.so"

cp "$STAGE/jniLibs/arm64-v8a/libphonecode_qemu.so" "$TEMP/staged-qemu"
touch "$STAGE/preserved-on-failure"
mv "$EVIDENCE/SBOM.cdx.json" "$EVIDENCE/SBOM.cdx.json.missing"
if "$NATIVE_ROOT/stage-release-host-runtime.sh" "$STAGE" "$EVIDENCE"; then
  printf 'staging unexpectedly accepted missing SBOM\n' >&2
  exit 1
fi
test -f "$STAGE/preserved-on-failure"
cmp "$TEMP/staged-qemu" "$STAGE/jniLibs/arm64-v8a/libphonecode_qemu.so"

printf 'release host evidence test: PASS\n'
