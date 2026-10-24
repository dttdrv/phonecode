#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
if [ ! -x "$zig" ]; then
    echo "runtime provider integration: missing pinned Zig at $zig" >&2
    exit 1
fi

scratch=$(mktemp -d /private/tmp/misul-runtime-provider-integration.XXXXXX)
cleanup() {
    rm -rf "$scratch"
}
trap cleanup EXIT HUP INT TERM

fixture="$root/tests/runtime_integration/runtime_provider_integration.zig"
"$zig" fmt --check "$fixture"

run_mode() {
    mode=$1
    cwd="$scratch/cwd-$mode"
    cache="$scratch/cache-$mode"
    global_cache="$scratch/global-cache-$mode"
    mkdir -p "$cwd" "$cache" "$global_cache"
    (
        cd "$cwd"
        env ZIG_GLOBAL_CACHE_DIR="$global_cache" "$zig" test \
            -O"$mode" \
            --cache-dir "$cache" \
            --dep misul_agent \
            -Mroot="$fixture" \
            -Mmisul_agent="$root/src/root.zig"
    )
}

run_mode Debug
run_mode ReleaseSafe
printf '%s\n' 'runtime provider integration: pass'
