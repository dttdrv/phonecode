#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
global_cache=${ZIG_GLOBAL_CACHE_DIR:-/private/tmp/misul-zig-global}
scratch=$(mktemp -d /private/tmp/misul-session-rpc.XXXXXX)

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
        -Mroot="$root/tests/runtime_integration/session_rpc_integration.zig" \
        -Mmisul_agent="$root/src/root.zig"

    run_rpc_witness "$mode" "$cache" 'session new does not commit before its response allocation succeeds'
    run_rpc_witness "$mode" "$cache" 'session start does not commit or swap state before its response allocation succeeds'
    run_rpc_witness "$mode" "$cache" 'session switch does not swap adapter state before its response allocation succeeds'
    run_runtime_witness "$mode" "$cache" 'advertised assistant message identifiers remain reserved while active after reap and after restart'
    run_rpc_witness "$mode" "$cache" 'stdio validates the complete JSON-RPC envelope before concurrent prompt dispatch'
    run_rpc_witness "$mode" "$cache" 'stdio live message identifiers are the exact durable history identifiers'
}

run_runtime_witness() {
    witness_mode=$1
    witness_cache=$2
    witness_filter=$3
    require_witness "$root/src/runtime.zig" "$witness_filter"
    env ZIG_GLOBAL_CACHE_DIR="$global_cache" "$zig" test \
        -O"$witness_mode" \
        --cache-dir "$witness_cache" \
        "$root/src/runtime.zig" \
        --test-filter "$witness_filter"
}

run_rpc_witness() {
    witness_mode=$1
    witness_cache=$2
    witness_filter=$3
    require_witness "$root/src/rpc_server.zig" "$witness_filter"
    env ZIG_GLOBAL_CACHE_DIR="$global_cache" "$zig" test \
        -O"$witness_mode" \
        --cache-dir "$witness_cache" \
        "$root/src/rpc_server.zig" \
        --test-filter "$witness_filter"
}

require_witness() {
    witness_source=$1
    witness_name=$2
    if ! grep -Fq "test \"$witness_name\"" "$witness_source"; then
        printf '%s\n' "missing focused witness: $witness_name" >&2
        exit 1
    fi
}

run_mode Debug
run_mode ReleaseSafe
printf '%s\n' 'durable session RPC: pass'
