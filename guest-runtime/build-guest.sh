#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE_DATE_EPOCH=1784592000
NEWC="$ROOT/tools/canonical-newc.py"

fail() {
  printf 'guest build: FAIL: %s\n' "$*" >&2
  exit 1
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

is_sha256() {
  case "$1" in
    *[!0-9a-f]*|'') return 1 ;;
  esac
  [ "${#1}" -eq 64 ]
}

is_expected_package() {
  case "$1" in
    alpine-baselayout|alpine-baselayout-data|alpine-keys|alpine-release|\
    apk-tools|busybox|busybox-binsh|ca-certificates-bundle|libapk|\
    libcrypto3|libssl3|musl|musl-utils|scanelf|ssl_client|zlib|\
    busybox-static|linux-virt|phonecode-guestd) return 0 ;;
    *) return 1 ;;
  esac
}

validate_sources_lock() {
  file=$1
  [ -f "$file" ] || fail "missing SOURCES.lock"
  grep -q '^status=unresolved$' "$file" &&
    fail "unresolved lock: SOURCES.lock"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*|schema=*|status=*|blocked_reason=*) continue ;; esac
    old_ifs=$IFS
    IFS='|'
    set -- $line
    IFS=$old_ifs
    [ "$#" -eq 5 ] ||
      fail "unauthenticated input: malformed SOURCES.lock entry"
    name=$1
    url=$3
    digest=$4
    authentication=$5
    [ -n "$name" ] || fail "unauthenticated input: empty source name"
    case "$url" in https://*) ;; *)
      fail "unauthenticated input: source URL must use HTTPS"
    esac
    is_sha256 "$digest" ||
      fail "unauthenticated input: source lacks locked SHA-256"
    case "$authentication" in
      sha256|openpgp:*|immutable-git:*) ;;
      *) fail "unauthenticated input: unsupported authentication identity" ;;
    esac
  done <"$file"
}

validate_packages_lock() {
  file=$1
  [ -f "$file" ] || fail "missing PACKAGES.lock"
  grep -q '^status=unresolved$' "$file" &&
    fail "unresolved lock: PACKAGES.lock"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*|schema=*|status=*|blocked_reason=*) continue ;; esac
    old_ifs=$IFS
    IFS='|'
    set -- $line
    IFS=$old_ifs
    [ "$#" -eq 4 ] ||
      fail "unauthenticated input: malformed PACKAGES.lock entry"
    is_expected_package "$1" || fail "unexpected package: $1"
    [ -n "$2" ] && [ -n "$3" ] ||
      fail "unauthenticated input: incomplete package identity"
    is_sha256 "$4" ||
      fail "unauthenticated input: package source lacks locked SHA-256"
  done <"$file"
}

validate_toolchain_lock() {
  file=$1
  [ -f "$file" ] || fail "missing toolchain.lock"
  grep -q '^status=unresolved$' "$file" &&
    fail "unresolved lock: toolchain.lock"
  grep -qx 'schema=phonecode-guest-toolchain-lock-v1' "$file" ||
    fail "toolchain.lock schema is not v1"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*|schema=*|status=*|blocked_reason=*) continue ;; esac
    old_ifs=$IFS
    IFS='|'
    set -- $line
    IFS=$old_ifs
    [ "$#" -eq 4 ] ||
      fail "unauthenticated input: malformed toolchain.lock entry"
    case "$3" in https://*) ;; *)
      fail "unauthenticated input: toolchain URL must use HTTPS"
    esac
    is_sha256 "$4" ||
      fail "unauthenticated input: toolchain lacks locked SHA-256"
  done <"$file"
}

validate_lock_set() {
  tree=$1
  validate_sources_lock "$tree/SOURCES.lock"
  validate_packages_lock "$tree/PACKAGES.lock"
  validate_toolchain_lock "$tree/toolchain.lock"
}

validate_initramfs_entries() {
  tree=$1
  actual=$(CDPATH= cd -- "$tree/initramfs-root" && find . -print | LC_ALL=C sort)
  expected='.
./bin
./bin/busybox
./dev
./etc
./init
./lib
./lib/modules
./lib/modules/6.18.35-0-virt
./lib/modules/6.18.35-0-virt/virtio_blk.ko
./lib/modules/6.18.35-0-virt/virtio_mmio.ko
./phonecode-guestd
./proc
./sys
./workspace'
  [ "$actual" = "$expected" ] ||
    fail "unexpected initramfs entry"
}

validate_metadata() {
  tree=$1
  while IFS= read -r path; do
    mtime=$(stat -f '%m' "$path" 2>/dev/null || stat -c '%Y' "$path")
    [ "$mtime" -eq "$SOURCE_DATE_EPOCH" ] ||
      fail "nondeterministic metadata: $path mtime=$mtime"
    mode=$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path")
    if [ -d "$path" ]; then
      expected_mode=755
    else
      case "$path" in
        */init|*/bin/busybox|*/phonecode-guestd) expected_mode=755 ;;
        *) expected_mode=644 ;;
      esac
    fi
    [ "$mode" -eq "$expected_mode" ] ||
      fail "nondeterministic metadata: $path mode=$mode"
  done <<EOF
$(find "$tree/initramfs-root" -print | LC_ALL=C sort)
EOF
}

validate_source_manifest() {
  tree=$1
  [ -f "$tree/SOURCE-MANIFEST.sha256" ] ||
    fail "missing source manifest"
  [ -d "$tree/sources" ] || fail "missing source manifest sources"
  (
    cd "$tree"
    shasum -a 256 -c SOURCE-MANIFEST.sha256 >/dev/null
  ) || fail "source manifest hash mismatch"
  manifest_paths=$(awk '{print $2}' "$tree/SOURCE-MANIFEST.sha256" | LC_ALL=C sort)
  source_paths=$(CDPATH= cd -- "$tree" && find sources -type f -print | LC_ALL=C sort)
  [ "$manifest_paths" = "$source_paths" ] ||
    fail "source manifest is incomplete"
}

validate_release_tree() {
  tree=$1
  [ -d "$tree" ] || fail "release tree does not exist: $tree"
  validate_lock_set "$tree"
  [ -f "$tree/licenses/NOTICE" ] || fail "missing license: NOTICE"
  [ -f "$tree/licenses/Apache-2.0.txt" ] ||
    fail "missing license: Apache-2.0"
  [ -f "$tree/licenses/BSD-3-Clause.txt" ] ||
    fail "missing license: BSD-3-Clause"
  [ -f "$tree/licenses/GPL-2.0-only.txt" ] ||
    fail "missing license: GPL-2.0-only"
  validate_source_manifest "$tree"
  validate_initramfs_entries "$tree"
  validate_metadata "$tree"
  [ -d "$tree/artifacts" ] || fail "missing artifacts directory"
  artifact_names=$(CDPATH= cd -- "$tree/artifacts" && find . -type f -print | sed 's|^\./||' | LC_ALL=C sort)
  expected_names='initramfs.cpio.gz
system.img
vmlinuz'
  [ "$artifact_names" = "$expected_names" ] ||
    fail "unexpected artifact"
  archive_entries=$(mktemp)
  if ! "$NEWC" verify "$tree/artifacts/initramfs.cpio.gz" \
    "$SOURCE_DATE_EPOCH" >"$archive_entries"
  then
    rm -f "$archive_entries"
    fail "nondeterministic archive"
  fi
  expected_archive_entries='.
bin
bin/busybox
dev
etc
init
lib
lib/modules
lib/modules/6.18.35-0-virt
lib/modules/6.18.35-0-virt/virtio_blk.ko
lib/modules/6.18.35-0-virt/virtio_mmio.ko
phonecode-guestd
proc
sys
workspace'
  actual_archive_entries=$(cat "$archive_entries")
  rm -f "$archive_entries"
  [ "$actual_archive_entries" = "$expected_archive_entries" ] ||
    fail "unexpected initramfs archive entry"
}

make_test_fixture() {
  tree=$1
  [ ! -e "$tree" ] || fail "fixture destination already exists"
  mkdir -p \
    "$tree/artifacts" \
    "$tree/initramfs-root/bin" \
    "$tree/initramfs-root/dev" \
    "$tree/initramfs-root/etc" \
    "$tree/initramfs-root/lib/modules/6.18.35-0-virt" \
    "$tree/initramfs-root/proc" \
    "$tree/initramfs-root/sys" \
    "$tree/initramfs-root/workspace" \
    "$tree/licenses" \
    "$tree/sources"
  printf 'fixture kernel\n' >"$tree/artifacts/vmlinuz"
  printf 'fixture initramfs\n' >"$tree/artifacts/initramfs.cpio.gz"
  printf 'fixture system\n' >"$tree/artifacts/system.img"
  printf '#!/bin/busybox sh\n' >"$tree/initramfs-root/init"
  printf 'fixture busybox\n' >"$tree/initramfs-root/bin/busybox"
  printf 'fixture daemon\n' >"$tree/initramfs-root/phonecode-guestd"
  printf 'fixture module\n' >"$tree/initramfs-root/lib/modules/6.18.35-0-virt/virtio_blk.ko"
  printf 'fixture module\n' >"$tree/initramfs-root/lib/modules/6.18.35-0-virt/virtio_mmio.ko"
  chmod 0755 \
    "$tree/initramfs-root/init" \
    "$tree/initramfs-root/bin/busybox" \
    "$tree/initramfs-root/phonecode-guestd"
  printf 'fixture source\n' >"$tree/sources/fixture.txt"
  printf 'Fixture notice\n' >"$tree/licenses/NOTICE"
  printf 'Fixture Apache license\n' >"$tree/licenses/Apache-2.0.txt"
  printf 'Fixture BSD license\n' >"$tree/licenses/BSD-3-Clause.txt"
  printf 'Fixture GPL license\n' >"$tree/licenses/GPL-2.0-only.txt"
  fixture_digest=$(sha256_file "$tree/sources/fixture.txt")
  {
    printf 'schema=phonecode-guest-sources-lock-v1\n'
    printf 'status=ready\n'
    printf 'fixture|1|https://example.invalid/fixture.tar.gz|%s|sha256\n' "$fixture_digest"
  } >"$tree/SOURCES.lock"
  {
    printf 'schema=phonecode-guest-packages-lock-v1\n'
    printf 'status=ready\n'
    for package in \
      alpine-baselayout alpine-baselayout-data alpine-keys alpine-release \
      apk-tools busybox busybox-binsh ca-certificates-bundle libapk \
      libcrypto3 libssl3 musl musl-utils scanelf ssl_client zlib \
      busybox-static linux-virt phonecode-guestd
    do
      printf '%s|fixture|fixture|%s\n' "$package" "$fixture_digest"
    done
  } >"$tree/PACKAGES.lock"
  {
    printf 'schema=phonecode-guest-toolchain-lock-v1\n'
    printf 'status=ready\n'
    printf 'fixture-tool|1|https://example.invalid/tool.tar.gz|%s\n' "$fixture_digest"
  } >"$tree/toolchain.lock"
  (
    cd "$tree"
    printf '%s  sources/fixture.txt\n' "$fixture_digest" >SOURCE-MANIFEST.sha256
  )
  find "$tree/initramfs-root" -exec env TZ=UTC touch -t 202607210000.00 {} +
  "$NEWC" create "$tree/initramfs-root" \
    "$tree/artifacts/initramfs.cpio.gz" "$SOURCE_DATE_EPOCH"
  validate_release_tree "$tree"
}

compare_release_trees() {
  left=$1
  right=$2
  if ! diff -qr "$left" "$right" >/dev/null 2>&1; then
    fail "non-identical clean builds"
  fi
}

verify_content_addressed_cache() {
  tree=$1
  cache=$2
  [ -d "$cache/sha256" ] || fail "content-addressed cache is missing sha256 namespace"
  validate_lock_set "$tree"
  digests=$(
    awk -F'|' '/^[^#].*[|]/{print $4}' \
      "$tree/SOURCES.lock" "$tree/toolchain.lock" |
      LC_ALL=C sort -u
  )
  for digest in $digests; do
    is_sha256 "$digest" ||
      fail "content-addressed cache lock digest is malformed"
    blob="$cache/sha256/$digest"
    [ -f "$blob" ] ||
      fail "content-addressed cache is missing $digest"
    actual=$(sha256_file "$blob")
    [ "$actual" = "$digest" ] ||
      fail "content-addressed cache digest mismatch: $digest"
  done
}

check_repository_contract() {
  for relative in \
    guest.config toolchain.lock sources.lock packages.lock patches/series \
    protocol-v1.md schemas/protocol-v1.schema.json \
    schemas/build-manifest-v1.schema.json expected-artifacts.json evidence/README.md
  do
    [ -f "$ROOT/$relative" ] || fail "missing $relative"
  done
  grep -qx 'schema=phonecode-guest-config-v1' "$ROOT/guest.config" ||
    fail "guest.config schema is not v1"
  grep -qx 'protocol_version=1' "$ROOT/guest.config" ||
    fail "guest.config protocol version is not v1"
  validate_lock_set "$ROOT"
  if grep -q '^status=blocked$' "$ROOT/toolchain.lock" ||
     grep -q '^status=blocked$' "$ROOT/sources.lock" ||
     grep -q '^status=blocked$' "$ROOT/packages.lock"
  then
    printf 'guest build: PASS (validated fail-closed contract; production inputs remain blocked)\n'
  else
    printf 'guest build: PASS (authenticated input contract)\n'
  fi
}

case "${1-}" in
  --check)
    [ "$#" -eq 1 ] || fail "usage: $0 --check"
    check_repository_contract
    ;;
  --make-test-fixture)
    [ "$#" -eq 2 ] || fail "usage: $0 --make-test-fixture DESTINATION"
    make_test_fixture "$2"
    ;;
  --validate-release-tree)
    [ "$#" -eq 2 ] || fail "usage: $0 --validate-release-tree TREE"
    validate_release_tree "$2"
    ;;
  --compare-release-trees)
    [ "$#" -eq 3 ] || fail "usage: $0 --compare-release-trees LEFT RIGHT"
    compare_release_trees "$2" "$3"
    ;;
  --verify-cache)
    [ "$#" -eq 3 ] || fail "usage: $0 --verify-cache LOCK_TREE CACHE"
    verify_content_addressed_cache "$2" "$3"
    ;;
  '')
    check_repository_contract
    reason=$(sed -n 's/^blocked_reason=//p' "$ROOT/sources.lock" | head -n 1)
    [ -n "$reason" ] || reason="authenticated source cache is incomplete"
    fail "release artifacts are unshipped: $reason"
    ;;
  *)
    fail "usage: $0 [--check|--make-test-fixture DESTINATION|--validate-release-tree TREE|--compare-release-trees LEFT RIGHT|--verify-cache LOCK_TREE CACHE]"
    ;;
esac
