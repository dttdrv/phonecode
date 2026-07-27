# Store listing draft

Status: **DRAFT — publish only after every described capability is present in the signed AAB.**

## Main listing

**App name**

PhoneCode

**Short description**

An on-device coding agent for Android projects, files, Git, and tools.

**Full description**

PhoneCode is a native coding agent for working on software projects from an Android phone.

Choose a project folder, connect an AI provider, and keep each project's chats, files, and source
control together. PhoneCode can help you inspect code, make edits, search a workspace, run supported
development tools, and review the result without sending work through a PhoneCode execution server.

PhoneCode includes:

- project-based chats and persistent workspaces;
- file reading, writing, search, patches, and approval controls;
- Git workflows for local repositories and connected Git hosts;
- configurable model providers, tools, MCP servers, and progressive-disclosure Skills;
- attachments selected through Android's system picker;
- foreground execution for user-started agent work, with a visible notification and Stop action;
- local session storage and recovery when Android interrupts active work.

PhoneCode has no required PhoneCode account, advertising, analytics, or telemetry. Credentials are
stored using Android Keystore-backed encryption and Android cloud backup is disabled. Prompts,
source code, attachments, and tool context are sent directly to the AI provider or other service you
choose when a feature needs them. Each connected service applies its own terms, privacy practices,
retention, and charges.

AI output and tool actions can be incorrect or destructive. Review important changes, use version
control and backups, and grant only the access you intend.

PhoneCode is an independent project inspired by and interoperable with OpenCode. It is not built by,
endorsed by, or affiliated with the OpenCode team.

## Listing links

| Field | Draft value | Status |
| --- | --- | --- |
| Website | `https://dttdrv.xyz/phonecode` | Verify before submission |
| Privacy policy | `https://dttdrv.xyz/phonecode-privacy` | BLOCKED until live and reconciled |
| Terms | `https://dttdrv.xyz/phonecode-terms` | Verify before submission |
| Support contact | Developer contact form at `https://dttdrv.xyz/#contact` | Verify before submission |

Do not include Ko-fi, Stripe, donation copy, prices, or payment links in the Play listing or app.
Outside the app, support must remain optional and unlock no app feature or service. Recheck the
current [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en)
before every submission.

## Listing asset checklist

- App icon: `graphics/app-icon.png` is a 512 × 512 PNG rendered from the shipping adaptive-icon
  artwork. Verify the final Play preview mask and confirm the artwork is unchanged in the signed AAB.
- Feature graphic: `graphics/feature-graphic.jpg` is a 1024 × 500 opaque JPEG rendered from the real
  welcome experience.
- Phone screenshots: seven 1440 × 2560 opaque JPEGs are inventoried in `graphics/README.md`. They
  contain synthetic project/chat content and release-compatible states. Reconfirm every visible
  claim against the exact signed build before upload.
- Tablet screenshots: unresolved; either provide compliant assets or confirm the supported-device
  presentation in Play Console.
- Preview video: optional for the public listing; do not reuse the foreground-service evidence video
  unless it is polished and contains no reviewer credentials.
- Localizations: unresolved; publish only reviewed translations that exactly match release behavior.

Google requires accurate, clear, non-misleading metadata and applies the policy to screenshots,
icons, titles, and descriptions. Review the current [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842?hl=en)
and [store-listing guidance](https://support.google.com/googleplay/android-developer/answer/13393723?hl=en).
