package ru.fromchat.api.schema.messages.dm.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmUploadCompleteResponse(
    @SerialName("file_id") val fileId: String,
    @SerialName("upload_id") val uploadId: String
)