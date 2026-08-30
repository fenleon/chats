package com.lightphone.chats

import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.error
import com.thelightphone.sdk.shared.getOrNull

/**
 * Thin RPC client for the Chats companion methods. Everything privileged — the
 * persistent Matrix connection, sync loop, storage, notifications — lives in
 * the companion (:server); the tool only renders state fetched over the SDK
 * binder.
 */
object ChatClient {

    /** Round-trips a binder call to the companion; true when the call succeeds. */
    suspend fun ping(): Boolean =
        callRemoteServiceMethod(LightServiceMethod.ChatPing, Unit) is LightResult.Success

    suspend fun setAccount(
        homeserver: String,
        user: String,
        passwordOrToken: String,
        tokenLogin: Boolean = false,
    ): LightServiceMethod.SetAccount.Response? =
        callRemoteServiceMethod(
            LightServiceMethod.SetAccount,
            LightServiceMethod.SetAccount.Request(homeserver, user, passwordOrToken, tokenLogin),
        ).getOrNull()

    /** Beeper login, step 1: emails a 6-digit code to [email]. @return null on success, else the companion's error message. */
    suspend fun beeperRequestCode(email: String): String? =
        callRemoteServiceMethod(
            LightServiceMethod.BeeperRequestCode,
            LightServiceMethod.BeeperRequestCode.Request(email),
        ).error?.extra

    /** Beeper login, step 2: completes the login with the emailed [code]. @return null on success, else the companion's error message. */
    suspend fun beeperLogin(email: String, code: String): String? =
        callRemoteServiceMethod(
            LightServiceMethod.SetBeeperAccount,
            LightServiceMethod.SetBeeperAccount.Request(email, code),
        ).error?.extra

    suspend fun accountState(): LightServiceMethod.GetAccountState.Response? =
        callRemoteServiceMethod(LightServiceMethod.GetAccountState, Unit).getOrNull()

    suspend fun logout() {
        callRemoteServiceMethod(LightServiceMethod.Logout, Unit)
    }

    suspend fun getRooms(): List<LightServiceMethod.GetRooms.Room> =
        callRemoteServiceMethod(LightServiceMethod.GetRooms, Unit)
            .getOrNull()?.rooms.orEmpty()

    /** Every room the companion knows (full census, trimmed rows — no preview
     *  or unread). The contacts list + search need any room, old or quiet. */
    suspend fun getAllRooms(): List<LightServiceMethod.GetRooms.Room> =
        callRemoteServiceMethod(LightServiceMethod.GetAllRooms, Unit)
            .getOrNull()?.rooms.orEmpty()

    /**
     * A page of messages, oldest first; [beforeEventId] pages further back.
     * The response carries [LightServiceMethod.GetMessages.Response.hasMore],
     * which the thread uses instead of page-size heuristics (a page of mostly
     * state events would otherwise end pagination early).
     */
    suspend fun getMessages(
        roomId: String,
        beforeEventId: String? = null,
        limit: Int = 30,
    ): LightServiceMethod.GetMessages.Response? =
        callRemoteServiceMethod(
            LightServiceMethod.GetMessages,
            LightServiceMethod.GetMessages.Request(roomId, beforeEventId, limit),
        ).getOrNull()

    /**
     * Sends [body] to [roomId]. The response carries the outbox transaction id
     * plus the timeline event id once the homeserver acked (null until then) —
     * the thread uses it for an optimistic row the sync echo replaces.
     */
    suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToEventId: String? = null,
    ): LightServiceMethod.SendMessage.Response? = callRemoteServiceMethod(
        LightServiceMethod.SendMessage,
        LightServiceMethod.SendMessage.Request(roomId, body, replyToEventId),
    ).getOrNull()

    /**
     * Re-sends a locally-failed message: the companion clears the outbox error
     * on [transactionId] (the txn of the "local-…" row) and Trixnity re-sends
     * the same transaction — idempotent, no duplicate if it already landed.
     */
    suspend fun retrySend(roomId: String, transactionId: String) {
        callRemoteServiceMethod(
            LightServiceMethod.RetrySend,
            LightServiceMethod.RetrySend.Request(roomId, transactionId),
        )
    }

    suspend fun markRead(roomId: String, eventId: String) {
        callRemoteServiceMethod(
            LightServiceMethod.MarkRead,
            LightServiceMethod.MarkRead.Request(roomId, eventId),
        )
    }

    suspend fun setTyping(roomId: String, active: Boolean) {
        callRemoteServiceMethod(
            LightServiceMethod.SetTyping,
            LightServiceMethod.SetTyping.Request(roomId, active),
        )
    }

    /** Mutes/unmutes [roomId]'s notifications (contact panel, 2026-08-23). */
    suspend fun setRoomMuted(roomId: String, muted: Boolean) {
        callRemoteServiceMethod(
            LightServiceMethod.SetRoomMuted,
            LightServiceMethod.SetRoomMuted.Request(roomId, muted),
        )
    }

    /** Pins/unpins [roomId] (m.favourite tag, synced; contact panel, 2026-08-28). */
    suspend fun setRoomPinned(roomId: String, pinned: Boolean) {
        callRemoteServiceMethod(
            LightServiceMethod.SetRoomPinned,
            LightServiceMethod.SetRoomPinned.Request(roomId, pinned),
        )
    }

    /** The room's current pinned/muted/archived flags (contact panel live poll). */
    suspend fun getRoomFlags(roomId: String): LightServiceMethod.GetRoomFlags.Response? =
        callRemoteServiceMethod(
            LightServiceMethod.GetRoomFlags,
            LightServiceMethod.GetRoomFlags.Request(roomId),
        ).getOrNull()

    /** Archives/unarchives [roomId] (Beeper inbox.done, synced; contact panel, 2026-08-28). */
    suspend fun setRoomArchived(roomId: String, archived: Boolean) {
        callRemoteServiceMethod(
            LightServiceMethod.SetRoomArchived,
            LightServiceMethod.SetRoomArchived.Request(roomId, archived),
        )
    }

    /** Tells the companion which room is on screen (null = list/settings/background). */
    suspend fun setActiveRoom(roomId: String?) {
        callRemoteServiceMethod(
            LightServiceMethod.SetActiveRoom,
            LightServiceMethod.SetActiveRoom.Request(roomId),
        )
    }

    suspend fun connectionState(): LightServiceMethod.GetConnectionState.Response? =
        callRemoteServiceMethod(LightServiceMethod.GetConnectionState, Unit).getOrNull()

    /** Pauses/resumes the companion's sync loop (Settings → Sync, audit 2026-08-14). */
    suspend fun setSyncEnabled(enabled: Boolean): Boolean =
        callRemoteServiceMethod(
            LightServiceMethod.SetSyncEnabled,
            LightServiceMethod.SetSyncEnabled.Request(enabled),
        ).getOrNull()?.ok == true

    suspend fun e2eeState(): LightServiceMethod.GetE2eeState.Response? =
        callRemoteServiceMethod(LightServiceMethod.GetE2eeState, Unit).getOrNull()

    suspend fun startDeviceVerification(): LightServiceMethod.StartDeviceVerification.Response? =
        callRemoteServiceMethod(LightServiceMethod.StartDeviceVerification, Unit).getOrNull()

    suspend fun verificationState(): LightServiceMethod.GetVerificationState.Response? =
        callRemoteServiceMethod(LightServiceMethod.GetVerificationState, Unit).getOrNull()

    suspend fun verifyAction(action: String): String? =
        when (val r = callRemoteServiceMethod(
            LightServiceMethod.VerifyAction,
            LightServiceMethod.VerifyAction.Request(action),
        )) {
            is LightResult.Success -> if (r.data.ok) null else r.data.error ?: "verification failed"
            is LightResult.Error -> r.extra ?: "verification action failed"
        }

    /** Non-interactive verification with the account's recovery key. @return null on success, else the error. */
    suspend fun recoverWithKey(recoveryKey: String): String? =
        when (val r = callRemoteServiceMethod(
            LightServiceMethod.RecoverWithKey,
            LightServiceMethod.RecoverWithKey.Request(recoveryKey),
        )) {
            is LightResult.Success -> if (r.data.ok) null else r.data.error ?: "recovery failed"
            is LightResult.Error -> r.extra ?: "recovery failed"
        }

    /**
     * Starts the attach-a-photo flow for [roomId]. @return the flattened
     * component name of the companion's photo-picker activity, which the tool
     * launches via `SimpleLightScreen.startServerActivity` (the tool runtime
     * forbids startActivity).
     */
    suspend fun startPhotoSend(roomId: String): String? =
        callRemoteServiceMethod(
            LightServiceMethod.StartPhotoSend,
            LightServiceMethod.StartPhotoSend.Request(roomId),
        ).getOrNull()?.activityComponent

    /**
     * Display-ready JPEG bytes for an image message, or null when unavailable.
     * [allowMobileData] false + a cellular connection = the companion skips
     * the download (Settings → Mobile data downloads).
     */
    suspend fun getMessageMedia(
        roomId: String,
        eventId: String,
        allowMobileData: Boolean,
    ): ByteArray? =
        callRemoteServiceMethod(
            LightServiceMethod.GetMessageMedia,
            LightServiceMethod.GetMessageMedia.Request(roomId, eventId, allowMobileData),
        ).getOrNull()?.bytes

    /**
     * Toggles voice-note playback in the companion: plays [eventId], or stops
     * it if it is already the one playing. @return (nowPlaying, error) — the
     * tool surfaces a fetch/playback failure on the row instead of a silent
     * no-op (feedback 2026-08-19).
     */
    suspend fun playVoiceNote(roomId: String, eventId: String): Pair<Boolean, String?> {
        val result = callRemoteServiceMethod(
            LightServiceMethod.PlayVoiceNote,
            LightServiceMethod.PlayVoiceNote.Request(roomId, eventId),
        )
        return when (result) {
            is LightResult.Success -> result.data.playing to result.data.error
            is LightResult.Error -> false to (result.extra ?: "playback failed")
        }
    }

    /**
     * Media volume (level, max) for the in-app volume panel (feedback
     * 2026-08-30): the SDK server answers GetVolumeLevel from the platform.
     */
    suspend fun volumeLevel(): Pair<Int, Int>? =
        callRemoteServiceMethod(LightServiceMethod.GetVolumeLevel, Unit)
            .getOrNull()?.let { it.level to it.max }

    /**
     * Starts the record-a-voice-note flow for [roomId]. @return the flattened
     * component name of the companion's recording activity, which the tool
     * launches via `SimpleLightScreen.startServerActivity`.
     */
    suspend fun startVoiceNoteSend(roomId: String): String? =
        callRemoteServiceMethod(
            LightServiceMethod.StartVoiceNoteSend,
            LightServiceMethod.StartVoiceNoteSend.Request(roomId),
        ).getOrNull()?.activityComponent
}
