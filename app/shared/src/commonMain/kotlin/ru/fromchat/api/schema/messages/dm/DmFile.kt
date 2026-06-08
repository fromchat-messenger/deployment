package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmFile(
    val id: Int,
    val name: String,
    val path: String,
    @SerialName("dm_envelope_id") val dmEnvelopeId: Int? = null,
    @SerialName("wrapped_mek_b64") val wrappedMekB64: String? = null,
    @SerialName("nonce_b64") val nonceB64: String? = null
)