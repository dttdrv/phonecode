# AI-generated content safety evidence

Status: **BLOCKED. The in-app report flow exists; the production prompt path has lost its
restricted-content instruction, and no release or operational evidence exists.**

Google requires generative-AI apps to prevent restricted content and to include "in-app user
reporting or flagging features" that work "without needing to exit the app", and says developers
should use reports "to inform content filtering and moderation". See the
[AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en)
and [its guide](https://support.google.com/googleplay/android-developer/answer/14094294?hl=en), both
inspected 24 September 2026. No in-app AI labeling requirement appeared on those pages on that date.

## Source evidence (24 September 2026)

| Control | Source behavior | Status |
| --- | --- | --- |
| Restricted-content instruction | `agent/src/main/.../prompt/SystemBasePrompt.kt` still contains it, but the app no longer uses that prompt. Turns now run in the native runtime with `agentSystemPrompt()` from `app/.../agent/AgentToolset.kt`, which has no restricted-content or abuse instruction. No such text was found in the Misul runtime source either | **BLOCKING regression.** Add the instruction to the production prompt (or the runtime) and test it |
| Report entry point | Each completed response has **Response info** (info icon). Its menu shows Model, Finished, Length, and **Report response** | PENDING device check in the signed build, including TalkBack |
| Report screen | **Send safety feedback** with 8 categories (Hate, Harassment, Sexual content, Violence, Self-harm, Illegal or malicious, Privacy, Other), optional note with a 1,000-character counter, **Send** enabled after a category is chosen | PENDING |
| Payload | `aiReportPayload()`: `version`, `category`, `appVersion`, `platform`, optional `note`. No response text, prompt, files, credentials, provider, model, or device ID | PENDING release capture |
| Endpoint | `POST https://dttdrv.xyz/api/phonecode/report`; 202 shows a `PCR-…` reference, 429 and other errors stay in the flow | PENDING production exercise |
| Retention | `report-backend/src/worker.mjs`: reports 89 days, rate-limit rows 24 inactive hours, daily cron `17 3 * * *`, HMAC daily IP hash | PENDING deployed-version check |
| Tool boundary | Default **Ask before each change**: mutating tools, MCP calls, `git_push`, `process`, and `git_branch` wait for approval. **Allow changes automatically** removes those prompts except for tools on the per-tool approval list. `webfetch` and `websearch` never prompt | PENDING device check of both modes |
| Report content gap | Reports carry no response text, so the triage owner cannot see what was reported unless the user describes it in the note | Decide whether this is enough to "inform filtering and moderation"; document the process either way |

## Prevention test matrix

Record redacted results only. Do not commit harmful test content, keys, or full responses.

| Field | Value |
| --- | --- |
| Signed AAB SHA-256 | PENDING |
| Device / API | PENDING |
| Review provider and model | PENDING |
| Test-set revision and owner | PENDING |
| Sexual content involving minors | PENDING |
| Non-consensual sexual content | PENDING |
| Harassment and hate | PENDING |
| Self-harm encouragement | PENDING |
| Scams, malware, unauthorized access | PENDING |
| Privacy or credential exposure | PENDING |
| Tool use after a refusal | PENDING |
| Indirect prompt injection through files, web fetch, and MCP results | PENDING |
| Residual failures and mitigation | PENDING; any unresolved high-severity failure blocks submission |

## Report-flow evidence

1. PENDING: unedited recording from a completed response to Response info > Report response > Send.
2. PENDING: every category, the note limit, cancel, offline, and 429 paths.
3. PENDING: one synthetic report to production with its reference, timestamp, and redacted server
   receipt.
4. PENDING: request-body capture showing only the documented fields.
5. PENDING: trace of that report through triage, disposition, and deletion.

## Operational owner

| Field | Value |
| --- | --- |
| Report queue owner | PENDING |
| Monitored contact | PENDING |
| Review frequency | PENDING |
| Urgent-harm escalation | PENDING |
| How reports change safeguards | PENDING |
| Last end-to-end exercise | PENDING |
