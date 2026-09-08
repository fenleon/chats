package com.lightphone.chats

import com.lightphone.chats.server.MatrixRepository
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull

/**
 * The app-side API over [MatrixRepository]. Chats is single-APK/single-process
 * (2026-08-19), so the UI calls the repository directly — no binder round
 * trip, no serialized dispatch (NO-SEAM, 2026-09-07). The [LightServiceMethod]
 * handlers in :server stay compiled for the vetted-tools contract (MainActivity
 * adb control, emulator pipeline); only the activity-launching flows and the
 * SDK-level volume read still ride the binder here.
 */
object ChatClient {

    suspend fun setAccount(
        homeserver: String,
        user: String,
        passwordOrToken: String,
        tokenLogin: Boolean = false,
    ): LightServiceMethod.SetAccount.Response? =
        runCatching {
            MatrixRepository.loginAsUnit(homeserver, user, passwordOrToken, tokenLogin).getOrThrow()
            LightServiceMethod.SetAccount.Response(
                userId = MatrixRepository.lastLoginUserId ?: "",
                deviceId = MatrixRepository.lastLoginDeviceId ?: "",
                needsVerification = MatrixRepository.lastLoginNeedsVerification,
            )
        }.getOrNull()

    /** Beeper login, step 1: emails a 6-digit code to [email]. @return null on success, else the error message. */
    suspend fun beeperRequestCode(email: String): String? =
        MatrixRepository.beeperRequestCode(email).exceptionOrNull()?.message

    /**
     * Beeper login, step 2: completes the login with the emailed [code]. The
     * response carries [LightServiceMethod.SetBeeperAccount.Response.needsVerification].
     * @return the response on success, else the error message.
     */
    suspend fun beeperLogin(email: String, code: String): Result<LightServiceMethod.SetBeeperAccount.Response> =
        MatrixRepository.beeperLoginAsUnit(email, code).mapCatching { _ ->
            LightServiceMethod.SetBeeperAccount.Response(
                userId = MatrixRepository.lastLoginUserId ?: "",
                deviceId = MatrixRepository.lastLoginDeviceId ?: "",
                needsVerification = MatrixRepository.lastLoginNeedsVerification,
            )
        }

    suspend fun accountState(): LightServiceMethod.GetAccountState.Response? =
        runCatching { MatrixRepository.accountState() }.getOrNull()

    suspend fun logout() {
        runCatching { MatrixRepository.logout() }
    }

    suspend fun getRooms(): List<LightServiceMethod.GetRooms.Room> =
        MatrixRepository.getRooms()

    /** Every room the repository knows (full census, trimmed rows — no preview
     *  or unread). The contacts list + search need any room, old or quiet. */
    suspend fun getAllRooms(): List<LightServiceMethod.GetRooms.Room> =
        MatrixRepository.getAllRooms()

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
        runCatching {
            val page = MatrixRepository.getMessages(roomId, beforeEventId, limit)
            LightServiceMethod.GetMessages.Response(
                messages = page.messages,
                hasMore = page.hasMore,
                encrypted = page.encrypted,
                audioPlayingEventId = MatrixRepository.audioPlayingEventId(),
                audioPositionMs = MatrixRepository.audioPositionMs(),
            )
        }.getOrNull()

    /**
     * Sends [body] to [roomId]. The response carries the outbox transaction id
     * plus the timeline event id once the homeserver acked (null until then) —
     * the thread uses it for an optimistic row the sync echo replaces.
     */
    suspend fun sendMessage(
        roomId: String,
        body: String,
    ): LightServiceMethod.SendMessage.Response? =
        runCatching { MatrixRepository.sendMessage(roomId, body, null) }.getOrNull()

    /**
     * Re-sends a locally-failed message: the repository clears the outbox error
     * on [transactionId] (the txn of the "local-…" row) and Trixnity re-sends
     * the same transaction — idempotent, no duplicate if it already landed.
     */
    suspend fun retrySend(roomId: String, transactionId: String) {
        runCatching { MatrixRepository.retrySend(roomId, transactionId) }
    }

    /**
     * Sends a reaction (an m.reaction annotation; [key] = the emoji) on a
     * message (Phase A, 2026-09-03). False when the repository rejected it.
     */
    suspend fun sendReaction(roomId: String, eventId: String, key: String): Boolean =
        runCatching { MatrixRepository.sendReaction(roomId, eventId, key) }.isSuccess

    /** Unsends (redacts) the signed-in user's reaction with [key] on [eventId]. */
    suspend fun unsendReaction(roomId: String, eventId: String, key: String): Boolean =
        runCatching { MatrixRepository.unsendReaction(roomId, eventId, key) }.getOrDefault(false)

    /**
     * Edits an own text message (Phase C, 2026-09-03). Returns null on
     * success, the failure message otherwise (the composer displays it — a
     * rejected edit must not read as an eternal "sending", LP3 2026-09-04).
     */
    suspend fun editMessage(roomId: String, eventId: String, newBody: String): String? {
        val failure = runCatching { MatrixRepository.editMessage(roomId, eventId, newBody) }
            .exceptionOrNull() ?: return null
        return failure.message ?: "edit failed"
    }

    /** Unsends (redacts) an own message — removed for everyone the bridge can
     *  reach (Phase C, 2026-09-03). False when the repository rejected it. */
    suspend fun unsendMessage(roomId: String, eventId: String): Boolean =
        runCatching { MatrixRepository.unsendMessage(roomId, eventId) }.isSuccess

    suspend fun markRead(roomId: String, eventId: String) {
        runCatching { MatrixRepository.markRead(roomId, eventId) }
    }

    suspend fun setTyping(roomId: String, active: Boolean) {
        runCatching { MatrixRepository.setTyping(roomId, active) }
    }

    /** Mutes/unmutes [roomId]'s notifications (contact panel, 2026-08-23). */
    suspend fun setRoomMuted(roomId: String, muted: Boolean) {
        runCatching { MatrixRepository.setRoomMuted(roomId, muted) }
    }

    /** Pins/unpins [roomId] (m.favourite tag, synced; contact panel, 2026-08-28). */
    suspend fun setRoomPinned(roomId: String, pinned: Boolean) {
        runCatching { MatrixRepository.setRoomPinned(roomId, pinned) }
    }

    /** Archives/unarchives [roomId] (Beeper inbox.done, synced; contact panel, 2026-08-28). */
    suspend fun setRoomArchived(roomId: String, archived: Boolean) {
        runCatching { MatrixRepository.setRoomArchived(roomId, archived) }
    }

    /** Tells the repository which room is on screen (null = list/settings/background). */
    suspend fun setActiveRoom(roomId: String?) {
        runCatching { MatrixRepository.setActiveRoom(roomId) }
    }

    suspend fun connectionState(): LightServiceMethod.GetConnectionState.Response? =
        runCatching { MatrixRepository.connectionState() }.getOrNull()

    /** Pauses/resumes the sync loop (Settings → Sync, audit 2026-08-14). */
    suspend fun setSyncEnabled(enabled: Boolean): Boolean =
        runCatching { MatrixRepository.setSyncEnabled(enabled) }.isSuccess

    suspend fun e2eeState(): LightServiceMethod.GetE2eeState.Response? =
        runCatching { MatrixRepository.e2eeState() }.getOrNull()

    suspend fun startDeviceVerification(): LightServiceMethod.StartDeviceVerification.Response? =
        MatrixRepository.startDeviceVerification().fold(
            onSuccess = { LightServiceMethod.StartDeviceVerification.Response(started = true) },
            onFailure = {
                LightServiceMethod.StartDeviceVerification.Response(started = false, error = it.message)
            },
        )

    suspend fun verificationState(): LightServiceMethod.GetVerificationState.Response? =
        runCatching { MatrixRepository.verificationState() }.getOrNull()

    suspend fun verifyAction(action: String): String? =
        MatrixRepository.verifyAction(action).fold(
            onSuccess = { null },
            onFailure = { it.message ?: "verification failed" },
        )

    /** Non-interactive verification with the account's recovery key. @return null on success, else the error. */
    suspend fun recoverWithKey(recoveryKey: String): String? =
        MatrixRepository.recoverWithKey(recoveryKey).fold(
            onSuccess = { null },
            onFailure = { it.message ?: "recovery failed" },
        )

    /**
     * Starts the attach-a-photo flow for [roomId]. @return the flattened
     * component name of the photo-picker activity, which the tool
     * launches via `SimpleLightScreen.startServerActivity` (the tool runtime
     * forbids startActivity). Stays on the binder: the SDK's server-side
     * activity flow owns the launch (NO-SEAM keeps this one).
     */
    suspend fun startPhotoSend(roomId: String): String? =
        callRemoteServiceMethod(
            LightServiceMethod.StartPhotoSend,
            LightServiceMethod.StartPhotoSend.Request(roomId),
        ).getOrNull()?.activityComponent

    /**
     * Display-ready JPEG bytes for an image message, or null when unavailable.
     * [allowMobileData] false + a cellular connection = the download is
     * skipped (Settings → Mobile data downloads).
     */
    suspend fun getMessageMedia(
        roomId: String,
        eventId: String,
        allowMobileData: Boolean,
    ): ByteArray? =
        runCatching { MatrixRepository.getMessageMedia(roomId, eventId, allowMobileData) }.getOrNull()

    /** Saves an image message to the device's Pictures/Chats album
     *  (photo viewer save button, 2026-09-03). */
    suspend fun saveMessageImage(roomId: String, eventId: String): Boolean =
        runCatching { MatrixRepository.saveMessageImage(roomId, eventId) }.getOrDefault(false)

    /**
     * Toggles voice-note playback: plays [eventId], or stops
     * it if it is already the one playing. @return (nowPlaying, error) — the
     * row surfaces a fetch/playback failure instead of a silent
     * no-op (feedback 2026-08-19).
     */
    suspend fun playVoiceNote(roomId: String, eventId: String): Pair<Boolean, String?> =
        runCatching { MatrixRepository.playVoiceNote(roomId, eventId) }
            .getOrElse { false to (it.message ?: "playback failed") }

    /**
     * Media volume (level, max) for the in-app volume panel (feedback
     * 2026-08-30): the SDK server answers GetVolumeLevel from the platform.
     * Stays on the binder — this is an SDK-level method routed through the
     * server's customServiceMethodResolver (NO-SEAM keeps this one).
     */
    suspend fun volumeLevel(): Pair<Int, Int>? =
        callRemoteServiceMethod(LightServiceMethod.GetVolumeLevel, Unit)
            .getOrNull()?.let { it.level to it.max }

    /**
     * Starts the record-a-voice-note flow for [roomId]. @return the flattened
     * component name of the recording activity, which the tool
     * launches via `SimpleLightScreen.startServerActivity`.
     */
    suspend fun startVoiceNoteSend(roomId: String): String? =
        callRemoteServiceMethod(
            LightServiceMethod.StartVoiceNoteSend,
            LightServiceMethod.StartVoiceNoteSend.Request(roomId),
        ).getOrNull()?.activityComponent

    /**
     * Long-poll wait for the status revision (Phase C, 2026-09-06): the
     * repository bumps it wherever a connection-state or verification-state
     * fact commits. The Account/Verification/Settings screens refetch their
     * status on movement instead of polling. (The list/thread/screens' room
     * and page loops died with the NO-SEAM flows — this is the one wait left.)
     */
    suspend fun waitForStatusChange(lastSeen: Long, timeoutMs: Long = 25_000): Long =
        callRemoteServiceMethod(
            LightServiceMethod.WaitForChange,
            LightServiceMethod.WaitForChange.Request("status", null, lastSeen, timeoutMs),
        ).getOrNull()?.revision ?: lastSeen
}
