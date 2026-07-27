# Bundled native prototype provenance

The binaries in this directory are development-prototype artifacts. They are not approved for a
Google Play, App Store, GitHub release, or other binary distribution.

## PRoot and loader

| File | SHA-256 |
| --- | --- |
| `arm64-v8a/libproot.so` | `42a0313a04296d913279c55eccabf9082d31d0f8caf1960079d92342c4629e0c` |
| `arm64-v8a/libproot-loader.so` | `12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0` |

The binaries contain PRoot and statically linked talloc code. PRoot states GPL-2.0-or-later in the
binary's own version notice. talloc is LGPL-3.0-or-later.

The previously cited build repository is
`green-green-avk/build-proot-android@01f83b8841358450c78333d1b33ab30d4943bec4`. That revision pins
PRoot `0.15_release`, talloc `2.1.14`, and Android NDK `23.2.8568313`. Its published aarch64 archive
contains these different hashes:

| Published file | SHA-256 |
| --- | --- |
| `root/bin/proot` | `297abc237247682a84a3fd4283b28f69506502b4b852faf71fd726fb5d955d60` |
| `root/libexec/proot/loader` | `de0ace1adc76ab0555b3dbbfbff0e78c1ac14017b255262707867aea70c49437` |

It therefore is not the corresponding source and build recipe for the committed binaries. The
former claims that these files were built from talloc 2.4.3 with NDK 28.2 and 16 KiB alignment were
not supported by committed source, patches, commands, or a reproducible build and have been
withdrawn.

Before any distribution, either remove both files from the artifact or replace them with a
reproducible build whose complete source, Android patches, build scripts, toolchain pin, license
texts, and output hashes are published beside the binary. Reproducing only the upstream archive is
not sufficient because its output does not match.

## Removed standalone BusyBox binaries

The former `libbusybox.so` files are deleted. The Alpine root filesystem still contains BusyBox and
other GPL-covered programs; their corresponding-source obligations remain separate and are tracked
in `THIRD_PARTY.md` and `legal/RELEASE_COMPLIANCE.md`.
