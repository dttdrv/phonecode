#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
global_cache=${ZIG_GLOBAL_CACHE_DIR:-/private/tmp/misul-zig-global}
scratch=$(mktemp -d /private/tmp/misul-runtime-event-integration.XXXXXX)

cleanup() {
    rm -rf "$scratch"
}
trap cleanup EXIT INT TERM

run_mode() {
    mode=$1
    cwd="$scratch/cwd-$mode"
    cache="$scratch/cache-$mode"
    mkdir -p "$cwd" "$cache"
    cd "$cwd"
    env ZIG_GLOBAL_CACHE_DIR="$global_cache" "$zig" test \
        -O"$mode" \
        --cache-dir "$cache" \
        --dep misul_agent \
        -Mroot="$root/tests/runtime_integration/runtime_event_integration.zig" \
        -Mmisul_agent="$root/src/root.zig"
}

run_mode Debug
run_mode ReleaseSafe
printf '%s\n' 'runtime event integration: pass'
