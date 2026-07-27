#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$ROOT/.." && pwd)
OUTPUT=${1:-"$PROJECT_ROOT/app/build/generated/phonecodeReleaseHostRuntime"}
EVIDENCE=${2:-"$PROJECT_ROOT/release-evidence/0.5.1/vm-host"}
RUNTIME="$ROOT/out/arm64-v8a"
SYMBOLS="$ROOT/out/symbols/arm64-v8a"
MANIFEST="$ROOT/arm64-v8a.SHA256SUMS"

fail() {
  printf 'release host staging: %s\n' "$*" >&2
  exit 1
}

runtime_names=(
  libphonecode_qemu.so
  libglib-2.0.so
  libiconv.so
  libpcre2-8.so
)
license_names=(
  QEMU-GPL-2.0.txt
  QEMU-LGPL-2.1.txt
  GLib-LGPL-2.1.txt
  libiconv-LGPL.txt
  PCRE2.txt
  libffi.txt
  proxy-libintl.txt
  dtc-GPL-2.0.txt
  dtc-BSD-2-Clause.txt
)

for name in "${runtime_names[@]}"; do
  [[ -f "$RUNTIME/$name" ]] || fail "missing runtime input: $RUNTIME/$name"
  [[ -f "$SYMBOLS/$name" ]] || fail "missing symbol input: $SYMBOLS/$name"
done
for name in "${license_names[@]}"; do
  [[ -f "$ROOT/out/licenses/$name" ]] || fail "missing license input: $ROOT/out/licenses/$name"
  [[ $(wc -c < "$ROOT/out/licenses/$name") -ge 128 ]] || fail "incomplete license input: $name"
done
for name in SBOM.cdx.json SOURCES.lock SOURCE-MANIFEST.sha256; do
  [[ -f "$EVIDENCE/$name" ]] || fail "missing release evidence: $EVIDENCE/$name"
done
[[ -d "$EVIDENCE/sources" ]] || fail "missing corresponding sources: $EVIDENCE/sources"
cmp "$ROOT/sources.lock" "$EVIDENCE/SOURCES.lock" ||
  fail "release evidence source lock differs from native-runtime/sources.lock"

(
  cd "$EVIDENCE/sources"
  shasum -a 256 -c ../SOURCE-MANIFEST.sha256 >/dev/null
) || fail "release evidence source manifest does not match corresponding sources"

python3 - "$ROOT/sources.lock" "$EVIDENCE/SBOM.cdx.json" <<'PY'
import json
from pathlib import Path
import sys

lock = {}
for raw in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if not raw or raw.startswith("#"):
        continue
    name, version, source, digest = raw.split("|")
    lock[name] = (version, digest)

document = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
assert document["bomFormat"] == "CycloneDX"
assert document["specVersion"] == "1.6"
components = document["components"]
expected = {"qemu", "glib", "libiconv", "pcre2", "libffi", "proxy-libintl", "dtc"}
indexed = {component["name"]: component for component in components}
assert indexed.keys() == expected
for name, component in indexed.items():
    version, digest = lock[name]
    assert component["version"] == version
    assert any(item["content"] == digest for item in component["hashes"])
    assert component["licenses"]
PY

"$ROOT/audit-android-arm64.sh" "$RUNTIME" "$MANIFEST" "$SYMBOLS"

OUTPUT_PARENT=$(dirname "$OUTPUT")
mkdir -p "$OUTPUT_PARENT"
TEMP=$(mktemp -d "$OUTPUT_PARENT/.phonecode-release-host.XXXXXX")
BACKUP=
COMMITTED=false
cleanup() {
  if [[ "$COMMITTED" != true ]]; then
    rm -rf "$TEMP"
    if [[ -n "$BACKUP" && -e "$BACKUP" && ! -e "$OUTPUT" ]]; then
      mv "$BACKUP" "$OUTPUT"
    fi
  fi
}
trap cleanup EXIT

RUNTIME_DEST="$TEMP/jniLibs/arm64-v8a"
LICENSE_DEST="$TEMP/assets/licenses/vm-host"
mkdir -p "$RUNTIME_DEST" "$LICENSE_DEST"
for name in "${runtime_names[@]}"; do
  cp "$RUNTIME/$name" "$RUNTIME_DEST/$name"
  cmp "$RUNTIME/$name" "$RUNTIME_DEST/$name"
done
for name in "${license_names[@]}"; do
  cp "$ROOT/out/licenses/$name" "$LICENSE_DEST/$name"
  cmp "$ROOT/out/licenses/$name" "$LICENSE_DEST/$name"
done
for name in SBOM.cdx.json SOURCES.lock SOURCE-MANIFEST.sha256; do
  cp "$EVIDENCE/$name" "$LICENSE_DEST/$name"
  cmp "$EVIDENCE/$name" "$LICENSE_DEST/$name"
done

"$ROOT/audit-android-arm64.sh" "$RUNTIME_DEST" "$MANIFEST" "$SYMBOLS"

actual_runtime=$(find "$RUNTIME_DEST" -maxdepth 1 -type f -exec basename {} \; | LC_ALL=C sort)
expected_runtime=$(printf '%s\n' "${runtime_names[@]}" | LC_ALL=C sort)
[[ "$actual_runtime" == "$expected_runtime" ]] || fail "staged runtime inventory differs"
actual_licenses=$(find "$LICENSE_DEST" -maxdepth 1 -type f -exec basename {} \; | LC_ALL=C sort)
expected_licenses=$(printf '%s\n' "${license_names[@]}" SBOM.cdx.json SOURCES.lock SOURCE-MANIFEST.sha256 | LC_ALL=C sort)
[[ "$actual_licenses" == "$expected_licenses" ]] || fail "staged license inventory differs"

if [[ -e "$OUTPUT" ]]; then
  BACKUP="$OUTPUT.previous.$$"
  [[ ! -e "$BACKUP" ]] || fail "backup path already exists: $BACKUP"
  mv "$OUTPUT" "$BACKUP"
fi
mv "$TEMP" "$OUTPUT"
COMMITTED=true
if [[ -n "$BACKUP" ]]; then
  rm -rf "$BACKUP"
fi
printf 'release host staging: PASS (%s)\n' "$OUTPUT"
