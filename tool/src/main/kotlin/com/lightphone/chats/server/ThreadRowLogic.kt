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
    /**
     * MUST be the resolved event content JSON — for `m.room.encrypted`
     * events, the DECRYPTED content (the persisted `TimelineEvent` JSON after
     * Trixnity re-persists the decrypted payload). All relation
     * classification (m.replace edits, m.annotation reactions, reply-to,
     * send-status targets) reads from here; the ciphertext blob carries no
     * relations, so a writer passing raw ciphertext for a decrypted event
     * would misclassify it as a plain message row.
     */
    val contentJson: String?,
    val decryptedBody: String?,
    val formattedBody: String?,
    val prevEventId: String?,
    val batchBefore: String?,
    /** Pre-v11 room redactions carry the target as the event's top-level
     *  `redacts` field, outside content — the writer passes it here. Used
     *  only when content-level `redacts` is absent. */
    val redactsTopLevel: String? = null,
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

    /** The body a stuck-decrypt row renders as (MatrixRepository's previewText
     *  and the store serve path share it). */
    const val ENCRYPTED_PLACEHOLDER_BODY = "[Encrypted message]"

    /** Event-id prefix of the seed mapping's pseudo side rows (SPEC §7) —
     *  stand-ins written from a computed page (label senders for reactions,
     *  the edited body for edits) until the real events arrive through the
     *  ingest writer. */
    const val SEED_ROW_PREFIX = "seed:"

    /** Mirrors MatrixRepository.BEEPER_SEND_STATUS_EVENT_TYPE. */
    const val SEND_STATUS_EVENT_TYPE = "com.beeper.message_send_status"

    /**
     * Event types [buildRows] can ever turn into a row (message class,
     * redactions, send-status; anything carrying an m.relates_to — reactions,
     * edits — is eligible regardless of its type). The deep-repair store
     * queries filter missing pages to these: everything else (m.room.member,
     * m.room.topic, …) stays rowless BY DESIGN, and occupying the missing
     * pages with it kept the deep repair's room set from ever emptying — its
     * done-set froze rooms marked done before their keys landed, never to be
     * revisited (LP3 2026-09-21: a room's 96 missing events, 46 of them
     * buildable, stayed rowless for good).
     */
    val ROW_ELIGIBLE_EVENT_TYPES = listOf(TYPE_MESSAGE, TYPE_ENCRYPTED, TYPE_REDACTION, SEND_STATUS_EVENT_TYPE)


    private const val TYPE_MESSAGE = "m.room.message"
    private const val TYPE_ENCRYPTED = "m.room.encrypted"
    private const val TYPE_REDACTION = "m.room.redaction"

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
                    // Room v11 moved `redacts` into content; pre-v11 events
                    // carry it top-level on the event (redactsTopLevel).
                    targetEventId = content?.redacts ?: e.redactsTopLevel
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
                    // Stickers are NOT message class: today's read path has no
                    // sticker handling, so a message row would diverge from
                    // what the §7 seed path (computeMessagesPage) produces.
                    val messageClass = e.type == TYPE_MESSAGE || e.type == TYPE_ENCRYPTED
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
                // side.contentType is the media-edit reclassification channel:
                // a bridge notice→media replace edit (gmessages/RCS photos)
                // reclassifies the target row; text edits leave it null and
                // keep the target's type. Folded targets re-apply every side
                // row in ingest order, so thumbnail→full-size edit chains
                // converge on the newest classification.
                if (side.targetEventId == target.eventId) target.copy(
                    body = side.payload,
                    contentType = side.contentType ?: target.contentType,
                )
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

    /**
     * The seed mapping's placeholder rule: a computed page row whose served
     * body is the stuck-decrypt placeholder must seed as an `encrypted=1`
     * placeholder (body null), never as real text. The ingest hook never
     * replays pre-update events, so the seed is existing installs' only entry
     * into the store — and `writeRows` skips existing non-placeholder rows
     * while the 30 s recheck only scans `encrypted=1` rows, so a seeded text
     * row would freeze as the placeholder forever.
     */
    fun isStuckDecryptBody(body: String?): Boolean = body == ENCRYPTED_PLACEHOLDER_BODY

    /**
     * Pseudo seeded reaction retirement (Task 5 review ruling): the distinct
     * targets whose `seed:`-prefixed pseudo reaction rows must go when this
     * batch's REAL reaction rows land (ingest writer hook). A batch carrying a
     * real reaction row for T means the truth has arrived — the pseudo rows
     * (label senders) must retire before they outlive it: a redaction of the
     * real reaction event could never remove a seeded tag, and a display-name
     * change would duplicate one (the serve path's label dedup only absorbs
     * the identical label). Pseudo rows themselves never trigger retirement —
     * they ARE the retirement's object.
     */
    fun seedReactionRetireTargets(rows: List<ThreadRowValues>): Set<String> =
        rows.filter {
            it.kind == RowKind.REACTION.wire && !it.eventId.startsWith(SEED_ROW_PREFIX)
        }.mapNotNull { it.targetEventId }.toSet()

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
