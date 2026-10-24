#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/misul-event-store-run.XXXXXX")
cache_dir="$run_dir/cache"
work_dir="$run_dir/work"
global_cache_dir="$run_dir/global"
mkdir -p "$work_dir"
trap 'rm -rf "$run_dir"' EXIT INT TERM

mode=${1---append-replay}
case "$mode" in
  --append-replay|--recovery|--sessions|--builds)
    ;;
  *)
    echo "usage: $0 --append-replay|--recovery|--sessions|--builds" >&2
    exit 2
    ;;
esac

run_test() {
  source=$1
  optimize=$2
  cd "$work_dir"
  env ZIG_GLOBAL_CACHE_DIR="$global_cache_dir" \
    "$zig" test "$root/src/$source" --cache-dir "$cache_dir/$optimize-$source" -O "$optimize"
}

run_capability_guard() {
  optimize=$1
  cd "$work_dir"
  env ZIG_GLOBAL_CACHE_DIR="$global_cache_dir" \
    "$zig" test --dep event_store -Mroot="$root/src/test/runtime_02_capability_guard.zig" \
    -Mevent_store="$root/src/event_store.zig" --cache-dir "$cache_dir/$optimize-capability-guard" -O "$optimize"
}

case "$mode" in
  --append-replay)
    run_test event_store.zig Debug
    echo "append and replay: pass"
    ;;
  --recovery)
    run_test event_store.zig Debug
    echo "crash and tamper recovery: pass"
    ;;
  --sessions)
    run_test session.zig Debug
    run_test conversation.zig Debug
    echo "durable session branches: pass"
    ;;
  --builds)
    run_test event_store.zig Debug
    run_test event_store.zig ReleaseSafe
    run_capability_guard Debug
    run_capability_guard ReleaseSafe
    run_test session.zig Debug
    run_test session.zig ReleaseSafe
    run_test conversation.zig Debug
    run_test conversation.zig ReleaseSafe
    run_test compaction.zig Debug
    run_test compaction.zig ReleaseSafe
    echo "event and compaction builds: pass"
    ;;
esac
