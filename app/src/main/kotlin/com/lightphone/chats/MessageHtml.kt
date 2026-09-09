package com.lightphone.chats

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * HTML subset → [AnnotatedString] for incoming formatted messages (chats
 * markdown): strong/b → Bold, em/i → Italic, del/strike/s → Strikethrough,
 * u → Underline, code → plain (no mono font in the Light design), br →
 * newline, p/li → line breaks, ul/ol → "• "/"1. " prefixes (one level).
 * h1–h6 render as a BOLD span at distinct sizes stepped around the paragraph
 * size ([paragraphSp] — the caller passes its scaled paragraph size; 0 keeps
 * every heading at the paragraph size): h1 reads as a heading, h6 barely
 * smaller than the body (LP3 feedback 2026-09-09: all six were identical).
 * `a` keeps its text (no clickable links on the LP3, and outgoing links
 * already strip the URL); unknown tags drop the TAG but keep their inner
 * text; script/style drop tag AND content; the named + numeric entities
 * unescape. When the HTML parses to nothing, [fallback] (the event's plain
 * body) renders instead.
 */
fun formattedMessage(html: String, fallback: String, paragraphSp: Float = 0f): AnnotatedString {
    if (html.isBlank()) return AnnotatedString(fallback)
    val parsed = parseHtml(html, paragraphSp)
    return if (parsed.text.isBlank()) AnnotatedString(fallback) else parsed
}

/** Heading size steps, as multiples of the paragraph size. */
private val HEADING_SCALES = mapOf(
    "h1" to 1.55f,
    "h2" to 1.3f,
    "h3" to 1.15f,
    "h4" to 1.0f,
    "h5" to 0.85f,
    "h6" to 0.75f,
)

private fun parseHtml(html: String, paragraphSp: Float): AnnotatedString {
    val text = StringBuilder()
    val spans = mutableListOf<Triple<Int, Int, SpanStyle>>() // start, end, style
    // Open-tag frames: the tag, the style it contributes (null = tag with no
    // visual effect), the text offset it opened at, and the ordered-list
    // counter (lists only).
    data class Frame(val tag: String, val style: SpanStyle?, val startAt: Int, val counter: Int = 0)
    val frames = ArrayDeque<Frame>()
    var dropping: String? = null // inside <script>/<style>: drop content too
    var i = 0

    fun breakLine() {
        if (text.isNotEmpty() && !text.endsWith("\n")) text.append('\n')
    }

    while (i < html.length) {
        val c = html[i]
        if (c != '<') {
            if (dropping == null) {
                if (c == '&') {
                    val semi = html.indexOf(';', i)
                    if (semi != -1 && semi - i <= 10) { // longest: &#65535;
                        text.append(unescapeEntity(html.substring(i, semi + 1)))
                        i = semi + 1
                        continue
                    }
                }
                text.append(c)
            }
            i++
            continue
        }
        // A tag: <name ...>, </name>, <name/> (attributes are skipped).
        val end = html.indexOf('>', i)
        if (end == -1) { // malformed tail — keep it as text
            if (dropping == null) text.append(html.substring(i))
            break
        }
        val rawTag = html.substring(i + 1, end)
        val body = rawTag.trimEnd('/')
        val closing = body.startsWith("/")
        val tag = body.removePrefix("/").trim().substringBefore(' ').lowercase()
        i = end + 1
        when {
            dropping != null -> if (closing && tag == dropping) dropping = null
            closing -> {
                val frameIndex = frames.indexOfLast { it.tag == tag }
                if (frameIndex >= 0) {
                    // Discard unclosed inner frames, then close this one
                    // (stray close tags for frames never opened are ignored).
                    while (frames.size > frameIndex + 1) frames.removeLast()
                    val frame = frames.removeLast()
                    if (frame.style != null && text.length > frame.startAt) {
                        spans.add(Triple(frame.startAt, text.length, frame.style))
                    }
                    when (frame.tag) {
                        "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol" -> breakLine()
                    }
                }
            }
            tag == "br" -> text.append('\n')
            tag == "script" || tag == "style" -> dropping = tag
            tag == "p" -> {
                breakLine()
                frames.addLast(Frame(tag, null, text.length))
            }
            tag == "ul" || tag == "ol" -> {
                breakLine()
                frames.addLast(Frame(tag, null, text.length))
            }
            tag == "li" -> {
                breakLine()
                val listIndex = frames.indexOfLast { it.tag == "ul" || it.tag == "ol" }
                if (listIndex >= 0) {
                    val list = frames[listIndex]
                    frames[listIndex] = list.copy(counter = list.counter + 1)
                    text.append(if (list.tag == "ul") "• " else "${list.counter + 1}. ")
                }
            }
            tag in HEADING_SCALES -> {
                breakLine()
                val size = paragraphSp.takeIf { it > 0f }
                    ?.let { HEADING_SCALES[tag]?.times(it) }
                frames.addLast(
                    Frame(
                        tag,
                        if (size != null) BOLD_STYLE.copy(fontSize = size.sp) else BOLD_STYLE,
                        text.length,
                    ),
                )
            }
            tag == "strong" || tag == "b" -> frames.addLast(Frame(tag, BOLD_STYLE, text.length))
            tag == "em" || tag == "i" -> frames.addLast(Frame(tag, ITALIC_STYLE, text.length))
            tag == "del" || tag == "strike" || tag == "s" -> frames.addLast(Frame(tag, STRIKE_STYLE, text.length))
            tag == "u" -> frames.addLast(Frame(tag, UNDERLINE_STYLE, text.length))
            tag == "code" || tag == "a" -> frames.addLast(Frame(tag, null, text.length)) // text only
            else -> Unit // unknown tag: drop the tag, keep the inner text
        }
    }
    // Trim the generated text and shift/clip the spans into the trimmed range.
    var start = 0
    var endIncl = text.length
    while (start < endIncl && text[start].isWhitespace()) start++
    while (endIncl > start && text[endIncl - 1].isWhitespace()) endIncl--
    val length = endIncl - start
    return AnnotatedString.Builder().apply {
        append(text.substring(start, endIncl))
        for ((s, e, style) in spans) {
            val from = (s - start).coerceIn(0, length)
            val to = (e - start).coerceIn(0, length)
            if (to > from) addStyle(style, from, to)
        }
    }.toAnnotatedString()
}

private val BOLD_STYLE = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC_STYLE = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE_STYLE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val UNDERLINE_STYLE = SpanStyle(textDecoration = TextDecoration.Underline)

/** Named + numeric (&#..;) entities — the set that survives markdown→HTML. */
private fun unescapeEntity(entity: String): String = when (entity) {
    "&amp;" -> "&"
    "&lt;" -> "<"
    "&gt;" -> ">"
    "&quot;" -> "\""
    "&apos;", "&#39;" -> "'"
    "&nbsp;" -> " "
    else -> {
        val digits = entity.removePrefix("&#").removeSuffix(";")
        digits.toIntOrNull()?.takeIf { it in 1..0xFFFF }?.toChar()?.toString() ?: entity
    }
}
