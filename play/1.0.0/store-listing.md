# Store listing draft

Status: **DRAFT. Publish only after every described capability is present in the signed AAB.**

The copy below describes what the 0.8.6 source does in a release build. It deliberately omits
running shell commands, builds, and tests on the phone: release builds have no command runtime until
the isolated VM ships. Add that back only when the signed AAB proves it.

## Main listing

**App name:** PhoneCode

**Short description (≤80 characters)**

A coding agent for your phone: projects, files, Git, and the AI model you choose.

**Full description**

PhoneCode is a coding agent for working on software projects from an Android phone.

Connect an AI model with your own provider key, pick a project, and describe what you want. The agent
reads and edits your files, searches your project and the web, works with GitHub, and shows every
step as it goes. You approve changes before they happen, or let it work on its own.

PhoneCode includes:

- project-based chats with a private workspace, plus phone folders you choose to link;
- file reading, editing, search, and patches with approval controls;
- Git commit, branch, pull, and push to GitHub;
- your choice of provider: OpenCode Go, OpenCode Zen, OpenAI, Anthropic, OpenRouter, Google
  Gemini, xAI, DeepSeek, Mistral, or a compatible endpoint;
- plugins for GitHub, Stripe, Microsoft Learn, AWS, Cloudflare, and DeepWiki, or any remote MCP
  server, plus Skills;
- photos and text files attached through Android's pickers;
- background work with a notification that shows progress and a Stop button, and Live Updates on
  Android 16.

PhoneCode has no PhoneCode account, ads, analytics, or telemetry. Keys are stored with Android
Keystore-backed encryption. Your prompts, files, and tool results go directly to the provider,
plugin, or service you choose, whose terms and charges apply. You can report a harmful response
from inside the app.

AI output and agent actions can be wrong or destructive. Review important changes and keep backups.

PhoneCode is independent. It is not built by, endorsed by, or affiliated with OpenCode, OpenAI,
Anthropic, or any other provider or plugin publisher named here.

Open questions before use: whether naming providers and plugins in the description is appropriate
under the [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842?hl=en)
(inspected 24 September 2026), and whether each trademark owner permits the listing to use its name
this way.

## Listing links

| Field | Value | Status |
| --- | --- | --- |
| Website | `https://dttdrv.xyz/phonecode` | Responds 200 (24 September 2026); content not reviewed |
| Privacy policy | `https://dttdrv.xyz/phonecode-privacy` | **BLOCKED:** live page dated 13 July 2026; publish the 24 September 2026 text |
| Terms | `https://dttdrv.xyz/phonecode-terms` | **BLOCKED:** publish the 24 September 2026 text |
| Support contact | `https://dttdrv.xyz/#contact` plus a support email in Play Console | PENDING |

No Ko-fi, Stripe donation, prices, or payment links in the listing or app. See the
[Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en).

## Graphics

None exist for 1.0.0. The 0.5.1 graphics were rendered from the interface before the 0.8.x
redesign and must not be reused.

- App icon 512 × 512 PNG: PENDING, render from the shipping adaptive icon.
- Feature graphic 1024 × 500: PENDING.
- Phone screenshots (2–8, 9:16): PENDING. Use synthetic content and release-build states only: no
  ChatGPT sign-in, no shell output, no real credentials.
- Tablet screenshots: PENDING or restrict device support.
- `PlayListingAssetTest` and `ScreenshotTest.playListingPhoneScreenshots` currently write to
  `play/0.5.1/graphics/`. Point them at `play/1.0.0/graphics/` when regenerating, then inspect every
  image by eye.
