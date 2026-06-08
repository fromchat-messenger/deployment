package ru.fromchat.api.schema.messages.dm.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmUploadChunkRequest(
    val offset: Long,
    @SerialName("data_b64") val dataB64: String
)