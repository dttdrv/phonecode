# Misul Android Runtime Input

PhoneCode consumes a separately built Misul Android library. The Zig source remains owned by `/Users/dttdrv/Projects/Misul-Terminal/MisulAgent`; this directory contains only the immutable consumer contract and staging checks.

Build the pinned input locally with:

```sh
ANDROID_NDK_HOME=/Users/dttdrv/Library/Android/sdk/ndk/28.2.13676358 \
ZIG=/Users/dttdrv/.codex/toolchains/zig-0.16.0/zig \
/Users/dttdrv/Projects/Misul-Terminal/MisulAgent/scripts/build-android-arm64.sh /absolute/output/path
```

Then run the staging contract with `MISUL_ANDROID_RUNTIME_DIR=/absolute/output/path`. The source tree, downloads, and native artifact are not vendored into PhoneCode. Gradle packages only a byte-for-byte verified `libmisul.so` and its provenance metadata when the experimental property is explicitly supplied.

The 0.6 alpha runs prompts, native file tools, approvals, cancellation, and durable session import through this in-process runtime. PhoneCode has no packaged Kotlin agent fallback. The source lock must be updated only from a freshly audited Misul Android build.
