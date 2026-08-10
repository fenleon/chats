package com.lightphone.chats

import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod

/**
 * Thin RPC client for the Chats companion methods. Everything privileged — the
 * persistent Matrix connection, storage, notifications — lives in the
 * companion (:server); the tool only renders state fetched over the SDK binder.
 */
object ChatClient {

    /** Round-trips a binder call to the companion; true when the call succeeds. */
    suspend fun ping(): Boolean =
        callRemoteServiceMethod(LightServiceMethod.ChatPing, Unit) is LightResult.Success
}
