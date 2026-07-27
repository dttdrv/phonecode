# PhoneCode native VM runtime

This directory is the reproducible Android host build for PhoneCode's isolated QEMU runtime. It
does not contain or package a guest kernel, initramfs, or disk image. The Play release stays blocked
until those guest artifacts have equally pinned sources, licenses, and audits.

## Build

Requirements: macOS, Python 3.11 or newer, Xcode command-line tools, and Android NDK
`28.2.13676358`. The script bootstraps pinned Ninja and pkgconf tools, verifies every source archive,
builds for Android API 26, and emits only arm64-v8a artifacts.

```sh
ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358" \
  ./native-runtime/build-android-arm64.sh
```

Network access is used only for the URLs and exact Git commits in `sources.lock`. Downloads are
cached in `.downloads`; every archive is SHA-256 checked before extraction. Build trees live in
`.work`, and release candidates plus unstripped symbols are written under `out`.

## Audit

```sh
./native-runtime/audit-android-arm64.sh native-runtime/out/arm64-v8a
```

The audit fails closed when the runtime differs from the checked-in `arm64-v8a.SHA256SUMS`, or on
an incomplete or unexpected dependency closure, non-PIE QEMU, unsafe RUNPATH, missing
RELRO/BIND_NOW, executable stack, text relocations, known API-26-incompatible imports, or a load
segment that is not 16 KiB aligned. A successful audit is necessary but not sufficient for Play:
the complete signed AAB native graph must also pass alignment, device, lifecycle, isolation,
license, and policy validation.

## Reproducibility

Run two independent clean builds and compare the complete release, symbol, license, and metadata
trees byte for byte:

```sh
./native-runtime/verify-reproducible-android-arm64.sh
```

The build fixes `SOURCE_DATE_EPOCH`, locale, timezone, compiler path maps, install prefixes, and
Python's hash seed. The last item is required because QEMU's decoder generator otherwise emits its
generated C in a different order across processes.

## Release evidence and staging

After a verified build, generate the exact corresponding-source pack and CycloneDX SBOM entirely
from the authenticated offline cache, then atomically stage the audited host runtime for the Android
release source set:

```sh
./gradlew :app:stageReleaseHostRuntime
```

`prepare-release-host-evidence.sh` verifies each cached archive against `sources.lock`, extracts the
pinned in-tree Meson wheel, creates standalone bundles for locked Git commits, publishes the exact
PhoneCode build inputs and patches, and authenticates the result with `SOURCE-MANIFEST.sha256`.
`stage-release-host-runtime.sh` refuses partial evidence, re-runs the native and symbol audit, and
replaces the generated release directory only after every input and staged byte passes. Generated
source archives live under ignored `release-evidence/`; generated Android inputs live under
`app/build/`.

Run the focused offline integration test with:

```sh
./gradlew :app:testReleaseHostEvidence :app:verifyReleaseHostEvidence
```

These tasks cover the VM host only. They do not produce the guest kernel, initramfs, system image,
guest notices, signed AAB, device lifecycle results, or Play Console evidence.

QEMU remains a separate PIE executable launched by the isolated service. Its `.so` filename is an
Android packaging mechanism, not a JNI contract.
