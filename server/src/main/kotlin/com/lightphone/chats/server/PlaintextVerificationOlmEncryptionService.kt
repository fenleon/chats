package com.lightphone.chats.server

import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.DecryptedMegolmEvent
import net.folivo.trixnity.core.model.events.DecryptedOlmEvent
import net.folivo.trixnity.core.model.events.EventContent
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.m.key.verification.VerificationRequest
import net.folivo.trixnity.core.model.events.m.key.verification.VerificationStep
import net.folivo.trixnity.core.model.events.m.room.EncryptedMessageEventContent.MegolmEncryptedMessageEventContent
import net.folivo.trixnity.core.model.events.m.room.EncryptedToDeviceEventContent.OlmEncryptedToDeviceEventContent
import net.folivo.trixnity.core.model.events.m.room.EncryptionEventContent
import net.folivo.trixnity.crypto.olm.OlmEncryptionService

/**
 * Delegates to the real [OlmEncryptionService] but refuses to encrypt
 * verification events, so Trixnity's plaintext fallback
 * (`encryptOlm(...).getOrNull() ?: step`) sends the whole to-device
 * verification chain unencrypted.
 *
 * Why (2026-08-13): Beeper's SDK is built on mautrix-go, which sends every
 * verification event (request, ready, start, accept, key, mac, done) as a
 * PLAINTEXT to-device message and drops incoming ENCRYPTED verification
 * events ("Unhandled encrypted to-device event" — verified live on the
 * account's desktop client: the encrypted request and encrypted SAS accept
 * were dropped, the plaintext request was handled). The Matrix spec does not
 * require encrypting these events, so matching Beeper's plaintext convention
 * is spec-legal and is the only way the SAS exchange completes against Beeper
 * clients. Room keys, megolm and ordinary olm traffic are unaffected.
 */
class PlaintextVerificationOlmEncryptionService(
    private val delegate: OlmEncryptionService,
) : OlmEncryptionService {
    override suspend fun encryptOlm(
        content: EventContent,
        userId: UserId,
        deviceId: String,
        forceNewSession: Boolean,
    ): Result<OlmEncryptedToDeviceEventContent> {
        android.util.Log.d("MatrixRepository", "encryptOlm(${content::class.simpleName}, $deviceId)")
        // VerificationStep covers ready/start/accept/key/mac/done/cancel; the
        // request is the odd one out — it implements VerificationRequest, not
        // VerificationStep.
        if (content is VerificationStep || content is VerificationRequest) {
            android.util.Log.d("MatrixRepository", "encryptOlm: forcing plaintext for ${content::class.simpleName}")
            return Result.failure(
                IllegalStateException("verification events are sent unencrypted (Beeper convention)"),
            )
        }
        return delegate.encryptOlm(content, userId, deviceId, forceNewSession)
    }

    override suspend fun decryptOlm(
        event: ClientEvent.ToDeviceEvent<OlmEncryptedToDeviceEventContent>,
    ): Result<DecryptedOlmEvent<*>> = delegate.decryptOlm(event)

    override suspend fun encryptMegolm(
        content: MessageEventContent,
        roomId: RoomId,
        settings: EncryptionEventContent,
    ): Result<MegolmEncryptedMessageEventContent> = delegate.encryptMegolm(content, roomId, settings)

    override suspend fun decryptMegolm(
        encryptedEvent: ClientEvent.RoomEvent<MegolmEncryptedMessageEventContent>,
    ): Result<DecryptedMegolmEvent<*>> = delegate.decryptMegolm(encryptedEvent)
}
