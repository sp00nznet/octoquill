package net.sp00nz.octoquill

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

private val INLINE = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|(?<![*\w])\*([^*]+?)\*(?!\*)|(?<![_\w])_([^_]+?)_(?!\w)|`([^`]+)`|~~(.+?)~~|\[([^\]]+)]\(([^)]*)\)"""
)

private val MONO = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

private fun AnnotatedString.Builder.inline(text: String, base: SpanStyle) {
    var i = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > i) withStyleOf(base) { append(text.substring(i, m.range.first)) }
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyleOf(base.copy(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyleOf(base.copy(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyleOf(base.copy(fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyleOf(base.copy(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyleOf(MONO) { append(g[5]) }
            g[6].isNotEmpty() -> withStyleOf(base.copy(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }
            g[7].isNotEmpty() -> withStyleOf(base.copy(textDecoration = TextDecoration.Underline)) { append(g[7]) }
            else -> withStyleOf(base) { append(m.value) }
        }
        i = m.range.last + 1
    }
    if (i < text.length) withStyleOf(base) { append(text.substring(i)) }
}

private inline fun AnnotatedString.Builder.withStyleOf(s: SpanStyle, block: () -> Unit) {
    pushStyle(s); block(); pop()
}

private fun headingStyle(level: Int, accent: Color) = SpanStyle(
    fontSize = when (level) {
        1 -> 24.sp
        2 -> 20.sp
        3 -> 17.sp
        else -> 15.sp
    }.let { it },
    fontWeight = FontWeight.Bold,
    color = accent,
)

/**
 * ponytail: a deliberately small markdown subset — headings, emphasis, code, quotes,
 * lists, rules, tables-as-monospace. Enough to read your own prose back. A real parser
 * (CommonMark) is a dependency and a lot of surface for a preview pane.
 */
fun renderMarkdown(src: String, body: Color, accent: Color, faint: Color): AnnotatedString =
    buildAnnotatedString {
        val base = SpanStyle(color = body)
        var fenced = false

        for (raw in src.split("\n")) {
            val line = raw.trimEnd()
            val t = line.trimStart()

            if (t.startsWith("```") || t.startsWith("~~~")) {
                fenced = !fenced
                append("\n")
                continue
            }
            if (fenced) {
                withStyleOf(MONO.copy(color = faint)) { append(line) }
                append("\n")
                continue
            }

            when {
                t.isEmpty() -> append("\n")

                t.startsWith("#") && t.takeWhile { it == '#' }.length <= 6 &&
                    t.length > t.takeWhile { it == '#' }.length -> {
                    val level = t.takeWhile { it == '#' }.length
                    append("\n")
                    inline(t.drop(level).trim(), headingStyle(level, accent))
                    append("\n")
                }

                t.startsWith("> ") -> {
                    pushStyle(ParagraphStyle())
                    withStyleOf(base.copy(fontStyle = FontStyle.Italic, color = faint)) {
                        append("  ")
                        inline(t.removePrefix("> "), base.copy(fontStyle = FontStyle.Italic, color = faint))
                    }
                    pop()
                    append("\n")
                }

                t == "---" || t == "***" || t == "___" -> {
                    withStyleOf(base.copy(color = faint)) { append("────────────") }
                    append("\n")
                }

                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                    val indent = " ".repeat((line.length - t.length).coerceAtMost(8))
                    withStyleOf(base) { append("$indent  •  ") }
                    inline(t.drop(2), base)
                    append("\n")
                }

                Regex("""^\d+\.\s""").containsMatchIn(t) -> {
                    val marker = t.takeWhile { it != ' ' }
                    withStyleOf(base.copy(color = faint)) { append("  $marker  ") }
                    inline(t.removePrefix(marker).trim(), base)
                    append("\n")
                }

                // tables read fine as monospace and are common in notes
                t.startsWith("|") -> {
                    withStyleOf(MONO.copy(color = faint)) { append(line) }
                    append("\n")
                }

                else -> {
                    inline(line, base)
                    append("\n")
                }
            }
        }
    }
