package dev.phonecode.app.ui.chat

import android.annotation.SuppressLint
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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.agent.AgentMode
import dev.phonecode.app.R
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.ModelOption
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.QuestionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.ui.components.ContextRing
import dev.phonecode.app.ui.components.PcDivider
import dev.phonecode.app.ui.components.PcIconButton
import dev.phonecode.app.ui.components.MorphingMenu
import dev.phonecode.app.ui.components.PcRoundButton
import dev.phonecode.app.ui.components.elasticOverscroll
import dev.phonecode.app.ui.components.pressFeedback
import androidx.compose.material3.ripple
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeSource
import dev.phonecode.app.ui.theme.Ethereal
import dev.phonecode.app.ui.theme.blurFade
import dev.phonecode.app.ui.theme.blurPill
import dev.phonecode.app.ui.theme.phoneHaze
import dev.phonecode.app.ui.theme.phoneHazeBand
import dev.phonecode.app.ui.theme.PcMono
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

private val STAMP = SimpleDateFormat("HH:mm · d MMM", Locale.getDefault())

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
    sendOnEnter: Boolean = true,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val rootView = LocalView.current
    var input by rememberSaveable { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<MessagePart.Image>>(emptyList()) }
    // Round-4: the custom morphing popouts are retired for standard M3 modal bottom sheets
    // ("improve the pop-out menus, substantially. Maybe use the default Material3 Expressive
    // for now") - platform motion and scrim, native back/swipe dismissal, zero morph jank.
    var toolsOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var contextOpen by remember { mutableStateOf(false) }
    var composerHeight by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val empty = state.lines.isEmpty() && state.streaming.isEmpty() && state.streamingReasoning.isEmpty()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val attachContext = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val mime = attachContext.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) {
                val photo = withContext(Dispatchers.IO) { readPhoto(attachContext, uri) }
                if (photo == null) vm.surfaceError("Couldn't read that photo.") else photos = listOf(photo)
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

    LaunchedEffect(state.lines.size, state.streaming.length, state.streamingReasoning.length) {
        // The list appends exactly one combined streaming item, so this is a single flag, not a sum.
        val extra = if (state.streamingReasoning.isNotEmpty() || state.streaming.isNotEmpty()) 1 else 0
        val count = state.lines.size + extra
        // Instant, not animated: iOS doesn't animate the sender's own scroll, and per-token
        // animated scrolling during streaming is what reads as "lag".
        if (count > 0) listState.scrollToItem(count - 1)
    }

    // ChatGPT's signature "the model is typing" haptic: a light tick per streamed batch, debounced
    // so fast token rates don't turn into a buzz (chatgpt-motion.md - the single feature users
    // read as "feels alive").
    var lastHapticAt by remember { mutableStateOf(0L) }
    LaunchedEffect(state.streaming.length) {
        if (state.isRunning && state.streaming.isNotEmpty()) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastHapticAt >= 60) {
                lastHapticAt = now
                rootView.performHapticFeedback(
                    if (android.os.Build.VERSION.SDK_INT >= 27) android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE
                    else android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                )
            }
        }
    }

    // NOTE: no imePadding anywhere in this screen - the root container applies safeDrawing
    // (bars + IME) exactly once; adding it again here is what flung the composer off-screen.
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // Ethereal ambient mist: while the model runs, a slow breathing wash of light at the top
        // of the screen - monochrome (white mist on black, soft shadow on white).
        if (state.isRunning) {
            val breath by rememberNeuralBreath(3000)
            Box(
                Modifier.fillMaxWidth().height(190.dp)
                    .graphicsLayer { alpha = 0.4f + 0.5f * breath }
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
        val hazeState = remember { HazeState() }
        val hazeStyle = phoneHaze()
        val bandStyle = phoneHazeBand()
        val chromeDensity = LocalDensity.current
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            // New-chat transition: conversation fades out, empty state fades in (chatgpt-motion.md
            // - a fade, never a slide; exits faster than enters).
            AnimatedContent(
                targetState = empty,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "emptySwap",
                modifier = Modifier.fillMaxSize(),
            ) { isEmpty ->
                Box(Modifier.fillMaxSize()) {
                if (isEmpty) {
                    AnimatedVisibility(
                        visible = !imeVisible,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(120)),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        EmptyState(onSuggestion = { input = it })
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
                        modifier = Modifier.elasticOverscroll().fillMaxSize(),
                        overscrollEffect = null,
                        // Padding clears the floating chrome at rest while letting scrolled
                        // content slide beneath the pills (top) and the composer (bottom).
                        contentPadding = PaddingValues(
                            start = 18.dp, end = 18.dp,
                            top = statusInset + Spacing.navBarHeight + 10.dp,
                            bottom = with(chromeDensity) { composerHeight.toDp() } +
                                WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 18.dp,
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
                                        completedAt = state.lastCompletedAt,
                                        onCopy = { },
                                        onRedo = vm::redo,
                                        copyText = line.text,
                                    )
                                    // Thinking that wasn't followed by assistant text (e.g. think → tool call):
                                    // render it standalone so the trace is never lost.
                                    is ChatLine.Reasoning -> AssistantTurn(
                                        text = "",
                                        reasoning = line.text,
                                        streaming = false,
                                        showActions = false, completedAt = null,
                                        onCopy = {}, onRedo = {}, copyText = "",
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
                                        showActions = false, completedAt = null,
                                        onCopy = {}, onRedo = {}, copyText = "",
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }

        }

        // ----- Floating chrome (each piece its own blurred pill; the band is gone) -----
        // Status-bar dissolve: content blurs out as it slides under the very top. The band exists
        // ONLY while content is actually beneath it - on a fresh chat nothing is under the bar,
        // and an unconditional band read as a status-bar tint at launch (round-4 feedback).
        androidx.compose.animation.AnimatedVisibility(
            visible = !empty && listState.canScrollBackward,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier.fillMaxWidth().height(statusInset + 14.dp)
                    .blurFade(hazeState, bandStyle, fromTop = true, edgeColor = colors.background),
            )
        }
        Box(Modifier.align(Alignment.TopStart).padding(top = statusInset + 6.dp, start = 12.dp).clip(ShapePill).background(colors.surfaceContainerHigh)) {
            // Opening the drawer clears any open overlay so Back/scrim semantics stay unambiguous.
            PcIconButton(Icons.Filled.Menu, "Menu") { toolsOpen = false; modelOpen = false; onOpenDrawer() }
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = statusInset + 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.widthIn(max = 230.dp).blurPill(hazeState, hazeStyle)) {
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
                Modifier.padding(top = 3.dp).blurPill(hazeState, hazeStyle, shape = ShapePill)
                    .clickable { toolsOpen = false; modelOpen = true }
                    .padding(start = 11.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    modelShortLabel(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondary,
                    maxLines = 1,
                )
                Icon(Icons.Filled.KeyboardArrowDown, "Switch model", tint = colors.secondary, modifier = Modifier.size(15.dp))
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
                Modifier.size(40.dp).clip(ShapePill).background(colors.surfaceContainerHigh)
                    .clickable { toolsOpen = false; modelOpen = false; contextOpen = true }
                    .semantics { contentDescription = "Context usage ${(ctxFrac.coerceIn(0f, 1f) * 100).toInt()} percent" },
                contentAlignment = Alignment.Center,
            ) {
                ContextRing(fraction = ctxFrac, modifier = Modifier.size(22.dp), stroke = 2.5f, color = contextUsageColor(ctxFrac))
                MorphingMenu(
                    expanded = contextOpen,
                    onDismiss = { contextOpen = false },
                    above = false,
                    alignEnd = true,
                    modifier = Modifier.width(280.dp),
                ) {
                    ContextPopover(state)
                }
            }
            Box(Modifier.clip(ShapePill).background(colors.surfaceContainerHigh)) {
                PcIconButton(Icons.Filled.Add, "New chat") { vm.newChat() }
            }
        }

        // Bottom dissolve band behind the floating composer AND the nav bar: text stays visible
        // through both, frosting as it goes under (signed prototype; navbar must not be solid).
        // Same gating as the top: only while content can still scroll under the composer.
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        androidx.compose.animation.AnimatedVisibility(
            visible = !empty && listState.canScrollForward,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .height(with(chromeDensity) { composerHeight.toDp() } + bottomInset + 24.dp)
                    .blurFade(hazeState, bandStyle, fromTop = false, edgeColor = colors.background),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // Union of ime+navbar: above the keyboard when typing, above the navbar otherwise.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            state.error?.let { ErrorBanner(it) { vm.clearError() } }
            state.notice?.let {
                NoticeBanner(it)
                LaunchedEffect(it) { kotlinx.coroutines.delay(3500); vm.clearNotice() }
            }
            if (state.todos.isNotEmpty()) TodoPanel(state.todos)
            if (state.queued.isNotEmpty()) QueuedMessages(state.queued)
            Composer(
                state = state,
                input = input,
                photos = photos,
                onInput = { input = it },
                onRemovePhoto = { photos = emptyList() },
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                menuExpanded = toolsOpen,
                onMenuToggle = { toolsOpen = !toolsOpen },
                onMenuDismiss = { toolsOpen = false },
                menuContent = {
                    WrenchMenu(
                        state = state,
                        vm = vm,
                        onUpload = {
                            toolsOpen = false
                            picker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml"))
                        },
                        modifier = Modifier.width(320.dp),
                    )
                },
                onSend = {
                    rootView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    vm.send(input, photos); input = ""; photos = emptyList(); toolsOpen = false
                },
                onStop = vm::cancel,
                sendOnEnter = sendOnEnter,
                modifier = Modifier.onSizeChanged { composerHeight = it.height },
            )
        }

        // The file picker is registered at SCREEN level: registering it inside the sheet's
        // conditional composition dropped results whenever the sheet/activity got recreated while
        // picking (device feedback: "attaching images/files doesn't work").
        if (modelOpen) PcSheet(onDismiss = { modelOpen = false }) { close ->
            ModelSheet(state = state, vm = vm, onDone = close)
        }

        state.pendingPermission?.let { r ->
            PermissionDialog(r, onApprove = { vm.resolvePermission(true) }, onDeny = { vm.resolvePermission(false) })
        }
        state.pendingQuestion?.let { r ->
            QuestionDialog(r, onSubmit = { vm.resolveQuestion(it) }, onDismiss = { vm.resolveQuestion(emptyList()) })
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

/**
 * iMessage default-send entrance (apple-motion-exact.md §1): a ~24px rise on a critically
 * damped spring plus a 300ms ease-out fade - the bubble appears AT its list position; nothing
 * flies across the screen.
 */
@Composable
private fun Modifier.messageEnter(animate: Boolean): Modifier {
    if (!animate) return this
    val offsetY = remember { androidx.compose.animation.core.Animatable(24f) }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                offsetY.animateTo(0f, spring(dampingRatio = 1f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow, visibilityThreshold = 0.5f))
            }
            launch {
                alpha.animateTo(1f, tween(300, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
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
private fun EmptyState(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // Grok-style home: crisp mark + wordmark + starter chips. The chat stays quiet at rest -
    // no halos, no gradients; the ethereal layer belongs to generation only (grok-design.md).
    Column(modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painter = painterResource(R.drawable.ic_phonecode_mark), contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
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
                    .background(colors.surfaceContainer)
                    .border(1.dp, colors.outline, ShapePill)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(text: String, images: List<MessagePart.Image>) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 300.dp)
                // Uniform large radius (Grok rounded-4xl) - short messages read as full pills.
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerHigh)
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        clipboard.setText(AnnotatedString(text))
                    },
                )
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
    }
}

@Composable
private fun AssistantTurn(
    text: String,
    reasoning: String?,
    streaming: Boolean,
    showActions: Boolean,
    completedAt: Long?,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
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
                Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { open = !open }.padding(vertical = 3.dp, horizontal = 2.dp),
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

        AnimatedVisibility(visible = showActions, enter = fadeIn(PhoneTweens.popEnter), exit = fadeOut(PhoneTweens.popExit)) {
            Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // Copy flips to a checkmark for ~1.8s - the standard confirmation (chatgpt-motion.md).
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
                if (completedAt != null) {
                    Text(
                        STAMP.format(Date(completedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.tertiary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingDot(active: Boolean, open: Boolean) {
    val colors = MaterialTheme.colorScheme
    // Only run the infinite pulse while streaming - an idle dot costs zero animation frames.
    val alpha = if (active) {
        val pulse by rememberInfiniteTransition(label = "think").animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
        )
        pulse
    } else 1f
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
        Modifier.size(8.dp).graphicsLayer { this.alpha = alpha; scaleX = if (open) 1.2f else 1f; scaleY = if (open) 1.2f else 1f }
            .clip(ShapePill).then(dotBackground),
    )
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(32.dp).clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = colors.secondary, modifier = Modifier.size(17.dp)) }
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
    var heightDp by remember(source) { mutableStateOf(0) }
    val html = remember(source, dark, mermaidJs) { mermaidHtml(source, dark, colors.onBackground, mermaidJs) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surface)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface fun reportHeight(px: Int) { mainHandler.post { heightDp = px } }
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
  document.getElementById('c').innerHTML='<pre style="color:$fgCss;white-space:pre-wrap;font:12px monospace;">'+(e&&e.message?String(e.message):'diagram error')+'</pre>';done();
 });
}catch(e){done();}
</script>
</body></html>"""
}

@Composable
private fun ToolActivityView(line: ChatLine.ToolActivity) {
    val colors = MaterialTheme.colorScheme
    val error = line.status == ToolStatus.ERROR
    val running = line.status == ToolStatus.RUNNING
    var expanded by remember(line.id) { mutableStateOf(false) }
    val iconAlpha = if (running) {
        val pulse by rememberInfiniteTransition(label = "tool").animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "toolPulse",
        )
        pulse
    } else 1f
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (error) colors.errorContainer else colors.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .animateContentSize(PhoneSprings.standardSpec()),
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
                    tint = (if (error) colors.onErrorContainer else colors.secondary).copy(alpha = iconAlpha),
                    modifier = Modifier.size(16.dp),
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
        AnimatedVisibility(visible = expanded && line.detail.isNotBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                line.detail,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = PcMono),
                color = if (error) colors.onErrorContainer else colors.secondary,
                modifier = Modifier.fillMaxWidth().padding(start = 50.dp, end = 12.dp, bottom = 10.dp),
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
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
    name.startsWith("question") -> Icons.Outlined.HelpOutline
    else -> Icons.Outlined.Build
}

@Composable
private fun NoticeBanner(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(MaterialTheme.shapes.small)
            .background(colors.surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(MaterialTheme.shapes.small)
            .background(colors.errorContainer).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.onErrorContainer, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.Close, "Dismiss", tint = colors.onErrorContainer, modifier = Modifier.size(16.dp).clickable(onClick = onDismiss))
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
            .background(colors.surface)
            .animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(10.dp),
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
                Modifier.elasticOverscroll().fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState(), overscrollEffect = null)
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
    input: String,
    photos: List<MessagePart.Image>,
    onInput: (String) -> Unit,
    onRemovePhoto: () -> Unit,
    hazeState: HazeState,
    hazeStyle: HazeStyle,
    menuExpanded: Boolean,
    onMenuToggle: () -> Unit,
    onMenuDismiss: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendOnEnter: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
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
                    .animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (photos.isNotEmpty()) {
                    Box(Modifier.padding(start = 44.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)) {
                        PhotoThumbnail(photos.first(), Modifier.size(72.dp).clip(MaterialTheme.shapes.medium))
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp)
                                .size(24.dp).clip(ShapePill).background(colors.inverseSurface)
                                .clickable(onClick = onRemovePhoto),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Close, "Remove photo", tint = colors.inverseOnSurface, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                // ChatGPT-style: a bare + opens tools/attach; the capsule itself is the only container.
                Box {
                    PcIconButton(Icons.Filled.Add, "Tools", tint = colors.secondary, onClick = onMenuToggle)
                    MorphingMenu(
                        expanded = menuExpanded,
                        onDismiss = onMenuDismiss,
                        above = true,
                        modifier = Modifier.width(320.dp),
                        content = menuContent,
                    )
                }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    if (input.isEmpty()) Text("Message...", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onBackground),
                        cursorBrush = SolidColor(colors.primary),
                        maxLines = 6,
                        keyboardOptions = if (sendOnEnter) {
                            KeyboardOptions(imeAction = ImeAction.Send)
                        } else {
                            KeyboardOptions.Default
                        },
                        keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() || photos.isNotEmpty()) onSend() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Send exists when it can act (text present or generation to stop); it pops in with a small
                // spring and swaps send<->stop via plain crossfade. While a turn runs, typing shows Send
                // (which queues the message) and an empty box shows Stop - so you can queue or stop.
                AnimatedVisibility(
                    visible = input.isNotBlank() || photos.isNotEmpty() || state.isRunning,
                    enter = scaleIn(initialScale = 0.6f, animationSpec = PhoneSprings.quickSpec()) + fadeIn(PhoneTweens.popEnter),
                    exit = scaleOut(targetScale = 0.6f, animationSpec = PhoneSprings.quickSpec()) + fadeOut(PhoneTweens.popExit),
                ) {
                    AnimatedContent(
                        targetState = state.isRunning && input.isBlank(),
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                        label = "sendStop",
                    ) { stop ->
                        if (stop) {
                            PcRoundButton(Icons.Filled.Stop, "Stop", filled = true, onClick = onStop)
                        } else {
                            PcRoundButton(Icons.Filled.ArrowUpward, "Send", filled = true, onClick = onSend)
                        }
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
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(MaterialTheme.shapes.medium))
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
private fun WrenchMenu(
    state: ChatUiState,
    vm: ChatViewModel,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(6.dp)) {
        Text(
            "Add to chat",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        )
        PopoverActionRow(Icons.Outlined.AttachFile, "Upload", "Photo or text file", onClick = onUpload)
        PopoverActionRow(
            Icons.Outlined.Checklist,
            "Mode",
            if (state.agentMode == AgentMode.PLAN) "Plan" else "Build",
            onClick = { vm.setAgentMode(if (state.agentMode == AgentMode.PLAN) AgentMode.BUILD else AgentMode.PLAN) },
        )
    }
}

@Composable
private fun ModelSheet(state: ChatUiState, vm: ChatViewModel, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val reasoningEfforts = vm.reasoningEfforts(state.selected)
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
                modifier = Modifier.clip(ShapePill).clickable(onClick = onDone).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
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
                            Modifier.height(40.dp).clip(ShapePill)
                                .background(if (selected) colors.primary else colors.surfaceContainerHigh)
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
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(40.dp)
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(Modifier.elasticOverscroll().heightIn(max = 480.dp).padding(horizontal = 6.dp, vertical = 4.dp).verticalScroll(rememberScrollState(), overscrollEffect = null)) {
            fun key(o: ModelOption) = "${o.providerId}/${o.modelId}"
            // Respect provider management: disabled providers and hidden models stay out of the picker.
            val visible = state.models.filter {
                it.providerId !in state.disabledProviders && key(it) !in state.hiddenModels &&
                    (it.providerId != "codex" || state.codexConnected) &&
                    (query.isBlank() || it.label.contains(query, ignoreCase = true) || it.modelId.contains(query, ignoreCase = true))
            }
            val grouped = visible.groupBy { it.providerId }
            val providerNames = remember(state.models) { vm.allProviders().associate { it.id to it.displayName } }
            val favourites = visible.filter { key(it) in state.favourites }
            if (favourites.isNotEmpty()) {
                Text(
                    "Favourites",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                )
                favourites.forEach { option ->
                    ModelRow(option, option == state.selected, isFav = true,
                        onSelect = { vm.selectModel(option) },
                        onToggleFav = { vm.toggleFavourite(option) })
                }
            }
            grouped.forEach { (pid, options) ->
                Text(
                    providerNames[pid] ?: pid,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                )
                options.forEach { option ->
                    ModelRow(option, option == state.selected, isFav = key(option) in state.favourites,
                        onSelect = { vm.selectModel(option) },
                        onToggleFav = { vm.toggleFavourite(option) })
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
private fun ModelRow(option: ModelOption, selected: Boolean, isFav: Boolean, onSelect: () -> Unit, onToggleFav: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onSelect).heightIn(min = 52.dp).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option.label.substringAfterLast(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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

@Composable
private fun PopoverActionRow(
    icon: ImageVector,
    label: String,
    detail: String,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressFeedback(interaction, pressedScale = 0.98f) else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = ripple(), onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, tint = if (onClick == null) colors.tertiary else colors.secondary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.onBackground)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun PcDialog(onDismiss: () -> Unit, content: @Composable ColumnScopeAlias.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().shadow(24.dp, MaterialTheme.shapes.extraLarge, clip = false)
                .clip(MaterialTheme.shapes.extraLarge).background(colors.surfaceContainerHigh).padding(Spacing.m),
            content = content,
        )
    }
}

@Composable
private fun DialogAction(text: String, emphasized: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (emphasized) colors.onBackground else colors.secondary)
    }
}

@Composable
private fun PermissionDialog(request: PermissionRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    PcDialog(onDeny) {
        Text("Allow ${request.tool}?", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(request.summary, style = MaterialTheme.typography.labelMedium, color = colors.secondary)
        Spacer(Modifier.height(Spacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogAction("Deny", emphasized = false, onClick = onDeny)
            Spacer(Modifier.width(8.dp))
            DialogAction("Allow", emphasized = true, onClick = onApprove)
        }
    }
}

@Composable
private fun QuestionDialog(request: QuestionRequest, onSubmit: (List<UserAnswer>) -> Unit, onDismiss: () -> Unit) {
    if (request.questions.isEmpty()) {
        LaunchedEffect(request) { onSubmit(emptyList()) }
        return
    }
    val colors = MaterialTheme.colorScheme
    var page by rememberSaveable(request) { mutableStateOf(0) }
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
                (slideInHorizontally(tween(220)) { direction * it / 4 } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(180)) { -direction * it / 4 } + fadeOut(tween(120)))
            },
            label = "questionPage",
        ) { index ->
            val item = request.questions[index]
            Column(
                Modifier.elasticOverscroll().padding(top = 12.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState(), overscrollEffect = null),
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
                DialogAction("Next", emphasized = true) { page++ }
            } else {
                DialogAction("Submit", emphasized = true) {
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
