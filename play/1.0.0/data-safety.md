# Data safety worksheet

Status: **BLOCKED. Provisional mapping from source, not approved Play Console answers.**

Google treats transmitting user data off the device as collection, including transmission to third
parties. On-device-only processing is not disclosed. Sharing means transferring collected data to a
third party, with exceptions for service providers, legal purposes, anonymized data, and transfers
"based on a specific user-initiated action, where the user reasonably expects the data to be
shared". See [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en),
inspected 24 September 2026.

Every row below was traced to source on 24 September 2026. None has been confirmed by a release
network capture.

## Top-level answers

| Play question | Provisional answer | Basis or open decision |
| --- | --- | --- |
| Does the app collect or share required user data? | **Yes** | Prompts, files, photos, personalization, search queries, MCP calls, GitHub sign-in, and reports leave the device |
| Is any data shared? | **PENDING decision** | Every recipient except the report endpoint is chosen and triggered by the user (provider, plugin, GitHub, search). The user-initiated exception looks applicable, but agent-initiated `webfetch`/`websearch` calls are not a specific user action. Decide and record the reasoning before answering |
| Is all user data encrypted in transit? | **Yes, provisional** | `network_security_config.xml` blocks cleartext except localhost; custom providers and MCP accept HTTP only for localhost; web tools require HTTPS; Git requires HTTPS. Confirm with a capture, including JGit and redirects |
| Can users request deletion? | **Yes, provisional** | Local data: in-app delete controls, clear storage, uninstall. Reports: auto-deleted within 90 days, and early deletion by reference through the contact form. Third-party data: each service's controls |
| Is data collection optional? | Mixed | A model provider is required for agent use. Everything else is feature-triggered |
| Account creation | None | PhoneCode has no account. Provider, GitHub, and plugin accounts are third-party |
| Ads, analytics, telemetry | No, provisional | No ad or analytics SDK in `THIRD_PARTY.md`; confirm from the release SBOM |

## Data-path inventory

"Play type" is a candidate mapping only.

| Data path | Candidate Play type | Recipient | Trigger | Source | Status |
| --- | --- | --- | --- | --- | --- |
| Prompts, chat history, tool results, workspace content | Messages or other user-generated content; files and docs | Selected model provider or custom endpoint | User sends a turn | `runtime/MisulRuntimeController.kt` | Collect: Yes. Share: PENDING |
| Photo attachments (re-encoded ≤1600 px, base64) | Photos | Selected model provider | User picks a photo and sends | `ui/chat/ChatScreen.kt` | Collect: Yes when used |
| Text-file attachments (inlined into the prompt) | Files and docs | Selected model provider | User picks a file and sends | `ui/chat/ChatScreen.kt` | Collect: Yes when used |
| Personalization: preferred name, occupation, About you, custom instructions | Name; other personal info | Selected model provider | Sent in the system prompt while **Use personalization** is on | `agent/ProjectInstructions.kt` | Collect: Yes when filled in. Name must be declared |
| Linked-folder content | Files and docs | Model provider, MCP server, or GitHub, when the agent uses it | Agent tool call | `agent/AndroidSharedFileAccess.kt` | Collect: Yes when transmitted |
| OpenCode conversation header `x-opencode-session` | Possibly app activity or device or other IDs | OpenCode | Every OpenCode request | `runtime/MisulRuntimeController.kt` | Taxonomy: PENDING. Random per conversation, not a device ID |
| Provider API keys, ChatGPT tokens (debug/sideload only) | Not user data sent elsewhere; credential to its own service | The issuing service only | Each request | `data/SecureKeyStore.kt` | Confirm Console treatment |
| GitHub device sign-in and username lookup | User IDs | github.com, api.github.com | User taps Sign in | `auth/GitHubAuth.kt` | Collect: Yes when used |
| Git pull/push content and credentials | Files and docs; user IDs | GitHub (push restricted to GitHub HTTPS) | Agent `git_pull`/`git_push` after approval | `tools/.../git/GitTools.kt` | Collect: Yes when used |
| Web search query | Search history or other user-generated content | DuckDuckGo (`html.duckduckgo.com`) | Agent `websearch`, no approval prompt | `tools/.../web/WebSearchTool.kt` | Collect: Yes. Share: PENDING |
| Web fetch URL | App activity or other | Any public HTTPS site the agent chooses | Agent `webfetch`, no approval prompt | `tools/.../web/WebFetchTool.kt` | Collect: Yes. Share: PENDING |
| MCP tool calls and plugin tokens | Depends on payload | The plugin endpoint (GitHub Copilot MCP, Stripe, DeepWiki, Microsoft Learn, AWS Knowledge, Cloudflare) or a custom MCP URL | Plugin added by the user, calls approved per the approval policy | `ui/settings/PluginSettings.kt`, `tools/.../mcp/McpClient.kt` | Collect: Yes when used. Types: PENDING |
| Model catalog refresh | None expected | models.dev | App start, 6-hour cache | `provider/.../CatalogLoader.kt` | Confirm no identifiers beyond request metadata |
| AI-output report: category, optional note, app version, platform | Other user-generated content; app info | `dttdrv.xyz` (developer, Cloudflare Worker + D1) | User taps Report response then Send | `agent/ChatViewModel.kt`, `report-backend/src/worker.mjs` | Collect: Yes. Share: No. Deleted within 90 days |
| Report rate limiting: daily keyed IP hash | Possibly device or other IDs | `dttdrv.xyz` | Same | `report-backend/src/worker.mjs` | Taxonomy: PENDING. Raw IP not stored, 48-hour target |
| Package downloads (command runtime) | App activity | Package mirrors | Only when a shell backend exists; release has none today | `runtime/ShellBackendFactory.kt` | Re-evaluate if the VM ships |

Not collected: location, contacts, calendar, microphone, camera, device identifiers, advertising
ID, health, financial data entered in the app, crash logs, analytics. The app declares no permission
that would provide them.

## Completion checks

- Decide the sharing answer for model providers, plugins, and agent-initiated web requests, and
  write the reasoning here.
- Run the release build through a proxy (synthetic keys only) for: onboarding, a turn with a photo,
  a text-file attachment, personalization on and off, web search, web fetch, GitHub sign-in, Git
  push, one plugin with and without a token, a custom localhost provider, catalog refresh, and a
  report. Record destinations and payload classes.
- Confirm that no destination receives cleartext.
- Export the completed Play Console form and archive it with the release evidence.
- Keep this worksheet, `legal/privacy.md`, and the live privacy URL consistent after every change.
