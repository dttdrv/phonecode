#!/bin/sh
set -eu

zig="${ZIG:-zig}"
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
out="${1:-release}"
case "$out" in
  ""|/|.|..|"$root") echo "Refusing unsafe output directory: $out" >&2; exit 1 ;;
esac
rm -rf "$out"
mkdir -p "$out"

build_one() {
  target="$1"
  os="$2"
  arch="$3"
  prefix="$out/prefix-$os-$arch"
  "$zig" build -Dtarget="$target" -Doptimize=ReleaseSafe --prefix "$prefix"
  archive="misul-agent-$os-$arch.tar.gz"
  tar -C "$prefix/bin" -czf "$out/$archive" misul
  rm -rf "$prefix"
}

cd "$root"
build_one aarch64-macos macos aarch64
build_one x86_64-macos macos x86_64
build_one aarch64-linux-gnu linux aarch64
build_one x86_64-linux-gnu linux x86_64
(
  cd "$out"
  shasum -a 256 misul-agent-*.tar.gz > SHA256SUMS
)
