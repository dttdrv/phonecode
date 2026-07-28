package dev.phonecode.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

internal enum class MdAlign { Start, Center, End }

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, val aligns: List<MdAlign>) : MdBlock
    data object Divider : MdBlock
}

private val BULLET = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^\s{0,3}(\d{1,3})[.)]\s+(.*)$""")
private val HEADING = Regex("""^(#{1,4})\s+(.*)$""")
private val DIVIDER = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
// A GFM table separator row: pipes plus dash runs with optional alignment colons, e.g. | :-- | --: |.
private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")
// A GFM task-list item body: `[ ]`/`[x]` then the text (the bullet marker is already stripped).
private val TASK = Regex("""^\[([ xX])]\s+(.*)$""")

private fun isTableRow(line: String): Boolean = line.contains('|') && line.isNotBlank()

/** Split a `| a | b |` row into trimmed cells, dropping the empty edges from leading/trailing pipes. */
private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun parseAligns(sep: String): List<MdAlign> =
    splitTableRow(sep).map {
        val l = it.startsWith(':'); val r = it.endsWith(':')
        when { l && r -> MdAlign.Center; r -> MdAlign.End; else -> MdAlign.Start }
    }

internal fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // A table is a `| ... |` header row whose NEXT line is a separator; consume rows until one isn't.
        if (isTableRow(line) && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
            flush()
            val header = splitTableRow(line)
            val aligns = parseAligns(lines[i + 1])
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && isTableRow(lines[i]) && !TABLE_SEP.matches(lines[i])) {
                rows += splitTableRow(lines[i]); i++
            }
            blocks += MdBlock.Table(header, rows, aligns)
            continue
        }
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
        i++
    }
    flush()
    return blocks
}

/**
 * Keeps blank-line-delimited blocks immutable while an append-only response streams. Only the
 * unfinished tail is parsed for each token; edits or rewinds reset the cache conservatively.
 */
internal class AppendOnlyMarkdownParser {
    private val settled = mutableListOf<MdBlock>()
    private var previous = ""
    private var settledThrough = 0

    internal val settledCharacterCount: Int get() = settledThrough

    fun update(text: String): List<MdBlock> {
        if (!text.startsWith(previous)) reset()

        val lastBlankLine = text.lastIndexOf("\n\n")
        val stableEnd = if (lastBlankLine >= 0) lastBlankLine + 2 else 0
        if (stableEnd > settledThrough) {
            settled += parseBlocks(text.substring(settledThrough, stableEnd))
            settledThrough = stableEnd
        }
        previous = text

        val tail = text.substring(settledThrough)
        return if (tail.isEmpty()) settled.toList() else settled + parseBlocks(tail)
    }

    private fun reset() {
        settled.clear()
        previous = ""
        settledThrough = 0
    }
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
            c == '~' && i + 1 < n && s[i + 1] == '~' -> {
                val close = s.indexOf("~~", i + 2)
                if (close > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(s.substring(i + 2, close), styles) }
                    i = close + 2
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
    val streamingParser = remember { AppendOnlyMarkdownParser() }
    val blocks = remember(text, streaming) {
        if (streaming) streamingParser.update(text) else parseBlocks(text)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEachIndexed { index, block ->
            val tail = if (index == blocks.lastIndex) caret else ""
            when (block) {
                is MdBlock.Paragraph ->
                    Text(blockInline(block.text, tail, styles), style = style, color = color)
                is MdBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        blockInline(block.text, tail, styles),
                        style = headingStyle,
                        color = color,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 5.dp).semantics { heading() },
                    )
                }
                is MdBlock.Bullet -> {
                    // GFM task list: a leading [ ] / [x] renders a checkbox glyph instead of the bullet dot.
                    val task = TASK.find(block.text)
                    val marker = if (task != null) (if (task.groupValues[1].lowercase() == "x") "☑" else "☐") else "•"
                    val body = task?.groupValues?.get(2) ?: block.text
                    Row(Modifier.padding(start = 4.dp)) {
                        Text(marker, style = style, color = colors.secondary, modifier = Modifier.width(16.dp))
                        Text(blockInline(body, tail, styles), style = style, color = color)
                    }
                }
                is MdBlock.Numbered -> Row(Modifier.padding(start = 4.dp)) {
                    Text("${block.number}.", style = style, color = colors.secondary, modifier = Modifier.width(22.dp))
                    Text(blockInline(block.text, tail, styles), style = style, color = color)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colors.outlineVariant))
                    Text(
                        blockInline(block.text, tail, styles),
                        style = style,
                        color = colors.secondary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Table -> TableBlock(block, styles, style)
                MdBlock.Divider -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(colors.outlineVariant),
                )
            }
        }
        if (blocks.isEmpty() && caret.isNotEmpty()) Text(caret, style = style, color = colors.secondary)
    }
}

/** A GFM table: bordered, equal-weight columns (cells wrap on a narrow phone), bold header, per-column align. */
@Composable
private fun TableBlock(table: MdBlock.Table, styles: MdStyles, baseStyle: TextStyle) {
    val colors = MaterialTheme.colorScheme
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    fun textAlign(i: Int) = when (table.aligns.getOrElse(i) { MdAlign.Start }) {
        MdAlign.Center -> TextAlign.Center
        MdAlign.End -> TextAlign.End
        MdAlign.Start -> TextAlign.Start
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxOf(112.dp, maxWidth / cols)
        Box(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small),
        ) {
            Column(Modifier.width(cellWidth * cols)) {
                Row(Modifier.background(colors.surfaceContainerHigh)) {
                    for (i in 0 until cols) {
                        TableCell(
                            table.header.getOrElse(i) { "" },
                            styles,
                            baseStyle.copy(fontWeight = FontWeight.SemiBold),
                            textAlign(i),
                            Modifier.width(cellWidth),
                        )
                    }
                }
                table.rows.forEach { row ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                    Row {
                        for (i in 0 until cols) {
                            TableCell(
                                row.getOrElse(i) { "" },
                                styles,
                                baseStyle,
                                textAlign(i),
                                Modifier.width(cellWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, styles: MdStyles, style: TextStyle, align: TextAlign, modifier: Modifier) {
    Text(
        inlineMarkdown(text, styles),
        style = style,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = align,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun blockInline(text: String, tail: String, styles: MdStyles): AnnotatedString =
    remember(text, tail, styles) { inlineMarkdown(text + tail, styles) }
