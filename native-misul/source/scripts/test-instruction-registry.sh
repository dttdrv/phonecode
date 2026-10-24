#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/misul-instructions-run.XXXXXX")
cache_dir="$run_dir/cache"
work_dir="$run_dir/work"
global_cache_dir="$run_dir/global"
mkdir -p "$work_dir"
trap 'rm -rf "$run_dir"' EXIT INT TERM
cd "$work_dir"
env ZIG_GLOBAL_CACHE_DIR="$global_cache_dir" \
  "$zig" test "$root/src/instruction_registry.zig" --cache-dir "$cache_dir/debug" -O Debug
env ZIG_GLOBAL_CACHE_DIR="$global_cache_dir" \
  "$zig" test "$root/src/instruction_registry.zig" --cache-dir "$cache_dir/release" -O ReleaseSafe
if rg -n 'saveJsonl|loadJsonl' "$root/src/session.zig" "$root/src/instruction_registry.zig"; then
  echo "legacy JSONL persistence path is present" >&2
  exit 1
fi
echo "instruction registry integration: pass"
