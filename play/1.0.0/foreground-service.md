# Foreground-service declaration and demo

Status: **BLOCKED. Console text is drafted from source; no signed build, video, or Play acceptance
exists.**

## Declared service (source, 24 September 2026)

| Field | Value | Source |
| --- | --- | --- |
| Service | `dev.phonecode.app.agent.TurnService`, `exported="false"` | `AndroidManifest.xml` |
| Type | `specialUse`; `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on API 34+ | `agent/TurnService.kt` |
| Permissions | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS` | `AndroidManifest.xml` |
| Subtype property | `User-started on-device coding agent turns and development processes that continue while PhoneCode is backgrounded` | `AndroidManifest.xml` |
| Start triggers | A user-sent turn (`ChatViewModel.beginLease("turn")`) or an agent-started process lease in the shell backend. No boot receiver, alarm, job, or push trigger exists | `ChatViewModel.kt`, `ForegroundLeaseManager.kt`, `runtime/ShellBackendFactory.kt` |
| Stop | Stops when no lease remains. The notification's **Stop** action cancels the turn and stops all managed processes | `TurnService.stopWork`, `ChatViewModel` stop handlers |
| Wake lock | One partial wake lock, no timeout, released in `onDestroy` | `TurnService.onStartCommand` |
| Channel | `Agent activity`, `IMPORTANCE_LOW` | `TurnService.ensureChannel` |

### Notification states

| State | Title | Text | Extras |
| --- | --- | --- | --- |
| Turn running | `PhoneCode is working` | Current todo step, or `Working on your request.` | Ongoing, **Stop**, chronometer, `ProgressStyle`, requests promotion; chip text `n/m` or `Running` |
| Waiting for approval or answer | `PhoneCode needs approval` | Approval summary, or `The agent has a question for you.` | Ongoing, **Stop**, requests promotion; chip text `Approve` |
| Only background processes | `PhoneCode is working` | `Agent work and local processes remain active.` | Ongoing, **Stop**, not promoted |
| Finished or failed (app not visible) | `PhoneCode finished` / `PhoneCode stopped with an error` | `Tap to review the result.` / `Open PhoneCode to see what went wrong.` | Auto-cancel, separate ID; posted after the service stops |

On Android 16, promotion makes the ongoing states eligible as
[Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update) (inspected
24 September 2026). The builder meets the published criteria: ongoing, has a title, `ProgressStyle`,
no custom views, not colorized, channel above `IMPORTANCE_MIN`. A dismissed Live Update is not
reposted during the same turn (`setDeleteIntent` → `liveDismissed`). Google's guidance lists chat
messages and alerts as inappropriate uses. The approval state is part of the user's own running task,
but review that wording before submission.

On Android 13+, `MainActivity` requests `POST_NOTIFICATIONS` the first time a turn runs. Denial does
not block the turn; the service still runs and appears in the system Task Manager.

## Play Console draft

Play Console: **Monitor and improve > App content > Foreground service permissions**. Google asks
for a description, the impact of deferral or interruption, and a video link for each type. See
[Understanding foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
and the FGS section of [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en),
both inspected 24 September 2026.

**Use case:** Other (special use).

**Description**

PhoneCode is a coding agent. When the user sends a request, the agent calls the user's chosen AI
model and runs tools on the user's project: reading and editing files, Git, web lookups, and MCP
tool calls. One request often takes several minutes. PhoneCode starts a foreground service only
when the user sends a request (or when that work starts a managed process) and stops it as soon as
the work ends. While it runs, an ongoing notification shows the current step and a Stop action; on
Android 16 it can appear as a Live Update. If the agent needs approval, the notification says so.

**Impact if deferred**

The user's request would not start or would stall until they reopen the app. They would return to
find no progress even though they had submitted the work.

**Impact if interrupted**

The model response stream is cut off and a multi-step change can stop halfway, leaving files
partly edited. PhoneCode reports the interruption rather than claiming success, but the user must
re-run the request.

Do not submit until the signed build demonstrates every sentence above.

## Video script

Record one continuous, unedited video from the Play-delivered build (internal track). Use a
disposable project and a synthetic or reviewer-only key. Keep the status bar and notification shade
readable.

1. Open **Settings > About** to show the version.
2. Open a disposable project and send a request that takes at least a minute (for example: create
   five files and summarize them).
3. Go to the home screen and open the notification shade. Show `PhoneCode is working`, the current
   step, and **Stop**. On an Android 16 device, also show the status-bar chip.
4. Tap the notification, show the same request still running or finished.
5. Send another long request, leave the app, and tap **Stop** in the notification.
6. Reopen PhoneCode and show that the turn stopped and the notification is gone.
7. Optional: trigger an approval with the default **Ask before each change** policy and show
   `PhoneCode needs approval` in the notification.

Host the video where reviewers can open it without signing in (for example an unlisted YouTube
link) and record the URL in `submission-evidence.json`.

## Release checks (all PENDING)

- The signed AAB's merged manifest contains only `specialUse` and the permissions above.
- The service never starts without a user-sent turn or agent-started process.
- **Stop** cancels the turn, stops every managed process, removes the notification, and releases
  the wake lock on API 26, 34, 35, and 36.
- Process death, force stop, reboot, denied notifications, and rapid start/stop leave no service or
  wake lock running.
- Live Update appears, updates, and stays dismissed on an Android 16 device; older versions show
  the standard progress notification.
- Battery and wake-lock time for a 10-minute turn are within Android vitals thresholds. The wake
  lock has no timeout, so confirm it is always released.
