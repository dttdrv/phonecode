#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/Users/dttdrv/.codex/toolchains/zig-0.16.0/zig}

cd "$root"
"$zig" fmt --check src/android_api.zig build.zig
"$zig" build android-api-test -Doptimize=ReleaseSafe --summary all

echo android-api-contract-ok
