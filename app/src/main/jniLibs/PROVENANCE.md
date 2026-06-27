# Bundled native binaries - provenance

These are prebuilt third-party binaries vendored into `jniLibs` (the only place Android lets an app
exec a binary from - see `EnvironmentBootstrap`). Each is verifiable against the source below: re-fetch
from the pinned commit and compare the SHA-256.

## proot (Linux userland via PRoot) - arm64-v8a only

- **What:** `libproot.so` is PRoot (userspace chroot+bind via ptrace); `libproot-loader.so` is its
  statically-linked loader. Together they run a full Alpine Linux rootfs on-device under Android's W^X
  (`mmap PROT_EXEC` survives where `execve` is denied), so `apk add python3 ...` works.
- **Source:** https://github.com/green-green-avk/build-proot-android (the PRoot build behind the
  AnotherTerm terminal app's "Linux under PRoot" feature).
- **Pinned commit:** `01f83b8841358450c78333d1b33ab30d4943bec4` (2023-06-23), `packages/proot-android-aarch64.tar.gz`.
- **Files + SHA-256 (verified at vendor time):**
  - `root/bin/proot` -> `arm64-v8a/libproot.so` -> `297abc237247682a84a3fd4283b28f69506502b4b852faf71fd726fb5d955d60`
  - `root/libexec/proot/loader` -> `arm64-v8a/libproot-loader.so` -> `de0ace1adc76ab0555b3dbbfbff0e78c1ac14017b255262707867aea70c49437`
- **Licenses:** PRoot (GPL-2.0), talloc (LGPL-3.0) - see the source repo.
- **Scope:** arm64-v8a only. Other ABIs intentionally have no proot and fall back to busybox at runtime
  (`EnvironmentBootstrap.buildLinux` returns null), keeping the unaudited-binary surface minimal.
- **Residual risk:** this is a prebuilt binary, not built from source here, so its bytes are trusted on
  the pin + hash, not independently audited. To harden a release, rebuild from the source repo's scripts.

To re-verify:

    curl -fsSL https://raw.githubusercontent.com/green-green-avk/build-proot-android/01f83b8841358450c78333d1b33ab30d4943bec4/packages/proot-android-aarch64.tar.gz | tar -xz
    shasum -a 256 root/bin/proot root/libexec/proot/loader

## busybox - arm64-v8a

- **What:** `arm64-v8a/libbusybox.so` is a static aarch64 busybox - the on-device POSIX shell toolkit,
  invoked through a `busybox`-named symlink and per-applet symlinks. (The previous arm64 binary was
  mistakenly a 32-bit ARM build, which does not run on arm64-only devices and broke the whole shell.)
- **Source:** Alpine Linux `busybox-static` package, v3.21 aarch64.
- **Pinned:** `busybox-static-1.37.0-r14.apk`, file `bin/busybox.static`.
- **SHA-256:** `e383c8bc25a1137b8ee88718cc6df1f1e84c54521d6045fc837385995dcdf031`
- **License:** GPL-2.0.
- The armeabi-v7a and x86_64 busybox binaries are pre-existing and the correct architecture for their ABI.

To re-verify:

    curl -fsSL https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/busybox-static-1.37.0-r14.apk | tar -xz
    shasum -a 256 bin/busybox.static
