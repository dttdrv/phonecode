package dev.phonecode.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.phonecode.app.ui.theme.PcMono

/**
 * Lightweight markdown for assistant prose. Code FENCES are handled upstream (splitFenced ->
 * CodeBlock); this renders everything between them: headings, bullet/numbered lists, quotes,
 * dividers, and inline **bold** / *italic* / `code` / [links](url).
 *
 * Streaming-safe: an unterminated marker (e.g. a lone `**` mid-stream) renders literally instead
 * of styling the rest of the message; the next parse picks it up once the closer arrives.
 */

// ---------- Block model ----------

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

private val BULLET = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^\s{0,3}(\d{1,3})[.)]\s+(.*)$""")
private val HEADING = Regex("""^(#{1,4})\s+(.*)$""")
private val DIVIDER = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }
    text.lines().forEach { line ->
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        val numbered = NUMBERED.find(line)
        when {
            line.isBlank() -> flush()
            DIVIDER.matches(line) -> { flush(); blocks += MdBlock.Divider }
            heading != null -> { flush(); blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]) }
            line.startsWith("> ") -> {
                flush()
                // Merge consecutive quote lines into one block.
                val last = blocks.lastOrNull()
                val content = line.removePrefix("> ")
                if (last is MdBlock.Quote) blocks[blocks.lastIndex] = MdBlock.Quote(last.text + "\n" + content)
                else blocks += MdBlock.Quote(content)
            }
            bullet != null -> { flush(); blocks += MdBlock.Bullet(bullet.groupValues[1]) }
            numbered != null -> { flush(); blocks += MdBlock.Numbered(numbered.groupValues[1], numbered.groupValues[2]) }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    flush()
    return blocks
}

// ---------- Inline parser ----------

/** Style bundle resolved once per theme; keeps the parser free of composition reads. */
data class MdStyles(val code: SpanStyle, val link: TextLinkStyles)

@Composable
fun rememberMdStyles(): MdStyles {
    val colors = MaterialTheme.colorScheme
    return remember(colors) {
        MdStyles(
            code = SpanStyle(
                fontFamily = PcMono,
                fontSize = 0.88.em,
                background = colors.surfaceContainerHigh,
            ),
            link = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)),
        )
    }
}

/** Renders inline markdown into an AnnotatedString. Public for reuse (e.g. drawer previews). */
fun inlineMarkdown(text: String, styles: MdStyles): AnnotatedString = buildAnnotatedString {
    appendInline(text, styles)
}

private fun AnnotatedString.Builder.appendInline(s: String, styles: MdStyles) {
    var i = 0
    val n = s.length
    while (i < n) {
        val c = s[i]
        when {
            c == '`' -> {
                val close = s.indexOf('`', i + 1)
                if (close > i) {
                    withStyle(styles.code) { append(s.substring(i + 1, close)) }
                    i = close + 1
                } else { append(c); i++ }
            }
            c == '*' -> {
                val run = s.runLength(i, '*').coerceAtMost(3)
                val marker = "*".repeat(run)
                val close = s.indexOf(marker, i + run)
                if (close > i && run > 0) {
                    val style = when (run) {
                        1 -> SpanStyle(fontStyle = FontStyle.Italic)
                        2 -> SpanStyle(fontWeight = FontWeight.SemiBold)
                        else -> SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
                    }
                    withStyle(style) { appendInline(s.substring(i + run, close), styles) }
                    i = close + run
                } else { append(marker); i += run }
            }
            c == '_' && i + 1 < n && s[i + 1] != '_' && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                // Single-underscore italic only at a word boundary (avoids snake_case mangling).
                val close = s.indexOf('_', i + 1)
                if (close > i && (close + 1 >= n || !s[close + 1].isLetterOrDigit())) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(s.substring(i + 1, close), styles) }
                    i = close + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                val mid = s.indexOf("](", i + 1)
                val end = if (mid > 0) s.indexOf(')', mid + 2) else -1
                if (mid > 0 && end > mid) {
                    val label = s.substring(i + 1, mid)
                    val url = s.substring(mid + 2, end)
                    val link = LinkAnnotation.Url(url, styles.link)
                    pushLink(link)
                    appendInline(label, styles)
                    pop()
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

private fun String.runLength(from: Int, ch: Char): Int {
    var j = from
    while (j < length && this[j] == ch) j++
    return j - from
}

// ---------- Renderer ----------

/**
 * Renders [text] as markdown blocks. [caret] (the streaming cursor) is appended to the final
 * block so it rides the text instead of dropping to its own line.
 */
@Composable
fun MarkdownBlocks(
    text: String,
    caret: String = "",
    streaming: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val styles = rememberMdStyles()
    val colors = MaterialTheme.colorScheme
    val blocks = remember(text) { parseBlocks(text) }

    // ChatGPT's streaming feel (chatgpt-motion.md): each arriving chunk fades in over ~90ms.
    // Only the GROWING block pulses - settled blocks above it stay rock-still.
    val chunkAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(text.length, streaming) {
        if (streaming) {
            chunkAlpha.snapTo(0.6f)
            chunkAlpha.animateTo(1f, androidx.compose.animation.core.tween(90, easing = androidx.compose.animation.core.LinearEasing))
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEachIndexed { index, block ->
            val tail = if (index == blocks.lastIndex) caret else ""
            val blockModifier = if (streaming && index == blocks.lastIndex) {
                Modifier.graphicsLayer { alpha = chunkAlpha.value }
            } else Modifier
            Box(blockModifier) {
                when (block) {
                is MdBlock.Paragraph ->
                    Text(remember(block, tail, styles) { inlineMarkdown(block.text + tail, styles) }, style = style, color = color)
                is MdBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        remember(block, tail, styles) { inlineMarkdown(block.text + tail, styles) },
                        style = headingStyle,
                        color = color,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 5.dp),
                    )
                }
                is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp)) {
                    Text("•", style = style, color = colors.secondary, modifier = Modifier.width(16.dp))
                    Text(remember(block, tail, styles) { inlineMarkdown(block.text + tail, styles) }, style = style, color = color)
                }
                is MdBlock.Numbered -> Row(Modifier.padding(start = 4.dp)) {
                    Text("${block.number}.", style = style, color = colors.secondary, modifier = Modifier.width(22.dp))
                    Text(remember(block, tail, styles) { inlineMarkdown(block.text + tail, styles) }, style = style, color = color)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colors.outlineVariant))
                    Text(
                        remember(block, tail, styles) { inlineMarkdown(block.text + tail, styles) },
                        style = style,
                        color = colors.secondary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                MdBlock.Divider -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(colors.outlineVariant),
                )
                }
            }
        }
        if (blocks.isEmpty() && caret.isNotEmpty()) Text(caret, style = style, color = colors.secondary)
    }
}
