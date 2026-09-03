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
import androidx.compose.material3.HorizontalDivider
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

private fun formatCompletionDate(value: Long) = SimpleDateFormat("HH:mm · d MMM", Locale.getDefault()).format(Date(value))

@Composable
internal fun UserBubble(text: String, images: List<MessagePart.Image>) {
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
internal fun AssistantTurn(
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

    // Keep the live state legible without scheduling continuous decorative animation.
    Column(Modifier.fillMaxWidth()) {
        if (reasoning != null) {
            // "Thinking" row: compact disclosure for the reasoning trace.
            Row(
                Modifier.clip(MaterialTheme.shapes.extraSmall).heightIn(min = Spacing.touchTarget)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .clickable { open = !open }.padding(vertical = 3.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                ThinkingDot(active = streaming, open = open)
                if (!open) {
                    val isThinking = streaming && text.isEmpty()
                    Text(
                        if (isThinking) "Thinking" else "Done",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isThinking) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isThinking) LocalMisulAccent.current else colors.tertiary,
                    )
                }
            }
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(PhoneTweens.popEnter),
                exit = fadeOut(PhoneTweens.popExit),
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
            val fenceParser = remember { AppendOnlyFenceParser() }
            val segments = remember(text, streaming) {
                if (streaming) fenceParser.update(text) else splitFenced(text)
            }
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
                if (showReport) ActionIcon(Icons.Outlined.Flag, "Send safety feedback", onReport)
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



@Composable
private fun ThinkingDot(active: Boolean, open: Boolean) {
    val colors = MaterialTheme.colorScheme
    val dotColor = if (active) LocalMisulAccent.current else if (open) colors.secondary else colors.tertiary
    Box(
        Modifier.size(8.dp).clip(ShapePill).background(dotColor),
    )
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    MisulIconButton(icon, desc, onClick = onClick)
}

internal data class Seg(val text: String, val isCode: Boolean, val lang: String)

/**
 * Streaming fence parser that commits complete lines once. Token updates only rebuild the active
 * tail segment instead of splitting and rescanning the whole response.
 */
internal class AppendOnlyFenceParser {
    private val settled = mutableListOf<Seg>()
    private val active = StringBuilder()
    private var previous = ""
    private var committedThrough = 0
    private var inCode = false
    private var language = ""

    internal val settledCharacterCount: Int get() = committedThrough

    fun update(input: String): List<Seg> {
        if (!input.startsWith(previous)) reset()

        var newline = input.indexOf('\n', committedThrough)
        while (newline >= 0) {
            commitLine(input.substring(committedThrough, newline))
            committedThrough = newline + 1
            newline = input.indexOf('\n', committedThrough)
        }
        previous = input

        val result = settled.toMutableList()
        val tail = buildString {
            append(active)
            append(input, committedThrough, input.length)
        }
        if (inCode || tail.isNotBlank()) result += Seg(tail, inCode, language)
        return result
    }

    private fun commitLine(line: String) {
        if (line.trimStart().startsWith("```")) {
            flush()
            if (inCode) {
                inCode = false
                language = ""
            } else {
                inCode = true
                language = line.trimStart().removePrefix("```").trim()
            }
        } else {
            active.append(line).append('\n')
        }
    }

    private fun flush() {
        val text = active.toString().removeSuffix("\n")
        if (inCode || text.isNotBlank()) settled += Seg(text, inCode, language)
        active.clear()
    }

    private fun reset() {
        settled.clear()
        active.clear()
        previous = ""
        committedThrough = 0
        inCode = false
        language = ""
    }
}

internal fun splitFenced(input: String): List<Seg> {
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
internal fun ToolActivityView(line: ChatLine.ToolActivity) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val error = line.status == ToolStatus.ERROR
    val running = line.status == ToolStatus.RUNNING
    val accent = LocalMisulAccent.current
    var detailsOpen by remember(line.id) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val statusLabel = when (line.status) {
        ToolStatus.AWAITING_APPROVAL -> "Awaiting approval"
        ToolStatus.RUNNING -> "Running"
        ToolStatus.DONE -> "Done"
        ToolStatus.ERROR -> "Failed"
        ToolStatus.STOPPED -> "Stopped"
    }
    Column(
        Modifier.fillMaxWidth().pressFeedback(interaction, pressedScale = 0.99f)
            .heightIn(min = Spacing.touchTarget)
            .semantics {
                contentDescription = "${toolAction(line.name, line.status)}, $statusLabel"
                stateDescription = statusLabel
                liveRegion = LiveRegionMode.Polite
            }
            .clickable(interactionSource = interaction, indication = ripple()) { detailsOpen = true },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Spacer(
                Modifier.width(1.dp).height(30.dp).background(
                    when {
                        error -> colors.error
                        running || line.status == ToolStatus.AWAITING_APPROVAL -> accent
                        else -> colors.outlineVariant
                    },
                ),
            )
            Icon(
                toolIcon(line.name), null,
                tint = when {
                    error -> colors.error
                    running || line.status == ToolStatus.AWAITING_APPROVAL -> accent
                    else -> colors.secondary
                },
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    toolAction(line.name, line.status),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (error) colors.error else colors.onSurface,
                )
                if (line.detail.isNotBlank()) {
                    Text(
                        line.detail.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (line.status != ToolStatus.DONE) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (error) colors.error else colors.tertiary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                "Open tool details",
                tint = colors.tertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (detailsOpen) ToolDetailsSheet(
        line = line,
        running = running,
        action = toolAction(line.name, line.status),
        onDismiss = { detailsOpen = false },
    )
}

private fun toolAction(name: String, status: ToolStatus): String {
    val active = status == ToolStatus.RUNNING
    val awaitingApproval = status == ToolStatus.AWAITING_APPROVAL
    if (status == ToolStatus.ERROR) {
        return when {
            name == "read" -> "Read failed"
            name == "write" -> "Write failed"
            name == "edit" || name == "apply_patch" -> "Edit failed"
            name == "ls" || name == "glob" -> "File browsing failed"
            name == "grep" -> "Code search failed"
            name == "bash" -> "Command failed"
            name == "websearch" -> "Web search failed"
            name == "webfetch" -> "Webpage failed to open"
            name.startsWith("git_") -> "Git operation failed"
            name == "question" -> "Question failed"
            name == "task" -> "Delegated task failed"
            name == "skill" -> "Skill failed to load"
            name.startsWith("todo") -> "Task update failed"
            else -> "${name.replace('_', ' ').replaceFirstChar { it.uppercase() }} failed"
        }
    }
    if (status == ToolStatus.STOPPED) {
        return when {
            name == "read" -> "Read stopped"
            name == "write" -> "Write stopped"
            name == "edit" || name == "apply_patch" -> "Edit stopped"
            name == "ls" || name == "glob" -> "File browsing stopped"
            name == "grep" -> "Code search stopped"
            name == "bash" -> "Command stopped"
            name == "websearch" -> "Web search stopped"
            name == "webfetch" -> "Webpage opening stopped"
            name.startsWith("git_") -> "Git operation stopped"
            name == "question" -> "Question stopped"
            name == "task" -> "Delegated task stopped"
            name == "skill" -> "Skill loading stopped"
            name.startsWith("todo") -> "Task update stopped"
            else -> "${name.replace('_', ' ').replaceFirstChar { it.uppercase() }} stopped"
        }
    }
    if (awaitingApproval) {
        return when {
            name == "write" -> "Waiting to write file"
            name == "edit" || name == "apply_patch" -> "Waiting to edit code"
            name == "bash" -> "Waiting to run command"
            name.startsWith("git_") -> "Waiting to run Git"
            else -> "Waiting to run ${name.replace('_', ' ')}"
        }
    }
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
internal fun PhotoThumbnail(image: MessagePart.Image, modifier: Modifier = Modifier) {
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



/**
 * Timeline-only routing. It deliberately receives callbacks from [ChatScreen] instead of retaining
 * a view model so a turn cannot duplicate session, streaming, or report state.
 */
@Composable
internal fun ChatTurn(
    line: ChatLine,
    reasoning: String?,
    isLatestAssistant: Boolean,
    isRunning: Boolean,
    turnOutcome: TurnOutcome?,
    completedAt: Long?,
    onRedo: () -> Unit,
    onReport: () -> Unit,
) {
    when (line) {
        is ChatLine.User -> UserBubble(line.text, line.images)
        is ChatLine.Assistant -> AssistantTurn(
            text = line.text,
            reasoning = reasoning,
            streaming = false,
            showActions = isLatestAssistant && !isRunning && turnOutcome == null,
            showReport = !isRunning,
            completedAt = completedAt,
            onCopy = {},
            onRedo = onRedo,
            onReport = onReport,
            copyText = line.text,
        )
        is ChatLine.Reasoning -> AssistantTurn(
            text = "",
            reasoning = line.text,
            streaming = false,
            showActions = false,
            showReport = !isRunning,
            completedAt = null,
            onCopy = {},
            onRedo = {},
            onReport = onReport,
            copyText = "",
        )
        is ChatLine.ToolActivity -> ToolActivityView(line)
    }
}
