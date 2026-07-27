#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
LOCK="$ROOT/sources.lock"
DOWNLOADS="$ROOT/.downloads"
WORK_ROOT="$ROOT/.work"
NDK_VERSION=28.2.13676358
API=26
ABI=arm64-v8a
HOST_TRIPLE=aarch64-linux-android
NDK=${ANDROID_NDK_HOME:-"$HOME/Library/Android/sdk/ndk/$NDK_VERSION"}
if [[ -z "${PYTHON:-}" ]]; then
  if command -v python3.11 >/dev/null; then
    PYTHON=python3.11
  else
    PYTHON=python3
  fi
fi
if [[ -z "${JOBS:-}" ]]; then
  JOBS=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || printf '4')
fi
SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1704067200}

fail() {
  printf 'native runtime build: %s\n' "$*" >&2
  exit 1
}

locked() {
  local name=$1 field=$2
  awk -F '|' -v name="$name" -v field="$field" '$1 == name { print $field; found = 1 } END { if (!found) exit 1 }' "$LOCK"
}

verify_sha256() {
  local file=$1 expected=$2 actual
  actual=$(shasum -a 256 "$file" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || fail "SHA-256 mismatch for $file: expected $expected, got $actual"
}

fetch_archive() {
  local name=$1 filename=$2 url expected partial
  url=$(locked "$name" 3)
  expected=$(locked "$name" 4)
  partial="$DOWNLOADS/$filename.partial"
  if [[ ! -f "$DOWNLOADS/$filename" ]]; then
    curl --proto '=https' --tlsv1.2 --fail --location --retry 3 --output "$partial" "$url"
    verify_sha256 "$partial" "$expected"
    mv "$partial" "$DOWNLOADS/$filename"
  fi
  verify_sha256 "$DOWNLOADS/$filename" "$expected"
}

clone_locked() {
  local name=$1 destination=$2 url commit mirror
  url=$(locked "$name" 3)
  commit=$(locked "$name" 4)
  mirror="$DOWNLOADS/$name.git"
  if [[ ! -d "$mirror" ]]; then
    git init --bare "$mirror" >/dev/null
    git --git-dir="$mirror" remote add origin "$url"
  fi
  git --git-dir="$mirror" fetch --force --depth 1 origin "$commit:refs/heads/phonecode"
  git clone --quiet --no-checkout "$mirror" "$destination"
  git -C "$destination" checkout --quiet --detach "$commit"
  [[ "$(git -C "$destination" rev-parse HEAD)" == "$commit" ]] || fail "$name checkout differs from lock"
}

for command in awk curl git make patch shasum tar; do
  command -v "$command" >/dev/null || fail "required command not found: $command"
done
[[ -f "$LOCK" ]] || fail "missing $LOCK"
[[ -f "$NDK/source.properties" ]] || fail "Android NDK not found: $NDK"
grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" || fail "Android NDK must be $NDK_VERSION"
"$PYTHON" -c 'import sys; raise SystemExit(sys.version_info < (3, 11))' || fail "Python 3.11 or newer is required"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot"
CC="$TOOLCHAIN/$HOST_TRIPLE$API-clang"
CXX="$TOOLCHAIN/$HOST_TRIPLE$API-clang++"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"
[[ -x "$CC" && -x "$CXX" && -x "$AR" && -x "$STRIP" ]] || fail "NDK LLVM toolchain is incomplete"

mkdir -p "$DOWNLOADS" "$WORK_ROOT"
WORK=$(mktemp -d "$WORK_ROOT/build.XXXXXX")
SRC="$WORK/src"
BUILD="$WORK/build"
PREFIX="$WORK/prefix"
HOST_BIN="$WORK/host/bin"
HOST_PY="$WORK/host/meson"
STAGE="$WORK/stage"
OUT_STAGE="$WORK/out"
mkdir -p "$SRC" "$BUILD" "$PREFIX" "$HOST_BIN" "$HOST_PY" "$STAGE" "$OUT_STAGE/$ABI" "$OUT_STAGE/symbols/$ABI" "$OUT_STAGE/licenses"

export SOURCE_DATE_EPOCH
export LC_ALL=C
export TZ=UTC
# QEMU's decodetree generator iterates hashed Python collections. Pinning the
# hash seed makes its generated decoder sources and final ELF bit-identical.
export PYTHONHASHSEED=0

fetch_archive qemu qemu-11.0.2.tar.xz
fetch_archive glib glib-2.88.2.tar.xz
fetch_archive libiconv libiconv-1.18.tar.gz
fetch_archive pcre2 pcre2-10.47.tar.bz2
fetch_archive libffi libffi-3.5.2.tar.gz
fetch_archive proxy-libintl proxy-libintl-0.5.tar.gz
fetch_archive wheel wheel-0.46.2-py3-none-any.whl
fetch_archive packaging packaging-26.2-py3-none-any.whl

tar -xf "$DOWNLOADS/qemu-11.0.2.tar.xz" -C "$SRC"
tar -xf "$DOWNLOADS/glib-2.88.2.tar.xz" -C "$SRC"
tar -xf "$DOWNLOADS/libiconv-1.18.tar.gz" -C "$SRC"
tar -xf "$DOWNLOADS/pcre2-10.47.tar.bz2" -C "$SRC"
tar -xf "$DOWNLOADS/libffi-3.5.2.tar.gz" -C "$SRC"

QEMU_SRC="$SRC/qemu-11.0.2"
GLIB_SRC="$SRC/glib-2.88.2"
ICONV_SRC="$SRC/libiconv-1.18"
PCRE_SRC="$SRC/pcre2-10.47"
FFI_SRC="$SRC/libffi-3.5.2"

MESON_WHEEL="$QEMU_SRC/python/wheels/meson-1.10.0-py3-none-any.whl"
verify_sha256 "$MESON_WHEEL" "$(locked meson-wheel 4)"
cp "$DOWNLOADS/wheel-0.46.2-py3-none-any.whl" "$QEMU_SRC/python/wheels/"
cp "$DOWNLOADS/packaging-26.2-py3-none-any.whl" "$QEMU_SRC/python/wheels/"
"$PYTHON" - "$MESON_WHEEL" "$HOST_PY" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as wheel:
    wheel.extractall(sys.argv[2])
PY

meson() {
  PYTHONPATH="$HOST_PY" "$PYTHON" -m mesonbuild.mesonmain "$@"
}

NINJA_SRC="$SRC/ninja"
clone_locked ninja "$NINJA_SRC"
(cd "$NINJA_SRC" && "$PYTHON" configure.py --bootstrap)
cp "$NINJA_SRC/ninja" "$HOST_BIN/ninja"
PATH="$HOST_BIN:$PATH"
export PATH

PKGCONF_SRC="$SRC/pkgconf"
clone_locked pkgconf "$PKGCONF_SRC"
meson setup "$BUILD/pkgconf" "$PKGCONF_SRC" --prefix="$WORK/host" -Dtests=disabled -Ddefault_library=static
meson compile -C "$BUILD/pkgconf"
meson install -C "$BUILD/pkgconf" --no-rebuild
[[ -x "$HOST_BIN/pkgconf" ]] || fail "pkgconf install did not produce pkgconf"
ln -s pkgconf "$HOST_BIN/pkg-config"

PATH_MAP_CFLAGS="-ffile-prefix-map=$WORK=/usr/src/phonecode -fdebug-prefix-map=$WORK=/usr/src/phonecode -fmacro-prefix-map=$WORK=/usr/src/phonecode"
COMMON_CFLAGS="-O2 -fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=2 $PATH_MAP_CFLAGS"
HARDENED_LDFLAGS='-Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,--as-needed'
ORIGIN_LDFLAGS='-Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,--as-needed -Wl,-rpath,$ORIGIN'

(cd "$ICONV_SRC" && env CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$COMMON_CFLAGS" LDFLAGS="$HARDENED_LDFLAGS" \
  ./configure --host="$HOST_TRIPLE" --prefix="$PREFIX" --enable-shared --disable-static --enable-extra-encodings)
make -C "$ICONV_SRC" -j"$JOBS"
make -C "$ICONV_SRC" install

(cd "$PCRE_SRC" && env CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$COMMON_CFLAGS" \
  LDFLAGS="-Wl,--undefined-version $HARDENED_LDFLAGS" ./configure --host="$HOST_TRIPLE" --prefix="$PREFIX" \
  --enable-shared --disable-static --enable-pcre2-8 --disable-pcre2-16 --disable-pcre2-32 --disable-jit \
  --disable-pcre2grep-callout --disable-pcre2grep-jit)
make -C "$PCRE_SRC" -j"$JOBS"
make -C "$PCRE_SRC" install

PCRE_PKGCONFIG="$WORK/pkgconfig"
mkdir -p "$PCRE_PKGCONFIG"
# Keep Meson from recording the private dependency directory in GLib's
# dynamic string table. The cross-file link arguments already supply -L.
cat > "$PCRE_PKGCONFIG/libpcre2-8.pc" <<EOF
prefix=$PREFIX
includedir=\${prefix}/include

Name: libpcre2-8
Description: Pinned PCRE2 for the PhoneCode GLib build
Version: 10.47
Libs: -lpcre2-8
Cflags: -I\${includedir}
EOF

mkdir -p "$BUILD/libffi"
(cd "$BUILD/libffi" && env CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" CFLAGS="$COMMON_CFLAGS -fexceptions" \
  LDFLAGS="$HARDENED_LDFLAGS" "$FFI_SRC/configure" --host="$HOST_TRIPLE" --prefix="$PREFIX" \
  --enable-shared --disable-static --disable-multi-os-directory)
make -C "$BUILD/libffi" -j"$JOBS"
make -C "$BUILD/libffi" install

mkdir -p "$PREFIX/lib/pkgconfig"
cat > "$PREFIX/lib/pkgconfig/zlib.pc" <<EOF
prefix=$SYSROOT/usr
libdir=\${prefix}/lib/$HOST_TRIPLE/$API
includedir=\${prefix}/include

Name: zlib
Description: Android platform zlib
Version: 1.2.11
Libs: -lz
Cflags:
EOF

mkdir -p "$GLIB_SRC/subprojects/packagecache"
cp "$DOWNLOADS/proxy-libintl-0.5.tar.gz" "$GLIB_SRC/subprojects/packagecache/"
patch -p1 -d "$GLIB_SRC" < "$ROOT/patches/glib-2.88.2-phonecode.patch"

CROSS_FILE="$WORK/android-aarch64.ini"
cat > "$CROSS_FILE" <<EOF
[binaries]
ar = '$AR'
c = '$CC'
cpp = '$CXX'
ld = '$TOOLCHAIN/ld.lld'
pkg-config = '$HOST_BIN/pkg-config'
strip = '$STRIP'

[properties]
needs_exe_wrapper = true

[built-in options]
c_args = ['-O2', '-fPIC', '-fstack-protector-strong', '-D_FORTIFY_SOURCE=2', '-D__BIONIC__=1', '-ffile-prefix-map=$WORK=/usr/src/phonecode', '-fdebug-prefix-map=$WORK=/usr/src/phonecode', '-fmacro-prefix-map=$WORK=/usr/src/phonecode', '-I$PREFIX/include']
cpp_args = ['-O2', '-fPIC', '-fstack-protector-strong', '-D_FORTIFY_SOURCE=2', '-D__BIONIC__=1', '-ffile-prefix-map=$WORK=/usr/src/phonecode', '-fdebug-prefix-map=$WORK=/usr/src/phonecode', '-fmacro-prefix-map=$WORK=/usr/src/phonecode', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib', '-liconv', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1', '-Wl,--as-needed', '-Wl,-rpath,\$ORIGIN']
cpp_link_args = ['-L$PREFIX/lib', '-liconv', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1', '-Wl,--as-needed', '-Wl,-rpath,\$ORIGIN']

[host_machine]
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
system = 'android'
EOF

export PKG_CONFIG_LIBDIR="$PCRE_PKGCONFIG:$PREFIX/lib/pkgconfig"
meson setup "$BUILD/glib" "$GLIB_SRC" --cross-file="$CROSS_FILE" --wrap-mode=nodownload \
  -Ddefault_library=shared -Dintrospection=disabled -Dlibmount=disabled -Dman-pages=disabled \
  -Dtests=false -Dinstalled_tests=false -Dnls=disabled -Dselinux=disabled -Dxattr=false \
  -Ddtrace=disabled -Dsystemtap=disabled -Dsysprof=disabled -Ddocumentation=false \
  -Dglib_debug=disabled -Dlibelf=disabled -Dprefix=/usr -Dlibdir=lib
meson compile -C "$BUILD/glib" glib-2.0
meson install -C "$BUILD/glib" --no-rebuild --tags phonecode-runtime --destdir "$STAGE/glib"
GLIB_RUNTIME="$STAGE/glib/usr/lib/libglib-2.0.so"
[[ -f "$GLIB_RUNTIME" ]] || fail "GLib runtime install did not produce libglib-2.0.so"

patch -p1 -d "$QEMU_SRC" < "$ROOT/patches/qemu-11.0.2-android.patch"
clone_locked dtc "$QEMU_SRC/subprojects/dtc"

QEMU_DEPS="$WORK/qemu-deps"
mkdir -p "$QEMU_DEPS/lib/pkgconfig"
cp -L "$GLIB_RUNTIME" "$QEMU_DEPS/lib/libglib-2.0.so"
# Keep the private build path out of Meson's dependency metadata. The linker
# still receives it below, while the shipped binary retains only $ORIGIN.
cat > "$QEMU_DEPS/lib/pkgconfig/glib-2.0.pc" <<EOF
prefix=$QEMU_DEPS
libdir=\${prefix}/lib

Name: GLib
Description: Android API 26 GLib core for the PhoneCode QEMU build
Version: 2.88.2
Libs: -lglib-2.0
Cflags: -I$GLIB_SRC -I$GLIB_SRC/glib -I$BUILD/glib -I$BUILD/glib/glib -I$BUILD/glib/subprojects/proxy-libintl-0.5
EOF

mkdir -p "$BUILD/qemu"
(cd "$BUILD/qemu" && env PKG_CONFIG="$HOST_BIN/pkg-config" PKG_CONFIG_LIBDIR="$QEMU_DEPS/lib/pkgconfig" \
  PKG_CONFIG_PATH="$QEMU_DEPS/lib/pkgconfig" "$QEMU_SRC/configure" \
  --python="$PYTHON" --ninja="$HOST_BIN/ninja" --cross-prefix="$TOOLCHAIN/llvm-" --cc="$CC" --cxx="$CXX" \
  --host-cc=/usr/bin/clang --cpu=aarch64 --target-list=aarch64-softmmu --with-devices-aarch64=phonecode \
  --without-default-devices --without-default-features --enable-system --disable-user --enable-tcg --enable-pie \
  --enable-fdt=internal --with-coroutine=sigaltstack --audio-drv-list= --enable-trace-backends=nop \
  --disable-tools --disable-guest-agent --disable-docs --disable-install-blobs --disable-relocatable \
  --disable-modules --disable-plugins --disable-gio --disable-iconv --disable-gettext --disable-slirp \
  --disable-passt --disable-rust --enable-stack-protector --disable-download \
  --extra-cflags="$COMMON_CFLAGS" --extra-ldflags="$ORIGIN_LDFLAGS -L$QEMU_DEPS/lib")
"$HOST_BIN/ninja" -C "$BUILD/qemu" qemu-system-aarch64

cp "$BUILD/qemu/qemu-system-aarch64" "$OUT_STAGE/symbols/$ABI/libphonecode_qemu.so"
cp -L "$GLIB_RUNTIME" "$OUT_STAGE/symbols/$ABI/libglib-2.0.so"
cp -L "$PREFIX/lib/libiconv.so" "$OUT_STAGE/symbols/$ABI/libiconv.so"
cp -L "$PREFIX/lib/libpcre2-8.so" "$OUT_STAGE/symbols/$ABI/libpcre2-8.so"
cp "$OUT_STAGE/symbols/$ABI/"*.so "$OUT_STAGE/$ABI/"

for binary in "$OUT_STAGE/$ABI/"*.so; do
  "$STRIP" --strip-unneeded "$binary"
done

(cd "$OUT_STAGE/$ABI" && shasum -a 256 libphonecode_qemu.so libglib-2.0.so libiconv.so libpcre2-8.so > SHA256SUMS)
cp "$LOCK" "$OUT_STAGE/sources.lock"
(cd "$ROOT" && shasum -a 256 patches/*.patch) > "$OUT_STAGE/PATCHES.sha256"
{
  printf 'android_ndk=%s\n' "$NDK_VERSION"
  printf 'android_api=%s\n' "$API"
  printf 'abi=%s\n' "$ABI"
  printf 'source_date_epoch=%s\n' "$SOURCE_DATE_EPOCH"
  printf 'python=%s\n' "$("$PYTHON" --version 2>&1)"
  printf 'ninja=%s\n' "$("$HOST_BIN/ninja" --version)"
  printf 'pkgconf=%s\n' "$("$HOST_BIN/pkg-config" --version)"
} > "$OUT_STAGE/BUILD-METADATA"

cp "$QEMU_SRC/COPYING" "$OUT_STAGE/licenses/QEMU-GPL-2.0.txt"
cp "$QEMU_SRC/COPYING.LIB" "$OUT_STAGE/licenses/QEMU-LGPL-2.1.txt"
cp "$GLIB_SRC/COPYING" "$OUT_STAGE/licenses/GLib-LGPL-2.1.txt"
cp "$ICONV_SRC/COPYING.LIB" "$OUT_STAGE/licenses/libiconv-LGPL.txt"
cp "$PCRE_SRC/LICENCE.md" "$OUT_STAGE/licenses/PCRE2.txt"
cp "$FFI_SRC/LICENSE" "$OUT_STAGE/licenses/libffi.txt"
cp "$GLIB_SRC/subprojects/proxy-libintl-0.5/COPYING" "$OUT_STAGE/licenses/proxy-libintl.txt"
cp "$QEMU_SRC/subprojects/dtc/GPL" "$OUT_STAGE/licenses/dtc-GPL-2.0.txt"
cp "$QEMU_SRC/subprojects/dtc/BSD-2-Clause" "$OUT_STAGE/licenses/dtc-BSD-2-Clause.txt"
cp "$PKGCONF_SRC/COPYING" "$OUT_STAGE/licenses/pkgconf.txt"
cp "$NINJA_SRC/COPYING" "$OUT_STAGE/licenses/Ninja.txt"

"$ROOT/audit-android-arm64.sh" \
  "$OUT_STAGE/$ABI" \
  "$ROOT/arm64-v8a.SHA256SUMS" \
  "$OUT_STAGE/symbols/$ABI"
rm -rf "$ROOT/out"
mv "$OUT_STAGE" "$ROOT/out"
printf 'native runtime build: PASS (%s)\n' "$ROOT/out"
