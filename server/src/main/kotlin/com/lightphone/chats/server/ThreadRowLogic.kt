package com.lightphone.chats.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One materialized thread-store row's values (docs/THREAD-STORE-SPEC.md §1).
 *  The SQL wrapper (Tasks 2/3) maps these onto the `ThreadRow` table 1:1. */
data class ThreadRowValues(
    val roomId: String,
    val eventId: String,
    val kind: String,
    val sender: String?,
    val timestampMs: Long,
    val ingestSeq: Int,
    val body: String?,
    val formattedHtml: String?,
    val contentType: String?,
    val replyToId: String?,
    val mediaMeta: String?,
    val sendStatus: String?,
    val encrypted: Int,
    val prevEventId: String?,
    val batchBefore: String?,
    val targetEventId: String?,
    val payload: String?,
    val reactionSummary: String?,
)

/** One raw sync-round event as the ingest writer hands it over
 *  (SPEC §2): the store's `TimelineEvent` JSON plus the decrypted body when
 *  Trixnity has one yet. */
data class RawEventInput(
    val eventId: String,
    val type: String,
    val sender: String,
    val originTs: Long,
    val contentJson: String?,
    val decryptedBody: String?,
    val formattedBody: String?,
    val prevEventId: String?,
    val batchBefore: String?,
)

/** Renderable item kinds — the `kind` column's wire values (SPEC §1). */
enum class RowKind(val wire: String) {
    MESSAGE("message"),
    REACTION("reaction"),
    EDIT("edit"),
    REDACTION("redaction"),
    SEND_STATUS("send_status"),
}

/**
 * Pure row rules for the ingest-materialized thread store (SPEC §1) — no
 * Android imports, no I/O, kotlin-test covered like [ProjectionPredicate].
 *
 * [ThreadRowLogic.buildRows] turns a sync round's raw events into row values
 * (message rows gated by the predicate, side rows bypassing it, undecrypted
 * encrypted events as placeholder rows); [ThreadRowLogic.applySideRow] folds a
 * side row into its target message row; [ThreadRowLogic.reactionSummaryOf] and
 * the keyset helpers round out the decisions the SQL wrapper, ingest writer,
 * and backfill (Tasks 2-4, 7) all consume. The store keeps side rows as the
 * only truth — every patched column here is a cache of those rows.
 */
object ThreadRowLogic {

    /** A redacted message row is never deleted — body becomes this marker
     *  and [CONTENT_REDACTED] (SPEC §1), same values the read path serves
     *  today. */
    const val REDACTED_BODY = "Redacted"
    const val CONTENT_REDACTED = "redacted"

    /** Mirrors MatrixRepository.BEEPER_SEND_STATUS_EVENT_TYPE. */
    const val SEND_STATUS_EVENT_TYPE = "com.beeper.message_send_status"

    private const val TYPE_MESSAGE = "m.room.message"
    private const val TYPE_ENCRYPTED = "m.room.encrypted"
    private const val TYPE_REDACTION = "m.room.redaction"
    private const val TYPE_STICKER = "m.sticker"

    private const val REL_ANNOTATION = "m.annotation"
    private const val REL_REPLACE = "m.replace"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RawContent(
        val body: String? = null,
        val msgtype: String? = null,
        val redacts: String? = null,
        val status: String? = null,
        @SerialName("delivered_to_users") val deliveredToUsers: List<String> = emptyList(),
        @SerialName("m.new_content") val newContent: NewContent? = null,
        @SerialName("m.relates_to") val relatesTo: Relation? = null,
    )

    @Serializable
    private data class NewContent(val body: String? = null)

    @Serializable
    private data class Relation(
        @SerialName("rel_type") val relType: String? = null,
        @SerialName("event_id") val eventId: String? = null,
        val key: String? = null,
        @SerialName("m.in_reply_to") val inReplyTo: InReplyTo? = null,
    )

    @Serializable
    private data class InReplyTo(@SerialName("event_id") val eventId: String? = null)

    /**
     * Row values for one sync round. `ingestSeq = ingestBase + index` over the
     * input order (dropped events still consume a seq so in-round order is
     * stable). Message-class events go through [ProjectionPredicate] first —
     * EXCEPT its encrypted-pending rule: an undecrypted encrypted event is the
     * store's placeholder row (encrypted=1, body null; SPEC §2/§4), not a
     * drop, so the gate runs with the encrypted rule neutralized. Side kinds
     * (reaction / edit / redaction / send-status) bypass the gate entirely —
     * they are not messages (SPEC §1); their target may arrive in a later
     * round, in which case the writer applies them late.
     */
    fun buildRows(
        roomId: String,
        ingestBase: Int,
        events: List<RawEventInput>,
        now: Long = System.currentTimeMillis(),
    ): List<ThreadRowValues> {
        val rows = ArrayList<ThreadRowValues>(events.size)
        for ((index, e) in events.withIndex()) {
            val content = e.contentJson?.let {
                runCatching { json.decodeFromString<RawContent>(it) }.getOrNull()
            }
            val rel = content?.relatesTo
            var kind: String
            var targetEventId: String? = null
            var payload: String? = null
            var body: String? = null
            var formattedHtml: String? = null
            var contentType: String? = null
            var replyToId: String? = null
            var encrypted = 0
            when {
                e.type == TYPE_REDACTION -> {
                    kind = RowKind.REDACTION.wire
                    // Room v11 moved `redacts` into content; the writer's
                    // RawEventInput carries that JSON only.
                    targetEventId = content?.redacts
                }
                rel?.relType == REL_REPLACE -> {
                    kind = RowKind.EDIT.wire
                    targetEventId = rel.eventId
                    payload = content?.newContent?.body ?: content?.body
                }
                rel?.relType == REL_ANNOTATION -> {
                    kind = RowKind.REACTION.wire
                    targetEventId = rel.eventId
                    payload = rel.key
                }
                e.type == SEND_STATUS_EVENT_TYPE -> {
                    kind = RowKind.SEND_STATUS.wire
                    targetEventId = rel?.eventId
                    payload = sendStatusOf(content)
                }
                else -> {
                    val messageClass = e.type == TYPE_MESSAGE ||
                        e.type == TYPE_ENCRYPTED || e.type == TYPE_STICKER
                    val renders = ProjectionPredicate.renders(
                        messageClass = messageClass,
                        isReplaceEdit = false,
                        originTs = e.originTs,
                        now = now,
                        isEncrypted = false,
                        decryptedOk = true,
                    )
                    if (!renders) continue
                    kind = RowKind.MESSAGE.wire
                    val undecrypted = e.type == TYPE_ENCRYPTED && e.decryptedBody == null
                    if (undecrypted) {
                        encrypted = 1
                    } else {
                        body = e.decryptedBody ?: content?.body
                        formattedHtml = e.formattedBody
                        contentType = contentTypeOf(content?.msgtype)
                        replyToId = rel?.inReplyTo?.eventId
                    }
                }
            }
            rows += ThreadRowValues(
                roomId = roomId,
                eventId = e.eventId,
                kind = kind,
                sender = e.sender,
                timestampMs = e.originTs,
                ingestSeq = ingestBase + index,
                body = body,
                formattedHtml = formattedHtml,
                contentType = contentType,
                replyToId = replyToId,
                mediaMeta = null,
                sendStatus = null,
                encrypted = encrypted,
                prevEventId = if (kind == RowKind.MESSAGE.wire) e.prevEventId else null,
                batchBefore = if (kind == RowKind.MESSAGE.wire) e.batchBefore else null,
                targetEventId = targetEventId,
                payload = payload,
                reactionSummary = null,
            )
        }
        return rows
    }

    /**
     * Fold a side row into its target message row: edit rewrites the body,
     * redaction blanks it ([REDACTED_BODY]/[CONTENT_REDACTED]) — and also
     * refreshes the reaction cache, because a redaction of a reaction row (an
     * unsend) is the writer dropping that row from `reactions` — send-status
     * stamps [ThreadRowValues.sendStatus], a reaction write recomputes the
     * summary. Returns the target unchanged when the side row doesn't apply
     * to it (absent/other target, non-message target).
     */
    fun applySideRow(
        target: ThreadRowValues,
        side: ThreadRowValues,
        reactions: List<ThreadRowValues>,
    ): ThreadRowValues {
        if (target.kind != RowKind.MESSAGE.wire) return target
        return when (side.kind) {
            RowKind.EDIT.wire ->
                if (side.targetEventId == target.eventId) target.copy(body = side.payload)
                else target
            RowKind.REDACTION.wire -> {
                var out = target
                if (side.targetEventId == target.eventId) {
                    out = out.copy(
                        body = REDACTED_BODY,
                        formattedHtml = null,
                        contentType = CONTENT_REDACTED,
                    )
                }
                out.copy(
                    reactionSummary = reactionSummaryOf(
                        reactions.filter { it.targetEventId == target.eventId },
                    ),
                )
            }
            RowKind.SEND_STATUS.wire ->
                if (side.targetEventId == target.eventId) target.copy(sendStatus = side.payload)
                else target
            RowKind.REACTION.wire -> target.copy(
                reactionSummary = reactionSummaryOf(
                    reactions.filter { it.targetEventId == target.eventId },
                ),
            )
            else -> target
        }
    }

    /** The target row's cached `reactionSummary` (SPEC §1): JSON
     *  `{reactionKey: count}` over the target's reaction rows — the side rows
     *  are the truth, this is only their materialization. Keys are sorted by
     *  code point (natural UTF-16 order would sort astral emoji like 👍 before
     *  BMP ones like ❤️) so equal input yields byte-identical output. */
    fun reactionSummaryOf(reactions: List<ThreadRowValues>): String =
        json.encodeToString(
            reactions
                .filter { it.kind == RowKind.REACTION.wire && it.payload != null }
                .groupingBy { it.payload!! }
                .eachCount()
                .entries
                .sortedWith(compareBy { it.key.codePointAt(0) })
                .associate { it.key to it.value },
        )

    /** Older-page keyset cursor: the `(timestampMs, ingestSeq)` tuple joined
     *  with `|` (both components numeric — no escaping needed, SPEC §6). */
    fun keysetBefore(cursorTs: Long, cursorSeq: Int): String = "$cursorTs|$cursorSeq"

    /** Inverse of [keysetBefore]. */
    fun parseKeyset(raw: String): Pair<Long, Int> =
        raw.substringBefore('|').toLong() to raw.substringAfter('|').toInt()

    /** Beeper bridges report delivery as SUCCESS + `delivered_to_users` —
     *  that IS the delivered state (same mapping the read path applies today,
     *  MatrixRepository.sendStatusByEventId). */
    private fun sendStatusOf(content: RawContent?): String? {
        val status = content?.status ?: return null
        return if (status == "SUCCESS" && content.deliveredToUsers.isNotEmpty()) "DELIVERED"
        else status
    }

    private fun contentTypeOf(msgtype: String?): String? = when (msgtype) {
        null -> null
        "m.image" -> "image"
        "m.audio" -> "audio"
        "m.notice" -> "notice"
        else -> "text"
    }
}
