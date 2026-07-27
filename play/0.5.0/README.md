# Google Play submission preflight: PhoneCode 0.5.0

Status: **BLOCKED — do not submit or describe this build as Play-ready.**

This directory holds draft Play Console copy and the evidence checklist for the 0.5.0 candidate. It
is not a changelog or a substitute for testing the exact signed Android App Bundle.

## Release identity

| Field | Candidate value | Required evidence |
| --- | --- | --- |
| App name | PhoneCode | Final store listing preview |
| Application ID | `dev.phonecode` | Final AAB manifest |
| Version name | `0.5.0` | Repository source is configured; confirm from the final AAB manifest |
| Version code | `50` | Confirm from the final AAB |
| Default locale | `en-US` | Play Console listing |
| Category | Tools, proposed | Confirm in Play Console |
| Ads | No, based on current source and privacy policy | Final dependency and network audit |
| Price | Unresolved | Play Console pricing page |

## Submission blockers

- The `verifyPlayRelease` dependency deliberately stops candidate packaging until credentials, exact
  QEMU/guest payloads, legal/SBOM/source inputs, and production integration are complete.
- `verifyPlaySubmission` builds that controlled candidate, audits every packaged release ELF, and
  still fails until signed-AAB, native-symbol, device-lifecycle, Play-artifact, and policy evidence
  exists. `submission-evidence.json` is the machine-readable fail-closed inventory; every `PASS`
  item must carry hashed evidence bound to the exact AAB SHA-256.
- The current Alpine/PRoot prototype executes downloaded native code under the app UID and is not the
  Google Play release boundary. The reproducible isolated QEMU payload is not yet a shipping artifact.
- PRoot/talloc, Alpine, Mermaid, fonts, Android/JVM dependencies, and the future QEMU payload still
  have unresolved source, provenance, notice, or SBOM requirements in the compliance register.
- The exact AAB and every Android-loaded native artifact still need 16 KB page-alignment inspection
  and execution on a 16 KB Android image. This has been required for Android 15-targeting Play
  submissions since November 1, 2025.
- The public privacy and terms URLs must be live, non-geofenced, and identical in substance to the
  policies bundled with the submitted app. Their deployment is not evidenced in this repository.
- The Data Safety worksheet contains unresolved classification, sharing, encryption-in-transit, and
  deletion answers. It must be reconciled against the final AAB and every reachable endpoint.
- The `specialUse` foreground service needs an accurate Play Console declaration and a video made
  from the exact release build. Approval is not established by the manifest alone.
- Reviewer access credentials, the signed AAB, signing record, bundle inventory, device-test matrix,
  pre-launch report, and release hashes do not exist in this pack.
- If the Play account is a personal account created after November 13, 2023, production access also
  requires physical-device verification and a closed test with at least 12 continuously opted-in
  testers for 14 consecutive days before the production-access application.
- The in-app AI-output report flow exists, but the final build still needs end-to-end report testing,
  documented restricted-content safeguards, and evidence that reports inform filtering or moderation.

Google Play bars apps from downloading executable code such as DEX, JAR, or native libraries outside
Play, with a defined exception for code running in a virtual machine or interpreter. The final
runtime and all dynamically obtained content must also remain policy-compliant. See the current
[Developer Program Policy](https://support.google.com/googleplay/android-developer/answer/17105854?hl=en)
and [Policy Coverage](https://support.google.com/googleplay/android-developer/answer/10146128?hl=en).

## Evidence index

Use `PASS`, `FAIL`, or `BLOCKED`; never leave a checked item without a link or hash.

| Evidence | Current state | Completion rule |
| --- | --- | --- |
| Release commit and clean-tree record | BLOCKED | Commit SHA recorded and all generated release inputs reproducible |
| Signed `.aab` and SHA-256 | BLOCKED | Upload-key-signed candidate archived outside the repository with hash |
| Final manifest and bundle inventory | BLOCKED | Extracted from the signed AAB, including permissions, services, assets, native libraries, and ABIs |
| Version identity | BLOCKED | AAB reports version name `0.5.0` and a Play-acceptable monotonic version code |
| Target API | PASS, source-only | Candidate targets API 36 ahead of the announced August 2026 requirement; confirm from the final AAB and recheck the [target API requirements](https://developer.android.com/google/play/requirements/target-sdk) on submission day |
| 16 KB page support | BLOCKED | Every loaded native artifact passes bundle alignment inspection and runs on a 16 KB API 35+ image, following the [official page-size guidance](https://developer.android.com/guide/practices/page-sizes) |
| Runtime boundary | BLOCKED | No out-of-Play native execution under the app UID; isolated VM evidence and package behavior reviewed |
| Native host foundation | PASS, source-only | [`native-runtime-evidence.md`](native-runtime-evidence.md) records reproducibility, exact host hashes, ELF audit, and the limited API 34 emulator boot proof; final guest/AAB/device evidence remains blocked |
| Runtime device matrix | BLOCKED | API 26, 34, 35, 36, and current target tested on representative arm64 hardware; lifecycle, stop, storage, network, and recovery pass |
| SBOM and complete notices | BLOCKED | Final resolved graph and every shipped binary covered by version, source, license, notice, and hash |
| Corresponding source and rebuild evidence | BLOCKED | Exact copyleft sources, patches, scripts, toolchain, offer or download, and reproduction logs available beside the release |
| Privacy policy URL | BLOCKED | Public HTTPS page loads without authentication, geofence, or PDF and matches `legal/privacy.md` |
| Terms URL | BLOCKED | Public HTTPS page loads and matches `legal/terms.md` |
| Data Safety declaration | BLOCKED | [`data-safety.md`](data-safety.md) reconciled to the final artifact and submitted in Play Console |
| Foreground-service declaration | BLOCKED | [`foreground-service.md`](foreground-service.md) text submitted and release-build video URL accepted |
| App access | BLOCKED | Dedicated API-key reviewer credential and [`reviewer-instructions.md`](reviewer-instructions.md) entered in Play Console; Codex OAuth is not part of the release path |
| Signing and Play App Signing | BLOCKED | [`signing.md`](signing.md) completed for the exact AAB without placing keystores or secrets in this repository |
| Personal-account production access | UNRESOLVED | [`developer-account.md`](developer-account.md) establishes applicability; when required, physical-device verification and [`closed-test.md`](closed-test.md) are complete |
| Developer verification | UNRESOLVED | [`developer-account.md`](developer-account.md) records current Play Console identity, contact, and package-registration evidence |
| Store listing | DRAFT | [`store-listing.md`](store-listing.md) proofread against the exact release capabilities and assets |
| Target audience and content rating | UNRESOLVED | Play questionnaires completed truthfully; app is not directed to children under 13 |
| Ads declaration | UNRESOLVED | Final SDK/AAB scan confirms no ad SDK or ad surface before selecting No |
| AI-generated content safeguards | BLOCKED | [`ai-safety.md`](ai-safety.md) verifies in-app reporting, restricted-content prevention, endpoint handling, retention, and moderation response against the [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en) |
| Financial features | PASS, source-only | No in-app Ko-fi, Stripe, donation, or external-payment steering in the submitted build |
| Pre-launch report | BLOCKED | No unresolved crash, ANR, security, accessibility, or compatibility issue |
| Play policy status | BLOCKED | No pending declaration, rejection, warning, or required action in Play Console |

Google requires a complete Data Safety form even when an app collects no data, and the developer is
responsible for its accuracy. See the [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

## Source-of-truth order

1. The exact signed AAB and observed release-build behavior.
2. `app/src/main/AndroidManifest.xml` and resolved release dependencies.
3. `legal/privacy.md`, `legal/terms.md`, and their in-app copies.
4. `MOBILE_BACKEND.md` and `legal/RELEASE_COMPLIANCE.md`.
5. These draft Play documents.

If these disagree, stop submission and update the lower-priority source. Google reviews metadata,
in-app behavior, third-party code, and declarations together; see [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en).

Validate the blocked draft structure without claiming readiness:

```sh
./gradlew :app:verifyPlaySubmissionEvidenceSchema
```

The final `verifyPlaySubmission` task passes the generated AAB to the validator and refuses evidence
whose recorded candidate hash differs from the actual bundle bytes.
