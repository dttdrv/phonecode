# Android signing and Play App Signing record

Status: **BLOCKED. No upload key is configured, no signed AAB exists, and Play App Signing is not
enrolled.**

`app/build.gradle.kts` creates the `release` signing config only when all four inputs are set:
`PHONECODE_RELEASE_STORE_FILE`, `PHONECODE_RELEASE_STORE_PASSWORD`, `PHONECODE_RELEASE_KEY_ALIAS`,
`PHONECODE_RELEASE_KEY_PASSWORD`. `verifyPlayRelease` fails closed when any is missing or the
keystore file is unreadable. Never commit a keystore, password, or private key.

New apps must use Play App Signing: Google holds the app-signing key and you sign uploads with an
upload key. See [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en).

The `sideload` build type is signed with the debug key and enables ChatGPT sign-in. It must never be
uploaded to Play.

## Console and certificate record

| Field | Value |
| --- | --- |
| Play App Signing enrolled | PENDING |
| App-signing certificate SHA-256 | PENDING (public fingerprint only) |
| Upload key created and stored outside the repository | PENDING |
| Upload certificate SHA-256 | PENDING |
| Key custodian and backup location (role only) | PENDING |

## Candidate record

| Field | Value |
| --- | --- |
| Release commit | PENDING |
| Version name / code | Source: `0.8.6` / `61`. PENDING bump to `1.0.0` |
| Signed AAB SHA-256 | PENDING |
| Certificate observed on the AAB | PENDING |
| Internal-track upload | PENDING |
| Play-delivered APK certificate matches app-signing key | PENDING |

## Ceremony

1. Generate the upload key outside the repository and back it up.
2. Supply the four inputs from a local secret store; do not echo them.
3. Also set `PHONECODE_GITHUB_OAUTH_CLIENT_ID` to a PhoneCode-owned GitHub OAuth app, or the release
   gate stops.
4. Run `./gradlew :app:bundleRelease` and archive the log, toolchain versions, inventory, and SHA-256.
5. Verify the AAB signature, upload to an internal track, and verify the Play-delivered certificate.
6. Record the fingerprints above.
