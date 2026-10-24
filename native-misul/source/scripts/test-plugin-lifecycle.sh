#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/misul-plugin-lifecycle.XXXXXX")
work_dir="$run_dir/work"
cache_dir="$run_dir/cache"
mkdir -p "$work_dir" "$cache_dir"
trap 'rm -rf "$run_dir"' EXIT INT TERM

run_mode() {
    mode=$1
    log="$run_dir/$mode.log"
    cd "$work_dir"
    if ! "$zig" test \
        -O"$mode" \
        --cache-dir "$cache_dir/$mode" \
        --dep plugin_registry \
        -Mroot="$root/src/test/runtime_04_plugin_guard.zig" \
        -Mplugin_registry="$root/src/plugin_registry.zig" >"$log" 2>&1; then
        sed -n '1,240p' "$log" >&2
        exit 1
    fi
    sed -n '1,240p' "$log"
    rg -F 'plugin lifecycle enable disable and old generation lease survival...OK' "$log" >/dev/null
    rg -F 'plugin lifecycle upgrade reload rollback and failed activation preserve prior generation...OK' "$log" >/dev/null
    rg -F 'plugin lifecycle replays desired effect and activation crash boundaries...OK' "$log" >/dev/null
    rg -F 'full graph validation rejects invalid and overprivileged graphs...OK' "$log" >/dev/null
    rg -F 'complete graph and factory validation happen before native staging...OK' "$log" >/dev/null
    rg -F 'plugin durable replay rejects unknown fields and oversized payloads...OK' "$log" >/dev/null
    rg -F 'plugin effect failure removes partial residue and failed phase is terminal...OK' "$log" >/dev/null
    rg -F 'plugin factory and effect order is graph-derived across replay...OK' "$log" >/dev/null
    rg -F 'plugin replay rejects an effect receipt before its dependency...OK' "$log" >/dev/null
    rg -F 'durable validation reserves mutable plugin state until commit...OK' "$log" >/dev/null
    rg -F 'multi-generation replay removes superseded native effects and contexts...OK' "$log" >/dev/null
    rg -F 'plugin replay rejects trusted native implementation drift...OK' "$log" >/dev/null
    rg -F 'trusted callbacks can inspect the prior published generation...OK' "$log" >/dev/null
    rg -F 'plugin parsing staging and teardown survive every allocation failure...OK' "$log" >/dev/null
    rm -rf "$cache_dir/$mode"
}

run_mode Debug
run_mode ReleaseSafe

"$zig" fmt --check \
    "$root/src/plugin_manifest.zig" \
    "$root/src/plugin_registry.zig" \
    "$root/src/test/runtime_04_plugin_guard.zig"
sh -n "$root/scripts/test-plugin-lifecycle.sh"
if rg -n 'TODO|FIXME|placeholder|simulated success|fake response' \
    "$root/src/plugin_manifest.zig" \
    "$root/src/plugin_registry.zig" \
    "$root/src/test/runtime_04_plugin_guard.zig"; then
    printf '%s\n' 'plugin lifecycle placeholder scan failed' >&2
    exit 1
fi
rmdir "$work_dir/.zig-cache/tmp" "$work_dir/.zig-cache" 2>/dev/null || true
if find "$work_dir" -mindepth 1 -print -quit | grep -q .; then
    printf '%s\n' 'plugin lifecycle stale test state detected' >&2
    exit 1
fi

printf '%s\n' 'plugin lifecycle enable-disable-upgrade-reload-rollback: pass'
printf '%s\n' 'plugin lifecycle crash-replay desired-effect-activation: pass'
printf '%s\n' 'plugin lifecycle failed reconcile preserves prior generation: pass'
printf '%s\n' 'plugin lifecycle active lease survival: pass'
printf '%s\n' 'plugin lifecycle full graph and bounded parsing: pass'
printf '%s\n' 'plugin lifecycle partial-effect cleanup and terminal failure: pass'
printf '%s\n' 'plugin lifecycle graph-derived replay order and factory identity: pass'
printf '%s\n' 'plugin lifecycle enforced live and replay effect order: pass'
printf '%s\n' 'plugin lifecycle durable append reservation: pass'
printf '%s\n' 'plugin lifecycle multi-generation replay cleanup: pass'
printf '%s\n' 'plugin lifecycle callback re-entry: pass'
printf '%s\n' 'plugin lifecycle allocation leak and temp hygiene: pass'
printf '%s\n' 'plugin lifecycle Debug and ReleaseSafe: pass'
printf '%s\n' 'plugin lifecycle focused leaf: pass'
