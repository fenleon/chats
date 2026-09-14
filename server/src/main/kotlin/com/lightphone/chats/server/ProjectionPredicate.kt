package com.lightphone.chats.server

/**
 * The ingest-time front-door decision (PLAN.md "Ingest-time projection",
 * 2026-09-14): an event advances the room projection iff it is message-class,
 * not own, not future-stamped (bridge clock skew), not an m.replace edit, and
 * — for encrypted events — actually decrypted. Not renderable ⇒ not new: one
 * rule every projection consumer follows, replacing the per-consumer junk
 * patches (receipt-cursor exclusion, notification gate skips, disk re-derive).
 *
 * Pure Kotlin so `kotlin-test` covers it without Android or a Trixnity client;
 * MatrixRepository maps each `TimelineEvent` onto these parameters.
 */
object ProjectionPredicate {

    /** Mirrors MatrixRepository.UNREAD_ENCRYPTED_STALE_MS: a real encrypted
     *  message decrypts within seconds of landing; one still-undecrypted this
     *  long after its timestamp is bridge re-delivery junk (09-14 storm). */
    const val STALE_ENCRYPTED_MS = 600_000L

    /** Mirrors MatrixRepository.UNREAD_FUTURE_SKEW_MS: events stamped more
     *  than this far into the future are bridge clock skew — never new. */
    const val FUTURE_SKEW_MS = 300_000L

    fun admits(
        messageClass: Boolean,
        isReplaceEdit: Boolean,
        sender: String,
        ownUserId: String,
        originTs: Long,
        now: Long,
        isEncrypted: Boolean,
        decryptedOk: Boolean,
    ): Boolean {
        if (!messageClass) return false
        if (isReplaceEdit) return false
        if (sender == ownUserId) return false
        if (originTs > now + FUTURE_SKEW_MS) return false
        // Encrypted: admit only what decrypts (within the decrypt window —
        // pending and still-undecryptable both stay out; a pending event is
        // picked up by the next projection recompute once it decrypts).
        if (isEncrypted && !decryptedOk) return false
        return true
    }

    /** True when a still-undecrypted encrypted event has passed the junk
     *  window (no longer worth a decrypt-retry recompute). */
    fun encryptedStale(originTs: Long, now: Long): Boolean =
        now - originTs > STALE_ENCRYPTED_MS
}
