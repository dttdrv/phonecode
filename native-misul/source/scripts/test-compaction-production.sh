#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/misul-compaction-run.XXXXXX")
cache_dir="$run_dir/cache"
work_dir="$run_dir/work"
global_cache_dir="$run_dir/global"
mkdir -p "$work_dir"
trap 'rm -rf "$run_dir"' EXIT INT TERM
cd "$work_dir"
env ZIG_GLOBAL_CACHE_DIR="$global_cache_dir" \
  "$zig" test "$root/src/compaction.zig" --cache-dir "$cache_dir/debug" -O Debug
env ZIG_GLOBAL_CACHE_DIR="$global_cache_dir" \
  "$zig" test "$root/src/compaction.zig" --cache-dir "$cache_dir/release" -O ReleaseSafe
env ZIG_GLOBAL_CACHE_DIR="$global_cache_dir" \
  "$zig" build-exe --dep compaction -Mroot="$root/src/test/runtime_02_production_guard.zig" \
  -Mcompaction="$root/src/compaction.zig" \
  --cache-dir "$cache_dir/guard" -O ReleaseSafe -femit-bin="$run_dir/legacy-guard"
"$run_dir/legacy-guard"
echo "compaction production integration: pass"
