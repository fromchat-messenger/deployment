package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendDmFile(
    @SerialName("encrypted_file_data_b64") val encryptedFileDataB64: String,
    val filename: String,
    @SerialName("file_size") val fileSize: Long
)