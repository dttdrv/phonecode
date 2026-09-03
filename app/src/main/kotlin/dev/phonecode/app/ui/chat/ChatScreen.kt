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
import androidx.compose.runtime.SideEffect
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





@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenModelSetup: () -> Unit,
    onOpenProviderSetup: (String) -> Unit,
    sendOnEnter: Boolean = true,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val modelConfigured = state.selected?.let { vm.providerConfigured(it.providerId) } == true
    val rootView = LocalView.current
    val composerKey = "${state.currentProjectId.orEmpty()}:${state.currentSessionId}"
    var input by rememberSaveable(composerKey) { mutableStateOf("") }
    val photos = state.draftPhotos[composerKey].orEmpty()
    // Round-4: the custom morphing popouts are retired for standard M3 modal bottom sheets
    // ("improve the pop-out menus, substantially. Maybe use the default Material3 Expressive
    // for now") - platform motion and scrim, native back/swipe dismissal, zero morph jank.
    var modelOpen by remember { mutableStateOf(false) }
    var pendingProviderSetup by remember { mutableStateOf<String?>(null) }
    var contextOpen by remember { mutableStateOf(false) }
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    var bottomOverlayHeight by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val listCanScroll = listState.canScrollBackward || listState.canScrollForward
    var followOutput by remember(state.currentSessionId) { mutableStateOf(true) }
    val listOverscroll = rememberContentOverscroll()
    val scope = rememberCoroutineScope()
    // Classify a timeline update before deciding which LazyColumn rows happen to be composed.
    // That keeps deferred/offscreen rows from acquiring an entrance just because they are later
    // scrolled into view.
    val appendTransitions = remember { ChatAppendTransitionTracker() }
    appendTransitions.observe(
        sessionId = state.currentSessionId,
        timelineEpoch = state.timelineEpoch,
        lines = state.lines,
        followOutput = followOutput,
    )
    val empty = state.lines.isEmpty() && state.streaming.isEmpty() && state.streamingReasoning.isEmpty()
    val blurTopBand = !empty && listState.canScrollBackward
    val blurBottomBand = !empty && listState.canScrollForward
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val attachContext = LocalContext.current
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val mime = attachContext.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) {
                val photo = withContext(Dispatchers.IO) { readPhoto(attachContext, uri) }
                if (photo == null) {
                    vm.surfaceError("Couldn't read that photo.")
                } else {
                    vm.setDraftPhotos(composerKey, listOf(photo))
                }
            } else {
                val attached = withContext(Dispatchers.IO) { readAttachment(attachContext, uri) }
                when (attached) {
                    null -> vm.surfaceError("Couldn't read that file.")
                    is Attachment.Binary -> vm.surfaceError("Choose a photo or text file.")
                    is Attachment.Text -> input = buildString {
                        append(input)
                        if (input.isNotBlank()) append("\n\n")
                        append("File: ").append(attached.name).append("\n```\n").append(attached.content).append("\n```")
                    }
                }
            }
        }
    }

    LaunchedEffect(listState, state.currentSessionId) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canScrollForward) ->
            if (scrolling) followOutput = !canScrollForward
        }
    }

    LaunchedEffect(state.currentSessionId, state.lines.size) {
        if (state.lines.lastOrNull() is ChatLine.User) followOutput = true
    }

    val autoScrollTarget = state.lines.size +
        if (state.streamingReasoning.isNotEmpty() || state.streaming.isNotEmpty()) 1 else 0
    LaunchedEffect(state.currentSessionId, autoScrollTarget, followOutput) {
        if (autoScrollTarget > 0 && followOutput) listState.scrollToItem(autoScrollTarget - 1)
    }

    var observedCompletion by remember { mutableStateOf(state.lastCompletedAt) }
    LaunchedEffect(state.lastCompletedAt) {
        val completedAt = state.lastCompletedAt
        if (completedAt != null && completedAt != observedCompletion) {
            observedCompletion = completedAt
            if (state.error == null) {
                rootView.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.view.HapticFeedbackConstants.CONFIRM
                    else android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                )
            }
        }
    }

    // NOTE: no imePadding anywhere in this screen - the root container applies safeDrawing
    // (bars + IME) exactly once; adding it again here is what flung the composer off-screen.
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // v2 chrome: NOTHING pads the top or bottom - the conversation fills the whole screen and
        // FEEDS the blur; every piece of chrome floats above it as an individually blurred pill
        // (signed prototype: design/v2.html).
        val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topChromeHeight = Spacing.navBarHeight + 20.dp
        val chromeDensity = LocalDensity.current
        StretchSyncedScrollChrome(
            modifier = Modifier.fillMaxSize(),
            showTop = blurTopBand,
            showBottom = blurBottomBand,
            topHeight = statusInset + topChromeHeight + 12.dp,
            bottomHeight = with(chromeDensity) { bottomOverlayHeight.toDp() } + 12.dp,
        ) { _ ->
            // New-chat transition: conversation fades out, empty state fades in (chatgpt-motion.md
            // - a fade, never a slide; exits faster than enters).
            AnimatedContent(
                targetState = empty,
                transitionSpec = {
                    fadeIn(tween(220, easing = PhoneEasings.easeOut)) togetherWith
                        fadeOut(tween(180, easing = PhoneEasings.easeOut))
                },
                label = "emptySwap",
                modifier = Modifier.fillMaxSize(),
            ) { isEmpty ->
                Box(
                    Modifier.fillMaxSize()
                        .then(
                            if (isEmpty) {
                                Modifier.padding(
                                    top = statusInset + topChromeHeight,
                                    bottom = with(chromeDensity) { bottomOverlayHeight.toDp() } + 18.dp,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .shortContentVerticalOverscroll(
                            enabled = isEmpty || !listCanScroll,
                            effect = listOverscroll,
                        )
                        .background(colors.background),
                ) {
                if (isEmpty) {
                    AnimatedVisibility(
                        visible = !imeVisible,
                        enter = fadeIn(tween(150, easing = PhoneEasings.easeOut)),
                        exit = fadeOut(tween(120, easing = PhoneEasings.easeOut)),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        EmptyState(
                            modelConfigured = modelConfigured,
                            onSuggestion = { input = it },
                            onOpenModelSetup = onOpenModelSetup,
                        )
                    }
                } else {
                    val lastAssistantIndex = state.lines.indexOfLast { it is ChatLine.Assistant }
                    // No imeNestedScroll: its scroll-to-show-IME behavior meant dragging the list
                    // after typing pulled the KEYBOARD open (device feedback) - the keyboard
                    // should only ever come from the text field.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        overscrollEffect = listOverscroll.takeIf { listCanScroll },
                        userScrollEnabled = listCanScroll,
                        // Padding clears the floating chrome at rest while letting scrolled
                        // content slide beneath the pills (top) and the composer (bottom).
                        contentPadding = PaddingValues(
                            start = 18.dp, end = 18.dp,
                            top = statusInset + topChromeHeight,
                            bottom = with(chromeDensity) { bottomOverlayHeight.toDp() } + 18.dp,
                        ),
                    ) {
                        // Index keys are safe because `lines` only ever appends within one
                        // (session, timelineEpoch): reduce() never edits mid-list, and the one
                        // path that REWINDS lines (redo) bumps timelineEpoch - baked into the key
                        // so truncated-then-regrown slots get fresh identities, never recycled
                        // composition state. contentType aids recycling per line variant.
                        items(
                            count = state.lines.size,
                            // Session id in the key too: a same-epoch session switch must not
                            // reuse slot state (fold toggles, entrance flags) across conversations.
                            key = { "${state.currentSessionId}:${state.timelineEpoch}:$it" },
                            contentType = { state.lines[it]::class },
                        ) { i ->
                            val line = state.lines[i]
                            // A Reasoning line directly before an Assistant line renders folded into that
                            // turn; skip it here entirely (no stray padded gap).
                            if (line is ChatLine.Reasoning && state.lines.getOrNull(i + 1) is ChatLine.Assistant) {
                                SideEffect { appendTransitions.discard(i) }
                                return@items
                            }
                            val entryMotion = appendTransitions.motionFor(i)
                            // Tool chips sit tighter than prose turns - they read as one timeline.
                            val rhythm = if (line is ChatLine.ToolActivity) 3.dp else 8.dp
                            Box(
                                Modifier.messageEnter(entryMotion) { appendTransitions.markEntered(i) }
                                    .padding(vertical = rhythm),
                            ) {
                                ChatTurn(
                                    line = line,
                                    reasoning = reasoningBefore(state.lines, i),
                                    isLatestAssistant = i == lastAssistantIndex,
                                    isRunning = state.isRunning,
                                    turnOutcome = state.turnOutcome,
                                    completedAt = state.lastCompletedAt,
                                    onRedo = vm::redo,
                                    onReport = { reportOpen = true },
                                )
                            }
                        }
                        if (state.streamingReasoning.isNotEmpty() || state.streaming.isNotEmpty()) {
                            item {
                                Box(Modifier.padding(vertical = 8.dp)) {
                                    AssistantTurn(
                                        text = state.streaming,
                                        reasoning = state.streamingReasoning.ifEmpty { null },
                                        streaming = true,
                                        showActions = false, showReport = false, completedAt = null,
                                        onCopy = {}, onRedo = {}, onReport = {}, copyText = "",
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }

        }

        Box(Modifier.align(Alignment.TopStart).padding(top = statusInset + 6.dp, start = 12.dp)) {
            // Opening the drawer clears any open overlay so Back/scrim semantics stay unambiguous.
            MisulIconButton(
                Icons.Filled.Menu,
                "Menu",
                onClick = {
                    modelOpen = false
                    onOpenDrawer()
                },
            )
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = statusInset + 6.dp)) {
            Column(
                Modifier.widthIn(max = 230.dp).height(topChromeHeight)
                    .clickable(role = Role.Button) {
                        if (modelConfigured) modelOpen = true else onOpenModelSetup()
                    }
                    .semantics {
                        contentDescription = "${chatTitle(state)}, ${if (modelConfigured) modelShortLabel(state) else "set up model"}"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    chatTitle(state),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.padding(start = 11.dp, end = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        if (modelConfigured) modelShortLabel(state) else "Set up model",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondary,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        if (modelConfigured) "Switch model" else "Set up model",
                        tint = colors.secondary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = statusInset + 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Context usage is a glanceable ring now (out of the tools menu); tap for the breakdown.
            val ctxUsed = state.usageInput + state.usageOutput
            val ctxFrac = state.contextLimit?.let { if (it > 0) ctxUsed.toFloat() / it else 0f } ?: 0f
            Box(
                Modifier.size(Spacing.touchTarget).clip(ShapePill)
                    .clickable(role = Role.Button) { modelOpen = false; contextOpen = true }
                    .semantics { contentDescription = "Context usage ${(ctxFrac.coerceIn(0f, 1f) * 100).toInt()} percent" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(Spacing.controlVisual).clip(ShapePill)
                        .background(colors.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    ContextRing(
                        fraction = ctxFrac,
                        modifier = Modifier.size(21.dp),
                        stroke = 2.5f,
                        color = contextUsageColor(ctxFrac),
                    )
                }
                ContextUsageMenu(
                    expanded = contextOpen,
                    onDismiss = { contextOpen = false },
                    inputTokens = state.usageInput,
                    outputTokens = state.usageOutput,
                    contextLimit = state.contextLimit,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomOverlayHeight = it.height }
                // Union of ime+navbar: above the keyboard when typing, above the navbar otherwise.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            ChatStatus(
                error = state.error,
                turnOutcome = state.turnOutcome,
                queued = state.queued,
                interruptedTurn = state.interruptedTurn,
                retry = state.retry,
                notice = state.notice,
                todos = state.todos,
                isRunning = state.isRunning,
                sessionLoading = state.sessionLoading,
                onRetry = vm::redo,
                onDismissError = vm::clearError,
                onClearNotice = vm::clearNotice,
                onRestoreQueued = {
                    input = listOf(input.trim(), state.queued.joinToString("\n\n"))
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    vm.clearQueuedMessages()
                },
                onClearQueued = vm::clearQueuedMessages,
            )
            val submitMessage = {
                if (vm.send(input, photos)) {
                    if (!notificationRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(attachContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationRequested = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    rootView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    input = ""
                    vm.setDraftPhotos(composerKey, emptyList())
                }
            }
            ChatComposer(
                value = input,
                onValueChange = { input = it },
                photos = photos,
                onRemovePhoto = { index ->
                    vm.setDraftPhotos(composerKey, photos.filterIndexed { current, _ -> current != index })
                },
                onAttach = { picker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml")) },
                onSend = submitMessage,
                onStop = vm::cancel,
                onQueue = submitMessage.takeIf { canQueueComposerDraft(input, photos) },
                enabled = modelConfigured,
                loading = state.sessionLoading,
                running = state.isRunning,
                sendOnEnter = sendOnEnter,
            )
        }

        // The file picker is registered at SCREEN level: registering it inside the sheet's
        // conditional composition dropped results whenever the sheet/activity got recreated while
        // picking (device feedback: "attaching images/files doesn't work").
        ChatOverlays(
            modelOpen = modelOpen,
            onDismissModel = {
                modelOpen = false
                pendingProviderSetup?.let(onOpenProviderSetup)
                pendingProviderSetup = null
            },
            models = state.models,
            selectedModel = state.selected,
            selectedEffort = state.effort,
            disabledProviders = state.disabledProviders,
            hiddenModels = state.hiddenModels,
            favourites = state.favourites,
            codexConnected = state.codexConnected,
            providerConfigured = vm::providerConfigured,
            reasoningEfforts = vm::reasoningEfforts,
            providerNames = { vm.allProviders().associate { it.id to it.displayName } },
            onSetEffort = vm::setEffort,
            onSelectModel = vm::selectModel,
            onToggleFavourite = vm::toggleFavourite,
            onConfigureProvider = { pendingProviderSetup = it },
            pendingPermission = state.pendingPermission,
            onResolvePermission = vm::resolvePermission,
            pendingQuestion = state.pendingQuestion,
            onResolveQuestion = vm::resolveQuestion,
            reportOpen = reportOpen,
            reportSubmitting = state.reportSubmitting,
            reportSubmission = state.reportSubmission,
            onClearReportSubmission = vm::clearAiReportSubmission,
            onSubmitReport = vm::submitAiReport,
            onDismissReport = {
                vm.clearAiReportSubmission()
                reportOpen = false
            },
        )
    }
}

/** A single, append-only turn entrance. Streaming text changes stay inside the settled turn. */
@Composable
private fun Modifier.messageEnter(
    motion: ChatEntryMotion,
    onEntered: () -> Unit,
): Modifier {
    if (motion == ChatEntryMotion.NONE) return this
    // START is consumed once by the item's composition. A recycled row re-entering at RETAINED
    // creates this remember as false, so routine scrolling never replays motion.
    val shouldEnter = remember { motion == ChatEntryMotion.START }
    if (!shouldEnter) return this
    val initialOffset = 8.dp
    val offsetY = remember { androidx.compose.animation.core.Animatable(initialOffset.value) }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        // Effects only start after the composition is applied. A speculative or abandoned
        // LazyColumn composition therefore cannot consume this row's one entrance.
        onEntered()
        kotlinx.coroutines.coroutineScope {
            launch { offsetY.animateTo(0f, tween(180, easing = PhoneEasings.easeOut)) }
            launch { alpha.animateTo(1f, tween(180, easing = PhoneEasings.easeOut)) }
        }
    }
    return this.offset(y = offsetY.value.dp).graphicsLayer { this.alpha = alpha.value }
}

/**
 * Timeline transitions are classified when state changes, before LazyColumn decides which rows
 * are visible. A timeline identity change establishes a restored, non-animated baseline.
 */
internal enum class ChatEntryMotion { NONE, START, RETAINED }

internal class ChatAppendTransitionTracker {
    private data class TimelineIdentity(val sessionId: String, val epoch: Int)

    private var identity: TimelineIdentity? = null
    private var previousLines: List<ChatLine> = emptyList()
    private val pending = mutableMapOf<Int, Boolean>()
    private val entered = mutableSetOf<Int>()

    fun observe(
        sessionId: String,
        timelineEpoch: Int,
        lines: List<ChatLine>,
        followOutput: Boolean,
    ) {
        val nextIdentity = TimelineIdentity(sessionId, timelineEpoch)
        if (identity != nextIdentity) {
            identity = nextIdentity
            previousLines = lines
            pending.clear()
            entered.clear()
            return
        }

        if (lines.size > previousLines.size && previousLines.isTimelinePrefixOf(lines)) {
            for (index in previousLines.size until lines.size) {
                // A fresh empty session has no previous viewport to wait for; its first message
                // is the one intentional exception to the visibility requirement.
                pending[index] = previousLines.isEmpty() && index == 0
            }
        } else if (lines.size < previousLines.size ||
            (lines.size > previousLines.size && !previousLines.isTimelinePrefixOf(lines))) {
            // A same-epoch replacement is still never an append. ViewModel replacements bump the
            // epoch; this is the conservative fallback for malformed or legacy callers.
            pending.clear()
            entered.clear()
        }

        previousLines = lines
        if (!followOutput) pending.entries.removeAll { !it.value }
    }

    fun motionFor(index: Int): ChatEntryMotion {
        if (index in entered) return ChatEntryMotion.RETAINED
        pending[index] ?: return ChatEntryMotion.NONE
        return ChatEntryMotion.START
    }

    fun markEntered(index: Int) {
        if (pending.remove(index) != null) entered += index
    }

    fun discard(index: Int) {
        pending.remove(index)
        entered.remove(index)
    }

    private fun List<ChatLine>.isTimelinePrefixOf(other: List<ChatLine>): Boolean =
        indices.all { this[it].sameTimelineIdentity(other[it]) }

    private fun ChatLine.sameTimelineIdentity(other: ChatLine): Boolean = when {
        this is ChatLine.ToolActivity && other is ChatLine.ToolActivity -> id == other.id
        else -> this == other
    }
}

private fun chatTitle(state: ChatUiState): String =
    state.sessions.firstOrNull { it.id == state.currentSessionId }?.title
        ?: state.lines.filterIsInstance<ChatLine.User>().firstOrNull()?.text?.take(40)
        ?: "New chat"

/** Compact model name for the composer pill (drops any "Provider ·" prefix). */
private fun modelShortLabel(state: ChatUiState): String =
    state.selected?.label?.substringAfterLast('·')?.trim()?.take(24) ?: "Model"

/** The Reasoning line immediately preceding lines[i], folded into the assistant turn it belongs to. */
private fun reasoningBefore(lines: List<ChatLine>, i: Int): String? =
    (lines.getOrNull(i - 1) as? ChatLine.Reasoning)?.text

@Composable
private fun EmptyState(
    modelConfigured: Boolean,
    onSuggestion: (String) -> Unit,
    onOpenModelSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalMisulAccent.current
    // Misul identity stays at the edge of the work: one cobalt mark, then quiet text-first actions.
    Column(modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painter = painterResource(R.drawable.ic_misul_mark), contentDescription = null, tint = accent, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        if (!modelConfigured) {
            Text(
                "Connect a model to start",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose ChatGPT or add an API key. You can change providers at any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Spacer(Modifier.height(20.dp))
            MisulActionButton(
                label = "Set up a model",
                modifier = Modifier.widthIn(max = 260.dp),
                role = ActionRole.PRIMARY,
                onClick = onOpenModelSetup,
            )
        } else {
            Text("What should we build?", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
            Spacer(Modifier.height(16.dp))
            val suggestions = listOf(
                "Inspect this project",
                "Explain a build failure",
                "Plan a safe code change",
            )
            Column(Modifier.widthIn(max = 320.dp)) {
                suggestions.forEachIndexed { index, suggestion ->
                    val interaction = remember(suggestion) { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth()
                            .pressFeedback(interaction, pressedScale = 0.98f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(interactionSource = interaction, indication = ripple()) {
                                onSuggestion(suggestion)
                            }
                            .heightIn(min = Spacing.touchTarget)
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            suggestion,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = colors.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (index < suggestions.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
                    }
                }
            }
        }
    }
}

/** Messages sent while the agent is working, or recoverable drafts if the turn ended first. */
/** The attach result: readable text, or a binary we refuse honestly. */
private sealed interface Attachment {
    data class Text(val name: String, val content: String) : Attachment
    data object Binary : Attachment
}

private fun readPhoto(context: android.content.Context, uri: Uri): MessagePart.Image? = runCatching {
    val decoded = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, 1600f / maxOf(width, height))
            if (scale < 1f) decoder.setTargetSize((width * scale).toInt(), (height * scale).toInt())
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } ?: return@runCatching null
    val maxSide = maxOf(decoded.width, decoded.height)
    val bitmap = if (maxSide > 1600) {
        val scale = 1600f / maxSide
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    } else decoded
    val alpha = bitmap.hasAlpha()
    val output = ByteArrayOutputStream()
    bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, output)
    MessagePart.Image(if (alpha) "image/png" else "image/jpeg", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
}.getOrNull()

/** Bounded 64KB read with UTF-8-safe trim; binary content (NUL bytes) is detected, not mangled. */
private fun readAttachment(context: android.content.Context, uri: Uri): Attachment? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        // Bounded read: never pull more than the cap into memory, whatever the file size.
        val buf = ByteArray(64_000)
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        // Binary sniff: NUL bytes in the head mean an image/zip/etc. - refusing beats inserting mush.
        for (i in 0 until minOf(read, 8_000)) if (buf[i] == 0.toByte()) return@use Attachment.Binary
        val truncated = read == buf.size && stream.read() >= 0
        // Trim ONLY an incomplete trailing UTF-8 sequence (a complete one stays):
        // walk back over at most 3 continuation bytes to the lead, compare the bytes
        // present against the length its lead byte demands.
        if (read > 0) {
            var lead = read - 1
            while (lead > 0 && lead > read - 4 && (buf[lead].toInt() and 0xC0) == 0x80) lead--
            val b = buf[lead].toInt() and 0xFF
            val needed = when { b >= 0xF0 -> 4; b >= 0xE0 -> 3; b >= 0xC0 -> 2; else -> 1 }
            if (b >= 0xC0 && read - lead < needed) read = lead
        }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val content = String(buf, 0, read, Charsets.UTF_8) + if (truncated) "\n... (truncated at 64 KB)" else ""
        Attachment.Text(name, content)
    }
}.getOrNull()
