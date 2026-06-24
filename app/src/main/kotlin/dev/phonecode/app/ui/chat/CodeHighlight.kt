package dev.phonecode.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Monochrome syntax tones derived from the theme's ink colors (no hues - weight + gray tiers). */
data class CodeTones(
    val keyword: SpanStyle,
    val string: SpanStyle,
    val comment: SpanStyle,
    val number: SpanStyle,
    val call: SpanStyle,
) {
    companion object {
        /** Build tones from the current ink colors: keywords pop via weight, comments recede. */
        fun monochrome(ink: Color, ink2: Color, ink3: Color) = CodeTones(
            keyword = SpanStyle(color = ink, fontWeight = FontWeight.Bold),
            string = SpanStyle(color = ink2),
            comment = SpanStyle(color = ink3, fontStyle = FontStyle.Italic),
            number = SpanStyle(color = ink2),
            call = SpanStyle(color = ink, fontWeight = FontWeight.Medium),
        )
    }
}

// A small, language-agnostic lexer covering the common surface of Kotlin/JS/TS/Python/Rust/Go/Java/etc.
private val KEYWORDS = setOf(
    "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "class", "object", "interface",
    "import", "package", "private", "public", "internal", "protected", "override", "open", "abstract", "suspend",
    "data", "sealed", "enum", "companion", "init", "constructor", "throw", "try", "catch", "finally", "is", "as",
    "in", "out", "by", "this", "super", "null", "true", "false", "function", "const", "let", "async", "await",
    "new", "void", "static", "final", "extends", "implements", "def", "lambda", "from", "export", "default",
    "type", "struct", "impl", "trait", "pub", "use", "mod", "match", "fn", "func", "go", "defer", "select",
    "with", "yield", "raise", "elif", "not", "and", "or", "None", "True", "False", "Some", "Ok", "Err",
)

/** Highlight [code] into styled spans (keywords/strings/comments/numbers/calls); default text unstyled. */
fun highlightCode(code: String, tones: CodeTones): AnnotatedString = buildAnnotatedString {
    val n = code.length
    var i = 0
    fun span(text: String, style: SpanStyle) = withStyle(style) { append(text) }
    while (i < n) {
        val c = code[i]
        when {
            c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                span(code.substring(i, end), tones.comment); i = end
            }
            // '#' starts a comment only at line start - avoids mangling C/Kotlin '#' uses mid-line.
            c == '#' && (i == 0 || code[i - 1] == '\n') -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                span(code.substring(i, end), tones.comment); i = end
            }
            c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                val end = code.indexOf("*/", i).let { if (it < 0) n else it + 2 }
                span(code.substring(i, end), tones.comment); i = end
            }
            c == '"' || c == '\'' || c == '`' -> {
                var j = i + 1
                while (j < n && code[j] != c) { if (code[j] == '\\') j++; j++ }
                j = minOf(j + 1, n)
                span(code.substring(i, j), tones.string); i = j
            }
            c.isDigit() -> {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                span(code.substring(i, j), tones.number); i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                var k = j
                while (k < n && code[k] == ' ') k++
                val isCall = k < n && code[k] == '('
                when {
                    word in KEYWORDS -> span(word, tones.keyword)
                    isCall -> span(word, tones.call)
                    else -> append(word)
                }
                i = j
            }
            else -> { append(c); i++ }
        }
    }
}
