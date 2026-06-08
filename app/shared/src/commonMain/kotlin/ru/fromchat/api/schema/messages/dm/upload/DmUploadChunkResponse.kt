package ru.fromchat.api.schema.messages.dm.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmUploadChunkResponse(
    @SerialName("offset_received") val offsetReceived: Long
)