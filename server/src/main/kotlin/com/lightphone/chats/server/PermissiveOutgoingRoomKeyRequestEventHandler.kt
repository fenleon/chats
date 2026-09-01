package com.lightphone.chats.server

import de.connect2x.trixnity.client.key.OutgoingRoomKeyRequestEventHandler
import de.connect2x.trixnity.client.store.AccountStore
import de.connect2x.trixnity.client.store.KeyStore
import de.connect2x.trixnity.client.store.OlmCryptoStore
import de.connect2x.trixnity.client.store.StoredRoomKeyRequest
import de.connect2x.trixnity.client.store.StoreTransactionManager
import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClient
import de.connect2x.trixnity.core.EventHandler
import de.connect2x.trixnity.core.UserInfo
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.ForwardedRoomKeyEventContent
import de.connect2x.trixnity.core.model.events.m.KeyRequestAction
import de.connect2x.trixnity.core.model.events.m.RoomKeyRequestEventContent
import de.connect2x.trixnity.core.model.keys.EncryptionAlgorithm
import de.connect2x.trixnity.core.unsubscribeOnCompletion
import de.connect2x.trixnity.crypto.core.SecureRandom
import de.connect2x.trixnity.crypto.driver.CryptoDriver
import de.connect2x.trixnity.crypto.invoke
import de.connect2x.trixnity.crypto.olm.DecryptedOlmEventContainer
import de.connect2x.trixnity.crypto.olm.OlmEventHandler
import de.connect2x.trixnity.crypto.olm.StoredInboundMegolmSession
import de.connect2x.trixnity.utils.nextString
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Replaces Trixnity's stock
 * [de.connect2x.trixnity.client.key.OutgoingRoomKeyRequestEventHandlerImpl] on
 * both sides of the room-key exchange:
 *
 * - **Requesting**: requests a megolm session from ANY device of our own user
 *   (the stock impl filters receivers to cross-signed devices; on accounts
 *   without cross-signing that set is empty, so a fresh-login or re-keyed
 *   device could never ask for the keys it needs to decrypt history).
 * - **Answering/importing**: accepts `m.forwarded_room_key` from ANY device of
 *   our own user (the stock impl drops keys from unverified senders — the
 *   bridge's own key shares never landed for the same reason).
 *
 * The stock impl still starts as an [EventHandler] (its 1-day stale-request
 * cleanup runs); only the interface lookup resolves to this class, and both
 * trust gates are dropped. The homeserver stamps to-device senders, so the
 * sender checks below only admit real devices of our own user.
 */
class PermissiveOutgoingRoomKeyRequestEventHandler(
    userInfo: UserInfo,
    private val api: MatrixClientServerApiClient,
    private val olmEventHandler: OlmEventHandler,
    private val accountStore: AccountStore,
    private val keyStore: KeyStore,
    private val olmCryptoStore: OlmCryptoStore,
    private val tm: StoreTransactionManager,
    private val clock: Clock,
    private val driver: CryptoDriver,
) : EventHandler, OutgoingRoomKeyRequestEventHandler {
    private val ownUserId = userInfo.userId
    private val ownDeviceId = userInfo.deviceId

    override fun startInCoroutineScope(scope: CoroutineScope) {
        olmEventHandler.subscribe(::handleForwardedRoomKey).unsubscribeOnCompletion(scope)
    }

    /** Imports a forwarded room key from any of our own devices (stock drops
     *  unverified senders). Mirrors the stock import: pickled session into the
     *  store, then cancel the now-satisfied pending requests. */
    private suspend fun handleForwardedRoomKey(event: DecryptedOlmEventContainer) {
        val content = event.decrypted.content
        if (event.decrypted.sender != ownUserId || content !is ForwardedRoomKeyEventContent) return
        android.util.Log.i("MatrixRepository", "importing forwarded room key for ${content.roomId} session ${content.sessionId}")
        val account = checkNotNull(accountStore.getAccount()) { "No account found" }
        val (firstKnownIndex, pickledSession) =
            try {
                driver.megolm.inboundGroupSession
                    .import(driver.megolm.exportedSessionKey(content.sessionKey))
                    .use { it.firstKnownIndex to it.pickle(driver.key.pickleKey(account.olmPickleKey)) }
            } catch (e: Exception) {
                android.util.Log.w("MatrixRepository", "could not import forwarded room key for ${content.sessionId}: ${e.message}")
                return
            }
        val newForwardingCurve25519KeyChain = content.forwardingKeyChain + event.encrypted.content.senderKey
        tm.writeTransaction {
            olmCryptoStore.updateInboundMegolmSession(content.sessionId, content.roomId) {
                if (it != null && it.firstKnownIndex <= firstKnownIndex) it
                else
                    StoredInboundMegolmSession(
                        senderKey = content.senderKey,
                        sessionId = content.sessionId,
                        roomId = content.roomId,
                        firstKnownIndex = firstKnownIndex.toLong(),
                        isTrusted = false,
                        hasBeenBackedUp = false,
                        senderSigningKey = content.senderClaimedKey,
                        forwardingCurve25519KeyChain = newForwardingCurve25519KeyChain,
                        pickled = pickledSession,
                    )
            }
        }
        keyStore.getAllRoomKeyRequests()
            .find { it.content.body?.roomId == content.roomId && it.content.body?.sessionId == content.sessionId }
            ?.let { stored ->
                val cancel = stored.content.copy(action = KeyRequestAction.REQUEST_CANCELLATION, body = null)
                runCatching {
                    api.user.sendToDevice(mapOf(ownUserId to stored.receiverDeviceIds.associateWith { cancel }))
                }
                tm.writeTransaction { keyStore.deleteRoomKeyRequest(stored.content.requestId) }
            }
    }

    override suspend fun requestRoomKeys(roomId: RoomId, sessionId: String) {
        if (keyStore.getAllRoomKeyRequests().none {
                it.content.body?.roomId == roomId && it.content.body?.sessionId == sessionId
            }
        ) {
            val receiverDeviceIds =
                keyStore.getDeviceKeys(ownUserId).first()
                    ?.filter { it.value.value.signed.deviceId != ownDeviceId }
                    ?.map { it.value.value.signed.deviceId }
                    ?.toSet()
            if (receiverDeviceIds.isNullOrEmpty()) {
                android.util.Log.d("MatrixRepository", "no other devices to request room keys from for session $sessionId")
                return
            }
            val requestId = SecureRandom.nextString(22)
            val request =
                RoomKeyRequestEventContent(
                    action = KeyRequestAction.REQUEST,
                    requestingDeviceId = ownDeviceId,
                    requestId = requestId,
                    body =
                        RoomKeyRequestEventContent.RequestedKeyInfo(
                            roomId = roomId,
                            sessionId = sessionId,
                            algorithm = EncryptionAlgorithm.Megolm,
                        ),
                )
            android.util.Log.d("MatrixRepository", "requesting room keys for $roomId session $sessionId from $receiverDeviceIds")
            api.user
                .sendToDevice(mapOf(ownUserId to receiverDeviceIds.associateWith { request }))
                .onSuccess {
                    tm.writeTransaction {
                        keyStore.addRoomKeyRequest(StoredRoomKeyRequest(request, receiverDeviceIds, clock.now()))
                    }
                }
                .onFailure { android.util.Log.w("MatrixRepository", "failed to send room key request: ${it.message}") }
        }
    }
}
