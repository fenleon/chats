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

                LightServiceMethod.SetBeeperAccount.id -> setBeeperAccount(payload!!)

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

                LightServiceMethod.GetAccountState.id ->
                    handleNoRequest(LightServiceMethod.GetAccountState) {
                        MatrixRepository.accountState()
                    }

                LightServiceMethod.Logout.id -> handleNoRequest(LightServiceMethod.Logout) {
                    runBlocking { MatrixRepository.logout() }
                }

                LightServiceMethod.GetRooms.id -> handleNoRequest(LightServiceMethod.GetRooms) {
                    LightServiceMethod.GetRooms.Response(runBlocking { MatrixRepository.getRooms() })
                }

                LightServiceMethod.GetAllRooms.id -> handleNoRequest(LightServiceMethod.GetAllRooms) {
                    LightServiceMethod.GetRooms.Response(runBlocking { MatrixRepository.getAllRooms() })
                }

                LightServiceMethod.GetMessages.id -> handle(LightServiceMethod.GetMessages, payload) { request ->
                    val page = runBlocking {
                        MatrixRepository.getMessages(request.roomId, request.beforeEventId, request.limit)
                    }
                    LightServiceMethod.GetMessages.Response(
                        messages = page.messages,
                        hasMore = page.hasMore,
                        encrypted = page.encrypted,
                        audioPlayingEventId = MatrixRepository.audioPlayingEventId(),
                        audioPositionMs = MatrixRepository.audioPositionMs(),
                    )
                }

                LightServiceMethod.SendMessage.id -> handle(LightServiceMethod.SendMessage, payload) { request ->
                    runBlocking {
                        MatrixRepository.sendMessage(request.roomId, request.body, request.replyToEventId)
                    }
                }

                LightServiceMethod.SendReaction.id -> handle(LightServiceMethod.SendReaction, payload) { request ->
                    runBlocking {
                        MatrixRepository.sendReaction(request.roomId, request.eventId, request.key)
                    }
                }

                LightServiceMethod.UnsendReaction.id -> handle(LightServiceMethod.UnsendReaction, payload) { request ->
                    runBlocking {
                        MatrixRepository.unsendReaction(request.roomId, request.eventId, request.key)
                    }
                }

                LightServiceMethod.EditMessage.id -> handle(LightServiceMethod.EditMessage, payload) { request ->
                    runBlocking {
                        MatrixRepository.editMessage(request.roomId, request.eventId, request.newBody)
                    }
                }

                LightServiceMethod.UnsendMessage.id -> handle(LightServiceMethod.UnsendMessage, payload) { request ->
                    runBlocking {
                        MatrixRepository.unsendMessage(request.roomId, request.eventId)
                    }
                }

                LightServiceMethod.RetrySend.id -> handle(LightServiceMethod.RetrySend, payload) { request ->
                    runBlocking {
                        MatrixRepository.retrySend(request.roomId, request.transactionId)
                    }
                }

                LightServiceMethod.MarkRead.id -> handle(LightServiceMethod.MarkRead, payload) { request ->
                    runBlocking { MatrixRepository.markRead(request.roomId, request.eventId) }
                }

                LightServiceMethod.SetTyping.id -> handle(LightServiceMethod.SetTyping, payload) { request ->
                    runBlocking { MatrixRepository.setTyping(request.roomId, request.active) }
                }

                LightServiceMethod.SetRoomMuted.id -> handle(LightServiceMethod.SetRoomMuted, payload) { request ->
                    runBlocking { MatrixRepository.setRoomMuted(request.roomId, request.muted) }
                }

                LightServiceMethod.SetRoomPinned.id -> handle(LightServiceMethod.SetRoomPinned, payload) { request ->
                    runBlocking { MatrixRepository.setRoomPinned(request.roomId, request.pinned) }
                }

                LightServiceMethod.GetRoomFlags.id -> handle(LightServiceMethod.GetRoomFlags, payload) { request ->
                    val flags = runBlocking { MatrixRepository.getRoomFlags(request.roomId) }
                    LightServiceMethod.GetRoomFlags.Response(
                        pinned = flags.pinned,
                        muted = flags.muted,
                        archived = flags.archived,
                    )
                }

                LightServiceMethod.SetRoomArchived.id -> handle(LightServiceMethod.SetRoomArchived, payload) { request ->
                    runBlocking { MatrixRepository.setRoomArchived(request.roomId, request.archived) }
                }

                LightServiceMethod.GetConnectionState.id ->
                    handleNoRequest(LightServiceMethod.GetConnectionState) {
                        MatrixRepository.connectionState()
                    }

                LightServiceMethod.GetE2eeState.id -> handleNoRequest(LightServiceMethod.GetE2eeState) {
                    runBlocking { MatrixRepository.e2eeState() }
                }

                LightServiceMethod.StartDeviceVerification.id ->
                    handleNoRequest(LightServiceMethod.StartDeviceVerification) {
                        runBlocking { MatrixRepository.startDeviceVerification() }.fold(
                            onSuccess = {
                                LightServiceMethod.StartDeviceVerification.Response(started = true)
                            },
                            onFailure = { error ->
                                LightServiceMethod.StartDeviceVerification.Response(
                                    started = false,
                                    error = error.message,
                                )
                            },
                        )
                    }

                LightServiceMethod.GetVerificationState.id ->
                    handleNoRequest(LightServiceMethod.GetVerificationState) {
                        MatrixRepository.verificationState()
                    }

                LightServiceMethod.SetActiveRoom.id -> handle(LightServiceMethod.SetActiveRoom, payload) { request ->
                    MatrixRepository.setActiveRoom(request.roomId)
                }

                LightServiceMethod.TakeNotifyRoom.id -> handleNoRequest(LightServiceMethod.TakeNotifyRoom) {
                    LightServiceMethod.TakeNotifyRoom.Response(
                        MatrixRepository.takeNotifyRoom(),
                    )
                }

                LightServiceMethod.VerifyAction.id -> handle(LightServiceMethod.VerifyAction, payload) { request ->
                    runBlocking { MatrixRepository.verifyAction(request.action) }.fold(
                        onSuccess = { LightServiceMethod.VerifyAction.Response(ok = true) },
                        onFailure = { error ->
                            LightServiceMethod.VerifyAction.Response(ok = false, error = error.message)
                        },
                    )
                }

                LightServiceMethod.RecoverWithKey.id -> handle(LightServiceMethod.RecoverWithKey, payload) { request ->
                    runBlocking { MatrixRepository.recoverWithKey(request.recoveryKey) }.fold(
                        onSuccess = { LightServiceMethod.RecoverWithKey.Response(ok = true) },
                        onFailure = { error ->
                            LightServiceMethod.RecoverWithKey.Response(ok = false, error = error.message)
                        },
                    )
                }

                LightServiceMethod.StartPhotoSend.id -> handle(LightServiceMethod.StartPhotoSend, payload) { request ->
                    LightServiceMethod.StartPhotoSend.Response(
                        MatrixRepository.startPhotoSend(request.roomId),
                    )
                }

                LightServiceMethod.GetMessageMedia.id -> handle(LightServiceMethod.GetMessageMedia, payload) { request ->
                    val bytes = runBlocking {
                        MatrixRepository.getMessageMedia(
                            request.roomId,
                            request.eventId,
                            request.allowMobileData,
                        )
                    }
                    LightServiceMethod.GetMessageMedia.Response(bytes)
                }

                LightServiceMethod.SaveMessageImage.id -> handle(LightServiceMethod.SaveMessageImage, payload) { request ->
                    LightServiceMethod.SaveMessageImage.Response(
                        runBlocking {
                            MatrixRepository.saveMessageImage(request.roomId, request.eventId)
                        },
                    )
                }

                LightServiceMethod.PlayVoiceNote.id -> handle(LightServiceMethod.PlayVoiceNote, payload) { request ->
                    runBlocking {
                        MatrixRepository.playVoiceNote(request.roomId, request.eventId)
                    }.let { (playing, error) ->
                        LightServiceMethod.PlayVoiceNote.Response(playing, error)
                    }
                }

                LightServiceMethod.StartVoiceNoteSend.id ->
                    handle(LightServiceMethod.StartVoiceNoteSend, payload) { request ->
                        LightServiceMethod.StartVoiceNoteSend.Response(
                            MatrixRepository.startVoiceNoteSend(request.roomId),
                        )
                    }

                // Media volume for the tool's in-app volume panel (feedback
                // 2026-08-30): the SDK routes GetVolumeLevel here via
                // customServiceMethodResolver.
                LightServiceMethod.GetVolumeLevel.id -> {
                    val response = MatrixRepository.mediaVolumeLevel()
                    if (response == null) {
                        LightResult.Error(LightResult.ErrorCode.Unknown, "volume unavailable")
                    } else {
                        LightResult.Success(LightServiceMethod.GetVolumeLevel.encodeResponse(response))
                    }
                }

                LightServiceMethod.SetSyncEnabled.id -> handle(LightServiceMethod.SetSyncEnabled, payload) { request ->
                    runBlocking { MatrixRepository.setSyncEnabled(request.enabled) }
                    LightServiceMethod.SetSyncEnabled.Response(ok = true)
                }

                LightServiceMethod.GetRoomListRevision.id ->
                    handleNoRequest(LightServiceMethod.GetRoomListRevision) {
                        LightServiceMethod.GetRoomListRevision.Response(
                            MatrixRepository.roomListRevision(),
                        )
                    }

                LightServiceMethod.GetMessagePageRevision.id ->
                    handle(LightServiceMethod.GetMessagePageRevision, payload) { request ->
                        LightServiceMethod.GetMessagePageRevision.Response(
                            MatrixRepository.messagePageRevision(request.roomId),
                        )
                    }

                LightServiceMethod.WaitForChange.id -> handle(LightServiceMethod.WaitForChange, payload) { request ->
                    val revision = runBlocking {
                        MatrixRepository.waitForChange(
                            request.watch, request.roomId, request.lastSeen, request.timeoutMs,
                        )
                    }
                    LightServiceMethod.WaitForChange.Response(revision)
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

    private inline fun <Req : Any, Resp : Any> handle(
        method: LightServiceMethod<Req, Resp>,
        payload: String?,
        block: (Req) -> Resp,
    ): LightResult<String> =
        LightResult.Success(method.encodeResponse(block(method.decodeRequest(payload!!))))

    private inline fun <Resp : Any> handleNoRequest(
        method: LightServiceMethod<Unit, Resp>,
        block: () -> Resp,
    ): LightResult<String> =
        LightResult.Success(method.encodeResponse(block()))

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
                    needsVerification = MatrixRepository.lastLoginNeedsVerification,
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
                    needsVerification = MatrixRepository.lastLoginNeedsVerification,
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
