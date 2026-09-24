package dev.phonecode.app.agent

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.phonecode.app.R
import dev.phonecode.tools.todo.TodoStatus

internal enum class TurnPhase { RUNNING, NEEDS_APPROVAL, DONE, FAILED }

internal data class TurnStatus(
    val phase: TurnPhase,
    val text: String,
    val done: Int = 0,
    val total: Int = 0,
) {
    val ongoing: Boolean get() = phase == TurnPhase.RUNNING || phase == TurnPhase.NEEDS_APPROVAL
}

/** Maps chat state to what the turn notification shows; null means "no turn to report" (e.g. user stopped it). */
internal fun turnStatusOf(state: ChatUiState): TurnStatus? = when {
    state.isRunning && state.pendingPermission != null ->
        TurnStatus(TurnPhase.NEEDS_APPROVAL, state.pendingPermission.summary.ifBlank { "Approve ${state.pendingPermission.tool}?" })
    state.isRunning && state.pendingQuestion != null ->
        TurnStatus(TurnPhase.NEEDS_APPROVAL, "The agent has a question for you.")
    state.isRunning -> state.todos.filter { it.status != TodoStatus.CANCELLED }.let { steps ->
        TurnStatus(
            TurnPhase.RUNNING,
            steps.firstOrNull { it.status == TodoStatus.IN_PROGRESS }?.content ?: "Working on your request.",
            done = steps.count { it.status == TodoStatus.COMPLETED },
            total = steps.size,
        )
    }
    state.turnOutcome == TurnOutcome.STOPPED -> null
    state.turnOutcome == TurnOutcome.FAILED -> TurnStatus(TurnPhase.FAILED, "Open PhoneCode to see what went wrong.")
    else -> TurnStatus(TurnPhase.DONE, "Tap to review the result.")
}

/**
 * One builder for every turn state. Ongoing states request promotion so Android 16 Live Updates
 * (status-bar chip) and OEM surfaces that consume promoted/progress notifications (Samsung Now Bar,
 * HyperOS Super Island, Honor Magic Capsule, ...) can pick them up. NotificationCompat drops the
 * API 36 extras on older releases, where the legacy progress bar + chronometer remain.
 */
internal fun buildTurnNotification(context: Context, status: TurnStatus?, startedAt: Long): Notification {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(context, dev.phonecode.app.MainActivity::class.java)
    val open = PendingIntent.getActivity(
        context,
        0,
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = NotificationCompat.Builder(context, TURN_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_phonecode)
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
    if (status == null || status.ongoing) {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, TurnService::class.java).setAction(TurnService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setOngoing(true).addAction(0, "Stop", stop)
    }
    if (status == null) {
        // Only background processes hold the service: keep the quiet, unpromoted notice.
        return builder
            .setContentTitle("PhoneCode is working")
            .setContentText("Agent work and local processes remain active.")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    when (status.phase) {
        TurnPhase.RUNNING, TurnPhase.NEEDS_APPROVAL -> {
            val approval = status.phase == TurnPhase.NEEDS_APPROVAL
            val steps = status.total > 0 && !approval
            builder
                .setContentTitle(if (approval) "PhoneCode needs approval" else "PhoneCode is working")
                .setContentText(status.text)
                .setCategory(if (approval) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_PROGRESS)
                // A pending approval is an alert, which Live Updates must not carry: it stays an
                // ordinary ongoing notification until the turn resumes.
                .setRequestPromotedOngoing(!approval)
                // Status-bar chip: Android suggests at most 7 characters.
                .setShortCriticalText(if (approval) null else if (steps) "${status.done}/${status.total}" else "Running")
                .setDeleteIntent(
                    PendingIntent.getBroadcast(
                        context,
                        2,
                        Intent(TurnService.ACTION_DISMISSED).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setWhen(startedAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setProgress(status.total, status.done, !steps)
                .setStyle(
                    // Not `apply {}`: Style has its own apply(builder) member that would shadow it.
                    NotificationCompat.ProgressStyle().also { style ->
                        if (steps) {
                            repeat(status.total) { style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(1)) }
                            style.setProgress(status.done)
                        } else {
                            style.setProgressIndeterminate(true)
                        }
                    },
                )
        }
        TurnPhase.DONE, TurnPhase.FAILED -> builder
            .setContentTitle(if (status.phase == TurnPhase.DONE) "PhoneCode finished" else "PhoneCode stopped with an error")
            .setContentText(status.text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
    }
    return builder.build()
}

internal const val TURN_CHANNEL_ID = "turn"
