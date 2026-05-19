package ru.fromchat.api.outbox

import kotlinx.serialization.Serializable
import ru.fromchat.api.SendDmFile

@Serializable
data class PublicOutboxPayload(
    val content: String,
    val replyToId: Int? = null,
)

@Serializable
data class DmOutboxPayload(
    val recipientId: Int,
    val plaintext: String,
    val clientMessageId: String? = null,
    val replyToId: Int? = null,
    val transportFiles: List<SendDmFile> = emptyList(),
    val uploadedFileIds: List<String> = emptyList(),
)

@Serializable
data class DmAttachmentOutboxPayload(
    val recipientId: Int,
    val plaintext: String,
    val clientMessageId: String,
    val replyToId: Int? = null,
    val fileUri: String,
    val filename: String,
    val fileSizeBytes: Long = 0L,
    val aspectRatio: Float? = null,
    /** Encrypted blob size; used for upload progress after encryption. */
    val encryptedFileSizeBytes: Long = 0L,
    val uploadId: String = "",
)
