# Data Safety worksheet

Status: **BLOCKED — provisional mapping, not approved Play Console answers.**

Google defines collection as transmitting user data off the device, including transmission by SDKs
and to third parties. PhoneCode therefore must not select “no data collected” merely because the
developer operates no general-purpose backend. Reconcile this worksheet with the exact signed AAB,
all enabled defaults, and the current [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

## Top-level answers

| Play question | Provisional answer | Evidence or unresolved work |
| --- | --- | --- |
| Does the app collect or share required user data? | **Yes: collect** | Prompts, project context, selected files/photos, credentials, searches, Git operations, MCP calls, and optional reports can leave the device |
| Does the app share user data? | **UNRESOLVED** | Apply Google's current sharing definition and user-initiated-transfer/service-provider exceptions to every recipient; do not assume an exception |
| Is all collected data encrypted in transit? | **UNRESOLVED** | Privacy text says HTTPS “where supported”; verify every built-in path and default, custom endpoint behavior, redirects, Git, package sources, MCP, search, and the reporting endpoint |
| Can users request deletion? | **UNRESOLVED** | Local deletion and uninstall are documented; reports have references for early-deletion requests; third-party deletion uses each service's controls. Verify a public global request path and final Console wording |
| Does the app create a PhoneCode account? | No | The app has no PhoneCode account; provider and Git accounts are third-party accounts |
| Are data collection and sharing optional? | Mixed | Core model use requires a chosen provider; attachments, linked folders, Git, MCP, package installation, search, and AI-output reports are feature-initiated |
| Ads or advertising data use? | No, provisional | Confirm the final dependency graph and AAB contain no ad SDK or advertising surface |
| Analytics or telemetry? | No, provisional | Current privacy policy states none; confirm final code and network trace |

## Data-path inventory

“Play type” is a candidate mapping, not a final taxonomy decision.

| Data path | Candidate Play type | Recipient | Purpose | Choice and retention | Status |
| --- | --- | --- | --- | --- | --- |
| Prompts, chat content, source code, tool results, and workspace context | User-generated content; files and docs; possibly other personal data contained by the user | Selected AI provider or custom endpoint | App functionality | Sent when the user invokes the agent; recipient controls remote retention | Collect: Yes; Share: UNRESOLVED |
| Selected photo attachments | Photos | Selected AI provider or custom endpoint | App functionality | User selects and sends; recipient controls remote retention | Collect: Yes when used; Share: UNRESOLVED |
| Selected file attachments and linked-folder content | Files and docs; possibly other types present in the file | Selected AI provider, Git host, MCP server, search destination, or custom endpoint when directed | App functionality | Feature-initiated; local access persists for linked folders until revoked | Collect: Yes when transmitted; Share: UNRESOLVED |
| Provider, Git, and custom-service credentials or account identifiers | User IDs or another applicable personal-information type | Only the service being authenticated | Account management and app functionality | Stored locally with Keystore-backed encryption when secure storage is available; otherwise PhoneCode does not save them. Codex OAuth is disabled in release | Taxonomy and sharing: UNRESOLVED |
| Git repository data and metadata | Files and docs; user-generated content; possibly user IDs | User-selected Git host | App functionality | Sent by explicit clone, fetch, pull, or push workflows | Collect: Yes when used; Share: UNRESOLVED |
| Search query and fetched web context | Search history or user-generated content | DuckDuckGo or a selected provider search service; requested website | App functionality | Sent when user or agent invokes search/fetch; recipient controls retention | Taxonomy and sharing: UNRESOLVED |
| MCP requests and custom endpoint payloads | Depends on payload | User-configured MCP server or custom endpoint | App functionality | Optional configuration; recipient controls retention | Types, encryption, and sharing: UNRESOLVED |
| Package names, source requests, and ordinary network metadata | App activity or another applicable type | Alpine, npm, pip, or other package/source services | App functionality | Sent when user or agent installs software | Taxonomy and sharing: UNRESOLVED |
| AI-output report category and optional note | App interactions or other user-generated content | `dttdrv.xyz` reporting endpoint | Abuse prevention, safety investigation, and filtering/moderation improvement | Deliberate Send action; scheduled for deletion after 89 days, with daily/request-triggered cleanup targeting a 90-day maximum | Collect: Yes; Share: No from developer, provisional |
| App version and platform attached to an AI-output report | App info and performance or outside required types | `dttdrv.xyz` reporting endpoint | Diagnose reported output | Same 89-day deletion threshold and 90-day retention target | Taxonomy: UNRESOLVED |
| Connecting IP processed for report rate limiting | Approximate location, device identifier, or outside required types | Cloudflare edge and `dttdrv.xyz` reporting endpoint | Security and abuse prevention | Raw IP is not stored by PhoneCode; daily salted hash/counter scheduled for deletion after 24 inactive hours, with a 48-hour retention target | Taxonomy and sharing: UNRESOLVED |
| Model catalog request | No prompt or workspace content documented | `models.dev` | App functionality | Public provider/model metadata refresh | Verify network trace sends no identifier beyond ordinary request metadata |

## Developer-operated endpoint

The privacy policy identifies only the deliberate AI-output reporting endpoint as developer-operated.
It receives the selected category, optional note, app version, platform, and ordinary connection
metadata. It must not receive the AI response, prompt, files, credentials, tool activity, chat
history, provider/model identifier, or a device identifier. Verify those exclusions with a release
network capture before submission.

## Completion checks

- Export the Play Console Data Safety CSV and archive it with the release evidence.
- Inventory every resolved SDK and native payload; include their data practices.
- Exercise onboarding, model calls, attachments, Git, search, web fetch, MCP, custom providers,
  package installation, catalog refresh, and AI-output reporting while capturing destinations and
  payload classes.
- Verify cleartext is blocked or change the encryption answer and public policy accordingly.
- Verify deletion controls without treating **Delete project** as erasure: deleting a chat removes the
  chat, while project removal unlinks the folder, moves remaining chats to Unsorted, and preserves
  private workspace files under Recovered projects. A complete app-private wipe currently requires
  clearing app storage or uninstalling; neither action deletes linked-folder files or third-party data.
- Verify the public report-deletion path and the deletion controls of each third-party recipient.
- Ensure the public privacy policy names PhoneCode and the publishing entity, is active, non-PDF,
  non-geofenced, and linked both in Play Console and in the app.
- Recheck the [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
  and update the form whenever the app's data practices change.
