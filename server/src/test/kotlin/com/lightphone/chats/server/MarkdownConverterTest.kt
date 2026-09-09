package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownConverterTest {

    @Test
    fun `no markdown passes through byte for byte`() {
        val text = "see you at 2, ok?"
        assertEquals(text to null, MarkdownConverter.toMatrixContent(text))
        assertFalse(MarkdownConverter.hasMarkdown(text))
    }

    @Test
    fun `arithmetic asterisks are not italic`() {
        val text = "2 * 3 * 4"
        assertEquals(text to null, MarkdownConverter.toMatrixContent(text))
    }

    @Test
    fun `bold inside words`() {
        assertEquals(
            "foobarbaz" to "<p>foo<strong>bar</strong>baz</p>",
            MarkdownConverter.toMatrixContent("foo**bar**baz"),
        )
    }

    @Test
    fun `italic inside words`() {
        assertEquals(
            "abc" to "<p>a<em>b</em>c</p>",
            MarkdownConverter.toMatrixContent("a*b*c"),
        )
    }

    @Test
    fun `unclosed bold marker stays literal`() {
        // An unclosed marker is not a construct: byte-for-byte passthrough.
        assertEquals("**bold" to null, MarkdownConverter.toMatrixContent("**bold"))
    }

    @Test
    fun `code and strike convert`() {
        assertEquals(
            "run code now" to "<p><code>run code</code> <del>now</del></p>",
            MarkdownConverter.toMatrixContent("`run code` ~~now~~"),
        )
    }

    @Test
    fun `bullet list`() {
        val (plain, html) = MarkdownConverter.toMatrixContent("- one\n- two")
        assertEquals("- one\n- two", plain)
        assertEquals("<ul><li>one</li><li>two</li></ul>", html)
    }

    @Test
    fun `ordered list`() {
        val (_, html) = MarkdownConverter.toMatrixContent("1. one\n2. two")
        assertEquals("<ol><li>one</li><li>two</li></ol>", html)
    }

    @Test
    fun `list interrupts a paragraph without a blank line`() {
        val (_, html) = MarkdownConverter.toMatrixContent("intro\n- one\n- two\n# Heading")
        assertEquals("<p>intro</p><ul><li>one</li><li>two</li></ul><h1>Heading</h1>", html)
    }

    @Test
    fun `paragraph interrupts a list`() {
        val (_, html) = MarkdownConverter.toMatrixContent("- one\nplain tail")
        assertEquals("<ul><li>one</li></ul><p>plain tail</p>", html)
    }

    @Test
    fun `heading levels`() {
        val (_, h1) = MarkdownConverter.toMatrixContent("# Title")
        val (_, h3) = MarkdownConverter.toMatrixContent("### Deep")
        val (_, h6) = MarkdownConverter.toMatrixContent("###### Deepest")
        assertEquals("<h1>Title</h1>", h1)
        assertEquals("<h3>Deep</h3>", h3)
        assertEquals("<h6>Deepest</h6>", h6)
    }

    @Test
    fun `html special chars are escaped`() {
        val (plain, html) = MarkdownConverter.toMatrixContent("a < b & c > d **bold**")
        assertEquals("a < b & c > d bold", plain)
        assertEquals("<p>a &lt; b &amp; c &gt; d <strong>bold</strong></p>", html)
    }

    @Test
    fun `paragraphs and line breaks`() {
        val (plain, html) = MarkdownConverter.toMatrixContent("first line\nsecond **line**\n\nthird")
        assertEquals("first line\nsecond line\n\nthird", plain)
        assertEquals("<p>first line<br/>second <strong>line</strong></p><p>third</p>", html)
    }

    @Test
    fun `hasMarkdown detects constructs`() {
        assertTrue(MarkdownConverter.hasMarkdown("**x**"))
        assertTrue(MarkdownConverter.hasMarkdown("*y*"))
        assertTrue(MarkdownConverter.hasMarkdown("~~z~~"))
        assertTrue(MarkdownConverter.hasMarkdown("`w`"))
        assertTrue(MarkdownConverter.hasMarkdown("- list"))
        assertTrue(MarkdownConverter.hasMarkdown("1. list"))
        assertTrue(MarkdownConverter.hasMarkdown("# heading"))
        assertFalse(MarkdownConverter.hasMarkdown("plain words only"))
    }
}
