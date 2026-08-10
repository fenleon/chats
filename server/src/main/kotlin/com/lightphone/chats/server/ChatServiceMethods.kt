package com.lightphone.chats.server

import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod

/**
 * Implements the Chats methods on the companion's LightSdkService. These are
 * the server-side half of the tool model: the tool is a thin UI that calls
 * these over the SDK binder; everything privileged lives here. Phase 2 adds
 * the Matrix connection (login/sync), room storage, and notifications.
 */
object ChatServiceMethods {

    fun dispatch(methodId: String, payload: String?): LightResult<String> =
        when (methodId) {
            LightServiceMethod.ChatPing.id -> LightResult.Success(
                LightServiceMethod.ChatPing.encodeResponse(Unit),
            )
            else -> LightResult.Error(
                LightResult.ErrorCode.Unknown,
                "unknown method: $methodId",
            )
        }
}
