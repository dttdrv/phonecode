#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
validator="$repo_root/legal/verify-jetbrains-mono-evidence.py"
license="$repo_root/legal/release/JetBrainsMono-OFL-1.1.txt"
provenance="$repo_root/legal/release/JetBrainsMono-PROVENANCE.json"
font_dir="$repo_root/app/src/main/res/font"

verify() {
  python3 "$validator" \
    --font-dir "$1" \
    --license "$2" \
    --provenance "$3"
}

expect_failure() {
  local description="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "expected failure: $description" >&2
    exit 1
  fi
}

verify "$font_dir" "$license" "$provenance"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
cp -R "$font_dir" "$tmp_dir/font"
cp "$license" "$tmp_dir/OFL.txt"
cp "$provenance" "$tmp_dir/provenance.json"

python3 - "$tmp_dir/provenance.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["sourceRevision"] = "0" * 40
path.write_text(json.dumps(document), encoding="utf-8")
PY
expect_failure "a substituted upstream revision" \
  verify "$tmp_dir/font" "$tmp_dir/OFL.txt" "$tmp_dir/provenance.json"

cp "$provenance" "$tmp_dir/provenance.json"
printf '\0' >>"$tmp_dir/font/jetbrainsmono_regular.ttf"
expect_failure "a modified bundled font" \
  verify "$tmp_dir/font" "$tmp_dir/OFL.txt" "$tmp_dir/provenance.json"

cp "$font_dir/jetbrainsmono_regular.ttf" "$tmp_dir/font/jetbrainsmono_regular.ttf"
printf '\nmodified\n' >>"$tmp_dir/OFL.txt"
expect_failure "a modified license text" \
  verify "$tmp_dir/font" "$tmp_dir/OFL.txt" "$tmp_dir/provenance.json"

echo "JetBrains Mono release evidence tests passed."
