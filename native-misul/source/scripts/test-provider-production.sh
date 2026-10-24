#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
zig=/tmp/misul-zig-0.16.0/zig
if [ ! -x "$zig" ]; then
    echo "provider production: missing pinned Zig at $zig" >&2
    exit 1
fi

run_dir=$(mktemp -d /private/tmp/misul-provider-production.XXXXXX)
trap 'rm -rf "$run_dir"' EXIT HUP INT TERM
cache_dir="$run_dir/zig-cache"
global_cache_dir="$run_dir/zig-global-cache"
fixture="$project_dir/tests/runtime_03/provider_fixture.zig"
provider="$project_dir/src/openai_compatible.zig"
protocol="$project_dir/src/protocol.zig"
runtime_fixture="$project_dir/scripts/test-runtime-03-production.sh"

run_fixture() {
    mode=$1
    optimize=${2-}
    cache_label=${optimize:-Debug}
    (
        cd "$run_dir"
        "$zig" run ${optimize:+-O"$optimize"} \
            --dep provider=provider \
            --dep protocol=protocol \
            -Mroot="$fixture" \
            --dep protocol.zig=protocol \
            -Mprovider="$provider" \
            -Mprotocol="$protocol" \
            --cache-dir "$cache_dir/fixture-$cache_label" \
            --global-cache-dir "$global_cache_dir/fixture-$cache_label" \
            -- "$mode"
    )
}

expect_fixture() {
    expected=$1
    mode=$2
    optimize=${3-}
    output=$(run_fixture "$mode" "$optimize")
    if [ "$output" != "$expected" ]; then
        echo "provider production: expected '$expected', got '$output'" >&2
        exit 1
    fi
    printf '%s\n' "$output"
}

run_source_witnesses() {
    optimize=$1
    if ! output=$( (
        cd "$project_dir"
        "$zig" test -O"$optimize" src/openai_compatible.zig \
            --cache-dir "$cache_dir/source-openai-$optimize" \
            --global-cache-dir "$global_cache_dir/source-openai-$optimize"
        "$zig" test -O"$optimize" src/model_catalog.zig \
            --cache-dir "$cache_dir/source-model-catalog-$optimize" \
            --global-cache-dir "$global_cache_dir/source-model-catalog-$optimize"
        "$zig" test -O"$optimize" src/provider_auth.zig \
            --cache-dir "$cache_dir/source-provider-auth-$optimize" \
            --global-cache-dir "$global_cache_dir/source-provider-auth-$optimize"
    ) 2>&1
    ); then
        printf '%s\n' "$output" >&2
        return 1
    fi
    printf 'provider source witnesses (%s): pass\n' "$optimize"
}

mode=${1-}
case "$mode" in
    --stream)
        expect_fixture 'provider streaming: pass' --stream
        ;;
    --request-controls)
        expect_fixture 'provider request controls: pass' --request-controls
        ;;
    --failures)
        expect_fixture 'provider failure semantics: pass' --failures
        ;;
    --invalid-delta-fields)
        expect_fixture 'provider invalid delta fields: pass' --invalid-delta-fields
        ;;
    --terminal-semantics)
        expect_fixture 'provider terminal semantics: pass' --terminal-semantics
        ;;
    --tool-fragments)
        expect_fixture 'provider nullable tool fragments: pass' --tool-fragments
        ;;
    --deepseek-usage)
        expect_fixture 'provider DeepSeek usage: pass' --deepseek-usage
        ;;
    --reliability)
        expect_fixture 'provider reliability semantics: pass' --reliability
        ;;
    --blocked-read-deadline)
        expect_fixture 'provider blocked read deadline interruption: pass' --blocked-read-deadline
        ;;
    --malformed-stall)
        expect_fixture 'provider malformed stream settlement: pass' --malformed-stall
        ;;
    --live-small-event)
        expect_fixture 'provider live small SSE delivery: pass' --live-small-event
        ;;
    --done-keep-open)
        expect_fixture 'provider DONE keep-open settlement: pass' --done-keep-open
        ;;
    --registry-credential)
        expect_fixture 'provider registry credential boundary: pass' --registry-credential
        ;;
    --metadata)
        expect_fixture 'provider rendered request metadata: pass' --metadata
        ;;
    --runtime)
        if [ ! -x "$runtime_fixture" ]; then
            echo 'provider runtime integration: lead-owned Runtime fixture is unavailable (red)' >&2
            exit 1
        fi
        "$runtime_fixture"
        ;;
    --builds)
        for optimize in Debug ReleaseSafe; do
            expect_fixture 'provider streaming: pass' --stream "$optimize"
            expect_fixture 'provider request controls: pass' --request-controls "$optimize"
            expect_fixture 'provider failure semantics: pass' --failures "$optimize"
            expect_fixture 'provider invalid delta fields: pass' --invalid-delta-fields "$optimize"
            expect_fixture 'provider terminal semantics: pass' --terminal-semantics "$optimize"
            expect_fixture 'provider nullable tool fragments: pass' --tool-fragments "$optimize"
            expect_fixture 'provider DeepSeek usage: pass' --deepseek-usage "$optimize"
            expect_fixture 'provider reliability semantics: pass' --reliability "$optimize"
            expect_fixture 'provider blocked read deadline interruption: pass' --blocked-read-deadline "$optimize"
            expect_fixture 'provider malformed stream settlement: pass' --malformed-stall "$optimize"
            expect_fixture 'provider live small SSE delivery: pass' --live-small-event "$optimize"
            expect_fixture 'provider DONE keep-open settlement: pass' --done-keep-open "$optimize"
            expect_fixture 'provider registry credential boundary: pass' --registry-credential "$optimize"
            expect_fixture 'provider rendered request metadata: pass' --metadata "$optimize"
            run_source_witnesses "$optimize"
        done
        printf '%s\n' 'provider builds: pass'
        ;;
    *)
        echo "usage: $0 --stream|--request-controls|--failures|--invalid-delta-fields|--terminal-semantics|--tool-fragments|--deepseek-usage|--reliability|--blocked-read-deadline|--malformed-stall|--live-small-event|--done-keep-open|--registry-credential|--metadata|--runtime|--builds" >&2
        exit 2
        ;;
esac
