package com.lightphone.chats.server

import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.EventContent
import de.connect2x.trixnity.core.model.events.PlaintextOlmEvent
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationRequest
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationStep
import de.connect2x.trixnity.core.model.events.m.room.EncryptedToDeviceEventContent.OlmEncryptedToDeviceEventContent
import de.connect2x.trixnity.crypto.olm.OlmEncryptionService

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
 *
 * v5 (2026-08-28): the megolm methods moved to [de.connect2x.trixnity.crypto.olm.MegolmEncryptionService]
 * (no overrides here anymore), `encryptOlm` lost its `forceNewSession` arg and
 * gained a batch overload, `decryptOlm` returns [PlaintextOlmEvent], and
 * `recoverOlm` is new. The batch overload must force plaintext too — v5's
 * megolm key distribution goes through it.
 */
class PlaintextVerificationOlmEncryptionService(
    private val delegate: OlmEncryptionService,
) : OlmEncryptionService {

    private fun shouldForcePlaintext(content: EventContent) =
        content is VerificationStep || content is VerificationRequest

    override suspend fun encryptOlm(
        content: EventContent,
        recipientUserId: UserId,
        recipientDeviceId: String,
    ): Result<OlmEncryptedToDeviceEventContent> {
        android.util.Log.d("MatrixRepository", "encryptOlm(${content::class.simpleName}, $recipientDeviceId)")
        // VerificationStep covers ready/start/accept/key/mac/done/cancel; the
        // request is the odd one out — it implements VerificationRequest, not
        // VerificationStep.
        if (shouldForcePlaintext(content)) {
            android.util.Log.d("MatrixRepository", "encryptOlm: forcing plaintext for ${content::class.simpleName}")
            return Result.failure(
                IllegalStateException("verification events are sent unencrypted (Beeper convention)"),
            )
        }
        return delegate.encryptOlm(content, recipientUserId, recipientDeviceId)
    }

    override suspend fun encryptOlm(
        content: EventContent,
        recipients: Set<Pair<UserId, String>>,
    ): Map<Pair<UserId, String>, Result<OlmEncryptedToDeviceEventContent>> {
        if (shouldForcePlaintext(content)) {
            android.util.Log.d(
                "MatrixRepository",
                "encryptOlm(batch): forcing plaintext for ${content::class.simpleName}",
            )
            val failure = Result.failure<OlmEncryptedToDeviceEventContent>(
                IllegalStateException("verification events are sent unencrypted (Beeper convention)"),
            )
            return recipients.associateWith { failure }
        }
        return delegate.encryptOlm(content, recipients)
    }

    override suspend fun recoverOlm(
        olmRecovery: OlmEncryptionService.OlmRecovery,
    ): Result<OlmEncryptedToDeviceEventContent?> = delegate.recoverOlm(olmRecovery)

    override suspend fun decryptOlm(
        event: ClientEvent.ToDeviceEvent<OlmEncryptedToDeviceEventContent>,
    ): Result<PlaintextOlmEvent<*>> = delegate.decryptOlm(event)
}
