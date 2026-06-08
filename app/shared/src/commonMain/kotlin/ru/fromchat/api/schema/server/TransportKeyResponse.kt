package ru.fromchat.api.schema.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransportKeyResponse(
    @SerialName("key_id") val keyId: String,
    @SerialName("public_key_b64") val publicKeyB64: String,
    @SerialName("created_at") val createdAt: Double? = null
)