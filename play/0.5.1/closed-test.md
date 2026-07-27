# Closed testing and production-access evidence

Status: **BLOCKED — applicability and test execution have not been established.**

If the submission uses a personal Play developer account created after November 13, 2023, Google
requires a closed test with at least 12 testers continuously opted in for 14 consecutive days before
the developer can apply for production access. Physical-device verification also applies. Determine
account applicability in [`developer-account.md`](developer-account.md) and follow the current
[production-access guidance](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).

Do not mark this requirement complete from invitations, an internal test, elapsed calendar time
alone, or tester promises. Preserve Play Console evidence that the threshold and continuous opt-in
period were actually satisfied.

## Applicability and track record

| Field | Value |
| --- | --- |
| Account type and creation date | BLOCKED — TODO from [`developer-account.md`](developer-account.md) |
| Requirement applies | BLOCKED — TODO Yes/No with Play Console evidence |
| Physical-device verification | BLOCKED — TODO applicability and completion date |
| Closed track name | BLOCKED — TODO |
| Test country/region availability | BLOCKED — TODO |
| Tester recruitment source | BLOCKED — TODO; do not commit tester personal data |
| Tester support/feedback channel | BLOCKED — TODO monitored channel |
| Opt-in URL tested | BLOCKED — TODO evidence reference; do not expose restricted URLs publicly |
| Exact AAB version and SHA-256 | BLOCKED — TODO; must match the tested release candidate |
| Play-delivered version verified | BLOCKED — TODO device screenshot/log reference |

## Threshold evidence

| Field | Value |
| --- | --- |
| Qualifying opted-in tester count | BLOCKED — TODO, minimum 12 when requirement applies |
| Continuous test start | BLOCKED — TODO timestamp and timezone |
| Continuous test end | BLOCKED — TODO timestamp and timezone, at least 14 consecutive days later |
| Drop below threshold during window | BLOCKED — TODO No; if Yes, restart the qualifying window |
| Play Console threshold evidence | BLOCKED — TODO private evidence reference |
| Production-access application date | BLOCKED — TODO after the qualifying window |
| Production access outcome | BLOCKED — TODO Pending/Approved/Rejected and dated evidence |

## Test coverage for the Play-delivered build

| Area | Required evidence |
| --- | --- |
| Install, first launch, onboarding, upgrade | BLOCKED — TODO device/API matrix and results |
| Dedicated API-key provider setup | BLOCKED — TODO; follow [`reviewer-instructions.md`](reviewer-instructions.md), never record the key |
| Project-folder link, file write/read, explicit cleanup | BLOCKED — TODO; verify project removal is non-destructive |
| Agent turn, approvals, Stop, interruption, recovery | BLOCKED — TODO |
| Foreground-service background/Stop behavior | BLOCKED — TODO against [`foreground-service.md`](foreground-service.md) |
| In-app AI-output report flow | BLOCKED — TODO against [`ai-safety.md`](ai-safety.md) |
| Offline, poor network, endpoint errors | BLOCKED — TODO |
| API 26, 34, 35, 36 and 16 KB device/image coverage | BLOCKED — TODO applicable results |
| Crash, ANR, battery, memory, thermal | BLOCKED — TODO Play and device evidence |
| Accessibility and large text | BLOCKED — TODO findings and disposition |
| Data Safety destination/payload capture | BLOCKED — TODO against [`data-safety.md`](data-safety.md) |

## Feedback and issue disposition

| Field | Value |
| --- | --- |
| Feedback summary owner | BLOCKED — TODO |
| Number of testers who exercised the core path | BLOCKED — TODO aggregate only |
| Highest-severity issue | BLOCKED — TODO None or issue reference |
| Crashes/ANRs observed | BLOCKED — TODO None or resolved issue references |
| Changes made during the qualifying window | BLOCKED — TODO; record whether a new artifact affected the evidence |
| Unresolved release-blocking feedback | BLOCKED — TODO; must be None before submission |
| Final tester communication date | BLOCKED — TODO |

## Release decision

Status remains **BLOCKED** until applicability is confirmed. When the rule applies, it remains
blocked until Play Console shows the qualifying tester count for the full consecutive period, the
tested AAB is identified, tester feedback is resolved, physical-device verification is complete,
and production access is granted.
