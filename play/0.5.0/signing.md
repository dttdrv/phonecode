# Android signing and Play App Signing record

Status: **BLOCKED — no signed 0.5.0 AAB, certificate record, or Play App Signing evidence is present
in this submission pack.**

The source declares a release signing configuration only when all four inputs are supplied:
`PHONECODE_RELEASE_STORE_FILE`, `PHONECODE_RELEASE_STORE_PASSWORD`,
`PHONECODE_RELEASE_KEY_ALIAS`, and `PHONECODE_RELEASE_KEY_PASSWORD`. The release task gate must fail
closed when signing is absent. Never place a keystore, password, private key, recovery material, or
unredacted secret in this repository or in Play reviewer instructions.

Google Play distinguishes the app-signing key used for distributed APKs from the upload key used to
authenticate uploads. Record both roles accurately from Play Console; see [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en).

## Console and certificate record

| Field | Value |
| --- | --- |
| Play Console application ID | `dev.phonecode` — confirm from the final app record |
| Play App Signing enrolled | BLOCKED — TODO Yes/No and dated Play Console evidence |
| App-signing key algorithm | BLOCKED — TODO |
| App-signing certificate SHA-256 | BLOCKED — TODO public fingerprint only |
| App-signing certificate SHA-1 | BLOCKED — TODO public fingerprint only if required by an integration |
| Upload key algorithm | BLOCKED — TODO |
| Upload certificate SHA-256 | BLOCKED — TODO public fingerprint only |
| Upload-certificate reset history | BLOCKED — TODO None or dated record |
| Signing owner/custodian | BLOCKED — TODO role, not a private key or password |
| Recovery owner and tested date | BLOCKED — TODO role and date; keep recovery material outside this repository |

## Exact candidate record

| Field | Value |
| --- | --- |
| Release commit | BLOCKED — TODO immutable commit SHA |
| Version name/code | Source currently declares `0.5.0` / `50`; BLOCKED until confirmed from the signed AAB |
| Signed AAB location | BLOCKED — TODO controlled artifact-store reference, not the bundle itself if public |
| Signed AAB SHA-256 | BLOCKED — TODO |
| Signing certificate observed on candidate | BLOCKED — TODO fingerprint and verification output reference |
| Final manifest/package inventory | BLOCKED — TODO evidence reference |
| Play internal-track upload | BLOCKED — TODO version and timestamp |
| Play-processed artifact certificate | BLOCKED — TODO confirm it matches the app-signing certificate |
| Downloaded universal/device APK verification | BLOCKED — TODO evidence reference |

## Required ceremony

1. TODO — verify the release commit is intentional and every native/runtime input is reproducible.
2. TODO — supply the four signing inputs through the approved local or CI secret store. Do not print
   them, persist them in Gradle properties committed to Git, or copy them into this pack.
3. TODO — build the exact release AAB and archive the build log, environment/toolchain versions,
   artifact inventory, and SHA-256.
4. TODO — verify the AAB signature and certificate using Android/JDK tooling, then compare the public
   fingerprint with the recorded upload certificate.
5. TODO — upload only to a controlled Play test track, download Play-generated artifacts, and verify
   the distributed certificate matches the Play app-signing certificate.
6. TODO — test upgrade from the latest Play-distributed version without uninstalling. A signature or
   package mismatch blocks release.
7. TODO — confirm no secret, key material, signing password, or credential entered the repository,
   logs, screenshots, AAB metadata, or reviewer-access fields.

## Release decision

Status remains **BLOCKED** until the exact signed AAB, its SHA-256, both public certificate
fingerprints, Play processing result, and upgrade test are recorded and independently checked.
