package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadRowLogicTest {

    private fun msg(id: String, body: String = "hi", ts: Long = 100) = ThreadRowValues(
        roomId = "!r", eventId = id, kind = "message", sender = "@a", timestampMs = ts,
        ingestSeq = 1, body = body, formattedHtml = null, contentType = "text",
        replyToId = null, mediaMeta = null, sendStatus = null, encrypted = 0,
        prevEventId = null, batchBefore = null, targetEventId = null, payload = null,
        reactionSummary = null)

    private fun raw(
        eventId: String,
        type: String = "m.room.message",
        sender: String = "@a",
        originTs: Long = 100,
        contentJson: String? = null,
        decryptedBody: String? = null,
        formattedBody: String? = null,
        prevEventId: String? = null,
        batchBefore: String? = null,
        redactsTopLevel: String? = null,
    ) = RawEventInput(
        eventId, type, sender, originTs, contentJson, decryptedBody,
        formattedBody, prevEventId, batchBefore, redactsTopLevel,
    )

    // --- buildRows: message rows ---

    @Test
    fun `plaintext message becomes a message row`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 5,
            listOf(raw("m1", contentJson = """{"msgtype":"m.text","body":"hi"}""")),
            now = 1_000,
        )
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("!r", row.roomId)
        assertEquals("message", row.kind)
        assertEquals("@a", row.sender)
        assertEquals(100, row.timestampMs)
        assertEquals("hi", row.body)
        assertEquals("text", row.contentType)
        assertEquals(5, row.ingestSeq)
        assertEquals(0, row.encrypted)
    }

    @Test
    fun `ingestSeq is base plus input index`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 10, listOf(raw("m1"), raw("m2"), raw("m3")), now = 1_000,
        )
        assertEquals(listOf(10, 11, 12), rows.map { it.ingestSeq })
    }

    @Test
    fun `non message class event is dropped (predicate gate)`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(raw("j1", type = "m.room.member", contentJson = """{"membership":"join"}""")),
            now = 1_000,
        )
        assertTrue(rows.isEmpty(), "non message-class events must not become rows")
    }

    @Test
    fun `future stamped message is dropped (predicate gate)`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(raw("m1", originTs = 1_000 + ProjectionPredicate.FUTURE_SKEW_MS + 1)),
            now = 1_000,
        )
        assertTrue(rows.isEmpty(), "future-stamped bridge skew must not become a row")
    }

    @Test
    fun `sticker event is dropped (not message class)`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(raw("st1", type = "m.sticker", contentJson = """{"body":"sticker"}""")),
            now = 1_000,
        )
        assertTrue(rows.isEmpty(), "stickers must not become message rows (no read-path handling)")
    }

    // --- buildRows: side rows (bypass the predicate) ---

    @Test
    fun `edit event becomes an edit side row`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "e1",
                    contentJson = """{"msgtype":"m.text","body":"* edited",""" +
                        """"m.new_content":{"msgtype":"m.text","body":"edited"},""" +
                        """"m.relates_to":{"rel_type":"m.replace","event_id":"m1"}}""",
                )
            ),
            now = 1_000,
        )
        assertEquals(1, rows.size)
        assertEquals("edit", rows[0].kind)
        assertEquals("m1", rows[0].targetEventId)
        assertEquals("edited", rows[0].payload)
    }

    @Test
    fun `reaction event becomes a reaction side row`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "rx1",
                    contentJson = """{"m.relates_to":{"rel_type":"m.annotation",""" +
                        """"event_id":"m1","key":"❤️"}}""",
                )
            ),
            now = 1_000,
        )
        assertEquals(1, rows.size)
        assertEquals("reaction", rows[0].kind)
        assertEquals("m1", rows[0].targetEventId)
        assertEquals("❤️", rows[0].payload)
    }

    @Test
    fun `send status event becomes a send_status side row with delivered mapping`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "s1",
                    type = "com.beeper.message_send_status",
                    contentJson = """{"status":"SUCCESS","delivered_to_users":["@ghost:whatsapp"],""" +
                        """"m.relates_to":{"event_id":"m1"}}""",
                )
            ),
            now = 1_000,
        )
        assertEquals(1, rows.size)
        assertEquals("send_status", rows[0].kind)
        assertEquals("m1", rows[0].targetEventId)
        assertEquals("DELIVERED", rows[0].payload)
    }

    @Test
    fun `redaction event becomes a redaction side row`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(raw("rd1", type = "m.room.redaction", contentJson = """{"redacts":"m1"}""")),
            now = 1_000,
        )
        assertEquals(1, rows.size)
        assertEquals("redaction", rows[0].kind)
        assertEquals("m1", rows[0].targetEventId)
    }

    // --- encrypted placeholders ---

    @Test
    fun `encrypted event is placeholder`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw("e1", type = "m.room.encrypted", contentJson = """{"algorithm":"m.megolm.v1"}""")
            ),
            now = 1_000,
        )
        assertEquals(1, rows.size, "undecrypted encrypted events stay as placeholder rows")
        assertEquals(1, rows[0].encrypted)
        assertNull(rows[0].body)
        assertNull(rows[0].contentType)
    }

    @Test
    fun `encrypted event that decrypted is a full row`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "e1", type = "m.room.encrypted",
                    contentJson = """{"algorithm":"m.megolm.v1"}""",
                    decryptedBody = "secret",
                )
            ),
            now = 1_000,
        )
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].encrypted)
        assertEquals("secret", rows[0].body)
    }

    @Test
    fun `encrypted event with decrypted relations classifies as side rows`() {
        // contentJson is the DECRYPTED content (the writer's contract): an
        // m.replace relation must classify as an edit, an m.annotation as a
        // reaction — not as message rows.
        val edit = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "e1", type = "m.room.encrypted",
                    contentJson = """{"msgtype":"m.text","body":"* edited",""" +
                        """"m.new_content":{"body":"edited"},""" +
                        """"m.relates_to":{"rel_type":"m.replace","event_id":"m1"}}""",
                    decryptedBody = "edited",
                )
            ),
            now = 1_000,
        )
        assertEquals("edit", edit.single().kind)
        assertEquals("m1", edit.single().targetEventId)
        assertEquals("edited", edit.single().payload)

        val reaction = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "e2", type = "m.room.encrypted",
                    contentJson = """{"m.relates_to":{"rel_type":"m.annotation",""" +
                        """"event_id":"m1","key":"❤️"}}""",
                    decryptedBody = "❤️",
                )
            ),
            now = 1_000,
        )
        assertEquals("reaction", reaction.single().kind)
        assertEquals("m1", reaction.single().targetEventId)
        assertEquals("❤️", reaction.single().payload)
    }

    @Test
    fun `redaction falls back to top level redacts (pre v11)`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "rd1", type = "m.room.redaction",
                    contentJson = """{}""",
                    redactsTopLevel = "m1",
                )
            ),
            now = 1_000,
        )
        assertEquals("redaction", rows.single().kind)
        assertEquals("m1", rows.single().targetEventId)
    }

    @Test
    fun `content level redacts wins over top level`() {
        val rows = ThreadRowLogic.buildRows(
            "!r", 0,
            listOf(
                raw(
                    "rd1", type = "m.room.redaction",
                    contentJson = """{"redacts":"m-content"}""",
                    redactsTopLevel = "m-top",
                )
            ),
            now = 1_000,
        )
        assertEquals("m-content", rows.single().targetEventId)
    }

    // --- applySideRow ---

    @Test
    fun `edit applies to present target`() {
        val target = msg("m1")
        val edit = target.copy(kind = "edit", targetEventId = "m1", payload = "edited!")
        val out = ThreadRowLogic.applySideRow(target, edit, emptyList())
        assertEquals("edited!", out.body)
    }

    @Test
    fun `edit to absent target is caller's no-op`() {
        val target = msg("m2")
        val edit = target.copy(kind = "edit", targetEventId = "mX", payload = "e")
        assertEquals(target, ThreadRowLogic.applySideRow(target, edit, emptyList()))
    }

    @Test
    fun `redaction blanks body and sets contentType`() {
        val target = msg("m1", body = "secret")
        val redaction = target.copy(kind = "redaction", targetEventId = "m1")
        val out = ThreadRowLogic.applySideRow(target, redaction, emptyList())
        assertEquals("Redacted", out.body)
        assertEquals("redacted", out.contentType)
    }

    @Test
    fun `reaction write recomputes summary`() {
        val target = msg("m1")
        val reactions = listOf(
            target.copy(kind = "reaction", targetEventId = "m1", payload = "❤️", sender = "@a"),
            target.copy(kind = "reaction", targetEventId = "m1", payload = "❤️", sender = "@b"),
            target.copy(kind = "reaction", targetEventId = "m1", payload = "👍", sender = "@a"))
        val out = ThreadRowLogic.applySideRow(target, target.copy(kind = "reaction"), reactions)
        assertEquals("""{"❤️":2,"👍":1}""", out.reactionSummary)
    }

    @Test
    fun `reaction redaction drops the key`() {
        val target = msg("m1")
        val rx1 = target.copy(eventId = "rx1", kind = "reaction", targetEventId = "m1", payload = "❤️", sender = "@a")
        val rx2 = target.copy(eventId = "rx2", kind = "reaction", targetEventId = "m1", payload = "❤️", sender = "@b")
        val rx3 = target.copy(eventId = "rx3", kind = "reaction", targetEventId = "m1", payload = "👍", sender = "@a")
        // The writer drops the redacted reaction row from the truth list and
        // re-applies; the ❤️ count drops to 1.
        val out = ThreadRowLogic.applySideRow(
            target,
            target.copy(kind = "redaction", targetEventId = "rx1"),
            listOf(rx2, rx3),
        )
        assertEquals("""{"❤️":1,"👍":1}""", out.reactionSummary)
    }

    @Test
    fun `send status stamps the target row`() {
        val target = msg("m1")
        val status = target.copy(kind = "send_status", targetEventId = "m1", payload = "DELIVERED")
        val out = ThreadRowLogic.applySideRow(target, status, emptyList())
        assertEquals("DELIVERED", out.sendStatus)
    }

    // --- keyset cursor ---

    @Test
    fun `keyset roundtrip`() {
        assertEquals(100L to 7, ThreadRowLogic.parseKeyset(ThreadRowLogic.keysetBefore(100, 7)))
    }

    @Test
    fun `parseKeyset malformed input throws`() {
        assertFailsWith<NumberFormatException>("a cursor without the | separator must not parse silently") {
            ThreadRowLogic.parseKeyset("not-a-cursor")
        }
        assertFailsWith<NumberFormatException>("a non-numeric component must not parse silently") {
            ThreadRowLogic.parseKeyset("100|x")
        }
    }

    @Test
    fun `reactionSummaryOf empty list is empty object`() {
        assertEquals("{}", ThreadRowLogic.reactionSummaryOf(emptyList()))
    }

    // --- seed mapping: stuck-decrypt placeholder rule ---

    @Test
    fun `placeholder body detected as stuck decrypt`() {
        assertTrue(
            ThreadRowLogic.isStuckDecryptBody(ThreadRowLogic.ENCRYPTED_PLACEHOLDER_BODY),
            "the rendered placeholder body must map to an encrypted=1 seed row",
        )
    }

    @Test
    fun `real bodies are not stuck decrypt`() {
        assertTrue(!ThreadRowLogic.isStuckDecryptBody(null))
        assertTrue(!ThreadRowLogic.isStuckDecryptBody("hi"))
        assertTrue(!ThreadRowLogic.isStuckDecryptBody("[Encrypted messages]"))
        assertTrue(!ThreadRowLogic.isStuckDecryptBody(""))
    }
}
