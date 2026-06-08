package ru.fromchat.api.schema.messages.dm.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmUploadStatusResponse(
    @SerialName("upload_id") val uploadId: String,
    val filename: String,
    @SerialName("total_size") val totalSize: Long,
    val offset: Long,
    val complete: Boolean
)