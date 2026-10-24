#!/bin/sh
set -eu

version="${MISUL_AGENT_VERSION:-0.1-alpha}"
repository="${MISUL_AGENT_REPOSITORY:-https://github.com/Misul-Computing/Terminal}"
bin_dir="${MISUL_AGENT_BIN_DIR:-$HOME/.local/bin}"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT HUP INT TERM

case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux) os=linux ;;
  *) echo "Misul Agent does not provide a binary for $(uname -s)." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=aarch64 ;;
  x86_64|amd64) arch=x86_64 ;;
  *) echo "Misul Agent does not provide a binary for $(uname -m)." >&2; exit 1 ;;
esac

asset="misul-agent-${os}-${arch}.tar.gz"
base="$repository/releases/download/v$version"
curl -fsSL "$base/$asset" -o "$temporary/$asset"
curl -fsSL "$base/SHA256SUMS" -o "$temporary/SHA256SUMS"
(
  cd "$temporary"
  expected="$(grep "  $asset\$" SHA256SUMS || true)"
  [ -n "$expected" ] || { echo "No checksum found for $asset." >&2; exit 1; }
  printf '%s\n' "$expected" | shasum -a 256 -c -
  tar -xzf "$asset"
)

mkdir -p "$bin_dir"
install -m 755 "$temporary/misul" "$bin_dir/misul"
printf 'Misul Agent %s installed at %s/misul\n' "$version" "$bin_dir"
