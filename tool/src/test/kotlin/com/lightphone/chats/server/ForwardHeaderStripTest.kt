package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The bridge "forwarded" header strip (LP3 feedback 2026-09-12): the raw
 * marker was still reaching the body, so the patterns were broadened. Each
 * accepted variant must strip; each near-miss (a message that merely starts
 * with the word) must pass through untouched.
 */
class ForwardHeaderStripTest {

    @Test
    fun `header variants strip`() {
        val variants = listOf(
            "\u21B7 Forwarded\n\nhello",
            "Forwarded\n\nhello",
            "\u21B7 Forwarded \n\nhello",
            " Forwarded\nhello",
            "\u21B7Forwarded\nhello",
            "Forwarded many times\n\nhello",
            "\u21B7 Forwarded many times\nhello",
            "Forwarded from Alice\nhello",
            "\u21B7 Forwarded from Alice\n\nhello",
            // Telegram-style header (LP3 feedback 2026-09-12): "message" between
            // "Forwarded" and "from" — the variant that was still reaching rows.
            "Forwarded message from Alice\n\nhello",
            "\u21B7 Forwarded message from Alice\nhello",
            // The tokens combine.
            "Forwarded many times from Alice\nhello",
        )
        variants.forEach { body ->
            assertEquals("hello" to true, stripForwardHeader(body), body)
        }
    }

    @Test
    fun `bare header strips to empty`() {
        assertEquals("" to true, stripForwardHeader("\u21B7 Forwarded"))
        assertEquals("" to true, stripForwardHeader("\u21B7 Forwarded\n\n"))
    }

    @Test
    fun `ordinary text is untouched`() {
        listOf(
            "Forwarded the email to Bob",
            "Forwarded to the team\n\n\nhello",
            "hello Forwarded",
            "I forwarded it to you",
        ).forEach { body ->
            assertEquals(body to false, stripForwardHeader(body), body)
        }
    }

    @Test
    fun `html header variants strip`() {
        listOf(
            "<p>\u21B7 Forwarded</p><p>hello</p>" to "<p>hello</p>",
            "<p>Forwarded</p><p>hello</p>" to "<p>hello</p>",
            "<p>\u21B7 Forwarded from Alice</p><p>hello</p>" to "<p>hello</p>",
            "<p>\u21B7 Forwarded many times</p><p>hello</p>" to "<p>hello</p>",
            "<p>Forwarded <br/>hello</p>" to "hello</p>",
            "<p>Forwarded message from Alice</p><p>hello</p>" to "<p>hello</p>",
            "<p>\u21B7 Forwarded message from Alice</p><p>hello</p>" to "<p>hello</p>",
            // mautrix-whatsapp marks its header paragraph explicitly; the
            // attribute + <em> wrapper was the variant still rendering the
            // italic "↷ Forwarded" above the content (LP3 feedback 2026-09-13).
            "<p data-mx-forwarded-notice><em>\u21B7 Forwarded</em></p><p>hello</p>" to "<p>hello</p>",
            "<p data-mx-forwarded-notice><em>\u21B7 Forwarded</em></p>" to "",
        ).forEach { (html, expected) ->
            assertEquals(expected to true, stripForwardHeaderFromHtml(html), html)
        }
    }

    @Test
    fun `ordinary html is untouched`() {
        val html = "<p>Forwarded the email to Bob</p>"
        assertEquals(html to false, stripForwardHeaderFromHtml(html))
        assertFalse(FORWARD_HEADER_HTML.containsMatchIn(html))
    }
}
