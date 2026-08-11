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

    suspend fun getMessages(
        roomId: String,
        beforeEventId: String? = null,
        limit: Int = 30,
    ): List<LightServiceMethod.GetMessages.Message> =
        callRemoteServiceMethod(
            LightServiceMethod.GetMessages,
            LightServiceMethod.GetMessages.Request(roomId, beforeEventId, limit),
        ).getOrNull()?.messages.orEmpty()

    suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToEventId: String? = null,
    ): Boolean = callRemoteServiceMethod(
        LightServiceMethod.SendMessage,
        LightServiceMethod.SendMessage.Request(roomId, body, replyToEventId),
    ) is LightResult.Success

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

    /** Tells the companion which room is on screen (null = list/settings/background). */
    suspend fun setActiveRoom(roomId: String?) {
        callRemoteServiceMethod(
            LightServiceMethod.SetActiveRoom,
            LightServiceMethod.SetActiveRoom.Request(roomId),
        )
    }

    /**
     * One-shot: the room a posted notification belongs to (so a notification
     * tap can open the right thread); null when there is none pending.
     */
    suspend fun takeNotifyRoom(): String? =
        callRemoteServiceMethod(LightServiceMethod.TakeNotifyRoom, Unit)
            .getOrNull()?.roomId

    suspend fun connectionState(): LightServiceMethod.GetConnectionState.Response? =
        callRemoteServiceMethod(LightServiceMethod.GetConnectionState, Unit).getOrNull()

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
}
