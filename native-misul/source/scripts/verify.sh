#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
out=$(mktemp -d "${TMPDIR:-/tmp}/misul-verify.XXXXXX")
trap 'rm -rf "$out"' EXIT INT TERM
cd "$root"

"$zig" fmt --check src build.zig
"$zig" build test -Doptimize=ReleaseSafe --summary all
"$zig" build -Doptimize=ReleaseSafe --summary all
./scripts/test-cli-errors.sh
test_profiles='{"profiles":[{"id":"test-model","name":"Production script model","provider":"openai-compatible","context_window":32768,"output_limit":4096,"max_output_tokens":8192,"cache_retention":"none","cache_aggressiveness":"balanced","transport":"auto"}]}'
env MISUL_MODEL_PROFILES_JSON="$test_profiles" ./scripts/test-openai-cli.sh
env MISUL_MODEL_PROFILES_JSON="$test_profiles" ./scripts/test-native-tools.sh

if grep -RInE 'nodejs|Node\.js|"node([[:space:]]|"|$)|(^|[^A-Za-z])npm([^A-Za-z]|$)|@deepseek-ai/cordis|(^|[^A-Za-z])fx([^A-Za-z]|$)|@misul/|@mariozechner' \
  --include='*.zig' --include='build.zig*' src build.zig build.zig.zon 2>/dev/null; then
  echo dependency-cleanliness-failed >&2
  exit 1
fi

ZIG="$zig" ./scripts/package-release.sh "$out/release"
test "$(find "$out/release" -name 'misul-agent-*.tar.gz' -type f | wc -l | tr -d ' ')" = 4
test -s "$out/release/SHA256SUMS"
git -C .. diff --check -- MisulAgent

echo verify-ok
