package com.offlinestudy.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinestudy.ai.ui.theme.Palette
import com.offlinestudy.ai.util.LatexToUnicode

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Numbered(val items: List<Pair<Int, String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Formula(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

/** Офлайн-рендерер Markdown для ответов модели (как в iOS-версии). */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block -> BlockView(block, color) }
        }
    }
}

@Composable
private fun BlockView(block: MdBlock, color: Color) {
    val body = 16.sp
    when (block) {
        is MdBlock.Heading -> Text(
            MarkdownParser.inline(block.text), color = color, fontWeight = FontWeight.Bold,
            fontSize = when (block.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp }
        )
        is MdBlock.Paragraph -> Text(MarkdownParser.inline(block.text), color = color, fontSize = body, lineHeight = 22.sp)
        is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row {
                    Box(Modifier.padding(top = 8.dp, end = 8.dp).size(6.dp).clip(CircleShape).background(Palette.Violet))
                    Text(MarkdownParser.inline(item), color = color, fontSize = body, lineHeight = 22.sp)
                }
            }
        }
        is MdBlock.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { (n, item) ->
                Row {
                    Text("$n.", color = Palette.Violet, fontWeight = FontWeight.Bold, fontSize = body, modifier = Modifier.width(26.dp))
                    Text(MarkdownParser.inline(item), color = color, fontSize = body, lineHeight = 22.sp)
                }
            }
        }
        is MdBlock.Quote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Palette.Teal, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(MarkdownParser.inline(block.text), color = color.copy(alpha = 0.75f), fontStyle = FontStyle.Italic, fontSize = body)
        }
        is MdBlock.Code -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.07f))
                .horizontalScroll(rememberScrollState()).padding(12.dp)
        ) { Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = color) }
        is MdBlock.Formula -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Indigo.copy(alpha = 0.1f))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) { Text(LatexToUnicode.convert(block.text), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 19.sp, color = color) }
        is MdBlock.Table -> Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.05f))
                .horizontalScroll(rememberScrollState()).padding(12.dp)
        ) {
            block.rows.forEachIndexed { index, row ->
                Row {
                    row.forEach { cell ->
                        Text(MarkdownParser.inline(cell), color = color, fontSize = 14.sp,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(130.dp).padding(end = 10.dp, bottom = 4.dp))
                    }
                }
                if (index == 0) HorizontalDivider()
            }
        }
        MdBlock.Divider -> HorizontalDivider()
    }
}

object MarkdownParser {
    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = source.replace("\r\n", "\n").split("\n")
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) { blocks += MdBlock.Paragraph(paragraph.joinToString("\n")); paragraph.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            when {
                line.isEmpty() -> { flush(); i++ }
                line.startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                    i++
                    blocks += MdBlock.Code(code.joinToString("\n"))
                }
                line.startsWith("$$") || line.startsWith("\\[") -> {
                    flush()
                    val closing = if (line.startsWith("$$")) "$$" else "\\]"
                    val body = line.drop(2)
                    val close = body.indexOf(closing)
                    if (close >= 0) {
                        blocks += MdBlock.Formula(body.substring(0, close))
                        val after = body.substring(close + closing.length).trim()
                        if (after.isNotEmpty()) paragraph += after
                        i++
                    } else {
                        val parts = mutableListOf(body)
                        i++
                        while (i < lines.size) {
                            val next = lines[i]
                            val idx = next.indexOf(closing)
                            i++
                            if (idx >= 0) { parts += next.substring(0, idx); break }
                            parts += next
                        }
                        blocks += MdBlock.Formula(parts.joinToString(" "))
                    }
                }
                line == "---" || line == "***" || line == "___" -> { flush(); blocks += MdBlock.Divider; i++ }
                line.startsWith("#") && line.trimStart('#').startsWith(" ") -> {
                    flush()
                    val level = line.takeWhile { it == '#' }.length
                    blocks += MdBlock.Heading(minOf(level, 3), line.drop(level + 1))
                    i++
                }
                line.startsWith("|") -> {
                    flush()
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        val separator = cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }
                        if (!separator) rows += cells
                        i++
                    }
                    blocks += MdBlock.Table(rows)
                }
                bullet(line) != null -> {
                    flush()
                    val items = mutableListOf(bullet(line)!!)
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        val item = bullet(next)
                        if (item != null) { items += item; i++ }
                        else if (next.isNotEmpty() && lines[i].startsWith("  ")) { items[items.lastIndex] = items.last() + " " + next; i++ }
                        else break
                    }
                    blocks += MdBlock.Bullets(items)
                }
                numbered(line) != null -> {
                    flush()
                    val items = mutableListOf(numbered(line)!!)
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        val item = numbered(next)
                        if (item != null) { items += item; i++ }
                        else if (next.isNotEmpty() && lines[i].startsWith("  ")) {
                            val sub = bullet(next)
                            val last = items.last()
                            items[items.lastIndex] = last.first to (last.second + if (sub != null) "\n• $sub" else " $next")
                            i++
                        } else break
                    }
                    blocks += MdBlock.Numbered(items)
                }
                line.startsWith(">") -> {
                    flush()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { quote += lines[i].trim().drop(1).trim(); i++ }
                    blocks += MdBlock.Quote(quote.joinToString("\n"))
                }
                else -> { paragraph += line; i++ }
            }
        }
        flush()
        return blocks
    }

    private fun bullet(line: String): String? {
        for (m in listOf("- ", "* ", "• ", "+ ", "– ")) if (line.startsWith(m)) return line.drop(m.length)
        return null
    }

    private val numberedRegex = Regex("""^(\d{1,3})[.)] (.*)$""")
    private fun numbered(line: String): Pair<Int, String>? =
        numberedRegex.find(line)?.let { it.groupValues[1].toInt() to it.groupValues[2] }

    /** $...$ и \(...\) → Unicode. */
    fun convertInlineFormulas(text: String): String {
        if (!text.contains('$') && !text.contains("\\")) return text
        val sb = StringBuilder()
        var rest = text
        while (rest.isNotEmpty()) {
            val dollar = rest.indexOf('$')
            val paren = rest.indexOf("\\(")
            val start: Int
            val isDollar: Boolean
            when {
                dollar >= 0 && (paren < 0 || dollar < paren) -> { start = dollar; isDollar = true }
                paren >= 0 -> { start = paren; isDollar = false }
                else -> { sb.append(rest); break }
            }
            sb.append(rest, 0, start)
            val openLen = if (isDollar) (if (rest.startsWith("$$", start)) 2 else 1) else 2
            val afterOpen = start + openLen
            val closing = if (isDollar) "$".repeat(openLen) else "\\)"
            val close = rest.indexOf(closing, afterOpen)
            if (close >= 0) {
                val formula = rest.substring(afterOpen, close)
                if (isDollar && formula.isBlank()) sb.append(rest, start, close + closing.length)
                else sb.append(LatexToUnicode.convert(formula))
                rest = rest.substring(close + closing.length)
            } else {
                val tail = rest.substring(minOf(afterOpen, rest.length))
                sb.append(if (tail.contains('\\') || tail.contains('^')) LatexToUnicode.convert(tail) else tail)
                rest = ""
            }
        }
        return sb.toString()
    }

    /** **жирный**, *курсив*, `код`. */
    fun inline(text: String): AnnotatedString {
        val src = convertInlineFormulas(text)
        return buildAnnotatedString {
            var i = 0
            while (i < src.length) {
                when {
                    src.startsWith("**", i) -> {
                        val end = src.indexOf("**", i + 2)
                        if (end > i + 2) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(src.substring(i + 2, end)) }
                            i = end + 2
                        } else { i += 2 } // незакрытые ** (ответ ещё печатается) — просто прячем
                    }
                    src[i] == '`' -> {
                        val end = src.indexOf('`', i + 1)
                        if (end > i) {
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))) { append(src.substring(i + 1, end)) }
                            i = end + 1
                        } else { append('`'); i++ }
                    }
                    (src[i] == '*' || src[i] == '_') && i + 1 < src.length && src[i + 1] != ' ' &&
                        (i == 0 || !src[i - 1].isLetterOrDigit()) -> {
                        val marker = src[i]
                        val end = src.indexOf(marker, i + 1)
                        if (end > i + 1 && src[end - 1] != ' ') {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(src.substring(i + 1, end)) }
                            i = end + 1
                        } else { append(src[i]); i++ }
                    }
                    else -> { append(src[i]); i++ }
                }
            }
        }
    }
}
