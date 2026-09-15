package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectionPredicateTest {

    private val now = 1_000_000_000_000L
    private val own = "@me:example.org"
    private val other = "@other:example.org"

    private fun renders(
        originTs: Long = now - 1_000,
        messageClass: Boolean = true,
        isReplaceEdit: Boolean = false,
        isEncrypted: Boolean = false,
        decryptedOk: Boolean = true,
    ): Boolean = ProjectionPredicate.renders(
        messageClass = messageClass,
        isReplaceEdit = isReplaceEdit,
        originTs = originTs,
        now = now,
        isEncrypted = isEncrypted,
        decryptedOk = decryptedOk,
    )

    private fun unread(
        sender: String = other,
        originTs: Long = now - 1_000,
        messageClass: Boolean = true,
        isReplaceEdit: Boolean = false,
        isEncrypted: Boolean = false,
        decryptedOk: Boolean = true,
    ): Boolean = ProjectionPredicate.countsAsUnread(
        messageClass = messageClass,
        isReplaceEdit = isReplaceEdit,
        sender = sender,
        ownUserId = own,
        originTs = originTs,
        now = now,
        isEncrypted = isEncrypted,
        decryptedOk = decryptedOk,
    )

    // --- head (renders): own messages INCLUDED ---

    @Test
    fun `plain text message renders`() {
        assertTrue(renders())
    }

    @Test
    fun `own sender renders (advances head, never unread)`() {
        assertTrue(renders())
        assertFalse(unread(sender = own))
    }

    @Test
    fun `non message class (reactions acks state) never renders`() {
        assertFalse(renders(messageClass = false))
    }

    @Test
    fun `m replace edit never renders`() {
        assertFalse(renders(isReplaceEdit = true))
    }

    @Test
    fun `future stamped beyond skew window never renders`() {
        assertFalse(renders(originTs = now + ProjectionPredicate.FUTURE_SKEW_MS + 1))
    }

    @Test
    fun `future stamped within skew window renders`() {
        assertTrue(renders(originTs = now + 60_000))
    }

    @Test
    fun `encrypted that decrypted renders`() {
        assertTrue(renders(isEncrypted = true, decryptedOk = true))
    }

    @Test
    fun `encrypted not decrypted (pending or failed) does not render`() {
        assertFalse(renders(isEncrypted = true, decryptedOk = false))
    }

    @Test
    fun `plain message outside future window renders regardless of encrypted window`() {
        // sanity: the stale-encrypted rule must not touch plaintext events
        assertTrue(renders(originTs = now - ProjectionPredicate.STALE_ENCRYPTED_MS - 1))
    }

    // --- unread (countsAsUnread) ---

    @Test
    fun `other sender counts as unread`() {
        assertTrue(unread())
    }

    @Test
    fun `unread rules mirror renders for non-own senders`() {
        assertFalse(unread(messageClass = false))
        assertFalse(unread(isReplaceEdit = true))
        assertFalse(unread(originTs = now + ProjectionPredicate.FUTURE_SKEW_MS + 1))
        assertFalse(unread(isEncrypted = true, decryptedOk = false))
        assertTrue(unread(isEncrypted = true, decryptedOk = true))
    }

    @Test
    fun `encryptedStale true only past the junk window`() {
        assertEquals(false, ProjectionPredicate.encryptedStale(now - 1_000, now))
        assertEquals(false, ProjectionPredicate.encryptedStale(now - ProjectionPredicate.STALE_ENCRYPTED_MS, now))
        assertEquals(true, ProjectionPredicate.encryptedStale(now - ProjectionPredicate.STALE_ENCRYPTED_MS - 1, now))
    }
}
