package com.lightphone.chats.server

/**
 * The ingest-time front-door decision (PLAN.md "Ingest-time projection",
 * 2026-09-14): an event advances the room projection iff it is message-class,
 * not future-stamped (bridge clock skew), not an m.replace edit, and — for
 * encrypted events — actually decrypted. Not renderable ⇒ not new: one rule
 * every projection consumer follows, replacing the per-consumer junk patches
 * (receipt-cursor exclusion, notification gate skips, disk re-derive).
 *
 * Beeper's two-decision model: [renders] decides head-worthiness (own
 * messages included — your send moves the row time and sorts the room);
 * [countsAsUnread] adds the not-own rule on top. The own-receipt cursor is
 * applied at the call site on top of [countsAsUnread].
 *
 * Pure Kotlin so `kotlin-test` covers it without Android or a Trixnity
 * client; MatrixRepository maps each `TimelineEvent` onto these parameters.
 */
object ProjectionPredicate {

    /** Mirrors MatrixRepository.UNREAD_ENCRYPTED_STALE_MS: a real encrypted
     *  message decrypts within seconds of landing; one still-undecrypted this
     *  long after its timestamp is bridge re-delivery junk (09-14 storm). */
    const val STALE_ENCRYPTED_MS = 600_000L

    /** Mirrors MatrixRepository.UNREAD_FUTURE_SKEW_MS: events stamped more
     *  than this far into the future are bridge clock skew — never new. */
    const val FUTURE_SKEW_MS = 300_000L

    /** Head-worthiness: own messages included. [isFlood] — a txn-id event
     *  inside a bridge re-import flood (counted by the caller against the
     *  walked window, no second store walk). [isBatchReplay] — a bridge
     *  `batch/` history re-import blocked by the row-existence rule (see
     *  [batchReplay]). */
    fun renders(
        messageClass: Boolean,
        isReplaceEdit: Boolean,
        originTs: Long,
        now: Long,
        isEncrypted: Boolean,
        decryptedOk: Boolean,
        isFlood: Boolean = false,
        isBatchReplay: Boolean = false,
    ): Boolean {
        if (!messageClass) return false
        if (isReplaceEdit) return false
        if (originTs > now + FUTURE_SKEW_MS) return false
        if (isFlood) return false
        if (isBatchReplay) return false
        // Encrypted: admit only what decrypts (within the decrypt window —
        // pending and still-undecryptable both stay out; a pending event is
        // picked up by the next projection recompute once it decrypts).
        if (isEncrypted && !decryptedOk) return false
        return true
    }

    /** Unread-worthiness: renderable AND not own. The own-receipt cursor
     *  (ts > ownTs) is applied at the call site. */
    fun countsAsUnread(
        messageClass: Boolean,
        isReplaceEdit: Boolean,
        sender: String,
        ownUserId: String,
        originTs: Long,
        now: Long,
        isEncrypted: Boolean,
        decryptedOk: Boolean,
        isFlood: Boolean = false,
        isBatchReplay: Boolean = false,
    ): Boolean =
        sender != ownUserId && renders(
            messageClass, isReplaceEdit, originTs, now, isEncrypted, decryptedOk,
            isFlood, isBatchReplay,
        )

    /** Bridge re-import flood (the density fallback, formerly
     *  MatrixRepository.isFloodGhost): a txn-id event inside
     *  [FLOOD_THRESHOLD]-per-window company is a re-delivery wall, never a
     *  conversation. The caller counts the txn-id neighbors (it walks the
     *  window anyway) and passes the count. */
    const val FLOOD_THRESHOLD = 30

    fun floodGhost(txnNeighborCount: Int): Boolean = txnNeighborCount >= FLOOD_THRESHOLD

    /** mautrix bridge history imports carry `batch/…` txn ids (LP3 dump:
     *  `batch/1787302461807/53`) and re-deliver an old message as the room's
     *  newest event — a single replay copy a 30-per-window flood rule can't
     *  catch. A batch/ event never advances a head once the room HAS one
     *  ([existingHeadTs] non-null; a headless row counts — imports are
     *  backfill, shown in the thread, never "the latest message"). No row
     *  yet (fresh backfill) → the import is the only truth and still admits.
     *  Comparing against row EXISTENCE (not ts newer-or-equal) keeps the
     *  verdict stable: a ts-based rule flaps — the healed row's older head
     *  re-admits the import on the next recompute. */
    const val BATCH_TXN_PREFIX = "batch/"

    fun batchReplay(txnId: String?, existingHeadTs: Long?): Boolean =
        txnId != null && txnId.startsWith(BATCH_TXN_PREFIX) && existingHeadTs != null

    /** True when a still-undecrypted encrypted event has passed the junk
     *  window (no longer worth a decrypt-retry recompute). */
    fun encryptedStale(originTs: Long, now: Long): Boolean =
        now - originTs > STALE_ENCRYPTED_MS
}
