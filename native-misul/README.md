# Misul Android runtime

PhoneCode includes the native source in [source/](source/). It contains all six patches in `patches/`; these are provenance records and must not be applied again. Building PhoneCode no longer requires Misul-Terminal.

Use Zig 0.16.0 and Android NDK 28.2.13676358. Set `ZIG` and `ANDROID_NDK_HOME` to their absolute installed paths, then run from this directory:

```sh
source/scripts/build-android-arm64.sh /absolute/new-output-directory
```

The builder hashes the complete source package, embeds its identity in the library, and audits the Android API 26 arm64 ABI. The committed library and provenance are authenticated distributable app inputs. Normal app builds use those inputs.

To replace them, review the source diff, build a new output directory, and run its audit. Update `sources.lock` from that producer's artifact digest, byte count and source-manifest digest. Run `tests/stage-runtime-test.sh` with `MISUL_TEST_ARTIFACT` set to the output directory, then stage through `stage-android-arm64.sh`. Copy the verified library and all three provenance files into the matching `app/src/main` paths. Verify the resulting APK with `:app:verifyDebugMisulApk`. Never bypass or weaken the source lock.

The 0.6 beta uses this in-process runtime for prompts, native file tools, approvals, cancellation and durable session import. It has no packaged Kotlin-agent fallback. This source consolidation does not establish Google Play or VM-runtime release readiness.
