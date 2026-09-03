package dev.phonecode.app.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.app.R
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.AiReportSubmission
import dev.phonecode.app.agent.ModelOption
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.QuestionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.agent.TurnOutcome
import dev.phonecode.app.ui.components.ContextRing
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulDialogAction
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulTextAction
import dev.phonecode.app.ui.components.StretchSyncedScrollChrome
import dev.phonecode.app.ui.components.MorphingMenu
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.components.predictiveBackTransform
import dev.phonecode.app.ui.components.rememberContentOverscroll
import dev.phonecode.app.ui.components.rememberPredictiveBackMotion
import dev.phonecode.app.ui.components.shortContentVerticalOverscroll
import dev.phonecode.app.ui.components.pressFeedback
import androidx.compose.material3.ripple
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PcMono
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.PhoneTweens
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.todo.TodoItem
import dev.phonecode.tools.todo.TodoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale

/** Feedback stays beside the state it describes; it owns no chat state or retry policy. */
@Composable
internal fun ChatStatus(
    error: String?,
    turnOutcome: TurnOutcome?,
    queued: List<String>,
    interruptedTurn: Boolean,
    retry: dev.phonecode.app.agent.RetryState?,
    notice: String?,
    todos: List<TodoItem>,
    isRunning: Boolean,
    sessionLoading: Boolean,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onClearNotice: () -> Unit,
    onRestoreQueued: () -> Unit,
    onClearQueued: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        error?.let { message ->
            ErrorBanner(
                text = if (turnOutcome == TurnOutcome.FAILED) "$message Partial output may be incomplete." else message,
                actionLabel = if (queued.isEmpty() && (interruptedTurn || turnOutcome == TurnOutcome.FAILED)) "Retry" else null,
                onAction = onRetry,
                onDismiss = onDismissError,
            )
        }
        retry?.let { NoticeBanner("Retrying connection · attempt ${it.attempt} · ${it.message}") }
        notice?.let { message ->
            NoticeBanner(message)
            LaunchedEffect(message) { kotlinx.coroutines.delay(3_500); onClearNotice() }
        }
        if (error == null) {
            turnOutcome?.let { outcome ->
                TurnOutcomeBanner(
                    outcome = outcome,
                    canRetry = outcome == TurnOutcome.FAILED && queued.isEmpty(),
                    onRetry = onRetry,
                )
            }
        }
        if (todos.isNotEmpty()) TodoPanel(todos)
        if (queued.isNotEmpty()) {
            QueuedMessages(
                queued = queued,
                recoverable = !isRunning,
                onRestore = onRestoreQueued,
                onClear = onClearQueued,
            )
        }
        if (sessionLoading) NoticeBanner("Opening chat…")
    }
}


@Composable
internal fun QueuedMessages(
    queued: List<String>,
    recoverable: Boolean,
    onRestore: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (recoverable) {
                    "${queued.size} unsent ${if (queued.size == 1) "follow-up" else "follow-ups"}"
                } else {
                    "${queued.size} queued ${if (queued.size == 1) "follow-up" else "follow-ups"}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (recoverable) colors.onSurface else colors.secondary,
                modifier = Modifier.weight(1f),
            )
            if (recoverable) {
                TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Restore")
                }
                TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Clear")
                }
            }
        }
        if (!recoverable) {
            queued.firstOrNull()?.let { text ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier.widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(colors.surfaceContainerHigh.copy(alpha = 0.45f))
                            .padding(horizontal = 15.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onBackground.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (queued.size > 1) {
                Text(
                    "+${queued.size - 1} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}



@Composable
internal fun NoticeBanner(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(MaterialTheme.shapes.small)
            .background(colors.surface).semantics { liveRegion = LiveRegionMode.Polite }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    }
}

@Composable
internal fun TurnOutcomeBanner(
    outcome: TurnOutcome,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val text = when (outcome) {
        TurnOutcome.STOPPED -> "Turn stopped · Partial output may be incomplete."
        TurnOutcome.FAILED -> "Turn failed · Partial output may be incomplete."
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surface)
            .semantics {
                stateDescription = when (outcome) {
                    TurnOutcome.STOPPED -> "Stopped"
                    TurnOutcome.FAILED -> "Failed"
                }
                liveRegion = LiveRegionMode.Polite
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (outcome == TurnOutcome.STOPPED) Icons.Filled.Stop else Icons.Outlined.Flag,
            null,
            tint = if (outcome == TurnOutcome.FAILED) colors.error else colors.secondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.weight(1f))
        if (canRetry) {
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Retry")
            }
        }
    }
}

@Composable
internal fun ErrorBanner(
    text: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(MaterialTheme.shapes.small)
            .background(colors.errorContainer).semantics {
                error(text)
                liveRegion = LiveRegionMode.Polite
            }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.onErrorContainer, modifier = Modifier.weight(1f))
        actionLabel?.let {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Text(it, color = colors.onErrorContainer)
            }
        }
        MisulIconButton(Icons.Filled.Close, "Dismiss", onClick = onDismiss)
    }
}

@Composable
internal fun TodoPanel(todos: List<TodoItem>) {
    val colors = MaterialTheme.colorScheme
    // Compact + collapsible: a one-line summary by default (it floats over the transcript, so a full list
    // was occluding the latest messages). Tap to expand the full plan, capped and scrollable.
    var expanded by remember { mutableStateOf(false) }
    fun iconOf(s: TodoStatus) = when (s) {
        TodoStatus.PENDING -> Icons.Outlined.RadioButtonUnchecked
        TodoStatus.IN_PROGRESS -> Icons.Outlined.Schedule
        TodoStatus.COMPLETED -> Icons.Filled.CheckCircle
        TodoStatus.CANCELLED -> Icons.Filled.Close
    }
    fun labelOf(s: TodoStatus) = when (s) {
        TodoStatus.PENDING -> "Task pending"
        TodoStatus.IN_PROGRESS -> "Task in progress"
        TodoStatus.COMPLETED -> "Task completed"
        TodoStatus.CANCELLED -> "Task cancelled"
    }
    fun colorOf(s: TodoStatus) = when (s) {
        TodoStatus.COMPLETED, TodoStatus.CANCELLED -> colors.tertiary
        TodoStatus.IN_PROGRESS -> colors.onBackground
        TodoStatus.PENDING -> colors.secondary
    }
    val done = todos.count { it.status == TodoStatus.COMPLETED }
    val active = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS }
        ?: todos.firstOrNull { it.status == TodoStatus.PENDING }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(MaterialTheme.shapes.small)
            .background(colors.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable { expanded = !expanded }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Tasks $done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
            if (active != null) {
                Icon(
                    iconOf(active.status),
                    labelOf(active.status),
                    tint = colorOf(active.status),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    active.content, style = MaterialTheme.typography.labelMedium, color = colors.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                tint = colors.tertiary, modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp)
                    .contentVerticalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            iconOf(todo.status),
                            labelOf(todo.status),
                            tint = colorOf(todo.status),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(todo.content, style = MaterialTheme.typography.labelMedium, color = colorOf(todo.status))
                    }
                }
            }
        }
    }
}
