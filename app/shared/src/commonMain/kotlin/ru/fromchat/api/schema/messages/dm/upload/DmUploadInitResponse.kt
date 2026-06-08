package ru.fromchat.api.schema.messages.dm.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmUploadInitResponse(
    @SerialName("upload_id") val uploadId: String,
    @SerialName("chunk_size") val chunkSize: Int,
    val offset: Long = 0L
)