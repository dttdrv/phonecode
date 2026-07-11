<p align="center">
  <img src=".github/assets/phonecode-icon.svg" width="112" height="112" alt="PhoneCode icon">
</p>

<h1 align="center">PhoneCode</h1>

<p align="center"><strong>A private, native coding agent that runs entirely on your Android phone.</strong></p>

<p align="center">
  <a href="https://dttdrv.xyz/phonecode">Website</a> ·
  <a href="https://github.com/dttdrv/phonecode/releases/latest">Latest release</a> ·
  <a href="https://ko-fi.com/dttdrv">Support on Ko-fi</a>
</p>

<p align="center">
  <a href="https://github.com/dttdrv/phonecode/actions/workflows/checks.yml"><img src="https://github.com/dttdrv/phonecode/actions/workflows/checks.yml/badge.svg" alt="Checks"></a>
</p>

PhoneCode runs the agent loop on your device. It reads, writes, and edits files in per-project
workspaces, runs git and a full Alpine Linux userland locally, searches the web, and talks to the
model provider you choose. There is no PhoneCode backend, telemetry, or required account. API keys
live in the Android Keystore, and prompts go only to the selected provider.

## App

<p align="center">
  <img src=".github/assets/screens/conversation.png" width="30%" alt="PhoneCode conversation">
  &nbsp;
  <img src=".github/assets/screens/add-menu.png" width="30%" alt="PhoneCode add menu">
  &nbsp;
  <img src=".github/assets/screens/drawer.png" width="30%" alt="PhoneCode projects drawer">
</p>

<p align="center"><sub>Conversation · Anchored controls · Projects</sub></p>

<p align="center">
  <img src=".github/assets/screens/onboarding.png" width="30%" alt="PhoneCode onboarding">
  &nbsp;
  <img src=".github/assets/screens/settings.png" width="30%" alt="PhoneCode settings">
</p>

<p align="center"><sub>Onboarding · Settings</sub></p>

PhoneCode is an independent project inspired by OpenCode and interoperable with it. It is not a
build of OpenCode.

> **Not affiliated with OpenCode.** PhoneCode is not built by, endorsed by, or affiliated with the
> OpenCode team (Anomaly). The name "OpenCode" appears here only to describe interoperability and
> origins. Parts of the agent (prompt structure, tool schemas, and loop design) are adapted from
> OpenCode, which is MIT licensed (Copyright (c) 2025 opencode); see [LICENSES](#licenses).

## Features

- **On-device agent** with a full coding-agent tool surface (modeled on OpenCode's): read, write,
  edit, glob, grep, ls, apply_patch, todo lists, plan and build modes, user questions, subagents
  (task tool), webfetch with free web search, MCP servers (Streamable HTTP and SSE), and
  progressive-disclosure skills (SKILL.md).
- **Local Linux environment** built from BusyBox and Alpine under PRoot. The agent can install
  Python, Node.js, npm packages, compilers, and other tools with `apk`, run long builds, and manage
  background commands with persistent logs, stdin, and explicit shutdown inside its private
  workspace.
- **Providers**: OpenCode Zen and Go, Anthropic, OpenAI, OpenRouter, Google, xAI, DeepSeek,
  Mistral, and custom endpoints. Models and reasoning levels refresh automatically from models.dev;
  ChatGPT sign-in uses the account's authenticated Codex catalog. You can enable or disable
  providers, hide models, and mark favourites. The agent can add providers and models itself by
  editing `providers.json`, which is hot-reloaded.
- **Sign-in flows**: GitHub uses the OAuth device flow (you type a code, with no tokens to paste)
  for push and pull. "Sign in with ChatGPT" (Codex, OAuth + PKCE) lets you use a paid ChatGPT plan
  as a provider through OpenAI's Responses API - no API key needed.
- **Projects and chats**: chats are organized into projects. Each project is its own workspace
  folder and git repository, with snapshots (commits), branch switching, and push and pull from the
  chat's git button.
- **Phone files**: link only the Android folders you choose, then keep reads and writes approval-gated
  or allow them automatically per your workspace settings.
- **Streaming chat** with reasoning traces, a tool-activity timeline, monochrome syntax
  highlighting, a context-window gauge, and per-model token limits that drive compaction.
- **Crash-safe sessions** with foreground execution, bounded pre-output retries, durable tool
  checkpoints, atomic local storage, and explicit recovery when Android interrupts a turn.
- **Privacy by construction**: keys are encrypted on-device, Android cloud backup is disabled, and
  manual chat/settings exports use a file you choose through the Storage Access Framework.

## Building

Requirements: JDK 21 and the Android SDK (platform 37.0, build-tools 36). Android Studio is not
needed. PhoneCode supports Android 8.0 and newer.

```bash
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
./gradlew :provider:test :tools:test :agent:test :app:testDebugUnitTest :app:lintDebug
```

The project has four modules. `:app` holds the Compose UI and Android glue. `:agent` holds the loop,
prompts, and compaction. `:provider` holds the wire formats and catalog. `:tools` holds the file,
git, web, todo, MCP, and skills tooling. The three library modules are pure JVM and fully
unit-tested.

Production bundles are unsigned unless `PHONECODE_RELEASE_STORE_FILE`,
`PHONECODE_RELEASE_STORE_PASSWORD`, `PHONECODE_RELEASE_KEY_ALIAS`, and
`PHONECODE_RELEASE_KEY_PASSWORD` are supplied as Gradle properties or environment variables. Use a
dedicated upload key with Play App Signing and keep it outside the repository.

## Notes

- The GitHub sign-in ships with the gh CLI's public client id for personal builds. Register your own
  OAuth app (one checkbox: Enable Device Flow) before distributing.
- Codex (Sign in with ChatGPT) authenticates over OAuth + PKCE with a loopback redirect, stores the
  tokens encrypted, and talks to ChatGPT's Responses API backend. It needs a paid ChatGPT plan.
- The Terms of Service and Privacy Policy live in `legal/` and are also shown in-app under
  Settings > About.
- The public Play policy URLs are `https://dttdrv.xyz/phonecode-privacy` and
  `https://dttdrv.xyz/phonecode-terms` after the website repository is deployed.
- The optional remote execution direction is documented in [`MOBILE_BACKEND.md`](MOBILE_BACKEND.md).
  The current release remains on-device.

## Licenses

PhoneCode bundles and adapts open-source work. The full list is in [`THIRD_PARTY.md`](THIRD_PARTY.md)
and in-app under Settings > About > Open-source licenses. In particular:

- **OpenCode** (MIT, Copyright (c) 2025 opencode) - the agent's prompt structure, tool schemas, and
  loop design are adapted from OpenCode. PhoneCode is an independent project and is not affiliated
  with the OpenCode team.
- **Mermaid** (MIT) - inline diagram rendering. **PRoot** (GPL-2.0) and **talloc** (LGPL-3.0) - the
  Linux sandbox. **BusyBox** (GPL-2.0) - the on-device shell toolkit.

Vendored artifacts are pinned in [`VENDORED_CHECKSUMS`](VENDORED_CHECKSUMS) and verified by the
repository checks.
