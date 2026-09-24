# PhoneCode 1.0.0 Play readiness gaps

Status: **NOT READY.** Checked against source commit `7574e3f` (0.8.6, versionCode 61) and Google
Play policy pages on 24 September 2026. Nothing below has been submitted or uploaded.

## Blocking

| # | Gap | Evidence or exact missing action |
| --- | --- | --- |
| B1 | No signed release AAB | `verifyPlayRelease` in `app/build.gradle.kts` stops on missing upload-signing inputs, GitHub OAuth client ID, VM guest payload, native symbols, and compliance files. Build `:app:bundleRelease` once these clear, record its SHA-256 in `submission-evidence.json` |
| B2 | Release gate requires the isolated VM guest, which does not exist | Decide: ship 1.0.0 without the command runtime (then change the gate deliberately and keep `bash`/`process` hidden), or finish the guest. See [`native-runtime-evidence.md`](native-runtime-evidence.md) |
| B3 | Version identity | Source says `0.8.6` / `61` (`app/build.gradle.kts` in `defaultConfig` and the `sideload` override, and `ReleaseRuntimePackagingTest`). Bump to `1.0.0` and a code above 61, then update `submission-evidence.json` |
| B4 | Upload key and Play App Signing | Create the upload key, set the four `PHONECODE_RELEASE_*` inputs, enroll in Play App Signing, record fingerprints in [`signing.md`](signing.md) |
| B5 | GitHub OAuth client | Register a PhoneCode-owned GitHub OAuth app with device flow and set `PHONECODE_GITHUB_OAUTH_CLIENT_ID`. Release sign-in fails closed without it (`auth/GitHubAuth.kt`) |
| B6 | **Restricted-content instruction missing from the production prompt** | `agentSystemPrompt()` (`app/.../agent/AgentToolset.kt`) is what the native runtime receives; the instruction still exists only in the unused `agent/.../SystemBasePrompt.kt`. Add it to the production path and run the prevention matrix in [`ai-safety.md`](ai-safety.md) |
| B7 | AI report flow not proven | Run Response info > Report response > Send against production from the signed build; name a triage owner and record how reports change safeguards |
| B8 | Public privacy policy is stale | `https://dttdrv.xyz/phonecode-privacy` shows 13 July 2026; publish `legal/privacy.md` (24 September 2026) at a public, non-PDF URL |
| B9 | Public terms are stale | Publish `legal/terms.md` (24 September 2026) at `https://dttdrv.xyz/phonecode-terms` |
| B10 | Data safety form | Decide the sharing answer for providers, plugins, and agent web requests; run the proxy capture in [`data-safety.md`](data-safety.md); complete and export the Play Console form |
| B11 | Foreground-service declaration | Submit the `specialUse` text from [`foreground-service.md`](foreground-service.md) under App content |
| B12 | FGS demo video | Record the unedited video from a Play-delivered build per the script and host it without sign-in |
| B13 | Reviewer access | Create a capped reviewer key; run [`reviewer-instructions.md`](reviewer-instructions.md) on a clean device; enter it in App access |
| B14 | Developer account facts | Record account type and creation date, verification, and `dev.phonecode` registration in [`developer-account.md`](developer-account.md) |
| B15 | Closed test, if a personal account created after 13 Nov 2023 | 12 testers opted in for 14 consecutive days, then apply for production access. See [`closed-test.md`](closed-test.md) |
| B16 | Listing graphics | None for 1.0.0; the 0.5.1 set shows the pre-redesign UI. Regenerate, inspect, and move the tests' output to `play/1.0.0/graphics/` |
| B17 | Content rating and target audience | Complete both questionnaires |
| B18 | Release compliance register | `legal/RELEASE_COMPLIANCE.md` still lists Mermaid closure and Android/JVM notices as blocked, plus privacy/terms publication |
| B19 | Signed-build device matrix and pre-launch report | API 26, 34, 35, 36, a 16 KB page-size device, Android 16 Live Updates; clear the pre-launch report |

## Non-blocking (fix or decide before 1.0.0 if possible)

| # | Item | Evidence or action |
| --- | --- | --- |
| N1 | Target API | `targetSdk = 36` meets the requirement in force since 31 August 2026 ([source](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)). Confirm from the AAB |
| N2 | In-app copy promises what release lacks | Onboarding says "Build, test, and manage source control on device" (`ui/onboarding/OnboardingScreen.kt`) while release has no command runtime. Reword or ship the runtime |
| N3 | Approval state as a Live Update | Google lists "chat messages or alerts" as inappropriate Live Updates. The approval state belongs to a running user task, but consider not promoting it |
| N4 | Wake lock without timeout | `TurnService` holds a partial wake lock with no timeout. Confirm release on every path and check Android vitals |
| N5 | `webfetch`/`websearch` run without approval | Agent-chosen URLs can carry workspace content to any public HTTPS host. Consider approval or disclosure for fetch |
| N6 | Reports carry no response text | Triage relies on the user's note. Document whether that meets "use reports to inform filtering and moderation" |
| N7 | Brand marks | `THIRD_PARTY.md` and the in-app notices now state trademark ownership. The site-icon PNGs (AWS, DeepSeek, DeepWiki, Microsoft, OpenCode) have no license grant; check each owner's brand rules or replace with neutral glyphs |
| N8 | OpenRouter attribution header | `HTTP-Referer: https://phonecode.app` (`ProviderPreset.kt`); confirm the domain is controlled or switch to `dttdrv.xyz/phonecode` |
| N9 | ChatGPT sign-in | Intended in the product direction but compiled out of release. It uses Codex CLI client identity headers; get written clearance from OpenAI before enabling it in a Play build |
| N10 | Store listing names third-party products | Review against the Metadata policy before publishing |
| N11 | Personalization sends a name | Data safety must declare Name when **Use personalization** is on |
| N12 | `play/0.5.1` graphics tests | `PlayListingAssetTest` and `ScreenshotTest` still write into `play/0.5.1/graphics/` |

## Verified in source on 24 September 2026

- Manifest permissions: network, FGS + `specialUse`, wake lock, `POST_NOTIFICATIONS`,
  `POST_PROMOTED_NOTIFICATIONS`. No storage, media, location, camera, or contacts permissions.
- Cleartext blocked except localhost; web, Git, MCP, and custom providers require HTTPS off-device.
- Credentials in Keystore-backed `EncryptedSharedPreferences`; cloud backup off.
- In-app legal copies equal `legal/privacy.md` and `legal/terms.md`.
- `libmisul.so` matches its source lock; `THIRD_PARTY.md` hash corrected.
- The FGS starts only from user turns or agent-started processes and has a Stop action.
- Report response exists inside the app and sends only category, note, version, and platform.

## Policy pages used (inspected 24 September 2026)

- Target API: <https://support.google.com/googleplay/android-developer/answer/11926878?hl=en>
- FGS declaration: <https://support.google.com/googleplay/android-developer/answer/13392821?hl=en>
- Device and Network Abuse: <https://support.google.com/googleplay/android-developer/answer/16559646?hl=en>
- AI-Generated Content: <https://support.google.com/googleplay/android-developer/answer/13985936?hl=en>,
  <https://support.google.com/googleplay/android-developer/answer/14094294?hl=en>
- Data safety: <https://support.google.com/googleplay/android-developer/answer/10787469?hl=en>
- New personal account testing: <https://support.google.com/googleplay/android-developer/answer/14151465?hl=en>
- Live Updates: <https://developer.android.com/develop/ui/views/notifications/live-update>
