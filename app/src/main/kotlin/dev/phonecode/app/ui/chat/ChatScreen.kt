package dev.phonecode.app.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonecode.agent.AgentMode
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.ModelOption
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.QuestionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.git.GitViewModel
import dev.phonecode.app.ui.components.ContextRing
import dev.phonecode.app.ui.components.PcButton
import dev.phonecode.app.ui.components.PcDivider
import dev.phonecode.app.ui.components.PcIconButton
import dev.phonecode.app.ui.components.PcRoundButton
import dev.phonecode.app.ui.components.PcToggle
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
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.todo.TodoItem
import dev.phonecode.tools.todo.TodoStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STAMP = SimpleDateFormat("HH:mm · d MMM", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    gitVm: GitViewModel,
    onOpenDrawer: () -> Unit,
    sendOnEnter: Boolean = true,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val rootView = LocalView.current
    var input by rememberSaveable { mutableStateOf("") }
    // Round-4: the custom morphing popouts are retired for standard M3 modal bottom sheets
    // ("improve the pop-out menus, substantially. Maybe use the default Material3 Expressive
    // for now") - platform motion and scrim, native back/swipe dismissal, zero morph jank.
    var toolsOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    // Hoisted so the action row can deep-link straight to the git panel inside the tools sheet.
    var menuPanel by remember { mutableStateOf("main") }
    var composerHeight by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val empty = state.lines.isEmpty() && state.streaming.isEmpty() && state.streamingReasoning.isEmpty()

    LaunchedEffect(state.lines.size, state.streaming.length, state.streamingReasoning.length) {
        val extra = (if (state.streamingReasoning.isNotEmpty()) 1 else 0) + (if (state.streaming.isNotEmpty()) 1 else 0)
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
                    EmptyState(onSuggestion = { input = it }, modifier = Modifier.align(Alignment.Center))
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
                                    is ChatLine.User -> UserBubble(line.text)
                                    is ChatLine.Assistant -> AssistantTurn(
                                        text = line.text,
                                        reasoning = reasoningBefore(state.lines, i),
                                        streaming = false,
                                        showActions = i == lastAssistantIndex && !state.isRunning,
                                        completedAt = state.lastCompletedAt,
                                        onCopy = { },
                                        onRedo = vm::redo,
                                        onBranch = { gitVm.refresh(); menuPanel = "git"; toolsOpen = true },
                                        copyText = line.text,
                                    )
                                    // Thinking that wasn't followed by assistant text (e.g. think → tool call):
                                    // render it standalone so the trace is never lost.
                                    is ChatLine.Reasoning -> AssistantTurn(
                                        text = "",
                                        reasoning = line.text,
                                        streaming = false,
                                        showActions = false, completedAt = null,
                                        onCopy = {}, onRedo = {}, onBranch = {}, copyText = "",
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
                                        onCopy = {}, onRedo = {}, onBranch = {}, copyText = "",
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
                Modifier.fillMaxWidth().height(statusInset + 4.dp)
                    .blurFade(hazeState, bandStyle, fromTop = true),
            )
        }
        Box(Modifier.align(Alignment.TopStart).padding(top = statusInset + 6.dp, start = 12.dp).blurPill(hazeState, hazeStyle).border(1.dp, colors.outline.copy(alpha = 0.6f), ShapePill)) {
            // Opening the drawer clears any open overlay so Back/scrim semantics stay unambiguous.
            PcIconButton(Icons.Filled.Menu, "Menu") { toolsOpen = false; modelOpen = false; onOpenDrawer() }
        }
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = statusInset + 9.dp)
                .widthIn(max = 230.dp).blurPill(hazeState, hazeStyle),
        ) {
            Text(
                chatTitle(state),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = statusInset + 6.dp, end = 12.dp).blurPill(hazeState, hazeStyle).border(1.dp, colors.outline.copy(alpha = 0.6f), ShapePill)) {
            PcIconButton(Icons.Filled.Add, "New chat") { vm.newChat() }
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
                    .height(with(chromeDensity) { composerHeight.toDp() } + bottomInset + 26.dp)
                    .blurFade(hazeState, bandStyle, fromTop = false),
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
            Composer(
                state = state,
                input = input,
                onInput = { input = it },
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                onMenuToggle = { menuPanel = "main"; toolsOpen = true },
                onModelTap = { modelOpen = true },
                onSend = {
                    rootView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    vm.send(input); input = ""; toolsOpen = false
                },
                onStop = vm::cancel,
                sendOnEnter = sendOnEnter,
                modifier = Modifier.onSizeChanged { composerHeight = it.height },
            )
        }

        // The file picker is registered at SCREEN level: registering it inside the sheet's
        // conditional composition dropped results whenever the sheet/activity got recreated while
        // picking (device feedback: "attaching images/files doesn't work").
        val attachContext = LocalContext.current
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val attached = readAttachment(attachContext, uri)
                when (attached) {
                    null -> vm.surfaceError("Couldn't read that file.")
                    is Attachment.Binary -> vm.surfaceError("Only text files can be attached for now - image support is coming.")
                    is Attachment.Text -> input = buildString {
                        append(input)
                        if (input.isNotBlank()) append("\n\n")
                        append("File: ").append(attached.name).append("\n```\n").append(attached.content).append("\n```")
                    }
                }
            }
        }

        // Round-4 popouts: standard M3 modal bottom sheets - the platform's own surface, scrim,
        // spring motion, drag handle, swipe + back dismissal. Contents are unchanged.
        val sheetScope = rememberCoroutineScope()
        if (toolsOpen) {
            val toolsSheet = rememberModalBottomSheetState()
            fun closeTools(then: () -> Unit = {}) {
                sheetScope.launch { toolsSheet.hide() }.invokeOnCompletion { toolsOpen = false; then() }
            }
            ModalBottomSheet(
                onDismissRequest = { toolsOpen = false },
                sheetState = toolsSheet,
                containerColor = colors.surfaceContainerLow,
            ) {
                WrenchMenu(
                    state = state,
                    vm = vm,
                    gitVm = gitVm,
                    panel = menuPanel,
                    onPanel = { menuPanel = it },
                    onPickFile = { closeTools { picker.launch(arrayOf("*/*")) } },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        if (modelOpen) {
            val modelSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { modelOpen = false },
                sheetState = modelSheet,
                containerColor = colors.surfaceContainerLow,
            ) {
                ModelSheet(
                    state = state,
                    vm = vm,
                    onDone = {
                        sheetScope.launch { modelSheet.hide() }.invokeOnCompletion { modelOpen = false }
                    },
                )
            }
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
        Box(
            Modifier.size(56.dp).clip(ShapePill).background(colors.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ">_",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = PcMono, fontWeight = FontWeight.Bold),
                color = colors.onBackground,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("What should we build?", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Spacer(Modifier.height(20.dp))
        listOf(
            "Build a small web app",
            "Explain an error message",
            "Refactor a function",
            "Set up a git project",
        ).forEach { suggestion ->
            Text(
                suggestion,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                color = colors.secondary,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(ShapePill)
                    .clickable { onSuggestion(suggestion) }
                    .background(colors.surfaceContainer)
                    .border(1.dp, colors.outline, ShapePill)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(text: String) {
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
                .padding(horizontal = 15.dp, vertical = 11.dp),
        ) { Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground) }
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
    onBranch: () -> Unit,
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
                enter = expandVertically(animationSpec = PhoneSprings.standardSpec()) + fadeIn(PhoneTweens.popEnter),
                exit = shrinkVertically(animationSpec = PhoneSprings.standardSpec()) + fadeOut(PhoneTweens.popExit),
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
            val segments = remember(text) { splitFenced(text) }
            Column(Modifier.fillMaxWidth().padding(top = if (reasoning != null) 11.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                segments.forEachIndexed { i, seg ->
                    if (seg.isCode) CodeBlock(seg.text, seg.lang)
                    else {
                        val live = streaming && i == segments.lastIndex
                        MarkdownBlocks(seg.text, caret = if (live) " ▋" else "", streaming = live)
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
                ActionIcon(Icons.Outlined.AccountTree, "Git", onBranch)
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

@Composable
private fun ToolActivityView(line: ChatLine.ToolActivity) {
    val colors = MaterialTheme.colorScheme
    val error = line.status == ToolStatus.ERROR
    val running = line.status == ToolStatus.RUNNING
    // Live tools pulse their icon; finished ones sit quiet. (Compact chip per revamp-diagnosis #2 -
    // the old full-width card slabs dominated the conversation.)
    val iconAlpha = if (running) {
        val pulse by rememberInfiniteTransition(label = "tool").animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "toolPulse",
        )
        pulse
    } else 1f
    Row(
        Modifier.clip(ShapePill)
            .background(if (error) colors.errorContainer else colors.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            toolIcon(line.name), null,
            tint = (if (error) colors.onErrorContainer else colors.secondary).copy(alpha = iconAlpha),
            modifier = Modifier.size(13.dp),
        )
        Text(
            line.name,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = PcMono, fontWeight = FontWeight.Medium),
            color = if (error) colors.onErrorContainer else colors.onSurface,
        )
        if (line.detail.isNotBlank()) {
            Text(
                line.detail.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (error) colors.onErrorContainer.copy(alpha = 0.7f) else colors.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
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
private fun TodoPanel(todos: List<TodoItem>) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(MaterialTheme.shapes.medium)
            .background(colors.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val done = todos.count { it.status == TodoStatus.COMPLETED }
        Text("Tasks  $done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = colors.secondary, modifier = Modifier.padding(bottom = 2.dp))
        todos.forEach { todo ->
            val glyph = when (todo.status) {
                TodoStatus.PENDING -> "○"; TodoStatus.IN_PROGRESS -> "◐"; TodoStatus.COMPLETED -> "●"; TodoStatus.CANCELLED -> "✕"
            }
            val color = when (todo.status) {
                TodoStatus.COMPLETED, TodoStatus.CANCELLED -> colors.tertiary
                TodoStatus.IN_PROGRESS -> colors.onBackground
                TodoStatus.PENDING -> colors.secondary
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(glyph, style = MaterialTheme.typography.labelMedium.copy(fontFamily = PcMono), color = color)
                Text(todo.content, style = MaterialTheme.typography.labelMedium, color = color)
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
    onInput: (String) -> Unit,
    hazeState: HazeState,
    hazeStyle: HazeStyle,
    onMenuToggle: () -> Unit,
    onModelTap: () -> Unit,
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
            Row(
                Modifier.fillMaxWidth()
                    .neuralRing(active = state.isRunning, shape = ShapeComposer)
                    .blurPill(hazeState, hazeStyle, shape = ShapeComposer)
                    .border(1.dp, colors.outline.copy(alpha = 0.6f), ShapeComposer)
                    .animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Bare icon, no blob - the capsule itself is the only container.
                PcIconButton(Icons.Filled.Build, "Tools", tint = colors.secondary, onClick = onMenuToggle)
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
                        keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !state.isRunning) onSend() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Grok's signature: the model pill lives in the composer and collapses to nothing
                // the moment you type, re-expanding when the field empties. Tap -> model sheet
                // (its own surface - device feedback: not the same place as the tools).
                AnimatedVisibility(
                    visible = input.isEmpty() && !state.isRunning,
                    enter = expandHorizontally(animationSpec = PhoneSprings.standardSpec()) + fadeIn(PhoneTweens.popEnter),
                    exit = shrinkHorizontally(animationSpec = PhoneSprings.standardSpec()) + fadeOut(PhoneTweens.popExit),
                ) {
                    Text(
                        modelShortLabel(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(ShapePill)
                            .clickable(onClick = onModelTap)
                            .background(colors.surfaceContainerHigh)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                // Send exists only when it can act (text present or generation to stop); it pops in
                // with a small spring and swaps send<->stop via plain crossfade - no morphing.
                AnimatedVisibility(
                    visible = input.isNotBlank() || state.isRunning,
                    enter = scaleIn(initialScale = 0.6f, animationSpec = PhoneSprings.quickSpec()) + fadeIn(PhoneTweens.popEnter),
                    exit = scaleOut(targetScale = 0.6f, animationSpec = PhoneSprings.quickSpec()) + fadeOut(PhoneTweens.popExit),
                ) {
                    AnimatedContent(
                        targetState = state.isRunning,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                        label = "sendStop",
                    ) { running ->
                        if (running) {
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

/** The attach result: readable text, or a binary we refuse honestly. */
private sealed interface Attachment {
    data class Text(val name: String, val content: String) : Attachment
    data object Binary : Attachment
}

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
    gitVm: GitViewModel,
    panel: String,
    onPanel: (String) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // Sheet content: the M3 ModalBottomSheet supplies surface, scrim, and back handling.
    Column(
        modifier.fillMaxWidth().animateContentSize(PhoneSprings.emphasizedSpec()),
    ) {
        AnimatedContent(
            targetState = panel,
            transitionSpec = { (fadeIn(PhoneTweens.popEnter) togetherWith fadeOut(PhoneTweens.popExit)) },
            label = "panel",
        ) { p ->
            when (p) {
                "thinking" -> PickList(title = "Thinking", onBack = { onPanel("main") }) {
                    ReasoningEffort.entries.forEach { e ->
                        PickRow(label = e.display(), selected = state.effort == e) { vm.setEffort(e); onPanel("main") }
                    }
                }
                "context" -> PickList(title = "Context", onBack = { onPanel("main") }) {
                    ContextPopover(state)
                }
                "git" -> PickList(title = "Git", onBack = { onPanel("main") }) {
                    GitPopover(gitVm)
                }
                else -> Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    MenuRow(icon = Icons.Filled.Image, label = "Photo / file", onClick = onPickFile)
                    PcDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    KvRow(
                        "Thinking",
                        if (vm.supportsReasoning(state.selected)) state.effort.display() else "Off - model has none",
                    ) { onPanel("thinking") }
                    // Plan is a TOGGLE, not a mode list (device feedback): on = read-only
                    // planning, off = build. The mode enum survives underneath.
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .heightIn(min = 48.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Plan mode", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
                        PcToggle(checked = state.agentMode == AgentMode.PLAN) { on ->
                            vm.setAgentMode(if (on) AgentMode.PLAN else AgentMode.BUILD)
                        }
                    }
                    PcDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    val used = state.usageInput + state.usageOutput
                    val ctxValue = state.contextLimit?.let { if (it > 0) "${(used * 100 / it).toInt()}%" else fmt(used) } ?: fmt(used)
                    KvRow("Context", ctxValue) { onPanel("context") }
                    KvRow("Git", "") { gitVm.refresh(); onPanel("git") }
                }
            }
        }
    }
}

/** Dedicated model sheet - searchable, provider-grouped, favourites first; selection closes it. */
@Composable
private fun ModelSheet(state: ChatUiState, vm: ChatViewModel, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Model",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onBackground,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
        )
        // Search across every visible model (device feedback).
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
        Column(Modifier.heightIn(max = 480.dp).padding(horizontal = 6.dp, vertical = 4.dp).verticalScroll(rememberScrollState())) {
            fun key(o: ModelOption) = "${o.providerId}/${o.modelId}"
            // Respect provider management: disabled providers and hidden models stay out of the picker.
            val visible = state.models.filter {
                it.providerId !in state.disabledProviders && key(it) !in state.hiddenModels &&
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
                        onSelect = { vm.selectModel(option); onDone() },
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
                        onSelect = { vm.selectModel(option); onDone() },
                        onToggleFav = { vm.toggleFavourite(option) })
                }
            }
        }
    }
}

// DEFAULT reads as "Auto": thinking adapts to the selected model (catalog reasoning capability)
// instead of one global effort silently applied to everything (round-3 feedback).
private fun ReasoningEffort.display(): String =
    if (this == ReasoningEffort.DEFAULT) "Auto" else name.lowercase().replaceFirstChar { it.uppercase() }
private fun AgentMode.display(): String = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun PickList(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(28.dp).clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.secondary, modifier = Modifier.size(18.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
        }
        Column(Modifier.heightIn(max = 392.dp).padding(horizontal = 6.dp, vertical = 4.dp).verticalScroll(rememberScrollState())) {
            content()
        }
    }
}

@Composable
private fun ModelRow(option: ModelOption, selected: Boolean, isFav: Boolean, onSelect: () -> Unit, onToggleFav: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onSelect).heightIn(min = 48.dp).padding(start = 14.dp),
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
private fun PickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Filled.Check, null, tint = colors.onBackground, modifier = Modifier.size(20.dp))
    }
}

// Popout rows share ONE list language with the redesigned settings (round-3: "not formatted,
// don't follow any design language"): 48dp menu rows, 14dp side padding, bodyLarge labels,
// onSurfaceVariant secondaries, real chevron glyphs.

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
    }
}

@Composable
private fun KvRow(key: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Popovers
// ---------------------------------------------------------------------------------------------

@Composable
private fun PopoverCard(modifier: Modifier = Modifier, content: @Composable ColumnScopeAlias.() -> Unit) {
    // Hosted inside a Material DropdownMenu, which supplies container, elevation, and shape -
    // this only sizes and pads the content.
    Column(
        modifier.widthIn(min = 212.dp, max = 280.dp).padding(Spacing.s),
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
            ContextRing(fraction = frac, modifier = Modifier.size(52.dp), stroke = 3f)
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

@Composable
private fun GitPopover(gitVm: GitViewModel) {
    val colors = MaterialTheme.colorScheme
    val git by gitVm.state.collectAsStateWithLifecycle()
    var showBranches by remember { mutableStateOf(false) }
    PopoverCard {
        Text(
            "GIT · ${(git.status?.branch ?: "no repo").uppercase()}",
            style = MaterialTheme.typography.labelSmall, color = colors.tertiary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        if (!git.isRepo) {
            PcButton("Initialize repository", filled = false) { gitVm.init() }
        } else {
            val s = git.status
            UsageRow("Staged", "${s?.staged?.size ?: 0}", colors.onBackground)
            UsageRow("Modified", "${s?.unstaged?.size ?: 0}", colors.secondary)
            UsageRow("Untracked", "${s?.untracked?.size ?: 0}", colors.tertiary)
            // Branch switcher (collapsed by default - most users never need it).
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall).clickable { showBranches = !showBranches }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Branches", style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.weight(1f))
                Text(if (showBranches) "▴" else "▾", style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
            }
            if (showBranches) {
                git.branches.forEach { branch ->
                    val current = branch == git.status?.branch
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall)
                            .clickable(enabled = !current) { gitVm.checkout(branch); showBranches = false }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            branch,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = PcMono),
                            color = if (current) colors.onBackground else colors.secondary,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (current) Icon(Icons.Filled.Check, null, tint = colors.onBackground, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PcButton("Save a snapshot", filled = true) {
                gitVm.stageAll()
                gitVm.commit("Snapshot " + STAMP.format(Date()))
            }
        }
    }
}

private fun fmt(n: Long): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

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
    val colors = MaterialTheme.colorScheme
    val selections = remember(request) { request.questions.map { mutableStateListOf<String>() } }
    val customAnswers = remember(request) { request.questions.map { mutableStateOf("") } }
    PcDialog(onDismiss) {
        Text("The agent has a question", style = MaterialTheme.typography.titleMedium, color = colors.onBackground, modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            request.questions.forEachIndexed { qi, question ->
                Column(Modifier.padding(top = if (qi == 0) 0.dp else Spacing.s)) {
                    if (question.header.isNotBlank()) Text(question.header.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
                    Text(question.question, style = MaterialTheme.typography.bodySmall, color = colors.onBackground)
                }
                question.options.forEach { option ->
                    val selected = selections[qi].contains(option.label)
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .background(if (selected) colors.surfaceContainerHighest else colors.surface)
                            .clickable {
                                val chosen = selections[qi]
                                if (question.multiSelect) { if (selected) chosen.remove(option.label) else chosen.add(option.label) }
                                else { chosen.clear(); if (!selected) chosen.add(option.label) }
                            }
                            .heightIn(min = Spacing.touchTarget).padding(Spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodySmall, color = colors.onBackground)
                            if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.padding(top = 2.dp))
                        }
                        if (selected) Icon(Icons.Filled.Check, null, tint = colors.onBackground, modifier = Modifier.size(16.dp))
                    }
                }
                dev.phonecode.app.ui.components.PcField(customAnswers[qi].value, { customAnswers[qi].value = it }, "Other / custom...")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            DialogAction("Skip", emphasized = false, onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
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
