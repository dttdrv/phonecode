# Closed testing and production access

Status: **BLOCKED. Applicability is unknown and no test has run.**

For personal developer accounts created after 13 November 2023, Google requires a closed test with
at least 12 testers opted in continuously for 14 consecutive days before you can apply for
production access. A tester who opts out and back in restarts their count. See
[Testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en),
inspected 24 September 2026.

Plan at least three weeks: one to recruit and build, two for the qualifying window. Every tester
needs an arm64 Android device (the bundle ships `arm64-v8a` only) and their own model key or a
test key you provide privately.

| Field | Value |
| --- | --- |
| Account type and creation date | PENDING, see [`developer-account.md`](developer-account.md) |
| Requirement applies | PENDING |
| Closed track name | PENDING |
| AAB version and SHA-256 under test | PENDING |
| Opted-in testers (minimum 12) | PENDING |
| Window start / end (≥14 consecutive days) | PENDING |
| Tester feedback channel | PENDING |
| Production-access application date and result | PENDING |

## Coverage for the tested build

| Area | Status |
| --- | --- |
| Install, onboarding, provider key, first turn | PENDING |
| Linked folder read/write, project delete keeps files | PENDING |
| Approval prompts in both approval modes | PENDING |
| Background turn, notification, Stop, Live Update on Android 16 | PENDING |
| GitHub sign-in, commit, push | PENDING (needs the PhoneCode-owned OAuth client) |
| One plugin with a token and one without | PENDING |
| Photo and text attachments | PENDING |
| Report response flow | PENDING |
| Offline and provider errors | PENDING |
| API 26, 34, 35, 36 and a 16 KB page-size device | PENDING |
| Crashes, ANRs, battery, accessibility, large text | PENDING |
