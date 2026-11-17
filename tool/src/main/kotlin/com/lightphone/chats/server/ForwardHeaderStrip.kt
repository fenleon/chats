package com.lightphone.chats.server

/**
 * Lifts a bridge "forwarded" header off a message body (WhatsApp forwards
 * arrive as "↷ Forwarded" or "Forwarded" + a blank line + the content — the
 * bridge's text stand-in for WhatsApp's forward chip). Returns the content
 * ("" when the message is nothing but the header — a forwarded photo's caption
 * is just the marker) and whether the header was found.
 *
 * The tool renders the header itself as the ↷ glyph + "forwarded" tag via
 * `Message.forwarded`, and previews strip it so the room list shows the
 * content, not the marker. (2026-09-09: header variants without the ↷ glyph
 * and after a single newline now strip too — rows kept showing the header
 * above the content. 2026-09-12: leading whitespace, a trailing space before
 * the newline, and "Forwarded many times" now strip too — rows were still
 * showing the raw marker.)
 *
 * Deliberately conservative on the word alone: a message that merely *starts*
 * with "Forwarded" ("Forwarded the email to Bob") does not strip. Covered by
 * [ForwardHeaderStripTest] — top-level so it needs no `MatrixRepository`
 * initialization.
 *
 * 2026-09-12 (LP3): Telegram forwards arrive as "Forwarded message from
 * <origin>" — the optional `message` token before `from` (else the whole
 * header stayed in the body; observed on-device, the only failing variant).
 */
internal val FORWARD_HEADER = Regex(
    "^\\s*(?:\u21B7[^\\S\\n]*)?Forwarded(?:[^\\S\\n]+many[^\\S\\n]+times)?" +
        "(?:[^\\S\\n]+(?:message[^\\S\\n]+)?from[^\\n]*)?[^\\S\\n]*(?:\\n+|\$)",
)

internal fun stripForwardHeader(body: String): Pair<String, Boolean> {
    val match = FORWARD_HEADER.find(body) ?: return body to false
    return body.substring(match.value.length).trimStart('\n') to true
}

/** Same strip for a formatted variant (`formatted_body`): the header is its own
 *  leading paragraph, or the first line before a `<br/>`. Mirrors the plain
 *  pattern's optional `message` token ("Forwarded message from …"). */
internal val FORWARD_HEADER_HTML = Regex(
    "^<p>\\s*(?:\u21B7[^\\S\\n]*)?Forwarded(?:[^\\S\\n]+many[^\\S\\n]+times)?" +
        "(?:[^\\S\\n]+(?:message[^\\S\\n]+)?from[^<]*)?[^\\S<]*(?:</p>\\s*|<br\\s*/?>)",
)

/** mautrix-whatsapp's forward header is its own marked-up paragraph:
 *  `<p data-mx-forwarded-notice><em>↷ Forwarded</em></p>`. The attribute is a
 *  definitive signal, so the whole paragraph goes whatever its text — the
 *  attribute plus the `<em>` wrapper defeated [FORWARD_HEADER_HTML], which
 *  expects a bare `<p>`, so the italic header kept rendering above the content
 *  (LP3 feedback 2026-09-13). */
internal val FORWARDED_NOTICE_HTML =
    Regex("^<p\\b[^>]*data-mx-forwarded-notice[^>]*>[\\s\\S]*?</p>\\s*")

/** (content, whether a header was found). The `data-mx` notice paragraph is
 *  tried first; the text-based [FORWARD_HEADER_HTML] stays for the other
 *  bridges' bare-`<p>` headers. */
internal fun stripForwardHeaderFromHtml(html: String): Pair<String, Boolean> {
    val notice = html.replaceFirst(FORWARDED_NOTICE_HTML, "")
    if (notice != html) return notice.trim() to true
    val stripped = html.replaceFirst(FORWARD_HEADER_HTML, "")
    return stripped.trim() to (stripped != html)
}
