package com.lightphone.chats.server

/**
 * Markdown → Matrix m.text conversion for outgoing messages (v1 subset):
 * inline **bold**, *italic*, `code`, ~~strike~~; block-level `-`/`*` bullets,
 * `1.` ordered lists (one level), `#`–`######` ATX headings, blank-line
 * paragraphs. Markdown is ALWAYS on (Beeper behavior), but the formatted
 * variant is only produced when the text actually contains a construct — a
 * plain message ("see you at 2", "2 * 3 * 4") keeps its body byte-for-byte and
 * sends no HTML, so nothing is mangled on the receiving clients.
 *
 * The HTML goes to OTHER Matrix clients (Beeper/Element render it), so it is
 * escaped (& < >) and kept to well-formed minimal tags. The plain body is the
 * markdown-stripped text — the fallback clients show when they can't render.
 */
internal object MarkdownConverter {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*]\\s+(.+)$")
    private val ORDERED = Regex("^\\s*\\d+\\.\\s+(.+)$")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    // An italic span must not start or end with whitespace: "2 * 3 * 4" stays
    // literal ("* 3 *" is not emphasis), "a*b*c" and "*a b*" convert.
    private val ITALIC = Regex("\\*([^\\s*][^*]*?)\\*")
    private val STRIKE = Regex("~~(.+?)~~")
    private val CODE = Regex("`([^`]+)`")

    fun hasMarkdown(text: String): Boolean =
        text.split("\n").any { line ->
            HEADING.matches(line) || BULLET.matches(line) || ORDERED.matches(line)
        } || BOLD.containsMatchIn(text) || STRIKE.containsMatchIn(text) ||
            CODE.containsMatchIn(text) || ITALIC.containsMatchIn(text)

    /**
     * (plainBody, formattedHtmlOrNull): the plain body is the original text
     * with inline markers stripped (structure — lists/headings — is already
     * readable plain text); null html when there is no markdown at all.
     */
    fun toMatrixContent(text: String): Pair<String, String?> {
        if (!hasMarkdown(text)) return text to null
        val plain = text.split("\n").joinToString("\n") { stripInline(it) }
        return plain to toHtml(text)
    }

    /** Line-walk into blocks: headings standalone, blank lines end a block,
     *  runs of list lines become one list, everything else is a paragraph
     *  (hard line breaks join with <br/>). */
    private fun toHtml(text: String): String {
        val lines = text.split("\n")
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i++
                HEADING.matches(line) -> {
                    val m = HEADING.matchEntire(line)!!
                    val level = m.groupValues[1].length
                    out.append("<h$level>").append(inline(m.groupValues[2])).append("</h$level>")
                    i++
                }
                else -> {
                    // A block is a run of ONE kind: bullet, ordered or plain.
                    // A list line interrupts a paragraph and vice versa (no
                    // blank line required between them).
                    val kind = blockKind(line)
                    val block = mutableListOf<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !HEADING.matches(lines[i]) && blockKind(lines[i]) == kind) {
                        block.add(lines[i])
                        i++
                    }
                    when (kind) {
                        1 -> {
                            out.append("<ul>")
                            block.forEach { out.append("<li>").append(inline(BULLET.matchEntire(it)!!.groupValues[1])).append("</li>") }
                            out.append("</ul>")
                        }
                        2 -> {
                            out.append("<ol>")
                            block.forEach { out.append("<li>").append(inline(ORDERED.matchEntire(it)!!.groupValues[1])).append("</li>") }
                            out.append("</ol>")
                        }
                        else -> out.append("<p>").append(block.joinToString("<br/>") { inline(it) }).append("</p>")
                    }
                }
            }
        }
        return out.toString()
    }

    /** Escape first, then wrap the (already-escaped) marker content. */
    private fun inline(s: String): String {
        val e = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return BOLD.replace(e) { "<strong>${it.groupValues[1]}</strong>" }
            .let { CODE.replace(it) { m -> "<code>${m.groupValues[1]}</code>" } }
            .let { STRIKE.replace(it) { m -> "<del>${m.groupValues[1]}</del>" } }
            .let { ITALIC.replace(it) { m -> "<em>${m.groupValues[1]}</em>" } }
    }

    private fun stripInline(s: String): String =
        s.replace(BOLD, "$1").replace(CODE, "$1").replace(STRIKE, "$1").replace(ITALIC, "$1")

    /** 0 = plain/paragraph line, 1 = bullet item, 2 = ordered item. */
    private fun blockKind(line: String) = when {
        BULLET.matches(line) -> 1
        ORDERED.matches(line) -> 2
        else -> 0
    }
}
