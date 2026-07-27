#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
RUNTIME_DIR=${1:-"$ROOT/out/arm64-v8a"}
EXPECTED_MANIFEST=${2:-"$ROOT/arm64-v8a.SHA256SUMS"}
SYMBOL_DIR=${3:-"$ROOT/out/symbols/arm64-v8a"}
NDK_VERSION=28.2.13676358
NDK=${ANDROID_NDK_HOME:-"$HOME/Library/Android/sdk/ndk/$NDK_VERSION"}
if [[ -z "${READELF:-}" ]]; then
  case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) printf 'native runtime audit: unsupported host %s\n' "$(uname -s)" >&2; exit 1 ;;
  esac
  READELF="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf"
fi

fail() {
  printf 'native runtime audit: %s\n' "$*" >&2
  exit 1
}

[[ -x "$READELF" ]] || fail "llvm-readelf not found at $READELF"
[[ -d "$RUNTIME_DIR" ]] || fail "runtime directory not found: $RUNTIME_DIR"
[[ -f "$EXPECTED_MANIFEST" ]] || fail "expected hash manifest not found: $EXPECTED_MANIFEST"
[[ -d "$SYMBOL_DIR" ]] || fail "native symbol directory not found: $SYMBOL_DIR"
EXPECTED_MANIFEST=$(cd "$(dirname "$EXPECTED_MANIFEST")" && pwd)/$(basename "$EXPECTED_MANIFEST")

required=(
  libphonecode_qemu.so
  libglib-2.0.so
  libiconv.so
  libpcre2-8.so
)

for name in "${required[@]}"; do
  [[ -f "$RUNTIME_DIR/$name" ]] || fail "missing $name"
done

actual=$(find "$RUNTIME_DIR" -maxdepth 1 -type f -name '*.so' -exec basename {} \; | LC_ALL=C sort)
expected=$(printf '%s\n' "${required[@]}" | LC_ALL=C sort)
[[ "$actual" == "$expected" ]] || fail "unexpected .so inventory: $actual"
symbol_actual=$(find "$SYMBOL_DIR" -maxdepth 1 -type f -name '*.so' -exec basename {} \; | LC_ALL=C sort)
[[ "$symbol_actual" == "$expected" ]] || fail "unexpected native symbol inventory: $symbol_actual"
(cd "$RUNTIME_DIR" && shasum -a 256 -c "$EXPECTED_MANIFEST" >/dev/null) || \
  fail "runtime bytes differ from $EXPECTED_MANIFEST"

expected_needed() {
  case "$1" in
    libphonecode_qemu.so) printf '%s\n' libc.so libdl.so libglib-2.0.so libm.so libz.so ;;
    libglib-2.0.so) printf '%s\n' libc.so libiconv.so libm.so libpcre2-8.so ;;
    libiconv.so|libpcre2-8.so) printf '%s\n' libc.so ;;
    *) return 1 ;;
  esac
}

for name in "${required[@]}"; do
  binary="$RUNTIME_DIR/$name"
  header=$($READELF -h "$binary")
  dynamic=$($READELF -d "$binary")
  program_headers=$($READELF -lW "$binary")
  notes=$($READELF --notes "$binary")
  symbol_notes=$($READELF --notes "$SYMBOL_DIR/$name")
  symbol_sections=$($READELF -SW "$SYMBOL_DIR/$name")
  symbols=$($READELF -Ws "$binary")

  grep -q 'Machine:.*AArch64' <<<"$header" || fail "$name is not AArch64"
  grep -q 'Type:.*DYN' <<<"$header" || fail "$name is not position independent"
  grep -q 'GNU_RELRO' <<<"$program_headers" || fail "$name has no GNU_RELRO segment"
  grep -q 'BIND_NOW' <<<"$dynamic" || fail "$name does not use immediate binding"
  grep -q 'Build ID:' <<<"$notes" || fail "$name has no GNU build ID"
  build_id=$(sed -n 's/.*Build ID: //p' <<<"$notes")
  symbol_build_id=$(sed -n 's/.*Build ID: //p' <<<"$symbol_notes")
  [[ -n "$build_id" && "$symbol_build_id" == "$build_id" ]] || \
    fail "$name native symbols do not match the release build ID"
  grep -q '\.symtab' <<<"$symbol_sections" || fail "$name native symbols have no symbol table"
  ! grep -q 'TEXTREL' <<<"$dynamic" || fail "$name contains text relocations"
  ! grep -Eq 'GNU_STACK.*RWE' <<<"$program_headers" || fail "$name requests an executable stack"

  load_count=$(awk '$1 == "LOAD" { count++ } END { print count + 0 }' <<<"$program_headers")
  [[ "$load_count" -gt 0 ]] || fail "$name has no load segments"
  awk '$1 == "LOAD" && $NF != "0x4000" { exit 1 }' <<<"$program_headers" || \
    fail "$name has a load segment that is not 16 KiB aligned"

  ! grep -q '(RPATH)' <<<"$dynamic" || fail "$name contains legacy RPATH"
  runpath=$(sed -n 's/.*(RUNPATH).*\[\(.*\)\].*/\1/p' <<<"$dynamic")
  case "$name" in
    libphonecode_qemu.so|libglib-2.0.so)
      [[ "$runpath" == '$ORIGIN' ]] || fail "$name RUNPATH must be exactly \$ORIGIN"
      ;;
    *)
      [[ -z "$runpath" || "$runpath" == '$ORIGIN' ]] || fail "$name has unsafe RUNPATH $runpath"
      ;;
  esac

  needed=$(sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' <<<"$dynamic" | LC_ALL=C sort)
  wanted=$(expected_needed "$name" | LC_ALL=C sort)
  [[ "$needed" == "$wanted" ]] || fail "$name dependency closure differs: $needed"

  case "$name" in
    libphonecode_qemu.so)
      grep -q '(FLAGS_1).*PIE' <<<"$dynamic" || fail "$name is not marked PIE"
      entry=$(sed -n 's/.*Entry point address:[[:space:]]*//p' <<<"$header")
      [[ "$entry" != '0x0' && -n "$entry" ]] || fail "$name has no executable entry point"
      ;;
    *)
      soname=$(sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p' <<<"$dynamic")
      [[ "$soname" == "$name" ]] || fail "$name SONAME differs: $soname"
      ;;
  esac

  if grep -Eq 'UND.*(posix_spawn|posix_spawnp|pthread_attr_setinheritsched|shm_open|shm_unlink)' <<<"$symbols"; then
    fail "$name imports an API unavailable at Android API 26"
  fi
done

if [[ -f "$RUNTIME_DIR/SHA256SUMS" ]]; then
  (cd "$RUNTIME_DIR" && shasum -a 256 -c SHA256SUMS >/dev/null) || fail "SHA256SUMS mismatch"
fi

printf 'native runtime audit: PASS (%s)\n' "$RUNTIME_DIR"
