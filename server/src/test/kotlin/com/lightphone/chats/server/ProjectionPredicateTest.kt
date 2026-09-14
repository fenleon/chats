package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectionPredicateTest {

    private val now = 1_000_000_000_000L
    private val own = "@me:example.org"
    private val other = "@other:example.org"

    private fun admits(
        originTs: Long = now - 1_000,
        messageClass: Boolean = true,
        isReplaceEdit: Boolean = false,
        sender: String = other,
        isEncrypted: Boolean = false,
        decryptedOk: Boolean = true,
    ): Boolean = ProjectionPredicate.admits(
        messageClass = messageClass,
        isReplaceEdit = isReplaceEdit,
        sender = sender,
        ownUserId = own,
        originTs = originTs,
        now = now,
        isEncrypted = isEncrypted,
        decryptedOk = decryptedOk,
    )

    @Test
    fun `plain text message from other admits`() {
        assertTrue(admits())
    }

    @Test
    fun `own sender never admits`() {
        assertFalse(admits(sender = own))
    }

    @Test
    fun `non message class (reactions acks state) never admits`() {
        assertFalse(admits(messageClass = false))
    }

    @Test
    fun `m replace edit never admits`() {
        assertFalse(admits(isReplaceEdit = true))
    }

    @Test
    fun `future stamped beyond skew window never admits`() {
        assertFalse(admits(originTs = now + ProjectionPredicate.FUTURE_SKEW_MS + 1))
    }

    @Test
    fun `future stamped within skew window admits`() {
        assertTrue(admits(originTs = now + 60_000))
    }

    @Test
    fun `encrypted that decrypted admits`() {
        assertTrue(admits(isEncrypted = true, decryptedOk = true))
    }

    @Test
    fun `encrypted not decrypted (pending or failed) does not admit`() {
        assertFalse(admits(isEncrypted = true, decryptedOk = false))
    }

    @Test
    fun `plain message outside future window admits regardless of encrypted window`() {
        // sanity: the stale-encrypted rule must not touch plaintext events
        assertTrue(admits(originTs = now - ProjectionPredicate.STALE_ENCRYPTED_MS - 1))
    }

    @Test
    fun `encryptedStale true only past the junk window`() {
        assertEquals(false, ProjectionPredicate.encryptedStale(now - 1_000, now))
        assertEquals(false, ProjectionPredicate.encryptedStale(now - ProjectionPredicate.STALE_ENCRYPTED_MS, now))
        assertEquals(true, ProjectionPredicate.encryptedStale(now - ProjectionPredicate.STALE_ENCRYPTED_MS - 1, now))
    }
}
