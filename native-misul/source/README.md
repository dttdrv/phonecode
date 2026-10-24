# Misul native runtime

PhoneCode owns this copy of the Misul Agent source. It includes the six Android/provider patches retained in `../patches/`; do not apply them again.

Use Zig 0.16.0 and Android NDK 28.2.13676358. Set `ZIG` and `ANDROID_NDK_HOME` to their absolute installed paths, then run:

```sh
scripts/build-android-arm64.sh /absolute/new-output-directory
```

The builder hashes the complete source package, embeds that identity in the runtime, and audits the Android API 26 arm64 library. Its output includes the library, source manifest and producer manifest. Follow `../README.md` to validate or replace PhoneCode's locked distributable input.

The source was reconstructed byte for byte against PhoneCode's previous 88-file source manifest before excluding eight build-cache, diagnostic and agent-workflow files. Runtime source and tests are unchanged. The clean package has a new source identity and requires a fresh build. The original MIT license is retained in LICENSE.
