package dev.phonecode.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.AiReportSubmission
import dev.phonecode.app.agent.ModelOption
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.QuestionRequest
import dev.phonecode.app.ui.components.ContextRing
import dev.phonecode.app.ui.components.ActionRole
import dev.phonecode.app.ui.components.MisulActionButton
import dev.phonecode.app.ui.components.MisulDialogAction
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulIconButton
import dev.phonecode.app.ui.components.MisulTextAction
import dev.phonecode.app.ui.components.MorphingMenu
import dev.phonecode.app.ui.components.contentVerticalScroll
import dev.phonecode.app.ui.components.predictiveBackTransform
import dev.phonecode.app.ui.components.rememberPredictiveBackMotion
import dev.phonecode.app.ui.theme.LocalMisulAccent
import dev.phonecode.app.ui.theme.PcMono
import dev.phonecode.app.ui.theme.PhoneEasings
import dev.phonecode.app.ui.theme.ShapePill
import dev.phonecode.app.ui.theme.Spacing
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.tools.UserAnswer
import kotlinx.coroutines.launch

/**
 * Decision routing: model, tool and approval details use the platform ModalBottomSheet host;
 * short prompts use the centered dialog actions (MisulDialogAction), while reports remain a
 * full-screen DialogProperties(usePlatformDefaultWidth = false) flow.
 */
@Composable
internal fun ToolDetailsSheet(
    line: ChatLine.ToolActivity,
    running: Boolean,
    action: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    PcSheet(onDismiss = onDismiss) { close ->
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(action, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
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

@Composable
internal fun ChatOverlays(
    modelOpen: Boolean,
    onDismissModel: () -> Unit,
    models: List<ModelOption>,
    selectedModel: ModelOption?,
    selectedEffort: ReasoningEffort,
    disabledProviders: Set<String>,
    hiddenModels: Set<String>,
    favourites: Set<String>,
    codexConnected: Boolean,
    providerConfigured: (String) -> Boolean,
    reasoningEfforts: (ModelOption?) -> List<ReasoningEffort>,
    providerNames: () -> Map<String, String>,
    onSetEffort: (ReasoningEffort) -> Unit,
    onSelectModel: (ModelOption) -> Unit,
    onToggleFavourite: (ModelOption) -> Unit,
    onConfigureProvider: (String) -> Unit,
    pendingPermission: PermissionRequest?,
    onResolvePermission: (Boolean) -> Unit,
    pendingQuestion: QuestionRequest?,
    onResolveQuestion: (List<UserAnswer>) -> Unit,
    reportOpen: Boolean,
    reportSubmitting: Boolean,
    reportSubmission: AiReportSubmission?,
    onClearReportSubmission: () -> Unit,
    onSubmitReport: (String, String) -> Unit,
    onDismissReport: () -> Unit,
) {
    if (modelOpen) PcSheet(onDismiss = onDismissModel) { close ->
        ModelSheet(
            models = models,
            selectedModel = selectedModel,
            selectedEffort = selectedEffort,
            disabledProviders = disabledProviders,
            hiddenModels = hiddenModels,
            favourites = favourites,
            codexConnected = codexConnected,
            providerConfigured = providerConfigured,
            reasoningEfforts = reasoningEfforts,
            providerNames = providerNames,
            onSetEffort = onSetEffort,
            onSelectModel = onSelectModel,
            onToggleFavourite = onToggleFavourite,
            onConfigureProvider = {
                onConfigureProvider(it)
                close()
            },
            onDone = close,
        )
    }
    pendingPermission?.let { request ->
        PermissionDialog(request, onApprove = { onResolvePermission(true) }, onDeny = { onResolvePermission(false) })
    }
    pendingQuestion?.let { request ->
        QuestionDialog(request, onSubmit = onResolveQuestion, onDismiss = { onResolveQuestion(emptyList()) })
    }
    if (reportOpen) {
        AiReportFlow(
            submitting = reportSubmitting,
            submission = reportSubmission,
            onDismiss = onDismissReport,
            onClearResult = onClearReportSubmission,
            onSubmit = onSubmitReport,
        )
    }
}


@Composable
internal fun ContextUsageMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    inputTokens: Long,
    outputTokens: Long,
    contextLimit: Long?,
) {
    MorphingMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        above = false,
        alignEnd = true,
        anchorSize = 48.dp,
        modifier = Modifier.width(280.dp),
    ) {
        ContextPopover(inputTokens, outputTokens, contextLimit)
    }
}

@Composable
internal fun contextUsageColor(fraction: Float): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when {
        fraction < 0.6f -> if (dark) Color(0xFF30D158) else Color(0xFF248A3D)
        fraction < 0.8f -> if (dark) Color(0xFFFFD60A) else Color(0xFFA66F00)
        fraction < 0.9f -> if (dark) Color(0xFFFF9F0A) else Color(0xFFC2410C)
        else -> MaterialTheme.colorScheme.error
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
    submitting: Boolean,
    submission: AiReportSubmission?,
    onDismiss: () -> Unit,
    onClearResult: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    val sent = submission?.accepted == true
    val reference = submission?.reference
    val error = submission?.error
    val reportSuccessFocus = remember { FocusRequester() }
    val dismissReport = { if (!submitting) onDismiss() }
    val backMotion = rememberPredictiveBackMotion(onBack = dismissReport)
    Dialog(
        onDismissRequest = dismissReport,
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
                        "Feedback sent",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.focusRequester(reportSuccessFocus).focusable().semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Thank you. Your feedback will be used to improve Misul Agent's safeguards.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    reference?.let {
                        Spacer(Modifier.height(10.dp))
                        Text("Reference: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.height(20.dp))
                    MisulActionButton(
                        label = "Done",
                        role = ActionRole.PRIMARY,
                        onClick = {
                            onClearResult()
                            onDismiss()
                        },
                    )
                }
            } else {
                ReportReview(
                    category = category,
                    note = note,
                    submitting = submitting,
                    error = error,
                    onCategory = {
                        category = it
                        onClearResult()
                    },
                    onNote = {
                        note = it.take(1000)
                        onClearResult()
                    },
                    onDismiss = dismissReport,
                    onSubmit = {
                        val chosen = category ?: return@ReportReview
                        onClearResult()
                        onSubmit(chosen, note)
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
            MisulIconButton(Icons.Filled.Close, "Cancel report", enabled = !submitting, onClick = onDismiss)
            Text(
                "Send safety feedback",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBackground,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            MisulActionButton(
                label = if (submitting) "Sending…" else "Send",
                onClick = onSubmit,
                role = ActionRole.PRIMARY,
                enabled = category != null && !submitting,
                loading = submitting,
                modifier = Modifier.semantics {
                    if (submitting) {
                        contentDescription = "Feedback submission in progress"
                        liveRegion = LiveRegionMode.Polite
                    }
                },
            )
        }
        error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelMedium,
                color = colors.error,
                modifier = Modifier.fillMaxWidth()
                    .semantics {
                        this.error(message)
                        liveRegion = LiveRegionMode.Polite
                    }
                    .padding(vertical = 8.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().contentVerticalScroll(rememberScrollState())
                .padding(top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Choose what went wrong. Misul Agent sends only this category, your optional note, and basic app information.",
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
                        enabled = !submitting,
                        onClick = { onCategory(option.id) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What happened? (optional)", style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                BasicTextField(
                    value = note,
                    onValueChange = onNote,
                    enabled = !submitting,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onBackground),
                    cursorBrush = SolidColor(LocalMisulAccent.current),
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
                "The response, prompt, files, credentials, tool activity, chat history, and device identifiers are never attached.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.tertiary,
            )
        }
    }
}

@Composable
private fun ReportChoice(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
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
            .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp),
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
private fun ModelSheet(
    models: List<ModelOption>,
    selectedModel: ModelOption?,
    selectedEffort: ReasoningEffort,
    disabledProviders: Set<String>,
    hiddenModels: Set<String>,
    favourites: Set<String>,
    codexConnected: Boolean,
    providerConfigured: (String) -> Boolean,
    reasoningEfforts: (ModelOption?) -> List<ReasoningEffort>,
    providerNames: () -> Map<String, String>,
    onSetEffort: (ReasoningEffort) -> Unit,
    onSelectModel: (ModelOption) -> Unit,
    onToggleFavourite: (ModelOption) -> Unit,
    onConfigureProvider: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val configuredSelection = selectedModel?.takeIf { providerConfigured(it.providerId) }
    val availableReasoningEfforts = reasoningEfforts(configuredSelection)
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
                Text("Reasoning", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    if (availableReasoningEfforts.isEmpty()) "Not available" else selectedEffort.display(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.tertiary,
                )
            }
            if (availableReasoningEfforts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableReasoningEfforts.forEach { effort ->
                        val selected = selectedEffort == effort
                        Box(
                            Modifier.heightIn(min = Spacing.touchTarget).clip(ShapePill)
                                .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                                .semantics {
                                    this.selected = selected
                                    role = Role.RadioButton
                                }
                                .clickable { onSetEffort(effort) }
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
                    cursorBrush = SolidColor(LocalMisulAccent.current), singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search models" },
                )
            }
        }
        val keyOf: (ModelOption) -> String = { "${it.providerId}/${it.modelId}" }
        val visible = models.filter {
            it.providerId !in disabledProviders && keyOf(it) !in hiddenModels &&
                (it.providerId != "codex" || codexConnected) &&
                (query.isBlank() || it.label.contains(query, ignoreCase = true) || it.modelId.contains(query, ignoreCase = true))
        }
        val grouped = visible.groupBy { it.providerId }
        val names = remember(models) { providerNames() }
        val favouriteModels = visible.filter { keyOf(it) in favourites }
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
            if (favouriteModels.isNotEmpty()) {
                item("favourites-header") {
                    Text(
                        "Favourites",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(favouriteModels, key = { "favourite:${keyOf(it)}" }) { option ->
                    val ready = providerConfigured(option.providerId)
                    ModelRow(
                        option = option,
                        selected = ready && option == selectedModel,
                        isFav = true,
                        ready = ready,
                        onSelect = { onSelectModel(option) },
                        onSetup = { onConfigureProvider(option.providerId) },
                        onToggleFav = { onToggleFavourite(option) },
                    )
                }
            }
            grouped.forEach { (pid, options) ->
                val ready = providerConfigured(pid)
                item("provider:$pid") {
                    Text(
                        (names[pid] ?: pid) + if (ready) "" else " · Setup required",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ready) colors.onSurfaceVariant else colors.error,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(options, key = { "model:${keyOf(it)}" }) { option ->
                    ModelRow(
                        option = option,
                        selected = ready && option == selectedModel,
                        isFav = keyOf(option) in favourites,
                        ready = ready,
                        onSelect = { onSelectModel(option) },
                        onSetup = { onConfigureProvider(option.providerId) },
                        onToggleFav = { onToggleFavourite(option) },
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
    custom.length <= QUESTION_CUSTOM_ANSWER_MAX_CHARS &&
        (selected.isNotEmpty() xor custom.isNotBlank())

private const val QUESTION_CUSTOM_ANSWER_MAX_CHARS = 4_000
private const val CUSTOM_ANSWER_PREFIX = "Custom: "

@Composable
private fun ContextPopover(inputTokens: Long, outputTokens: Long, contextLimit: Long?) {
    val colors = MaterialTheme.colorScheme
    val used = inputTokens + outputTokens
    val limit = contextLimit
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
        UsageRow("Input", fmt(inputTokens), colors.onBackground)
        UsageRow("Output", fmt(outputTokens), colors.secondary)
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
    fullScreen: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 3f)
    }
    if (fullScreen) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = colors.background) {
                Column(
                    modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Spacing.m),
                    content = content,
                )
            }
        }
    } else Dialog(onDismissRequest = onDismiss) {
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
private fun androidx.compose.foundation.layout.RowScope.DialogAction(
    text: String,
    emphasized: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = MisulDialogAction(label = text, onClick = onClick, primary = emphasized, enabled = enabled)

@Composable
private fun PermissionDialog(request: PermissionRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val presentation = remember(request.tool) { approvalPresentation(request.tool) }
    var submitted by rememberSaveable(request) { mutableStateOf(false) }
    var detailsPage by rememberSaveable(request) { mutableIntStateOf(0) }
    val fullDetails = request.summary.ifBlank { "No additional details were provided." }
    val detailsPageCount = ((fullDetails.length + APPROVAL_DETAILS_PAGE_CHARS - 1) /
        APPROVAL_DETAILS_PAGE_CHARS).coerceAtLeast(1)
    val pageStart = detailsPage.coerceIn(0, detailsPageCount - 1) * APPROVAL_DETAILS_PAGE_CHARS
    val detailsSlice = fullDetails.substring(
        pageStart,
        (pageStart + APPROVAL_DETAILS_PAGE_CHARS).coerceAtMost(fullDetails.length),
    )
    val visibleDetails = buildString {
        if (detailsPage > 0) append("… continued from previous section …\n")
        append(detailsSlice)
        if (detailsPage < detailsPageCount - 1) append("\n… continued in next section …")
    }
    fun resolve(decision: () -> Unit) {
        if (submitted) return
        submitted = true
        decision()
    }
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 32.dp)
            .coerceAtLeast(Spacing.touchTarget * 3f)
    }
    PcSheet(onDismiss = { resolve(onDeny) }) { _ ->
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                .semantics { isTraversalGroup = true },
        ) {
        Column(
            Modifier.weight(1f, fill = false)
                .contentVerticalScroll(rememberScrollState())
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = -1f
                },
        ) {
            Text(
                "Approve agent action?",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                modifier = Modifier.testTag("approval-intro").semantics {
                    heading()
                    traversalIndex = 0f
                },
            )
            Text(
                "Review this action before it runs.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(Spacing.m))
            Text("Action", style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.s))
            Column(
                Modifier.fillMaxWidth()
                    .testTag("approval-risk")
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 1f
                    }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    presentation.risk,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onBackground,
                )
                Text(
                    presentation.guidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(Spacing.s))
            Column(
                Modifier.fillMaxWidth()
                    .testTag("approval-details")
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 2f
                    },
            ) {
                Text(
                    "Details",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                )
                if (detailsPageCount > 1) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Section ${detailsPage + 1} of $detailsPageCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        if (fullDetails.length <= APPROVAL_CLIPBOARD_CHARS) {
                                            fullDetails
                                        } else {
                                            detailsSlice
                                        },
                                    ),
                                )
                            },
                        ) {
                            Text(
                                if (fullDetails.length <= APPROVAL_CLIPBOARD_CHARS) {
                                    "Copy full details"
                                } else {
                                    "Copy this section"
                                },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            enabled = detailsPage > 0,
                            onClick = { detailsPage-- },
                        ) {
                            Text("Previous section")
                        }
                        TextButton(
                            enabled = detailsPage < detailsPageCount - 1,
                            onClick = { detailsPage++ },
                        ) {
                            Text("Next section")
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = 5.dp)
                        .heightIn(min = Spacing.touchTarget)
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.surface)
                        .padding(Spacing.s),
                ) {
                    SelectionContainer {
                        Text(
                            visibleDetails,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PcMono),
                            color = colors.onBackground,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(
            Modifier.fillMaxWidth()
                .testTag("approval-actions")
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = 3f
                },
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            MisulTextAction(
                label = "Deny",
                destructive = true,
                enabled = !submitted,
                onClick = { resolve(onDeny) },
            )
            Spacer(Modifier.weight(1f))
            MisulActionButton(
                label = "Approve once",
                role = ActionRole.PRIMARY,
                enabled = !submitted,
                onClick = { resolve(onApprove) },
            )
        }
        }
    }
}

private const val APPROVAL_DETAILS_PAGE_CHARS = 2_000
private const val APPROVAL_CLIPBOARD_CHARS = 64_000

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
                guidance = "This reads the exact file or folder path shown above. Misul Agent always asks for this access.",
            )
        normalized.startsWith("mcp_") ->
            ApprovalPresentation(
                action = "Run an MCP server action",
                risk = "Connected service change",
                guidance = "This enabled MCP server may send data to or change an external service.",
            )
        normalized == "bash" || normalized.contains("shell") ||
            normalized.contains("terminal") || normalized.contains("process") ->
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
                guidance = "Only approve actions that match what you asked Misul Agent to do.",
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
    PcDialog(onDismiss, fullScreen = request.questions.size > 1) {
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
            modifier = Modifier.weight(1f),
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
                                customAnswers[index].value = ""
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
                MisulField(
                    customAnswers[index].value,
                    {
                        val bounded = it.take(QUESTION_CUSTOM_ANSWER_MAX_CHARS)
                        customAnswers[index].value = bounded
                        if (bounded.isNotBlank()) selections[index].clear()
                    },
                    "Something else",
                )
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
                    if (custom.isNotEmpty()) chosen.add(CUSTOM_ANSWER_PREFIX + custom)
                    UserAnswer(question.question, chosen)
                })
                }
            }
        }
    }
}
