# Misul Android Runtime Input

PhoneCode consumes a separately built Misul Android library. The Zig source remains owned by `/Users/dttdrv/Projects/Misul-Terminal/MisulAgent`; this directory contains only the immutable consumer contract and staging checks.

Build the pinned input locally with:

```sh
ANDROID_NDK_HOME=/Users/dttdrv/Library/Android/sdk/ndk/28.2.13676358 \
ZIG=/Users/dttdrv/.codex/toolchains/zig-0.16.0/zig \
/Users/dttdrv/Projects/Misul-Terminal/MisulAgent/scripts/build-android-arm64.sh /absolute/output/path
```

Then run the staging contract with `MISUL_ANDROID_RUNTIME_DIR=/absolute/output/path`. The source tree and downloads are not vendored into PhoneCode. The locked `libmisul.so` and its provenance are committed as distributable app inputs so local and CI sideload builds cannot silently omit the runtime. Replace them only with staging output that matches `native-misul/sources.lock`.

Before building the current PhoneCode runtime, apply `native-misul/patches/provider-failure-rpc.patch` and `native-misul/patches/android-https-transport.patch` to the Misul source. Apply the zero-context transport patch with `git apply --unidiff-zero`. The first patch preserves Misul's structured, redacted provider failure in the Android RPC settlement. The second uses Android's dual-stack resolver, races the returned addresses, and eagerly loads Android's system CA bundle before native HTTPS, avoiding Zig's Linux resolver path inside the app process without narrowing custom providers to IPv4.

The 0.6 beta runs prompts, native file tools, approvals, cancellation, and durable session import through this in-process runtime. PhoneCode has no packaged Kotlin agent fallback. The source lock must be updated only from a freshly audited Misul Android build.
