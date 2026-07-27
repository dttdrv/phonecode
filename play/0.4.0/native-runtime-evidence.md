# Native runtime foundation evidence

Status: **PASS, source-only host foundation; BLOCKED for the shipping guest and signed AAB.**

This record separates verified QEMU host work from evidence that does not yet exist. It is not a
release approval and does not cover a Play-delivered artifact.

## Reproducible host build

| Field | Recorded value |
| --- | --- |
| Evidence date | 22 July 2026 |
| Target | Android arm64-v8a, API 26 baseline |
| Toolchain | Android NDK `28.2.13676358` |
| QEMU | `11.0.2` |
| Reproduction | Two clean builds in different randomized work directories; complete `out` trees compared byte for byte with no differences |
| Reproduction command | `./native-runtime/verify-reproducible-android-arm64.sh` |
| Audit command | `./native-runtime/audit-android-arm64.sh native-runtime/out/arm64-v8a` |
| Audit result | PASS for the exact checked-in manifest; a modified but otherwise valid ELF was separately confirmed to fail the hash gate |

The auditor verifies the exact four-file inventory and hashes, AArch64 type, QEMU PIE marking and
entry point, dependency closure, build IDs, RELRO, immediate binding, non-executable stacks, absence
of text relocations, safe runtime paths, 16 KiB load alignment, and a denylist of known API
26-incompatible imports. That import denylist is not a complete API compatibility proof.

## Exact stripped host hashes

| File | SHA-256 |
| --- | --- |
| `libphonecode_qemu.so` | `433b33b1cf00fe53b35d5d621db5a19c8a801bae9f87a554eb81d9708e9284ec` |
| `libglib-2.0.so` | `01ac901adcb7e2e58a054f1a1fcfb7bbe3cd6adc64a9d2eb43e51c823b3fea8d` |
| `libiconv.so` | `7ef3f474dc27a94d915c5cee957c9011942edeb8aa44c6e14248c73e6804dbac` |
| `libpcre2-8.so` | `e2cdd8d3c925493a58c8e7173130cec581376296f5714d59ae5d2daac7851a31` |

The authoritative checked-in manifest is `native-runtime/arm64-v8a.SHA256SUMS`.

## Limited Android boot proof

The exact QEMU host bytes above were pushed to an API 34 arm64 emulator and launched with one virtual
CPU and 256 MiB RAM. A nonshipping proof kernel/initramfs emitted `PHONECODE_VM_BOOT_OK`. The host
then sent `SIGTERM`; a subsequent process lookup found no QEMU process.

| Proof input | SHA-256 |
| --- | --- |
| Nonshipping kernel | `47970e0ee0478fe5c60824a89f162d5a353fa29466e5d3bddb0f9c506f1ed756` |
| Nonshipping initramfs | `e530da460998be9029223f6c74e9025cac70f1254e2f41d4caa1f1dc2f7fc104` |

This proves loading, TCG boot, and host termination only for that emulator exercise. The proof guest
is not licensed or reproducibly sourced for distribution and is not a release input.

## Still blocked

- Reproducible, licensed shipping kernel, initramfs, and base image.
- Production controller/service/turn integration and ready/command/graceful-shutdown protocol.
- Complete signed-AAB native inventory, native debug-symbol archive, and 16 KiB artifact inspection.
- API 26/34/35/36 physical-device coverage, including a 16 KiB device image.
- Screen-off background lifecycle, foreground-service stop, crash/restart recovery, isolation,
  persistent storage, networking, resource, battery, and thermal evidence.
- Play test-track delivery and verification of the Play-generated artifact.

Do not copy these source-only PASS results into a Play declaration as release-artifact evidence.
