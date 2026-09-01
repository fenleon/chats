package com.lightphone.chats.server

import de.connect2x.trixnity.client.store.AccountStore
import de.connect2x.trixnity.client.store.OlmCryptoStore
import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClient
import de.connect2x.trixnity.core.subscribeEvent
import de.connect2x.trixnity.core.unsubscribeOnCompletion
import de.connect2x.trixnity.core.EventHandler
import de.connect2x.trixnity.core.UserInfo
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.m.ForwardedRoomKeyEventContent
import de.connect2x.trixnity.core.model.events.m.RoomKeyRequestEventContent
import de.connect2x.trixnity.core.model.keys.EncryptionAlgorithm
import de.connect2x.trixnity.crypto.driver.CryptoDriver
import de.connect2x.trixnity.crypto.olm.OlmEncryptionService
import de.connect2x.trixnity.crypto.olm.OlmEventHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Answers `m.room_key_request` events from ANY device of our own user,
 * verified or not. Trixnity's stock
 * [de.connect2x.trixnity.client.key.IncomingRoomKeyRequestEventHandler] drops
 * requests from unverified devices (`KeySignatureTrustLevel.isVerified` gate),
 * but this app never bootstraps cross-signing, so every device on our accounts
 * is unverified and requests would never be answered — Beeper's bridge keeps
 * reporting "the bridge hasn't received the decryption keys" while we hold the
 * session. The event-sender check stays: the homeserver stamps the to-device
 * sender, so only real devices of our own user can reach this path.
 *
 * Registered alongside the stock handler (which stays inert — nothing on our
 * accounts is cross-signed); the sender+decryption checks are the same, only
 * the trust gate is dropped. Requests are answered immediately instead of
 * being batched into the next sync round (the stock handler defers via its
 * per-sync loop); a key share is idempotent, so a racing cancel is harmless.
 */
class PermissiveIncomingRoomKeyRequestEventHandler(
    private val userInfo: UserInfo,
    private val api: MatrixClientServerApiClient,
    private val olmEventHandler: OlmEventHandler,
    private val olmEncryptionService: OlmEncryptionService,
    private val accountStore: AccountStore,
    private val olmStore: OlmCryptoStore,
    private val driver: CryptoDriver,
) : EventHandler {

    private val ownUserId: UserId = userInfo.userId

    override fun startInCoroutineScope(scope: CoroutineScope) {
        // Requests arrive olm-encrypted (spec) and surface decrypted via the
        // olm handler; the sync-stream subscription covers plaintext senders.
        olmEventHandler.subscribe { event ->
            val content = event.decrypted.content
            if (event.decrypted.sender == ownUserId && content is RoomKeyRequestEventContent) {
                answer(content)
            }
        }.unsubscribeOnCompletion(scope)

        api.sync.subscribeEvent(subscriber = ::handleIncomingKeyRequests).unsubscribeOnCompletion(scope)
    }

    private suspend fun handleIncomingKeyRequests(event: ClientEvent.ToDeviceEvent<RoomKeyRequestEventContent>) {
        if (event.sender == ownUserId) answer(event.content)
    }

    /** Mirrors the stock handler's answer path (megolm export + olm-encrypted
     * `m.forwarded_room_key` to the requesting device) minus the trust gate. */
    private suspend fun answer(request: RoomKeyRequestEventContent) {
        val requestingDeviceId = request.requestingDeviceId ?: return
        val body = request.body ?: return
        if (body.algorithm != EncryptionAlgorithm.Megolm) return
        val roomId = body.roomId ?: return
        val session = olmStore.getInboundMegolmSession(body.sessionId, roomId).firstOrNull() ?: return
        val account = accountStore.getAccountAsFlow().firstOrNull() ?: return
        try {
            val exported = driver.megolm.inboundGroupSession
                .fromPickle(session.pickled, driver.key.pickleKey(account.olmPickleKey))
                .use { it.exportAtFirstKnownIndex() }
            val content = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(
                ForwardedRoomKeyEventContent.serializer(),
                buildJsonObject {
                    put("room_id", session.roomId.full)
                    put("sender_key", session.senderKey.value)
                    put("session_id", session.sessionId)
                    put("session_key", exported.base64)
                    put("sender_claimed_ed25519_key", session.senderSigningKey.value)
                    put(
                        "forwarding_curve25519_key_chain",
                        JsonArray(session.forwardingCurve25519KeyChain.map { JsonPrimitive(it.value) }),
                    )
                    put("algorithm", EncryptionAlgorithm.Megolm.name)
                },
            )
            val encrypted = olmEncryptionService.encryptOlm(content, ownUserId, requestingDeviceId).getOrNull()
                ?: return
            api.user.sendToDevice(mapOf(ownUserId to mapOf(requestingDeviceId to encrypted)))
            android.util.Log.i("MatrixRepository", "answered room key request from $requestingDeviceId for session ${body.sessionId}")
        } catch (e: Exception) {
            android.util.Log.w("MatrixRepository", "failed to answer room key request from $requestingDeviceId: ${e.message}")
        }
    }
}
