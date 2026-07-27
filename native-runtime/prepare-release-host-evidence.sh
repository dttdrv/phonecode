#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$ROOT/.." && pwd)
OUTPUT=${1:-"$PROJECT_ROOT/release-evidence/0.5.1/vm-host"}
LOCK="$ROOT/sources.lock"
DOWNLOADS="$ROOT/.downloads"

fail() {
  printf 'release host evidence: %s\n' "$*" >&2
  exit 1
}

verify_sha256() {
  local file=$1 expected=$2 actual
  actual=$(shasum -a 256 "$file" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || fail "SHA-256 mismatch for $file: expected $expected, got $actual"
}

for command in awk basename cmp git mkdir mv python3 shasum tar; do
  command -v "$command" >/dev/null || fail "required command not found: $command"
done
[[ -f "$LOCK" ]] || fail "missing source lock: $LOCK"
[[ -d "$DOWNLOADS" ]] || fail "offline download cache not found: $DOWNLOADS"

OUTPUT_PARENT=$(dirname "$OUTPUT")
mkdir -p "$OUTPUT_PARENT"
TEMP=$(mktemp -d "$OUTPUT_PARENT/.vm-host.XXXXXX")
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

mkdir -p "$TEMP/sources/git" "$TEMP/sources/phonecode/native-runtime/patches"

while IFS='|' read -r name version source digest; do
  [[ -n "$name" && "${name:0:1}" != "#" ]] || continue
  case "$name" in
    android-ndk)
      [[ "$version" == "$digest" ]] || fail "Android NDK lock is not self-authenticating"
      ;;
    pkgconf|ninja|dtc)
      mirror="$DOWNLOADS/$name.git"
      bundle="$TEMP/sources/git/$name.bundle"
      [[ -d "$mirror" ]] || fail "missing offline Git mirror: $mirror"
      [[ "$(git --git-dir="$mirror" rev-parse refs/heads/phonecode)" == "$digest" ]] ||
        fail "$name mirror does not contain the locked commit"
      git -c pack.threads=1 --git-dir="$mirror" bundle create "$bundle" refs/heads/phonecode
      git bundle verify "$bundle" >/dev/null
      git bundle list-heads "$bundle" | grep -q "^$digest " ||
        fail "$bundle does not advertise locked commit $digest"
      ;;
    meson-wheel)
      archive="$DOWNLOADS/qemu-11.0.2.tar.xz"
      destination="$TEMP/sources/$(basename "$source")"
      [[ -f "$archive" ]] || fail "missing offline QEMU archive: $archive"
      tar -xOf "$archive" "qemu-11.0.2/python/wheels/$(basename "$source")" > "$destination"
      verify_sha256 "$destination" "$digest"
      ;;
    proxy-libintl)
      filename="proxy-libintl-$version.tar.gz"
      cached="$DOWNLOADS/$filename"
      destination="$TEMP/sources/$(basename "$source")"
      [[ -f "$cached" ]] || fail "missing offline source archive: $cached"
      verify_sha256 "$cached" "$digest"
      cp "$cached" "$destination"
      verify_sha256 "$destination" "$digest"
      ;;
    *)
      filename=$(basename "$source")
      cached="$DOWNLOADS/$filename"
      destination="$TEMP/sources/$filename"
      [[ -f "$cached" ]] || fail "missing offline source archive: $cached"
      verify_sha256 "$cached" "$digest"
      cp "$cached" "$destination"
      verify_sha256 "$destination" "$digest"
      ;;
  esac
done < "$LOCK"

for relative in \
  sources.lock \
  build-android-arm64.sh \
  patches/qemu-11.0.2-android.patch \
  patches/glib-2.88.2-phonecode.patch; do
  source_file="$ROOT/$relative"
  destination="$TEMP/sources/phonecode/native-runtime/$relative"
  [[ -f "$source_file" ]] || fail "missing published build input: $source_file"
  mkdir -p "$(dirname "$destination")"
  cp "$source_file" "$destination"
  cmp "$source_file" "$destination"
done

cp "$LOCK" "$TEMP/SOURCES.lock"
for relative in BUILD-METADATA PATCHES.sha256 arm64-v8a/SHA256SUMS; do
  [[ -f "$ROOT/out/$relative" ]] || fail "missing native build evidence: $ROOT/out/$relative"
  destination="$TEMP/$relative"
  mkdir -p "$(dirname "$destination")"
  cp "$ROOT/out/$relative" "$destination"
done

python3 - "$LOCK" "$TEMP/SBOM.cdx.json" "$TEMP/sources" "$TEMP/SOURCE-MANIFEST.sha256" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

lock_path = Path(sys.argv[1])
sbom_path = Path(sys.argv[2])
sources = Path(sys.argv[3])
manifest_path = Path(sys.argv[4])

locked = {}
for raw in lock_path.read_text(encoding="utf-8").splitlines():
    if not raw or raw.startswith("#"):
        continue
    name, version, source, digest = raw.split("|")
    locked[name] = {
        "name": name,
        "version": version,
        "source": source,
        "digest": digest,
    }

licenses = {
    "qemu": "GPL-2.0-only",
    "glib": "LGPL-2.1-or-later",
    "libiconv": "LGPL-2.1-or-later",
    "pcre2": "BSD-3-Clause",
    "libffi": "MIT",
    "proxy-libintl": "LGPL-2.1-or-later",
    "dtc": "GPL-2.0-or-later OR BSD-2-Clause",
}
components = []
for name in sorted(licenses):
    entry = locked[name]
    algorithm = "SHA-256" if len(entry["digest"]) == 64 else "SHA-1"
    components.append(
        {
            "type": "library",
            "bom-ref": f"pkg:generic/{name}@{entry['version']}",
            "name": name,
            "version": entry["version"],
            "externalReferences": [
                {"type": "distribution", "url": entry["source"]}
            ],
            "hashes": [{"alg": algorithm, "content": entry["digest"]}],
            "licenses": [{"expression": licenses[name]}],
        }
    )

document = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.6",
    "version": 1,
    "metadata": {
        "component": {
            "type": "application",
            "bom-ref": "pkg:generic/phonecode-vm-host@0.5.1",
            "name": "PhoneCode VM host runtime",
            "version": "0.5.1",
        }
    },
    "components": components,
}
sbom_path.write_text(
    json.dumps(document, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)

lines = []
for path in sorted(item for item in sources.rglob("*") if item.is_file()):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    lines.append(f"{digest}  {path.relative_to(sources).as_posix()}")
manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

(
  cd "$TEMP/sources"
  shasum -a 256 -c ../SOURCE-MANIFEST.sha256 >/dev/null
)

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
printf 'release host evidence: PASS (%s)\n' "$OUTPUT"
