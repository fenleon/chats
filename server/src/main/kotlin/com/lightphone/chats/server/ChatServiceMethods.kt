package com.lightphone.chats.server

import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.runBlocking

/**
 * Implements the Chats methods on the companion's LightSdkService. These are
 * the server-side half of the tool model: the tool is a thin UI that calls
 * these over the SDK binder; the Matrix connection, storage, and sync live in
 * [MatrixRepository] / [ChatSyncService].
 *
 * The resolver runs on a binder thread; the Matrix calls are suspend, so each
 * is awaited with [runBlocking] (same pattern as the Audiobooks scan).
 */
object ChatServiceMethods {

    fun dispatch(methodId: String, payload: String?): LightResult<String> =
        try {
            when (methodId) {
                LightServiceMethod.ChatPing.id ->
                    LightResult.Success(LightServiceMethod.ChatPing.encodeResponse(Unit))

                LightServiceMethod.SetAccount.id -> setAccount(payload!!)

                LightServiceMethod.BeeperRequestCode.id -> {
                    val request = LightServiceMethod.BeeperRequestCode.decodeRequest(payload!!)
                    val result = runBlocking { MatrixRepository.beeperRequestCode(request.email) }
                    result.fold(
                        onSuccess = {
                            LightResult.Success(LightServiceMethod.BeeperRequestCode.encodeResponse(Unit))
                        },
                        onFailure = { error ->
                            LightResult.Error(
                                LightResult.ErrorCode.Unknown,
                                error.message ?: "code request failed",
                            )
                        },
                    )
                }

                LightServiceMethod.SetBeeperAccount.id -> setBeeperAccount(payload!!)

                LightServiceMethod.GetAccountState.id -> {
                    val response = MatrixRepository.accountState()
                    LightResult.Success(LightServiceMethod.GetAccountState.encodeResponse(response))
                }

                LightServiceMethod.Logout.id -> {
                    runBlocking { MatrixRepository.logout() }
                    LightResult.Success(LightServiceMethod.Logout.encodeResponse(Unit))
                }

                LightServiceMethod.GetRooms.id -> {
                    val rooms = runBlocking { MatrixRepository.getRooms() }
                    val response = LightServiceMethod.GetRooms.Response(rooms)
                    LightResult.Success(LightServiceMethod.GetRooms.encodeResponse(response))
                }

                LightServiceMethod.GetAllRooms.id -> {
                    val rooms = runBlocking { MatrixRepository.getAllRooms() }
                    val response = LightServiceMethod.GetRooms.Response(rooms)
                    LightResult.Success(LightServiceMethod.GetRooms.encodeResponse(response))
                }

                LightServiceMethod.GetMessages.id -> {
                    val request = LightServiceMethod.GetMessages.decodeRequest(payload!!)
                    val page = runBlocking {
                        MatrixRepository.getMessages(request.roomId, request.beforeEventId, request.limit)
                    }
                    val response = LightServiceMethod.GetMessages.Response(
                        messages = page.messages,
                        hasMore = page.hasMore,
                        encrypted = page.encrypted,
                        audioPlayingEventId = MatrixRepository.audioPlayingEventId(),
                        audioPositionMs = MatrixRepository.audioPositionMs(),
                    )
                    LightResult.Success(LightServiceMethod.GetMessages.encodeResponse(response))
                }

                LightServiceMethod.SendMessage.id -> {
                    val request = LightServiceMethod.SendMessage.decodeRequest(payload!!)
                    val response = runBlocking {
                        MatrixRepository.sendMessage(request.roomId, request.body, request.replyToEventId)
                    }
                    LightResult.Success(LightServiceMethod.SendMessage.encodeResponse(response))
                }

                LightServiceMethod.RetrySend.id -> {
                    val request = LightServiceMethod.RetrySend.decodeRequest(payload!!)
                    runBlocking {
                        MatrixRepository.retrySend(request.roomId, request.transactionId)
                    }
                    LightResult.Success(LightServiceMethod.RetrySend.encodeResponse(Unit))
                }

                LightServiceMethod.MarkRead.id -> {
                    val request = LightServiceMethod.MarkRead.decodeRequest(payload!!)
                    runBlocking { MatrixRepository.markRead(request.roomId, request.eventId) }
                    LightResult.Success(LightServiceMethod.MarkRead.encodeResponse(Unit))
                }

                LightServiceMethod.SetTyping.id -> {
                    val request = LightServiceMethod.SetTyping.decodeRequest(payload!!)
                    runBlocking { MatrixRepository.setTyping(request.roomId, request.active) }
                    LightResult.Success(LightServiceMethod.SetTyping.encodeResponse(Unit))
                }

                LightServiceMethod.SetRoomMuted.id -> {
                    val request = LightServiceMethod.SetRoomMuted.decodeRequest(payload!!)
                    runBlocking { MatrixRepository.setRoomMuted(request.roomId, request.muted) }
                    LightResult.Success(LightServiceMethod.SetRoomMuted.encodeResponse(Unit))
                }

                LightServiceMethod.SetRoomPinned.id -> {
                    val request = LightServiceMethod.SetRoomPinned.decodeRequest(payload!!)
                    runBlocking { MatrixRepository.setRoomPinned(request.roomId, request.pinned) }
                    LightResult.Success(LightServiceMethod.SetRoomPinned.encodeResponse(Unit))
                }

                LightServiceMethod.GetRoomFlags.id -> {
                    val request = LightServiceMethod.GetRoomFlags.decodeRequest(payload!!)
                    val flags = runBlocking { MatrixRepository.getRoomFlags(request.roomId) }
                    LightResult.Success(
                        LightServiceMethod.GetRoomFlags.encodeResponse(
                            LightServiceMethod.GetRoomFlags.Response(
                                pinned = flags.pinned,
                                muted = flags.muted,
                                archived = flags.archived,
                            ),
                        ),
                    )
                }

                LightServiceMethod.SetRoomArchived.id -> {
                    val request = LightServiceMethod.SetRoomArchived.decodeRequest(payload!!)
                    runBlocking { MatrixRepository.setRoomArchived(request.roomId, request.archived) }
                    LightResult.Success(LightServiceMethod.SetRoomArchived.encodeResponse(Unit))
                }

                LightServiceMethod.GetConnectionState.id -> {
                    val response = MatrixRepository.connectionState()
                    LightResult.Success(LightServiceMethod.GetConnectionState.encodeResponse(response))
                }

                LightServiceMethod.GetE2eeState.id -> {
                    val response = runBlocking { MatrixRepository.e2eeState() }
                    LightResult.Success(LightServiceMethod.GetE2eeState.encodeResponse(response))
                }

                LightServiceMethod.StartDeviceVerification.id -> {
                    val result = runBlocking { MatrixRepository.startDeviceVerification() }
                    result.fold(
                        onSuccess = {
                            LightResult.Success(
                                LightServiceMethod.StartDeviceVerification.encodeResponse(
                                    LightServiceMethod.StartDeviceVerification.Response(started = true),
                                ),
                            )
                        },
                        onFailure = { error ->
                            LightResult.Success(
                                LightServiceMethod.StartDeviceVerification.encodeResponse(
                                    LightServiceMethod.StartDeviceVerification.Response(
                                        started = false,
                                        error = error.message,
                                    ),
                                ),
                            )
                        },
                    )
                }

                LightServiceMethod.GetVerificationState.id -> {
                    val response = MatrixRepository.verificationState()
                    LightResult.Success(LightServiceMethod.GetVerificationState.encodeResponse(response))
                }

                LightServiceMethod.SetActiveRoom.id -> {
                    val request = LightServiceMethod.SetActiveRoom.decodeRequest(payload!!)
                    MatrixRepository.setActiveRoom(request.roomId)
                    LightResult.Success(LightServiceMethod.SetActiveRoom.encodeResponse(Unit))
                }

                LightServiceMethod.TakeNotifyRoom.id -> {
                    val response = LightServiceMethod.TakeNotifyRoom.Response(
                        MatrixRepository.takeNotifyRoom(),
                    )
                    LightResult.Success(LightServiceMethod.TakeNotifyRoom.encodeResponse(response))
                }

                LightServiceMethod.VerifyAction.id -> {
                    val request = LightServiceMethod.VerifyAction.decodeRequest(payload!!)
                    val result = runBlocking { MatrixRepository.verifyAction(request.action) }
                    val response = result.fold(
                        onSuccess = { LightServiceMethod.VerifyAction.Response(ok = true) },
                        onFailure = { error ->
                            LightServiceMethod.VerifyAction.Response(ok = false, error = error.message)
                        },
                    )
                    LightResult.Success(LightServiceMethod.VerifyAction.encodeResponse(response))
                }

                LightServiceMethod.RecoverWithKey.id -> {
                    val request = LightServiceMethod.RecoverWithKey.decodeRequest(payload!!)
                    val result = runBlocking { MatrixRepository.recoverWithKey(request.recoveryKey) }
                    val response = result.fold(
                        onSuccess = { LightServiceMethod.RecoverWithKey.Response(ok = true) },
                        onFailure = { error ->
                            LightServiceMethod.RecoverWithKey.Response(ok = false, error = error.message)
                        },
                    )
                    LightResult.Success(LightServiceMethod.RecoverWithKey.encodeResponse(response))
                }

                LightServiceMethod.StartPhotoSend.id -> {
                    val request = LightServiceMethod.StartPhotoSend.decodeRequest(payload!!)
                    val response = LightServiceMethod.StartPhotoSend.Response(
                        MatrixRepository.startPhotoSend(request.roomId),
                    )
                    LightResult.Success(LightServiceMethod.StartPhotoSend.encodeResponse(response))
                }

                LightServiceMethod.GetMessageMedia.id -> {
                    val request = LightServiceMethod.GetMessageMedia.decodeRequest(payload!!)
                    val bytes = runBlocking {
                        MatrixRepository.getMessageMedia(
                            request.roomId,
                            request.eventId,
                            request.allowMobileData,
                        )
                    }
                    LightResult.Success(
                        LightServiceMethod.GetMessageMedia.encodeResponse(
                            LightServiceMethod.GetMessageMedia.Response(bytes),
                        ),
                    )
                }

                LightServiceMethod.PlayVoiceNote.id -> {
                    val request = LightServiceMethod.PlayVoiceNote.decodeRequest(payload!!)
                    val (playing, error) = runBlocking {
                        MatrixRepository.playVoiceNote(request.roomId, request.eventId)
                    }
                    LightResult.Success(
                        LightServiceMethod.PlayVoiceNote.encodeResponse(
                            LightServiceMethod.PlayVoiceNote.Response(playing, error),
                        ),
                    )
                }

                LightServiceMethod.StartVoiceNoteSend.id -> {
                    val request = LightServiceMethod.StartVoiceNoteSend.decodeRequest(payload!!)
                    val response = LightServiceMethod.StartVoiceNoteSend.Response(
                        MatrixRepository.startVoiceNoteSend(request.roomId),
                    )
                    LightResult.Success(LightServiceMethod.StartVoiceNoteSend.encodeResponse(response))
                }

                LightServiceMethod.SetSyncEnabled.id -> {
                    val request = LightServiceMethod.SetSyncEnabled.decodeRequest(payload!!)
                    runBlocking { MatrixRepository.setSyncEnabled(request.enabled) }
                    LightResult.Success(
                        LightServiceMethod.SetSyncEnabled.encodeResponse(
                            LightServiceMethod.SetSyncEnabled.Response(ok = true),
                        ),
                    )
                }

                else -> LightResult.Error(
                    LightResult.ErrorCode.Unknown,
                    "unknown method: $methodId",
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatServiceMethods", "dispatch failed for $methodId", e)
            LightResult.Error(LightResult.ErrorCode.Unknown, e.message ?: "error handling $methodId")
        }

    private fun setAccount(payload: String): LightResult<String> {
        val request = LightServiceMethod.SetAccount.decodeRequest(payload)
        val result = runBlocking {
            MatrixRepository.login(
                homeserver = request.homeserver,
                user = request.user,
                passwordOrToken = request.passwordOrToken,
                tokenLogin = request.tokenLogin,
            )
        }
        return result.fold(
            onSuccess = { client ->
                val response = LightServiceMethod.SetAccount.Response(
                    userId = client.userId.full,
                    deviceId = client.deviceId,
                )
                LightResult.Success(LightServiceMethod.SetAccount.encodeResponse(response))
            },
            onFailure = { error ->
                LightResult.Error(
                    LightResult.ErrorCode.Unknown,
                    error.message ?: "login failed",
                )
            },
        )
    }

    private fun setBeeperAccount(payload: String): LightResult<String> {
        val request = LightServiceMethod.SetBeeperAccount.decodeRequest(payload)
        val result = runBlocking {
            MatrixRepository.beeperLogin(email = request.email, code = request.code)
        }
        return result.fold(
            onSuccess = { client ->
                val response = LightServiceMethod.SetBeeperAccount.Response(
                    userId = client.userId.full,
                    deviceId = client.deviceId,
                )
                LightResult.Success(LightServiceMethod.SetBeeperAccount.encodeResponse(response))
            },
            onFailure = { error ->
                LightResult.Error(
                    LightResult.ErrorCode.Unknown,
                    error.message ?: "beeper login failed",
                )
            },
        )
    }
}
