package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EditDmRequest(
    @SerialName("client_public_key_b64") val clientPublicKeyB64: String,
    @SerialName("transport_nonce_b64") val transportNonceB64: String,
    @SerialName("transport_ciphertext_b64") val transportCiphertextB64: String,
    @SerialName("sender_public_key_b64") val senderPublicKeyB64: String,
    @SerialName("recipient_public_key_b64") val recipientPublicKeyB64: String
)