# Misul Native Android Migration Gates

Status: active
Specification: `docs/superpowers/specs/2026-09-02-misul-native-android-runtime-design.md`

Release ladder:

- `0.6.0-alpha`: MNA-01 through MNA-10 plus fresh alpha build/install/relaunch verification. This is the user-testable native backend cutover.
- `0.6.0-beta`: MNA-11 plus corrections from alpha testing. This is the UI and UX acceptance release.
- `0.6.0`: MNA-12 and MNA-13 plus corrections from beta testing. This is the final performance and stability release.

- [x] MNA-01: The current untracked MisulAgent source is preserved, checksummed, and freshly verified with the pinned Zig toolchain before any source edit.
  CHECK: test -f .codex-checkpoints/misul-agent-baseline.sha256 && test -f .codex-checkpoints/misul-agent-baseline-path.txt
  EXPECT: both checkpoint records exist and the recorded copy is readable
  EVIDENCE: `.codex-checkpoints/misul-agent-verify.txt`; 75 files preserved at `/Users/dttdrv/.codex/tmp/misul-agent-android.wpQAzM/MisulAgent`; manifest `28cbc393a8378ceb5669e034b40de8d25d6071a455993a65c65c58e10e588fe5`; Zig archive `b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489`; 289/289 tests and `verify-ok`.

- [x] MNA-02: The native Misul runtime exposes the bounded Android C ABI over the existing runtime and RPC protocol, with hostile-input and lifetime tests.
  CHECK: /Users/dttdrv/Projects/Misul-Terminal/MisulAgent/scripts/test-android-api-contract.sh
  EXPECT: android-api-contract-ok
  EVIDENCE: `.codex-checkpoints/misul-android-api-verify.txt`; `android-api-contract-ok`; 248/248 focused tests and 292/292 full native tests.

- [x] MNA-03: The native Misul runtime cross-compiles as an Android API 26 AArch64 shared library and passes a focused ELF, dependency, export, path, and 16 KiB alignment audit.
  CHECK: /Users/dttdrv/Projects/Misul-Terminal/MisulAgent/scripts/test-android-build-contract.sh
  EXPECT: android-build-contract-ok
  EVIDENCE: `.codex-checkpoints/misul-android-build-verify.txt`; `android-build-contract-ok`; 2,565,368-byte audited library.

- [x] MNA-04: Two clean Android arm64 builds from the same Misul source and toolchain produce byte-identical runtime artifacts and manifests.
  CHECK: /Users/dttdrv/Projects/Misul-Terminal/MisulAgent/scripts/verify-android-reproducibility.sh
  EXPECT: android-reproducibility-ok
  EVIDENCE: `.codex-checkpoints/misul-android-build-verify.txt`; `android-reproducibility-ok`; artifact `678c40a5f67bab42e5588776faf2431c52e2d690fd6aa824f81fa1dfa49c6742` in both clean builds.

- [x] MNA-05: PhoneCode stages only a source-identified, SHA-256-verified Misul library and rejects missing, stale, malformed, wrong-architecture, unexpected-export, or unexpected native inputs.
  CHECK: MISUL_TEST_ARTIFACT=/Users/dttdrv/.codex/artifacts/misul-android-phase1-20260902 native-misul/tests/stage-runtime-test.sh && ./gradlew :app:verifyDebugMisulApk -PMISUL_ANDROID_RUNTIME_DIR=/Users/dttdrv/.codex/artifacts/misul-android-phase1-20260902
  EXPECT: stage-runtime-test-ok and BUILD SUCCESSFUL
  EVIDENCE: negative staging fixtures passed; the debug APK contains exactly one `lib/arm64-v8a/libmisul.so` with locked SHA-256 `678c40a5f67bab42e5588776faf2431c52e2d690fd6aa824f81fa1dfa49c6742` plus all three provenance records.

- [x] MNA-06: An installed Android debug build loads the packaged `libmisul.so`, opens one runtime handle, completes the versioned handshake, receives a model list, closes cleanly, and starts no Misul child process.
  CHECK: ./gradlew :app:connectedDebugAndroidTest -PMISUL_ANDROID_RUNTIME_DIR=/Users/dttdrv/.codex/artifacts/misul-android-phase1-20260902 -Pandroid.testInstrumentationRunnerArguments.class=dev.phonecode.app.runtime.MisulNativeHandshakeTest
  EXPECT: 1 test passes twice with no native crash, leaked handle, or Misul child process
  EVIDENCE: `.codex-checkpoints/misul-jni-verify.txt`; two passing Android 14 arm64 instrumentation runs, each with two open/close cycles; no matching Misul child process in `adb shell ps -A`.

- [ ] MNA-07: Misul owns the validated self-refreshing model catalogue needed by PhoneCode, and PhoneCode omits provider paths that are not dispatch-capable.
  CHECK: /Users/dttdrv/Projects/Misul-Terminal/MisulAgent/scripts/test-phonecode-model-catalog.sh
  EXPECT: phonecode-model-catalog-ok
  EVIDENCE: pending

- [ ] MNA-08: The Misul native adapter supports bounded host requests for Android transport, credentials, platform tools, approvals, cancellation, and typed failures through the shared runtime.
  CHECK: /Users/dttdrv/Projects/Misul-Terminal/scripts/test-unified-runtime.sh
  EXPECT: unified runtime: pass
  EVIDENCE: pending

- [ ] MNA-09: The Android runtime service completes a real native session restore, prompt stream, tool approval, cancellation, process-death recovery, and settled result without using the Kotlin agent loop.
  CHECK: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.phonecode.app.runtime.MisulRuntimeWorkflowTest
  EXPECT: all native workflow cases pass
  EVIDENCE: pending

- [ ] MNA-10: Existing sessions migrate once into the Misul event store, and production builds contain no second agent loop, provider semantic mapper, session authority, permission policy, or fallback backend.
  CHECK: ./gradlew :app:verifyMisulCutover :app:testDebugUnitTest
  EXPECT: BUILD SUCCESSFUL and the production-surface audit reports one runtime
  EVIDENCE: pending

- [ ] MNA-11: Conversation, drawer, approvals, and settings pass phone and tablet review in light and dark themes and retain bounded progressive scroll-edge blur without dashboard or card-grid treatment.
  CHECK: ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
  EXPECT: UI tests pass and the four required final captures are recorded under .impeccable/review
  EVIDENCE: pending

- [ ] MNA-12: Final measurements satisfy the approved startup, native-call, idle CPU, memory, frame-time, size, battery, and thermal contract on the named Android targets with raw before-and-after samples retained.
  CHECK: ./gradlew :app:connectedBenchmarkAndroidTest :app:verifyMisulPerformanceEvidence
  EXPECT: all performance budgets pass without an unexplained regression above 5 percent
  EVIDENCE: pending

- [ ] MNA-13: Full native and Android verification passes with no warnings, leaks, dangling work, stale generated files, placeholder production behavior, or unreviewed scope expansion.
  CHECK: /Users/dttdrv/Projects/Misul-Terminal/scripts/verify-production.sh && ./gradlew :provider:test :tools:test :agent:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  EXPECT: both repositories pass their complete production checks
  EVIDENCE: pending
