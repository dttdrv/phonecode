<p align="center">
  <img src=".github/assets/phonecode-icon.svg" width="112" height="112" alt="PhoneCode icon">
</p>

<h1 align="center">PhoneCode</h1>

<p align="center"><strong>A private, native coding agent that runs entirely on your Android phone.</strong></p>

<p align="center">
  <a href="https://ko-fi.com/dttdrv"><strong>Support PhoneCode on Ko-fi</strong></a> ·
  <a href="https://dttdrv.xyz/phonecode">Website</a> ·
  <a href="https://github.com/dttdrv/phonecode/releases/latest">Latest release</a>
</p>

<p align="center"><sub>Support is optional and unlocks no features or services.</sub></p>

<p align="center">
  <a href="https://github.com/dttdrv/phonecode/actions/workflows/checks.yml"><img src="https://github.com/dttdrv/phonecode/actions/workflows/checks.yml/badge.svg" alt="Checks"></a>
</p>

<p align="center">
  <img src=".github/assets/showcase/phonecode-chat-hero.png" width="100%" alt="PhoneCode running on an Android phone">
</p>

PhoneCode runs the agent loop on your device. It reads, writes, and edits files in per-project
workspaces, runs Git and a bundled Alpine Linux environment locally, searches the web, and talks to
the model provider you choose. There is no general-purpose PhoneCode backend, telemetry, remote
execution service, or required account. The only developer-operated endpoint accepts AI-output
reports you explicitly submit. API keys live in the Android Keystore.

## Interface

<p align="center">
  <img src=".github/assets/screens/conversation.png" width="30%" alt="PhoneCode conversation">
  &nbsp;
  <img src=".github/assets/screens/action-approval.png" width="30%" alt="PhoneCode action approval">
  &nbsp;
  <img src=".github/assets/screens/model-providers.png" width="30%" alt="PhoneCode private model setup">
</p>

<p align="center"><sub>Conversation · Explicit action approval · Private model setup</sub></p>

PhoneCode is an independent project inspired by OpenCode and interoperable with it. It is not a
build of OpenCode.

> **Not affiliated with OpenCode.** PhoneCode is not built by, endorsed by, or affiliated with the
> OpenCode team (Anomaly). The name "OpenCode" appears here only to describe interoperability and
> origins. Parts of the agent (prompt structure, tool schemas, and loop design) are adapted from
> OpenCode, which is MIT licensed (Copyright (c) 2025 opencode); see [LICENSES](#licenses).

## Features

- **On-device agent** with bounded file and process tools, Git, durable todo lists, plan and build
  modes, user questions, subagents, cancellable web access, MCP servers, and progressive-disclosure
  skills. Tool input and bounded output remain inspectable from the chat timeline.
- **On-device development runtime in active migration.** The current arm64 Alpine/PRoot runtime is
  a sideload development prototype. The Google Play architecture is a software-emulated QEMU Linux
  VM so packages execute on a virtual CPU behind an isolated Android process. The app will not claim
  arbitrary outside-Play native package installation until that VM boundary passes its release gate.
- **Providers**: OpenCode Zen and Go, Anthropic, OpenAI, OpenRouter, Google, xAI, DeepSeek,
  Mistral, and custom endpoints. Models and reasoning levels refresh automatically from models.dev;
  ChatGPT sign-in uses the account's authenticated Codex catalog. You can enable or disable
  providers, hide models, and mark favourites. The agent can add providers and models itself by
  editing `providers.json`, which is hot-reloaded.
- **Sign-in flows**: GitHub uses the OAuth device flow (you type a code, with no tokens to paste)
  for push and pull. "Sign in with ChatGPT" (Codex, OAuth + PKCE) lets you use a paid ChatGPT plan
  as a provider through OpenAI's Responses API - no API key needed.
- **Projects and sessions**: chats are organized into folder-backed projects. Active-session state,
  partial turns, tool checkpoints, and todos survive restarts; project instructions can come from
  bounded root-level `AGENTS.md` or `CLAUDE.md` files.
- **Skills and MCP management**: seven starter skills ship with the app. Global and project skills can
  be created, edited, enabled, and hot-reloaded without restarting a chat. MCP configuration is
  validated before activation, reconnects live, and clears unavailable tools instead of leaving a
  stale tool surface.
- **Phone files**: link only the Android folders you choose, then keep reads and writes approval-gated
  or allow them automatically per your workspace settings.
- **Streaming chat** with reasoning traces, a tool-activity timeline, monochrome syntax
  highlighting, a context-window gauge, and per-model token limits that drive compaction.
- **Crash-safe sessions** with foreground execution, bounded pre-output retries, durable tool
  checkpoints, atomic local storage, cancellable network calls, and explicit recovery when Android
  interrupts or you stop a turn.
- **Privacy by construction**: keys are encrypted on-device, Android cloud backup is disabled, and
  manual chat/settings exports use a file you choose through the Storage Access Framework.

## Building

Requirements: JDK 21 and the Android SDK (platform 37.0, build-tools 36). Android Studio is not
needed. PhoneCode supports Android 8.0 and newer.

```bash
./gradlew :app:assembleDebug
./gradlew :provider:test :tools:test :agent:test :app:testDebugUnitTest :app:lintDebug \
  :app:verifyLegalInventory :app:verifyPrototypeRuntimeBoundary
```

The project has four modules. `:app` holds the Compose UI and Android glue. `:agent` holds the loop,
prompts, and compaction. `:provider` holds the wire formats and catalog. `:tools` holds the file,
git, web, todo, MCP, and skills tooling. The three library modules are pure JVM and fully
unit-tested.

Google Play release artifacts remain intentionally blocked until the QEMU runtime, licensing, API,
and Android App Bundle audits pass. When that gate opens, use a dedicated upload key with Play App
Signing and keep it outside the repository.

## Notes

- The GitHub sign-in ships with the gh CLI's public client id for personal builds. Register your own
  OAuth app (one checkbox: Enable Device Flow) before distributing.
- Codex (Sign in with ChatGPT) authenticates over OAuth + PKCE with a loopback redirect, stores the
  tokens encrypted, and talks to ChatGPT's Responses API backend. It needs a paid ChatGPT plan.
- The Terms of Service and Privacy Policy live in `legal/` and are also shown in-app under
  Settings > About.
- The public Play policy URLs are `https://dttdrv.xyz/phonecode-privacy` and
  `https://dttdrv.xyz/phonecode-terms` after the website repository is deployed.
- The local execution architecture is documented in [`MOBILE_BACKEND.md`](MOBILE_BACKEND.md).

## Licenses

PhoneCode's original code is Copyright 2026 dttdrv and licensed under the
[Apache License 2.0](LICENSE).

PhoneCode bundles and adapts open-source work. The full list is in [`THIRD_PARTY.md`](THIRD_PARTY.md)
and in-app under Settings > About > Open-source licenses. In particular:

- **OpenCode** (MIT, Copyright (c) 2025 opencode) - the agent's prompt structure, tool schemas, and
  loop design are adapted from OpenCode. PhoneCode is an independent project and is not affiliated
  with the OpenCode team.
- **Mermaid** (MIT) - inline diagram rendering. **PRoot** (GPL-2.0) and **talloc** (LGPL-3.0) -
  the Linux compatibility layer. **BusyBox** (GPL-2.0) and **Alpine Linux** - the bundled local
  development environment.

Vendored artifacts are pinned in [`VENDORED_CHECKSUMS`](VENDORED_CHECKSUMS) and verified by the
repository checks.
