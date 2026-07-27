# AI-generated content safety evidence

Status: **BLOCKED — the source contains prevention and reporting controls, but release-build and
operational evidence is incomplete.**

Google requires apps that generate AI content to prevent restricted content and provide an in-app
way to report or flag offensive content. This worksheet covers both obligations for the exact 0.5.1
release candidate; see the [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en).

## Current source evidence

| Control | Source-level behavior | Release evidence status |
| --- | --- | --- |
| Restricted-content instruction | `SystemBasePrompt.kt` instructs the agent not to generate child sexual exploitation, non-consensual sexual content, targeted harassment, encouragement of self-harm, scams, or deceptive official documents | BLOCKED — test against every review provider/model and adversarial prompt class |
| Tool permission boundary | **Settings > Files & permissions > Approval policy** defaults to **Ask before each change**; mutating actions then show **Approve agent action?** with **Deny** and **Approve once**. **Allow changes automatically** suppresses workspace-change prompts, while reads outside the active workspace and user-linked folders always require explicit approval. Untrusted repository, web, MCP, and tool-result text is described as data rather than authority | BLOCKED — verify both approval modes and every release tool surface on device |
| In-app feedback entry | Completed assistant responses expose **Send safety feedback** without sending the user to a browser | BLOCKED — verify visibility and accessibility in the Play-delivered build |
| Report categories | Hate, harassment, sexual content, violence, self-harm, illegal or malicious activity, privacy, and other | BLOCKED — verify every category can be selected and submitted |
| Report payload | Source builds a payload containing category, optional note, app version, and `android`; the AI response, prompt, files, credentials, tool activity, chat history, provider/model, and device identifier are not added by the app | BLOCKED — confirm with a release network capture and endpoint logs |
| Endpoint response | HTTP 202 is treated as accepted and may display a returned reference; rate limits and other errors stay in the report flow | BLOCKED — exercise success, 429, timeout, offline, and server-error paths |
| Retention and deletion | The checked-in Worker uses 89-day and 24-hour deletion thresholds, runs cleanup on report submission, and declares a daily Cron trigger to target the public 90-day and 48-hour maximums | BLOCKED — deploy the checked source and capture production scheduled/triggered deletion evidence |
| Moderation feedback loop | The UI and privacy text say reports improve safeguards and filtering/moderation | BLOCKED — document who reviews reports, response targets, escalation, and how confirmed reports change safeguards |

Source presence is not proof that the exact release artifact or production endpoint behaves as
described. Any mismatch blocks submission and requires the lower-priority document or implementation
to be corrected before the Data Safety and AI-content declarations are submitted.

## Required prevention test matrix

Complete the table with redacted evidence. Do not paste harmful test content, user data, provider
keys, or full model responses into this repository.

| Field | Required value |
| --- | --- |
| Signed AAB SHA-256 | BLOCKED — TODO |
| Play-delivered version/device/API | BLOCKED — TODO |
| Dedicated review provider and model | BLOCKED — TODO |
| Test-set revision and owner | BLOCKED — TODO |
| Sexual exploitation of minors | BLOCKED — TODO result and evidence reference |
| Non-consensual sexual content | BLOCKED — TODO result and evidence reference |
| Targeted harassment and hate | BLOCKED — TODO result and evidence reference |
| Self-harm encouragement | BLOCKED — TODO result and evidence reference |
| Scams, malware, and unauthorized access | BLOCKED — TODO result and evidence reference |
| Privacy or credential exposure | BLOCKED — TODO result and evidence reference |
| Tool-use attempts after refusal | BLOCKED — TODO result and evidence reference |
| Indirect prompt injection through files, web, and MCP | BLOCKED — TODO result and evidence reference |
| Residual failures and mitigation | BLOCKED — TODO; unresolved high-severity failure blocks submission |

## Required report-flow evidence

1. BLOCKED — TODO: record an unedited run from a completed AI response to **Send safety feedback**.
2. BLOCKED — TODO: verify all categories, the 1,000-character note limit, cancel, and offline retry.
3. BLOCKED — TODO: with approved synthetic content only, submit one test report and record its
   returned reference, timestamp, endpoint environment, and redacted server receipt.
4. BLOCKED — TODO: capture the request body and prove it contains only the documented fields.
5. BLOCKED — TODO: demonstrate rate limiting without storing a raw IP in application data.
6. BLOCKED — TODO: trace the test report through triage, disposition, any safeguard change, and
   deletion. Keep operational evidence outside the public repository when it contains sensitive data.

## Operational owner template

| Field | Value |
| --- | --- |
| Report queue owner | BLOCKED — TODO |
| Monitored contact | BLOCKED — TODO; do not record private credentials here |
| Review frequency | BLOCKED — TODO |
| Urgent-harm escalation target | BLOCKED — TODO |
| Abuse and false-report handling | BLOCKED — TODO |
| Safeguard-change record location | BLOCKED — TODO |
| Last end-to-end exercise | BLOCKED — TODO date and evidence reference |
| Open incidents or policy warnings | BLOCKED — TODO; must be none or resolved before submission |

## Release decision

Status remains **BLOCKED** until prevention, report submission, endpoint minimization, retention,
moderation ownership, and feedback-loop evidence all refer to the same signed AAB and production
configuration used for Play review.
