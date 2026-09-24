# Google Play submission preflight: PhoneCode 1.0.0

Status: **BLOCKED. Do not submit this build or describe it as Play-ready.**

This directory holds draft Play Console copy and the evidence checklist for the first Play release,
targeted as 1.0.0. It replaces [`../0.5.1/`](../0.5.1/) as the active package. The claims here were
checked against the source at commit `7574e3f` (version 0.8.6, versionCode 61) on 24 September
2026. Source inspection is not release evidence; every item still has to be proven on the exact
signed Android App Bundle.

[`READINESS.md`](READINESS.md) is the short gap list. `submission-evidence.json` is the
machine-readable, fail-closed inventory. Every `PASS` must carry hashed evidence bound to the exact
AAB SHA-256.

Policy pages cited in this package were inspected on 24 September 2026.

## Release identity

| Field | Current source | Required evidence |
| --- | --- | --- |
| App name | PhoneCode | Final store listing preview |
| Application ID | `dev.phonecode` | Final AAB manifest |
| Version name / code | `0.8.6` / `61` in `app/build.gradle.kts` | **PENDING:** bump to `1.0.0` with a code above 61, then update `submission-evidence.json` and confirm from the signed AAB |
| Target / compile / min SDK | 36 / 37 / 26 | Confirm from the signed AAB |
| ABI | `arm64-v8a` only | Confirm the bundle inventory and device targeting in Play Console |
| Default locale | `en-US` | Play Console listing |
| Category | Tools, proposed | Confirm in Play Console |
| Ads | No, based on source and privacy policy | Final dependency and network audit |
| Price | Unresolved | Play Console pricing page |

## What 0.8.6 actually does (source-verified)

| Area | Behavior in source | Where |
| --- | --- | --- |
| Agent runtime | Native Misul runtime `libmisul.so` packaged in `jniLibs`, hash-locked to `native-misul/sources.lock` | `app/src/main/jniLibs/arm64-v8a/`, `app/src/main/assets/misul-runtime/` |
| Bridged tools | File tools, `apply_patch`, shared-folder read/write, todo, question, `webfetch`, `websearch`, `git_*`, `extension_read`/`extension_write`, MCP tools, skills; `bash`/`process` only when a shell backend is available | `agent/AgentToolset.kt`, `agent/ChatViewModel.kt` |
| Command runtime | Release builds use the isolated QEMU backend only after `VmArtifactStore.verify()`; with no packaged guest it reports unavailable and `bash`/`process` are not offered. PRoot/Alpine exist only in `app/src/debug` | `runtime/ShellBackendFactory.kt` |
| Providers | OpenAI, Anthropic, OpenRouter, OpenCode Zen, OpenCode Go, Google Gemini, xAI, DeepSeek, Mistral (API keys); custom OpenAI-compatible (HTTPS or localhost HTTP). ChatGPT sign-in is compiled out of release (`CODEX_OAUTH_ENABLED=false`) and enabled only in debug and sideload | `provider/.../ProviderPreset.kt`, `app/build.gradle.kts` |
| GitHub | Device-flow sign-in (scope `repo read:user`); release requires a PhoneCode-owned client ID or sign-in fails closed; push only to GitHub HTTPS remotes | `auth/GitHubAuth.kt`, `tools/.../git/GitTools.kt` |
| Plugins | Catalog of remote MCP servers: GitHub (PAT), Stripe (restricted key), DeepWiki, Microsoft Learn, AWS Knowledge, Cloudflare Docs, Cloudflare Agents docs; plus custom remote MCP (HTTPS or localhost). MCP tools are marked mutating, so under the default **Ask before each change** each call waits for approval; **Allow changes automatically** lifts that unless the tool is on the per-tool approval list. `webfetch` and `websearch` are read-only and run without a prompt | `ui/settings/PluginSettings.kt`, `data/McpSkillRepository.kt`, `tools/.../mcp/McpTool.kt` |
| Attachments | System photo picker (image only) and document picker (images, text, JSON, XML); photos re-encoded at up to 1600 px | `ui/chat/ChatScreen.kt` |
| Background work | `TurnService`, `specialUse`, started only by a user-sent turn or an agent-started process lease; Stop action; wake lock released on destroy | `agent/TurnService.kt`, `ForegroundLeaseManager.kt` |
| Notifications | `POST_NOTIFICATIONS` requested on Android 13+ the first time a turn runs; ongoing turns request promotion (Android 16 Live Updates) with `ProgressStyle` | `MainActivity.kt`, `agent/TurnNotification.kt` |
| AI reporting | Response info > Report response opens **Send safety feedback** (8 categories, optional 1,000-character note) posting to `https://dttdrv.xyz/api/phonecode/report` | `ui/chat/ChatTurn.kt`, `ui/chat/ChatOverlays.kt`, `agent/ChatViewModel.kt` |
| Credentials | `EncryptedSharedPreferences` with an AES256-GCM Keystore master key; not saved when unavailable | `data/SecureKeyStore.kt` |
| Transport | `cleartextTrafficPermitted="false"` except localhost; web tools HTTPS-only, public addresses only, no redirects | `res/xml/network_security_config.xml`, `tools/.../web/WebFetchTool.kt` |
| Backup | `allowBackup="false"`; manual export excludes secrets | `AndroidManifest.xml`, `data/TransferBundle.kt` |

## Manifest permissions

| Permission | Why | Play process |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Model, tool, and report requests | None |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | `TurnService` | FGS declaration and video, see [`foreground-service.md`](foreground-service.md) |
| `WAKE_LOCK` | Partial wake lock while a turn runs | None; covered by the FGS evidence |
| `POST_NOTIFICATIONS` | Runtime permission, Android 13+ | None beyond accurate use |
| `POST_PROMOTED_NOTIFICATIONS` | Install-time permission for Android 16 Live Updates | No Play declaration found; must follow the [Live Update guidance](https://developer.android.com/develop/ui/views/notifications/live-update) |

The app requests no storage, media, location, camera, contacts, or account permissions.

## Policy references (inspected 24 September 2026)

- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en):
  from 31 August 2026 new apps and updates must target API 36. Source targets 36.
- [Foreground service declaration](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en):
  description, deferral/interruption impact, and a video link per type on App content.
- [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en):
  no executable code from outside Play except code running in a virtual machine or interpreter; FGS
  use must be core, user-initiated or perceptible, stoppable, non-deferrable, and no longer than needed.
- [AI-Generated Content](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en)
  and [its guide](https://support.google.com/googleplay/android-developer/answer/14094294?hl=en):
  in-app reporting without leaving the app, and reports used to inform filtering and moderation.
- [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
  and [User Data](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
- [Testing requirements for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en):
  12 testers opted in for 14 consecutive days, for personal accounts created after 13 November 2023.
- [Payments](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en) and
  [Metadata](https://support.google.com/googleplay/android-developer/answer/9898842?hl=en).

## Files

| File | Purpose |
| --- | --- |
| [`READINESS.md`](READINESS.md) | Blocking and non-blocking gap list |
| [`data-safety.md`](data-safety.md) | Data safety worksheet from actual data paths |
| [`foreground-service.md`](foreground-service.md) | `specialUse` declaration draft and video script |
| [`ai-safety.md`](ai-safety.md) | AI-generated content controls and evidence |
| [`reviewer-instructions.md`](reviewer-instructions.md) | App access and review path |
| [`store-listing.md`](store-listing.md) | Listing copy draft |
| [`signing.md`](signing.md) | Upload key and Play App Signing record |
| [`closed-test.md`](closed-test.md) | Personal-account closed-test record |
| [`developer-account.md`](developer-account.md) | Account and package record |
| [`native-runtime-evidence.md`](native-runtime-evidence.md) | Native runtime status |
| `submission-evidence.json` | Fail-closed evidence manifest |

Validate the blocked manifest without claiming readiness:

```sh
python3 play/verify_submission_evidence.py --schema-only play/1.0.0/submission-evidence.json
./gradlew :app:verifyPlaySubmissionEvidenceSchema
```

## Source-of-truth order

1. The exact signed AAB and observed release-build behavior.
2. `app/src/main/AndroidManifest.xml` and resolved release dependencies.
3. `legal/privacy.md`, `legal/terms.md`, and their identical in-app copies in `app/src/main/assets/`.
4. `MOBILE_BACKEND.md` and `legal/RELEASE_COMPLIANCE.md`.
5. These draft Play documents.

If these disagree, stop and correct the lower-priority source.
