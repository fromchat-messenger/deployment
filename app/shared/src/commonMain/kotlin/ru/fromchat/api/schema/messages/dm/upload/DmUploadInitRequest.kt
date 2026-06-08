package ru.fromchat.api.schema.messages.dm.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmUploadInitRequest(
    val filename: String,
    @SerialName("total_size") val totalSize: Long,
    @SerialName("recipient_id") val recipientId: Int,
    @SerialName("chunk_size") val chunkSize: Int? = null
)