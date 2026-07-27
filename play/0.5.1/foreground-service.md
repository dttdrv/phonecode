# Foreground-service declaration and demo

Status: **BLOCKED — Play Console text is drafted; release-build evidence and approval are missing.**

## Declared service

| Field | Release candidate value |
| --- | --- |
| Service | `dev.phonecode.app.agent.TurnService` |
| Exported | `false` |
| Foreground service type | `specialUse` |
| Permissions | `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`, `android.permission.POST_NOTIFICATIONS` |
| Manifest subtype | `User-started on-device coding agent turns and development processes that continue while PhoneCode is backgrounded` |
| Notification | `PhoneCode is working` / `Agent work and local processes remain active.` |
| User control | Persistent notification with a `Stop` action |

## Play Console draft

**Functionality using `specialUse`**

PhoneCode runs coding-agent turns and user-started local development processes. A turn can read and
edit a selected project, call the user's chosen model, and run development tools. These tasks may
continue after the user leaves the app so a long model response or build is not silently abandoned.
The service starts only for work the user explicitly starts and stops when no active turn or managed
process remains. While it runs, PhoneCode displays an ongoing notification that opens the app and
offers a Stop action.

On Android 13 and newer, PhoneCode requests notification permission after the user starts their
first accepted agent turn. Denial does not block the user-started work; Android still exposes the
foreground service in Task Manager, while the notification-drawer surface remains unavailable.

**Impact if deferred**

A user-started model turn, build, or development command may fail to begin before the app leaves the
foreground. The user would reasonably believe that submitted work was running when it was not.

**Impact if interrupted**

The active model stream or local process may stop before producing its result, and a partially
completed tool workflow may need to be resumed. PhoneCode persists session checkpoints and reports
interruption rather than claiming the work completed.

Do not submit this text unless the exact release build demonstrates every statement. Google permits
`specialUse` only in limited cases, reviews the free-form manifest subtype, and requires the use to
be core, user-initiated or perceptible, user-stoppable, non-deferrable without harm, and limited to
the necessary duration. See [foreground-service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
and the Android [`specialUse` documentation](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

## Evidence video script

Record one continuous, unedited video from the exact signed release candidate. Keep system status,
the app, and the notification shade readable. Do not expose credentials or personal project data.

1. Show PhoneCode's About/version screen, then open a disposable local project.
2. Start an agent task or local process that safely runs long enough to demonstrate background work.
3. Immediately return to the launcher and open the notification shade.
4. Show the ongoing `PhoneCode is working` notification, its accurate description, and the `Stop`
   action while the task is genuinely active.
5. Tap the notification to return to PhoneCode and show that the same user-started work is still in
   progress or has completed.
6. Start the demonstration task again, background the app, open the notification shade, and tap
   `Stop`.
7. Return to PhoneCode and show that the turn and managed process stopped and that the notification
   disappeared. If the release build cannot visibly prove this, fix or clarify the behavior before
   recording the submission video.

Upload the video using the method accepted by Play Console, confirm reviewers can open it without a
request or expiring login, and record its URL in the evidence index. Google requires a description,
defer/interruption impact, and a feature video for each declared type; see [Play Console's declaration guidance](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en-GB).

## Release checks

- Verify the final merged manifest contains only the foreground-service types and permissions used.
- Verify the service cannot start from an unsolicited background event.
- Verify notification text always describes real active work and is removed promptly afterward.
- Verify Stop cancels the agent turn, terminates every managed process and VM, and releases the wake
  lock on API 26, 34, 35, 36, and the final target API.
- Verify process death, force stop, reboot, low memory, denied notifications, timeouts, and rapid
  start/stop cycles do not leave work or wake locks running.
- Record duration, battery, memory, thermal, and idle-CPU evidence for long tasks.
- Reconcile the declaration after any lifecycle or runtime change. The current [Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-GB)
  governs the final submission.
