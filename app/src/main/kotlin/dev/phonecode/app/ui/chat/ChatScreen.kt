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
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.phonecode.agent.AgentMode
import dev.phonecode.app.R
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.AiReportSubmission
import dev.phonecode.app.agent.ModelOption
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.QuestionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.ui.components.ContextRing
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcDivider
import dev.phonecode.app.ui.components.PcIconButton
import dev.phonecode.app.ui.components.MorphingMenu
import dev.phonecode.app.ui.components.PcRoundButton
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.components.predictiveBackTransform
import dev.phonecode.app.ui.components.rememberContentOverscroll
import dev.phonecode.app.ui.components.rememberPredictiveBackMotion
import dev.phonecode.app.ui.components.shortContentVerticalOverscroll
import dev.phonecode.app.ui.components.pressFeedback
import androidx.compose.material3.ripple
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeSource
import dev.phonecode.app.ui.theme.Ethereal
import dev.phonecode.app.ui.theme.LocalNeuralPhase
import dev.phonecode.app.ui.theme.blurFade
import dev.phonecode.app.ui.theme.phoneHaze
import dev.phonecode.app.ui.theme.phoneHazeBand
import dev.phonecode.app.ui.theme.phoneHazeEffect
import dev.phonecode.app.ui.theme.PcMono
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.PhoneSprings
import dev.phonecode.app.ui.theme.PhoneTweens
import dev.phonecode.app.ui.theme.ShapeComposer
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.app.ui.theme.neuralRing
import dev.phonecode.app.ui.theme.neuralSweepBrush
import dev.phonecode.app.ui.theme.rememberNeuralBreath
import dev.phonecode.app.ui.theme.rememberNeuralPhase
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.todo.TodoItem
import dev.phonecode.tools.todo.TodoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale

private fun formatCompletionDate(value: Long) = SimpleDateFormat("HH:mm · d MMM", Locale.getDefault()).format(Date(value))

@Composable
private fun contextUsageColor(fraction: Float): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when {
        fraction < 0.6f -> if (dark) Color(0xFF30D158) else Color(0xFF248A3D)
        fraction < 0.8f -> if (dark) Color(0xFFFFD60A) else Color(0xFFA66F00)
        fraction < 0.9f -> if (dark) Color(0xFFFF9F0A) else Color(0xFFC2410C)
        else -> MaterialTheme.colorScheme.error
    }
}

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
    var reportOpen by remember { mutableStateOf(false) }
    var bottomOverlayHeight by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val listCanScroll = listState.canScrollBackward || listState.canScrollForward
    var followOutput by remember(state.currentSessionId) { mutableStateOf(true) }
    val listOverscroll = rememberContentOverscroll()
    val scope = rememberCoroutineScope()
    val empty = state.lines.isEmpty() && state.streaming.isEmpty() && state.streamingReasoning.isEmpty()
    val blurChrome = !empty && listCanScroll
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

    LaunchedEffect(state.currentSessionId, state.lines.size, state.streaming.length, state.streamingReasoning.length, followOutput) {
        val extra = if (state.streamingReasoning.isNotEmpty() || state.streaming.isNotEmpty()) 1 else 0
        val count = state.lines.size + extra
        if (count > 0 && followOutput) listState.scrollToItem(count - 1)
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

    val sharedNeuralPhase = if (state.isRunning) rememberNeuralPhase(3600) else null
    CompositionLocalProvider(LocalNeuralPhase provides sharedNeuralPhase) {
    // NOTE: no imePadding anywhere in this screen - the root container applies safeDrawing
    // (bars + IME) exactly once; adding it again here is what flung the composer off-screen.
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // Ethereal ambient mist: while the model runs, a slow breathing wash of light at the top
        // of the screen - monochrome (white mist on black, soft shadow on white).
        if (state.isRunning) {
            val breath = rememberNeuralBreath(3000)
            Box(
                Modifier.fillMaxWidth().height(190.dp)
                    .graphicsLayer { alpha = 0.4f + 0.5f * breath.value }
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(colors.onBackground.copy(alpha = 0.09f), androidx.compose.ui.graphics.Color.Transparent),
                        ),
                    ),
            )
        }
        // v2 chrome: NOTHING pads the top or bottom - the conversation fills the whole screen and
        // FEEDS the blur; every piece of chrome floats above it as an individually blurred pill
        // (signed prototype: design/v2.html).
        val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // The centered title is two rows (chat title + model selector), taller than the side
        // buttons. Resting content must clear both rows; only user-driven scrolling goes beneath.
        val topChromeHeight = Spacing.navBarHeight + 34.dp
        val hazeState = remember { HazeState() }
        val hazeStyle = phoneHaze()
        val bandStyle = phoneHazeBand()
        val chromeDensity = LocalDensity.current
        Box(Modifier.fillMaxSize().then(if (blurChrome) Modifier.hazeSource(hazeState) else Modifier)) {
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
                    // iMessage-style insert (apple-motion-exact.md §1): only lines appended AFTER this
                    // composition first saw the session animate in; restored history renders statically.
                    // timelineEpoch: redo() truncates `lines`, so these index caches reset with it.
                    val initialCount = remember(state.currentSessionId, state.timelineEpoch) { state.lines.size }
                    val animatedIndices = remember(state.currentSessionId, state.timelineEpoch) { mutableSetOf<Int>() }
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
                            val shouldAnimate = remember(i) { i >= initialCount && animatedIndices.add(i) }
                            // A Reasoning line directly before an Assistant line renders folded into that
                            // turn; skip it here entirely (no stray padded gap).
                            if (line is ChatLine.Reasoning && state.lines.getOrNull(i + 1) is ChatLine.Assistant) return@items
                            // Tool chips sit tighter than prose turns - they read as one timeline.
                            val rhythm = if (line is ChatLine.ToolActivity) 3.dp else 8.dp
                            Box(Modifier.messageEnter(shouldAnimate).padding(vertical = rhythm)) {
                                when (line) {
                                    is ChatLine.User -> UserBubble(line.text, line.images)
                                    is ChatLine.Assistant -> AssistantTurn(
                                        text = line.text,
                                        reasoning = reasoningBefore(state.lines, i),
                                        streaming = false,
                                        showActions = i == lastAssistantIndex && !state.isRunning,
                                        showReport = !state.isRunning,
                                        completedAt = state.lastCompletedAt,
                                        onCopy = { },
                                        onRedo = vm::redo,
                                        onReport = { reportOpen = true },
                                        copyText = line.text,
                                    )
                                    // Thinking that wasn't followed by assistant text (e.g. think → tool call):
                                    // render it standalone so the trace is never lost.
                                    is ChatLine.Reasoning -> AssistantTurn(
                                        text = "",
                                        reasoning = line.text,
                                        streaming = false,
                                        showActions = false, showReport = !state.isRunning, completedAt = null,
                                        onCopy = {}, onRedo = {}, onReport = { reportOpen = true }, copyText = "",
                                    )
                                    is ChatLine.ToolActivity -> ToolActivityView(line)
                                }
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

        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusInset + topChromeHeight)
                .shadow(if (!empty && listState.canScrollBackward) 2.dp else 0.dp, RectangleShape, clip = false)
                .then(if (blurChrome) Modifier.phoneHazeEffect(hazeState, hazeStyle) else Modifier)
                .background(if (blurChrome) colors.background.copy(alpha = 0.16f) else colors.background),
        )
        Box(Modifier.align(Alignment.TopStart).padding(top = statusInset + 6.dp, start = 12.dp).clip(ShapePill).background(colors.surfaceContainerHigh)) {
            // Opening the drawer clears any open overlay so Back/scrim semantics stay unambiguous.
            PcIconButton(Icons.Filled.Menu, "Menu") { modelOpen = false; onOpenDrawer() }
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = statusInset + 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.widthIn(max = 230.dp).clip(ShapePill).background(colors.surfaceContainerHigh.copy(alpha = 0.72f))) {
                Text(
                    chatTitle(state),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            // Model selector moved out of the composer into the title: tap to switch.
            Row(
                Modifier.padding(top = 3.dp).heightIn(min = 48.dp)
                    .clip(ShapePill).background(colors.surfaceContainerHigh.copy(alpha = 0.72f))
                    .clickable(role = Role.Button) {
                        if (modelConfigured) modelOpen = true else onOpenModelSetup()
                    }
                    .padding(start = 11.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
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
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = statusInset + 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Context usage is a glanceable ring now (out of the tools menu); tap for the breakdown.
            val ctxUsed = state.usageInput + state.usageOutput
            val ctxFrac = state.contextLimit?.let { if (it > 0) ctxUsed.toFloat() / it else 0f } ?: 0f
            Box(
                Modifier.size(48.dp).clip(ShapePill).background(colors.surfaceContainerHigh)
                    .clickable(role = Role.Button) { modelOpen = false; contextOpen = true }
                    .semantics { contentDescription = "Context usage ${(ctxFrac.coerceIn(0f, 1f) * 100).toInt()} percent" },
                contentAlignment = Alignment.Center,
            ) {
                ContextRing(fraction = ctxFrac, modifier = Modifier.size(22.dp), stroke = 2.5f, color = contextUsageColor(ctxFrac))
                MorphingMenu(
                    expanded = contextOpen,
                    onDismiss = { contextOpen = false },
                    above = false,
                    alignEnd = true,
                    anchorSize = 48.dp,
                    modifier = Modifier.width(280.dp),
                ) {
                    ContextPopover(state)
                }
            }
        }

        // Bottom dissolve band behind the floating composer AND the nav bar: text stays visible
        // through both, frosting as it goes under (signed prototype; navbar must not be solid).
        // Same gating as the top: only while content can still scroll under the composer.
        androidx.compose.animation.AnimatedVisibility(
            visible = !empty && listState.canScrollForward,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .height(with(chromeDensity) { bottomOverlayHeight.toDp() } + 24.dp)
                    .blurFade(hazeState, bandStyle, fromTop = false, edgeColor = colors.background),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomOverlayHeight = it.height }
                // Union of ime+navbar: above the keyboard when typing, above the navbar otherwise.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            AnimatedContent(
                targetState = state.error,
                transitionSpec = {
                    (slideInVertically(tween(160, easing = PhoneEasings.easeOut)) { it / 2 } +
                        fadeIn(tween(140, easing = PhoneEasings.easeOut))) togetherWith
                        (slideOutVertically(tween(120, easing = PhoneEasings.easeOut)) { it / 3 } +
                            fadeOut(tween(100, easing = PhoneEasings.easeOut)))
                },
                label = "errorBanner",
            ) { error ->
                if (error != null) ErrorBanner(
                    text = error,
                    actionLabel = if (state.interruptedTurn) "Retry" else null,
                    onAction = vm::redo,
                    onDismiss = vm::clearError,
                )
            }
            AnimatedContent(
                targetState = state.retry,
                transitionSpec = {
                    (slideInVertically(tween(160, easing = PhoneEasings.easeOut)) { it / 2 } +
                        fadeIn(tween(140, easing = PhoneEasings.easeOut))) togetherWith
                        (slideOutVertically(tween(120, easing = PhoneEasings.easeOut)) { it / 3 } +
                            fadeOut(tween(100, easing = PhoneEasings.easeOut)))
                },
                label = "retryBanner",
            ) { retry ->
                if (retry != null) NoticeBanner("Retrying connection · attempt ${retry.attempt} · ${retry.message}")
            }
            AnimatedContent(
                targetState = state.notice,
                transitionSpec = {
                    (slideInVertically(tween(160, easing = PhoneEasings.easeOut)) { it / 2 } +
                        fadeIn(tween(140, easing = PhoneEasings.easeOut))) togetherWith
                        (slideOutVertically(tween(120, easing = PhoneEasings.easeOut)) { it / 3 } +
                            fadeOut(tween(100, easing = PhoneEasings.easeOut)))
                },
                label = "noticeBanner",
            ) { notice ->
                if (notice != null) {
                    NoticeBanner(notice)
                    LaunchedEffect(notice) { kotlinx.coroutines.delay(3500); vm.clearNotice() }
                }
            }
            if (state.todos.isNotEmpty()) TodoPanel(state.todos)
            if (state.queued.isNotEmpty()) QueuedMessages(state.queued)
            if (state.sessionLoading) NoticeBanner("Opening chat…")
            Composer(
                state = state,
                enabled = modelConfigured,
                input = input,
                photos = photos,
                onInput = { input = it },
                onRemovePhoto = { vm.setDraftPhotos(composerKey, emptyList()) },
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                onUpload = { picker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml")) },
                onSend = {
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
                },
                onStop = vm::cancel,
                sendOnEnter = sendOnEnter,
            )
        }

        // The file picker is registered at SCREEN level: registering it inside the sheet's
        // conditional composition dropped results whenever the sheet/activity got recreated while
        // picking (device feedback: "attaching images/files doesn't work").
        if (modelOpen) PcSheet(
            onDismiss = {
                modelOpen = false
                pendingProviderSetup?.let(onOpenProviderSetup)
                pendingProviderSetup = null
            },
        ) { close ->
            ModelSheet(
                state = state,
                vm = vm,
                onConfigureProvider = {
                    pendingProviderSetup = it
                    close()
                },
                onDone = close,
            )
        }

        state.pendingPermission?.let { r ->
            PermissionDialog(r, onApprove = { vm.resolvePermission(true) }, onDeny = { vm.resolvePermission(false) })
        }
        state.pendingQuestion?.let { r ->
            QuestionDialog(r, onSubmit = { vm.resolveQuestion(it) }, onDismiss = { vm.resolveQuestion(emptyList()) })
        }
        if (reportOpen) {
            AiReportFlow(onDismiss = { reportOpen = false }, onSubmit = vm::submitAiReport)
        }
    }
    }
}

/**
 * Native Material modal bottom sheet host - the standard Android picker (the one Claude's app uses
 * for model switching). The platform owns the slide-up, scrim and drag-to-dismiss motion. [content]
 * receives a `close` lambda that hides the sheet WITH that animation before [onDismiss] flips the
 * caller's trigger flag, so a pick-and-close action slides away instead of vanishing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) { content(close) }
    }
}

@Composable
private fun Modifier.messageEnter(animate: Boolean): Modifier {
    if (!animate) return this
    val offsetY = remember { androidx.compose.animation.core.Animatable(12f) }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                offsetY.animateTo(0f, spring(dampingRatio = 1f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow, visibilityThreshold = 0.5f))
            }
            launch {
                alpha.animateTo(1f, PhoneTweens.popEnter)
            }
        }
    }
    return graphicsLayer {
        translationY = offsetY.value
        this.alpha = alpha.value
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
    // Grok-style home: crisp mark + wordmark + starter chips. The chat stays quiet at rest -
    // no halos, no gradients; the ethereal layer belongs to generation only (grok-design.md).
    Column(modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painter = painterResource(R.drawable.ic_phonecode_mark), contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(48.dp))
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
            PcButton(
                text = "Set up a model",
                modifier = Modifier.widthIn(max = 260.dp),
                onClick = onOpenModelSetup,
            )
        } else {
            Text("What should we build?", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
            Spacer(Modifier.height(20.dp))
            listOf(
                "Build a small web app",
                "Explain an error message",
                "Refactor a function",
                "Set up a git project",
            ).forEach { suggestion ->
                val chipInteraction = remember(suggestion) { MutableInteractionSource() }
                Text(
                    suggestion,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                    color = colors.secondary,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .pressFeedback(chipInteraction, pressedScale = 0.96f)
                        .clip(ShapePill)
                        .clickable(interactionSource = chipInteraction, indication = ripple()) { onSuggestion(suggestion) }
                        .background(colors.surfaceContainerHigh)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Messages sent while the agent is working, shown faded above the composer until it picks each one up. */
@Composable
private fun QueuedMessages(queued: List<String>) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (text in queued) {
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, images: List<MessagePart.Image>) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1800)
            copied = false
        }
    }
    fun copyMessage() {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    // Uniform large radius (Grok rounded-4xl) - short messages read as full pills.
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { PhotoThumbnail(it, Modifier.fillMaxWidth().height(180.dp)) }
                    if (text.isNotEmpty()) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onBackground,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (text.isNotEmpty()) {
                Box(
                    Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = if (copied) "Copied" else "Ready to copy"
                    },
                ) {
                    ActionIcon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        if (copied) "Copied" else "Copy message",
                        ::copyMessage,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantTurn(
    text: String,
    reasoning: String?,
    streaming: Boolean,
    showActions: Boolean,
    showReport: Boolean,
    completedAt: Long?,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
    onReport: () -> Unit,
    copyText: String,
) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }

    // No stream rail beside live replies - the shimmering "Thinking" label carries the signal
    // alone (device feedback: "remove the line next to thinking").
    Column(Modifier.fillMaxWidth()) {
        if (reasoning != null) {
            // "Thinking" row: dot + label that wipes right-to-left when opened.
            Row(
                Modifier.clip(MaterialTheme.shapes.extraSmall).heightIn(min = Spacing.touchTarget)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .clickable { open = !open }.padding(vertical = 3.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                ThinkingDot(active = streaming, open = open)
                AnimatedVisibility(
                    visible = !open,
                    enter = expandHorizontally(expandFrom = Alignment.Start, animationSpec = PhoneSprings.standardSpec()) + fadeIn(PhoneTweens.popEnter),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start, animationSpec = PhoneSprings.standardSpec()) + fadeOut(PhoneTweens.popExit),
                ) {
                    if (streaming && text.isEmpty()) {
                        // Actively thinking (no answer text yet): the shimmer sweep.
                        val phase by rememberNeuralPhase(3000)
                        Text(
                            "Thinking",
                            style = MaterialTheme.typography.labelMedium.copy(
                                brush = neuralSweepBrush(phase, ink = colors.onBackground, extent = 220f),
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    } else {
                        // Reasoning finished (answer streaming or turn complete) - say so.
                        Text("Done", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                    }
                }
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(animationSpec = PhoneSprings.emphasizedSpec()) + fadeIn(PhoneTweens.popEnter),
                exit = shrinkVertically(animationSpec = PhoneSprings.emphasizedSpec()) + fadeOut(PhoneTweens.popExit),
            ) {
                Row(Modifier.padding(start = 3.dp, top = 6.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(1.5.dp).fillMaxHeight().background(colors.outlineVariant))
                    Text(
                        reasoning,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.tertiary,
                        modifier = Modifier.padding(start = 13.dp),
                    )
                }
            }
        }

        if (text.isNotEmpty() || streaming) {
            // ponytail: splitFenced re-scans the whole reply each token -> O(n^2) over a long stream.
            // Bounded by reply length and dwarfed by markdown recomposition; settled segments are memoized
            // downstream by String equality so they don't re-render. Upgrade path if profiling flags it:
            // parse only the tail past the last settled fence boundary.
            val segments = remember(text) { splitFenced(text) }
            Column(Modifier.fillMaxWidth().padding(top = if (reasoning != null) 11.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                segments.forEachIndexed { i, seg ->
                    val live = streaming && i == segments.lastIndex
                    when {
                        // Render a settled mermaid block as a diagram; while it is still streaming keep it as
                        // code (the source is incomplete and would render as an error).
                        seg.isCode && seg.lang.equals("mermaid", ignoreCase = true) && !live ->
                            MermaidDiagram(seg.text)
                        seg.isCode -> CodeBlock(seg.text, seg.lang)
                        else -> MarkdownBlocks(seg.text, caret = if (live) " ▋" else "", streaming = live)
                    }
                }
                if (segments.isEmpty() && streaming) Text("▋", style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
            }
        }

        AnimatedVisibility(visible = showActions || showReport, enter = fadeIn(PhoneTweens.popEnter), exit = fadeOut(PhoneTweens.popExit)) {
            Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (showActions) {
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
                    AnimatedContent(
                        targetState = copied,
                        transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(120)) },
                        label = "copyCheck",
                    ) { isCopied ->
                        ActionIcon(if (isCopied) Icons.Filled.Check else Icons.Filled.ContentCopy, "Copy") {
                            clipboard.setText(AnnotatedString(copyText)); copied = true; onCopy()
                        }
                    }
                    ActionIcon(Icons.Filled.Refresh, "Redo", onRedo)
                }
                if (showReport) ActionIcon(Icons.Outlined.Flag, "Report AI response", onReport)
                if (showActions && completedAt != null) {
                    Text(
                        formatCompletionDate(completedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.tertiary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private data class ReportCategory(val id: String, val title: String, val detail: String)

private val REPORT_CATEGORIES = listOf(
    ReportCategory("hate", "Hate", "Hateful or dehumanizing content"),
    ReportCategory("harassment", "Harassment", "Bullying, threats, or targeted abuse"),
    ReportCategory("sexual", "Sexual content", "Sexual or exploitative material"),
    ReportCategory("violence", "Violence", "Violent threats or harmful instructions"),
    ReportCategory("self_harm", "Self-harm", "Encouragement of self-harm"),
    ReportCategory("illegal", "Illegal or malicious", "Scams, malware, or unauthorized access"),
    ReportCategory("privacy", "Privacy", "Exposure of private or sensitive information"),
    ReportCategory("other", "Other", "Another harmful or inappropriate response"),
)

@Composable
private fun AiReportFlow(
    onDismiss: () -> Unit,
    onSubmit: suspend (String, String) -> AiReportSubmission,
) {
    var category by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val reportSuccessFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val backMotion = rememberPredictiveBackMotion(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize().predictiveBackTransform(backMotion),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (sent) {
                LaunchedEffect(Unit) { reportSuccessFocus.requestFocus() }
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Report sent",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.focusRequester(reportSuccessFocus).focusable().semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Thank you. Your report will be used to improve PhoneCode's safeguards.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    reference?.let {
                        Spacer(Modifier.height(10.dp))
                        Text("Reference: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            } else {
                ReportReview(
                    category = category,
                    note = note,
                    submitting = submitting,
                    error = error,
                    onCategory = { category = it; error = null },
                    onNote = { note = it.take(1000); error = null },
                    onDismiss = onDismiss,
                    onSubmit = {
                        val chosen = category ?: return@ReportReview
                        submitting = true
                        error = null
                        scope.launch {
                            val result = onSubmit(chosen, note)
                            submitting = false
                            if (result.accepted) {
                                reference = result.reference
                                sent = true
                            } else {
                                error = result.error
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReportReview(
    category: String?,
    note: String,
    submitting: Boolean,
    error: String?,
    onCategory: (String) -> Unit,
    onNote: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(Spacing.navBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PcIconButton(Icons.Filled.Close, "Cancel report", onClick = onDismiss)
            Text(
                "Report AI response",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onSubmit, enabled = category != null && !submitting) {
                Text(if (submitting) "Sending…" else "Send")
            }
        }
        Column(
            Modifier.fillMaxSize().contentVerticalScroll(rememberScrollState())
                .padding(top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Choose what went wrong. PhoneCode sends only this category, your optional note, and basic app information.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reason", style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                REPORT_CATEGORIES.forEach { option ->
                    ReportChoice(
                        title = option.title,
                        detail = option.detail,
                        selected = category == option.id,
                        onClick = { onCategory(option.id) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What happened? (optional)", style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                BasicTextField(
                    value = note,
                    onValueChange = onNote,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onBackground),
                    cursorBrush = SolidColor(colors.onBackground),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                        .semantics { contentDescription = "Optional report details" }
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.surfaceContainerLow).padding(14.dp),
                    decorationBox = { field ->
                        Box {
                            if (note.isEmpty()) Text("Describe the problem without pasting private information.", color = colors.tertiary)
                            field()
                        }
                    },
                )
                Text("${note.length}/1000", style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
            }
            Text(
                error ?: "The response, prompt, files, credentials, tool activity, chat history, and device identifiers are never attached.",
                style = MaterialTheme.typography.labelMedium,
                color = if (error != null) colors.error else colors.tertiary,
                modifier = if (error == null) Modifier else Modifier.semantics {
                    this.error(error)
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
    }
}

@Composable
private fun ReportChoice(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.surfaceContainerHigh else colors.surfaceContainerLow)
            .border(1.dp, if (selected) colors.onBackground else colors.outline, MaterialTheme.shapes.medium)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(ShapePill)
                .border(1.5.dp, if (selected) colors.onBackground else colors.secondary, ShapePill),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(ShapePill).background(colors.onBackground))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onBackground)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        }
    }
}

@Composable
private fun ThinkingDot(active: Boolean, open: Boolean) {
    val colors = MaterialTheme.colorScheme
    // Only run the infinite pulse while streaming - an idle dot costs zero animation frames.
    val pulse = if (active) rememberNeuralBreath(1400) else null
    val dotScale = animateFloatAsState(
        targetValue = if (open) 1.2f else 1f,
        animationSpec = PhoneSprings.quick,
        label = "thinkingDotScale",
    )
    val dotBackground = if (active) {
        // Live: a small point of light (bright ink fading to mid) instead of a flat grey.
        Modifier.background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(colors.onBackground, colors.onBackground.copy(alpha = 0.45f)),
            ),
        )
    } else {
        Modifier.background(if (open) colors.secondary else colors.tertiary)
    }
    Box(
        Modifier.size(8.dp).graphicsLayer {
            alpha = pulse?.let { 0.4f + it.value * 0.6f } ?: 1f
            scaleX = dotScale.value
            scaleY = dotScale.value
        }
            .clip(ShapePill).then(dotBackground),
    )
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    PcIconButton(icon, desc, tint = MaterialTheme.colorScheme.secondary, onClick = onClick)
}

private data class Seg(val text: String, val isCode: Boolean, val lang: String)

private fun splitFenced(input: String): List<Seg> {
    val out = mutableListOf<Seg>()
    val buf = StringBuilder()
    var inCode = false
    var lang = ""
    fun flush(code: Boolean) {
        val t = buf.toString().removeSuffix("\n")
        if (code || t.isNotBlank()) out += Seg(t, code, lang)
        buf.clear()
    }
    input.split("\n").forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (!inCode) { flush(false); inCode = true; lang = line.trimStart().removePrefix("```").trim() }
            else { flush(true); inCode = false; lang = "" }
        } else buf.append(line).append("\n")
    }
    flush(inCode)
    return out
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val colors = MaterialTheme.colorScheme
    val tones = remember(colors) { CodeTones.monochrome(colors.onBackground, colors.secondary, colors.tertiary) }
    val highlighted = remember(code, tones) { highlightCode(code, tones) }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surface)) {
        if (lang.isNotBlank()) {
            Text(lang.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.tertiary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            PcDivider()
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(highlighted, style = MaterialTheme.typography.labelMedium.copy(fontFamily = PcMono, fontSize = MaterialTheme.typography.labelMedium.fontSize), color = colors.onBackground)
        }
    }
}

/**
 * Renders a ```mermaid block as an actual diagram (trees, graphs, flowcharts, sequence, ...) in a WebView.
 * mermaid.min.js is bundled in assets and INLINED into the page, so there is no network and no file-access
 * setting - the page is fully self-contained. securityLevel 'strict' sanitizes the model-authored source.
 * The page reports its rendered height back so the view sizes to the diagram instead of a fixed box.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidDiagram(source: String) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val mermaidJs = remember { runCatching { context.assets.open("mermaid.min.js").bufferedReader().use { it.readText() } }.getOrDefault("") }
    if (mermaidJs.isBlank()) { CodeBlock(source, "mermaid"); return } // asset missing: degrade to source

    val dark = colors.background.luminance() < 0.5f
    var heightDp by remember(source) { mutableIntStateOf(0) }
    val html = remember(source, dark, mermaidJs) { mermaidHtml(source, dark, colors.onBackground, mermaidJs) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surface)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        domStorageEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                    }
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface fun reportHeight(px: Int) { mainHandler.post { heightDp = px.coerceIn(80, 2_000) } }
                        },
                        "AndroidBridge",
                    )
                    webViewClient = WebViewClient()
                    tag = html
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            // Reload only when the diagram source/theme actually changed (update runs on every recomposition).
            update = { wv -> if (wv.tag != html) { wv.tag = html; wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) } },
            modifier = Modifier.fillMaxWidth().padding(8.dp).height(if (heightDp > 0) heightDp.dp else 160.dp),
        )
    }
}

private fun Color.toCssHex(): String =
    "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

private fun mermaidHtml(source: String, dark: Boolean, fg: Color, js: String): String {
    val theme = if (dark) "dark" else "default"
    val esc = source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val fgCss = fg.toCssHex()
    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;background:transparent;}#c{width:100%;}.mermaid{display:flex;justify-content:center;}svg{max-width:100%;height:auto;}</style>
<script>$js</script>
</head><body>
<div id="c"><pre class="mermaid">$esc</pre></div>
<script>
function done(){try{AndroidBridge.reportHeight(Math.ceil(document.documentElement.scrollHeight)+6);}catch(e){}}
try{
 mermaid.initialize({startOnLoad:false,theme:'$theme',securityLevel:'strict',flowchart:{useMaxWidth:true}});
 mermaid.run({querySelector:'.mermaid'}).then(done).catch(function(e){
  var p=document.createElement('pre');p.style='color:$fgCss;white-space:pre-wrap;font:12px monospace;';p.textContent=e&&e.message?String(e.message):'diagram error';document.getElementById('c').replaceChildren(p);done();
 });
}catch(e){done();}
</script>
</body></html>"""
}

@Composable
private fun ToolActivityView(line: ChatLine.ToolActivity) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val error = line.status == ToolStatus.ERROR
    val running = line.status == ToolStatus.RUNNING
    var detailsOpen by remember(line.id) { mutableStateOf(false) }
    val iconPulse = if (running) rememberNeuralBreath(1800) else null
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pressFeedback(interaction, pressedScale = 0.99f).clip(MaterialTheme.shapes.medium)
            .background(if (error) colors.errorContainer else colors.surfaceContainerLow)
            .heightIn(min = Spacing.touchTarget)
            .clickable(interactionSource = interaction, indication = ripple()) { detailsOpen = true },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(30.dp).clip(MaterialTheme.shapes.small)
                    .background(if (error) colors.error.copy(alpha = 0.12f) else colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    toolIcon(line.name), null,
                    tint = if (error) colors.onErrorContainer else colors.secondary,
                    modifier = Modifier.size(16.dp).graphicsLayer {
                        alpha = iconPulse?.let { 0.45f + it.value * 0.55f } ?: 1f
                    },
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    toolAction(line.name, line.status),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (error) colors.onErrorContainer else colors.onSurface,
                )
                if (line.detail.isNotBlank()) {
                    Text(
                        line.detail.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (error) colors.onErrorContainer.copy(alpha = 0.7f) else colors.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                when (line.status) {
                    ToolStatus.RUNNING -> "Running"
                    ToolStatus.DONE -> "Done"
                    ToolStatus.ERROR -> "Failed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (error) colors.onErrorContainer else colors.tertiary,
            )
        }
    }
    if (detailsOpen) PcSheet(onDismiss = { detailsOpen = false }) { close ->
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(toolAction(line.name, line.status), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                Text(line.name, style = MaterialTheme.typography.labelSmall.copy(fontFamily = PcMono), color = colors.onSurfaceVariant)
            }
            TextButton(onClick = close, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Done") }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).contentVerticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Input", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            SelectionContainer {
                Text(line.input.ifBlank { "(none)" }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = PcMono), color = colors.onBackground)
            }
            Text("Output", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            SelectionContainer {
                Text(line.detail.ifBlank { if (running) "Waiting for output…" else "(no output)" }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = PcMono), color = colors.onBackground)
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString("Input:\n${line.input}\n\nOutput:\n${line.detail}")) },
                modifier = Modifier.heightIn(min = Spacing.touchTarget),
            ) {
                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy details")
            }
        }
    }
}

private fun toolAction(name: String, status: ToolStatus): String {
    val active = status == ToolStatus.RUNNING
    return when {
        name == "read" -> if (active) "Reading file" else "Read file"
        name == "write" -> if (active) "Writing file" else "Wrote file"
        name == "edit" || name == "apply_patch" -> if (active) "Editing code" else "Edited code"
        name == "ls" || name == "glob" -> if (active) "Browsing files" else "Browsed files"
        name == "grep" -> if (active) "Searching code" else "Searched code"
        name == "bash" -> if (active) "Running command" else "Ran command"
        name == "websearch" -> if (active) "Searching the web" else "Searched the web"
        name == "webfetch" -> if (active) "Opening webpage" else "Opened webpage"
        name.startsWith("git_") -> if (active) "Running Git" else "Git · ${name.removePrefix("git_").replace('_', ' ')}"
        name == "question" -> "Asked a question"
        name == "task" -> if (active) "Delegating task" else "Completed delegated task"
        name == "skill" -> if (active) "Loading skill" else "Loaded skill"
        name.startsWith("todo") -> if (active) "Updating tasks" else "Updated tasks"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Icon per tool family - keeps the chip scannable without reading names. */
private fun toolIcon(name: String) = when {
    name.startsWith("read") -> Icons.Outlined.Description
    name.startsWith("write") || name.startsWith("edit") || name.startsWith("apply") -> Icons.Outlined.Edit
    name.startsWith("glob") || name.startsWith("grep") || name == "ls" -> Icons.Outlined.Search
    name.startsWith("bash") || name.startsWith("shell") -> Icons.Outlined.Terminal
    name.startsWith("web") -> Icons.Outlined.Language
    name.startsWith("todo") -> Icons.Outlined.Checklist
    name.startsWith("question") -> Icons.AutoMirrored.Outlined.HelpOutline
    else -> Icons.Outlined.Build
}

@Composable
private fun NoticeBanner(text: String) {
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
private fun ErrorBanner(
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
        PcIconButton(Icons.Filled.Close, "Dismiss", tint = colors.onErrorContainer, onClick = onDismiss)
    }
}

@Composable
internal fun TodoPanel(todos: List<TodoItem>) {
    val colors = MaterialTheme.colorScheme
    // Compact + collapsible: a one-line summary by default (it floats over the transcript, so a full list
    // was occluding the latest messages). Tap to expand the full plan, capped and scrollable.
    var expanded by remember { mutableStateOf(false) }
    fun glyphOf(s: TodoStatus) = when (s) {
        TodoStatus.PENDING -> "○"; TodoStatus.IN_PROGRESS -> "◐"; TodoStatus.COMPLETED -> "●"; TodoStatus.CANCELLED -> "✕"
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
                Text(glyphOf(active.status), style = MaterialTheme.typography.labelMedium.copy(fontFamily = PcMono), color = colorOf(active.status))
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
                        Text(glyphOf(todo.status), style = MaterialTheme.typography.labelMedium.copy(fontFamily = PcMono), color = colorOf(todo.status))
                        Text(todo.content, style = MaterialTheme.typography.labelMedium, color = colorOf(todo.status))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Composer + morphing wrench menu
// ---------------------------------------------------------------------------------------------

@Composable
private fun Composer(
    state: ChatUiState,
    enabled: Boolean,
    input: String,
    photos: List<MessagePart.Image>,
    onInput: (String) -> Unit,
    onRemovePhoto: () -> Unit,
    hazeState: HazeState,
    hazeStyle: HazeStyle,
    onUpload: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendOnEnter: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp)) {
            // Neural floating capsule: full-radius pill; while the model runs, the hairline is
            // replaced by the animated gradient ring (energy = generation in progress).
            // v2 composer: a floating blurred capsule - the conversation stays visible through it
            // (signed prototype). The ethereal ring still takes over while the model runs.
            Column(
                Modifier.fillMaxWidth()
                    .neuralRing(active = state.isRunning, shape = ShapeComposer)
                    .clip(ShapeComposer)
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (photos.isNotEmpty()) {
                    Box(Modifier.padding(start = 44.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)) {
                        PhotoThumbnail(photos.first(), Modifier.size(72.dp).clip(MaterialTheme.shapes.medium))
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 18.dp, y = (-18).dp)
                                .size(48.dp).clip(ShapePill).clickable(onClick = onRemovePhoto),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(24.dp).clip(ShapePill).background(colors.inverseSurface), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, "Remove photo", tint = colors.inverseOnSurface, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcIconButton(
                        Icons.Filled.Add,
                        "Add attachment",
                        tint = if (!enabled || state.sessionLoading) colors.tertiary else colors.secondary,
                        enabled = enabled && !state.sessionLoading,
                        onClick = onUpload,
                    )
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) Text(
                            when {
                                state.sessionLoading -> "Opening chat…"
                                else -> "Message..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.secondary,
                        )
                        BasicTextField(
                            value = input,
                            onValueChange = onInput,
                            enabled = enabled && !state.sessionLoading,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
                            cursorBrush = SolidColor(colors.primary),
                            maxLines = 6,
                            keyboardOptions = if (sendOnEnter) {
                                KeyboardOptions(imeAction = ImeAction.Send)
                            } else {
                                KeyboardOptions.Default
                            },
                            keyboardActions = KeyboardActions(onSend = {
                                if (enabled && (input.isNotBlank() || photos.isNotEmpty())) onSend()
                            }),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Message" },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        AnimatedVisibility(
                            visible = enabled && !state.sessionLoading && state.isRunning,
                            enter = scaleIn(initialScale = 0.92f, animationSpec = PhoneSprings.quickSpec()) +
                                fadeIn(PhoneTweens.popEnter),
                            exit = scaleOut(targetScale = 0.92f, animationSpec = PhoneSprings.quickSpec()) +
                                fadeOut(PhoneTweens.popExit),
                            label = "composerStop",
                        ) {
                            PcRoundButton(Icons.Filled.Stop, "Stop", filled = true, onClick = onStop)
                        }
                        AnimatedVisibility(
                            visible = enabled && !state.sessionLoading &&
                                (input.isNotBlank() || photos.isNotEmpty()),
                            enter = scaleIn(initialScale = 0.92f, animationSpec = PhoneSprings.quickSpec()) +
                                fadeIn(PhoneTweens.popEnter),
                            exit = scaleOut(targetScale = 0.92f, animationSpec = PhoneSprings.quickSpec()) +
                                fadeOut(PhoneTweens.popExit),
                            label = "composerSend",
                        ) {
                            PcRoundButton(Icons.Filled.ArrowUpward, "Send", filled = true, onClick = onSend)
                        }
                    }
                }
            }
        }
    }
}

/** The attach result: readable text, or a binary we refuse honestly. */
private sealed interface Attachment {
    data class Text(val name: String, val content: String) : Attachment
    data object Binary : Attachment
}

@Composable
private fun PhotoThumbnail(image: MessagePart.Image, modifier: Modifier = Modifier) {
    val bitmap = remember(image.data) {
        runCatching {
            val bytes = Base64.decode(image.data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = modifier.clip(MaterialTheme.shapes.medium))
    }
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

@Composable
private fun ModelSheet(
    state: ChatUiState,
    vm: ChatViewModel,
    onConfigureProvider: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val configuredSelection = state.selected?.takeIf { vm.providerConfigured(it.providerId) }
    val reasoningEfforts = vm.reasoningEfforts(configuredSelection)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Model & reasoning", style = MaterialTheme.typography.titleSmall, color = colors.onBackground, modifier = Modifier.weight(1f))
            Text(
                "Done",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onBackground,
                modifier = Modifier.clip(ShapePill).clickable(onClick = onDone)
                    .heightIn(min = Spacing.touchTarget)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Agent mode", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    if (state.agentMode == AgentMode.PLAN) "Plan" else "Build",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onBackground,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentMode.entries.forEach { mode ->
                    val selected = state.agentMode == mode
                    Box(
                        Modifier.weight(1f).heightIn(min = Spacing.touchTarget).clip(ShapePill)
                            .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                            .semantics {
                                this.selected = selected
                                role = Role.RadioButton
                            }
                            .clickable { vm.setAgentMode(mode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) colors.onPrimary else colors.onBackground,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Reasoning", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    if (reasoningEfforts.isEmpty()) "Not available" else state.effort.display(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.tertiary,
                )
            }
            if (reasoningEfforts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    reasoningEfforts.forEach { effort ->
                        val selected = state.effort == effort
                        Box(
                            Modifier.heightIn(min = Spacing.touchTarget).clip(ShapePill)
                                .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                                .semantics {
                                    this.selected = selected
                                    role = Role.RadioButton
                                }
                                .clickable { vm.setEffort(effort) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                effort.display(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) colors.onPrimary else colors.onBackground,
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = Spacing.touchTarget)
                .clip(ShapePill).background(colors.surfaceContainerHigh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = colors.tertiary, modifier = Modifier.padding(start = 12.dp).size(17.dp))
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (query.isEmpty()) Text("Search models", style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
                    cursorBrush = SolidColor(colors.primary), singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search models" },
                )
            }
        }
        val keyOf: (ModelOption) -> String = { "${it.providerId}/${it.modelId}" }
        val visible = state.models.filter {
            it.providerId !in state.disabledProviders && keyOf(it) !in state.hiddenModels &&
                (it.providerId != "codex" || state.codexConnected) &&
                (query.isBlank() || it.label.contains(query, ignoreCase = true) || it.modelId.contains(query, ignoreCase = true))
        }
        val grouped = visible.groupBy { it.providerId }
        val providerNames = remember(state.models) { vm.allProviders().associate { it.id to it.displayName } }
        val favourites = visible.filter { keyOf(it) in state.favourites }
        LazyColumn(
            Modifier.heightIn(max = 480.dp).padding(horizontal = 6.dp, vertical = 4.dp)
                .fillMaxWidth(),
        ) {
            if (visible.isEmpty()) {
                item("models-empty") {
                    Text(
                        "No models match “${query.trim()}”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp),
                    )
                }
            }
            if (favourites.isNotEmpty()) {
                item("favourites-header") {
                    Text(
                        "Favourites",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(favourites, key = { "favourite:${keyOf(it)}" }) { option ->
                    val ready = vm.providerConfigured(option.providerId)
                    ModelRow(
                        option = option,
                        selected = ready && option == state.selected,
                        isFav = true,
                        ready = ready,
                        onSelect = { vm.selectModel(option) },
                        onSetup = { onConfigureProvider(option.providerId) },
                        onToggleFav = { vm.toggleFavourite(option) },
                    )
                }
            }
            grouped.forEach { (pid, options) ->
                val ready = vm.providerConfigured(pid)
                item("provider:$pid") {
                    Text(
                        (providerNames[pid] ?: pid) + if (ready) "" else " · Setup required",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ready) colors.onSurfaceVariant else colors.error,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(options, key = { "model:${keyOf(it)}" }) { option ->
                    ModelRow(
                        option = option,
                        selected = ready && option == state.selected,
                        isFav = keyOf(option) in state.favourites,
                        ready = ready,
                        onSelect = { vm.selectModel(option) },
                        onSetup = { onConfigureProvider(option.providerId) },
                        onToggleFav = { vm.toggleFavourite(option) },
                    )
                }
            }
        }
    }
}

// DEFAULT reads as "Auto": thinking adapts to the selected model (catalog reasoning capability)
// instead of one global effort silently applied to everything (round-3 feedback).
private fun ReasoningEffort.display(): String =
    when (this) {
        ReasoningEffort.DEFAULT -> "Auto"
        ReasoningEffort.XHIGH -> "Extra high"
        else -> name.lowercase().replaceFirstChar { it.uppercase() }
    }

@Composable
private fun ModelRow(
    option: ModelOption,
    selected: Boolean,
    isFav: Boolean,
    ready: Boolean,
    onSelect: () -> Unit,
    onSetup: () -> Unit,
    onToggleFav: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = if (ready) onSelect else onSetup).heightIn(min = 52.dp).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                option.label.substringAfterLast(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = if (ready) colors.onBackground else colors.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!ready) Text("Provider setup required", style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = colors.onBackground, modifier = Modifier.size(20.dp))
        Box(
            Modifier.size(Spacing.touchTarget).clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onToggleFav),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                if (isFav) "Unfavourite" else "Favourite",
                tint = if (isFav) colors.onBackground else colors.tertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Popovers
// ---------------------------------------------------------------------------------------------

@Composable
private fun PopoverCard(modifier: Modifier = Modifier, content: @Composable ColumnScopeAlias.() -> Unit) {
    // Rendered inside a full-width ModalBottomSheet (ContextPopover), which supplies the surface and
    // scrim - this just fills the sheet width and pads the content (the old 280dp cap left a narrow,
    // start-aligned card floating in a full-width sheet).
    Column(
        modifier.fillMaxWidth().padding(Spacing.s),
        content = content,
    )
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

internal fun questionAnswered(selected: Collection<String>, custom: String): Boolean =
    selected.isNotEmpty() || custom.isNotBlank()

@Composable
private fun ContextPopover(state: ChatUiState) {
    val colors = MaterialTheme.colorScheme
    val used = state.usageInput + state.usageOutput
    val limit = state.contextLimit
    val frac = limit?.let { if (it > 0) used.toFloat() / it else 0f } ?: 0f
    PopoverCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            ContextRing(fraction = frac, modifier = Modifier.size(52.dp), stroke = 3f, color = contextUsageColor(frac))
            Column {
                Text(if (limit != null) "${(frac * 100).toInt()}%" else fmt(used), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
                Text(
                    if (limit != null) "${fmt(used)} / ${fmt(limit)} tokens" else "tokens this turn",
                    style = MaterialTheme.typography.labelSmall, color = colors.tertiary,
                )
            }
        }
        UsageRow("Input", fmt(state.usageInput), colors.onBackground)
        UsageRow("Output", fmt(state.usageOutput), colors.secondary)
    }
}

@Composable
private fun UsageRow(label: String, value: String, swatch: androidx.compose.ui.graphics.Color) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(MaterialTheme.shapes.extraSmall).background(swatch))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.padding(start = 10.dp).weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = colors.onBackground, fontWeight = FontWeight.SemiBold)
    }
}

private fun fmt(n: Long): String = when {
    n >= 1_000_000 -> trimZero(n / 1_000_000.0) + "M"
    n >= 1_000 -> trimZero(n / 1_000.0) + "k"
    else -> n.toString()
}

private fun trimZero(v: Double): String = "%.1f".format(v).removeSuffix(".0")

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

@Composable
private fun PcDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 3f)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier.fillMaxWidth().heightIn(max = maxHeight)
                .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                .shadow(24.dp, MaterialTheme.shapes.extraLarge, clip = false)
                .clip(MaterialTheme.shapes.extraLarge).background(colors.surfaceContainerHigh).padding(Spacing.m),
            content = content,
        )
    }
}

@Composable
private fun DialogAction(text: String, emphasized: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> colors.tertiary
                emphasized -> colors.onBackground
                else -> colors.secondary
            },
        )
    }
}

@Composable
private fun PermissionDialog(request: PermissionRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val presentation = remember(request.tool) { approvalPresentation(request.tool) }
    var submitted by rememberSaveable(request) { mutableStateOf(false) }
    fun resolve(decision: () -> Unit) {
        if (submitted) return
        submitted = true
        decision()
    }
    PcDialog(
        onDismiss = { resolve(onDeny) },
        modifier = Modifier.fillMaxHeight(0.9f),
    ) {
        Column(Modifier.weight(1f).contentVerticalScroll(rememberScrollState())) {
            Text(
                "Approve agent action?",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Review this action before it runs.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(Spacing.m))
            Text("ACTION", style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
            Text(
                presentation.action,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "Tool · ${request.tool}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = PcMono),
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(Spacing.s))
            Text("DETAILS", style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
            Box(
                Modifier.fillMaxWidth().padding(top = 5.dp)
                    .heightIn(min = Spacing.touchTarget)
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surface)
                    .padding(Spacing.s),
            ) {
                Text(
                    request.summary.ifBlank { "No additional details were provided." },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PcMono),
                    color = colors.onBackground,
                )
            }
            Box(
                Modifier.fillMaxWidth().padding(top = Spacing.s)
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surface)
                    .padding(Spacing.s),
            ) {
                Column {
                    Text(presentation.risk, style = MaterialTheme.typography.labelLarge, color = colors.onBackground)
                    Text(
                        presentation.guidance,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(Modifier.weight(1f)) {
                PcButton("Deny", filled = false, enabled = !submitted) { resolve(onDeny) }
            }
            Box(Modifier.weight(1f)) {
                PcButton("Approve once", enabled = !submitted) { resolve(onApprove) }
            }
        }
    }
}

private data class ApprovalPresentation(
    val action: String,
    val risk: String,
    val guidance: String,
)

private fun approvalPresentation(tool: String): ApprovalPresentation {
    val normalized = tool.lowercase()
    return when {
        normalized == "external_directory" || normalized.startsWith("external_directory_") ->
            ApprovalPresentation(
                action = "Read outside linked folders",
                risk = "External file access",
                guidance = "This reads the exact file or folder path shown above. PhoneCode always asks for this access.",
            )
        normalized.startsWith("mcp_") ->
            ApprovalPresentation(
                action = "Run an MCP server action",
                risk = "Connected service change",
                guidance = "This enabled MCP server may send data to or change an external service.",
            )
        normalized.contains("shell") || normalized.contains("terminal") || normalized.contains("process") ->
            ApprovalPresentation(
                action = "Run a command",
                risk = "Command execution",
                guidance = "Commands can change files, install software, or contact external services.",
            )
        normalized.contains("write") || normalized.contains("edit") || normalized.contains("patch") ||
            normalized.contains("delete") || normalized.contains("move") ->
            ApprovalPresentation(
                action = "Change files",
                risk = "Workspace change",
                guidance = "The agent may create, edit, move, or delete project files.",
            )
        normalized.contains("git") ->
            ApprovalPresentation(
                action = "Run a Git operation",
                risk = "Repository change",
                guidance = "This may change branches, commits, or a connected remote repository.",
            )
        normalized.contains("web") || normalized.contains("http") || normalized.contains("fetch") ->
            ApprovalPresentation(
                action = "Contact an external service",
                risk = "External request",
                guidance = "Data in the request may be sent outside this device.",
            )
        else ->
            ApprovalPresentation(
                action = tool.replace('_', ' ').replaceFirstChar { it.uppercase() },
                risk = "Approval required",
                guidance = "Only approve actions that match what you asked PhoneCode to do.",
            )
    }
}

@Composable
private fun QuestionDialog(request: QuestionRequest, onSubmit: (List<UserAnswer>) -> Unit, onDismiss: () -> Unit) {
    if (request.questions.isEmpty()) {
        LaunchedEffect(request) { onSubmit(emptyList()) }
        return
    }
    val colors = MaterialTheme.colorScheme
    var page by rememberSaveable(request) { mutableIntStateOf(0) }
    val selections = rememberSaveable(
        request,
        saver = listSaver<List<SnapshotStateList<String>>, ArrayList<String>>(
            save = { orig -> orig.map { ArrayList(it) } },
            restore = { saved -> saved.map { it.toMutableStateList() } },
        ),
    ) { request.questions.map { mutableStateListOf<String>() } }
    val customAnswers = rememberSaveable(
        request,
        saver = listSaver<List<MutableState<String>>, String>(
            save = { orig -> orig.map { it.value } },
            restore = { saved -> saved.map { mutableStateOf(it) } },
        ),
    ) { request.questions.map { mutableStateOf("") } }
    val question = request.questions[page]
    fun answered(index: Int): Boolean =
        questionAnswered(selections[index], customAnswers[index].value)
    val currentAnswered = answered(page)
    val allAnswered = request.questions.indices.all(::answered)
    PcDialog(onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (question.header.isBlank()) "Question" else question.header,
                style = MaterialTheme.typography.labelLarge,
                color = colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${page + 1} of ${request.questions.size}", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(220, easing = PhoneEasings.easeInOut)) { direction * it / 4 } +
                    fadeIn(tween(160, easing = PhoneEasings.easeOut))) togetherWith
                    (slideOutHorizontally(tween(180, easing = PhoneEasings.easeInOut)) { -direction * it / 4 } +
                        fadeOut(tween(120, easing = PhoneEasings.easeOut)))
            },
            label = "questionPage",
            modifier = Modifier.weight(1f, fill = false),
        ) { index ->
            val item = request.questions[index]
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .contentVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.question, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                Text(
                    if (item.multiSelect) "Choose any that apply, or write your own." else "Choose one, or write your own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                item.options.forEach { option ->
                    val selected = selections[index].contains(option.label)
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                            .background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainer)
                            .semantics {
                                this.selected = selected
                                role = if (item.multiSelect) Role.Checkbox else Role.RadioButton
                            }
                            .clickable {
                                val chosen = selections[index]
                                if (item.multiSelect) {
                                    if (selected) chosen.remove(option.label) else chosen.add(option.label)
                                } else {
                                    chosen.clear()
                                    if (!selected) chosen.add(option.label)
                                }
                            }
                            .heightIn(min = 56.dp).padding(horizontal = Spacing.s, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                            if (option.description.isNotBlank()) {
                                Text(option.description, style = MaterialTheme.typography.bodySmall, color = colors.secondary, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        if (selected) Icon(Icons.Filled.Check, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                }
                dev.phonecode.app.ui.components.PcField(customAnswers[index].value, { customAnswers[index].value = it }, "Something else")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DialogAction("Skip all", emphasized = false, onClick = onDismiss)
            Spacer(Modifier.weight(1f))
            if (page > 0) DialogAction("Back", emphasized = false) { page-- }
            Spacer(Modifier.width(4.dp))
            if (page < request.questions.lastIndex) {
                DialogAction("Next", emphasized = true, enabled = currentAnswered) { page++ }
            } else {
                DialogAction("Submit", emphasized = true, enabled = allAnswered) {
                    if (!allAnswered) return@DialogAction
                    onSubmit(request.questions.mapIndexed { qi, question ->
                    val chosen = selections[qi].toMutableList()
                    val custom = customAnswers[qi].value.trim()
                    if (custom.isNotEmpty()) chosen.add(custom)
                    UserAnswer(question.question, chosen)
                })
                }
            }
        }
    }
}
