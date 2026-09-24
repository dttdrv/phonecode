# Native runtime evidence

Status: **Agent runtime locked in source; command runtime not shipping.**

## Misul agent runtime (`libmisul.so`)

| Field | Value (checked 24 September 2026) |
| --- | --- |
| File | `app/src/main/jniLibs/arm64-v8a/libmisul.so`, 2,856,144 bytes |
| SHA-256 | `e0f6e43e4caf091fc0087bd980996c8aad8863a1c3ae7c7ed78b227876137bf4`; matches `native-misul/sources.lock`, `assets/misul-runtime/MANIFEST.sha256`, and `VENDORED_CHECKSUMS` |
| Source manifest SHA-256 | `f98b4b7cd6507d411a2cc482c47df902db87292754f6bb00552871218b5529f0` |
| Toolchain | Zig 0.16.0, NDK 28.2.13676358, API 26, arm64-v8a |
| License | MIT, Misul Computing |

It is installed by Play as part of the app, so it is not downloaded executable code. `THIRD_PARTY.md`
listed an older hash until 24 September 2026; it now matches the lock.

PENDING: 16 KB alignment check of `libmisul.so` inside the signed AAB, native debug symbols uploaded
to Play, and a run on a 16 KB page-size device.

## Command runtime

Release builds select the isolated QEMU backend and verify its packaged guest first
(`runtime/ShellBackendFactory.kt`). No guest is packaged, so the backend reports unavailable and the
`bash` and `process` tools are not offered. The PRoot and Alpine prototype lives only in
`app/src/debug/` and must never ship.

The host build evidence from 22 July 2026 is recorded in
[`../0.5.1/native-runtime-evidence.md`](../0.5.1/native-runtime-evidence.md). Still open, per
`app/build.gradle.kts` and `legal/RELEASE_COMPLIANCE.md`: licensed reproducible guest, workspace
transport, corresponding source, SBOM and notices, and device lifecycle evidence.

Shipping 1.0.0 without the command runtime is a product decision. If it ships later, reopen Data
safety (package sources), the FGS description, the listing, and the Device and Network Abuse review
(code must run inside the VM).
